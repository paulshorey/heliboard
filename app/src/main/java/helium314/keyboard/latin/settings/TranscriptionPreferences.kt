package helium314.keyboard.latin.settings

import android.content.SharedPreferences
import androidx.core.content.edit

object TranscriptionPreferences {
    private const val LEGACY_PROVIDER_API_KEY_PREF = "deepgram_api_key"
    private const val MIN_SPEECHMATICS_MAX_DELAY_TENTHS = 7
    private const val MAX_SPEECHMATICS_MAX_DELAY_TENTHS = 40
    private const val MIN_SPEECHMATICS_END_OF_UTTERANCE_MS = 0
    private const val MAX_SPEECHMATICS_END_OF_UTTERANCE_MS = 2000
    private const val MIN_PUNCTUATION_SENSITIVITY_PERCENT = 0
    private const val MAX_PUNCTUATION_SENSITIVITY_PERCENT = 100
    const val DEFAULT_MAX_DELAY_SECONDS = Defaults.PREF_SPEECHMATICS_MAX_DELAY_MILLIS / 1000.0
    const val DEFAULT_END_OF_UTTERANCE_SILENCE_TRIGGER_SECONDS =
        Defaults.PREF_SPEECHMATICS_END_OF_UTTERANCE_MILLIS / 1000.0
    const val DEFAULT_REMOVE_DISFLUENCIES = Defaults.PREF_SPEECHMATICS_REMOVE_DISFLUENCIES
    const val DEFAULT_PUNCTUATION_SENSITIVITY =
        Defaults.PREF_SPEECHMATICS_PUNCTUATION_SENSITIVITY_PERCENT / 100.0
    const val DEFAULT_DIARIZATION = Defaults.PREF_SPEECHMATICS_DIARIZATION

    data class SpeechmaticsConfig(
        val apiKey: String,
        val maxDelaySeconds: Double,
        val endOfUtteranceSilenceSeconds: Double,
        val removeDisfluencies: Boolean,
        val punctuationSensitivity: Double,
        val diarizationEnabled: Boolean
    )

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

    fun readSpeechmaticsConfig(prefs: SharedPreferences): SpeechmaticsConfig {
        val maxDelayTenths = prefs.getInt(
            Settings.PREF_SPEECHMATICS_MAX_DELAY_MILLIS,
            Defaults.PREF_SPEECHMATICS_MAX_DELAY_MILLIS
        ) / 100
        val sanitizedMaxDelayTenths = maxDelayTenths.coerceIn(
            MIN_SPEECHMATICS_MAX_DELAY_TENTHS,
            MAX_SPEECHMATICS_MAX_DELAY_TENTHS
        ).coerceIn(
            MIN_SPEECHMATICS_MAX_DELAY_TENTHS,
            MAX_SPEECHMATICS_MAX_DELAY_TENTHS
        )

        val endOfUtteranceMs = prefs.getInt(
            Settings.PREF_SPEECHMATICS_END_OF_UTTERANCE_MILLIS,
            Defaults.PREF_SPEECHMATICS_END_OF_UTTERANCE_MILLIS
        ).coerceIn(
            MIN_SPEECHMATICS_END_OF_UTTERANCE_MS,
            MAX_SPEECHMATICS_END_OF_UTTERANCE_MS
        )

        val maxDelaySeconds = sanitizedMaxDelayTenths / 10.0
        val sanitizedEndOfUtteranceMs = when {
            endOfUtteranceMs <= 0 -> 0
            endOfUtteranceMs >= sanitizedMaxDelayTenths * 100 -> (sanitizedMaxDelayTenths * 100) - 100
            else -> endOfUtteranceMs
        }.coerceIn(
            MIN_SPEECHMATICS_END_OF_UTTERANCE_MS,
            MAX_SPEECHMATICS_END_OF_UTTERANCE_MS
        )

        return SpeechmaticsConfig(
            apiKey = readSpeechmaticsApiKey(prefs),
            maxDelaySeconds = maxDelaySeconds,
            endOfUtteranceSilenceSeconds = sanitizedEndOfUtteranceMs / 1000.0,
            removeDisfluencies = prefs.getBoolean(
                Settings.PREF_SPEECHMATICS_REMOVE_DISFLUENCIES,
                Defaults.PREF_SPEECHMATICS_REMOVE_DISFLUENCIES
            ),
            punctuationSensitivity = prefs.getInt(
                Settings.PREF_SPEECHMATICS_PUNCTUATION_SENSITIVITY_PERCENT,
                Defaults.PREF_SPEECHMATICS_PUNCTUATION_SENSITIVITY_PERCENT
            ).coerceIn(
                MIN_PUNCTUATION_SENSITIVITY_PERCENT,
                MAX_PUNCTUATION_SENSITIVITY_PERCENT
            ) / 100.0,
            diarizationEnabled = prefs.getBoolean(
                Settings.PREF_SPEECHMATICS_DIARIZATION,
                Defaults.PREF_SPEECHMATICS_DIARIZATION
            )
        )
    }

