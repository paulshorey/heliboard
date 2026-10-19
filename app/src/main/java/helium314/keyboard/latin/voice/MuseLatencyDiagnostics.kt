// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.SystemClock
import helium314.keyboard.latin.utils.Log
import org.json.JSONObject

/** Optional local receipt-time measurements. Never log payloads, labels or session credentials. */
internal class MuseLatencyDiagnostics(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val emit: (String) -> Unit = { Log.i("MuseTranscription", it) },
) {
    private data class Turn(var end: Long? = null, var complete: Long? = null,
        var speaker: Long? = null, var partial: Long? = null, var punctuation: Long? = null)
    private val started = clock()
    private val turns = mutableMapOf<Int, Turn>()
    private var active: Int? = null
    private var lastProgress: Long? = null

    fun event(event: JSONObject, processingLeadMs: Long, socketBytes: Long, receivedAtMs: Long = clock()) {
        val now = receivedAtMs
        val type = event.getString("type")
        val id = when (type) {
            "speechStart", "speechEnd", "speechComplete" -> event.getInt("turnId")
            else -> active
        }
        if (type == "speechStart") {
            active = id
            // Match the assembler bound, including when called independently in tests.
            if (turns.size >= 64) turns.remove(turns.keys.first())
            turns[id!!] = Turn()
        }
        val turn = turns[id]
        var report = type in setOf("speechStart", "speechEnd", "speechComplete") ||
            (type == "speaker" && turn?.speaker == null)
        when (type) {
            "speechEnd" -> turn?.end = turn?.end ?: now
            "speechComplete" -> turn?.complete = turn?.complete ?: now
            "speaker" -> turn?.speaker = now
            "transcript" -> if (turn != null) {
                val text = event.getString("transcript")
                if (text.isNotBlank() && turn.partial == null) { turn.partial = now; report = true }
                if (text.any { it in ".!?" } && turn.punctuation == null) { turn.punctuation = now; report = true }
            }
            "audioProgress" -> if (lastProgress == null || now - lastProgress!! >= 1000) {
                lastProgress = now
                emit("timing phase=progress elapsedMs=${now - started} " +
                    "audioProcessedMs=${event.getLong("audioProcessedMs")} " +
                    "processingLeadMs=$processingLeadMs socketBytes=$socketBytes")
            }
        }
        if (report) {
            // type is allowlisted; never interpolate arbitrary text or speaker labels.
            val text = if (type == "transcript" || type == "speechComplete") event.getString("transcript") else ""
            emit("timing phase=event type=$type turn=$id elapsedMs=${now - started} " +
                "audioProcessedMs=${event.getLong("audioProcessedMs")} chars=${text.length} " +
                "sentenceMarks=${text.count { it in ".!?" }} handlerQueueMs=${clock() - receivedAtMs}")
        }
    }

    fun released(id: Int, selected: Boolean) {
        val turn = turns.remove(id) ?: return
        val now = clock()
        fun elapsed(value: Long?) = value?.minus(started)?.toString() ?: "none"
        val endToFinal = turn.complete?.let { final -> turn.end?.let { final - it } }
        emit("timing phase=release turn=$id selected=$selected elapsedMs=${now - started} " +
            "firstPartialMs=${elapsed(turn.partial)} firstPunctuationMs=${elapsed(turn.punctuation)} " +
            "speakerMs=${elapsed(turn.speaker)} speechEndMs=${elapsed(turn.end)} " +
            "completeMs=${elapsed(turn.complete)} endToFinalMs=${endToFinal ?: "none"} " +
            "finalToReleaseMs=${turn.complete?.let { now - it } ?: "none"}")
    }
}
