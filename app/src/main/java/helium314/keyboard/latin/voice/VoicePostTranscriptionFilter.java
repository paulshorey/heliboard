// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Centralized text preparation for finalized voice transcripts before insertion.
 *
 * Execution order is intentionally split into two public stages:
 *
 * 1. applyPostTranscriptionFilter(String)
 *    - Normalize spoken aliases in a single token pass. This combines number words
 *      like "zero" through "ninety nine" and spoken symbol phrases like
 *      "open parenthesis", "slash", and "comma" into one longest-match scan.
 *    - Run deterministic cleanup rules on the replaced text. This handles spacing
 *      between adjacent non-letters and a small ordered set of edge-case rewrites
 *      such as "one hundred" -> "100", "negative five" -> "-5", and delayed
 *      "dash" / "hyphen" / "minus" handling.
 *
 * 2. prepareForInsertion(String, CharSequence)
 *    - Call applyPostTranscriptionFilter.
 *    - Strip invisible Unicode control characters via VoiceTextSanitizer.
 *    - Adjust capitalization using text before the caret so mid-sentence insertions
 *      do not stay incorrectly capitalized.
 *    - Ensure a trailing space so the IME can commit the final text directly.
 *
 * The IME should treat this class as the single owner of voice text shaping.
 * Callers should pass in raw finalized transcript text and commit the returned
 * string without applying extra formatting rules elsewhere.
 */
public final class VoicePostTranscriptionFilter {

    /**
     * Reserved for future filtering that may treat short chunks differently (e.g. length cutoff).
     */
    public static final int POST_TRANSCRIPTION_FILTER_SHORT_CHUNK_CHAR_LIMIT = 40;
    private static final Map<String, String> SPOKEN_REPLACEMENT_ALIASES =
            createSpokenReplacementAliases();
    private static final int MAX_REPLACEMENT_WORDS = findMaxAliasWords(SPOKEN_REPLACEMENT_ALIASES);
    private static final Pattern NON_ALPHA_SPACE_PATTERN =
            Pattern.compile("(?<=[^a-zA-Z])\\s+(?=[^a-zA-Z])");
    private static final Pattern REMAINING_ONE_WORD_PATTERN =
            Pattern.compile("\\b1(\\s?)([a-zA-Z]+)");

    private VoicePostTranscriptionFilter() {
        // Utility class.
    }

    /**
     * Apply deterministic text-only post-transcription rules.
     *
     * <p>This is the hook for future substitutions such as spoken punctuation and number
     * conversions. It intentionally stays independent of editor context.</p>
     */
    public static String applyPostTranscriptionFilter(@Nullable final String text) {
        if (text == null) {
            return "";
        }
        if (text.isBlank()) {
            return "";
        }
        String result = replaceSpokenAliases(text);
        return fixReplacedEdgeCases(result);
    }

    /**
     * Prepare finalized transcript text for insertion into the editor.
     *
     * <p>Applies deterministic voice-specific cleanup, then context-aware capitalization, and
     * finally normalizes trailing spacing so step 3 only needs to commit the result.</p>
     */
    public static String prepareForInsertion(
            @Nullable final String rawText,
            @Nullable final CharSequence textBeforeCursor
    ) {
        final String filtered = applyPostTranscriptionFilter(rawText);
        final String sanitized = VoiceTextSanitizer.stripInvisibleChars(filtered).trim();
        if (sanitized.isEmpty()) {
            return sanitized;
        }

        final String capitalized = adjustCapitalization(sanitized, textBeforeCursor);
        return ensureTrailingSpace(capitalized);
    }

    private static String adjustCapitalization(
            final String text,
            @Nullable final CharSequence textBeforeCursor
    ) {
        if (text.isEmpty() || textBeforeCursor == null || textBeforeCursor.length() == 0) {
            return text;
        }

        final int lastVisibleChar = findLastVisibleChar(textBeforeCursor);
        if (lastVisibleChar == -1 || isSentenceBoundary(lastVisibleChar)) {
            return text;
        }

        final char firstChar = text.charAt(0);
        if (!Character.isUpperCase(firstChar)) {
            return text;
        }
        return Character.toLowerCase(firstChar) + text.substring(1);
    }

