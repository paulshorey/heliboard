// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Capacity is local JNI work, never an acknowledgment of service receipt. */
internal class BoundedAudioWriter(
    private val worker: Executor,
    private val unavailable: () -> Boolean,
    private val writeAudio: (ByteArray) -> Unit,
    private val onCapacity: () -> Unit,
    private val onFailure: () -> Unit,
) {
    companion object { const val MAX_QUEUED_BYTES = 256_000 }
    private val queuedBytes = AtomicInteger(0)
    private val waiting = AtomicBoolean(false)

    fun write(audio: ByteArray): MaiSpeechSession.WriteResult {
        if (unavailable() || audio.size > MAX_QUEUED_BYTES) return MaiSpeechSession.WriteResult.CLOSED
        while (true) {
            val previous = queuedBytes.get()
            if (previous + audio.size > MAX_QUEUED_BYTES) {
                waiting.set(true)
                // A worker may have released capacity just before waiting was set.
                if (queuedBytes.get() + audio.size <= MAX_QUEUED_BYTES) continue
                return MaiSpeechSession.WriteResult.BACKPRESSURE
            }
            if (queuedBytes.compareAndSet(previous, previous + audio.size)) break
        }
        try {
            worker.execute {
                try { if (!unavailable()) writeAudio(audio) }
                catch (_: Exception) { onFailure() }
                catch (_: LinkageError) { onFailure() }
                finally {
                    queuedBytes.addAndGet(-audio.size)
                    if (!unavailable() && waiting.compareAndSet(true, false)) onCapacity()
                }
            }
        } catch (_: RejectedExecutionException) {
            queuedBytes.addAndGet(-audio.size)
            return MaiSpeechSession.WriteResult.CLOSED
        }
        return MaiSpeechSession.WriteResult.ACCEPTED
    }
}
