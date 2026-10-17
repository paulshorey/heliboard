// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

/** Bounded, memory-only PCM suffix. Final offsets are the only replay cut points. */
internal class PendingAudioReplay(private val capacity: Int = VoiceRecorder.SAMPLE_RATE * 2 * 30) {
    private data class Chunk(val start: Long, val audio: ByteArray) {
        val end get() = start + audio.size
    }
    private val chunks = ArrayDeque<Chunk>()
    private var bytes = 0
    fun add(start: Long, audio: ByteArray) {
        chunks.addLast(Chunk(start, audio.copyOf()))
        bytes += audio.size
        while (bytes > capacity && chunks.isNotEmpty()) bytes -= chunks.removeFirst().audio.size
    }
    fun confirm(end: Long) {
        while (chunks.firstOrNull()?.end?.let { it <= end } == true)
            bytes -= chunks.removeFirst().audio.size
        val first = chunks.firstOrNull() ?: return
        if (end > first.start) {
            chunks.removeFirst()
            val suffix = first.audio.copyOfRange((end - first.start).toInt(), first.audio.size)
            bytes -= first.audio.size - suffix.size
            chunks.addFirst(Chunk(end, suffix))
        }
    }
    /** Null means the complete unfinished suffix no longer fits; never guess a gap. */
    fun suffix(start: Long, end: Long): List<ByteArray>? {
        if (start == end) return emptyList()
        if (chunks.firstOrNull()?.start?.let { it > start } != false || chunks.last().end != end) return null
        return chunks.filter { it.end > start }.map {
            it.audio.copyOfRange((start - it.start).coerceAtLeast(0).toInt(), it.audio.size)
        }
    }
    fun clear() { chunks.clear(); bytes = 0 }
}
