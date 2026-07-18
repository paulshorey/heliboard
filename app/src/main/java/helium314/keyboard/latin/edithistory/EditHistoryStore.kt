// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.edithistory

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.protectedPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

enum class EditHistorySource {
    FULLAPP,
    REGULAR,
}

data class EditHistoryEntry(
    val id: String,
    val source: EditHistorySource,
    val target: EditorTargetSnapshot,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val updatedAt: Long,
    val truncated: Boolean,
)

object EditHistoryStore {
    private const val TAG = "EditHistory"

    private const val PREF_EDIT_HISTORY_INDEX = "edit_history_index"
    private const val PREF_EDIT_HISTORY_PREFIX = "edit_history_"
    private const val PREF_EDIT_HISTORY_LATEST_PREFIX = "edit_history_latest_"
    private const val PREF_EDIT_HISTORY_MIGRATED = "edit_history_migrated"

    // Legacy fullapp archive keys (pre-general-history).
    private const val LEGACY_FULLAPP_ARCHIVE_KEYS = "fullapp_archive_keys"
    private const val LEGACY_FULLAPP_ARCHIVE_PREFIX = "fullapp_archive_"

    const val MAX_HISTORY_ENTRIES = 200
    const val MAX_HISTORY_TOTAL_CHARS = 1_000_000
    const val MAX_ENTRY_CHARS = 100_000

    private const val JSON_ID = "id"
    private const val JSON_SOURCE = "source"
    private const val JSON_TEXT = "text"
    private const val JSON_SELECTION_START = "selection_start"
    private const val JSON_SELECTION_END = "selection_end"
    private const val JSON_UPDATED_AT = "updated_at"
    private const val JSON_TRUNCATED = "truncated"

    private val lock = Any()

    private data class LatestSlot(
        val source: EditHistorySource,
        val target: EditorTargetSnapshot,
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val updatedAt: Long,
        val truncated: Boolean,
    )

    /**
     * Configured retention window in milliseconds, or null when age-based eviction is disabled
     * (slider set to "No limit").
     */
    @JvmStatic
    fun getRetentionAgeMs(context: Context): Long? {
        val hours = context.prefs().getInt(
            Settings.PREF_EDIT_HISTORY_RETENTION_HOURS,
            Defaults.PREF_EDIT_HISTORY_RETENTION_HOURS,
        )
        if (hours >= Defaults.EDIT_HISTORY_RETENTION_HOURS_NO_LIMIT) {
            return null
        }
        return hours.coerceAtLeast(1) * 60L * 60L * 1000L
    }

    @JvmStatic
    fun isWithinRetentionWindow(
        context: Context,
        timestampMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (timestampMs <= 0L) {
            return false
        }
        val ageMs = getRetentionAgeMs(context) ?: return true
        return nowMs - timestampMs <= ageMs
    }

    @JvmStatic
    fun updateLatest(
        context: Context,
        target: EditorTargetSnapshot,
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        synchronized(lock) {
            if (text.isEmpty()) {
                clearLatestLocked(context, target)
                return
            }
            val prefs = historyPrefs(context) ?: return
            migrateIfNeeded(context, prefs)
            val (storedText, truncated) = truncateEntryText(text)
            val textLength = storedText.length
            val slot = LatestSlot(
                source = EditHistorySource.REGULAR,
                target = target,
                text = storedText,
                selectionStart = selectionStart.coerceIn(0, textLength),
                selectionEnd = selectionEnd.coerceIn(0, textLength),
                updatedAt = System.currentTimeMillis(),
                truncated = truncated,
            )
            prefs.edit {
                putString(latestPrefKey(target.storageKey), slot.toJson().toString())
            }
        }
    }

    @JvmStatic
    fun finalizeLatest(context: Context, target: EditorTargetSnapshot) {
        synchronized(lock) {
            val prefs = historyPrefs(context) ?: return
            migrateIfNeeded(context, prefs)
            val slot = loadLatest(prefs, target.storageKey) ?: return
            clearLatestLocked(context, target)
            addEntryLocked(
                context = context,
                source = slot.source,
                target = slot.target,
                text = slot.text,
                selectionStart = slot.selectionStart,
                selectionEnd = slot.selectionEnd,
                updatedAt = slot.updatedAt,
                truncated = slot.truncated,
            )
        }
    }

    @JvmStatic
    fun addEntry(
        context: Context,
        source: EditHistorySource,
        target: EditorTargetSnapshot,
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        updatedAt: Long,
        truncated: Boolean = false,
    ) {
        synchronized(lock) {
            addEntryLocked(
                context = context,
                source = source,
                target = target,
                text = text,
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                updatedAt = updatedAt,
                truncated = truncated,
            )
        }
    }

