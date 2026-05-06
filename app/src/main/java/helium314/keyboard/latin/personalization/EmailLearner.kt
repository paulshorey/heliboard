// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import android.content.Context
import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.RichInputConnection
import helium314.keyboard.latin.common.StringUtils
import helium314.keyboard.latin.utils.Log

/**
 * Debounced scanner that watches the host editor for email addresses and adds
 * any it finds to [EmailsDictionary].
 *
 * Why this exists in addition to the commit-time capture in
 * `InputLogic.performAdditionToUserHistoryDictionary`:
 *
 * - The IME only commits the typed word (and therefore only learns it) when
 *   the user types a separator (typically a space or punctuation). If the
 *   user types an email and immediately submits the field — Send / Done /
 *   Search / focus loss / app switch — the composing word is dropped without
 *   ever being learned. Email fields in particular often end with submission
 *   rather than space.
 *
 * - We also want to capture emails the user typed *before* this feature
 *   existed. As long as such an email is still visible in the editor near
 *   the cursor, the debounced scan will pick it up the next time the user
 *   types in that field.
 *
 * Behavioral guarantees:
 *
 * - **At most one count increment per editor session per email.** A single
 *   editor session contributes +1 to each distinct email's usage count. This
 *   prevents pure cursor movement (selection-only `onUpdateSelection` events)
 *   or repeated scans of the same window from inflating counts. The seen
 *   set is reset on [flushNow] (called from `onFinishInput`) so the next
 *   editor session starts fresh.
 *
 * - **Skipped while incognito.** Capture is gated by
 *   `SettingsValues.mIncognitoModeEnabled` (which already covers password
 *   fields, no-learning fields, and the always-incognito toggle) and by
 *   `mUsePersonalizedDicts`. Both checks live in the caller so the learner
 *   can stay context-free.
 *
 * - **Bounded work.** Only a window of ~512 chars before and ~64 chars
 *   after the cursor is scanned. A fingerprint of that window short-circuits
 *   redundant scans when text has not actually changed since the last scan.
 *
 * The scanner runs on the main looper because that is the only safe thread
 * for [RichInputConnection] reads.
 */
object EmailLearner {
    private const val TAG = "EmailLearner"

    /** Wait this long after the most recent change before scanning. Long
     *  enough that mid-word typing doesn't churn, short enough that it
     *  usually fires before the user submits. */
    private const val DEBOUNCE_MS = 1500L

    /** How much text before the cursor to scan. A few hundred chars is
     *  enough to cover the email and any preceding word, but small enough to
     *  keep IPC cheap on slow input connections. */
    private const val LOOKBEHIND = 512

    /** How much text after the cursor to scan, so we still catch emails
     *  when the user is editing in the middle of existing text. */
    private const val LOOKAHEAD = 64

    /**
     * Email-shaped match: [local]@[domain](.[domain])+
     *
     * - Local part: letters, digits, and the typical "atom" punctuation
     *   (`._%+-`). Spaces, slashes, etc. are not allowed.
     * - Domain labels: letters, digits, hyphens.
     * - At least one dot in the domain so we can distinguish a real address
     *   from text like `foo@bar`.
     *
     * Trailing sentence punctuation (e.g. trailing `.`, `,`, `!`) is
     * stripped after the match — see [extractEmails].
     */
    private val EMAIL_REGEX = Regex(
        "[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(?:\\.[A-Za-z0-9\\-]+)+"
    )

    /** Characters we strip from the right edge of a regex match before
     *  validating, because they are most likely sentence punctuation that
     *  happened to abut the email. */
    private val TRAILING_PUNCTUATION = ".,;:!?\")]}>".toCharArray().toHashSet()

    private val handler = Handler(Looper.getMainLooper())

    private val pendingScan = Runnable { runPendingScan() }

    @Volatile
    private var pendingConnection: RichInputConnection? = null

    /** Emails already recorded for the current editor session. Reset on
     *  [flushNow] / [reset], i.e. when input switches editors. */
    private val seenInCurrentEditor: MutableSet<String> = HashSet()

    /** Hash of the last scanned window. Used to skip redundant scans when
     *  the user is just moving the cursor without changing text. Null means
     *  "no scan has run yet for this editor session". */
    @Volatile
    private var lastWindowFingerprint: Int? = null

    @Volatile
    private var initialized = false

