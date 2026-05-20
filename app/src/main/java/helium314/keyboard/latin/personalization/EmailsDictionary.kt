// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tiny, locale-agnostic dictionary of email addresses the user has typed, along
 * with how many times each has been used. Unlike [UserHistoryDictionary] this does
 * not plug into the native binary dictionary pipeline — instead it is a plain
 * in-memory map that is persisted to a simple tab-separated file, which makes it
 * straightforward to expose to a full settings UI, rank by usage, and filter by
 * prefix when building suggestions.
 *
 * File format (one entry per line):
 *   email\tcount
 *
 * Where `count` is the number of times the user has typed this email. When the
 * user types at least two characters, callers can ask this dictionary for the
 * top N matching emails (ranked by usage count, stable by email string) to show
 * as prioritized suggestions in the suggestion strip.
 *
 * Emails are normalized to lower-case before being added or matched, mirroring
 * what [helium314.keyboard.latin.common.StringUtils.normalizeEmailAddress] does
 * during user-history learning.
 */
object EmailsDictionary {
    private const val TAG = "EmailsDictionary"
    private const val FILE_NAME = "emails_dictionary.tsv"
    private const val PREFIX_MIN_CHARS = 2

    // email (lowercase) -> count
    private val entries = ConcurrentHashMap<String, Int>()

    @Volatile
    private var loaded = false

    @Volatile
    private var appContext: Context? = null