    private fun addEntryLocked(
        context: Context,
        source: EditHistorySource,
        target: EditorTargetSnapshot,
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        updatedAt: Long,
        truncated: Boolean = false,
    ) {
        if (text.isEmpty()) {
            return
        }
        val prefs = historyPrefs(context) ?: return
        migrateIfNeeded(context, prefs)
        val (storedText, entryTruncated) = if (truncated) {
            text to true
        } else {
            truncateEntryText(text)
        }
        if (isDuplicateOfLatestForTarget(prefs, target.storageKey, storedText)) {
            return
        }
        val entryId = entryId(target.storageKey, source, updatedAt, storedText)
        val textLength = storedText.length
        val entry = EditHistoryEntry(
            id = entryId,
            source = source,
            target = target,
            text = storedText,
            selectionStart = selectionStart.coerceIn(0, textLength),
            selectionEnd = selectionEnd.coerceIn(0, textLength),
            updatedAt = updatedAt,
            truncated = entryTruncated,
        )
        val index = readIndex(prefs).toMutableList()
        index.remove(entryId)
        index.add(0, entryId)
        prefs.edit {
            putString(PREF_EDIT_HISTORY_INDEX, JSONArray(index).toString())
            putString(entryPrefKey(entryId), entry.toJson().toString())
        }
        enforceRetentionLocked(context)
        Log.i(TAG, "Saved ${source.name.lowercase()} history for ${target.debugSummary()}, chars=${storedText.length}")
    }

    @JvmStatic
    fun getAllEntries(context: Context): List<EditHistoryEntry> {
        synchronized(lock) {
            val prefs = historyPrefs(context) ?: return emptyList()
            migrateIfNeeded(context, prefs)
            enforceRetentionLocked(context)
            val index = readIndex(prefs)
            val entries = mutableListOf<EditHistoryEntry>()
            val staleIds = mutableListOf<String>()
            for (entryId in index) {
                val rawEntry = prefs.getString(entryPrefKey(entryId), null)
                if (rawEntry == null) {
                    staleIds.add(entryId)
                    continue
                }
                val entry = entryFromJson(entryId, rawEntry)
                if (entry == null) {
                    staleIds.add(entryId)
                    continue
                }
                entries.add(entry)
            }
            if (staleIds.isNotEmpty()) {
                val cleanedIndex = index.filterNot { it in staleIds }
                prefs.edit {
                    putString(PREF_EDIT_HISTORY_INDEX, JSONArray(cleanedIndex).toString())
                    staleIds.forEach { remove(entryPrefKey(it)) }
                }
            }
            return entries.sortedByDescending { it.updatedAt }
        }
    }

    /**
     * In-progress per-field slots that have not been finalized into history yet.
     * Shown in Settings so the user can copy text even before leaving the field.
     */
    @JvmStatic
    fun getPendingLatestEntries(context: Context): List<EditHistoryEntry> {
        synchronized(lock) {
            val prefs = historyPrefs(context) ?: return emptyList()
            migrateIfNeeded(context, prefs)
            enforceRetentionLocked(context)
            return prefs.all.keys
                .filter { it.startsWith(PREF_EDIT_HISTORY_LATEST_PREFIX) }
                .mapNotNull { key ->
                    val raw = prefs.getString(key, null) ?: return@mapNotNull null
                    val slot = latestFromJson(raw) ?: return@mapNotNull null
                    EditHistoryEntry(
                        id = "latest:${slot.target.storageKey}",
                        source = slot.source,
                        target = slot.target,
                        text = slot.text,
                        selectionStart = slot.selectionStart,
                        selectionEnd = slot.selectionEnd,
                        updatedAt = slot.updatedAt,
                        truncated = slot.truncated,
                    )
                }
                .sortedByDescending { it.updatedAt }
        }
    }

    @JvmStatic
    fun clearAll(context: Context) {
        synchronized(lock) {
            val prefs = historyPrefs(context) ?: return
            migrateIfNeeded(context, prefs)
            val index = readIndex(prefs)
            val latestKeys = prefs.all.keys.filter { it.startsWith(PREF_EDIT_HISTORY_LATEST_PREFIX) }
            prefs.edit {
                remove(PREF_EDIT_HISTORY_INDEX)
                index.forEach { remove(entryPrefKey(it)) }
                latestKeys.forEach { remove(it) }
            }
            Log.i(TAG, "Cleared edit history")
        }
    }

    @JvmStatic
    fun enforceRetention(context: Context) {
        synchronized(lock) {
            enforceRetentionLocked(context)
        }
    }

