package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.voice.GeminiTranscriptionClient

/**
 * Typed access to Gemini Live transcription preferences.
 *
 * Also erases the preferences of the speech providers this app used before
 * Gemini. Their API keys belong to other accounts and their tuning knobs have no
 * Gemini equivalent, so nothing is carried over except the user's own vocabulary
 * list, which is provider-independent. The literal key names below are what is
 * already written to disk on existing installs, so they have to be spelled out
 * here even though nothing else references those providers.
 */
object TranscriptionPreferences {
    private const val LEGACY_CUSTOM_TERMS_PREF = "soniox_custom_terms"

    private val LEGACY_PROVIDER_PREFS = listOf(
        "speechmatics_api_key",
        "deepgram_api_key",
        "soniox_api_key",
        "soniox_enable_endpoint_detection",
        "soniox_max_endpoint_delay_ms",
        "soniox_diarization",
        LEGACY_CUSTOM_TERMS_PREF
    )

    /** Hard cap on user-defined vocabulary terms, mirroring the client. */
    private const val MAX_CUSTOM_VOCABULARY_TERMS =
        GeminiTranscriptionClient.MAX_USER_VOCABULARY_TERMS

    /** Hard cap on the length of any individual vocabulary term. */
    private const val MAX_VOCABULARY_TERM_LENGTH = 100

    data class GeminiConfig(
        val apiKey: String,
        /** `SMART` (cleaned/formatted) or `VERBATIM` (literal). */
        val transcriptionMode: String,
        /** `automaticActivityDetection.silenceDurationMs`. */
        val endOfSpeechSilenceMs: Int,
        /** When true, `languageCodes` is left empty so Gemini detects the language. */
        val autoDetectLanguage: Boolean,
        /** When true, editor text near the caret seeds `customVocabulary`. */
        val useEditorContext: Boolean,
        val customVocabulary: List<String>
    )

    fun readGeminiApiKey(prefs: SharedPreferences): String {
        migrateLegacyProviderPrefs(prefs)
        return prefs.getString(
            Settings.PREF_GEMINI_API_KEY,
            Defaults.PREF_GEMINI_API_KEY
        )?.trim().orEmpty()
    }

    fun writeGeminiApiKey(prefs: SharedPreferences, value: String) {
        prefs.edit {
            putString(Settings.PREF_GEMINI_API_KEY, value.trim())
            LEGACY_PROVIDER_PREFS.forEach { remove(it) }
        }
    }

    fun readGeminiConfig(prefs: SharedPreferences): GeminiConfig {
        return GeminiConfig(
            apiKey = readGeminiApiKey(prefs),
            transcriptionMode = readGeminiTranscriptionMode(prefs),
            endOfSpeechSilenceMs = readGeminiEndOfSpeechSilenceMs(prefs),
            autoDetectLanguage = readGeminiAutoDetectLanguage(prefs),
            useEditorContext = readGeminiUseEditorContext(prefs),
            customVocabulary = readGeminiCustomVocabulary(prefs)
        )
    }

    fun readGeminiTranscriptionMode(prefs: SharedPreferences): String {
        return GeminiTranscriptionClient.sanitizeTranscriptionMode(
            prefs.getString(
                Settings.PREF_GEMINI_TRANSCRIPTION_MODE,
                Defaults.PREF_GEMINI_TRANSCRIPTION_MODE
            )
        )
    }

    fun writeGeminiTranscriptionMode(prefs: SharedPreferences, value: String) {
        prefs.edit {
            putString(
                Settings.PREF_GEMINI_TRANSCRIPTION_MODE,
                GeminiTranscriptionClient.sanitizeTranscriptionMode(value)
            )
        }
    }

    /** True when the settings toggle is on smart (cleaned) transcription. */
    fun readGeminiSmartMode(prefs: SharedPreferences): Boolean =
        readGeminiTranscriptionMode(prefs) == GeminiTranscriptionClient.TRANSCRIPTION_MODE_SMART

    fun writeGeminiSmartMode(prefs: SharedPreferences, smart: Boolean) {
        writeGeminiTranscriptionMode(
            prefs,
            if (smart) {
                GeminiTranscriptionClient.TRANSCRIPTION_MODE_SMART
            } else {
                GeminiTranscriptionClient.TRANSCRIPTION_MODE_VERBATIM
            }
        )
    }

