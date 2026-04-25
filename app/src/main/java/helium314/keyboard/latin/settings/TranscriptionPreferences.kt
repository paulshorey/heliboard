package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Typed read/write helpers for the AssemblyAI Universal-Streaming voice
 * transcription preferences.
 *
 * The previous Speechmatics-era prefs are intentionally cleared on first read
 * — see [clearLegacyProviderKeys] — so this is a hard cutover, not a
 * coexistence. Tweaks to the legacy provider produced fragmented dictation
 * (one word per "sentence"); AssemblyAI Universal-Streaming uses a semantic
 * end-of-turn model that sidesteps the entire failure mode, so there is
 * nothing useful to migrate.
 */
object TranscriptionPreferences {
    private val LEGACY_PROVIDER_PREFS = listOf(
        "deepgram_api_key",
        "speechmatics_api_key",
        "speechmatics_max_delay_millis",
        "speechmatics_end_of_utterance_millis",
        "speechmatics_remove_disfluencies",
        "speechmatics_punctuation_sensitivity_percent",
        "speechmatics_diarization",
    )

    private const val MIN_CONFIDENCE_PERCENT = 0
    private const val MAX_CONFIDENCE_PERCENT = 100
    private const val MIN_TURN_SILENCE_MS = 0
    private const val MAX_TURN_SILENCE_MS = 10_000
    private const val MIN_MAX_TURN_SILENCE_MS = 80
    private const val MAX_MAX_TURN_SILENCE_MS = 30_000

    /** AssemblyAI's documented Universal-Streaming speech models. */
    val SUPPORTED_SPEECH_MODELS = listOf(
        "universal-streaming-english",
        "universal-streaming-multilingual",
        "u3-rt-pro",
        "whisper-rt",
    )

    data class AssemblyAIConfig(
        val apiKey: String,
        val speechModel: String,
        val formatTurns: Boolean,
        val endOfTurnConfidence: Double,
        val minTurnSilenceMs: Int,
        val maxTurnSilenceMs: Int,
        val useEuEndpoint: Boolean,
        val keyterms: List<String>,
    )

    fun readAssemblyAIApiKey(prefs: SharedPreferences): String {
        clearLegacyProviderKeys(prefs)
        return prefs.getString(
            Settings.PREF_ASSEMBLYAI_API_KEY,
            Defaults.PREF_ASSEMBLYAI_API_KEY
        )?.trim().orEmpty()
    }

    fun writeAssemblyAIApiKey(prefs: SharedPreferences, value: String) {
        val trimmed = value.trim()
        prefs.edit {
            putString(Settings.PREF_ASSEMBLYAI_API_KEY, trimmed)
            for (key in LEGACY_PROVIDER_PREFS) {
                remove(key)
            }
        }
    }

    fun readAssemblyAIConfig(prefs: SharedPreferences): AssemblyAIConfig {
        val speechModel = prefs.getString(
            Settings.PREF_ASSEMBLYAI_SPEECH_MODEL,
            Defaults.PREF_ASSEMBLYAI_SPEECH_MODEL
        )?.trim().orEmpty().let { configured ->
            if (configured.isBlank()) Defaults.PREF_ASSEMBLYAI_SPEECH_MODEL else configured
        }

        val confidencePercent = prefs.getInt(
            Settings.PREF_ASSEMBLYAI_END_OF_TURN_CONFIDENCE_PERCENT,
            Defaults.PREF_ASSEMBLYAI_END_OF_TURN_CONFIDENCE_PERCENT
        ).coerceIn(MIN_CONFIDENCE_PERCENT, MAX_CONFIDENCE_PERCENT)

        val minSilence = prefs.getInt(
            Settings.PREF_ASSEMBLYAI_MIN_TURN_SILENCE_MILLIS,
            Defaults.PREF_ASSEMBLYAI_MIN_TURN_SILENCE_MILLIS
        ).coerceIn(MIN_TURN_SILENCE_MS, MAX_TURN_SILENCE_MS)

        val maxSilence = prefs.getInt(
            Settings.PREF_ASSEMBLYAI_MAX_TURN_SILENCE_MILLIS,
            Defaults.PREF_ASSEMBLYAI_MAX_TURN_SILENCE_MILLIS
        ).coerceIn(MIN_MAX_TURN_SILENCE_MS, MAX_MAX_TURN_SILENCE_MS)
            .coerceAtLeast(minSilence)

        return AssemblyAIConfig(
            apiKey = readAssemblyAIApiKey(prefs),
            speechModel = speechModel,
            formatTurns = prefs.getBoolean(
                Settings.PREF_ASSEMBLYAI_FORMAT_TURNS,
                Defaults.PREF_ASSEMBLYAI_FORMAT_TURNS
            ),
            endOfTurnConfidence = confidencePercent / 100.0,
            minTurnSilenceMs = minSilence,
            maxTurnSilenceMs = maxSilence,
            useEuEndpoint = prefs.getBoolean(
                Settings.PREF_ASSEMBLYAI_USE_EU_ENDPOINT,
                Defaults.PREF_ASSEMBLYAI_USE_EU_ENDPOINT
            ),
            keyterms = readKeyterms(prefs),
        )
    }