    private static int findLastVisibleChar(final CharSequence text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            final char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c;
            }
        }
        return -1;
    }

    private static boolean isSentenceBoundary(final int c) {
        return c == '.' || c == '!' || c == '?' || c == '\n';
    }

    private static String ensureTrailingSpace(final String text) {
        if (text.isEmpty() || text.endsWith(" ")) {
            return text;
        }
        return text + " ";
    }

    private static String replaceSpokenAliases(final String text) {
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        final String[] originalTokens = trimmed.split("\\s+");
        final String[] normalizedTokens = normalizeTokens(originalTokens);

        final List<String> segments = new ArrayList<>();
        int index = 0;
        while (index < originalTokens.length) {
            final Match match = findLongestAliasMatch(normalizedTokens, index);
            if (match != null) {
                segments.add(match.replacement);
                index += match.wordCount;
            } else {
                segments.add(originalTokens[index]);
                index += 1;
            }
        }
        return joinSegments(segments);
    }

    @Nullable
    private static Match findLongestAliasMatch(final String[] normalizedTokens, final int startIndex) {
        final int maxWords = Math.min(MAX_REPLACEMENT_WORDS, normalizedTokens.length - startIndex);
        for (int wordCount = maxWords; wordCount >= 1; wordCount--) {
            final String candidate = buildCandidate(normalizedTokens, startIndex, wordCount);
            if (candidate == null) {
                continue;
            }
            final String replacement = SPOKEN_REPLACEMENT_ALIASES.get(candidate);
            if (replacement != null) {
                return new Match(replacement, wordCount);
            }
        }
        return null;
    }

    private static String joinSegments(final List<String> segments) {
        final StringBuilder joined = new StringBuilder();
        String previousSegment = "";
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (joined.length() > 0 && shouldInsertSpace(previousSegment, segment)) {
                joined.append(' ');
            }
            joined.append(segment);
            previousSegment = segment;
        }
        return joined.toString();
    }

    private static boolean shouldInsertSpace(final String previousSegment, final String nextSegment) {
        if (previousSegment.isEmpty() || nextSegment.isEmpty()) {
            return false;
        }
        if (isOpeningSymbol(previousSegment)) {
            return false;
        }
        if (isClosingSymbol(nextSegment)) {
            return false;
        }
        if (isPrefixSymbol(previousSegment)) {
            return false;
        }
        if (isInfixSymbol(previousSegment) || isInfixSymbol(nextSegment)) {
            return false;
        }
        if (isQuoteSymbol(previousSegment) || isQuoteSymbol(nextSegment)) {
            return false;
        }
        return true;
    }

    private static boolean isOpeningSymbol(final String segment) {
        return "(".equals(segment)
                || "[".equals(segment)
                || "{".equals(segment)
                || "<".equals(segment);
    }

    private static boolean isClosingSymbol(final String segment) {
        return ".".equals(segment)
                || ",".equals(segment)
                || "!".equals(segment)
                || "?".equals(segment)
                || ":".equals(segment)
                || ";".equals(segment)
                || "%".equals(segment)
                || ")".equals(segment)
                || "]".equals(segment)
                || "}".equals(segment)
                || ">".equals(segment)
                || "...".equals(segment);
    }

    private static boolean isPrefixSymbol(final String segment) {
        return "$".equals(segment)
                || "#".equals(segment);
    }

    private static boolean isInfixSymbol(final String segment) {
        return "-".equals(segment)
                || "_".equals(segment)
                || "/".equals(segment)
                || "\\".equals(segment)
                || "+".equals(segment)
                || "=".equals(segment)
                || "*".equals(segment)
                || "&".equals(segment)
                || "|".equals(segment)
                || "@".equals(segment)
                || "^".equals(segment)
                || "~".equals(segment);
    }

    private static boolean isQuoteSymbol(final String segment) {
        return "\"".equals(segment) || "'".equals(segment) || "`".equals(segment);
    }

    private static String normalizeToken(final String token) {
        return token.toLowerCase(Locale.US)
                .replace('\u2019', '\'')
                .replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
    }

    private static String fixReplacedEdgeCases(final String text) {
        String result = collapseSpacesBetweenNonAlphabeticChars(text);

        result = result.replaceAll("(?i)\\b0\\s+in\\b", "zero in");
        result = result.replaceAll("(?i)\\b1\\s+hundred\\b", "100");
        result = result.replaceAll("(?i)\\b1\\s+thousand\\b", "1000");
        result = result.replaceAll("(?i)\\b1\\s+million\\b", "1000000");
        result = result.replaceAll("(?i)\\b1\\s+billion\\b", "1000000000");

        for (int i = 1; i <= 9; i++) {
            result = result.replaceAll("(?i)\\bnegative\\s+" + i + "\\b", "-" + i);
        }

        result = result.replaceAll("(?i)\\s*\\bminus\\s+sign\\b\\s*", "-");
        result = result.replaceAll("(?i)\\s*\\bdash\\b\\s*", " - ");
        result = result.replaceAll("(?i)\\s*\\bhyphen\\b\\s*", "-");
        result = result.replaceAll("(?i)\\s*\\bminus\\b\\s*", "-");
        result = result.replaceAll("(?i)\\be\\s+t\\s+c\\.\\.\\.", "etc...");
        result = result.replaceAll("(?i)\\betcetera\\b", "etc");
        result = result.replaceAll("(?i)\\betc(?=\\s)", "etc.");

        return REMAINING_ONE_WORD_PATTERN.matcher(result).replaceAll("one$1$2");
    }

    private static String collapseSpacesBetweenNonAlphabeticChars(final String text) {
        return NON_ALPHA_SPACE_PATTERN.matcher(text).replaceAll("");
    }

    private static String[] normalizeTokens(final String[] originalTokens) {
        final String[] normalizedTokens = new String[originalTokens.length];
        for (int i = 0; i < originalTokens.length; i++) {
            normalizedTokens[i] = normalizeToken(originalTokens[i]);
        }
        return normalizedTokens;
    }

    @Nullable
    private static String buildCandidate(
            final String[] normalizedTokens,
            final int startIndex,
            final int wordCount
    ) {
        final StringBuilder candidate = new StringBuilder();
        for (int i = 0; i < wordCount; i++) {
            final String token = normalizedTokens[startIndex + i];
            if (token.isEmpty()) {
                return null;
            }
            if (candidate.length() > 0) {
                candidate.append(' ');
            }
            candidate.append(token);
        }
        return candidate.toString();
    }

    private static Map<String, String> createSpokenReplacementAliases() {
        final Map<String, String> aliases = new HashMap<>();
        aliases.putAll(createSpokenNumberWords());
        aliases.putAll(createSpokenSymbolAliases());
        return aliases;
    }

    private static Map<String, String> createSpokenNumberWords() {
        final Map<String, String> numbers = new HashMap<>();

        final String[] ones = {
                "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
                "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen",
                "seventeen", "eighteen", "nineteen"
        };
        for (int i = 0; i < ones.length; i++) {
            numbers.put(ones[i], String.valueOf(i));
        }

        final String[] tens = {"twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"};
        for (int i = 0; i < tens.length; i++) {
            final int tenValue = (i + 2) * 10;
            numbers.put(tens[i], String.valueOf(tenValue));
            for (int j = 1; j <= 9; j++) {
                numbers.put(tens[i] + " " + ones[j], String.valueOf(tenValue + j));
            }
        }

        return numbers;
    }

    private static Map<String, String> createSpokenSymbolAliases() {
        final Map<String, String> aliases = new HashMap<>();

        registerAliases(aliases, ".", "period", "full stop", "dot");
        registerAliases(aliases, ",", "comma");
        registerAliases(aliases, "!", "exclamation", "exclamation point", "exclamation mark", "bang");
        registerAliases(aliases, "?", "question mark", "question point", "question-mark");
        registerAliases(aliases, ":", "colon");
        registerAliases(aliases, ";", "semicolon", "semi colon");
        registerAliases(aliases, "...", "ellipsis", "dot dot dot");
        registerAliases(aliases, "•", "bullet", "bullet point");
        registerAliases(aliases, "°", "degree", "degree sign");
        registerAliases(aliases, "©", "copyright", "copyright sign");
        registerAliases(aliases, "®", "registered", "registered sign");
        registerAliases(aliases, "™", "trademark", "trademark sign");
        registerAliases(aliases, "€", "euro", "euro sign");
        registerAliases(aliases, "£", "pound sterling", "sterling", "sterling sign");
        registerAliases(aliases, "¥", "yen", "yen sign");
        registerAliases(aliases, "₹", "rupee", "rupee sign");
        registerAliases(aliases, "¢", "cent", "cent sign");

        registerAliases(
                aliases,
                "&",
                "ampersand",
                "and sign",
                "ampersand sign"
        );
        registerAliases(
                aliases,
                "@",
                "at sign",
                "at symbol"
        );
        registerAliases(
                aliases,
                "#",
                "hash",
                "hash sign",
                "number sign",
                "pound sign",
                "hashtag"
        );
        registerAliases(aliases, "$", "dollar", "dollar sign");
        registerAliases(aliases, "%", "percent", "percent sign");
        registerAliases(aliases, "^", "caret", "caret sign");
        registerAliases(aliases, "|", "pipe", "vertical bar");
        registerAliases(aliases, "_", "underscore", "under score");
        registerAliases(aliases, "+", "plus", "plus sign");
        registerAliases(aliases, "=", "equals", "equal sign", "equals sign");
        registerAliases(aliases, "*", "asterisk", "star");
        registerAliases(aliases, "~", "tilde");
        registerAliases(aliases, "`", "backtick", "back tick", "grave", "grave accent");
        registerAliases(aliases, "/", "slash", "forward slash", "solidus");
        registerAliases(aliases, "\\", "backslash", "back slash", "reverse slash");
        registerAliases(aliases, "<", "less than", "less than sign", "open angle bracket", "left angle bracket", "open chevron", "left chevron");
        registerAliases(aliases, ">", "greater than", "greater than sign", "close angle bracket", "right angle bracket", "close chevron", "right chevron");

        registerAliases(aliases, "'", "apostrophe", "single quote", "single quotation mark", "single quotes");
        registerAliases(aliases, "\"", "quote", "quotes", "double quote", "quotation mark", "double quotation mark", "double quotes");
        registerAliases(aliases, "'", "open single quote", "opening single quote", "left single quote", "close single quote", "closing single quote", "right single quote");
        registerAliases(aliases, "\"", "open quote", "opening quote", "left quote", "close quote", "closing quote", "right quote");
        registerAliases(aliases, "\"", "open double quote", "opening double quote", "left double quote", "close double quote", "closing double quote", "right double quote");

        registerBracketFamily(
                aliases,
                "(",
                ")",
                "parenthesis",
                "parentheses",
                "parenthese",
                "paren",
                "parens"
        );
        registerBracketFamily(
                aliases,
                "[",
                "]",
                "square bracket",
                "square brackets",
                "square brace",
                "square braces"
        );
        registerBracketFamily(
                aliases,
                "{",
                "}",
                "curly bracket",
                "curly brackets",
                "curly brace",
                "curly braces",
                "brace",
                "braces"
        );

        registerAliases(aliases, "(", "left parenthesis", "left parentheses", "left parenthese", "left paren", "open round bracket", "opening round bracket");
        registerAliases(aliases, ")", "right parenthesis", "right parentheses", "right parenthese", "right paren", "close round bracket", "closing round bracket");

        registerAliases(aliases, "[", "open bracket", "opening bracket", "left bracket", "left square bracket", "opening square bracket");
        registerAliases(aliases, "]", "close bracket", "closing bracket", "right bracket", "right square bracket", "closing square bracket");

        registerAliases(aliases, "{", "open curly bracket", "opening curly bracket", "left curly bracket", "open curly brace", "opening curly brace", "left curly brace");
        registerAliases(aliases, "}", "close curly bracket", "closing curly bracket", "right curly bracket", "close curly brace", "closing curly brace", "right curly brace");
        registerAliases(aliases, "<", "open the angle bracket", "open the chevron", "opening angle bracket", "opening chevron");
        registerAliases(aliases, ">", "close the angle bracket", "close the chevron", "closing angle bracket", "closing chevron");

        registerAliases(aliases, "(", "open the parenthesis", "open the parentheses", "open the parenthese", "open the paren", "parentheses", "parenthesis");
        registerAliases(aliases, ")", "close the parenthesis", "close the parentheses", "close the parenthese", "close the paren");
        registerAliases(aliases, "[", "open the square bracket", "open the square brackets");
        registerAliases(aliases, "]", "close the square bracket", "close the square brackets");
        registerAliases(aliases, "{", "open the curly bracket", "open the curly brace");
        registerAliases(aliases, "}", "close the curly bracket", "close the curly brace");

        return aliases;
    }

    private static void registerBracketFamily(
            final Map<String, String> aliases,
            final String openingSymbol,
            final String closingSymbol,
            final String... baseNames
    ) {
        for (String baseName : baseNames) {
            registerAliases(
                    aliases,
                    openingSymbol,
                    "open " + baseName,
                    "opening " + baseName,
                    "left " + baseName
            );
            registerAliases(
                    aliases,
                    closingSymbol,
                    "close " + baseName,
                    "closing " + baseName,
                    "right " + baseName
            );
        }
    }

    private static void registerAliases(
            final Map<String, String> aliases,
            final String symbol,
            final String... phrases
    ) {
        for (String phrase : phrases) {
            aliases.put(phrase, symbol);
        }
    }

    private static int findMaxAliasWords(final Map<String, String> aliases) {
        int maxWords = 1;
        for (String phrase : aliases.keySet()) {
            final int wordCount = phrase.split("\\s+").length;
            if (wordCount > maxWords) {
                maxWords = wordCount;
            }
        }
        return maxWords;
    }

    private static final class Match {
        private final String replacement;
        private final int wordCount;

        private Match(final String replacement, final int wordCount) {
            this.replacement = replacement;
            this.wordCount = wordCount;
        }
    }
}
