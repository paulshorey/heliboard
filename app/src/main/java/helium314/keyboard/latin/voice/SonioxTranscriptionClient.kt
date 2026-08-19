// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * 3. Stream binary PCM frames. If nothing is sent for ~10 s, send
 *    `{"type":"keepalive"}` so Soniox does not idle-timeout (~20 s).
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

        /**
         * Current Soniox real-time model. v4 is retired on 2026-06-30 and
         * already aliased to v5; pin v5 explicitly so we can use v5-only
         * knobs such as `endpoint_sensitivity`.
         * https://soniox.com/docs/stt/models
         */
        internal const val MODEL = "stt-rt-v5"

        private const val FINALIZE_CLOSE_GRACE_MS = 8_000L

        /**
         * Soniox closes the socket if it receives neither audio nor a
         * keepalive control frame for more than ~20 s. Send one at least
         * every 10 s during pauses (mic pause, or any other outbound gap).
         * https://soniox.com/docs/stt/rt/connection-keepalive
         */
        private const val KEEPALIVE_INTERVAL_MS = 10_000L

        /**
         * Control message that forces Soniox to finalize every token processed
         * so far without ending the stream. Soniox replies with the pending
         * tokens as `is_final: true` followed by a `<fin>` marker, then keeps
         * the session open for more audio.
         *
         * This is the documented mechanism for client-side VAD / push-to-talk
         * pipelines (https://soniox.com/docs/stt/rt/manual-finalization). We use
         * it so a trailing phrase is always committed once the local silence
         * detector decides the speaker paused, instead of waiting for the
         * server's semantic endpoint — which can be delayed indefinitely when
         * the model is unsure an utterance ended.
         */
        internal const val FINALIZE_CONTROL_MESSAGE = "{\"type\":\"finalize\"}"

        /**
         * Control message that keeps a real-time session alive when no audio
         * is being sent. Distinct from OkHttp's WebSocket protocol ping.
         */
        internal const val KEEPALIVE_CONTROL_MESSAGE = "{\"type\":\"keepalive\"}"

        // Soniox's documented bounds for max_endpoint_delay_ms.
        internal const val MIN_MAX_ENDPOINT_DELAY_MS = 500
        internal const val MAX_MAX_ENDPOINT_DELAY_MS = 3000
        internal const val DEFAULT_MAX_ENDPOINT_DELAY_MS = 3000

        // v5-only endpoint_sensitivity: -1.0 (patient) to 1.0 (eager).
        // Negative values are the documented starting point for dictation and
        // speakers who pause mid-sentence, which is exactly this keyboard's
        // premature-period problem. Default 0.0 is Soniox's API default.
        // https://soniox.com/docs/stt/rt/endpoint-detection
        internal const val MIN_ENDPOINT_SENSITIVITY = -1.0
        internal const val MAX_ENDPOINT_SENSITIVITY = 1.0
        internal const val DEFAULT_ENDPOINT_SENSITIVITY = -0.3

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

        internal data class ContextGeneralItem(
            val key: String,
            val value: String
        )

        internal data class SessionConfig(
            val languageHint: String?,
            val languageHintsStrict: Boolean,
            val enableEndpointDetection: Boolean,
            val maxEndpointDelayMs: Int,
            val endpointSensitivity: Double,
            val diarizationEnabled: Boolean,
            val contextTerms: List<String>,
            val contextText: String?,
            val contextGeneral: List<ContextGeneralItem>
        )

        internal fun buildSessionConfig(
            languageTag: String?,
            enableEndpointDetection: Boolean,
            maxEndpointDelayMs: Int,
            diarizationEnabled: Boolean,
            contextTerms: List<String> = defaultContextTerms(),
            customContextTerms: List<String> = emptyList(),
            contextText: String? = null,
            endpointSensitivity: Double = DEFAULT_ENDPOINT_SENSITIVITY
        ): SessionConfig {
            val mergedTerms = (contextTerms + customContextTerms)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val languageHint = normalizeLanguageHint(languageTag)
            return SessionConfig(
                languageHint = languageHint,
                // Keyboard subtype already names the expected language. Strict
                // hints are the documented v5 way to avoid transliteration into
                // the wrong script (best with a single language).
                languageHintsStrict = languageHint != null,
                enableEndpointDetection = enableEndpointDetection,
                maxEndpointDelayMs = maxEndpointDelayMs.coerceIn(
                    MIN_MAX_ENDPOINT_DELAY_MS,
                    MAX_MAX_ENDPOINT_DELAY_MS
                ),
                endpointSensitivity = endpointSensitivity.coerceIn(
                    MIN_ENDPOINT_SENSITIVITY,
                    MAX_ENDPOINT_SENSITIVITY
                ),
                diarizationEnabled = diarizationEnabled,
                contextTerms = mergedTerms,
                contextText = sanitizeContextText(contextText),
                contextGeneral = buildContextGeneral(
                    languageHint = languageHint,
                    diarizationEnabled = diarizationEnabled
                )
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
         * Structured `context.general` key/value pairs. v5 treats these as
         * more influential than free-form `context.text`; keep the list short
         * (Soniox recommends ~10 or fewer).
         * https://soniox.com/docs/stt/concepts/context
         */
        internal fun buildContextGeneral(
            languageHint: String?,
            diarizationEnabled: Boolean
        ): List<ContextGeneralItem> {
            val items = mutableListOf(
                ContextGeneralItem("domain", "Mobile keyboard dictation"),
                ContextGeneralItem("setting", "User dictating text into a mobile app"),
                ContextGeneralItem("topic", "Dictation"),
                ContextGeneralItem("product", "HeliBoard")
            )
            val languageName = languageDisplayName(languageHint)
            if (languageName != null) {
                items += ContextGeneralItem("language", languageName)
                items += ContextGeneralItem(
                    "instructions",
                    "User is dictating in $languageName. Output transcription only in $languageName."
                )
            }
            if (diarizationEnabled) {
                items += ContextGeneralItem(
                    "speakers",
                    "1 speaker (local user dictating)"
                )
            }
            return items
        }

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
                .put("enable_speaker_diarization", config.diarizationEnabled)

            if (config.enableEndpointDetection) {
                payload.put("max_endpoint_delay_ms", config.maxEndpointDelayMs)
                // Leave endpoint_latency_adjustment_level at Soniox's default 0
                // (no extra aggressiveness). Negative sensitivity is the
                // documented dictation setting so the model waits through
                // mid-sentence pauses instead of inserting early periods.
                payload.put("endpoint_sensitivity", config.endpointSensitivity)
            }

            if (config.languageHint != null) {
                payload.put(
                    "language_hints",
                    JSONArray().apply { put(config.languageHint) }
                )
                payload.put("language_hints_strict", config.languageHintsStrict)
            }
            val contextObject = JSONObject()
            if (config.contextGeneral.isNotEmpty()) {
                contextObject.put(
                    "general",
                    JSONArray().apply {
                        config.contextGeneral.forEach { item ->
                            put(
                                JSONObject()
                                    .put("key", item.key)
                                    .put("value", item.value)
                            )
                        }
                    }
                )
            }
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
         * English display name for a Soniox language-hint code, used in
         * `context.general`. Returns null when [languageCode] is blank.
         */
        internal fun languageDisplayName(languageCode: String?): String? {
            if (languageCode.isNullOrBlank()) return null
            val displayName = Locale.forLanguageTag(languageCode)
                .getDisplayLanguage(Locale.ENGLISH)
                .trim()
            return displayName.ifBlank { languageCode }
        }

        /**
         * True when Soniox returned at least one `is_final` token (text or
         * control marker).
         */
        internal fun tokensContainFinalTokens(tokens: JSONArray?): Boolean {
            if (tokens == null || tokens.length() == 0) return false
            for (i in 0 until tokens.length()) {
                val token = tokens.optJSONObject(i) ?: continue
                if (token.optBoolean("is_final", false)) return true
            }
            return false
        }

        /**
         * Build a [TranscriptSegment] from a single Soniox response's `tokens`
         * array. Returns `null` if the response contained no usable final
         * tokens for the active speaker.
         *
         * Soniox encodes inter-word whitespace as **separate space tokens**
         * (e.g. a stream may arrive as `"Hello"`, `" "`, `"world"`, `"."`).
         * Concatenating the text in order, then trimming, yields the correct
         * rendered text for the segment.
         *
         * Word-boundary detection across responses
         * ----------------------------------------
         * When endpoint detection or internal segmentation finalize tokens
         * mid-word, Soniox splits the word across two responses. The second
         * response then starts with a content token that has **no preceding
         * space token** — this missing leading whitespace is Soniox's signal
         * that the new chunk continues the previous word (e.g. `"head"`
         * finalizes in response A and `"ing"` starts response B with no space
         * token between them, meaning "heading"). Without this signal the IME
         * would auto-insert a space and produce `"head ing"`.
         *
         * To honor that signal we pass [previousTailIsWordy] from the previous
         * call: if it's `true` and the current response's raw text does **not**
         * start with whitespace, we mark the segment as `attachesToPrevious`
         * so the IME does not insert a separating space. The new tail state is
         * returned in [SegmentResult.tailIsWordy] so the caller can feed it
         * back on the next response.
         */
        internal fun buildSegmentFromFinalTokens(
            tokens: org.json.JSONArray?,
            primarySpeaker: String?,
            diarizationEnabled: Boolean,
            previousTailIsWordy: Boolean = false
        ): SegmentResult {
            if (tokens == null || tokens.length() == 0) {
                return SegmentResult(
                    segment = null,
                    observedSpeaker = primarySpeaker,
                    tailIsWordy = previousTailIsWordy
                )
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

            val raw = builder.toString()
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) {
                return SegmentResult(
                    segment = null,
                    observedSpeaker = lockedSpeaker,
                    tailIsWordy = previousTailIsWordy
                )
            }
            val rawStartsWithWhitespace = raw[0].isWhitespace()
            val firstChar = trimmed[0]
            val isPunctuationStart = firstChar in PUNCTUATION_ATTACHING_TO_PREVIOUS
            // Mid-word continuation: Soniox emitted the new chunk with no
            // preceding space token, signaling that it joins onto the previous
            // chunk's last (wordy) character rather than starting a new word.
            // We require previousTailIsWordy to be true so the first chunk of
            // a session (or a chunk that follows sentence-ending punctuation)
            // is NOT silently attached without a separator. That preserves
            // both leading-casing adjustment and auto-leading-space behavior
            // for legitimate new utterances.
            val isWordContinuation =
                previousTailIsWordy && !rawStartsWithWhitespace
            val attachesToPrevious = isPunctuationStart || isWordContinuation
            val tailIsWordy = isWordyContinuationChar(trimmed.last())
            return SegmentResult(
                segment = TranscriptSegment(text = trimmed, attachesToPrevious = attachesToPrevious),
                observedSpeaker = lockedSpeaker,
                tailIsWordy = tailIsWordy
            )
        }

        /**
         * A "wordy" character is one that, when followed by another non-space
         * character, would form a continuous word (no separating space). Used
         * to detect when a finalized chunk ends inside a word so the next
         * chunk can be attached without an injected space.
         *
         * Letters and digits are wordy. Apostrophes and hyphens are also
         * treated as wordy because they appear inside English words
         * (`"don't"`, `"co-op"`); ending a chunk on one and resuming with
         * a letter typically means the word continues. Sentence-attaching
         * punctuation (`.`, `,`, `!`, `?`, …) is explicitly **not** wordy
         * because those mark word/clause ends.
         */
        private fun isWordyContinuationChar(c: Char): Boolean {
            if (c.isLetterOrDigit()) return true
            return c == '\'' || c == '\u2019' /* right single quote */ ||
                c == '-' || c == '\u2010' /* hyphen */
        }

        internal data class SegmentResult(
            val segment: TranscriptSegment?,
            val observedSpeaker: String?,
            val tailIsWordy: Boolean = false
        )

        internal fun buildErrorDescription(json: JSONObject): String {
            val type = json.optString("error_type", "")
            val requestId = json.optString("request_id", "")
            val friendly = friendlyErrorForType(type)
            val description = friendly ?: run {
                val code = json.optString("error_code", "")
                val message = json.optString("error_message", "")
                val summary = listOf(type, code)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                when {
                    summary.isBlank() && message.isBlank() -> "Soniox reported an unknown error"
                    summary.isBlank() -> message
                    message.isBlank() -> summary
                    else -> "$summary: $message"
                }
            }
            val withRequestId = if (requestId.isBlank()) {
                description
            } else {
                "$description (request_id=$requestId)"
            }
            val moreInfo = json.optString("more_info", "")
            return if (moreInfo.isBlank()) {
                withRequestId
            } else {
                "$withRequestId $moreInfo"
            }
        }

        /**
         * Map stable Soniox `error_type` values to short user-facing text.
         * Unknown types fall through so the raw message is preserved.
         */
        internal fun friendlyErrorForType(errorType: String): String? {
            return when (errorType) {
                "unauthenticated" ->
                    "Invalid Soniox API key. Please check Settings."
                "organization_balance_exhausted",
                "organization_monthly_budget_exhausted",
                "project_monthly_budget_exhausted" ->
                    "Soniox account is out of credits. Add funds in the Soniox console."
                "limit_exceeded" ->
                    "Soniox rate limited — too many requests"
                "max_duration_reached" ->
                    "Soniox session reached its maximum duration"
                "service_unavailable" ->
                    "Soniox is temporarily unavailable. Please try again."
                "request_timeout" ->
                    "Soniox streaming timed out"
                "temp_api_key_session_expired" ->
                    "Soniox temporary API key expired"
                "model_not_available" ->
                    "The requested Soniox model is not available"
                else -> null
            }
        }
    }

    interface StreamingCallback {
        /** Recognition session is configured and ready to receive audio chunks. */
        fun onStreamReady()

        /** Finalized transcription text rebuilt from `is_final: true` tokens. */
        fun onTranscriptionResult(segment: TranscriptSegment)

        /**
         * Soniox returned at least one `is_final` token. [hasTranscriptText] is
         * true when those tokens contained user-visible text (not only markers).
         */
        fun onFinalTokensReceived(hasTranscriptText: Boolean)

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
    private var pendingKeepaliveRunnable: Runnable? = null

    @Volatile
    private var lastOutboundAtMs = 0L

    @Volatile
    private var diarizationEnabled = false

    @Volatile
    private var primarySpeaker: String? = null

    /**
     * Tracks whether the most recently emitted finalized text ended on a
     * "wordy" character (letter/digit/apostrophe/hyphen). Used to detect
     * Soniox-signaled mid-word continuations across consecutive responses;
     * see [Companion.buildSegmentFromFinalTokens].
     */
    @Volatile
    private var lastFinalTokenTailIsWordy = false

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
        lastFinalTokenTailIsWordy = false
        lastOutboundAtMs = 0L
        clearFinalizeCloseTimer()
        clearKeepaliveTimer()

        val request = Request.Builder()
            .url(STREAMING_URL)
            .build()

        Log.i(
            TAG,
            "VOICE_STEP_3 opening Soniox realtime socket " +
                "(model=$MODEL, " +
                "language=${sessionConfig.languageHint ?: "auto"}, " +
                "languageHintsStrict=${sessionConfig.languageHintsStrict}, " +
                "endpointDetection=${sessionConfig.enableEndpointDetection}, " +
                "maxEndpointDelayMs=${sessionConfig.maxEndpointDelayMs}, " +
                "endpointSensitivity=${sessionConfig.endpointSensitivity}, " +
                "diarization=${sessionConfig.diarizationEnabled}, " +
                "contextGeneral=${sessionConfig.contextGeneral.size}, " +
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
                markOutbound()

                // Soniox does not emit a "started" event. OkHttp queues frames
                // in order, so PCM frames sent immediately after the config
                // arrive after it. Treat the stream as ready as soon as the
                // config is queued. If authentication fails, the server will
                // reply with `error_code` which we route to onStreamError.
                isRecognitionReady = true
                scheduleKeepalive(newToken)
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
                clearKeepaliveTimer()
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
                clearKeepaliveTimer()
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
            val sent = socket.send(pcmData.toByteString())
            if (sent) markOutbound()
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
            false
        }
    }

    /**
     * Ask Soniox to finalize all audio processed so far without ending the
     * stream (see [FINALIZE_CONTROL_MESSAGE]). Safe to call repeatedly; when
     * there are no pending non-final tokens it is effectively a no-op (Soniox
     * just returns a `<fin>` marker, which we filter). Returns true when the
     * control frame was queued.
     */
    fun finalizeNow(): Boolean {
        if (isClosing) return false
        val socket = webSocket ?: return false
        if (!isRecognitionReady) return false
        return try {
            val sent = socket.send(FINALIZE_CONTROL_MESSAGE)
            if (sent) markOutbound()
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Soniox finalize control frame: ${e.message}")
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
        clearKeepaliveTimer()

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
            markOutbound()
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
        clearKeepaliveTimer()
        isClosing = true
        isRecognitionReady = false
        diarizationEnabled = false
        primarySpeaker = null
        lastFinalTokenTailIsWordy = false
        lastOutboundAtMs = 0L
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
            diarizationEnabled = diarizationEnabled,
            previousTailIsWordy = lastFinalTokenTailIsWordy
        )
        if (result.observedSpeaker != null && primarySpeaker == null) {
            primarySpeaker = result.observedSpeaker
        }
        val segment = result.segment
        if (segment != null) {
            lastFinalTokenTailIsWordy = result.tailIsWordy
            Log.i(
                TAG,
                "VOICE_STEP_4 Soniox final transcript (${segment.text.length} chars)"
            )
            postIfCurrent(connectionToken) {
                callback?.onTranscriptionResult(segment)
            }
        }

        if (tokensContainFinalTokens(tokens)) {
            postIfCurrent(connectionToken) {
                callback?.onFinalTokensReceived(segment != null)
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
                402 -> "Soniox account is out of credits. Add funds in the Soniox console."
                408 -> "Soniox streaming timed out"
                413 -> "Soniox session reached its maximum duration"
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

    private fun markOutbound() {
        lastOutboundAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * Send Soniox's application-level keepalive if no audio or control frame
     * has gone out for [KEEPALIVE_INTERVAL_MS]. OkHttp protocol pings are not
     * enough — Soniox times out after ~20 s without audio or this message.
     */
    private fun scheduleKeepalive(connectionToken: Long) {
        clearKeepaliveTimer()
        val runnable = object : Runnable {
            override fun run() {
                if (connectionToken != activeConnectionToken) return
                if (isClosing || !isRecognitionReady) return
                val elapsed = SystemClock.elapsedRealtime() - lastOutboundAtMs
                if (elapsed >= KEEPALIVE_INTERVAL_MS) {
                    sendKeepaliveFrame()
                }
                if (connectionToken == activeConnectionToken && !isClosing && isRecognitionReady) {
                    mainHandler.postDelayed(this, KEEPALIVE_INTERVAL_MS)
                }
            }
        }
        pendingKeepaliveRunnable = runnable
        mainHandler.postDelayed(runnable, KEEPALIVE_INTERVAL_MS)
    }

    private fun sendKeepaliveFrame(): Boolean {
        if (isClosing) return false
        val socket = webSocket ?: return false
        if (!isRecognitionReady) return false
        return try {
            val sent = socket.send(KEEPALIVE_CONTROL_MESSAGE)
            if (sent) {
                markOutbound()
                Log.i(TAG, "Soniox keepalive sent")
            }
            sent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Soniox keepalive: ${e.message}")
            false
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

    private fun clearKeepaliveTimer() {
        val runnable = pendingKeepaliveRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        pendingKeepaliveRunnable = null
    }
}