    /**
     * Single background thread for disk writes. Both file I/O and listener
     * notifications happen here so we never block the IME's main thread on
     * disk, and so we can coalesce bursts of changes (e.g. a single
     * [EmailLearner] scan that records 5 emails at once) into one save and
     * one notification.
     *
     * The executor is daemon so it doesn't block process shutdown.
     */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "EmailsDictionary-IO").apply { isDaemon = true }
    }

    /** True when a save+notify task is queued on [ioExecutor] but has not
     *  started running yet. We only enqueue one at a time; whichever task
     *  is next to run will pick up the latest snapshot of [entries]. */
    private val saveScheduled = AtomicBoolean(false)

    /** Listener notifications fire on the main looper so observers (Compose
     *  screens) don't have to do their own thread hopping. */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** A listener notified whenever the dictionary contents change. Used by the
     *  settings screen so the list updates live. */
    fun interface ChangeListener {
        fun onEmailsChanged()
    }

    private val listeners = mutableListOf<ChangeListener>()

    @Synchronized
    fun addListener(listener: ChangeListener) {
        listeners.add(listener)
    }

    @Synchronized
    fun removeListener(listener: ChangeListener) {
        listeners.remove(listener)
    }

    private fun notifyChangedOnMain() {
        val snapshot: List<ChangeListener>
        synchronized(this) { snapshot = listeners.toList() }
        for (l in snapshot) {
            try {
                l.onEmailsChanged()
            } catch (t: Throwable) {
                Log.w(TAG, "ChangeListener threw", t)
            }
        }
    }

    /**
     * Mark the dictionary dirty: schedule one background save + one
     * main-thread listener notification. Repeated calls before the
     * scheduled task starts running coalesce into a single save.
     */
    private fun scheduleSaveAndNotify() {
        if (!saveScheduled.compareAndSet(false, true)) return
        ioExecutor.execute {
            // Clear the flag *before* writing so any new mutation happening
            // mid-write is captured by a follow-up scheduled save.
            saveScheduled.set(false)
            save()
            mainHandler.post { notifyChangedOnMain() }
        }
    }

    /** Initialize from the given context. Safe to call multiple times; the
     *  backing file is only read once per process. */
    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            appContext = context.applicationContext
            load()
            loaded = true
        }
    }

    private fun file(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, FILE_NAME)
    }

    private fun load() {
        val f = file() ?: return
        if (!f.isFile) return
        try {
            f.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val parts = trimmed.split('\t')
                val email = parts.getOrNull(0)?.lowercase() ?: return@forEach
                val count = parts.getOrNull(1)?.toIntOrNull() ?: 1
                if (email.contains('@')) entries[email] = count
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load emails dictionary", t)
        }
    }

    @Synchronized
    private fun save() {
        val f = file() ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.bufferedWriter().use { out ->
                for ((email, count) in entries) {
                    out.write(email)
                    out.write("\t")
                    out.write(count.toString())
                    out.write("\n")
                }
            }
            if (f.exists()) f.delete()
            tmp.renameTo(f)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to save emails dictionary", t)
        }
    }

    /**
     * Record that the user typed the given email. Increments its usage
     * counter (or inserts it with count=1 if new). Persistence and listener
     * notification happen asynchronously so this is safe to call from the
     * main thread.
     */
    fun recordEmail(email: String) {
        recordEmails(listOf(email))
    }

    /**
     * Batch version of [recordEmail]. Increments each email's count by 1,
     * does at most one save and one listener notification regardless of how
     * many emails are in the batch.
     */
    fun recordEmails(emails: Collection<String>) {
        if (emails.isEmpty()) return
        var changed = false
        for (raw in emails) {
            val normalized = raw.trim().lowercase()
            if (normalized.isEmpty() || !normalized.contains('@')) continue
            entries.merge(normalized, 1) { old, add -> old + add }
            changed = true
        }
        if (changed) scheduleSaveAndNotify()
    }

    /** Add or replace an entry. Used by the settings UI. */
    fun upsert(email: String, count: Int) {
        val normalized = email.trim().lowercase()
        if (normalized.isEmpty() || !normalized.contains('@')) return
        entries[normalized] = count.coerceAtLeast(0)
        scheduleSaveAndNotify()
    }

    /** Rename an email, preserving its count. */
    fun rename(oldEmail: String, newEmail: String, newCount: Int) {
        val oldKey = oldEmail.trim().lowercase()
        val newKey = newEmail.trim().lowercase()
        if (newKey.isEmpty() || !newKey.contains('@')) return
        val existingCount = entries[oldKey] ?: 0
        if (oldKey != newKey) entries.remove(oldKey)
        entries[newKey] = newCount.coerceAtLeast(0).takeIf { it > 0 } ?: existingCount.coerceAtLeast(1)
        scheduleSaveAndNotify()
    }

    fun remove(email: String) {
        val normalized = email.trim().lowercase()
        if (entries.remove(normalized) != null) {
            scheduleSaveAndNotify()
        }
    }

    fun clear() {
        if (entries.isEmpty()) return
        entries.clear()
        scheduleSaveAndNotify()
    }

    /** Returns all entries, sorted by usage count (descending), then email. */
    fun getAll(): List<Entry> =
        entries.entries
            .map { Entry(it.key, it.value) }
            .sortedWith(compareByDescending<Entry> { it.count }.thenBy { it.email })

    /**
     * Returns up to [limit] emails whose local-part (left of the '@') starts with
     * [prefix] (case-insensitive), ranked by usage count (descending). Returns an
     * empty list if [prefix] has fewer than [PREFIX_MIN_CHARS] characters, to
     * avoid aggressively suggesting on very short input.
     */
    fun topMatches(prefix: String, limit: Int): List<Entry> {
        if (prefix.length < PREFIX_MIN_CHARS) return emptyList()
        if (entries.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val matches = ArrayList<Entry>()
        for ((email, count) in entries) {
            val localPart = email.substringBefore('@')
            if (localPart.startsWith(p) || email.startsWith(p)) {
                matches.add(Entry(email, count))
            }
        }
        if (matches.isEmpty()) return emptyList()
        matches.sortWith(compareByDescending<Entry> { it.count }.thenBy { it.email })
        return if (matches.size > limit) matches.subList(0, limit) else matches
    }

    data class Entry(val email: String, val count: Int)
}
