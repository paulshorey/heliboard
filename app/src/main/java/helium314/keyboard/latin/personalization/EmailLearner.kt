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
 * - The IME only commits the typed word (and therefore only learns it) when the
 *   user types a separator (typically a space or punctuation). If the user
 *   types an email address and immediately submits the field — pressing Send,
 *   Done, Search, the back button, switching apps, etc. — the composing word
 *   is dropped without ever being learned. Email fields in particular often
 *   end with submission rather than space.
 *
 * - We also want to capture emails the user typed *before* this feature
 *   existed. As long as such an email is still visible in the editor near the
 *   cursor, the debounced scan will pick it up the next time the user types in
 *   that field.
 *
 * The scanner is intentionally lossy (only scans a window around the cursor)
 * to keep the work bounded, and is gated by [Settings.mIncognitoModeEnabled]
 * so we don't capture from password fields, no-learning fields, or while the
 * user has incognito mode enabled.
 *
 * The scanner runs on the same Looper as the IME so it can safely call into
 * [RichInputConnection].
 */
object EmailLearner {
    private const val TAG = "EmailLearner"

    /** Wait this long after the most recent change before scanning. Long enough
     *  that mid-word typing doesn't churn, short enough that it usually fires
     *  before the user submits. */
    private const val DEBOUNCE_MS = 1500L

    /** How much text before the cursor to scan. A few hundred chars is enough
     *  to cover the email and any preceding word, but small enough to keep IPC
     *  cheap on slow input connections. */
    private const val LOOKBEHIND = 512

    /** How much text after the cursor to scan, so we still catch emails when
     *  the user is editing in the middle of existing text. */
    private const val LOOKAHEAD = 64

    /**
     * Email-shaped match: [local]@[domain](.[domain])+
     *
     * - Local part: letters, digits, and the typical "atom" punctuation
     *   (`._%+-`). We deliberately do not allow spaces, slashes, etc.
     * - Domain labels: letters, digits, hyphens.
     * - At least one dot in the domain so we can distinguish a real address
     *   from text like `foo@bar`.
     *
     * Trailing sentence punctuation (e.g. trailing `.`, `,`, `!`) is stripped
     * after the match — see [extractEmails].
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

    @Volatile
    private var pendingIncognito: Boolean = false

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
     * Schedule a debounced scan of the text around the cursor. Repeated calls
     * within [DEBOUNCE_MS] coalesce — only the last one fires.
     *
     * Pass `incognito = true` (e.g. password / no-learning fields, or when the
     * user has enabled incognito) to skip capture entirely; the existing
     * pending scan is also cancelled in that case so we don't accidentally
     * leak a still-buffered scan from a previous, non-incognito field.
     */
    fun notifyTextChanged(connection: RichInputConnection?, incognito: Boolean) {
        if (!initialized || connection == null) return
        if (incognito) {
            cancelPending()
            return
        }
        pendingConnection = connection
        pendingIncognito = false
        handler.removeCallbacks(pendingScan)
        handler.postDelayed(pendingScan, DEBOUNCE_MS)
    }

    /**
     * Run any pending scan immediately, on the current thread. Called from
     * `LatinIME.onFinishInputInternal` so we get a last chance to learn any
     * email the user typed but never separated.
     */
    fun flushNow(connection: RichInputConnection?, incognito: Boolean) {
        if (!initialized) return
        handler.removeCallbacks(pendingScan)
        if (incognito || connection == null) {
            pendingConnection = null
            return
        }
        scan(connection)
        pendingConnection = null
    }

    /** Cancel any in-flight scan and forget the connection reference. Use when
     *  switching to an incognito field, or in tests. */
    fun cancelPending() {
        handler.removeCallbacks(pendingScan)
        pendingConnection = null
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
            val window = StringBuilder(before.length + after.length)
                .append(before).append(after).toString()
            if (window.isEmpty()) return
            for (email in extractEmails(window)) {
                EmailsDictionary.recordEmail(email)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Email scan failed", t)
        }
    }

    /**
     * Extract every well-formed email address from [text]. Each candidate is
     * trimmed of trailing sentence punctuation and re-validated with
     * [StringUtils.looksLikeEmailAddress] so weird matches are dropped.
     */
    @JvmStatic
    fun extractEmails(text: CharSequence): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        for (match in EMAIL_REGEX.findAll(text)) {
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
}
