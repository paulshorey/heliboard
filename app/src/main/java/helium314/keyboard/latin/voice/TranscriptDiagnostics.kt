// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/** Content-free punctuation/casing metadata. Never returns letters, numbers or text fragments. */
object TranscriptDiagnostics {
    const val VERSION = 2
    private val lexicalWords = Regex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}'’]*")
    private val lowerAfterStop = Regex("[.!?。！？][\\\"'”’)}\\]]*\\s+\\p{Ll}")
    private val stops = Regex("[.!?。！？]+")

    @JvmStatic fun shape(text: CharSequence): String {
        val value = text.toString()
        val first = value.firstOrNull { it.isLetterOrDigit() }
        val start = when { first == null -> "none"; first.isUpperCase() -> "upper";
            first.isLowerCase() -> "lower"; first.isDigit() -> "number"; else -> "other" }
        val tail = value.trimEnd().lastOrNull()
        val spans = value.split(stops).map { lexicalWords.findAll(it).count() }.filter { it > 0 }
        val ending = when (tail) {
            '.', '。' -> "period"; '?', '？' -> "question"; '!', '！' -> "exclamation"
            ',', '，', '、' -> "comma"; ':', '：' -> "colon"; ';', '；' -> "semicolon"
            null -> "empty"; else -> "other"
        }
        return "chars=${value.length},words=${lexicalWords.findAll(value).count()}," +
            "periods=${value.count { it == '.' || it == '。' }},commas=${value.count { it in ",，、" }}," +
            "questions=${value.count { it in "?？" }},exclamations=${value.count { it in "!！" }}," +
            "colons=${value.count { it in ":：" }},semicolons=${value.count { it in ";；" }}," +
            "quotes=${value.count { it in "\"“”‘’" }},start=$start,end=$ending," +
            "lowerAfterStop=${lowerAfterStop.findAll(value).count()}," +
            "oneWordSpans=${spans.count { it == 1 }},shortSpans=${spans.count { it <= 3 }}"
    }
}
