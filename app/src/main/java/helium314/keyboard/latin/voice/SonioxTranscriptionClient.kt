// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Client for Soniox real-time speech-to-text over WebSocket.
 *
 * Protocol overview (https://soniox.com/docs/stt/api-reference/websocket-api):
 * 1. Open the socket. Authentication is done in the body, not in headers.
 * 2. Send a single JSON config text frame as the first message.
 *    It must include `api_key`, `model`, and (for raw PCM) `audio_format`,
 *    `sample_rate`, `num_channels`.
 * 3. Stream binary PCM frames.
 * 4. Receive JSON responses, each with a `tokens` array. Tokens with
 *    `is_final: true` are confirmed and never repeated. Non-final tokens
 *    update on every response and we drop them — the IME only commits
 *    text that won't change.
 * 5. To gracefully end, send an empty WebSocket frame. The server flushes
 *    any remaining tokens and emits `{"finished": true}`, then closes.
 */
class SonioxTranscriptionClient {

    companion object {
        private const val TAG = "SonioxTranscription"
        private const val STREAMING_URL = "wss://stt-rt.soniox.com/transcribe-websocket"

        private const val MODEL = "stt-rt-v4"

        private const val FINALIZE_CLOSE_GRACE_MS = 8_000L

        // Soniox's documented bounds for max_endpoint_delay_ms.
        internal const val MIN_MAX_ENDPOINT_DELAY_MS = 500
        internal const val MAX_MAX_ENDPOINT_DELAY_MS = 3000
        internal const val DEFAULT_MAX_ENDPOINT_DELAY_MS = 2000

        private val PUNCTUATION_ATTACHING_TO_PREVIOUS = setOf(
            '.', ',', '!', '?', ':', ';', ')', ']', '}', '%'
        )

        /**
         * Soniox emits special control tokens in the same `tokens` array as real
         * transcript text:
         *  - `<end>` marks the end of an utterance when endpoint detection is on.
         *  - `<fin>` marks the end of a manually-flushed segment.
         * The Soniox SDKs filter these via `filterSpecialTokens()`; raw WebSocket
         * consumers must do it themselves or the markers leak into the user's
         * editor as literal text.
         */
        private val STREAM_MARKERS: Set<String> = setOf("<end>", "<fin>")

        /**
         * Maximum characters of editor context sent as `context.text`. Soniox's
         * documented context limit is ~10,000 characters / 8,000 tokens for the
         * entire context object; staying well under that leaves headroom for
         * `terms`.
         */
        internal const val MAX_CONTEXT_TEXT_CHARS = 4000

        /** Hard cap on the number of user-defined custom terms. */
        internal const val MAX_USER_CONTEXT_TERMS = 200

        internal data class SessionConfig(
            val languageHint: String?,
            val enableEndpointDetection: Boolean,
            val maxEndpointDelayMs: Int,
            val diarizationEnabled: Boolean,
            val contextTerms: List<String>,
            val contextText: String?
        )

        internal fun buildSessionConfig(
            languageTag: String?,
            enableEndpointDetection: Boolean,
            maxEndpointDelayMs: Int,
            diarizationEnabled: Boolean,
            contextTerms: List<String> = defaultContextTerms(),
            customContextTerms: List<String> = emptyList(),
            contextText: String? = null
        ): SessionConfig {
            val mergedTerms = (contextTerms + customContextTerms)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            return SessionConfig(
                languageHint = normalizeLanguageHint(languageTag),
                enableEndpointDetection = enableEndpointDetection,
                maxEndpointDelayMs = maxEndpointDelayMs.coerceIn(
                    MIN_MAX_ENDPOINT_DELAY_MS,
                    MAX_MAX_ENDPOINT_DELAY_MS
                ),
                diarizationEnabled = diarizationEnabled,
                contextTerms = mergedTerms,
                contextText = sanitizeContextText(contextText)
            )
        }

        internal fun defaultContextTerms(): List<String> = listOf(
            "HeliBoard",
            "Soniox",
            "Kubernetes",
            "API",
            "gnocchi"
        )

        /**
         * Trim incoming editor context to the most recent [MAX_CONTEXT_TEXT_CHARS]
         * characters and collapse it to a single non-blank string. Returns null
         * when the context is empty after sanitization.
         */
        internal fun sanitizeContextText(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val condensed = raw.replace("\u0000", "").trim()
            if (condensed.isEmpty()) return null
            return if (condensed.length <= MAX_CONTEXT_TEXT_CHARS) {
                condensed
            } else {
                condensed.substring(condensed.length - MAX_CONTEXT_TEXT_CHARS)
            }
        }

        internal fun buildStartConfigMessage(apiKey: String, config: SessionConfig): String {
            val payload = JSONObject()
                .put("api_key", apiKey)
                .put("model", MODEL)
                .put("audio_format", "pcm_s16le")
                .put("sample_rate", VoiceRecorder.SAMPLE_RATE)
                .put("num_channels", 1)
                .put("enable_endpoint_detection", config.enableEndpointDetection)
                .put("max_endpoint_delay_ms", config.maxEndpointDelayMs)
                .put("enable_speaker_diarization", config.diarizationEnabled)

            if (config.languageHint != null) {
                payload.put(
                    "language_hints",
                    JSONArray().apply { put(config.languageHint) }
                )
            }
            val contextObject = JSONObject()
            if (config.contextTerms.isNotEmpty()) {
                contextObject.put(
                    "terms",
                    JSONArray().apply {
                        config.contextTerms.forEach { put(it) }
                    }
                )
            }
            if (!config.contextText.isNullOrBlank()) {
                contextObject.put("text", config.contextText)
            }
            if (contextObject.length() > 0) {
                payload.put("context", contextObject)
            }
            return payload.toString()
        }

        private fun normalizeLanguageHint(languageTag: String?): String? {
            val normalizedTag = languageTag
                ?.trim()
                ?.replace('_', '-')
                .orEmpty()
            if (normalizedTag.isBlank()) return null
            if (normalizedTag == "und" || normalizedTag == "zz") return null
            val locale = Locale.forLanguageTag(normalizedTag)
            val languageCode = locale.language.orEmpty()
            return when {
                languageCode.isBlank() -> null
                languageCode == "und" -> null
                else -> languageCode.lowercase(Locale.US)
            }
        }

        /**
         * Build a [TranscriptSegment] from a single Soniox response's `tokens`
         * array. Returns `null` if the response contained no usable final
         * tokens for the active speaker.
         *
         * Soniox encodes inter-word whitespace inside token text (e.g. tokens
         * arrive as `"Hello"`, `" world"`, `"."`). Concatenating the text in
         * order, then trimming, yields the correct rendered text.
         */
        internal fun buildSegmentFromFinalTokens(
            tokens: org.json.JSONArray?,
            primarySpeaker: String?,
            diarizationEnabled: Boolean
        ): SegmentResult {
            if (tokens == null || tokens.length() == 0) {
                return SegmentResult(segment = null, observedSpeaker = primarySpeaker)
            }

            val builder = StringBuilder()
            var lockedSpeaker: String? = primarySpeaker

            for (i in 0 until tokens.length()) {
                val token = tokens.optJSONObject(i) ?: continue
                if (!token.optBoolean("is_final", false)) continue
                val text = token.optString("text", "")
                if (text.isEmpty()) continue
                if (text in STREAM_MARKERS) continue

                if (diarizationEnabled) {
                    val speaker = token.optString("speaker", "")
                    if (speaker.isNotEmpty()) {
                        if (lockedSpeaker == null) {
                            lockedSpeaker = speaker
                        } else if (speaker != lockedSpeaker) {
                            continue
                        }
                    }
                }

                builder.append(text)
            }

            val trimmed = builder.toString().trim()
            if (trimmed.isEmpty()) {
                return SegmentResult(segment = null, observedSpeaker = lockedSpeaker)
            }
            val attachesToPrevious = trimmed.first() in PUNCTUATION_ATTACHING_TO_PREVIOUS
            return SegmentResult(
                segment = TranscriptSegment(text = trimmed, attachesToPrevious = attachesToPrevious),
                observedSpeaker = lockedSpeaker
            )
        }

        internal data class SegmentResult(
            val segment: TranscriptSegment?,
            val observedSpeaker: String?
        )

        internal fun buildErrorDescription(json: JSONObject): String {
            val code = json.optString("error_code", "")
            val type = json.optString("error_type", "")
            val message = json.optString("error_message", "")
            val requestId = json.optString("request_id", "")
            val summary = listOf(type, code)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val description = when {
                summary.isBlank() && message.isBlank() -> "Soniox reported an unknown error"
                summary.isBlank() -> message
                message.isBlank() -> summary
                else -> "$summary: $message"
            }
            return if (requestId.isBlank()) {
                description
            } else {
                "$description (request_id=$requestId)"
            }
        }
    }

    interface StreamingCallback {
        /** Recognition session is configured and ready to receive audio chunks. */
        fun onStreamReady()

        /** Finalized transcription text rebuilt from `is_final: true` tokens. */
        fun onTranscriptionResult(segment: TranscriptSegment)

        /** Streaming failed and this stream can no longer be used. */
        fun onStreamError(error: String)

        /** Stream closed (gracefully or remotely). */
        fun onStreamClosed()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var callback: StreamingCallback? = null

    @Volatile
    private var activeConnectionToken = 0L

    @Volatile
    private var isClosing = false

    @Volatile
    private var isRecognitionReady = false

    @Volatile
    private var pendingFinalizeCloseRunnable: Runnable? = null

    @Volatile
    private var diarizationEnabled = false

    @Volatile
    private var primarySpeaker: String? = null

    internal fun startStreaming(
        apiKey: String,
        sessionConfig: SessionConfig,
        callback: StreamingCallback
    ) {
        val newToken = activeConnectionToken + 1
        activeConnectionToken = newToken
        stopStreamingInternal(cancel = true, clearCallback = false)

        this.callback = callback
        isClosing = false
        isRecognitionReady = false
        diarizationEnabled = sessionConfig.diarizationEnabled
        primarySpeaker = null
        clearFinalizeCloseTimer()

        val request = Request.Builder()
            .url(STREAMING_URL)
            .build()

        Log.i(
            TAG,
            "VOICE_STEP_3 opening Soniox realtime socket " +
                "(language=${sessionConfig.languageHint ?: "auto"}, " +
                "endpointDetection=${sessionConfig.enableEndpointDetection}, " +
                "maxEndpointDelayMs=${sessionConfig.maxEndpointDelayMs}, " +
                "diarization=${sessionConfig.diarizationEnabled}, " +
                "contextTerms=${sessionConfig.contextTerms.size}, " +
                "contextTextChars=${sessionConfig.contextText?.length ?: 0})"
        )

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (newToken != activeConnectionToken) return
                this@SonioxTranscriptionClient.webSocket = webSocket

                val startMessage = buildStartConfigMessage(apiKey, sessionConfig)
                val started = try {
                    webSocket.send(startMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send Soniox start config: ${e.message}")
                    false
                }
                if (!started) {
                    postIfCurrent(newToken) {
                        callback.onStreamError("Soniox session could not be started")
                    }
                    return
                }

                // Soniox does not emit a "started" event. OkHttp queues frames
                // in order, so PCM frames sent immediately after the config
                // arrive after it. Treat the stream as ready as soon as the
                // config is queued. If authentication fails, the server will
                // reply with `error_code` which we route to onStreamError.
                isRecognitionReady = true
                Log.i(TAG, "Soniox start config queued; stream ready")
                postIfCurrent(newToken) {
                    this@SonioxTranscriptionClient.callback?.onStreamReady()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (newToken != activeConnectionToken) return
                handleMessage(newToken, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Soniox only sends JSON text frames; ignore unexpected binary
                // messages.
                if (newToken != activeConnectionToken) return
                Log.w(TAG, "Ignoring unexpected binary frame from Soniox (${bytes.size} bytes)")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (newToken != activeConnectionToken) return
                Log.i(TAG, "Soniox stream closing: code=$code, reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (newToken != activeConnectionToken) return
                clearFinalizeCloseTimer()
                isRecognitionReady = false
                this@SonioxTranscriptionClient.webSocket = null
                Log.i(TAG, "Soniox stream closed: code=$code, reason=$reason")
                postIfCurrent(newToken) {
                    this@SonioxTranscriptionClient.callback?.onStreamClosed()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (newToken != activeConnectionToken) return
                clearFinalizeCloseTimer()
                isRecognitionReady = false
                this@SonioxTranscriptionClient.webSocket = null
                if (isClosing) {
                    Log.i(TAG, "Soniox stream failure after close request: ${t.message}")
                    postIfCurrent(newToken) {
                        this@SonioxTranscriptionClient.callback?.onStreamClosed()
                    }
                    return
                }
                val errorMessage = mapConnectionError(t, response)
                Log.e(TAG, "Soniox stream failure: $errorMessage (raw: ${t.message})")
                postIfCurrent(newToken) {
                    this@SonioxTranscriptionClient.callback?.onStreamError(errorMessage)
                }
            }
        })
    }

    fun sendAudioChunk(pcmData: ByteArray): Boolean {
        if (pcmData.isEmpty()) return true
        if (isClosing) return false
        val socket = webSocket ?: return false
        if (!isRecognitionReady) return false
        return try {
            socket.send(pcmData.toByteString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
            false
        }
    }

    /**
     * Gracefully end the stream by sending an empty binary frame. Soniox
     * flushes any remaining tokens, emits `{"finished": true}`, and closes.
     * If `finished` does not arrive within [FINALIZE_CLOSE_GRACE_MS] we
     * close the socket anyway so the IME doesn't hang.
     */
    fun finishStreaming() {
        val socket = webSocket ?: return
        isClosing = true

        clearFinalizeCloseTimer()
        val connectionToken = activeConnectionToken
        val closeRunnable = Runnable {
            if (connectionToken != activeConnectionToken) return@Runnable
            val activeSocket = webSocket ?: return@Runnable
            Log.w(TAG, "Soniox finished:true grace timeout fired; closing socket")
            activeSocket.close(1000, "client_stop")
        }
        pendingFinalizeCloseRunnable = closeRunnable
        mainHandler.postDelayed(closeRunnable, FINALIZE_CLOSE_GRACE_MS)

        val sent = try {
            socket.send(ByteString.EMPTY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Soniox empty end-of-stream frame: ${e.message}")
            false
        }
        if (sent) {
            Log.i(TAG, "Soniox empty end-of-stream frame sent")
        } else {
            socket.close(1000, "client_stop")
        }
    }

    fun cancelAll() {
        activeConnectionToken += 1
        stopStreamingInternal(cancel = true, clearCallback = true)
    }

    private fun stopStreamingInternal(cancel: Boolean, clearCallback: Boolean) {
        clearFinalizeCloseTimer()
        isClosing = true
        isRecognitionReady = false
        diarizationEnabled = false
        primarySpeaker = null
        val socket = webSocket
        webSocket = null
        if (socket != null) {
            if (cancel) {
                socket.cancel()
            } else {
                socket.close(1000, "client_stop")
            }
        }
        if (clearCallback) {
            callback = null
        }
    }

    private fun handleMessage(connectionToken: Long, message: String) {
        val json = try {
            JSONObject(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Soniox message: ${e.message}")
            return
        }

        if (json.has("error_code")) {
            val description = buildErrorDescription(json)
            Log.e(TAG, "Soniox stream error event: $description")
            postIfCurrent(connectionToken) {
                callback?.onStreamError(description)
            }
            return
        }

        val tokens = json.optJSONArray("tokens")
        val result = buildSegmentFromFinalTokens(
            tokens = tokens,
            primarySpeaker = primarySpeaker,
            diarizationEnabled = diarizationEnabled
        )
        if (result.observedSpeaker != null && primarySpeaker == null) {
            primarySpeaker = result.observedSpeaker
        }
        val segment = result.segment
        if (segment != null) {
            Log.i(
                TAG,
                "VOICE_STEP_4 Soniox final transcript (${segment.text.length} chars)"
            )
            postIfCurrent(connectionToken) {
                callback?.onTranscriptionResult(segment)
            }
        }

        if (json.optBoolean("finished", false)) {
            Log.i(TAG, "Soniox finished:true received; closing socket")
            clearFinalizeCloseTimer()
            webSocket?.close(1000, "client_stop")
        }
    }

    private fun mapConnectionError(error: Throwable, response: Response?): String {
        val code = response?.code
        if (code != null) {
            return when (code) {
                401, 403 -> "Invalid Soniox API key. Please check Settings."
                429 -> "Soniox rate limited — too many requests"
                in 500..599 -> "Soniox service error ($code)"
                else -> "Soniox connection rejected ($code)"
            }
        }
        return when (error) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Soniox streaming timed out"
            is ConnectException -> "Could not connect to Soniox streaming"
            else -> "Streaming error: ${error.message ?: "unknown"}"
        }
    }

    private fun postIfCurrent(connectionToken: Long, action: () -> Unit) {
        if (connectionToken != activeConnectionToken) return
        mainHandler.post {
            if (connectionToken != activeConnectionToken) return@post
            action()
        }
    }

    private fun clearFinalizeCloseTimer() {
        val runnable = pendingFinalizeCloseRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        pendingFinalizeCloseRunnable = null
    }
}
