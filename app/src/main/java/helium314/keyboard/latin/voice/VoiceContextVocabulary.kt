// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.util.Locale

/**
 * Builds the `inputAudioTranscription.customVocabulary` list sent to Gemini at
 * the start of every transcription session.
 *
 * Custom vocabulary is the documented speech-biasing channel for
 * `gemini-3.5-transcribe-live`, and it is how HeliBoard makes dictated text
 * agree with what the user has already typed. Names, product names and jargon
 * sitting in the editor right before the caret are exactly the words a speech
 * model is most likely to get wrong, and exactly the words the user expects to
 * come back spelled and capitalized the way they wrote them.
 *
 * Priority order, because Google notes accuracy is best at roughly 100 terms
 * even though 1,000 are accepted:
 * 1. the user's own list — explicit intent,
 * 2. the built-in product/technical terms,
 * 3. terms harvested from the editor, nearest the caret first.
 */
object VoiceContextVocabulary {

    /**
     * Characters that may appear inside a single harvested term. `.` is
     * excluded on purpose: treating it as word-internal would swallow the
     * sentence boundary in "end. Next", and sentence position is what
     * distinguishes a proper noun from an ordinary capitalized word.
     */
    private const val WORD_INNER_CHARS = "'\u2019-\u2010"

    /** Characters that mark a token as an identifier, path, URL or address. */
    private const val IDENTIFIER_CHARS = "@/\\:_"

    /** Terms shorter than this are too generic to bias usefully. */
    private const val MIN_TERM_LENGTH = 2

    /** Longer "words" are almost always identifiers, hashes or glued-together text. */
    private const val MAX_TERM_LENGTH = 40

    /** Upper bound on all-caps runs treated as acronyms rather than shouting. */
    private const val MAX_ACRONYM_LENGTH = 10

    /**
     * Frequent English words that start sentences. Without this filter every
     * paragraph would contribute "The", "This", "I" and friends and crowd out
     * the proper nouns that actually need biasing.
     */
    private val COMMON_WORDS: Set<String> = setOf(
        "a", "about", "after", "again", "all", "also", "although", "always", "am", "an",
        "and", "another", "any", "anyway", "are", "as", "at", "back", "because", "been",
        "before", "being", "besides", "both", "but", "by", "can", "could", "did", "do",
        "does", "doing", "done", "down", "during", "each", "either", "else", "even",
        "ever", "every", "everyone", "everything", "few", "finally", "first", "for",
        "from", "get", "give", "go", "going", "good", "got", "had", "has", "have",
        "having", "he", "hello", "her", "here", "hers", "hi", "him", "his", "how",
        "however", "i", "if", "in", "instead", "into", "is", "it", "its", "just", "know",
        "last", "let", "like", "little", "look", "make", "many", "maybe", "me", "might",
        "mine", "more", "most", "much", "must", "my", "need", "never", "new", "next",
        "no", "none", "not", "nothing", "now", "of", "off", "ok", "okay", "on", "once",
        "one", "only", "or", "other", "others", "our", "ours", "out", "over", "own",
        "perhaps", "please", "put", "really", "right", "said", "same", "say", "see",
        "she", "should", "since", "so", "some", "someone", "something", "sometimes",
        "soon", "sorry", "still", "such", "sure", "take", "tell", "than", "thanks",
        "that", "the", "their", "theirs", "them", "then", "there", "therefore", "these",
        "they", "thing", "things", "think", "this", "those", "though", "through", "thus",
        "time", "to", "today", "tomorrow", "too", "try", "under", "until", "up", "us",
        "use", "very", "want", "was", "we", "well", "were", "what", "when", "where",
        "whether", "which", "while", "who", "whose", "why", "will", "with", "without",
        "would", "yes", "yesterday", "yet", "you", "your", "yours"
    )

