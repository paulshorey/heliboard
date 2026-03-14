// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceContextUtilsTest {
    @Test
    fun `newline counts as sentence boundary`() {
        val recentContext = VoiceContextUtils.extractRecentContext(
            "first line\nsecond line\nthird line",
            1,
            300
        )

        assertEquals("third line", recentContext)
    }

    @Test
    fun `newline run counts as a single boundary`() {
        val recentContext = VoiceContextUtils.extractRecentContext(
            "first line\n\nsecond line\nthird line",
            2,
            300
        )

        assertEquals("second line\nthird line", recentContext)
    }

    @Test
    fun `fallback keeps trailing characters when no boundaries exist`() {
        val recentContext = VoiceContextUtils.extractRecentContext(
            "abcdefghijklmnopqrstuvwxyz",
            3,
            5
        )

        assertEquals("vwxyz", recentContext)
    }

    @Test
    fun `split keeps earlier lines read only`() {
        val cleanupWindow = VoiceContextUtils.splitForCleanup("context one\ncontext two\neditable")

        assertEquals("context one\ncontext two\n", cleanupWindow.referenceContext)
        assertEquals("editable", cleanupWindow.editableText)
    }

    @Test
    fun `split preserves trailing line break when latest line is empty`() {
        val cleanupWindow = VoiceContextUtils.splitForCleanup("context one\r\n\r\n")

        assertEquals("context one\r\n\r\n", cleanupWindow.referenceContext)
        assertEquals("", cleanupWindow.editableText)
    }
}
