package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit

object TranscriptionPreferences {
    private const val LEGACY_PROVIDER_API_KEY_PREF = "deepgram_api_key"

    fun readSpeechmaticsApiKey(prefs: SharedPreferences): String {
        clearLegacyProviderKey(prefs)
        return prefs.getString(
            Settings.PREF_SPEECHMATICS_API_KEY,
            Defaults.PREF_SPEECHMATICS_API_KEY
        )?.trim().orEmpty()
    }

    fun writeSpeechmaticsApiKey(prefs: SharedPreferences, value: String) {
        val trimmedValue = value.trim()
        prefs.edit {
            putString(Settings.PREF_SPEECHMATICS_API_KEY, trimmedValue)
            remove(LEGACY_PROVIDER_API_KEY_PREF)
        }
    }

    private fun clearLegacyProviderKey(prefs: SharedPreferences) {
        if (!prefs.contains(LEGACY_PROVIDER_API_KEY_PREF)) {
            return
        }
        prefs.edit {
            remove(LEGACY_PROVIDER_API_KEY_PREF)
        }
    }
}