    /**
     * Assemble the vocabulary list for one session.
     *
     * @param userTerms terms the user entered in settings, in their order.
     * @param builtInTerms terms that ship with the app.
     * @param editorContext editor text before the caret, oldest character first.
     * @param limit maximum number of terms to send.
     */
    fun build(
        userTerms: List<String>,
        builtInTerms: List<String>,
        editorContext: String?,
        limit: Int
    ): List<String> {
        if (limit <= 0) return emptyList()
        val result = LinkedHashMap<String, String>()

        fun add(terms: Iterable<String>) {
            for (term in terms) {
                if (result.size >= limit) return
                val cleaned = term.trim()
                if (cleaned.length < MIN_TERM_LENGTH || cleaned.length > MAX_TERM_LENGTH) continue
                val key = cleaned.lowercase(Locale.US)
                // Map.putIfAbsent is API 24+; minSdk is 21.
                if (!result.containsKey(key)) result[key] = cleaned
            }
        }

        add(userTerms)
        add(builtInTerms)
        if (result.size < limit) {
            add(extractTermsFromContext(editorContext, limit - result.size))
        }
        return result.values.toList()
    }

    /**
     * Harvest likely proper nouns, brand names and acronyms from [context],
     * nearest the end (the caret) first, so a long document still contributes
     * the terms relevant to what the user is writing right now.
     */
    internal fun extractTermsFromContext(context: String?, limit: Int): List<String> {
        if (context.isNullOrBlank() || limit <= 0) return emptyList()
        val words = tokenize(context)
        val seen = LinkedHashMap<String, String>()
        for (index in words.indices.reversed()) {
            if (seen.size >= limit) break
            val word = words[index]
            if (!isBiasWorthy(word)) continue
            val key = word.text.lowercase(Locale.US)
            // Map.putIfAbsent is API 24+; minSdk is 21.
            if (!seen.containsKey(key)) seen[key] = word.text
        }
        return seen.values.toList()
    }

    private data class ContextWord(val text: String, val startsSentence: Boolean)

    private fun tokenize(context: String): List<ContextWord> {
        val words = ArrayList<ContextWord>()
        val builder = StringBuilder()
        // A word starts a sentence when the last meaningful character before it
        // ended one. Opening quotes and brackets are transparent so `("Kubernetes`
        // is still treated as sentence-initial.
        var sentenceBoundary = true
        var wordStartsSentence = true
        var isIdentifier = false

        fun flush() {
            if (builder.isNotEmpty()) {
                val trimmed = builder.toString().trim { it in WORD_INNER_CHARS }
                if (!isIdentifier && trimmed.isNotEmpty()) {
                    words.add(ContextWord(trimmed, wordStartsSentence))
                }
                builder.setLength(0)
            }
            isIdentifier = false
        }

        for (c in context) {
            when {
                c.isLetterOrDigit() -> {
                    if (builder.isEmpty()) {
                        wordStartsSentence = sentenceBoundary
                        sentenceBoundary = false
                    }
                    builder.append(c)
                }
                builder.isNotEmpty() && c in WORD_INNER_CHARS -> builder.append(c)
                // Only meaningful while a token is being built: they signal a
                // URL, path, email or code identifier, whose fragments are noise.
                builder.isNotEmpty() && c in IDENTIFIER_CHARS -> {
                    isIdentifier = true
                    builder.append(c)
                }
                else -> {
                    flush()
                    if (c == '.' || c == '!' || c == '?' || c == '\n' || c == '\u2026') {
                        sentenceBoundary = true
                    } else if (!c.isWhitespace() && !isTransparentPunctuation(c)) {
                        sentenceBoundary = false
                    }
                }
            }
        }
        flush()
        return words
    }

    private fun isTransparentPunctuation(c: Char): Boolean =
        c == '"' || c == '\'' || c == '\u201C' || c == '\u201D' || c == '\u2018' ||
            c == '\u2019' || c == '(' || c == '[' || c == '{' || c == '\u00AB'

    /**
     * True when biasing recognition toward [word] is likely to help. Words with
     * internal capitals and acronyms are unambiguous wins. A leading capital only
     * counts mid-sentence, where English does not capitalize ordinary words.
     */
    private fun isBiasWorthy(word: ContextWord): Boolean {
        val text = word.text
        if (text.length < MIN_TERM_LENGTH || text.length > MAX_TERM_LENGTH) return false
        if (text.none { it.isLetter() }) return false
        if (text.any { it.isDigit() }) return false
        if (text.lowercase(Locale.US) in COMMON_WORDS) return false

        val first = text[0]
        if (text.substring(1).any { it.isUpperCase() }) return true
        if (text.length <= MAX_ACRONYM_LENGTH && text.all { !it.isLetter() || it.isUpperCase() }) {
            return true
        }
        if (!first.isUpperCase()) return false
        return !word.startsSentence
    }
}
