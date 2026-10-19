// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.core.content.edit
import java.io.File
import java.util.Locale

/** Muse preferences. Comparison-build credentials are never migrated or erased. */
object TranscriptionPreferences {
    private const val VOCABULARY = "muse_custom_vocabulary"
    private const val AUTO_LANGUAGE = "muse_auto_detect_language"
    private const val EDITOR_CONTEXT = "muse_use_editor_context"
    private const val RESPONSIVE = "muse_responsive_dictation"
    private const val TIMING = "muse_timing_diagnostics"
    data class MuseConfig(val autoDetectLanguage: Boolean, val useEditorContext: Boolean,
        val customVocabulary: List<String>, val responsiveDictation: Boolean = false,
        val timingDiagnostics: Boolean = false)
    private fun credential(context: Context) = AtomicFile(File(context.noBackupFilesDir, "muse-api-key"))
    fun readMuseApiKey(context: Context): String = try {
        credential(context).openRead().bufferedReader().use { it.readText().trim() }
    } catch (_: java.io.FileNotFoundException) { "" }
    fun writeMuseApiKey(context: Context, value: String) {
        val file = credential(context)
        if (value.isBlank()) { file.delete(); return }
        val output = file.startWrite()
        try { output.write(value.trim().removePrefix("Bearer ").toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
    fun readMuseConfig(prefs: SharedPreferences) = MuseConfig(readMuseAutoDetectLanguage(prefs),
        readMuseUseEditorContext(prefs), parseCustomVocabulary(readMuseCustomVocabularyRaw(prefs)),
        readMuseResponsiveDictation(prefs), readMuseTimingDiagnostics(prefs))
    fun readMuseResponsiveDictation(prefs: SharedPreferences) = prefs.getBoolean(RESPONSIVE, false)
    fun writeMuseResponsiveDictation(prefs: SharedPreferences, enabled: Boolean) = prefs.edit { putBoolean(RESPONSIVE, enabled) }
    fun readMuseTimingDiagnostics(prefs: SharedPreferences) = prefs.getBoolean(TIMING, false)
    fun writeMuseTimingDiagnostics(prefs: SharedPreferences, enabled: Boolean) = prefs.edit { putBoolean(TIMING, enabled) }
    fun readMuseAutoDetectLanguage(prefs: SharedPreferences) = prefs.getBoolean(AUTO_LANGUAGE, false)
    fun writeMuseAutoDetectLanguage(prefs: SharedPreferences, enabled: Boolean) = prefs.edit { putBoolean(AUTO_LANGUAGE, enabled) }
    fun readMuseUseEditorContext(prefs: SharedPreferences) = prefs.getBoolean(EDITOR_CONTEXT, true)
    fun writeMuseUseEditorContext(prefs: SharedPreferences, enabled: Boolean) = prefs.edit { putBoolean(EDITOR_CONTEXT, enabled) }
    fun readMuseCustomVocabularyRaw(prefs: SharedPreferences): String {
        if (!prefs.contains(VOCABULARY)) {
            val inherited = prefs.getString("gemini_custom_vocabulary", null)
                ?: prefs.getString("soniox_custom_terms", "").orEmpty()
            prefs.edit { putString(VOCABULARY, inherited) }
        }
        return prefs.getString(VOCABULARY, "").orEmpty()
    }
    fun writeMuseCustomVocabulary(prefs: SharedPreferences, value: String) = prefs.edit { putString(VOCABULARY, value) }
    internal fun parseCustomVocabulary(raw: String): List<String> = raw.lineSequence().map { it.trim() }
        .filter { it.length in 2..100 }.distinctBy { it.lowercase(Locale.ROOT) }.take(100).toList()
}
