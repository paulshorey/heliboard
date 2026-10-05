// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import com.microsoft.cognitiveservices.speech.CancellationErrorCode
import com.microsoft.cognitiveservices.speech.CancellationReason
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
import java.util.concurrent.atomic.AtomicInteger

/** Test seam around the SDK; callbacks may arrive on a background thread. */
internal interface MaiSpeechSession {
    interface Listener {
        fun onReady()
        fun onFinal(resultId: String, text: String, audioEndBytes: Long, commitToken: Int)
        fun onCommitRequested(token: Int, audioEndBytes: Long)
        fun onEnded()
        fun onError(message: String, retryable: Boolean)
    }
    fun start(config: MaiConfig, language: String?, listener: Listener)
    fun write(audio: ByteArray): Boolean
    fun commit(audioEndBytes: Long)
    fun finishInput()
    fun close()
}

/** Serializes JNI calls off the main thread. The SDK owns ACKs and audio recovery. */
internal class AzureMaiSpeechSession : MaiSpeechSession {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "MAI-Speech-SDK") }
    private val closed = AtomicBoolean(false)
    private val queuedBytes = AtomicInteger(0)
    private var listener: MaiSpeechSession.Listener? = null
    private var speechConfig: SpeechConfig? = null
    private var audioFormat: AudioStreamFormat? = null
    private var stream: PushAudioInputStream? = null
    private var audioConfig: AudioConfig? = null
    private var recognizer: SpeechRecognizer? = null
    private var inputClosed = false

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
            active.recognized.addEventListener { _, event ->
                val result = event.result
                if (result.reason == ResultReason.RecognizedSpeech || result.reason == ResultReason.NoMatch) {
                    val ticks = result.offset.add(result.duration)
                    val endBytes = ticks.multiply(BigInteger.valueOf(VoiceRecorder.SAMPLE_RATE * 2L))
                        .divide(BigInteger.valueOf(10_000_000L)).toLong()
                    if (!closed.get()) listener.onFinal(result.resultId,
                        if (result.reason == ResultReason.RecognizedSpeech) result.text else "",
                        endBytes, result.commitToken)
                }
            }
            // Intermediate hypotheses deliberately stay inside the SDK.
            active.canceled.addEventListener { _, event ->
                if (closed.get()) return@addEventListener
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
                    listener.onError(message, code in setOf(CancellationErrorCode.ConnectionFailure,
                        CancellationErrorCode.ServiceTimeout, CancellationErrorCode.ServiceError,
                        CancellationErrorCode.ServiceUnavailable, CancellationErrorCode.TooManyRequests))
                } else if (event.reason == CancellationReason.EndOfStream) listener.onEnded()
            }
            active.sessionStopped.addEventListener { _, _ -> if (!closed.get()) listener.onEnded() }
            active.startContinuousRecognitionAsync().get(30, TimeUnit.SECONDS)
            if (!closed.get()) listener.onReady()
        }
    }

    override fun write(audio: ByteArray): Boolean {
        if (closed.get()) return false
        if (queuedBytes.addAndGet(audio.size) > 256_000) {
            queuedBytes.addAndGet(-audio.size)
            return false
        }
        submit {
            try { stream!!.write(audio) }
            finally { queuedBytes.addAndGet(-audio.size) }
        }
        return true
    }

    override fun commit(audioEndBytes: Long) = submit {
        // Advisory request: 0 means it was rejected, not a recognition failure.
        val token = stream!!.commit()
        if (!closed.get()) listener?.onCommitRequested(token, audioEndBytes)
    }

    override fun finishInput() = submit {
        // EOF must precede waiting for sessionStopped. Stopping now can lose the tail.
        if (!inputClosed) {
            inputClosed = true
            stream!!.close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        worker.execute {
            try { recognizer?.stopContinuousRecognitionAsync()?.get(5, TimeUnit.SECONDS) }
            catch (_: Exception) { /* Release handles even after a failed stop. */ }
            for (release in listOf<() -> Unit>({ recognizer?.close() }, { audioConfig?.close() },
                { if (!inputClosed) stream?.close() }, { audioFormat?.close() }, { speechConfig?.close() })) {
                try { release() } catch (_: Exception) { /* Continue releasing other JNI handles. */ }
            }
        }
        worker.shutdown()
    }

    private fun submit(action: () -> Unit) {
        if (closed.get()) return
        worker.execute {
            if (closed.get()) return@execute
            try { action() }
            catch (_: Exception) {
                if (!closed.get()) listener?.onError("Azure Speech SDK could not complete dictation. Check the resource settings and connection.", false)
            } catch (_: LinkageError) {
                if (!closed.get()) listener?.onError("Azure Speech SDK is unavailable on this device.", false)
            }
        }
    }
}
