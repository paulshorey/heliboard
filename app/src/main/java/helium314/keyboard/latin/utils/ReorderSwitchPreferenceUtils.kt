// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import helium314.keyboard.latin.common.Constants.Separators

/**
 * Merge stored reorder/switch preference entries with the current default list so newly added
 * options appear without forcing users to reset the entire preference.
 */
fun normalizeReorderSwitchPreferenceValue(value: String, default: String): String {
    val normalized = mutableListOf<String>()
    val seen = linkedSetOf<String>()

    fun appendEntries(source: String) {
        source.split(Separators.ENTRY).forEach { entry ->
            val split = entry.split(Separators.KV, limit = 2)
            val name = split.firstOrNull()?.takeIf { split.size == 2 && it.isNotBlank() } ?: return@forEach
            if (seen.add(name)) {
                normalized.add(name + Separators.KV + split.last().toBoolean())
            }
        }
    }

    appendEntries(value)
    appendEntries(default)
    return normalized.joinToString(Separators.ENTRY)
}
