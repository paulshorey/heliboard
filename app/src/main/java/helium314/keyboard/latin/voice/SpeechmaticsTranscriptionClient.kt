// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.utils.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

internal sealed interface SpeechmaticsServerEvent {
    data object RecognitionStarted : SpeechmaticsServerEvent
    data class AudioAdded(val sequenceNumber: Int) : SpeechmaticsServerEvent
    data class FinalTranscript(
        val transcript: String,
        val attachesToPrevious: Boolean,
        val startTime: Double,
        val endTime: Double
    ) : SpeechmaticsServerEvent
    data class Error(val description: String) : SpeechmaticsServerEvent
    data object EndOfUtterance : SpeechmaticsServerEvent
    data object EndOfTranscript : SpeechmaticsServerEvent
}

data class TranscriptSegment(
    val text: String,
    val attachesToPrevious: Boolean
)

/**
 * Client for Speechmatics realtime transcription over WebSocket.
 *
 * The socket is opened first, then a StartRecognition message configures the
 * session before audio frames are accepted. Only finalized AddTranscript events
 * are surfaced to the rest of the IME so insertion order remains deterministic.
 *
 * API docs: https://docs.speechmatics.com/api-ref/realtime-transcription-websocket
 */
class SpeechmaticsTranscriptionClient {

