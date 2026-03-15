// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceTextSanitizerTest {
    @Test
    fun `strips bidi formatting controls used by ai responses`() {
        val sanitized = VoiceTextSanitizer.stripInvisibleChars(
            "alpha\u202Ebeta\u202C \u2068gamma\u2069"
        )

        assertEquals("alphabeta gamma", sanitized)
    }

    @Test
    fun `strips hidden controls but preserves normal whitespace`() {
        val sanitized = VoiceTextSanitizer.stripInvisibleChars(
            "one\two\nthree\rfour\u0000\u0008\u034F"
        )

        assertEquals("one\two\nthree\rfour", sanitized)
    }

    @Test
    fun `keeps normal visible text unchanged`() {
        val sanitized = VoiceTextSanitizer.stripInvisibleChars("plain visible text")

        assertEquals("plain visible text", sanitized)
    }
}
