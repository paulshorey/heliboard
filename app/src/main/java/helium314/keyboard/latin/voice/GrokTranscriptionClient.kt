// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.utils.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Raw PCM WebSocket STT. Only explicit pre-audio HTTP rejections may reconnect. */
class GrokTranscriptionClient {
    companion object {
        private const val TAG = "GrokTranscription"
        const val MODEL = "grok-voice-transcribe-2.0"
        // App policy, not a claimed provider session limit. New recordings require a tap.
        const val SESSION_CAPTURE_LIMIT_MS = 30 * 60_000L
        const val MAX_USER_VOCABULARY_TERMS = 100
        const val CREDITS_DEPLETED_ERROR = "Grok API credits are exhausted. Add API credits in console.x.ai."
        internal const val MAX_SOCKET_BYTES = 256 * 1024L
        internal const val START_TIMEOUT_MS = 12_000L
        internal const val STREAMING_ENDPOINT = "https://api.x.ai/v1/stt"
        private val LANGUAGES = setOf("ar", "bs", "bg", "yue", "ca", "zh", "hr", "cs", "da", "nl",
            "en", "fil", "fi", "fr", "de", "el", "hi", "hu", "id", "it", "ja", "ko", "mk", "ms",
            "nb", "fa", "pl", "pt", "ro", "ru", "sk", "es", "sv", "th", "tr", "uk", "ur", "vi")
        internal fun defaultVocabulary() = listOf("HeliBoard", "Grok", "GitHub", "Android", "API")
        internal fun resolveLanguageCode(tag: String?): String? {
            val normalized = tag?.replace('_', '-')?.lowercase(Locale.ROOT) ?: return null
            if (normalized in setOf("es-es", "pt-pt", "ar-ae", "ar-sa")) return normalized
            val language = normalized.substringBefore('-').let { when (it) { "tl" -> "fil"; "no" -> "nb"; else -> it } }
            return language.takeIf { it in LANGUAGES }
        }
        internal fun buildSessionConfig(languageTag: String?, autoDetectLanguage: Boolean,
            smartTurn: Double, turnTimeoutMs: Int, format: Boolean,
            userVocabulary: List<String>, editorContext: String?) = SessionConfig(
            if (autoDetectLanguage) null else resolveLanguageCode(languageTag),
            smartTurn.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.7,
            turnTimeoutMs.coerceIn(1, 5000), format,
            VoiceContextVocabulary.build(userVocabulary, defaultVocabulary(), editorContext, 100)
                .map { it.take(50) }.distinct(),
        )
    }

    internal data class SessionConfig(val language: String?, val smartTurn: Double,
        val turnTimeoutMs: Int, val format: Boolean, val keyterms: List<String>)
    interface StreamingCallback {
        fun onStreamReady()
        fun onHandshakeRestarted()
        fun onTranscriptionResult(segment: TranscriptSegment)
        fun onInterimTranscription()
        /** Progress may settle deliberately filtered speakers without an editor insertion. */
        fun onServerResponse(hasTranscriptText: Boolean)
        fun onSessionExpiring(timeLeftMs: Long)
        fun onStreamError(error: String)
        fun onStreamClosed()
    }
    internal enum class AudioSendResult { ACCEPTED, BACKPRESSURE, FAILED }
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(5, TimeUnit.SECONDS).build()
    internal var socketFactory: WebSocket.Factory = http
    internal var streamingEndpoint = STREAMING_ENDPOINT
    private var socket: WebSocket? = null
    private var callback: StreamingCallback? = null
    private var generation = 0L
    private var attempt = 0L
    private var ready = false
    private var finishing = false
    private var done = false
    private var retries = 0
    private var deadline = 0L
    private var submittedBytes = 0L
    private var nextAudioAt = 0L
    private var bytesAfterSpeech = 0L
    private var speechSent = false
    private var assembler = GrokTranscriptAssembler()
    private data class Control(val text: String, val queuedAt: Long)
    private val controls = ArrayDeque<Control>()
    private var pump: Runnable? = null
    private var startupTimer: Runnable? = null
    private var retryTimer: Runnable? = null

    internal fun startStreaming(apiKey: String, config: SessionConfig, callback: StreamingCallback) {
        cancelAll()
        this.callback = callback
        val key = apiKey.trim().removePrefix("Bearer ").trim()
        if (key.isBlank() || key.length > 4096 || key.any { it.isISOControl() }) {
            fail("Invalid Grok API key. Check Settings."); return
        }
        deadline = SystemClock.elapsedRealtime() + START_TIMEOUT_MS
        val token = generation
        startupTimer = Runnable { if (token == generation && !ready) fail("Grok connection timed out. Tap the mic to restart.") }
            .also { handler.postDelayed(it, START_TIMEOUT_MS) }
        open(key, config)
    }

