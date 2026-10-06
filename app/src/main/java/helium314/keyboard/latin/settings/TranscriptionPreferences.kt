// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit

/** Speech resource credentials for direct MAI-Transcribe-2-Streaming access. */
object TranscriptionPreferences {
    val supportedRegions = setOf("centralus", "swedencentral", "southeastasia")
    const val MIN_VOICE_CHUNK_SILENCE_MS = 100
    const val MAX_VOICE_CHUNK_SILENCE_MS = 30_000
    private const val LEGACY_CHUNK_SILENCE_SECONDS = "voice_chunk_silence_seconds"

    fun readVoiceChunkSilenceMs(prefs: SharedPreferences): Int {
        if (prefs.contains(LEGACY_CHUNK_SILENCE_SECONDS)) {
            val milliseconds = if (prefs.contains(Settings.PREF_VOICE_CHUNK_SILENCE_MS)) {
                prefs.getInt(Settings.PREF_VOICE_CHUNK_SILENCE_MS, Defaults.PREF_VOICE_CHUNK_SILENCE_MS)
            } else {
                // Convert saved durations before changing units; clamp before multiplication.
                prefs.getInt(LEGACY_CHUNK_SILENCE_SECONDS, 1).coerceIn(1, 30) * 1000
            }
            writeVoiceChunkSilenceMs(prefs, milliseconds)
        }
        return prefs.getInt(Settings.PREF_VOICE_CHUNK_SILENCE_MS, Defaults.PREF_VOICE_CHUNK_SILENCE_MS)
            .coerceIn(MIN_VOICE_CHUNK_SILENCE_MS, MAX_VOICE_CHUNK_SILENCE_MS)
    }

    fun writeVoiceChunkSilenceMs(prefs: SharedPreferences, milliseconds: Int) {
        prefs.edit {
            putInt(Settings.PREF_VOICE_CHUNK_SILENCE_MS,
                milliseconds.coerceIn(MIN_VOICE_CHUNK_SILENCE_MS, MAX_VOICE_CHUNK_SILENCE_MS))
            remove(LEGACY_CHUNK_SILENCE_SECONDS)
        }
    }

    data class MaiConfig(
        val apiKey: String,
        val region: String,
        val autoDetectLanguage: Boolean,
    ) {
        fun validationError(): String? {
            if (apiKey.isBlank()) return "Enter your Azure Speech API key in Settings → Transcription."
            if (apiKey.any { it == '\r' || it == '\n' }) return "Azure Speech API key must be a single line."
            if (region !in supportedRegions) {
                return "Choose Central US, Sweden Central, or Southeast Asia to match your Azure Speech resource."
            }
            return null
        }
    }

    fun readMaiConfig(prefs: SharedPreferences) = MaiConfig(
        readMaiApiKey(prefs), readMaiRegion(prefs), readMaiAutoDetectLanguage(prefs),
    )

    fun readMaiApiKey(prefs: SharedPreferences): String =
        prefs.getString(Settings.PREF_MAI_API_KEY, Defaults.PREF_MAI_API_KEY)?.trim().orEmpty()
    fun writeMaiApiKey(prefs: SharedPreferences, value: String) {
        prefs.edit { putString(Settings.PREF_MAI_API_KEY, value.trim()) }
    }
    fun readMaiRegion(prefs: SharedPreferences): String =
        prefs.getString(Settings.PREF_MAI_REGION, Defaults.PREF_MAI_REGION)?.trim()?.lowercase().orEmpty()
    fun writeMaiRegion(prefs: SharedPreferences, value: String) {
        prefs.edit { putString(Settings.PREF_MAI_REGION, value.trim().lowercase()) }
    }
    fun readMaiAutoDetectLanguage(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(Settings.PREF_MAI_AUTO_DETECT_LANGUAGE, Defaults.PREF_MAI_AUTO_DETECT_LANGUAGE)
    fun writeMaiAutoDetectLanguage(prefs: SharedPreferences, value: Boolean) {
        prefs.edit { putBoolean(Settings.PREF_MAI_AUTO_DETECT_LANGUAGE, value) }
    }
}