    fun writeAssemblyAISpeechModel(prefs: SharedPreferences, value: String) {
        val trimmed = value.trim()
        prefs.edit {
            putString(
                Settings.PREF_ASSEMBLYAI_SPEECH_MODEL,
                if (trimmed.isBlank()) Defaults.PREF_ASSEMBLYAI_SPEECH_MODEL else trimmed
            )
        }
    }

    fun writeAssemblyAIFormatTurns(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(Settings.PREF_ASSEMBLYAI_FORMAT_TURNS, enabled)
        }
    }

    fun writeAssemblyAIEndOfTurnConfidencePercent(prefs: SharedPreferences, value: Int) {
        prefs.edit {
            putInt(
                Settings.PREF_ASSEMBLYAI_END_OF_TURN_CONFIDENCE_PERCENT,
                value.coerceIn(MIN_CONFIDENCE_PERCENT, MAX_CONFIDENCE_PERCENT)
            )
        }
    }

    fun writeAssemblyAIMinTurnSilenceMs(prefs: SharedPreferences, value: Int) {
        prefs.edit {
            putInt(
                Settings.PREF_ASSEMBLYAI_MIN_TURN_SILENCE_MILLIS,
                value.coerceIn(MIN_TURN_SILENCE_MS, MAX_TURN_SILENCE_MS)
            )
        }
    }

    fun writeAssemblyAIMaxTurnSilenceMs(prefs: SharedPreferences, value: Int) {
        prefs.edit {
            putInt(
                Settings.PREF_ASSEMBLYAI_MAX_TURN_SILENCE_MILLIS,
                value.coerceIn(MIN_MAX_TURN_SILENCE_MS, MAX_MAX_TURN_SILENCE_MS)
            )
        }
    }

    fun writeAssemblyAIUseEuEndpoint(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(Settings.PREF_ASSEMBLYAI_USE_EU_ENDPOINT, enabled)
        }
    }

    fun writeAssemblyAIKeyterms(prefs: SharedPreferences, keytermsText: String) {
        prefs.edit {
            putString(Settings.PREF_ASSEMBLYAI_KEYTERMS, keytermsText)
        }
    }

    fun readAssemblyAIKeytermsRaw(prefs: SharedPreferences): String {
        return prefs.getString(
            Settings.PREF_ASSEMBLYAI_KEYTERMS,
            Defaults.PREF_ASSEMBLYAI_KEYTERMS
        ).orEmpty()
    }

    private fun readKeyterms(prefs: SharedPreferences): List<String> {
        val raw = readAssemblyAIKeytermsRaw(prefs)
        if (raw.isBlank()) return emptyList()
        return raw.split('\n', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= 50 }
            .distinct()
            .take(100)
    }

    private fun clearLegacyProviderKeys(prefs: SharedPreferences) {
        val present = LEGACY_PROVIDER_PREFS.filter { prefs.contains(it) }
        if (present.isEmpty()) return
        prefs.edit {
            for (key in present) {
                remove(key)
            }
        }
    }
}
