// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.content.SharedPreferences
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * User-defined keyboard presets.
 *
 * A single JSON document is stored under [Settings.PREF_CUSTOM_KEYBOARDS_JSON]; the
 * user edits it as plain text in the settings screen. The schema is intentionally
 * compact so it stays human-editable:
 *
 * ```json
 * {
 *   "active": 0,
 *   "presets": [
 *     {
 *       "name": "Default",
 *       "alphabet":     ["...numberRow?...", "...row1...", "...row2...", "...row3..."],
 *       "symbols":      ["...numberRow?...", "...row1...", "...row2...", "...row3..."],
 *       "more_symbols": ["...numberRow?...", "...row1...", "...row2...", "...row3..."]
 *     }
 *   ]
 * }
 * ```
 *
 * Each slot accepts either **4** rows or **3** rows. When 4 rows are given the
 * first row is the number row and is rendered at the top of the keyboard. When
 * 3 rows are given the keyboard has no number row at all; the three rows are
 * laid out from the bottom up (i.e. the top row of the standard alphabet is
 * still the top of the keyboard, the slot just owns less vertical territory).
 * The built-in number row from `assets/layouts/number_row/` is **never** added
 * on top of a preset; the preset is the single source of truth for which rows
 * a layout has.
 *
 * Each row string is a list of keys separated by spaces. Each key token is either
 * a single primary character (`q`) or a primary character followed by `|` and a
 * single hint character (`e|3`). The hint is shown above the primary as a small
 * gray label and is the only available long-press popup; no other hidden popups
 * are emitted for these layouts when custom keyboards are active.
 *
 * Because `|` is the primary/hint separator, a literal pipe character must be
 * escaped as `\|` and a literal backslash as `\\` inside row strings. In the
 * JSON document (where `\` itself is the JSON escape character) this means
 * writing `\\|` for a pipe key and `\\\\` for a backslash key.
 *
 * The model is multi-preset from day one so the user can later cycle between
 * variations (e.g. via a toolbar button); only the preset selected by `active`
 * drives the keyboard at any given moment.
 */
object CustomKeyboards {
    private const val TAG = "CustomKeyboards"

    /** Token separator for primary|hint. */
    const val HINT_SEPARATOR = '|'

    /** Accepted row counts per slot. 4 rows = with number row (top), 3 rows = no number row. */
    val ALLOWED_ROW_COUNTS = setOf(3, 4)

    /** The three layouts every preset describes. */
    enum class Slot(val jsonKey: String) {
        ALPHABET("alphabet"),
        SYMBOLS("symbols"),
        MORE_SYMBOLS("more_symbols");
    }

    @Serializable
    data class Preset(
        val name: String = "",
        val alphabet: List<String> = emptyList(),
        val symbols: List<String> = emptyList(),
        val more_symbols: List<String> = emptyList()
    ) {
        fun rowsFor(slot: Slot): List<String> = when (slot) {
            Slot.ALPHABET -> alphabet
            Slot.SYMBOLS -> symbols
            Slot.MORE_SYMBOLS -> more_symbols
        }
    }

    @Serializable
    data class Document(
        val active: Int = 0,
        val presets: List<Preset> = emptyList()
    ) {
        val activePreset: Preset?
            get() = presets.getOrNull(active.coerceIn(0, (presets.size - 1).coerceAtLeast(0)))
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Parse [text]; returns null when malformed so callers can fall back gracefully. */
    fun parse(text: String?): Document? {
        if (text.isNullOrBlank()) return null
        return try {
            json.decodeFromString(Document.serializer(), text)
        } catch (e: SerializationException) {
            Log.w(TAG, "Failed to parse custom keyboards JSON: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Failed to parse custom keyboards JSON: ${e.message}")
            null
        }
    }

    /** Validate text without storing it; returns null when valid or an error message. */
    fun validationError(text: String): String? {
        if (text.isBlank()) return null
        val doc = try {
            json.decodeFromString(Document.serializer(), text)
        } catch (e: SerializationException) {
            return e.message ?: "Invalid JSON"
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Invalid JSON"
        }
        if (doc.presets.isEmpty()) return "Add at least one preset"
        doc.presets.forEachIndexed { i, p ->
            val label = if (p.name.isNotBlank()) "\"${p.name}\"" else "#$i"
            Slot.entries.forEach { slot ->
                val rows = p.rowsFor(slot)
                if (rows.isEmpty())
                    return "Preset $label is missing ${slot.jsonKey}"
                if (rows.size !in ALLOWED_ROW_COUNTS)
                    return "Preset $label / ${slot.jsonKey} must have 3 or 4 rows (got ${rows.size}). " +
                            "4 rows = top row is the number row; 3 rows = no number row."
                rows.forEachIndexed { ri, row ->
                    parseRowTokens(row).forEach { (primary, hint) ->
                        if (primary.isEmpty())
                            return "Preset $label / ${slot.jsonKey} row ${ri + 1}: empty key"
                        if (hint != null && hint.isEmpty())
                            return "Preset $label / ${slot.jsonKey} row ${ri + 1}: empty hint after '|'"
                    }
                }
            }
        }
        return null
    }

    /** Splits a row string into `(primary, hint?)` tuples, honoring `\|` escapes. */
    fun parseRowTokens(row: String): List<Pair<String, String?>> {
        if (row.isBlank()) return emptyList()
        return row.trim().split(Regex("\\s+")).map { token ->
            splitKeyToken(token)
        }
    }

    /**
     * Find the first unescaped [HINT_SEPARATOR] in [token], split into
     * primary / hint, and unescape both halves.
     */
    private fun splitKeyToken(token: String): Pair<String, String?> {
        var i = 0
        while (i < token.length) {
            val ch = token[i]
            if (ch == '\\' && i + 1 < token.length) {
                i += 2
            } else if (ch == HINT_SEPARATOR) {
                return unescape(token.substring(0, i)) to unescape(token.substring(i + 1))
            } else {
                i++
            }
        }
        return unescape(token) to null
    }

    /** Replace `\|` with `|` and `\\` with `\`. */
    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                sb.append(s[i + 1])
                i += 2
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    /** Escape a raw character string for use inside a row token (escapes `\` and `|`). */
    fun escapeForRow(s: String): String =
        s.replace("\\", "\\\\").replace("|", "\\|")

    /**
     * Convert a preset's [slot] into the simple-text layout format the existing
     * [helium314.keyboard.keyboard.internal.keyboard_parser.LayoutParser.parseSimpleString]
     * pipeline expects: rows separated by blank lines, one key per line as
     * `primary` or `primary popup`.
     */
    fun toSimpleLayoutText(preset: Preset, slot: Slot): String {
        val rows = preset.rowsFor(slot)
        return rows.joinToString("\n\n") { row ->
            parseRowTokens(row).joinToString("\n") { (primary, hint) ->
                if (hint.isNullOrEmpty()) primary else "$primary $hint"
            }
        }
    }

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(Settings.PREF_USE_CUSTOM_KEYBOARDS, Defaults.PREF_USE_CUSTOM_KEYBOARDS)

    fun read(prefs: SharedPreferences): Document? {
        if (!isEnabled(prefs)) return null
        val raw = prefs.getString(Settings.PREF_CUSTOM_KEYBOARDS_JSON, Defaults.PREF_CUSTOM_KEYBOARDS_JSON)
        return parse(raw) ?: parse(Defaults.PREF_CUSTOM_KEYBOARDS_JSON)
    }

    fun activePreset(prefs: SharedPreferences): Preset? = read(prefs)?.activePreset

    /** Pretty-print a Document, used to round-trip user edits or seed the text field. */
    fun encode(doc: Document): String = json.encodeToString(Document.serializer(), doc)

    /**
     * Returns a one-line description of the active preset for use in summary text.
     */
    fun activePresetName(prefs: SharedPreferences): String? {
        val doc = read(prefs) ?: return null
        if (doc.presets.isEmpty()) return null
        val preset = doc.activePreset ?: return null
        val name = preset.name.ifBlank { "#${doc.active}" }
        return if (doc.presets.size > 1) "$name (${doc.active + 1}/${doc.presets.size})" else name
    }

    /**
     * Helper for an eventual toolbar cycle button. Advances `active` modulo
     * presets.size and writes the document back. No-op when there is fewer than
     * two presets or custom keyboards is disabled.
     */
    fun cycleActive(prefs: SharedPreferences): Boolean {
        val doc = read(prefs) ?: return false
        if (doc.presets.size < 2) return false
        val next = (doc.active + 1) % doc.presets.size
        val updated = doc.copy(active = next)
        prefs.edit().putString(Settings.PREF_CUSTOM_KEYBOARDS_JSON, encode(updated)).apply()
        return true
    }

    /**
     * Defensive helper: when the JSON pref contains malformed user input we
     * still want a usable layout. This returns the parsed default document.
     */
    fun fallbackDefault(): Document = parse(Defaults.PREF_CUSTOM_KEYBOARDS_JSON)!!

    /**
     * Inspect a [JsonObject] for legacy/extra fields beyond the schema. Used in
     * tests; safe-guarded against deserialization to keep older JSON readable.
     */
    @Suppress("unused")
    internal fun hasExtraFields(obj: JsonObject): Boolean {
        val known = setOf("active", "presets")
        return obj.keys.any { it !in known && obj[it] is JsonPrimitive }
    }
}
