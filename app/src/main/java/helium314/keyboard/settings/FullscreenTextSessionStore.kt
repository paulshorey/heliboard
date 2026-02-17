// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.os.Build
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.utils.prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

data class TextSessionSnapshot(
    val sessionKey: String,
    val packageName: String,
    val fieldFingerprint: String?,
    val text: String,
    val sourceText: String?,
    val state: String,
    val lastWriter: String,
    val updatedAt: Long,
)

@Serializable
private data class TextSessionRecord(
    val sessionKey: String,
    val packageName: String,
    val fieldFingerprint: String? = null,
    val text: String = "",
    val sourceText: String? = null,
    val state: String = FullscreenTextSessionStore.STATE_REGULAR_ACTIVE,
    val lastWriter: String = FullscreenTextSessionStore.WRITER_REGULAR,
    val updatedAt: Long = 0L,
)

object FullscreenTextSessionStore {
    const val STATE_REGULAR_ACTIVE = "regular_active"
    const val STATE_FULLSCREEN_IN_PROGRESS = "fullscreen_in_progress"
    const val STATE_PENDING_SYNC = "pending_sync"

    const val WRITER_REGULAR = "regular"
    const val WRITER_FULLSCREEN = "fullscreen"

    private const val PREF_KEY_SESSIONS = "fullscreen_text_sessions_v1"
    private const val MAX_SESSION_COUNT = 40
    private const val SESSION_TTL_MS = 3L * 24L * 60L * 60L * 1000L // 3 days

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableMap<String, TextSessionRecord>? = null

    @JvmStatic
    fun packageName(editorInfo: EditorInfo?, fallbackPackageName: String? = ""): String {
        val fromEditor = editorInfo?.packageName.orEmpty()
        if (fromEditor.isNotEmpty()) return fromEditor
        return fallbackPackageName.orEmpty()
    }

    @JvmStatic
    fun buildSessionKey(editorInfo: EditorInfo?, fallbackPackageName: String? = ""): String {
        val pkg = packageName(editorInfo, fallbackPackageName)
        if (pkg.isEmpty()) return ""
        val fingerprint = buildFieldFingerprint(editorInfo)
        return if (fingerprint == null) pkg else "$pkg|$fingerprint"
    }

    @JvmStatic
    fun getSessionForEditor(
        context: Context,
        editorInfo: EditorInfo?,
        fallbackPackageName: String? = "",
    ): TextSessionSnapshot? {
        val pkg = packageName(editorInfo, fallbackPackageName)
        if (pkg.isEmpty()) return null
        synchronized(this) {
            val exactKey = buildSessionKey(editorInfo, pkg)
            val sessions = load(context)
            val exact = sessions[exactKey]
            if (exact != null) return exact.toSnapshot()

            val packageOnly = sessions[pkg]
            if (packageOnly != null) return packageOnly.toSnapshot()
            if (exactKey == pkg) {
                return sessions.values
                    .asSequence()
                    .filter { it.packageName == pkg }
                    .maxByOrNull { it.updatedAt }
                    ?.toSnapshot()
            }
            return null
        }
    }

    @JvmStatic
    fun getSessionForKey(
        context: Context,
        sessionKey: String,
        packageName: String,
    ): TextSessionSnapshot? {
        if (sessionKey.isEmpty() && packageName.isEmpty()) return null
        synchronized(this) {
            val sessions = load(context)
            sessions[sessionKey]?.let { return it.toSnapshot() }
            sessions[packageName]?.let { return it.toSnapshot() }
            if (sessionKey == packageName) {
                return sessions.values
                    .asSequence()
                    .filter { it.packageName == packageName }
                    .maxByOrNull { it.updatedAt }
                    ?.toSnapshot()
            }
            return null
        }
    }

    /**
     * Resolve text to open fullscreen with:
     * - If a fullscreen session is active/pending for this target, prefer stored text.
     * - Otherwise prefer live field text passed in from IME.
     */
    @JvmStatic
    fun resolveLaunchText(
        context: Context,
        sessionKey: String,
        packageName: String,
        fallbackLiveText: String,
    ): String {
        synchronized(this) {
            val session = getSessionForKey(context, sessionKey, packageName) ?: return fallbackLiveText
            return if (session.state == STATE_PENDING_SYNC || session.state == STATE_FULLSCREEN_IN_PROGRESS) {
                session.text
            } else {
                fallbackLiveText
            }
        }
    }

