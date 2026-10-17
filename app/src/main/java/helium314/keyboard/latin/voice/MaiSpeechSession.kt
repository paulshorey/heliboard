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
import helium314.keyboard.latin.utils.Log
import java.math.BigInteger
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Test seam around the SDK; callbacks may arrive on a background thread. */
internal interface MaiSpeechSession {
    enum class WriteResult { ACCEPTED, BACKPRESSURE, CLOSED }
    interface Listener {
        /** Local recognizer initialization permits writes; it is not service acceptance. */
        fun onReady()
        fun onConnected() {}
        fun onDisconnected() {}
        fun onFinal(resultId: String, text: String, audioEndBytes: Long, commitToken: Int)
        /** Liveness metadata only; provisional text never leaves the SDK. */
        fun onInterim()
        fun onCommitRequested(token: Int, audioEndBytes: Long)
        fun onEnded()
        fun onError(message: String, retryable: Boolean = false, startupRejection: Boolean = false)
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
    companion object {
        // A replacement must not create native handles while its predecessor is
        // still stopping/disposal is running. One process-wide lane also avoids
        // accumulating a new cleanup thread on every rapid mic tap or retry.
        private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "MAI-Speech-SDK") }
    }
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
                transport.connected.addEventListener { _, _ ->
                    if (!closed.get() && !failed.get()) listener.onConnected()
                }
                transport.disconnected.addEventListener { _, _ ->
                    // This is a transport observation, not a recognition cancellation.
                    // Let the SDK reconnect or deliver its actual Canceled error code.
                    if (!closed.get() && !failed.get() && !ended.get()) listener.onDisconnected()
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
            active.recognizing.addEventListener { _, event ->
                if (!closed.get() && !failed.get() && event.result.text.isNotBlank()) listener.onInterim()
            }
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
                    fail(message, code in setOf(CancellationErrorCode.ConnectionFailure,
                        CancellationErrorCode.ServiceTimeout, CancellationErrorCode.ServiceUnavailable,
                        CancellationErrorCode.ServiceError, CancellationErrorCode.ServiceRedirectTemporary),
                        code in setOf(CancellationErrorCode.AuthenticationFailure, CancellationErrorCode.Forbidden,
                            CancellationErrorCode.BadRequest, CancellationErrorCode.TooManyRequests))
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
            val started = System.nanoTime()
            Log.i("MaiTranscription", "VOICE_STOP native disposal started ended=${ended.get()}")
            try { recognizer?.stopContinuousRecognitionAsync()?.get(5, TimeUnit.SECONDS) }
            catch (_: Exception) {
                Log.w("MaiTranscription", "VOICE_STOP native stop did not finish within its cleanup wait")
            }
            for (release in listOf<() -> Unit>({ connection?.close() }, { recognizer?.close() }, { audioConfig?.close() },
                { if (!inputClosed.get()) stream?.close() }, { audioFormat?.close() }, { speechConfig?.close() })) {
                try { release() } catch (_: Exception) { /* Continue releasing other JNI handles. */ }
            }
            Log.i("MaiTranscription", "VOICE_STOP native disposal finished elapsedMs=${
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)}")
        }
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
    private fun fail(message: String, retryable: Boolean = false, startupRejection: Boolean = false) {
        if (!closed.get() && failed.compareAndSet(false, true)) listener?.onError(message, retryable, startupRejection)
    }
}
