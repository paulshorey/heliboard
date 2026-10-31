// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Last accepted setup's words for the on-device settings viewer, never persisted or exported. */
object VoiceVocabularySnapshot {
    data class Snapshot(
        val customTerms: List<String>,
        val editorTerms: List<String>,
        val connectedAtMillis: Long,
    ) {
        // Prevent accidental logging of words through this value's string representation.
        override fun toString() = "VocabularySnapshot(custom=${customTerms.size}, editor=${editorTerms.size})"
    }

    private val current = MutableStateFlow<Snapshot?>(null)
    val latest = current.asStateFlow()

    internal fun record(terms: List<String>, editorTerms: List<String>) {
        val editorSet = editorTerms.toSet()
        current.value = Snapshot(
            customTerms = terms.filterNot { it in editorSet },
            editorTerms = editorTerms.toList(),
            connectedAtMillis = System.currentTimeMillis(),
        )
    }
}
