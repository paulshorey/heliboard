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
 * Client for Google Gemini Live API real-time speech-to-text over WebSocket,
 * using the dedicated transcription model `gemini-3.5-transcribe-live`.
 *
 * Protocol overview (https://ai.google.dev/gemini-api/docs/live-api/live-transcribe,
 * https://ai.google.dev/api/live):
 * 1. Open `wss://generativelanguage.googleapis.com/ws/...BidiGenerateContent?key=API_KEY`.
 *    The API key travels in the query string; there is no auth header.
 * 2. Send exactly one `setup` JSON text frame. Wait for `{"setupComplete":{}}`
 *    before sending audio.
 * 3. Stream audio as JSON text frames containing base64 PCM16
 *    (`realtimeInput.audio`), ~100 ms per chunk.
 * 4. Read `serverContent.interimInputTranscription` (speculative, discarded here)
 *    and `serverContent.inputTranscription` (authoritative, committed to the
 *    editor). `serverContent.turnComplete` closes an utterance.
 * 5. `realtimeInput.audioStreamEnd` finalizes the current turn without ending
 *    the session ("Hybrid VAD"); audio may resume afterwards.
 *
 * Accuracy over latency
 * ---------------------
 * This client is deliberately tuned for transcription quality rather than
 * responsiveness, because dictated text goes straight into the user's editor
 * and a wrong word costs more than a slow one:
 *  - `mode: SMART` so Gemini removes disfluencies, resolves spoken
 *    self-corrections, and applies grammar/casing/punctuation polish.
 *  - `endOfSpeechSensitivity: END_SENSITIVITY_LOW` plus a long
 *    `silenceDurationMs` so mid-sentence thinking pauses do not split one
 *    utterance into fragments. Google documents that short silence windows
 *    make the model lose cross-fragment context and lower quality.
 *  - `customVocabulary` seeded from built-in terms, the user's list, and proper
 *    nouns already present in the editor, so dictated names match what the user
 *    has typed before.
 *
 * Setup-schema degradation
 * ------------------------
 * The Live API rejects an unsupported `setup` field by closing the socket with
 * code 1007, which would leave voice input permanently broken. The transcribe
 * model's documented feature list is narrower than the shared `setup` proto, so
 * the accuracy knobs above are sent in [SetupTier] order and the client retries
 * one tier lower on 1007. The last tier that worked is remembered for the rest
 * of the process, so the fallback costs at most one reconnect.
 */
class GeminiTranscriptionClient {

    companion object {
        private const val TAG = "GeminiTranscription"

        /**
         * Live API bidirectional endpoint for the Gemini Developer API. `v1beta`
         * is the version the currently served docs use for Live transcription.
         */
        internal const val STREAMING_URL_BASE =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /**
         * Endpoint the next session connects to. Only the WebSocket lifecycle
         * tests change this, so they can drive a local server through the real
         * connect/setup/stream/close path instead of mocking it away.
         */
        @Volatile
        internal var streamingEndpoint: String = STREAMING_URL_BASE

        /**
         * Dedicated real-time speech-to-text model. The raw WebSocket `setup.model`
         * field requires the `models/` resource prefix even though the SDKs accept
         * the bare id.
         */
        internal const val MODEL = "gemini-3.5-transcribe-live"
        internal const val MODEL_RESOURCE_NAME = "models/$MODEL"

        internal const val MIME_TYPE_PCM16 = "audio/pcm;rate=${VoiceRecorder.SAMPLE_RATE}"

        /** Smart transcription: cleaned, formatted, punctuated output. */
        internal const val TRANSCRIPTION_MODE_SMART = "SMART"

        /** Verbatim transcription: literal word-for-word output with disfluencies. */
        internal const val TRANSCRIPTION_MODE_VERBATIM = "VERBATIM"

        /**
         * Server-side end-of-speech silence window. Google warns that values in
         * the 100–200 ms range fragment one utterance and cost transcription
         * quality, so the usable floor here is deliberately well above that.
         */
        internal const val MIN_END_OF_SPEECH_SILENCE_MS = 400
        internal const val MAX_END_OF_SPEECH_SILENCE_MS = 5000

        /**
         * Required duration of detected speech before start-of-speech is
         * committed. The server keeps this much prefix audio, which is what
         * prevents the first syllable from being clipped.
         */
        internal const val PREFIX_PADDING_MS = 300

        /** Detect speech onset eagerly so no leading words are dropped. */
        internal const val START_OF_SPEECH_SENSITIVITY = "START_SENSITIVITY_HIGH"

        /** End speech reluctantly so pauses do not cut sentences in half. */
        internal const val END_OF_SPEECH_SENSITIVITY = "END_SENSITIVITY_LOW"

        /**
         * Live transcription sessions are capped at 10 minutes. Rotate a little
         * early so a long dictation never hits the hard abort mid-utterance.
         */
        internal const val SESSION_ROTATE_AFTER_MS = 9L * 60L * 1000L

        /** How long to keep reading after `audioStreamEnd` before closing. */
        private const val FINALIZE_CLOSE_GRACE_MS = 8_000L

        /** OkHttp protocol ping cadence; the Live API has no app-level keepalive. */
        private const val PING_INTERVAL_SECONDS = 20L

        /**
         * WebSocket close code the Live API uses for a malformed or unsupported
         * `setup` payload (gRPC `INVALID_ARGUMENT`).
         */
        internal const val CLOSE_CODE_INVALID_ARGUMENT = 1007

        /** Maximum `customVocabulary` phrases sent. */
        internal const val MAX_CUSTOM_VOCABULARY_TERMS = 100

        /** Hard cap on the number of user-defined vocabulary terms. */
        internal const val MAX_USER_VOCABULARY_TERMS = 200

        private val PUNCTUATION_ATTACHING_TO_PREVIOUS = setOf(
            '.', ',', '!', '?', ':', ';', ')', ']', '}', '%', '\u2026'
        )

        /**
         * Static dictation guidance sent as `systemInstruction`. Live
         * Transcription does not advertise system-instruction support, so this
         * may be ignored; it lives in the top [SetupTier] so a rejection
         * degrades instead of breaking the session.
         *
         * Deliberately contains no editor text. Feeding the already-typed
         * paragraph to a generative model risks it echoing that text back as
         * transcription, and `customVocabulary` is the documented channel for
         * conveying what the user has written.
         */
        internal const val SYSTEM_INSTRUCTION =
            "You are transcribing dictation typed into a mobile keyboard. " +
                "Transcribe only what the speaker says, with natural sentence " +
                "structure, capitalization and punctuation. Prefer waiting for " +
                "a complete phrase over guessing a word. Never add commentary, " +
                "answers, translations or text the speaker did not say."

        /**
         * Ordered `setup` payload variants, most capable first. On close code
         * [CLOSE_CODE_INVALID_ARGUMENT] the client retries with [next].
         */
        enum class SetupTier {
            /** Everything: dictation system instruction + tuned VAD + full transcription config. */
            FULL,

            /** Drop `systemInstruction` (not in the transcribe model's documented feature list). */
            NO_SYSTEM_INSTRUCTION,

            /** Also drop `realtimeInputConfig` VAD tuning; keep smart mode and vocabulary. */
            NO_REALTIME_CONFIG,

            /** Documented minimum: model + TEXT modality + language codes only. */
            MINIMAL;

            fun next(): SetupTier? = when (this) {
                FULL -> NO_SYSTEM_INSTRUCTION
                NO_SYSTEM_INSTRUCTION -> NO_REALTIME_CONFIG
                NO_REALTIME_CONFIG -> MINIMAL
                MINIMAL -> null
            }
        }

        /**
         * Highest [SetupTier] known to be accepted by the server in this process.
         * Starts at [SetupTier.FULL] and only ever moves down, so a schema
         * mismatch costs one reconnect per app run instead of one per session.
         */
        @Volatile
        internal var negotiatedSetupTier: SetupTier = SetupTier.FULL

        internal data class SessionConfig(
            /** BCP-47 codes sent as `inputAudioTranscription.languageCodes`. Empty = auto-detect. */
            val languageCodes: List<String>,
            /** `SMART` or `VERBATIM`. */
            val transcriptionMode: String,
            /** `automaticActivityDetection.silenceDurationMs`. */
            val endOfSpeechSilenceMs: Int,
            /** `inputAudioTranscription.customVocabulary`. */
            val customVocabulary: List<String>
        )

        internal fun buildSessionConfig(
            languageTag: String?,
            autoDetectLanguage: Boolean,
            transcriptionMode: String,
            endOfSpeechSilenceMs: Int,
            userVocabulary: List<String> = emptyList(),
            editorContext: String? = null,
            builtInVocabulary: List<String> = defaultVocabulary()
        ): SessionConfig {
            val resolvedLanguage = if (autoDetectLanguage) null else resolveLanguageCode(languageTag)
            return SessionConfig(
                languageCodes = listOfNotNull(resolvedLanguage),
                transcriptionMode = sanitizeTranscriptionMode(transcriptionMode),
                endOfSpeechSilenceMs = endOfSpeechSilenceMs.coerceIn(
                    MIN_END_OF_SPEECH_SILENCE_MS,
                    MAX_END_OF_SPEECH_SILENCE_MS
                ),
                customVocabulary = VoiceContextVocabulary.build(
                    userTerms = userVocabulary,
                    builtInTerms = builtInVocabulary,
                    editorContext = editorContext,
                    limit = MAX_CUSTOM_VOCABULARY_TERMS
                )
            )
        }

        internal fun sanitizeTranscriptionMode(mode: String?): String =
            if (mode?.uppercase(Locale.US) == TRANSCRIPTION_MODE_VERBATIM) {
                TRANSCRIPTION_MODE_VERBATIM
            } else {
                TRANSCRIPTION_MODE_SMART
            }

        /**
         * Product and technical terms that ship with the app. Merged with the
         * user's list and with proper nouns found in the editor.
         */
        internal fun defaultVocabulary(): List<String> = listOf(
            "HeliBoard",
            "Gemini",
            "Kubernetes",
            "API",
            "gnocchi"
        )

        internal fun buildStreamingUrl(apiKey: String): String =
            "$streamingEndpoint?key=$apiKey"

        internal fun buildSetupMessage(config: SessionConfig, tier: SetupTier): String {
            val transcription = JSONObject()
                // Explicit empty array is the documented way to request
                // automatic language identification.
                .put("languageCodes", JSONArray().apply { config.languageCodes.forEach { put(it) } })
            if (tier != SetupTier.MINIMAL) {
                transcription.put("mode", config.transcriptionMode)
                if (config.customVocabulary.isNotEmpty()) {
                    transcription.put(
                        "customVocabulary",
                        JSONArray().apply { config.customVocabulary.forEach { put(it) } }
                    )
                }
            }

            val setup = JSONObject()
                .put("model", MODEL_RESOURCE_NAME)
                .put(
                    "generationConfig",
                    JSONObject().put("responseModalities", JSONArray().put("TEXT"))
                )
                // `inputAudioTranscription` is a sibling of `generationConfig`,
                // not a child. Nesting it inside closes the socket with 1007.
                .put("inputAudioTranscription", transcription)

            if (tier == SetupTier.FULL || tier == SetupTier.NO_SYSTEM_INSTRUCTION) {
                setup.put(
                    "realtimeInputConfig",
                    JSONObject().put(
                        "automaticActivityDetection",
                        JSONObject()
                            // Server VAD stays on for accurate speech onset with
                            // prefix padding; the client only adds an early
                            // finalize via audioStreamEnd (Hybrid VAD).
                            .put("disabled", false)
                            .put("startOfSpeechSensitivity", START_OF_SPEECH_SENSITIVITY)
                            .put("prefixPaddingMs", PREFIX_PADDING_MS)
                            .put("endOfSpeechSensitivity", END_OF_SPEECH_SENSITIVITY)
                            .put("silenceDurationMs", config.endOfSpeechSilenceMs)
                    )
                )
            }

            if (tier == SetupTier.FULL) {
                setup.put(
                    "systemInstruction",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION))
                    )
                )
            }

            return JSONObject().put("setup", setup).toString()
        }

        internal fun buildAudioMessage(pcmData: ByteArray): String {
            return JSONObject().put(
                "realtimeInput",
                JSONObject().put(
                    "audio",
                    JSONObject()
                        // okio emits unwrapped base64; embedded newlines corrupt
                        // the payload.
                        .put("data", pcmData.toByteString().base64())
                        .put("mimeType", MIME_TYPE_PCM16)
                )
            ).toString()
        }

        /**
         * Finalizes the current turn without ending the session. The server
         * treats this as an immediate end-of-speech, bypassing its silence
         * wait; audio sent afterwards reopens the stream.
         */
        internal val AUDIO_STREAM_END_MESSAGE: String =
            JSONObject().put(
                "realtimeInput",
                JSONObject().put("audioStreamEnd", true)
            ).toString()

        internal fun isSetupComplete(json: JSONObject): Boolean = json.has("setupComplete")

        /** Authoritative transcript text, or null when this message has none. */
        internal fun extractFinalTranscript(json: JSONObject): String? =
            json.optJSONObject("serverContent")
                ?.optJSONObject("inputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotEmpty() }

        /**
         * Speculative partial hypothesis. Never committed to the editor — it is
         * only evidence that the server is alive and processing audio.
         */
        internal fun extractInterimTranscript(json: JSONObject): String? =
            json.optJSONObject("serverContent")
                ?.optJSONObject("interimInputTranscription")
                ?.optString("text")
                ?.takeIf { it.isNotEmpty() }

        internal fun isTurnComplete(json: JSONObject): Boolean =
            json.optJSONObject("serverContent")?.optBoolean("turnComplete", false) == true

        /**
         * Remaining milliseconds from a `goAway` notice, or null when the
         * message is not a `goAway`. `timeLeft` is a protobuf Duration, so it
         * arrives as a string such as `"30s"` or `"10.5s"`.
         */
        internal fun extractGoAwayMillis(json: JSONObject): Long? {
            val goAway = json.optJSONObject("goAway") ?: return null
            val raw = goAway.optString("timeLeft", "")
            val seconds = raw.removeSuffix("s").toDoubleOrNull() ?: return 0L
            return (seconds * 1000).toLong().coerceAtLeast(0L)
        }

        /** Error payload sent in-band rather than as a close frame. */
        internal fun extractErrorMessage(json: JSONObject): String? {
            val error = json.optJSONObject("error") ?: return null
            val message = error.optString("message", "")
            val status = error.optString("status", "")
            val friendly = friendlyErrorForStatus(status)
            return when {
                friendly != null -> friendly
                message.isNotBlank() && status.isNotBlank() -> "$status: $message"
                message.isNotBlank() -> message
                status.isNotBlank() -> status
                else -> "Gemini reported an unknown error"
            }
        }

        internal fun friendlyErrorForStatus(status: String): String? = when (status) {
            "UNAUTHENTICATED" -> "Invalid Gemini API key. Please check Settings."
            "PERMISSION_DENIED" ->
                "This Gemini API key is not allowed to use $MODEL."
            "RESOURCE_EXHAUSTED" -> "Gemini rate limited — too many requests"
            "FAILED_PRECONDITION" ->
                "Gemini requires billing to be enabled for this API key."
            "UNAVAILABLE" -> "Gemini is temporarily unavailable. Please try again."
            "DEADLINE_EXCEEDED" -> "Gemini streaming timed out"
            "NOT_FOUND" -> "The Gemini model $MODEL is not available for this key."
            else -> null
        }

        /**
         * User-facing message for a WebSocket close that was not requested by
         * the client. [reason] is the server's status detail, which for schema
         * problems names the offending field path and is worth surfacing.
         */
        internal fun describeCloseFailure(code: Int, reason: String): String {
            val trimmedReason = reason.trim()
            statusFromReason(trimmedReason)?.let { return it }
            return when (code) {
                CLOSE_CODE_INVALID_ARGUMENT ->
                    "Gemini rejected the transcription session setup"
                1008 -> "Gemini rejected this API key or project"
                1011 -> "Gemini stream ended unexpectedly"
                else -> if (trimmedReason.isEmpty()) {
                    "Gemini stream closed (code $code)"
                } else {
                    "Gemini stream closed: $trimmedReason"
                }
            }
        }

        /**
         * Map a gRPC status name embedded in a close reason or handshake body to
         * a short user-facing message.
         */
        internal fun statusFromReason(reason: String): String? {
            if (reason.isBlank()) return null
            val upper = reason.uppercase(Locale.US)
            for (status in FRIENDLY_STATUSES) {
                if (upper.contains(status)) return friendlyErrorForStatus(status)
            }
            if (upper.contains("API KEY NOT VALID") || upper.contains("API_KEY_INVALID")) {
                return "Invalid Gemini API key. Please check Settings."
            }
            return null
        }

        private val FRIENDLY_STATUSES = listOf(
            "UNAUTHENTICATED",
            "PERMISSION_DENIED",
            "RESOURCE_EXHAUSTED",
            "FAILED_PRECONDITION",
            "DEADLINE_EXCEEDED",
            "NOT_FOUND",
            "UNAVAILABLE"
        )

        /**
         * Canonical BCP-47 codes accepted by `gemini-3.5-transcribe-live`, from
         * the Live Transcription supported-languages table. Sending a code
         * outside this list risks the server rejecting the setup, so unknown
         * subtypes fall back to auto-detection.
         */
        internal val SUPPORTED_LANGUAGE_CODES: List<String> = listOf(
            "af-ZA", "am-ET", "ar-EG", "as-IN", "az-AZ", "be-BY", "bg-BG", "bn-BD", "bn-IN",
            "bs-BA", "ca-ES", "ceb", "cmn-Hans-CN", "cs-CZ", "da-DK", "de-DE", "el-GR",
            "en-GB", "en-IN", "en-US", "es-419", "es-US", "et-EE", "fa-IR", "fi-FI", "fil-PH",
            "fr-FR", "gl-ES", "gu-IN", "ha-NG", "he-IL", "hi-IN", "hr-HR", "hu-HU", "hy-AM",
            "id-ID", "is-IS", "it-IT", "ja-JP", "jv-ID", "ka-GE", "kea-CV", "kk-KZ", "km-KH",
            "kn-IN", "ko-KR", "ky-KG", "ln-CD", "lt-LT", "lv-LV", "mk-MK", "ml-IN", "mn-MN",
            "mr-IN", "ms-MY", "mt-MT", "my-MM", "nb-NO", "ne-NP", "nl-NL", "or-IN",
            "pa-Guru-IN", "pa-IN", "pl-PL", "pt-BR", "pt-PT", "ro-RO", "ru-RU", "rup-BG",
            "sd-Arab-IN", "sk-SK", "sl-SI", "sr-RS", "sv-SE", "sw-KE", "te-IN", "tg-TJ",
            "th-TH", "tr-TR", "uk-UA", "uz-UZ", "vi-VN", "yue-Hant-HK"
        )

        private val SUPPORTED_BY_LOWERCASE: Map<String, String> =
            SUPPORTED_LANGUAGE_CODES.associateBy { it.lowercase(Locale.US) }

        /**
         * Default regional variant per language subtag, used when the keyboard
         * subtype has no region or names one Gemini does not list. Includes the
         * legacy/alternate ISO codes Android still produces (`iw`, `in`, `ji`)
         * and the macro-language codes for Chinese, Norwegian and Tagalog.
         */
        private val LANGUAGE_DEFAULTS: Map<String, String> = mapOf(
            "af" to "af-ZA", "am" to "am-ET", "ar" to "ar-EG", "as" to "as-IN",
            "az" to "az-AZ", "be" to "be-BY", "bg" to "bg-BG", "bn" to "bn-IN",
            "bs" to "bs-BA", "ca" to "ca-ES", "ceb" to "ceb", "cmn" to "cmn-Hans-CN",
            "cs" to "cs-CZ", "da" to "da-DK", "de" to "de-DE", "el" to "el-GR",
            "en" to "en-US", "es" to "es-419", "et" to "et-EE", "fa" to "fa-IR",
            "fi" to "fi-FI", "fil" to "fil-PH", "fr" to "fr-FR", "gl" to "gl-ES",
            "gu" to "gu-IN", "ha" to "ha-NG", "he" to "he-IL", "hi" to "hi-IN",
            "hr" to "hr-HR", "hu" to "hu-HU", "hy" to "hy-AM", "id" to "id-ID",
            "in" to "id-ID", "is" to "is-IS", "it" to "it-IT", "iw" to "he-IL",
            "ja" to "ja-JP", "ji" to "he-IL", "jv" to "jv-ID", "ka" to "ka-GE",
            "kea" to "kea-CV", "kk" to "kk-KZ", "km" to "km-KH", "kn" to "kn-IN",
            "ko" to "ko-KR", "ky" to "ky-KG", "ln" to "ln-CD", "lt" to "lt-LT",
            "lv" to "lv-LV", "mk" to "mk-MK", "ml" to "ml-IN", "mn" to "mn-MN",
            "mr" to "mr-IN", "ms" to "ms-MY", "mt" to "mt-MT", "my" to "my-MM",
            "nb" to "nb-NO", "ne" to "ne-NP", "nl" to "nl-NL", "no" to "nb-NO",
            "or" to "or-IN", "pa" to "pa-IN", "pl" to "pl-PL", "pt" to "pt-BR",
            "ro" to "ro-RO", "ru" to "ru-RU", "rup" to "rup-BG", "sd" to "sd-Arab-IN",
            "sk" to "sk-SK", "sl" to "sl-SI", "sr" to "sr-RS", "sv" to "sv-SE",
            "sw" to "sw-KE", "te" to "te-IN", "tg" to "tg-TJ", "th" to "th-TH",
            "tl" to "fil-PH", "tr" to "tr-TR", "uk" to "uk-UA", "uz" to "uz-UZ",
            "vi" to "vi-VN", "yue" to "yue-Hant-HK", "zh" to "cmn-Hans-CN"
        )

        /**
         * Map a keyboard subtype language tag to a BCP-47 code Gemini accepts.
         * Returns null when nothing matches, which leaves `languageCodes` empty
         * so the model auto-detects instead of being biased toward the wrong
         * language.
         */
        internal fun resolveLanguageCode(languageTag: String?): String? {
            val normalized = languageTag?.trim()?.replace('_', '-').orEmpty()
            if (normalized.isEmpty()) return null
            val lower = normalized.lowercase(Locale.US)
            if (lower == "und" || lower == "zz") return null
            SUPPORTED_BY_LOWERCASE[lower]?.let { return it }

            // Try progressively shorter prefixes so `zh-Hans-CN` matches
            // `zh-Hans` and then `zh`.
            val parts = lower.split('-').filter { it.isNotEmpty() }
            for (end in parts.size downTo 1) {
                val prefix = parts.subList(0, end).joinToString("-")
                SUPPORTED_BY_LOWERCASE[prefix]?.let { return it }
                LANGUAGE_DEFAULTS[prefix]?.let { return it }
            }
            return null
        }

        /**
         * Reconstructs editor-ready segments from the server's finalized
         * transcripts.
         *
         * The Live API has emitted finalized input transcriptions both as
         * per-utterance deltas and as text that grows on each message, and the
         * semantics have changed between model generations. Comparing each
         * message against the previous one covers both: a message that extends
         * the previous one contributes only its suffix, an unrelated message
         * contributes all of its text, and an identical repeat (which happens on
         * reconnect) contributes nothing.
         */
        internal class TranscriptAccumulator {
            private var lastRawText: String = ""
            private var emittedTailIsWordy: Boolean = false

            /** Forget turn state so the next transcript starts a fresh comparison. */
            fun reset() {
                lastRawText = ""
                emittedTailIsWordy = false
            }

            fun accept(rawText: String): TranscriptSegment? {
                if (rawText.isEmpty()) return null

                val previousRaw = lastRawText
                val isPrefixExtension = previousRaw.isNotEmpty() &&
                    rawText.length > previousRaw.length &&
                    rawText.startsWith(previousRaw)
                val newPart = when {
                    previousRaw.isEmpty() -> rawText
                    rawText == previousRaw -> return null
                    isPrefixExtension -> rawText.substring(previousRaw.length)
                    else -> rawText
                }
                lastRawText = rawText

                val trimmed = newPart.trim()
                if (trimmed.isEmpty()) return null

                // A suffix of a growing transcript can resume mid-word (the
                // server split "heading" into "head" + "ing"). The missing
                // leading whitespace is the only signal, so it is only trusted
                // when the previously emitted text also ended inside a word.
                val continuesWord = isPrefixExtension &&
                    emittedTailIsWordy &&
                    !newPart[0].isWhitespace()
                val attachesToPrevious =
                    trimmed[0] in PUNCTUATION_ATTACHING_TO_PREVIOUS || continuesWord
                emittedTailIsWordy = isWordyContinuationChar(trimmed.last())

                return TranscriptSegment(text = trimmed, attachesToPrevious = attachesToPrevious)
            }
        }

        /**
         * A "wordy" character would form a continuous word if immediately
         * followed by another non-space character. Apostrophes and hyphens count
         * because they occur inside words (`don't`, `co-op`); sentence
         * punctuation does not.
         */
        private fun isWordyContinuationChar(c: Char): Boolean {
            if (c.isLetterOrDigit()) return true
            return c == '\'' || c == '\u2019' || c == '-' || c == '\u2010'
        }
    }

    interface StreamingCallback {
        /** `setupComplete` received — the session is configured and audio may flow. */
        fun onStreamReady()

        /** A finalized transcript segment ready to be committed at the caret. */
        fun onTranscriptionResult(segment: TranscriptSegment)

        /**
         * The server responded to audio we sent. [hasTranscriptText] is true
         * when the response carried a finalized transcript rather than only an
         * interim hypothesis or a turn boundary.
         */
        fun onServerResponse(hasTranscriptText: Boolean)

        /**
         * The server announced it will disconnect in [timeLeftMs]. The session
         * should be rotated onto a fresh connection before that happens.
         */
        fun onSessionExpiring(timeLeftMs: Long)

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
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
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
    private var isSessionReady = false

    @Volatile
    private var pendingFinalizeCloseRunnable: Runnable? = null

    private val accumulator = TranscriptAccumulator()

    /**
     * Set while the socket is open but `setupComplete` has not arrived. A 1007
     * close in this window means the `setup` payload was rejected, which is what
     * triggers [SetupTier] degradation.
     */
    @Volatile
    private var awaitingSetupTier: SetupTier? = null

    /**
     * Open a transcription session. [sessionConfig] is rendered at the highest
     * [SetupTier] still believed to work; if the server rejects it the client
     * reconnects itself one tier lower.
     */
    internal fun startStreaming(
        apiKey: String,
        sessionConfig: SessionConfig,
        callback: StreamingCallback
    ) {
        startStreaming(apiKey, sessionConfig, callback, negotiatedSetupTier)
    }

    private fun startStreaming(
        apiKey: String,
        sessionConfig: SessionConfig,
        callback: StreamingCallback,
        tier: SetupTier
    ) {
        val newToken = activeConnectionToken + 1
        activeConnectionToken = newToken
        stopStreamingInternal(cancel = true, clearCallback = false)

        this.callback = callback
        isClosing = false
        isSessionReady = false
        awaitingSetupTier = tier
        accumulator.reset()
        clearFinalizeCloseTimer()

        val request = Request.Builder()
            .url(buildStreamingUrl(apiKey))
            .build()

        Log.i(
            TAG,
            "VOICE_STEP_3 opening Gemini Live socket " +
                "(model=$MODEL, setupTier=$tier, " +
                "languages=${sessionConfig.languageCodes.ifEmpty { listOf("auto") }}, " +
                "mode=${sessionConfig.transcriptionMode}, " +
                "endOfSpeechSilenceMs=${sessionConfig.endOfSpeechSilenceMs}, " +
                "customVocabulary=${sessionConfig.customVocabulary.size})"
        )

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (newToken != activeConnectionToken) return
                this@GeminiTranscriptionClient.webSocket = webSocket

                val setupMessage = buildSetupMessage(sessionConfig, tier)
                val sent = try {
                    webSocket.send(setupMessage)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send Gemini setup message: ${e.message}")
                    false
                }
                if (!sent) {
                    postIfCurrent(newToken) {
                        callback.onStreamError("Gemini session could not be started")
                    }
                    return
                }
                // Audio must wait for setupComplete; onStreamReady is what
                // releases the manager's buffered chunks.
                Log.i(TAG, "Gemini setup sent (tier=$tier); awaiting setupComplete")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (newToken != activeConnectionToken) return
                handleMessage(newToken, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (newToken != activeConnectionToken) return
                // The Live API answers with JSON; some proxies deliver it as a
                // binary frame, so decode rather than discard.
                handleMessage(newToken, bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (newToken != activeConnectionToken) return
                Log.i(TAG, "Gemini stream closing: code=$code, reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (newToken != activeConnectionToken) return
                clearFinalizeCloseTimer()
                val rejectedTier = awaitingSetupTier
                awaitingSetupTier = null
                isSessionReady = false
                this@GeminiTranscriptionClient.webSocket = null
                Log.i(TAG, "Gemini stream closed: code=$code, reason=$reason")

                if (!isClosing && rejectedTier != null &&
                    retrySetupWithLowerTier(rejectedTier, code, reason, apiKey, sessionConfig, callback)
                ) {
                    return
                }
                if (!isClosing && code != 1000) {
                    val message = describeCloseFailure(code, reason)
                    Log.e(TAG, "Gemini stream closed unexpectedly: $message")
                    postIfCurrent(newToken) {
                        this@GeminiTranscriptionClient.callback?.onStreamError(message)
                    }
                    return
                }
                postIfCurrent(newToken) {
                    this@GeminiTranscriptionClient.callback?.onStreamClosed()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (newToken != activeConnectionToken) return
                clearFinalizeCloseTimer()
                awaitingSetupTier = null
                isSessionReady = false
                this@GeminiTranscriptionClient.webSocket = null
                if (isClosing) {
                    Log.i(TAG, "Gemini stream failure after close request: ${t.message}")
                    postIfCurrent(newToken) {
                        this@GeminiTranscriptionClient.callback?.onStreamClosed()
                    }
                    return
                }
                val errorMessage = mapConnectionError(t, response)
                Log.e(TAG, "Gemini stream failure: $errorMessage (raw: ${t.message})")
                postIfCurrent(newToken) {
                    this@GeminiTranscriptionClient.callback?.onStreamError(errorMessage)
                }
            }
        })
    }

    /**
     * Reopen the session one [SetupTier] lower after the server rejected the
     * `setup` payload. Returns true when a retry was started, in which case the
     * caller must not report an error for this close.
     */
    private fun retrySetupWithLowerTier(
        rejectedTier: SetupTier,
        code: Int,
        reason: String,
        apiKey: String,
        sessionConfig: SessionConfig,
        callback: StreamingCallback
    ): Boolean {
        if (code != CLOSE_CODE_INVALID_ARGUMENT) return false
        // An invalid key also surfaces as a close frame; retrying the schema
        // would just repeat the same failure.
        if (statusFromReason(reason) != null) return false
        val fallback = rejectedTier.next() ?: return false
        negotiatedSetupTier = fallback
        Log.w(
            TAG,
            "Gemini rejected setup tier $rejectedTier (code=$code, reason=$reason); " +
                "retrying with $fallback"
        )
        startStreaming(apiKey, sessionConfig, callback, fallback)
        return true
    }

    fun sendAudioChunk(pcmData: ByteArray): Boolean {
        if (pcmData.isEmpty()) return true
        if (isClosing) return false
        val socket = webSocket ?: return false
        if (!isSessionReady) return false
        return try {
            socket.send(buildAudioMessage(pcmData))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
            false
        }
    }

    /**
     * Ask Gemini to finalize the current turn immediately instead of waiting out
     * its own silence window (Hybrid VAD). The session stays open and the next
     * audio chunk reopens the stream, so this is also the right signal to send
     * when the microphone is paused — leaving a turn open with no audio is what
     * makes the server drop the connection.
     */
    fun finalizeTurn(): Boolean {
        if (isClosing) return false
        val socket = webSocket ?: return false
        if (!isSessionReady) return false
        return try {
            socket.send(AUDIO_STREAM_END_MESSAGE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Gemini audioStreamEnd: ${e.message}")
            false
        }
    }

    /**
     * Gracefully end the stream: finalize the last turn, then keep reading for
     * up to [FINALIZE_CLOSE_GRACE_MS] so the trailing transcript still arrives.
     * Closing right after the last audio chunk is the usual way to lose the
     * final phrase.
     */
    fun finishStreaming() {
        val socket = webSocket ?: return
        val finalized = finalizeTurn()
        isClosing = true

        clearFinalizeCloseTimer()
        val connectionToken = activeConnectionToken
        val closeRunnable = Runnable {
            if (connectionToken != activeConnectionToken) return@Runnable
            val activeSocket = webSocket ?: return@Runnable
            Log.i(TAG, "Gemini finalize grace elapsed; closing socket")
            activeSocket.close(1000, "client_stop")
        }
        pendingFinalizeCloseRunnable = closeRunnable
        mainHandler.postDelayed(closeRunnable, FINALIZE_CLOSE_GRACE_MS)

        if (finalized) {
            Log.i(TAG, "Gemini audioStreamEnd sent; awaiting final transcript")
        } else {
            clearFinalizeCloseTimer()
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
        isSessionReady = false
        awaitingSetupTier = null
        accumulator.reset()
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
            Log.e(TAG, "Failed to parse Gemini message: ${e.message}")
            return
        }

        extractErrorMessage(json)?.let { description ->
            Log.e(TAG, "Gemini stream error event: $description")
            postIfCurrent(connectionToken) {
                callback?.onStreamError(description)
            }
            return
        }

        if (isSetupComplete(json)) {
            awaitingSetupTier = null
            isSessionReady = true
            Log.i(TAG, "Gemini setupComplete received; stream ready")
            postIfCurrent(connectionToken) {
                callback?.onStreamReady()
            }
            return
        }

        // A single server event can carry several fields at once, so every
        // branch below is checked independently.
        var sawResponse = false
        var sawTranscript = false

        if (extractInterimTranscript(json) != null) {
            sawResponse = true
        }

        extractFinalTranscript(json)?.let { finalText ->
            sawResponse = true
            val segment = accumulator.accept(finalText)
            if (segment != null) {
                sawTranscript = true
                Log.i(
                    TAG,
                    "VOICE_STEP_4 Gemini final transcript (${segment.text.length} chars)"
                )
                postIfCurrent(connectionToken) {
                    callback?.onTranscriptionResult(segment)
                }
            } else {
                Log.i(TAG, "Gemini final transcript added no new text; ignoring")
            }
        }

        if (isTurnComplete(json)) {
            sawResponse = true
            accumulator.reset()
        }

        if (sawResponse) {
            postIfCurrent(connectionToken) {
                callback?.onServerResponse(sawTranscript)
            }
        }

        extractGoAwayMillis(json)?.let { timeLeftMs ->
            Log.w(TAG, "Gemini goAway received; ${timeLeftMs}ms left on this connection")
            postIfCurrent(connectionToken) {
                callback?.onSessionExpiring(timeLeftMs)
            }
        }
    }

    private fun mapConnectionError(error: Throwable, response: Response?): String {
        val code = response?.code
        if (code != null) {
            return when (code) {
                400 -> "Gemini rejected the request. Check the API key in Settings."
                401, 403 -> "Invalid Gemini API key. Please check Settings."
                404 -> "The Gemini model $MODEL is not available for this key."
                429 -> "Gemini rate limited — too many requests"
                in 500..599 -> "Gemini service error ($code)"
                else -> "Gemini connection rejected ($code)"
            }
        }
        return when (error) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Gemini streaming timed out"
            is ConnectException -> "Could not connect to Gemini streaming"
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
