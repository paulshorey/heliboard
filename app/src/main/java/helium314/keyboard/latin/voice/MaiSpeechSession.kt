// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import com.microsoft.cognitiveservices.speech.CancellationErrorCode
import com.microsoft.cognitiveservices.speech.CancellationReason
import com.microsoft.cognitiveservices.speech.Connection
import com.microsoft.cognitiveservices.speech.ResultReason
import com.microsoft.cognitiveservices.speech.SpeechConfig
import com.microsoft.cognitiveservices.speech.SpeechRecognizer
import com.microsoft.cognitiveservices.speech.audio.AudioConfig
import com.microsoft.cognitiveservices.speech.audio.AudioInputStream
import com.microsoft.cognitiveservices.speech.audio.AudioStreamFormat
import com.microsoft.cognitiveservices.speech.audio.PushAudioInputStream
import helium314.keyboard.latin.settings.TranscriptionPreferences.MaiConfig
import java.math.BigInteger
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Test seam around the SDK; callbacks may arrive on a background thread. */
internal interface MaiSpeechSession {
    enum class WriteResult { ACCEPTED, BACKPRESSURE, CLOSED }
    interface Listener {
        fun onReady()
        fun onFinal(resultId: String, text: String, audioEndBytes: Long, commitToken: Int)
        fun onCommitRequested(token: Int, audioEndBytes: Long)
        fun onEnded()
        fun onError(message: String)
        fun onWriteAvailable()
    }
    fun start(config: MaiConfig, language: String?, listener: Listener)
    fun write(audio: ByteArray): WriteResult
    fun commit(audioEndBytes: Long)
    fun finishInput()
    fun close()
}

/** Serializes JNI calls off the main thread. The SDK owns ACKs and audio recovery. */
internal class AzureMaiSpeechSession : MaiSpeechSession {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "MAI-Speech-SDK") }
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private var listener: MaiSpeechSession.Listener? = null
    private var speechConfig: SpeechConfig? = null
    private var audioFormat: AudioStreamFormat? = null
    private var stream: PushAudioInputStream? = null
    private var audioConfig: AudioConfig? = null
    private var recognizer: SpeechRecognizer? = null
    private var connection: Connection? = null
    private val inputClosed = AtomicBoolean(false)
    private val ended = AtomicBoolean(false)
    private val audioWriter = BoundedAudioWriter(worker, { closed.get() || failed.get() },
        { stream!!.write(it) }, { listener?.onWriteAvailable() },
        { fail("Azure Speech could not accept microphone audio. Dictation stopped.") })

    override fun start(config: MaiConfig, language: String?, listener: MaiSpeechSession.Listener) {
        this.listener = listener
        submit {
            speechConfig = SpeechConfig.fromEndpoint(URI(MaiTranscriptionClient.buildSpeechEndpoint(config.region)), config.apiKey)
            speechConfig!!.setModel(MaiTranscriptionClient.MODEL)
            if (language != null) speechConfig!!.setSpeechRecognitionLanguage(language)
            audioFormat = AudioStreamFormat.getWaveFormatPCM(VoiceRecorder.SAMPLE_RATE.toLong(), 16, 1)
            stream = AudioInputStream.createPushStream(audioFormat)
            audioConfig = AudioConfig.fromStreamInput(stream)
            val active = SpeechRecognizer(speechConfig, audioConfig)
            recognizer = active
            connection = Connection.fromRecognizer(active).also { transport ->
                transport.disconnected.addEventListener { _, _ ->
                    // Closing input deliberately can disconnect as recognition reaches EOF.
                    // While input is open, do not allow the SDK to silently reconnect capture.
                    if (!inputClosed.get() && !ended.get())
                        fail("Azure Speech disconnected. Dictation stopped; check your internet connection.")
                }
            }
            active.recognized.addEventListener { _, event ->
                val result = event.result
                if (result.reason == ResultReason.RecognizedSpeech || result.reason == ResultReason.NoMatch) {
                    val ticks = result.offset.add(result.duration)
                    val endBytes = ticks.multiply(BigInteger.valueOf(VoiceRecorder.SAMPLE_RATE * 2L))
                        .divide(BigInteger.valueOf(10_000_000L)).toLong()
                    if (!closed.get() && !failed.get()) listener.onFinal(result.resultId,
                        if (result.reason == ResultReason.RecognizedSpeech) result.text else "",
                        endBytes, result.commitToken)
                }
            }
            // Intermediate hypotheses deliberately stay inside the SDK.
            active.canceled.addEventListener { _, event ->
                if (closed.get() || failed.get()) return@addEventListener
                if (event.reason == CancellationReason.Error) {
                    val code = event.errorCode
                    val message = when (code) {
                        CancellationErrorCode.AuthenticationFailure, CancellationErrorCode.Forbidden ->
                            "Azure Speech authentication failed. Check the key and matching region in Settings."
                        CancellationErrorCode.BadRequest ->
                            "Azure Speech rejected the configuration. Check the resource region and MAI availability."
                        CancellationErrorCode.TooManyRequests -> "Azure Speech rate limited dictation. Try again later."
                        else -> "Azure Speech connection failed (${code.name}). Check your internet connection."
                    }
                    // errorDetails may echo credentials or text; never log or forward it.
                    fail(message)
                } else if (event.reason == CancellationReason.EndOfStream) {
                    ended.set(true)
                    listener.onEnded()
                }
            }
            active.sessionStopped.addEventListener { _, _ ->
                ended.set(true)
                if (!closed.get() && !failed.get()) listener.onEnded()
            }
            active.startContinuousRecognitionAsync().get(30, TimeUnit.SECONDS)
            if (!closed.get() && !failed.get()) listener.onReady()
        }
    }

    override fun write(audio: ByteArray) = audioWriter.write(audio)

    override fun commit(audioEndBytes: Long) = submit {
        // Advisory request: 0 means it was rejected, not a recognition failure.
        val token = stream!!.commit()
        if (!closed.get()) listener?.onCommitRequested(token, audioEndBytes)
    }

    override fun finishInput() = submit {
        // EOF must precede waiting for sessionStopped. Stopping now can lose the tail.
        if (inputClosed.compareAndSet(false, true)) {
            stream!!.close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute {
            try { recognizer?.stopContinuousRecognitionAsync()?.get(5, TimeUnit.SECONDS) }
            catch (_: Exception) { /* Release handles even after a failed stop. */ }
            for (release in listOf<() -> Unit>({ connection?.close() }, { recognizer?.close() }, { audioConfig?.close() },
                { if (!inputClosed.get()) stream?.close() }, { audioFormat?.close() }, { speechConfig?.close() })) {
                try { release() } catch (_: Exception) { /* Continue releasing other JNI handles. */ }
            }
        }
        worker.shutdown()
    }

    private fun submit(action: () -> Unit) {
        if (closed.get() || failed.get()) return
        worker.execute {
            if (closed.get() || failed.get()) return@execute
            try { action() }
            catch (_: Exception) {
                fail("Azure Speech SDK could not complete dictation. Check the resource settings and connection.")
            } catch (_: LinkageError) {
                fail("Azure Speech SDK is unavailable on this device.")
            }
        }
    }
    private fun fail(message: String) {
        if (!closed.get() && failed.compareAndSet(false, true)) listener?.onError(message)
    }
}
