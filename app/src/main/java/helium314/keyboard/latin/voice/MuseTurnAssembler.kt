// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import org.json.JSONObject

/** Session-scoped speaker selection. Partials are evidence, never editor text. */
internal class MuseTurnAssembler {
    internal data class CompletedTurn(val id: Int, val text: String, val selected: Boolean)
    private data class Turn(
        var speaker: String? = null,
        var conflictingSpeaker: Boolean = false,
        var text: String? = null,
        var hasWords: Boolean = false,
        var ended: Boolean = false,
    )
    // Insertion order is speechStart order; numeric IDs have no ordering contract.
    private val turns = linkedMapOf<Int, Turn>()
    private val settled = mutableSetOf<Int>()
    private var activeId: Int? = null
    private var selectedSpeaker: String? = null

    fun accept(event: JSONObject): List<CompletedTurn> {
        when (event.getString("type")) {
            "speechStart" -> {
                val id = event.getInt("turnId")
                check(id !in settled && id !in turns) { "Repeated speech start" }
                check(activeId?.let { turns[it]?.ended == false } != true) { "Overlapping speech boundaries" }
                check(turns.size < 64 && settled.size < 8192) { "Voice turn queue is full" }
                turns[id] = Turn()
                activeId = id
            }
            "transcript" -> {
                val turn = turns[activeId] ?: error("Transcript without a speech turn")
                if (event.getString("transcript").isNotBlank()) turn.hasWords = true
            }
            "speaker" -> {
                val turn = turns[activeId] ?: error("Speaker without a speech turn")
                // The label describes the preceding active span, not the next one.
                check(!turn.ended) { "Speaker arrived outside an active speech span" }
                val label = event.getString("label")
                check(label.isNotBlank()) { "Missing speaker label" }
                if (turn.speaker != null && turn.speaker != label) turn.conflictingSpeaker = true
                turn.speaker = label
            }
            "speechEnd" -> {
                val id = event.getInt("turnId")
                if (id !in settled) (turns[id] ?: error("Unknown speech boundary")).ended = true
            }
            "speechComplete" -> {
                val id = event.getInt("turnId")
                if (id !in settled) {
                    val turn = turns[id] ?: error("Unknown completed turn")
                    val text = event.getString("transcript")
                    check(turn.text == null || turn.text == text) { "Completed transcript changed" }
                    turn.text = text
                    turn.hasWords = turn.hasWords || text.isNotBlank()
                }
            }
        }
        val results = mutableListOf<CompletedTurn>()
        while (turns.isNotEmpty()) {
            val (id, turn) = turns.entries.first()
            val text = turn.text ?: break
            if (!turn.ended) break
            if (turn.conflictingSpeaker || (text.isNotBlank() && turn.speaker == null)) break
            if (text.isNotBlank() && selectedSpeaker == null) selectedSpeaker = turn.speaker
            results.add(CompletedTurn(id, text, text.isNotBlank() && turn.speaker == selectedSpeaker))
            turns.remove(id)
            settled.add(id)
        }
        return results
    }

    fun pendingFinalIds(): Set<Int> = turns.filterValues {
        it.hasWords && (it.ended || it.text != null)
    }.keys.toSet()
    fun hasPendingTurns(): Boolean = turns.values.any { it.hasWords }
    fun verifyFinished() {
        check(!hasPendingTurns()) { "Muse ended with unresolved text or speaker attribution. Tap the mic and speak first again." }
    }
}
