// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.voice.MaiTranscriptionClient

/** Azure resource credentials and deployment settings for MAI-Transcribe-2-Streaming. */
object TranscriptionPreferences {
    data class MaiConfig(
        val apiKey: String,
        val endpoint: String,
        val deployment: String,
        val autoDetectLanguage: Boolean,
    ) {
        fun validationError(): String? {
            if (apiKey.isBlank() || endpoint.isBlank() || deployment.isBlank()) {
                return "Configure the Azure endpoint, API key, and MAI deployment in Settings → Transcription."
            }
            if (apiKey.any { it == '\r' || it == '\n' }) return "Azure API key must be a single line."
            return try { MaiTranscriptionClient.buildStreamingUrl(endpoint); null }
                catch (e: IllegalArgumentException) { e.message }
        }
    }

    fun readMaiConfig(prefs: SharedPreferences) = MaiConfig(
        readMaiApiKey(prefs), readMaiEndpoint(prefs), readMaiDeployment(prefs),
        readMaiAutoDetectLanguage(prefs),
    )

    fun readMaiApiKey(prefs: SharedPreferences): String =
        prefs.getString(Settings.PREF_MAI_API_KEY, Defaults.PREF_MAI_API_KEY)?.trim().orEmpty()
    fun writeMaiApiKey(prefs: SharedPreferences, value: String) {
        prefs.edit { putString(Settings.PREF_MAI_API_KEY, value.trim()) }
    }
    fun readMaiEndpoint(prefs: SharedPreferences): String =
        prefs.getString(Settings.PREF_MAI_ENDPOINT, Defaults.PREF_MAI_ENDPOINT)?.trim().orEmpty()
    fun writeMaiEndpoint(prefs: SharedPreferences, value: String) {
        prefs.edit { putString(Settings.PREF_MAI_ENDPOINT, value.trim()) }
    }
    fun readMaiDeployment(prefs: SharedPreferences): String =
        prefs.getString(Settings.PREF_MAI_DEPLOYMENT, Defaults.PREF_MAI_DEPLOYMENT)?.trim().orEmpty()
    fun writeMaiDeployment(prefs: SharedPreferences, value: String) {
        prefs.edit { putString(Settings.PREF_MAI_DEPLOYMENT, value.trim()) }
    }
    fun readMaiAutoDetectLanguage(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(Settings.PREF_MAI_AUTO_DETECT_LANGUAGE, Defaults.PREF_MAI_AUTO_DETECT_LANGUAGE)
    fun writeMaiAutoDetectLanguage(prefs: SharedPreferences, value: Boolean) {
        prefs.edit { putBoolean(Settings.PREF_MAI_AUTO_DETECT_LANGUAGE, value) }
    }
}