    fun writeSpeechmaticsMaxDelayTenths(prefs: SharedPreferences, value: Int) {
        prefs.edit {
            putInt(
                Settings.PREF_SPEECHMATICS_MAX_DELAY_MILLIS,
                value.coerceIn(
                    MIN_SPEECHMATICS_MAX_DELAY_TENTHS,
                    MAX_SPEECHMATICS_MAX_DELAY_TENTHS
                ) * 100
            )
        }
    }

    fun writeSpeechmaticsEndOfUtteranceMs(prefs: SharedPreferences, value: Int) {
        prefs.edit {
            putInt(
                Settings.PREF_SPEECHMATICS_END_OF_UTTERANCE_MILLIS,
                value.coerceIn(
                    MIN_SPEECHMATICS_END_OF_UTTERANCE_MS,
                    MAX_SPEECHMATICS_END_OF_UTTERANCE_MS
                )
            )
        }
    }

    fun writeSpeechmaticsRemoveDisfluencies(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(Settings.PREF_SPEECHMATICS_REMOVE_DISFLUENCIES, enabled)
        }
    }

    fun writeSpeechmaticsPunctuationSensitivityPercent(prefs: SharedPreferences, value: Int) {
        prefs.edit {
            putInt(
                Settings.PREF_SPEECHMATICS_PUNCTUATION_SENSITIVITY_PERCENT,
                value.coerceIn(
                    MIN_PUNCTUATION_SENSITIVITY_PERCENT,
                    MAX_PUNCTUATION_SENSITIVITY_PERCENT
                )
            )
        }
    }

    fun readSpeechmaticsMaxDelaySeconds(prefs: SharedPreferences): Double {
        return readSpeechmaticsConfig(prefs).maxDelaySeconds
    }

    fun readSpeechmaticsEndOfUtteranceSeconds(prefs: SharedPreferences): Double {
        return readSpeechmaticsConfig(prefs).endOfUtteranceSilenceSeconds
    }

    fun readSpeechmaticsRemoveDisfluencies(prefs: SharedPreferences): Boolean {
        return readSpeechmaticsConfig(prefs).removeDisfluencies
    }

    fun readSpeechmaticsPunctuationSensitivity(prefs: SharedPreferences): Double {
        return readSpeechmaticsConfig(prefs).punctuationSensitivity
    }

    fun readSpeechmaticsDiarization(prefs: SharedPreferences): Boolean {
        return readSpeechmaticsConfig(prefs).diarizationEnabled
    }

    fun writeSpeechmaticsDiarization(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit {
            putBoolean(Settings.PREF_SPEECHMATICS_DIARIZATION, enabled)
        }
    }

    fun sanitizeSpeechmaticsMaxDelaySeconds(value: Double): Double {
        val sanitizedTenths = (value * 10).toInt().coerceIn(
            MIN_SPEECHMATICS_MAX_DELAY_TENTHS,
            MAX_SPEECHMATICS_MAX_DELAY_TENTHS
        )
        return sanitizedTenths / 10.0
    }

    fun sanitizeSpeechmaticsEndOfUtteranceSeconds(value: Double, maxDelaySeconds: Double): Double {
        val maxAllowedMs = maxEndOfUtteranceForMaxDelay(maxDelaySeconds) * 1000
        val requestedMs = (value * 1000).toInt()
        return requestedMs.coerceIn(
            MIN_SPEECHMATICS_END_OF_UTTERANCE_MS,
            maxAllowedMs.toInt()
        ) / 1000.0
    }

    fun maxEndOfUtteranceForMaxDelay(maxDelaySeconds: Double): Double {
        return (sanitizeSpeechmaticsMaxDelaySeconds(maxDelaySeconds) - 0.1)
            .coerceAtLeast(0.0)
            .coerceAtMost(MAX_SPEECHMATICS_END_OF_UTTERANCE_MS / 1000.0)
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