    private fun enforceRetentionLocked(context: Context) {
        val prefs = historyPrefs(context) ?: return
        val now = System.currentTimeMillis()
        val retentionAgeMs = getRetentionAgeMs(context)
        purgeExpiredLatestSlotsLocked(prefs, now, retentionAgeMs)
        val index = readIndex(prefs)
        if (index.isEmpty()) {
            return
        }
        val loaded = index.mapNotNull { entryId ->
            val rawEntry = prefs.getString(entryPrefKey(entryId), null) ?: return@mapNotNull null
            entryFromJson(entryId, rawEntry)
        }
        val sorted = loaded
            .filter { entry ->
                retentionAgeMs == null || now - entry.updatedAt <= retentionAgeMs
            }
            .sortedByDescending { it.updatedAt }
        val kept = mutableListOf<EditHistoryEntry>()
        var totalChars = 0
        for (entry in sorted) {
            if (kept.size >= MAX_HISTORY_ENTRIES) {
                break
            }
            if (totalChars + entry.text.length > MAX_HISTORY_TOTAL_CHARS) {
                break
            }
            kept.add(entry)
            totalChars += entry.text.length
        }
        val keptIds = kept.map { it.id }.toSet()
        val removedIds = index.filterNot { it in keptIds }
        prefs.edit {
            putString(PREF_EDIT_HISTORY_INDEX, JSONArray(kept.map { it.id }).toString())
            removedIds.forEach { remove(entryPrefKey(it)) }
        }
        if (removedIds.isNotEmpty()) {
            Log.i(TAG, "Evicted ${removedIds.size} edit-history entries to enforce retention")
        }
    }

    private fun purgeExpiredLatestSlotsLocked(
        prefs: SharedPreferences,
        nowMs: Long,
        retentionAgeMs: Long?,
    ) {
        val ageLimit = retentionAgeMs ?: return
        val expiredKeys = prefs.all.keys
            .filter { it.startsWith(PREF_EDIT_HISTORY_LATEST_PREFIX) }
            .filter { key ->
                val raw = prefs.getString(key, null) ?: return@filter true
                val slot = latestFromJson(raw) ?: return@filter true
                nowMs - slot.updatedAt > ageLimit
            }
        if (expiredKeys.isEmpty()) {
            return
        }
        prefs.edit {
            expiredKeys.forEach { remove(it) }
        }
        Log.i(TAG, "Evicted ${expiredKeys.size} expired pending edit-history slots")
    }

