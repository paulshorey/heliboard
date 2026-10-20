// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.core.content.edit
import java.io.File
import java.io.FileNotFoundException

/** Personal comparison-build credentials never enter preferences or Android backups. */
object GrokPreferences {
    private const val KEY_FILE = "grok-transcription-key"
    const val SMART_TURN = "grok_smart_turn"
    const val TURN_TIMEOUT = "grok_turn_timeout_ms"
    const val AUTO_LANGUAGE = "grok_auto_language"
    const val EDITOR_CONTEXT = "grok_editor_context"
    const val FORMAT = "grok_format"
    const val VOCABULARY = "grok_vocabulary"

    data class Config(
        val smartTurn: Double,
        val turnTimeoutMs: Int,
        val autoDetectLanguage: Boolean,
        val useEditorContext: Boolean,
        val format: Boolean,
        val customVocabulary: List<String>,
    )

    @Synchronized fun readApiKey(context: Context): String {
        val file = keyFile(context)
        return try {
            // openRead restores a committed backup even when the base file is missing.
            file.openRead().bufferedReader().use { it.readText() }.trim()
        } catch (_: FileNotFoundException) {
            ""
        }
    }

    @Synchronized fun writeApiKey(context: Context, value: String) {
        val key = value.trim().removePrefix("Bearer ").trim()
        require(key.length <= 4096 && key.none { it.isISOControl() }) { "Invalid API key" }
        val file = keyFile(context)
        if (key.isEmpty()) { file.delete(); return }
        val output = file.startWrite()
        try {
            output.write(key.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun keyFile(context: Context) = AtomicFile(File(context.noBackupFilesDir, KEY_FILE))

    fun readConfig(prefs: SharedPreferences): Config {
        migrateVocabulary(prefs)
        val threshold = prefs.getString(SMART_TURN, "0.7")?.toDoubleOrNull()
        return Config(
            threshold?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.7,
            prefs.getInt(TURN_TIMEOUT, 5000).coerceIn(1, 5000),
            prefs.getBoolean(AUTO_LANGUAGE, false),
            prefs.getBoolean(EDITOR_CONTEXT, true),
            prefs.getBoolean(FORMAT, true),
            parseVocabulary(readVocabulary(prefs)),
        )
    }

    fun readVocabulary(prefs: SharedPreferences): String {
        migrateVocabulary(prefs)
        return prefs.getString(VOCABULARY, "").orEmpty()
    }

    fun parseVocabulary(raw: String): List<String> = raw.lineSequence().map { it.trim().take(50) }
        .filter { it.isNotEmpty() }.distinct().take(100).toList()

    /** Copy vocabulary once; never delete or reinterpret another provider's key/settings. */
    fun migrateVocabulary(prefs: SharedPreferences) {
        if (prefs.contains(VOCABULARY)) return
        val inherited = listOf("muse_custom_vocabulary", "gemini_custom_vocabulary", "soniox_custom_terms")
            .map { prefs.getString(it, "").orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
        prefs.edit { putString(VOCABULARY, inherited) }
    }
}
