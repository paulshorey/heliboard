// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.json.JSONObject
import kotlin.math.roundToLong

/** Only completed attributed utterances leave this bounded, per-recording ledger. */
internal class GrokTranscriptException(message: String) : IllegalArgumentException(message)

internal class GrokTranscriptAssembler {
    private data class Word(val start: Long, val end: Long, val text: String, val speaker: Int?)
    private val pending = mutableListOf<Word>()
    private val released = mutableSetOf<Word>()
    private var selectedSpeaker: Int? = null
    private var releasedEnd = 0L
    private var interimEnd = -1L
    private var interimStart = -1L
    var settledBytes: Long = 0; private set
    val hasPendingWords: Boolean get() = pending.isNotEmpty() || interimEnd >= 0

    data class Update(val segments: List<TranscriptSegment>, val progressed: Boolean, val filteredWords: Int)

    fun accept(event: JSONObject, submittedBytes: Long): Update {
        val done = event.getString("type") == "transcript.done"
        val start = if (done) 0L else time(event.getDouble("start"))
        val end = start + time(event.getDouble("duration"))
        wire(end <= submittedBytes / 320 + 2) { "Transcript exceeds submitted audio" }
        val text = event.getString("text")
        wire(text.length <= 131072) { "Transcript is too large" }
        val words = event.getJSONArray("words")
        wire(words.length() <= 10000) { "Too many transcript words" }
        val isFinal = done || event.getBoolean("is_final")
        if (!isFinal) {
            val progressed = text.isNotBlank() && end * 320 > settledBytes && end > interimEnd
            if (text.isNotBlank() && end * 320 > settledBytes) {
                val uncoveredStart = maxOf(start, settledBytes / 320)
                interimStart = if (interimStart < 0) uncoveredStart else minOf(interimStart, uncoveredStart)
                interimEnd = maxOf(interimEnd, end)
            }
            return Update(emptyList(), progressed, 0)
        }

        wire(text.isBlank() || words.length() > 0) { "Final text has no speaker attribution" }
        wire(interimStart < 0 || start <= interimStart || end <= interimStart) {
            "Earlier recognized speech has no final result; speaker selection is unsafe"
        }
        val incoming = (0 until words.length()).map { i ->
            val w = words.getJSONObject(i)
            val a = time(w.getDouble("start")); val b = time(w.getDouble("end"))
            wire(a <= b && a >= start - 2 && b <= end + 2) { "Invalid word timing" }
            val speaker = if (w.has("speaker") && !w.isNull("speaker")) w.getInt("speaker") else null
            wire(speaker == null || speaker >= 0) { "Invalid speaker label" }
            val token = w.getString("text")
            wire(token.length <= 4096 && token.none { it == '\u0000' }) { "Invalid word text" }
            Word(a, b, token, speaker)
        }.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.start }, { it.end }))
        // A stitched utterance/EOF may repeat previous finals. It must never rewrite them.
        for (word in incoming.filter { it.start < releasedEnd }) {
            wire(word in released) { "Provider revised already inserted speech" }
        }
        val newWords = incoming.filter { it.start >= releasedEnd }
        // Replace overlapping uninserted chunks using the provider's authoritative final.
        if (newWords.isNotEmpty()) {
            val first = newWords.first().start
            val last = newWords.maxOf { it.end }
            pending.removeAll { old -> first < old.end && old.start < last || old.start == first }
            pending.addAll(newWords)
            pending.sortWith(compareBy({ it.start }, { it.end }))
        }
        wire(pending.size <= 10000 && pending.sumOf { it.text.length } <= 131072) { "Utterance buffer is full" }
        val oldSettled = settledBytes
        // EOF's duration certifies processing of quiet/noise tails too.
        settledBytes = maxOf(settledBytes, end * 320)
        if (interimEnd >= 0 && start <= interimStart) {
            if (end >= interimEnd) { interimStart = -1; interimEnd = -1 }
            else interimStart = maxOf(interimStart, end)
        }
        if (!done && !event.getBoolean("speech_final"))
            return Update(emptyList(), settledBytes > oldSettled, 0)

        val complete = pending.filter { done || it.end <= end }
        wire(complete.all { it.speaker != null }) { "Speaker attribution is missing. Restart and speak first" }
        wire(!done || interimEnd < 0) { "Speech ended without a final transcript" }
        if (selectedSpeaker == null) selectedSpeaker = complete.firstOrNull()?.speaker
        val segments = mutableListOf<TranscriptSegment>()
        val run = mutableListOf<String>()
        fun flush() {
            if (run.isEmpty()) return
            val joined = joinWords(run)
            segments.add(TranscriptSegment(joined, joined.first() in ".,!?;:%)]}"))
            run.clear()
        }
        var filtered = 0
        for (word in complete) {
            if (word.speaker == selectedSpeaker) run.add(word.text)
            else { flush(); filtered++ }
        }
        flush()
        // With a fully attributed single-speaker utterance, retain the provider's
        // exact spacing/quotes/casing instead of rebuilding it from word tokens.
        if (filtered == 0 && complete.isNotEmpty() && complete == incoming && text.isNotBlank()) {
            val native = text.trim()
            segments.clear()
            segments.add(TranscriptSegment(native, native.first() in ".,!?;:%)]}"))
        }
        wire(released.size + complete.size <= 50000) { "Recording transcript ledger is full" }
        pending.removeAll(complete.toSet())
        released.addAll(complete)
        releasedEnd = maxOf(releasedEnd, complete.maxOfOrNull { it.end } ?: releasedEnd)
        return Update(segments, settledBytes > oldSettled || complete.isNotEmpty(), filtered)
    }

    private inline fun wire(condition: Boolean, reason: () -> String) {
        if (!condition) throw GrokTranscriptException(reason())
    }

    private fun time(seconds: Double): Long {
        wire(seconds.isFinite() && seconds >= 0 && seconds <= 86400) { "Invalid transcript time" }
        return (seconds * 100).roundToLong()
    }

    companion object {
        internal fun joinWords(words: List<String>): String = buildString {
            for (raw in words) {
                val word = raw.trim()
                if (word.isEmpty()) continue
                val attach = word.first() in ".,!?;:%)]}、。，！？：；" ||
                    (isCjk(word.first()) && lastOrNull()?.let(::isCjk) == true) ||
                    lastOrNull()?.let { it in "([{“" } == true
                if (isNotEmpty() && !attach) append(' ')
                append(word)
            }
        }
        private fun isCjk(c: Char) = c in '\u3040'..'\u30ff' || c in '\u3400'..'\u9fff'
    }
}