    private fun migrateIfNeeded(context: Context, prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_EDIT_HISTORY_MIGRATED, false)) {
            return
        }
        migrateLegacyFullappArchive(context, prefs)
        prefs.edit { putBoolean(PREF_EDIT_HISTORY_MIGRATED, true) }
        enforceRetentionLocked(context)
    }

    private fun migrateLegacyFullappArchive(context: Context, prefs: SharedPreferences) {
        val archiveKeys = prefs.getStringSet(LEGACY_FULLAPP_ARCHIVE_KEYS, emptySet()).orEmpty()
        if (archiveKeys.isEmpty()) {
            return
        }
        val index = readIndex(prefs).toMutableList()
        prefs.edit {
            for (archiveKey in archiveKeys) {
                val rawArchive = prefs.getString(LEGACY_FULLAPP_ARCHIVE_PREFIX + archiveKey, null) ?: continue
                val migrated = migrateLegacyArchiveJson(rawArchive) ?: continue
                val (storedText, truncated) = truncateEntryText(migrated.text)
                val entryId = entryId(
                    migrated.target.storageKey,
                    EditHistorySource.FULLAPP,
                    migrated.updatedAt,
                    storedText,
                )
                if (index.contains(entryId)) {
                    continue
                }
                index.add(0, entryId)
                val textLength = storedText.length
                val entry = EditHistoryEntry(
                    id = entryId,
                    source = EditHistorySource.FULLAPP,
                    target = migrated.target,
                    text = storedText,
                    selectionStart = migrated.selectionStart.coerceIn(0, textLength),
                    selectionEnd = migrated.selectionEnd.coerceIn(0, textLength),
                    updatedAt = migrated.updatedAt,
                    truncated = truncated,
                )
                putString(entryPrefKey(entryId), entry.toJson().toString())
            }
            putString(PREF_EDIT_HISTORY_INDEX, JSONArray(index).toString())
            remove(LEGACY_FULLAPP_ARCHIVE_KEYS)
            archiveKeys.forEach { remove(LEGACY_FULLAPP_ARCHIVE_PREFIX + it) }
        }
        Log.i(TAG, "Migrated ${archiveKeys.size} legacy fullapp archive entries")
    }

    private data class MigratedLegacyEntry(
        val target: EditorTargetSnapshot,
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val updatedAt: Long,
    )

    private fun migrateLegacyArchiveJson(rawArchive: String): MigratedLegacyEntry? = runCatching {
        val json = JSONObject(rawArchive)
        val target = EditorTargetSnapshot.fromJson(json)
        val text = json.optString("draft_text", "")
        MigratedLegacyEntry(
            target = target,
            text = text,
            selectionStart = json.optInt("selection_start", 0),
            selectionEnd = json.optInt("selection_end", 0),
            updatedAt = json.optLong("archived_at", json.optLong("last_saved_at", 0L)),
        )
    }.getOrNull()

    private fun isDuplicateOfLatestForTarget(
        prefs: SharedPreferences,
        storageKey: String,
        text: String,
    ): Boolean {
        val index = readIndex(prefs)
        for (entryId in index) {
            val rawEntry = prefs.getString(entryPrefKey(entryId), null) ?: continue
            val entry = entryFromJson(entryId, rawEntry) ?: continue
            if (entry.target.storageKey == storageKey) {
                return entry.text == text
            }
        }
        return false
    }

    private fun clearLatestLocked(context: Context, target: EditorTargetSnapshot) {
        val prefs = historyPrefs(context) ?: return
        prefs.edit { remove(latestPrefKey(target.storageKey)) }
    }

    private fun loadLatest(prefs: SharedPreferences, storageKey: String): LatestSlot? {
        val raw = prefs.getString(latestPrefKey(storageKey), null) ?: return null
        return latestFromJson(raw)
    }

    private fun readIndex(prefs: SharedPreferences): List<String> {
        val rawIndex = prefs.getString(PREF_EDIT_HISTORY_INDEX, null) ?: return emptyList()
        return runCatching {
            val jsonArray = JSONArray(rawIndex)
            buildList {
                for (i in 0 until jsonArray.length()) {
                    val entryId = jsonArray.optString(i)
                    if (entryId.isNotBlank()) {
                        add(entryId)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun truncateEntryText(text: String): Pair<String, Boolean> {
        if (text.length <= MAX_ENTRY_CHARS) {
            return text to false
        }
        return text.substring(text.length - MAX_ENTRY_CHARS) to true
    }

    private fun entryId(
        storageKey: String,
        source: EditHistorySource,
        updatedAt: Long,
        text: String,
    ): String = sha256("$storageKey|${source.name}|$updatedAt|$text")

    private fun entryPrefKey(entryId: String) = PREF_EDIT_HISTORY_PREFIX + entryId

    private fun latestPrefKey(storageKey: String) = PREF_EDIT_HISTORY_LATEST_PREFIX + storageKey

    private fun EditHistoryEntry.toJson() = JSONObject().apply {
        put(JSON_ID, id)
        put(JSON_SOURCE, source.name)
        put(JSON_TEXT, text)
        put(JSON_SELECTION_START, selectionStart)
        put(JSON_SELECTION_END, selectionEnd)
        put(JSON_UPDATED_AT, updatedAt)
        put(JSON_TRUNCATED, truncated)
        put("target", target.toJson())
    }

    private fun entryFromJson(entryId: String, rawEntry: String): EditHistoryEntry? = runCatching {
        val json = JSONObject(rawEntry)
        val targetJson = json.optJSONObject("target") ?: json
        EditHistoryEntry(
            id = json.optString(JSON_ID, entryId),
            source = EditHistorySource.valueOf(json.getString(JSON_SOURCE)),
            target = EditorTargetSnapshot.fromJson(targetJson),
            text = json.getString(JSON_TEXT),
            selectionStart = json.optInt(JSON_SELECTION_START, 0),
            selectionEnd = json.optInt(JSON_SELECTION_END, 0),
            updatedAt = json.optLong(JSON_UPDATED_AT, 0L),
            truncated = json.optBoolean(JSON_TRUNCATED, false),
        )
    }.getOrNull()

    private fun LatestSlot.toJson() = JSONObject().apply {
        put(JSON_SOURCE, source.name)
        put(JSON_TEXT, text)
        put(JSON_SELECTION_START, selectionStart)
        put(JSON_SELECTION_END, selectionEnd)
        put(JSON_UPDATED_AT, updatedAt)
        put(JSON_TRUNCATED, truncated)
        put("target", target.toJson())
    }

    private fun latestFromJson(raw: String): LatestSlot? = runCatching {
        val json = JSONObject(raw)
        val targetJson = json.optJSONObject("target") ?: json
        LatestSlot(
            source = EditHistorySource.valueOf(json.getString(JSON_SOURCE)),
            target = EditorTargetSnapshot.fromJson(targetJson),
            text = json.getString(JSON_TEXT),
            selectionStart = json.optInt(JSON_SELECTION_START, 0),
            selectionEnd = json.optInt(JSON_SELECTION_END, 0),
            updatedAt = json.optLong(JSON_UPDATED_AT, 0L),
            truncated = json.optBoolean(JSON_TRUNCATED, false),
        )
    }.getOrNull()

    private fun historyPrefs(context: Context): SharedPreferences? = runCatching {
        context.protectedPrefs()
    }.getOrNull()

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
