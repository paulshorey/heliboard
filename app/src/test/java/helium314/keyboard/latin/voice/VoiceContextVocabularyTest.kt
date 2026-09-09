package helium314.keyboard.latin.voice

import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Tests for the speech-biasing vocabulary that makes dictated text agree with
 * what the user already typed.
 */
class VoiceContextVocabularyTest {

    @Test
    fun harvestsProperNounsAndAcronymsFromEditorText() {
        val terms = VoiceContextVocabulary.extractTermsFromContext(
            "We deployed the Roentgen service to Kubernetes using our internal CLI.",
            limit = 20
        )
        assertContains(terms, "Roentgen")
        assertContains(terms, "Kubernetes")
        assertContains(terms, "CLI")
    }

    @Test
    fun preservesTheCasingTheUserTyped() {
        val terms = VoiceContextVocabulary.extractTermsFromContext(
            "The new iPhone build ships with McDonald's coupons.",
            limit = 20
        )
        assertContains(terms, "iPhone")
        assertContains(terms, "McDonald's")
    }

    @Test
    fun skipsOrdinaryWordsThatOnlyHappenToStartASentence() {
        val terms = VoiceContextVocabulary.extractTermsFromContext(
            "Hello there. This is a note. Maybe we should meet.",
            limit = 20
        )
        assertEquals(emptyList(), terms)
    }

    @Test
    fun skipsCapitalizedCommonWordsEvenMidSentence() {
        val terms = VoiceContextVocabulary.extractTermsFromContext(
            "he said Maybe and then Perhaps",
            limit = 20
        )
        assertEquals(emptyList(), terms)
    }

    @Test
    fun keepsAProperNounThatOpensASentenceWhenItAlsoAppearsMidSentence() {
        val terms = VoiceContextVocabulary.extractTermsFromContext(
            "Roentgen is slow. We should profile Roentgen tomorrow.",
            limit = 20
        )
        assertContains(terms, "Roentgen")
    }

    @Test
    fun skipsUrlsPathsEmailsAndIdentifiers() {
        val terms = VoiceContextVocabulary.extractTermsFromContext(
            "See https://Example.com/Docs or mail Alice@Example.com, or run /usr/Local/bin, " +
                "field my_FieldName",
            limit = 30
        )
        assertFalse(terms.any { it.contains("Example", ignoreCase = true) })
        assertFalse(terms.any { it.contains("Local", ignoreCase = true) })
        assertFalse(terms.any { it.contains("FieldName", ignoreCase = true) })
        assertFalse(terms.any { it.contains("Docs", ignoreCase = true) })
    }

    @Test
    fun skipsWordsContainingDigits() {
        val terms = VoiceContextVocabulary.extractTermsFromContext(
            "we shipped Roentgen2 and Version3 today",
            limit = 20
        )
        assertEquals(emptyList(), terms)
    }

    @Test
    fun prefersTermsNearestTheCaret() {
        val context = "we met Alpha then Bravo then Charlie then Delta"
        val terms = VoiceContextVocabulary.extractTermsFromContext(context, limit = 2)
        assertEquals(listOf("Delta", "Charlie"), terms)
    }

    @Test
    fun deduplicatesCaseInsensitivelyKeepingTheMostRecentSpelling() {
        val terms = VoiceContextVocabulary.extractTermsFromContext(
            "we use Roentgen here and ROENTGEN there",
            limit = 20
        )
        assertEquals(listOf("ROENTGEN"), terms)
    }

    @Test
    fun buildPrioritizesUserTermsThenBuiltInsThenEditorTerms() {
        val terms = VoiceContextVocabulary.build(
            userTerms = listOf("Roentgen"),
            builtInTerms = listOf("HeliBoard"),
            editorContext = "we talked about Wittgenstein yesterday",
            limit = 10
        )
        assertEquals(listOf("Roentgen", "HeliBoard", "Wittgenstein"), terms)
    }

    @Test
    fun buildNeverExceedsTheRequestedLimit() {
        val editorContext = (1..50).joinToString(" ") { "x Term$it Zebra Yankee Xray" }
        val terms = VoiceContextVocabulary.build(
            userTerms = listOf("Alpha", "Bravo"),
            builtInTerms = listOf("Charlie", "Delta"),
            editorContext = editorContext,
            limit = 5
        )
        assertEquals(5, terms.size)
        // The user's own terms are never crowded out by harvested ones.
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), terms.take(4))
    }

    @Test
    fun buildDeduplicatesAcrossSourcesCaseInsensitively() {
        val terms = VoiceContextVocabulary.build(
            userTerms = listOf("Kubernetes"),
            builtInTerms = listOf("kubernetes", "HeliBoard"),
            editorContext = "the KUBERNETES cluster",
            limit = 10
        )
        assertEquals(listOf("Kubernetes", "HeliBoard"), terms)
    }

    @Test
    fun buildHandlesNoEditorContext() {
        val terms = VoiceContextVocabulary.build(
            userTerms = emptyList(),
            builtInTerms = listOf("HeliBoard"),
            editorContext = null,
            limit = 10
        )
        assertEquals(listOf("HeliBoard"), terms)
    }

    @Test
    fun buildReturnsNothingForANonPositiveLimit() {
        assertTrue(
            VoiceContextVocabulary.build(
                userTerms = listOf("Roentgen"),
                builtInTerms = listOf("HeliBoard"),
                editorContext = "Wittgenstein",
                limit = 0
            ).isEmpty()
        )
    }
}
