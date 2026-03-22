// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Centralized text preparation for finalized voice transcripts before insertion.
 *
 * <p>This keeps all deterministic voice-specific string shaping in one place so the IME only
 * needs to commit the already-prepared text into the editor.</p>
 */
public final class VoicePostTranscriptionFilter {

    /**
     * Reserved for future filtering that may treat short chunks differently (e.g. length cutoff).
     */
    public static final int POST_TRANSCRIPTION_FILTER_SHORT_CHUNK_CHAR_LIMIT = 40;
    private static final Map<String, String> SPOKEN_SYMBOL_ALIASES = createSpokenSymbolAliases();
    private static final int MAX_ALIAS_WORDS = findMaxAliasWords(SPOKEN_SYMBOL_ALIASES);
    private static final Map<String, String> SPOKEN_NUMBER_WORDS = createSpokenNumberWords();
    private static final int MAX_NUMBER_WORDS = 2;

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
        String result = replaceSpokenNumbers(text);
        return replaceSpokenSymbols(result);
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

    private static String replaceSpokenSymbols(final String text) {
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        final String[] originalTokens = trimmed.split("\\s+");
        final String[] normalizedTokens = new String[originalTokens.length];
        for (int i = 0; i < originalTokens.length; i++) {
            normalizedTokens[i] = normalizeToken(originalTokens[i]);
        }

        final List<String> segments = new ArrayList<>();
        int index = 0;
        while (index < originalTokens.length) {
            Match match = findLongestAliasMatch(normalizedTokens, index);
            if (match != null) {
                segments.add(match.symbol);
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
        final int maxWords = Math.min(MAX_ALIAS_WORDS, normalizedTokens.length - startIndex);
        for (int wordCount = maxWords; wordCount >= 1; wordCount--) {
            final StringBuilder candidate = new StringBuilder();
            boolean valid = true;
            for (int i = 0; i < wordCount; i++) {
                final String token = normalizedTokens[startIndex + i];
                if (token.isEmpty()) {
                    valid = false;
                    break;
                }
                if (candidate.length() > 0) {
                    candidate.append(' ');
                }
                candidate.append(token);
            }
            if (!valid) {
                continue;
            }
            final String symbol = SPOKEN_SYMBOL_ALIASES.get(candidate.toString());
            if (symbol != null) {
                return new Match(symbol, wordCount);
            }
        }
        return null;
    }

    private static String joinSegments(final List<String> segments) {
        final StringBuilder joined = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (joined.length() > 0 && shouldInsertSpace(joined.toString(), segment)) {
                joined.append(' ');
            }
            joined.append(segment);
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

    private static String replaceSpokenNumbers(final String text) {
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return "";
        }

        final String[] originalTokens = trimmed.split("\\s+");
        final String[] normalizedTokens = new String[originalTokens.length];
        for (int i = 0; i < originalTokens.length; i++) {
            normalizedTokens[i] = normalizeToken(originalTokens[i]);
        }

        final List<String> segments = new ArrayList<>();
        int index = 0;
        while (index < originalTokens.length) {
            final Match match = findLongestNumberMatch(normalizedTokens, index);
            if (match != null) {
                segments.add(match.symbol);
                index += match.wordCount;
            } else {
                segments.add(originalTokens[index]);
                index += 1;
            }
        }
        return String.join(" ", segments);
    }

    @Nullable
    private static Match findLongestNumberMatch(final String[] normalizedTokens, final int startIndex) {
        final int maxWords = Math.min(MAX_NUMBER_WORDS, normalizedTokens.length - startIndex);
        for (int wordCount = maxWords; wordCount >= 1; wordCount--) {
            final StringBuilder candidate = new StringBuilder();
            boolean valid = true;
            for (int i = 0; i < wordCount; i++) {
                final String token = normalizedTokens[startIndex + i];
                if (token.isEmpty()) {
                    valid = false;
                    break;
                }
                if (candidate.length() > 0) {
                    candidate.append(' ');
                }
                candidate.append(token);
            }
            if (!valid) {
                continue;
            }
            final String number = SPOKEN_NUMBER_WORDS.get(candidate.toString());
            if (number != null) {
                return new Match(number, wordCount);
            }
        }
        return null;
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
        registerAliases(aliases, "-", "dash", "hyphen", "minus", "minus sign");
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

        registerAliases(aliases, "(", "open the parenthesis", "open the parentheses", "open the parenthese", "open the paren");
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
        private final String symbol;
        private final int wordCount;

        private Match(final String symbol, final int wordCount) {
            this.symbol = symbol;
            this.wordCount = wordCount;
        }
    }
}
