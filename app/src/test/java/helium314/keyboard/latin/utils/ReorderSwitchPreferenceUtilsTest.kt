// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import helium314.keyboard.latin.common.Constants.Separators
import kotlin.test.Test
import kotlin.test.assertEquals

class ReorderSwitchPreferenceUtilsTest {
    @Test fun `normalize appends missing entries from defaults`() {
        val value = "VOICE${Separators.KV}true"
        val default = listOf(
            "VOICE${Separators.KV}true",
            "FULLAPP${Separators.KV}false",
            "CLIPBOARD${Separators.KV}false",
        ).joinToString(Separators.ENTRY)

        val normalized = normalizeReorderSwitchPreferenceValue(value, default)

        assertEquals(
            listOf(
                "VOICE${Separators.KV}true",
                "FULLAPP${Separators.KV}false",
                "CLIPBOARD${Separators.KV}false",
            ).joinToString(Separators.ENTRY),
            normalized
        )
    }

    @Test fun `normalize preserves existing order and state`() {
        val value = listOf(
            "CLIPBOARD${Separators.KV}true",
            "VOICE${Separators.KV}false",
        ).joinToString(Separators.ENTRY)
        val default = listOf(
            "VOICE${Separators.KV}true",
            "FULLAPP${Separators.KV}true",
            "CLIPBOARD${Separators.KV}false",
        ).joinToString(Separators.ENTRY)

        val normalized = normalizeReorderSwitchPreferenceValue(value, default)

        assertEquals(
            listOf(
                "CLIPBOARD${Separators.KV}true",
                "VOICE${Separators.KV}false",
                "FULLAPP${Separators.KV}true",
            ).joinToString(Separators.ENTRY),
            normalized
        )
    }
}
