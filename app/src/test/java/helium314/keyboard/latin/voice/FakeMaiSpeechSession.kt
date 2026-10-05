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
    val totalBytes get() = audio.sumOf { it.size }.toLong()
    override fun start(config: MaiConfig, language: String?, listener: MaiSpeechSession.Listener) {
        this.config = config; this.language = language; this.listener = listener
    }
    override fun write(audio: ByteArray): Boolean {
        if (!acceptWrites) return false
        this.audio.add(audio.copyOf()); return true
    }
    override fun commit(audioEndBytes: Long) {
        val token = commits.size + 1
        commits.add(token to audioEndBytes)
        listener.onCommitRequested(token, audioEndBytes)
    }
    override fun finishInput() { inputFinished = true }
    override fun close() { closed = true }
    fun ready() = listener.onReady()
    fun final(text: String, id: String = "result-${commits.size}-${totalBytes}", end: Long = totalBytes,
        token: Int = commits.lastOrNull()?.first ?: 0) = listener.onFinal(id, text, end, token)
    fun ended() = listener.onEnded()
}
