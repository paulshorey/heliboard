// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

/** Bounded metadata only, including before a missing-speaker rejection. */
internal object GrokEventDiagnostics {
    fun describe(event: JSONObject, submittedBytes: Long): String {
        val words = event.optJSONArray("words")
        val count = words?.length() ?: 0
        val speakers = sortedMapOf<Int, Int>()
        val tokenText = mutableListOf<String>()
        val missingIntervals = mutableListOf<String>()
        var missing = 0
        var missingLexical = 0
        for (i in 0 until minOf(count, 10000)) {
            val word = words?.optJSONObject(i) ?: continue
            val text = word.optString("text")
            tokenText.add(text)
            val speaker = if (word.has("speaker") && !word.isNull("speaker")) word.optInt("speaker", -1) else -1
            if (speaker >= 0) speakers[speaker] = (speakers[speaker] ?: 0) + 1
            else {
                missing++
                if (text.any(Char::isLetterOrDigit)) missingLexical++
                if (missingIntervals.size < 8) missingIntervals.add("$i:${millis(word.optDouble("start"))}-${millis(word.optDouble("end"))}")
            }
        }
        val kind = when {
            event.optString("type") == "transcript.done" -> "eof"
            !event.optBoolean("is_final") -> "interim"
            event.optBoolean("speech_final") -> "utterance"
            else -> "chunk"
        }
        val confidence = event.optDouble("end_of_turn_confidence")
        val safeConfidence = if (confidence.isFinite() && confidence in 0.0..1.0)
            String.format(Locale.ROOT, "%.3f", confidence) else "absent"
        val start = if (kind == "eof") 0.0 else event.optDouble("start")
        val duration = event.optDouble("duration")
        val end = start + duration
        val lag = if (end.isFinite() && end in 0.0..86400.0) submittedBytes / 32 - (end * 1000).roundToLong() else -1
        return "kind=$kind startMs=${millis(start)} endMs=${millis(end)} audioLagMs=$lag " +
            "endConfidence=$safeConfidence words=$count speakers=${speakers.entries.take(8).joinToString(",") { "${it.key}:${it.value}" }} " +
            "speakerKinds=${speakers.size} missingLabels=$missing missingLexical=$missingLexical " +
            "missingPunctuation=${missing - missingLexical} missingIntervals=${missingIntervals.joinToString(",")} " +
            "nativeShape={${TranscriptDiagnostics.shape(event.optString("text"))}} " +
            "wordShape={${TranscriptDiagnostics.shape(GrokTranscriptAssembler.joinWords(tokenText))}}"
    }

    private fun millis(value: Double): String =
        if (value.isFinite() && value in 0.0..86400.0) (value * 1000).roundToLong().toString() else "invalid"
}
