// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.settings.TranscriptionPreferences.MaiConfig
import helium314.keyboard.latin.utils.Log
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** Microsoft MAI-Transcribe-2-Streaming's Realtime transcription protocol. */
class MaiTranscriptionClient internal constructor(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
    private val endpointOverride: String? = null,
    private val completionTimeoutMs: Long = 60_000L,
) {
    companion object {
        private const val TAG = "MaiTranscription"
        private const val HANDSHAKE_TIMEOUT_MS = 30_000L
        private const val MAX_SOCKET_QUEUE_BYTES = 256_000L
        private const val MAX_PENDING_COMMITS = 64
        const val MODEL = "MAI-Transcribe-2-Streaming"
        // Drain and replace the connection before the service's one-hour limit.
        const val SESSION_ROTATE_AFTER_MS = 55 * 60 * 1000L
        private const val TRANSCRIPTION_EVENT = "conversation.item.input_audio_transcription."

        fun buildStreamingUrl(endpoint: String): String {
            val uri = try { URI(endpoint.trim()) } catch (_: Exception) {
                throw IllegalArgumentException("Enter a valid Azure resource endpoint in Settings.")
            }
            require(uri.scheme in setOf("https", "wss") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.path in setOf("", "/") &&
                uri.query == null && uri.fragment == null) {
                "Azure endpoint must be an HTTPS or WSS resource root URL, without a path or query."
            }
            return "wss://${uri.rawAuthority}/mai/v1/realtime?intent=transcription"
        }

        internal fun resolveLanguage(languageTag: String?, autoDetect: Boolean): String? {
            if (autoDetect) return null
            val language = languageTag?.replace('_', '-')?.substringBefore('-')?.lowercase()
            return language?.takeIf { it.isNotBlank() && it != "und" && it != "zz" }
        }

        internal fun buildSessionUpdate(deployment: String, language: String?): String =
            JSONObject().put("type", "session.update").put("session", JSONObject()
                .put("type", "transcription").put("audio", JSONObject().put("input", JSONObject()
                    .put("format", JSONObject().put("type", "audio/pcm").put("rate", VoiceRecorder.SAMPLE_RATE))
                    .put("transcription", JSONObject().put("model", deployment).put("language", language ?: JSONObject.NULL))
                    .put("turn_detection", JSONObject.NULL).put("noise_reduction", JSONObject.NULL)))).toString()
    }

    interface StreamingCallback {
        fun onStreamReady()
        fun onTranscriptionResult(segment: TranscriptSegment)
        fun onPendingProcessingChanged()
        fun onStreamError(error: String, retryable: Boolean)
        fun onStreamClosed()
    }

    private enum class Phase { CLOSED, CREATED, CONFIGURING, READY }
    private data class Commit(
        val sentAtMs: Long,
        var itemId: String? = null,
        var transcript: String? = null,
    )

    // All protocol state and callbacks run on the main looper. OkHttp only posts events here.
    private val handler = Handler(Looper.getMainLooper())
    private var connectionToken = 0L
    private var socket: WebSocket? = null
    private var callback: StreamingCallback? = null
    private var phase = Phase.CLOSED
    private var finishing = false
    private var bytesSinceCommit = 0L
    private val commits = ArrayDeque<Commit>()
    private val completedItemIds = HashSet<String>()
    private var timeout: Runnable? = null

    val hasPendingProcessing: Boolean get() = bytesSinceCommit > 0 || commits.isNotEmpty()

    fun startStreaming(config: MaiConfig, languageTag: String?, callback: StreamingCallback) {
        cancelAll()
        this.callback = callback
        val url = try { endpointOverride ?: buildStreamingUrl(config.endpoint) } catch (e: IllegalArgumentException) {
            fail(e.message ?: "Invalid Azure endpoint.", retryable = false)
            return
        }
        if (config.apiKey.isBlank() || config.deployment.isBlank()) {
            fail("Configure the Azure API key and MAI model deployment in Settings.", retryable = false)
            return
        }
        val request = try {
            Request.Builder().url(url).header("api-key", config.apiKey).build()
        } catch (_: IllegalArgumentException) {
            fail("Invalid Azure endpoint or API key in Settings.", retryable = false)
            return
        }
        val token = connectionToken
        phase = Phase.CREATED
        armTimeout(HANDSHAKE_TIMEOUT_MS, "MAI session configuration timed out.")
        socket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                post(token) { receive(text, config, languageTag) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                post(token) { fail("MAI returned an unexpected binary event.", retryable = false) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                post(token) {
                    val code = response?.code
                    val message = when (code) {
                        400 -> "MAI rejected the request. Check the Azure endpoint and deployment in Settings."
                        401, 403 -> "Azure authentication failed. Check the API key and resource access in Settings."
                        404 -> "MAI endpoint or deployment was not found. Check Settings."
                        429 -> "MAI rate limited the request. Try again later."
                        null -> "Could not connect to MAI. Check your internet connection."
                        else -> "MAI connection failed (HTTP $code)."
                    }
                    fail(message, retryable = code == null || code == 429 || code >= 500)
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                post(token) { closedByServer() }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                post(token) { closedByServer() }
            }
        })
        Log.i(TAG, "VOICE_STEP_3 opening MAI transcription session")
    }

    fun sendAudioChunk(pcmData: ByteArray): Boolean {
        if (phase != Phase.READY || finishing) return false
        if (pcmData.isEmpty() || pcmData.size % 2 != 0) {
            fail("Microphone audio must contain complete PCM16 samples.", retryable = false)
            return false
        }
        if ((socket?.queueSize() ?: 0) > MAX_SOCKET_QUEUE_BYTES) {
            fail("Voice upload fell behind. Stop and try again on a faster connection.", retryable = false)
            return false
        }
        if (!send(JSONObject().put("type", "input_audio_buffer.append")
                .put("audio", pcmData.toByteString().base64()).toString())) return false
        bytesSinceCommit += pcmData.size
        return true
    }

    /** Commit audio at local silence or mic pause. An empty buffer needs no commit. */
    fun finalizeTurn(): Boolean {
        if (phase != Phase.READY || bytesSinceCommit == 0L) return false
        if (commits.size >= MAX_PENDING_COMMITS) {
            fail("MAI has too many pending transcripts. Stop and try again.", retryable = false)
            return false
        }
        // Register before sending so even an immediate completion has a matching commit.
        commits.addLast(Commit(SystemClock.elapsedRealtime()))
        bytesSinceCommit = 0
        if (!send("""{"type":"input_audio_buffer.commit"}""")) return false
        armCompletionTimeout()
        callback?.onPendingProcessingChanged()
        Log.i(TAG, "VOICE_RESPONSE awaiting final transcript (${commits.size} commits)")
        return true
    }

    /** Keep receiving until every commit has a completed result, then close. */
    fun finishStreaming() {
        if (phase != Phase.READY || finishing) return
        finalizeTurn()
        if (phase != Phase.READY) return
        finishing = true
        closeIfDrained()
    }

    fun cancelAll() {
        connectionToken++
        clearTimeout()
        socket?.cancel()
        socket = null
        callback = null
        phase = Phase.CLOSED
        finishing = false
        bytesSinceCommit = 0
        commits.clear()
        completedItemIds.clear()
    }

    private fun receive(message: String, config: MaiConfig, languageTag: String?) {
        val event = try { JSONObject(message) } catch (_: Exception) {
            fail("MAI returned an invalid transcription event.", retryable = false)
            return
        }
        when (val type = event.optString("type")) {
            "session.created" -> if (phase == Phase.CREATED) {
                phase = Phase.CONFIGURING
                send(buildSessionUpdate(config.deployment, resolveLanguage(languageTag, config.autoDetectLanguage)))
            }
            "session.updated" -> if (phase == Phase.CONFIGURING) {
                phase = Phase.READY
                clearTimeout()
                Log.i(TAG, "VOICE_RESPONSE MAI session ready")
                callback?.onStreamReady()
            }
            "input_audio_buffer.committed" -> {
                val id = event.optString("item_id").takeIf { it.isNotEmpty() }
                if (id != null && id !in completedItemIds && commits.none { it.itemId == id }) {
                    commits.firstOrNull { it.itemId == null }?.itemId = id
                }
                // This is an acknowledgement, not a completed transcript.
            }
            "${TRANSCRIPTION_EVENT}completed" -> acceptCompleted(event)
            "${TRANSCRIPTION_EVENT}delta", "${TRANSCRIPTION_EVENT}intermediate" -> {
                // Both are intentionally held at the service: insertion waits for full completed
                // segments so IME punctuation/casing cleanup never runs on partial words.
            }
            "error", "${TRANSCRIPTION_EVENT}failed" -> {
                val code = event.optJSONObject("error")?.optString("code").orEmpty()
                // Never log raw server messages: they can echo credentials or dictated text.
                val messageText = when (code) {
                    "invalid_api_key", "unauthorized", "authentication_error" -> "Azure authentication failed. Check Settings."
                    "model_not_found", "deployment_not_found" -> "MAI deployment was not found. Check Settings."
                    else -> "MAI rejected the transcription session. Check the deployment and configuration in Settings."
                }
                fail(messageText, retryable = false)
            }
            else -> Log.d(TAG, "MAI event ignored (${type.take(80)})")
        }
    }

    private fun acceptCompleted(event: JSONObject) {
        val id = event.optString("item_id").takeIf { it.isNotEmpty() }
        if (id != null && id in completedItemIds) return
        val commit = if (id != null) {
            commits.firstOrNull { it.itemId == id }
                ?: commits.firstOrNull { it.itemId == null }
        } else commits.firstOrNull { it.transcript == null }
        if (commit == null || commit.transcript != null) return
        if (!event.has("transcript") || event.isNull("transcript")) {
            fail("MAI returned a completion without transcript text.", retryable = false)
            return
        }
        commit.itemId = id ?: commit.itemId
        commit.transcript = event.getString("transcript")
        if (id != null) completedItemIds.add(id)
        val token = connectionToken
        while (commits.firstOrNull()?.transcript != null) {
            val finished = commits.removeFirst()
            val text = finished.transcript!!.trim()
            if (text.isNotEmpty()) {
                Log.i(TAG, "VOICE_STEP_4 MAI completed transcript (${text.length} chars)")
                callback?.onTranscriptionResult(TranscriptSegment(text,
                    text.first() in ",.!?;:%)]}、。，！？：；…"))
                if (token != connectionToken) return
            }
        }
        armCompletionTimeout()
        callback?.onPendingProcessingChanged()
        if (token == connectionToken) closeIfDrained()
    }

    private fun send(message: String): Boolean {
        if (socket?.send(message) == true) return true
        fail("MAI connection was interrupted while uploading audio.", retryable = true)
        return false
    }

    private fun closeIfDrained() {
        if (!finishing || hasPendingProcessing || phase == Phase.CLOSED) return
        socket?.close(1000, "client_stop")
        socket = null // let OkHttp complete the close handshake asynchronously
        val listener = callback
        cancelAll()
        listener?.onStreamClosed()
    }

    private fun closedByServer() {
        if (phase == Phase.CLOSED) return
        fail("MAI closed the transcription connection.", retryable = true)
    }

    private fun fail(message: String, retryable: Boolean) {
        val pending = hasPendingProcessing
        val listener = callback
        cancelAll()
        val description = if (pending) "$message The last voice segment could not be finalized." else message
        Log.e(TAG, description)
        // Audio already accepted by a broken socket cannot safely be replayed. Surface
        // incomplete dictation instead of silently reconnecting and losing a phrase.
        listener?.onStreamError(description, retryable && !pending)
    }

    private fun armCompletionTimeout() {
        clearTimeout()
        val oldest = commits.firstOrNull() ?: return
        val remaining = completionTimeoutMs - (SystemClock.elapsedRealtime() - oldest.sentAtMs)
        armTimeout(remaining.coerceAtLeast(0), "MAI timed out waiting for the final transcript.")
    }

    private fun armTimeout(delayMs: Long, message: String) {
        clearTimeout()
        val token = connectionToken
        timeout = Runnable { if (token == connectionToken) fail(message, retryable = true) }
            .also { handler.postDelayed(it, delayMs) }
    }

    private fun clearTimeout() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
    }

    private fun post(token: Long, action: () -> Unit) {
        handler.post { if (token == connectionToken) action() }
    }
}
