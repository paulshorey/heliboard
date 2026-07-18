package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Typed access to Soniox transcription preferences with cleanup for legacy
 * provider keys (Speechmatics, Deepgram).
 */
object TranscriptionPreferences {
    private const val LEGACY_SPEECHMATICS_API_KEY_PREF = "speechmatics_api_key"
    private const val LEGACY_DEEPGRAM_API_KEY_PREF = "deepgram_api_key"

    // Soniox documents max_endpoint_delay_ms must be between 500 and 3000.
    private const val MIN_MAX_ENDPOINT_DELAY_MS = 500
    private const val MAX_MAX_ENDPOINT_DELAY_MS = 3000

    const val DEFAULT_ENABLE_ENDPOINT_DETECTION = Defaults.PREF_SONIOX_ENABLE_ENDPOINT_DETECTION
    const val DEFAULT_MAX_ENDPOINT_DELAY_MS = Defaults.PREF_SONIOX_MAX_ENDPOINT_DELAY_MS
    const val DEFAULT_DIARIZATION = Defaults.PREF_SONIOX_DIARIZATION
    const val DEFAULT_REMOVE_COMMAS = Defaults.PREF_SONIOX_REMOVE_COMMAS

    /** Hard cap on user-defined custom terms, mirroring the client. */
    private const val MAX_CUSTOM_TERMS = 200

    /** Hard cap on the length of any individual custom term. */
    private const val MAX_CUSTOM_TERM_LENGTH = 100

    data class SonioxConfig(
        val apiKey: String,
        val enableEndpointDetection: Boolean,
        val maxEndpointDelayMs: Int,
        val diarizationEnabled: Boolean,
        val customTerms: List<String>,
        val removeCommas: Boolean
    )

    fun readSonioxApiKey(prefs: SharedPreferences): String {
        clearLegacyApiKeys(prefs)
        return prefs.getString(
            Settings.PREF_SONIOX_API_KEY,
            Defaults.PREF_SONIOX_API_KEY
        )?.trim().orEmpty()
    }

    fun writeSonioxApiKey(prefs: SharedPreferences, value: String) {
        val trimmedValue = value.trim()
        prefs.edit {
            putString(Settings.PREF_SONIOX_API_KEY, trimmedValue)
            remove(LEGACY_SPEECHMATICS_API_KEY_PREF)
            remove(LEGACY_DEEPGRAM_API_KEY_PREF)
        }
    }

    fun readSonioxConfig(prefs: SharedPreferences): SonioxConfig {
        return SonioxConfig(
            apiKey = readSonioxApiKey(prefs),
            enableEndpointDetection = prefs.getBoolean(
                Settings.PREF_SONIOX_ENABLE_ENDPOINT_DETECTION,
                Defaults.PREF_SONIOX_ENABLE_ENDPOINT_DETECTION
            ),
            maxEndpointDelayMs = sanitizeMaxEndpointDelayMs(
                prefs.getInt(
                    Settings.PREF_SONIOX_MAX_ENDPOINT_DELAY_MS,
                    Defaults.PREF_SONIOX_MAX_ENDPOINT_DELAY_MS
                )
            ),
            diarizationEnabled = prefs.getBoolean(
                Settings.PREF_SONIOX_DIARIZATION,
                Defaults.PREF_SONIOX_DIARIZATION
            ),
            customTerms = readSonioxCustomTerms(prefs),
            removeCommas = prefs.getBoolean(
                Settings.PREF_SONIOX_REMOVE_COMMAS,
                Defaults.PREF_SONIOX_REMOVE_COMMAS
            )
        )
    }

    /**
     * Read user-defined custom Soniox `context.terms`. The preference is stored
     * as a single string with one term per line; this helper splits, trims,
     * deduplicates and clamps to [MAX_CUSTOM_TERMS] entries.
     */
    fun readSonioxCustomTerms(prefs: SharedPreferences): List<String> {
        val raw = prefs.getString(
            Settings.PREF_SONIOX_CUSTOM_TERMS,
            Defaults.PREF_SONIOX_CUSTOM_TERMS
        ).orEmpty()
        return parseCustomTerms(raw)
    }

    /** Stored form (as the user sees it) — preserves their order, trims whitespace. */
    fun readSonioxCustomTermsRaw(prefs: SharedPreferences): String {
        return prefs.getString(
            Settings.PREF_SONIOX_CUSTOM_TERMS,
            Defaults.PREF_SONIOX_CUSTOM_TERMS
        ).orEmpty()
    }

    fun writeSonioxCustomTerms(prefs: SharedPreferences, value: String) {
        prefs.edit {
            putString(Settings.PREF_SONIOX_CUSTOM_TERMS, value)
        }
    }

    internal fun parseCustomTerms(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val clamped = if (trimmed.length > MAX_CUSTOM_TERM_LENGTH) {
                trimmed.substring(0, MAX_CUSTOM_TERM_LENGTH)
            } else {
                trimmed
            }
            if (seen.add(clamped) && seen.size >= MAX_CUSTOM_TERMS) break
        }
        return seen.toList()
    }

    fun writeSonioxEnableEndpointDetection(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(Settings.PREF_SONIOX_ENABLE_ENDPOINT_DETECTION, enabled)
        }
    }

    fun writeSonioxMaxEndpointDelayMs(prefs: SharedPreferences, value: Int) {
        prefs.edit {
            putInt(
                Settings.PREF_SONIOX_MAX_ENDPOINT_DELAY_MS,
                sanitizeMaxEndpointDelayMs(value)
            )
        }
    }

    fun writeSonioxDiarization(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(Settings.PREF_SONIOX_DIARIZATION, enabled)
        }
    }

    fun writeSonioxRemoveCommas(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(Settings.PREF_SONIOX_REMOVE_COMMAS, enabled)
        }
    }

    fun readSonioxEnableEndpointDetection(prefs: SharedPreferences): Boolean {
        return readSonioxConfig(prefs).enableEndpointDetection
    }

    fun readSonioxMaxEndpointDelayMs(prefs: SharedPreferences): Int {
        return readSonioxConfig(prefs).maxEndpointDelayMs
    }

    fun readSonioxDiarization(prefs: SharedPreferences): Boolean {
        return readSonioxConfig(prefs).diarizationEnabled
    }

    fun readSonioxRemoveCommas(prefs: SharedPreferences): Boolean {
        return readSonioxConfig(prefs).removeCommas
    }

    fun sanitizeMaxEndpointDelayMs(value: Int): Int {
        return value.coerceIn(MIN_MAX_ENDPOINT_DELAY_MS, MAX_MAX_ENDPOINT_DELAY_MS)
    }

    private fun clearLegacyApiKeys(prefs: SharedPreferences) {
        val hasLegacySpeechmatics = prefs.contains(LEGACY_SPEECHMATICS_API_KEY_PREF)
        val hasLegacyDeepgram = prefs.contains(LEGACY_DEEPGRAM_API_KEY_PREF)
        if (!hasLegacySpeechmatics && !hasLegacyDeepgram) return

        prefs.edit {
            remove(LEGACY_SPEECHMATICS_API_KEY_PREF)
            remove(LEGACY_DEEPGRAM_API_KEY_PREF)
        }
    }
}