    companion object {
        private const val TAG = "SpeechmaticsTranscription"
        private const val STREAMING_BASE_URL = "wss://eu.rt.speechmatics.com/v2/"
        private const val FINALIZE_CLOSE_GRACE_MS = 8_000L
        private const val DEFAULT_LANGUAGE = "en"
        private const val DEFAULT_OUTPUT_LOCALE = "en-US"
        /**
         * Speechmatics requires `max_speakers` >= 2; we still only insert tokens for the
         * primary speaker (S1). Lower sensitivity reduces spurious extra-speaker splits.
         */
        private const val SPEAKER_DIARIZATION_MAX_SPEAKERS = 2
        private const val SPEAKER_DIARIZATION_SENSITIVITY = 0.35

        internal data class SessionConfig(
            val language: String,
            val outputLocale: String?,
            val maxDelaySeconds: Double,
            val removeDisfluencies: Boolean,
            val endOfUtteranceSilenceTriggerSeconds: Double,
            val punctuationSensitivity: Double,
            val diarizationEnabled: Boolean,
            val additionalVocab: List<VocabEntry>,
            val replacements: List<ReplacementRule>,
            val operatingPoint: String
        )

        data class VocabEntry(
            val content: String,
            val soundsLike: List<String> = emptyList()
        )

        data class ReplacementRule(
            val from: String,
            val to: String
        )

        private val ATTACHES_TO_PREVIOUS_VALUES = setOf("previous", "both")
        private val PUNCTUATION_ATTACHING_TO_PREVIOUS = setOf(
            '.', ',', '!', '?', ':', ';', ')', ']', '}', '%'
        )

        internal fun buildSessionConfig(
            languageTag: String?,
            maxDelaySeconds: Double,
            removeDisfluencies: Boolean,
            endOfUtteranceSilenceTriggerSeconds: Double,
            punctuationSensitivity: Double,
            diarizationEnabled: Boolean = false,
            additionalVocab: List<VocabEntry> = defaultAdditionalVocab(),
            replacements: List<ReplacementRule> = defaultReplacements(),
            operatingPoint: String = "enhanced"
        ): SessionConfig {
            val normalizedLanguage = normalizeLanguage(languageTag)
            val normalizedOutputLocale = normalizeOutputLocale(languageTag, normalizedLanguage)
            return SessionConfig(
                language = normalizedLanguage,
                outputLocale = normalizedOutputLocale,
                maxDelaySeconds = maxDelaySeconds,
                removeDisfluencies = removeDisfluencies && normalizedLanguage == "en",
                endOfUtteranceSilenceTriggerSeconds = endOfUtteranceSilenceTriggerSeconds,
                punctuationSensitivity = punctuationSensitivity.coerceIn(0.0, 1.0),
                diarizationEnabled = diarizationEnabled,
                additionalVocab = additionalVocab,
                replacements = replacements,
                operatingPoint = operatingPoint
            )
        }

        internal fun defaultAdditionalVocab(): List<VocabEntry> = listOf(
            VocabEntry("HeliBoard"),
            VocabEntry("Speechmatics"),
            VocabEntry("gnocchi", listOf("nyohki", "nokey", "nochi")),
            VocabEntry("Kubernetes", listOf("koo-ber-net-eez")),
            VocabEntry("API"),
        )

        internal fun defaultReplacements(): List<ReplacementRule> = listOf(
            ReplacementRule("heli board", "HeliBoard"),
            ReplacementRule("speechmatics", "Speechmatics"),
            ReplacementRule("/^[Oo]kay google$/", "OK Google"),
            ReplacementRule("/^[Hh]ey [Ss]iri$/", "Hey Siri"),
        )

        internal fun buildStartRecognitionMessage(config: SessionConfig): String {
            val message = JSONObject()
                .put("message", "StartRecognition")
                .put(
                    "audio_format",
                    JSONObject()
                        .put("type", "raw")
                        .put("encoding", "pcm_s16le")
                        .put("sample_rate", VoiceRecorder.SAMPLE_RATE)
                )
                .put(
                    "transcription_config",
                    JSONObject().apply {
                        put("language", config.language)
                        put("max_delay", config.maxDelaySeconds)
                        put("max_delay_mode", "flexible")
                        put("enable_partials", false)
                        put("enable_entities", true)
                        put("operating_point", config.operatingPoint)
                        // permitted_marks = ["all"] permits every punctuation mark the
                        // active language pack supports (including commas for English,
                        // 、 for Japanese, ． for Hindi, etc.). Sensitivity (0–1) then
                        // controls how readily the model inserts them: higher values
                        // produce more commas at short pauses and more periods / ? / !
                        // at sentence boundaries.
                        put(
                            "punctuation_overrides",
                            JSONObject()
                                .put("permitted_marks", org.json.JSONArray().put("all"))
                                .put("sensitivity", config.punctuationSensitivity)
                        )
                        if (config.outputLocale != null) {
                            put("output_locale", config.outputLocale)
                        }
                        if (config.diarizationEnabled) {
                            put("diarization", "speaker")
                            put(
                                "speaker_diarization_config",
                                JSONObject()
                                    .put("max_speakers", SPEAKER_DIARIZATION_MAX_SPEAKERS)
                                    .put("prefer_current_speaker", true)
                                    .put("speaker_sensitivity", SPEAKER_DIARIZATION_SENSITIVITY)
                            )
                        }
                        if (config.additionalVocab.isNotEmpty()) {
                            put("additional_vocab", buildAdditionalVocabJson(config.additionalVocab))
                        }

                        val filterConfig = buildTranscriptFilteringConfig(
                            config.removeDisfluencies,
                            config.replacements
                        )
                        if (filterConfig.length() > 0) {
                            put("transcript_filtering_config", filterConfig)
                        }

                        if (config.endOfUtteranceSilenceTriggerSeconds > 0.0) {
                            put(
                                "conversation_config",
                                JSONObject().put(
                                    "end_of_utterance_silence_trigger",
                                    config.endOfUtteranceSilenceTriggerSeconds
                                )
                            )
                        }
                    }
                )
            return message.toString()
        }

        private fun buildAdditionalVocabJson(vocab: List<VocabEntry>): org.json.JSONArray {
            val arr = org.json.JSONArray()
            for (entry in vocab) {
                if (entry.soundsLike.isEmpty()) {
                    arr.put(entry.content)
                } else {
                    val obj = JSONObject()
                        .put("content", entry.content)
                        .put("sounds_like", org.json.JSONArray().apply {
                            entry.soundsLike.forEach { put(it) }
                        })
                    arr.put(obj)
                }
            }
            return arr
        }

        private fun buildTranscriptFilteringConfig(
            removeDisfluencies: Boolean,
            replacements: List<ReplacementRule>
        ): JSONObject {
            val config = JSONObject()
            if (removeDisfluencies) {
                config.put("remove_disfluencies", true)
            }
            if (replacements.isNotEmpty()) {
                val arr = org.json.JSONArray()
                for (rule in replacements) {
                    arr.put(
                        JSONObject()
                            .put("from", rule.from)
                            .put("to", rule.to)
                    )
                }
                config.put("replacements", arr)
            }
            return config
        }

        internal fun parseServerEvent(
            message: String,
            primarySpeaker: String? = null
        ): SpeechmaticsServerEvent? {
            val json = JSONObject(message)
            return when (json.optString("message")) {
                "RecognitionStarted" -> SpeechmaticsServerEvent.RecognitionStarted

                "AudioAdded" -> {
                    val sequenceNumber = json.optInt("seq_no", -1)
                    if (sequenceNumber >= 0) SpeechmaticsServerEvent.AudioAdded(sequenceNumber) else null
                }

                "AddTranscript" -> {
                    val metadata = json.optJSONObject("metadata")
                    val fallbackTranscript = if (primarySpeaker != null) {
                        ""
                    } else {
                        metadata?.optString("transcript", "").orEmpty()
                    }
                    val transcriptSegment = buildTranscriptSegment(
                        results = json.optJSONArray("results"),
                        fallbackTranscript = fallbackTranscript,
                        primarySpeaker = primarySpeaker
                    )
                    val transcript = transcriptSegment?.text.orEmpty()
                    if (transcript.isBlank()) {
                        null
                    } else {
                        SpeechmaticsServerEvent.FinalTranscript(
                            transcript = transcript,
                            attachesToPrevious = transcriptSegment?.attachesToPrevious ?: false,
                            startTime = metadata?.optDouble("start_time", -1.0) ?: -1.0,
                            endTime = metadata?.optDouble("end_time", -1.0) ?: -1.0
                        )
                    }
                }

                "Error" -> SpeechmaticsServerEvent.Error(buildServerErrorDescription(json))
                "EndOfUtterance" -> SpeechmaticsServerEvent.EndOfUtterance
                "EndOfTranscript" -> SpeechmaticsServerEvent.EndOfTranscript
                else -> null
            }
        }

        private fun buildTranscriptSegment(
            results: org.json.JSONArray?,
            fallbackTranscript: String,
            primarySpeaker: String? = null
        ): TranscriptSegment? {
            if (results == null || results.length() == 0) {
                if (primarySpeaker != null) {
                    return null
                }
                val normalizedFallback = fallbackTranscript.trim()
                return if (normalizedFallback.isBlank()) {
                    null
                } else {
                    TranscriptSegment(
                        text = normalizedFallback,
                        attachesToPrevious = startsWithAttachingPunctuation(normalizedFallback)
                    )
                }
            }

            val builder = StringBuilder()
            var attachesToPrevious = false

            for (index in 0 until results.length()) {
                val result = results.optJSONObject(index) ?: continue
                val alt = result.optJSONArray("alternatives")?.optJSONObject(0) ?: continue
                val token = alt.optString("content", "")
                if (token.isBlank()) continue

                if (primarySpeaker != null) {
                    val speaker = alt.optString("speaker", "")
                    if (speaker.isNotEmpty() && speaker != primarySpeaker && speaker != "UU") {
                        continue
                    }
                }

                val attachesTo = result.optString("attaches_to", "none")
                if (builder.isEmpty()) {
                    attachesToPrevious = attachesTo in ATTACHES_TO_PREVIOUS_VALUES ||
                        startsWithAttachingPunctuation(token)
                    builder.append(token)
                    continue
                }

                if (shouldInsertSpaceBeforeToken(builder, token, attachesTo)) {
                    builder.append(' ')
                }
                builder.append(token)
            }

            val normalized = builder.toString().trim()
            return if (normalized.isBlank()) {
                null
            } else {
                TranscriptSegment(
                    text = normalized,
                    attachesToPrevious = attachesToPrevious
                )
            }
        }

        private fun shouldInsertSpaceBeforeToken(
            builder: StringBuilder,
            token: String,
            attachesTo: String
        ): Boolean {
            if (builder.isEmpty()) return false
            if (builder.last().isWhitespace()) return false
            if (token.firstOrNull()?.isWhitespace() == true) return false
            if (attachesTo in ATTACHES_TO_PREVIOUS_VALUES) return false
            if (startsWithAttachingPunctuation(token)) return false
            return true
        }

        private fun startsWithAttachingPunctuation(text: String): Boolean {
            val first = text.firstOrNull() ?: return false
            return first in PUNCTUATION_ATTACHING_TO_PREVIOUS
        }

        private fun buildServerErrorDescription(json: JSONObject): String {
            val messageType = json.optString("type", "")
            val code = json.optString("code", "")
            val reason = json.optString("reason", "")
            val detail = json.optString("detail", "")

            val summary = listOf(messageType, code)
                .filter { it.isNotBlank() }
                .joinToString(" ")

            val explanation = listOf(reason, detail)
                .filter { it.isNotBlank() }
                .joinToString(": ")

            return when {
                summary.isBlank() && explanation.isBlank() -> "Speechmatics reported an unknown error"
                summary.isBlank() -> explanation
                explanation.isBlank() -> summary
                else -> "$summary: $explanation"
            }
        }

        private fun normalizeLanguage(language: String?): String {
            val normalizedTag = language
                ?.trim()
                ?.replace('_', '-')
                .orEmpty()
            val locale = Locale.forLanguageTag(normalizedTag)
            val languageCode = locale.language.orEmpty()
            return when {
                normalizedTag.isBlank() -> DEFAULT_LANGUAGE
                normalizedTag == "und" -> DEFAULT_LANGUAGE
                normalizedTag == "zz" -> DEFAULT_LANGUAGE
                languageCode.isBlank() -> DEFAULT_LANGUAGE
                languageCode == "und" -> DEFAULT_LANGUAGE
                else -> languageCode
            }
        }

        private fun normalizeOutputLocale(languageTag: String?, normalizedLanguage: String): String? {
            if (normalizedLanguage != "en") {
                return null
            }

            val normalizedTag = languageTag
                ?.trim()
                ?.replace('_', '-')
                .orEmpty()

            val locale = Locale.forLanguageTag(normalizedTag)
            val region = locale.country.orEmpty().uppercase(Locale.US)
            return when (region) {
                "GB", "US", "AU" -> "en-$region"
                else -> DEFAULT_OUTPUT_LOCALE
            }
        }
    }

