// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlin.test.*
import org.junit.Test

class BoundedAudioWriterTest {
    @Test fun fullQueueReturnsBackpressureThenResumesInOrder() {
        val tasks = ArrayDeque<Runnable>()
        val written = mutableListOf<Int>()
        var capacity = 0
        val writer = BoundedAudioWriter(Executor { tasks.addLast(it) }, { false },
            { written.add(it[0].toInt()) }, { capacity++ }, { fail("Unexpected failure") })
        repeat(80) { index -> assertEquals(MaiSpeechSession.WriteResult.ACCEPTED, writer.write(ByteArray(3200) { index.toByte() })) }
        assertEquals(MaiSpeechSession.WriteResult.BACKPRESSURE, writer.write(ByteArray(3200) { 81 }))
        tasks.removeFirst().run()
        assertEquals(1, capacity)
        assertEquals(MaiSpeechSession.WriteResult.ACCEPTED, writer.write(ByteArray(3200) { 81 }))
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals((0..79).toList() + 81, written)
    }
    @Test fun writeExceptionStopsQueuedAudioWithoutContinuingPastAGap() {
        val tasks = ArrayDeque<Runnable>()
        var failed = false
        var attempts = 0
        val writer = BoundedAudioWriter(Executor { tasks.addLast(it) }, { failed },
            { attempts++; throw IllegalStateException() }, {}, { failed = true })
        repeat(3) { writer.write(ByteArray(3200)) }
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
        assertEquals(1, attempts)
        assertEquals(MaiSpeechSession.WriteResult.CLOSED, writer.write(ByteArray(3200)))
    }
    @Test fun canceledOrRejectedWorkerNeverReportsAnAcceptedWrite() {
        var closed = false
        val tasks = ArrayDeque<Runnable>()
        val writer = BoundedAudioWriter(Executor { tasks.addLast(it) }, { closed },
            { fail("Canceled audio written") }, {}, {})
        writer.write(ByteArray(3200)); closed = true; tasks.removeFirst().run()
        assertEquals(MaiSpeechSession.WriteResult.CLOSED, writer.write(ByteArray(3200)))
        val rejected = BoundedAudioWriter(Executor { throw RejectedExecutionException() }, { false }, {}, {}, {})
        assertEquals(MaiSpeechSession.WriteResult.CLOSED, rejected.write(ByteArray(3200)))
    }
}