    fun readGeminiEndOfSpeechSilenceMs(prefs: SharedPreferences): Int {
        return sanitizeEndOfSpeechSilenceMs(
            prefs.getInt(
                Settings.PREF_GEMINI_END_OF_SPEECH_SILENCE_MS,
                Defaults.PREF_GEMINI_END_OF_SPEECH_SILENCE_MS
            )
        )
    }

    fun writeGeminiEndOfSpeechSilenceMs(prefs: SharedPreferences, value: Int) {
        prefs.edit {
            putInt(
                Settings.PREF_GEMINI_END_OF_SPEECH_SILENCE_MS,
                sanitizeEndOfSpeechSilenceMs(value)
            )
        }
    }

    fun readGeminiAutoDetectLanguage(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(
            Settings.PREF_GEMINI_AUTO_DETECT_LANGUAGE,
            Defaults.PREF_GEMINI_AUTO_DETECT_LANGUAGE
        )
    }

    fun writeGeminiAutoDetectLanguage(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(Settings.PREF_GEMINI_AUTO_DETECT_LANGUAGE, enabled)
        }
    }

    fun readGeminiUseEditorContext(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(
            Settings.PREF_GEMINI_USE_EDITOR_CONTEXT,
            Defaults.PREF_GEMINI_USE_EDITOR_CONTEXT
        )
    }

    fun writeGeminiUseEditorContext(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(Settings.PREF_GEMINI_USE_EDITOR_CONTEXT, enabled)
        }
    }

    /**
     * Read the user's custom vocabulary. The preference is stored as a single
     * string with one term per line; this helper splits, trims, deduplicates and
     * clamps to [MAX_CUSTOM_VOCABULARY_TERMS] entries.
     */
    fun readGeminiCustomVocabulary(prefs: SharedPreferences): List<String> {
        return parseCustomVocabulary(readGeminiCustomVocabularyRaw(prefs))
    }

    /** Stored form (as the user sees it) — preserves their order and blank lines. */
    fun readGeminiCustomVocabularyRaw(prefs: SharedPreferences): String {
        return prefs.getString(
            Settings.PREF_GEMINI_CUSTOM_VOCABULARY,
            Defaults.PREF_GEMINI_CUSTOM_VOCABULARY
        ).orEmpty()
    }

    fun writeGeminiCustomVocabulary(prefs: SharedPreferences, value: String) {
        prefs.edit {
            putString(Settings.PREF_GEMINI_CUSTOM_VOCABULARY, value)
        }
    }

    internal fun parseCustomVocabulary(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val clamped = if (trimmed.length > MAX_VOCABULARY_TERM_LENGTH) {
                trimmed.substring(0, MAX_VOCABULARY_TERM_LENGTH)
            } else {
                trimmed
            }
            if (seen.add(clamped) && seen.size >= MAX_CUSTOM_VOCABULARY_TERMS) break
        }
        return seen.toList()
    }

    fun sanitizeEndOfSpeechSilenceMs(value: Int): Int {
        return value.coerceIn(
            GeminiTranscriptionClient.MIN_END_OF_SPEECH_SILENCE_MS,
            GeminiTranscriptionClient.MAX_END_OF_SPEECH_SILENCE_MS
        )
    }

    /**
     * Carry the user's hand-written vocabulary list over from the previous
     * provider's preference (only when they have not written a Gemini list yet),
     * then delete every preference belonging to the old providers.
     *
     * Called both from [readGeminiApiKey] and from app upgrade, so a restored
     * backup is cleaned up too, not just an in-place update.
     */
    fun migrateLegacyProviderPrefs(prefs: SharedPreferences) {
        if (LEGACY_PROVIDER_PREFS.none { prefs.contains(it) }) return
        val legacyTerms = prefs.getString(LEGACY_CUSTOM_TERMS_PREF, "").orEmpty()
        val currentVocabulary = readGeminiCustomVocabularyRaw(prefs)
        prefs.edit {
            if (legacyTerms.isNotBlank() && currentVocabulary.isBlank()) {
                putString(Settings.PREF_GEMINI_CUSTOM_VOCABULARY, legacyTerms)
            }
            LEGACY_PROVIDER_PREFS.forEach { remove(it) }
        }
    }
}