    @JvmStatic
    fun upsertSession(
        context: Context,
        sessionKey: String,
        packageName: String,
        text: String,
        state: String,
        lastWriter: String,
        sourceText: String?,
    ): TextSessionSnapshot {
        val key = if (sessionKey.isNotEmpty()) sessionKey else packageName
        val now = System.currentTimeMillis()
        synchronized(this) {
            val sessions = load(context)
            val next = TextSessionRecord(
                sessionKey = key,
                packageName = packageName,
                fieldFingerprint = key.substringAfter('|', missingDelimiterValue = "").ifEmpty { null },
                text = text,
                sourceText = sourceText,
                state = state,
                lastWriter = lastWriter,
                updatedAt = now,
            )
            sessions[key] = next
            save(context, sessions)
            return next.toSnapshot()
        }
    }

    @JvmStatic
    fun clearSession(context: Context, sessionKey: String) {
        if (sessionKey.isEmpty()) return
        synchronized(this) {
            val sessions = load(context)
            if (sessions.remove(sessionKey) != null) {
                save(context, sessions)
            }
        }
    }

    @JvmStatic
    fun clearAllSessions(context: Context) {
        synchronized(this) {
            cache = mutableMapOf()
            context.prefs().edit().remove(PREF_KEY_SESSIONS).apply()
        }
    }

    private fun TextSessionRecord.toSnapshot() = TextSessionSnapshot(
        sessionKey = sessionKey,
        packageName = packageName,
        fieldFingerprint = fieldFingerprint,
        text = text,
        sourceText = sourceText,
        state = state,
        lastWriter = lastWriter,
        updatedAt = updatedAt,
    )

    private fun buildFieldFingerprint(editorInfo: EditorInfo?): String? {
        if (editorInfo == null) return null

        val components = mutableListOf<String>()
        if (editorInfo.fieldId != 0) {
            components.add("f:${editorInfo.fieldId}")
        }
        components.add("it:${editorInfo.inputType}")
        components.add("io:${editorInfo.imeOptions}")

        val privateOptionsHash = editorInfo.privateImeOptions?.hashCode()
        if (privateOptionsHash != null) {
            components.add("pio:$privateOptionsHash")
        }
        val fieldName = editorInfo.fieldName.orEmpty()
        if (fieldName.isNotEmpty()) {
            components.add("fn:${fieldName.hashCode()}")
        }
        val hintText = editorInfo.hintText?.toString().orEmpty()
        if (hintText.isNotEmpty()) {
            components.add("ht:${hintText.hashCode()}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val autofill = editorInfo.autofillId?.toString().orEmpty()
            if (autofill.isNotEmpty()) {
                components.add("af:$autofill")
            }
        }

        val hasStrongSignal = editorInfo.fieldId != 0 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && editorInfo.autofillId != null) ||
            !editorInfo.privateImeOptions.isNullOrEmpty() ||
            fieldName.isNotEmpty() ||
            hintText.isNotEmpty()
        if (!hasStrongSignal) return null

        return components.joinToString("|").lowercase(Locale.ROOT)
    }

    private fun load(context: Context): MutableMap<String, TextSessionRecord> {
        cache?.let { return it }
        val raw = context.prefs().getString(PREF_KEY_SESSIONS, null)
        val loaded = if (raw.isNullOrEmpty()) {
            mutableMapOf()
        } else {
            runCatching {
                json.decodeFromString<List<TextSessionRecord>>(raw)
                    .associateByTo(mutableMapOf()) { it.sessionKey }
            }.getOrElse {
                mutableMapOf()
            }
        }
        prune(loaded)
        cache = loaded
        return loaded
    }

    private fun save(context: Context, sessions: MutableMap<String, TextSessionRecord>) {
        prune(sessions)
        val serialized = json.encodeToString(sessions.values.sortedByDescending { it.updatedAt })
        context.prefs().edit().putString(PREF_KEY_SESSIONS, serialized).apply()
        cache = sessions
    }

    private fun prune(sessions: MutableMap<String, TextSessionRecord>) {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { (_, value) ->
            now - value.updatedAt > SESSION_TTL_MS
        }
        if (sessions.size <= MAX_SESSION_COUNT) return
        val toRemove = sessions.values
            .sortedByDescending { it.updatedAt }
            .drop(MAX_SESSION_COUNT)
            .map { it.sessionKey }
            .toSet()
        sessions.entries.removeAll { it.key in toRemove }
    }
}
