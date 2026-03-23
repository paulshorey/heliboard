// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings

/** Reads Deepgram streaming `endpointing` (ms) from preferences, with one-time migration from legacy keys. */
object VoiceTranscriptionSettings {
    const val MIN_ENDPOINTING_MS = 50
    const val MAX_ENDPOINTING_MS = 5000

    private const val LEGACY_CHUNK_SILENCE_SECONDS_KEY = "voice_chunk_silence_seconds"

    fun readDeepgramEndpointingMs(prefs: SharedPreferences): Int {
        if (prefs.contains(Settings.PREF_VOICE_CHUNK_SILENCE_MS)) {
            return prefs.getInt(Settings.PREF_VOICE_CHUNK_SILENCE_MS, Defaults.PREF_VOICE_CHUNK_SILENCE_MS)
                .coerceIn(MIN_ENDPOINTING_MS, MAX_ENDPOINTING_MS)
        }
        val legacySeconds = prefs.getInt(LEGACY_CHUNK_SILENCE_SECONDS_KEY, -1)
        // Old default was 1s for abandoned local chunking, not Deepgram endpointing — use new default.
        val migrated =
            if (legacySeconds in 2..30) {
                (legacySeconds * 1000).coerceIn(MIN_ENDPOINTING_MS, MAX_ENDPOINTING_MS)
            } else {
                // 1 was the old default for local chunking, not Deepgram endpointing.
                Defaults.PREF_VOICE_CHUNK_SILENCE_MS
            }
        prefs.edit {
            putInt(Settings.PREF_VOICE_CHUNK_SILENCE_MS, migrated)
            remove(LEGACY_CHUNK_SILENCE_SECONDS_KEY)
            remove("voice_silence_threshold")
            remove("voice_new_paragraph_silence_seconds")
            remove("voice_auto_stop_silence_seconds")
        }
        return migrated
    }
}