    /** Called once during IME startup. Idempotent. */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            EmailsDictionary.init(context)
            initialized = true
        }
    }

    /**
     * Schedule a debounced scan of the text around the cursor. Repeated
     * calls within [DEBOUNCE_MS] coalesce — only the last one fires.
     *
     * Pass `skipCapture = true` when the current field should not be
     * learned from (incognito, password, no-learning, or personalized
     * dicts disabled). The pending scan is also cancelled in that case so
     * we don't accidentally leak a still-buffered scan from a previous,
     * non-incognito field.
     */
    fun notifyTextChanged(connection: RichInputConnection?, skipCapture: Boolean) {
        if (!initialized || connection == null) return
        if (skipCapture) {
            cancelPending()
            return
        }
        pendingConnection = connection
        handler.removeCallbacks(pendingScan)
        handler.postDelayed(pendingScan, DEBOUNCE_MS)
    }

    /**
     * Run any pending scan immediately, on the current thread, then clear
     * the per-editor seen set so the next editor session starts fresh.
     * Called from `LatinIME.onFinishInputInternal`.
     */
    fun flushNow(connection: RichInputConnection?, skipCapture: Boolean) {
        if (!initialized) return
        handler.removeCallbacks(pendingScan)
        if (!skipCapture && connection != null) {
            scan(connection)
        }
        reset()
    }

    /** Cancel any in-flight scan and forget the connection reference. */
    fun cancelPending() {
        handler.removeCallbacks(pendingScan)
        pendingConnection = null
    }

    /** Drop all per-editor state. Public for tests; also called from
     *  [flushNow]. */
    @JvmStatic
    fun reset() {
        cancelPending()
        synchronized(seenInCurrentEditor) { seenInCurrentEditor.clear() }
        lastWindowFingerprint = null
    }

    private fun runPendingScan() {
        val conn = pendingConnection ?: return
        pendingConnection = null
        scan(conn)
    }

    private fun scan(connection: RichInputConnection) {
        try {
            val before = connection.getTextBeforeCursor(LOOKBEHIND, 0) ?: ""
            val after = connection.getTextAfterCursor(LOOKAHEAD, 0) ?: ""
            if (before.isEmpty() && after.isEmpty()) return

            // Skip the regex pass entirely when the surrounding text hasn't
            // changed since our last scan. This is the common case for cursor
            // moves and selection updates that aren't actually edits.
            val fingerprint = (before.toString() + "\u0000" + after.toString()).hashCode()
            if (fingerprint == lastWindowFingerprint) return
            lastWindowFingerprint = fingerprint

            val window = StringBuilder(before.length + after.length)
                .append(before).append(after).toString()
            val fresh = ArrayList<String>(2)
            for (email in extractEmails(window)) {
                val added: Boolean
                synchronized(seenInCurrentEditor) {
                    added = seenInCurrentEditor.add(email)
                }
                if (added) fresh.add(email)
            }
            if (fresh.isNotEmpty()) {
                EmailsDictionary.recordEmails(fresh)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Email scan failed", t)
        }
    }

    /**
     * Extract every well-formed email address from [text]. Each candidate
     * is trimmed of trailing sentence punctuation, rejected if it appears
     * to be inside a URL (preceded by `/` or `://`), and finally
     * re-validated with [StringUtils.looksLikeEmailAddress].
     */
    @JvmStatic
    fun extractEmails(text: CharSequence): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (match in EMAIL_REGEX.findAll(text)) {
            // Drop matches that are part of a URL path, e.g.
            // "https://example.com/foo@bar.com" — the trailing piece
            // matches our regex but isn't an email the user typed.
            if (looksLikePartOfUrl(text, match.range.first)) continue

            var candidate = match.value
            while (candidate.isNotEmpty() && TRAILING_PUNCTUATION.contains(candidate.last())) {
                candidate = candidate.dropLast(1)
            }
            if (candidate.isEmpty()) continue
            if (!StringUtils.looksLikeEmailAddress(candidate)) continue
            out.add(StringUtils.normalizeEmailAddress(candidate))
        }
        return out.toList()
    }

    /** Returns true if the character right before [start] looks like it puts
     *  us inside a URL path or scheme rather than at the start of a word. */
    private fun looksLikePartOfUrl(text: CharSequence, start: Int): Boolean {
        if (start <= 0) return false
        val prev = text[start - 1]
        if (prev != '/') return false
        // Single '/' is enough to suggest URL path. We don't need to look
        // for the full "://" — anything URL-pathy will have a '/' immediately
        // before the segment.
        return true
    }
}