    private fun open(key: String, config: SessionConfig) {
        val token = generation
        val connection = ++attempt
        val url = streamingEndpoint.replaceFirst("wss:", "https:").replaceFirst("ws:", "http:")
            .toHttpUrl().newBuilder().addQueryParameter("model", MODEL)
            .addQueryParameter("encoding", "pcm").addQueryParameter("sample_rate", "16000")
            .addQueryParameter("diarize", "true").addQueryParameter("interim_results", "true")
            .addQueryParameter("smart_turn", config.smartTurn.toString())
            .addQueryParameter("smart_turn_timeout", config.turnTimeoutMs.toString())
            .addQueryParameter("filler_words", "false").apply {
                config.language?.let {
                    addQueryParameter("language", it)
                    addQueryParameter("format", config.format.toString())
                }
                config.keyterms.take(100).forEach { addQueryParameter("keyterm", it.take(50)) }
            }.build()
        Log.i(TAG, "opening model=$MODEL attempt=$retries smartTurn=${config.smartTurn} timeoutMs=${config.turnTimeoutMs}")
        socket = socketFactory.newWebSocket(Request.Builder().url(url)
            .header("Authorization", "Bearer $key").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = post(token, connection) {
                try {
                    require(text.length <= 1024 * 1024) { "Server response is too large" }
                    receive(JSONObject(text))
                } catch (error: Exception) {
                    val reason = if (error is GrokTranscriptException) error.message else "invalid server message schema"
                    Log.w(TAG, "protocol failure reason=$reason submittedBytes=$submittedBytes")
                    // Never expose payloads, provider error echoes, keys, or dictated words.
                    fail("Grok returned invalid or unresolved transcription data. Restart and speak first.")
                }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = post(token, connection) {
                fail("Grok returned an unexpected audio response.")
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post(token, connection) {
                if (!done || code != 1000) fail("Grok connection ended before dictation finished (code $code). Tap the mic to restart.")
                else {
                    val target = this@GrokTranscriptionClient.callback
                    cancelAll(); target?.onStreamClosed()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post(token, connection) {
                val code = response?.code
                val body = try { response?.peekBody(4096)?.string().orEmpty().lowercase(Locale.ROOT) } catch (_: Exception) { "" }
                val credits = body.contains("credit") && (body.contains("insufficient") || body.contains("exhaust") || body.contains("depleted"))
                val retryDelay = response?.header("Retry-After")?.toLongOrNull()?.let { it.coerceIn(0, 86400) * 1000 }
                    ?: if (retries == 0) 500L else 1500L
                if (!ready && submittedBytes == 0L && !credits && code in listOf(429, 503) && retries < 2 &&
                    SystemClock.elapsedRealtime() + retryDelay < deadline) {
                    retries++
                    attempt++ // Fence all callbacks from the rejected attempt immediately.
                    this@GrokTranscriptionClient.callback?.onHandshakeRestarted()
                    retryTimer = Runnable { if (token == generation) open(key, config) }
                        .also { handler.postDelayed(it, retryDelay) }
                } else fail(when {
                    credits || code == 402 -> CREDITS_DEPLETED_ERROR
                    code == 401 || code == 403 -> "Grok rejected the API key or account. Check Settings and console.x.ai."
                    code == 429 -> "Grok rate limit reached. Wait before starting again."
                    code == 400 || code == 404 -> "Grok rejected the model or settings. Check API access in console.x.ai."
                    code != null -> "Grok connection failed (HTTP $code). Tap the mic to restart."
                    else -> "Grok connection lost or unavailable. Check internet access and tap the mic to restart."
                })
            }
        })
    }

    private fun receive(event: JSONObject) {
        when (event.getString("type")) {
            "transcript.created" -> {
                require(!ready && !done) { "Duplicate readiness" }
                ready = true
                startupTimer?.let(handler::removeCallbacks); startupTimer = null
                Log.i(TAG, "ready")
                callback?.onStreamReady()
            }
            "transcript.partial", "transcript.done" -> {
                require(ready && !done) { "Unexpected transcript" }
                val isDone = event.getString("type") == "transcript.done"
                require(!isDone || finishing && controls.isEmpty()) { "Unexpected EOF" }
                val update = assembler.accept(event, submittedBytes)
                val token = generation
                if (!isDone && !event.getBoolean("is_final") && update.progressed) callback?.onInterimTranscription()
                for (segment in update.segments) {
                    if (token != generation) return
                    callback?.onTranscriptionResult(segment)
                }
                if (token != generation) return
                if (update.progressed || isDone) callback?.onServerResponse(isDone || event.optBoolean("is_final"))
                if (token != generation) return
                if (update.filteredWords > 0) Log.i(TAG, "filteredWords=${update.filteredWords} settledBytes=${assembler.settledBytes}")
                if (isDone) {
                    done = true
                    Log.i(TAG, "done submittedBytes=$submittedBytes settledBytes=${assembler.settledBytes}")
                    // onClosing may already have acknowledged the peer close on OkHttp's
                    // thread. close(false) then means the close was queued, not failure.
                    socket?.close(1000, null)
                }
            }
            "error" -> {
                val detail = event.optString("message").lowercase(Locale.ROOT)
                fail(when {
                    detail.contains("credit") && (detail.contains("insufficient") || detail.contains("exhaust") || detail.contains("depleted")) -> CREDITS_DEPLETED_ERROR
                    detail.contains("unauthoriz") || detail.contains("api key") -> "Grok rejected the API key. Check Settings and console.x.ai."
                    detail.contains("rate limit") -> "Grok rate limit reached. Wait before starting again."
                    else -> "Grok reported a transcription error. Check API access and restart dictation."
                })
            }
            else -> error("Unknown server event")
        }
    }

    internal fun offerAudioChunk(pcm: ByteArray, containsSpeech: Boolean): AudioSendResult {
        val target = socket ?: return AudioSendResult.FAILED
        if (!ready || finishing || done || pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > 3200)
            return AudioSendResult.FAILED
        if (controls.isNotEmpty() || target.queueSize() + pcm.size > MAX_SOCKET_BYTES) return AudioSendResult.BACKPRESSURE
        val now = SystemClock.elapsedRealtime()
        // Never burst a startup backlog: pace captured PCM with one 100 ms frame of headroom.
        if (now + 100 < nextAudioAt) return AudioSendResult.BACKPRESSURE
        if (!target.send(pcm.toByteString())) return AudioSendResult.FAILED
        nextAudioAt = maxOf(now, nextAudioAt) + (pcm.size / 32L).coerceAtLeast(1)
        submittedBytes += pcm.size
        if (containsSpeech) { speechSent = true; bytesAfterSpeech = 0 }
        else if (speechSent) bytesAfterSpeech += pcm.size
        return AudioSendResult.ACCEPTED
    }

    internal fun finalizeTurn(): Boolean = enqueueControl("finalize")
    /** Local VAD is advisory; forcing a turn here would override semantic endpointing. */
    internal fun noteLocalSilence(): Boolean = ready && !finishing && !done
    internal fun finishStreaming(): Boolean {
        if (finishing) return false
        val accepted = enqueueControl("audio.done")
        if (accepted) finishing = true
        return accepted
    }
    private fun enqueueControl(type: String): Boolean {
        if (!ready || done || controls.size >= 64) return false
        controls.addLast(Control(JSONObject().put("type", type).toString(), SystemClock.elapsedRealtime()))
        pumpControls()
        return callback != null
    }
    private fun pumpControls() {
        pump?.let(handler::removeCallbacks); pump = null
        val target = socket ?: return
        while (controls.isNotEmpty()) {
            val control = controls.first()
            if (SystemClock.elapsedRealtime() - control.queuedAt >= 30_000) {
                fail("Grok audio finalization stalled. Tap the mic to restart."); return
            }
            val text = control.text
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (target.queueSize() + bytes > MAX_SOCKET_BYTES) {
                val token = generation
                pump = Runnable { if (token == generation) pumpControls() }.also { handler.postDelayed(it, 25) }
                return
            }
            if (!target.send(text)) { fail("Grok could not finalize the queued audio."); return }
            if (speechSent) bytesAfterSpeech += bytes
            controls.removeFirst()
        }
    }

    internal fun queuedFrameBytes(): Long = socket?.queueSize() ?: 0
    internal fun hasQueuedFrames() = controls.isNotEmpty() || queuedFrameBytes() > 0
    internal fun hasQueuedSpeechFrames() = speechSent && queuedFrameBytes() > bytesAfterSpeech
    internal fun hasPendingSpeech(capturedSpeechEndBytes: Long) = assembler.hasPendingWords ||
        assembler.settledBytes + 320 < capturedSpeechEndBytes

    internal fun cancelAll() {
        generation++; attempt++
        listOf(pump, startupTimer, retryTimer).forEach { it?.let(handler::removeCallbacks) }
        pump = null; startupTimer = null; retryTimer = null
        callback = null
        val old = socket; socket = null; old?.cancel()
        ready = false; finishing = false; done = false; retries = 0
        submittedBytes = 0; nextAudioAt = 0; bytesAfterSpeech = 0; speechSent = false
        controls.clear(); assembler = GrokTranscriptAssembler()
    }
    private fun fail(message: String) {
        val target = callback
        Log.w(TAG, "failure ready=$ready submittedBytes=$submittedBytes retries=$retries")
        cancelAll(); target?.onStreamError(message)
    }
    private fun post(token: Long, connection: Long, action: () -> Unit) {
        handler.post { if (token == generation && connection == attempt) action() }
    }
}
