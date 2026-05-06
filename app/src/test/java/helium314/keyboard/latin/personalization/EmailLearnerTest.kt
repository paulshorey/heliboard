// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.personalization

import helium314.keyboard.ShadowInputMethodManager2
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowInputMethodManager2::class])
class EmailLearnerTest {

    @Test fun `extracts a bare email`() {
        assertEquals(
            listOf("hello-world@somewhere.co.uk"),
            EmailLearner.extractEmails("hello-world@somewhere.co.uk")
        )
    }

    @Test fun `lowercases extracted email`() {
        assertEquals(
            listOf("user@example.com"),
            EmailLearner.extractEmails("User@Example.COM")
        )
    }

    @Test fun `ignores trailing sentence period`() {
        assertEquals(
            listOf("a@b.co"),
            EmailLearner.extractEmails("Email me at a@b.co.")
        )
    }

    @Test fun `ignores trailing comma and other punctuation`() {
        assertEquals(
            listOf("a@b.co"),
            EmailLearner.extractEmails("Mail me a@b.co, and bla")
        )
        assertEquals(
            listOf("a@b.co"),
            EmailLearner.extractEmails("Mail me a@b.co!")
        )
        assertEquals(
            listOf("a@b.co"),
            EmailLearner.extractEmails("(see a@b.co)")
        )
    }

    @Test fun `extracts multiple emails`() {
        assertEquals(
            listOf("hello-world@somewhere.co.uk", "person@b.co"),
            EmailLearner.extractEmails("hello-world@somewhere.co.uk; person@b.co.")
        )
    }

    @Test fun `keeps internal periods in subdomain`() {
        assertEquals(
            listOf("user@mail.somewhere.co.uk"),
            EmailLearner.extractEmails("contact user@mail.somewhere.co.uk thanks")
        )
    }

    @Test fun `rejects strings without a tld`() {
        assertEquals(emptyList(), EmailLearner.extractEmails("user@example"))
    }

    @Test fun `rejects strings with double dots in the domain`() {
        assertEquals(emptyList(), EmailLearner.extractEmails("user@example..com"))
    }

    @Test fun `dedupes when same email appears multiple times`() {
        assertEquals(
            listOf("a@b.co"),
            EmailLearner.extractEmails("ping a@b.co? then a@b.co again, also A@B.co!")
        )
    }

    @Test fun `extracts at end of multiline paragraph`() {
        val text = """
            Hi all,
            Please send the report to alice+work@example.com when ready.
            Thanks
        """.trimIndent()
        assertEquals(
            listOf("alice+work@example.com"),
            EmailLearner.extractEmails(text)
        )
    }

    @Test fun `empty input returns empty`() {
        assertEquals(emptyList(), EmailLearner.extractEmails(""))
    }
}
