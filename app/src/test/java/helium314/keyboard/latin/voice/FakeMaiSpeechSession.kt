// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import helium314.keyboard.latin.settings.TranscriptionPreferences.MaiConfig

/** Exercises app lifecycle without loading the SDK's device-native libraries in a JVM. */
internal class FakeMaiSpeechSession : MaiSpeechSession {
    lateinit var listener: MaiSpeechSession.Listener
    lateinit var config: MaiConfig
    var language: String? = null
    val audio = mutableListOf<ByteArray>()
    val commits = mutableListOf<Pair<Int, Long>>()
    var inputFinished = false
    var closed = false
    var acceptWrites = true
    var backpressure = false
    var deferWrites = false
    val work = ArrayDeque<Runnable>()
    private val writer = BoundedAudioWriter(java.util.concurrent.Executor { task ->
        if (deferWrites) work.addLast(task) else task.run()
    }, { closed }, { audio.add(it.copyOf()) }, { listener.onWriteAvailable() },
        { listener.onError("Write failed.") })
    fun runWorker() { while (work.isNotEmpty()) work.removeFirst().run() }
    val totalBytes get() = audio.sumOf { it.size }.toLong()
    override fun start(config: MaiConfig, language: String?, listener: MaiSpeechSession.Listener) {
        this.config = config; this.language = language; this.listener = listener
    }
    override fun write(audio: ByteArray): MaiSpeechSession.WriteResult {
        if (!acceptWrites) return MaiSpeechSession.WriteResult.CLOSED
        if (backpressure) return MaiSpeechSession.WriteResult.BACKPRESSURE
        return writer.write(audio)
    }
    override fun commit(audioEndBytes: Long) {
        val token = commits.size + 1
        commits.add(token to audioEndBytes)
        val task = Runnable { listener.onCommitRequested(token, audioEndBytes) }
        if (deferWrites) work.addLast(task) else task.run()
    }
    override fun finishInput() {
        val task = Runnable { inputFinished = true }
        if (deferWrites) work.addLast(task) else task.run()
    }
    override fun close() { closed = true }
    fun ready() = listener.onReady()
    fun final(text: String, id: String = "result-${commits.size}-${totalBytes}", end: Long = totalBytes,
        token: Int = commits.lastOrNull()?.first ?: 0) = listener.onFinal(id, text, end, token)
    fun ended() = listener.onEnded()
}
