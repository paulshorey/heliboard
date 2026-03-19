// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies deterministic edits to newly transcribed voice chunks before cleanup.
 *
 * <p>This runs on each individual chunk as it arrives so short-phrase rules stay scoped to the
 * newly transcribed text instead of any later merged queue item.</p>
 */
public final class VoiceTranscriptionPreprocessor {
    private static final int SHORT_PHRASE_TRAILING_PERIOD_LIMIT = 40;
    private static final Pattern TRAILING_PERIOD_PATTERN = Pattern.compile("(?<!\\.)\\.(\\s*)$");

    private static final PhraseReplacement[] SPECIAL_CHARACTER_REPLACEMENTS =
            new PhraseReplacement[] {
                    replacement("question mark", "?"),
                    replacement("exclamation point", "!"),
                    replacement("exclamation mark", "!"),
                    replacement("semicolon", ";"),
                    replacement("colon", ":"),
                    replacement("period", "."),
                    replacement("full stop", "."),
                    replacement("dot", "."),
                    replacement("comma", ","),
                    replacement("apostrophe", "'"),
                    replacement("single quote", "'"),
                    replacement("double quote", "\""),
                    replacement("quotation mark", "\""),
                    replacement("hashtag", "#"),
                    replacement("hash tag", "#"),
                    replacement("number sign", "#"),
                    replacement("pound sign", "#"),
                    replacement("backtick", "`"),
                    replacement("ampersand", "&"),
                    replacement("asterisk", "*"),
                    replacement("star", "*"),
                    replacement("plus sign", "+"),
                    replacement("minus sign", "-"),
                    replacement("hyphen", "-"),
                    replacement("dash", "-"),
                    replacement("underscore", "_"),
                    replacement("equals sign", "="),
                    replacement("equal sign", "="),
                    replacement("at sign", "@"),
                    replacement("percent sign", "%"),
                    replacement("caret", "^"),
                    replacement("pipe", "|"),
                    replacement("vertical bar", "|"),
                    replacement("tilde", "~"),
                    replacement("forward slash", "/"),
                    replacement("slash", "/"),
                    replacement("backslash", "\\"),
                    replacement("open parenthesis", "("),
                    replacement("open parentheses", "("),
                    replacement("close parenthesis", ")"),
                    replacement("close parentheses", ")"),
                    replacement("open bracket", "["),
                    replacement("open square bracket", "["),
                    replacement("close bracket", "]"),
                    replacement("close square bracket", "]"),
                    replacement("open brace", "{"),
                    replacement("open curly brace", "{"),
                    replacement("open curly bracket", "{"),
                    replacement("close brace", "}"),
                    replacement("close curly brace", "}"),
                    replacement("close curly bracket", "}"),
                    replacement("open angle bracket", "<"),
                    replacement("close angle bracket", ">"),
                    replacement("less than sign", "<"),
                    replacement("greater than sign", ">"),
                    replacement("ellipsis", "...")
            };

    private VoiceTranscriptionPreprocessor() {
        // Utility class.
    }

    public static String preprocessChunk(@Nullable final String text) {
        if (text == null) {
            return "";
        }
        if (text.isEmpty()) {
            return text;
        }

        String normalized = stripTrailingPeriodIfShort(text);
        for (PhraseReplacement replacement : SPECIAL_CHARACTER_REPLACEMENTS) {
            normalized = replacement.apply(normalized);
        }
        return normalized;
    }

    private static String stripTrailingPeriodIfShort(final String text) {
        if (text.trim().length() >= SHORT_PHRASE_TRAILING_PERIOD_LIMIT) {
            return text;
        }

        final Matcher trailingPeriodMatcher = TRAILING_PERIOD_PATTERN.matcher(text);
        if (trailingPeriodMatcher.find()) {
            return trailingPeriodMatcher.replaceFirst("$1");
        }
        return text;
    }

    private static PhraseReplacement replacement(
            final String spokenPhrase,
            final String symbol
    ) {
        return new PhraseReplacement(
                Pattern.compile(
                        "(?i)(?<![\\p{L}\\p{N}])"
                                + buildSpokenPhrasePattern(spokenPhrase)
                                + "(?![\\p{L}\\p{N}])"
                ),
                symbol
        );
    }

    private static String buildSpokenPhrasePattern(final String spokenPhrase) {
        final String[] parts = spokenPhrase.trim().split("\\s+");
        final StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                pattern.append("\\s+");
            }
            pattern.append(Pattern.quote(parts[i]));
        }
        return pattern.toString();
    }

    private static final class PhraseReplacement {
        private final Pattern mPattern;
        private final String mReplacement;

        private PhraseReplacement(final Pattern pattern, final String replacement) {
            mPattern = pattern;
            mReplacement = replacement;
        }

        private String apply(final String text) {
            return mPattern.matcher(text).replaceAll(Matcher.quoteReplacement(mReplacement));
        }
    }
}