    interface StreamingCallback {
        /** Recognition session is configured and ready to receive audio chunks. */
        fun onStreamReady()

        /** Finalized transcription text from Speechmatics AddTranscript events. */
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
    private var lastFinalResultFingerprint = ""

    @Volatile
    private var pendingFinalizeCloseRunnable: Runnable? = null

    @Volatile
    private var endOfStreamSent = false

    @Volatile
    private var totalAudioChunksSent = 0

    @Volatile
    private var pendingAudioAcknowledgements = 0

    @Volatile
    private var lastAcknowledgedSequenceNumber = -1

    @Volatile
    private var forceEndOfUtteranceSent = false

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
        lastFinalResultFingerprint = ""
        endOfStreamSent = false
        totalAudioChunksSent = 0
        pendingAudioAcknowledgements = 0
        lastAcknowledgedSequenceNumber = -1
        forceEndOfUtteranceSent = false
        diarizationEnabled = sessionConfig.diarizationEnabled
        primarySpeaker = if (sessionConfig.diarizationEnabled) "S1" else null
        clearFinalizeCloseTimer()
        val request = Request.Builder()
            .url(STREAMING_BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .build()

        Log.i(
            TAG,
            "VOICE_STEP_3 opening Speechmatics realtime socket " +
                "(language=${sessionConfig.language}, outputLocale=${sessionConfig.outputLocale ?: "default"}, " +
                "max_delay=${sessionConfig.maxDelaySeconds}s, removeDisfluencies=${sessionConfig.removeDisfluencies}, " +
                "eou=${sessionConfig.endOfUtteranceSilenceTriggerSeconds}s, " +
                "diarization=${sessionConfig.diarizationEnabled}, " +
                "operating_point=${sessionConfig.operatingPoint}, " +
                "vocab=${sessionConfig.additionalVocab.size}, " +
                "replacements=${sessionConfig.replacements.size})"
        )

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (newToken != activeConnectionToken) return
                this@SpeechmaticsTranscriptionClient.webSocket = webSocket

                val startMessage = buildStartRecognitionMessage(sessionConfig)
                val started = try {
                    webSocket.send(startMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send StartRecognition: ${e.message}")
                    false
                }
                if (!started) {
                    postIfCurrent(newToken) {
                        callback.onStreamError("Speechmatics session could not be started")
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (newToken != activeConnectionToken) return
                handleMessage(newToken, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (newToken != activeConnectionToken) return
                Log.i(TAG, "Speechmatics stream closing: code=$code, reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (newToken != activeConnectionToken) return
                clearFinalizeCloseTimer()
                isRecognitionReady = false
                this@SpeechmaticsTranscriptionClient.webSocket = null
                Log.i(TAG, "Speechmatics stream closed: code=$code, reason=$reason")
                postIfCurrent(newToken) {
                    this@SpeechmaticsTranscriptionClient.callback?.onStreamClosed()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (newToken != activeConnectionToken) return
                clearFinalizeCloseTimer()
                isRecognitionReady = false
                this@SpeechmaticsTranscriptionClient.webSocket = null
                if (isClosing) {
                    Log.i(TAG, "Speechmatics stream failure after close request: ${t.message}")
                    postIfCurrent(newToken) {
                        this@SpeechmaticsTranscriptionClient.callback?.onStreamClosed()
                    }
                    return
                }
                val errorMessage = mapConnectionError(t, response)
                Log.e(TAG, "Speechmatics stream failure: $errorMessage (raw: ${t.message})")
                postIfCurrent(newToken) {
                    this@SpeechmaticsTranscriptionClient.callback?.onStreamError(errorMessage)
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
            val accepted = socket.send(pcmData.toByteString())
            if (accepted) {
                totalAudioChunksSent += 1
                pendingAudioAcknowledgements += 1
            }
            accepted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
            false
        }
    }

    fun finishStreaming() {
        val socket = webSocket ?: return
        isClosing = true

        clearFinalizeCloseTimer()
        val connectionToken = activeConnectionToken
        val closeRunnable = Runnable {
            if (connectionToken != activeConnectionToken) return@Runnable
            val activeSocket = webSocket ?: return@Runnable
            activeSocket.close(1000, "client_stop")
        }
        pendingFinalizeCloseRunnable = closeRunnable
        mainHandler.postDelayed(closeRunnable, FINALIZE_CLOSE_GRACE_MS)

        if (totalAudioChunksSent == 0) {
            socket.close(1000, "client_stop")
            return
        }

        maybeSendEndOfStream()
    }

    fun cancelAll() {
        activeConnectionToken += 1
        stopStreamingInternal(cancel = true, clearCallback = true)
    }

    private fun stopStreamingInternal(cancel: Boolean, clearCallback: Boolean) {
        clearFinalizeCloseTimer()
        isClosing = true
        isRecognitionReady = false
        endOfStreamSent = false
        totalAudioChunksSent = 0
        pendingAudioAcknowledgements = 0
        lastAcknowledgedSequenceNumber = -1
        forceEndOfUtteranceSent = false
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
        val event = try {
            parseServerEvent(message, primarySpeaker)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Speechmatics event: ${e.message}")
            null
        } ?: return

        when (event) {
            SpeechmaticsServerEvent.RecognitionStarted -> {
                isRecognitionReady = true
                Log.i(TAG, "Speechmatics recognition started")
                postIfCurrent(connectionToken) {
                    callback?.onStreamReady()
                }
            }

            is SpeechmaticsServerEvent.AudioAdded -> {
                lastAcknowledgedSequenceNumber = event.sequenceNumber
                if (pendingAudioAcknowledgements > 0) {
                    pendingAudioAcknowledgements -= 1
                }
                if (isClosing) {
                    maybeSendEndOfStream()
                }
            }

            is SpeechmaticsServerEvent.FinalTranscript -> {
                val fingerprint = "${event.startTime}|${event.endTime}|${event.transcript}"
                if (fingerprint == lastFinalResultFingerprint) {
                    return
                }
                lastFinalResultFingerprint = fingerprint

                Log.i(
                    TAG,
                    "VOICE_STEP_4 Speechmatics final transcript (${event.transcript.length} chars)"
                )
                postIfCurrent(connectionToken) {
                    callback?.onTranscriptionResult(
                        TranscriptSegment(
                            text = event.transcript,
                            attachesToPrevious = event.attachesToPrevious
                        )
                    )
                }
            }

            is SpeechmaticsServerEvent.Error -> {
                Log.e(TAG, "Speechmatics stream error event: ${event.description}")
                postIfCurrent(connectionToken) {
                    callback?.onStreamError(event.description)
                }
            }

            SpeechmaticsServerEvent.EndOfTranscript -> {
                if (isClosing) {
                    clearFinalizeCloseTimer()
                    webSocket?.close(1000, "client_stop")
                }
            }

            SpeechmaticsServerEvent.EndOfUtterance -> {
                Log.i(TAG, "Speechmatics end-of-utterance received")
            }
        }
    }

    private fun maybeSendEndOfStream() {
        if (!isClosing || endOfStreamSent) return
        if (pendingAudioAcknowledgements > 0) return

        val socket = webSocket ?: return
        if (lastAcknowledgedSequenceNumber < 0) {
            socket.close(1000, "client_stop")
            return
        }

        if (!forceEndOfUtteranceSent) {
            val forcePayload = JSONObject()
                .put("message", "ForceEndOfUtterance")
                .toString()
            val forceSent = try {
                socket.send(forcePayload)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send ForceEndOfUtterance: ${e.message}")
                false
            }
            if (forceSent) {
                forceEndOfUtteranceSent = true
                Log.i(TAG, "Speechmatics ForceEndOfUtterance sent before EndOfStream")
            }
        }

        val payload = JSONObject()
            .put("message", "EndOfStream")
            .put("last_seq_no", lastAcknowledgedSequenceNumber)
            .toString()

        val sent = try {
            socket.send(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send EndOfStream: ${e.message}")
            false
        }

        if (sent) {
            endOfStreamSent = true
            Log.i(TAG, "Speechmatics EndOfStream sent (last_seq_no=$lastAcknowledgedSequenceNumber)")
        } else {
            socket.close(1000, "client_stop")
        }
    }

    private fun mapConnectionError(error: Throwable, response: Response?): String {
        val code = response?.code
        if (code != null) {
            return when (code) {
                401, 403 -> "Invalid Speechmatics API key. Please check Settings."
                429 -> "Speechmatics rate limited — too many requests"
                in 500..599 -> "Speechmatics service error ($code)"
                else -> "Speechmatics connection rejected ($code)"
            }
        }
        return when (error) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Speechmatics streaming timed out"
            is ConnectException -> "Could not connect to Speechmatics streaming"
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
