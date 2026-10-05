// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.settings.TranscriptionPreferences.MaiConfig
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/** Captures PCM locally and delivers completed MAI transcripts through the IME listener. */
class VoiceInputManager internal constructor(
    private val context: Context,
    private val recorder: VoiceRecorder,
    private val client: MaiTranscriptionClient,
    private val network: VoiceNetworkMonitor = AndroidVoiceNetworkMonitor(context),
    private val audioQueueTimeoutMs: Long = 30_000L,
) {
    constructor(context: Context) : this(context, VoiceRecorder(context), MaiTranscriptionClient())
    companion object {
        private const val TAG = "VoiceInputManager"
        private const val MAX_PENDING_AUDIO_CHUNKS = 300 // 30 seconds at the recorder's cadence
    }

    enum class State { IDLE, RECORDING, PAUSED }

    interface VoiceInputListener {
        fun onStateChanged(state: State)
        fun onTranscriptionResult(text: String, attachesToPrevious: Boolean)
        fun onProcessingStarted()
        fun onProcessingIdle()
        fun onPendingProcessingCancelled()
        fun onError(error: String)
        fun onTranscriptionInterrupted()
        fun onPermissionRequired()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var listener: VoiceInputListener? = null
    private var currentState = State.IDLE
    private var sessionId = 0L
    private var connectionId = 0L
    private var config: MaiConfig = TranscriptionPreferences.readMaiConfig(context.prefs())
    private var ready = false
    private var connecting = false
    private var draining = false
    private var rotating = false
    private var stopRequested = false
    private var captureInterrupted = false
    private var awaitingIntegrityDrain = false
    private var commitWhenFlushed = false
    private var connectionReadyAt = 0L
    private var rotation: Runnable? = null
    private var autoStopSilenceMs = Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS * 1000L
    private data class BufferedAudio(val pcm: ByteArray, val capturedAt: Long)
    private val pendingAudio = ArrayDeque<BufferedAudio>()
    private var audioQueueTimeout: Runnable? = null
    private val autoStop = Runnable { if (isRecording) stopRecording() }

    val isRecording: Boolean get() = currentState == State.RECORDING
    val isPaused: Boolean get() = currentState == State.PAUSED
    val isIdle: Boolean get() = currentState == State.IDLE && !ready && !connecting && !draining && !stopRequested
    val state: State get() = currentState

    fun hasPendingProcessing(): Boolean = pendingAudio.isNotEmpty() || connecting || draining ||
        stopRequested || client.hasPendingProcessing

    fun setListener(listener: VoiceInputListener?) { this.listener = listener }

    fun toggleRecording() {
        when (currentState) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.PAUSED -> resumeRecording()
        }
    }

    fun startRecording(): Boolean {
        if (currentState != State.IDLE || captureInterrupted || awaitingIntegrityDrain) return false
        if (!recorder.hasRecordPermission()) {
            listener?.onPermissionRequired()
            return false
        }
        val continuing = !isIdle
        if (!continuing) config = TranscriptionPreferences.readMaiConfig(context.prefs())
        client.configurationError(config)?.let { listener?.onError(it); return false }
        if (!continuing) {
            invalidateSession()
            val token = sessionId
            if (!network.start { if (token == sessionId) failSession("Internet connection lost. Dictation stopped.", true) }) {
                listener?.onError("No validated internet connection. Connect before starting dictation.")
                return false
            }
        } else {
            // A mic restart must preserve the outgoing session's finals. Capture now
            // and buffer until its EOF; if input is still open, simply keep using it.
            stopRequested = false
            if (draining) rotating = true
            val previousSession = sessionId
            flushAudio()
            if (previousSession != sessionId) return false
        }
        val activeSession = sessionId
        reloadRecorderConfig()
        var captureFailed = false
        fun failCapture(error: String) {
            if (activeSession != sessionId || captureFailed) return
            captureFailed = true
            captureInterrupted = isRecording || hasPendingProcessing()
            // A local capture failure must not cancel speech already sent to Azure.
            // Stop capture and drain both the outgoing session and any buffered tail.
            stopRecording()
            Log.e(TAG, error)
            if (hasPendingProcessing()) listener?.onProcessingStarted()
            listener?.onError(error)
            notifyIdle()
        }
        recorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() {
                if (activeSession == sessionId) Log.i(TAG, "VOICE_STEP_1 microphone started")
            }
            override fun onAudioChunk(pcmData: ByteArray) {
                if (activeSession != sessionId || captureFailed || pcmData.isEmpty()) return
                if (pendingAudio.size >= MAX_PENDING_AUDIO_CHUNKS) {
                    failCapture("Voice audio buffer filled before the connection became ready. Try again.")
                    return
                }
                pendingAudio.addLast(BufferedAudio(pcmData, SystemClock.elapsedRealtime()))
                armAudioQueueTimeout()
                flushAudio()
            }
            override fun onSpeechStarted() {
                if (activeSession != sessionId || captureFailed) return
                cancelAutoStop()
                flushAudio()
            }
            override fun onSpeechStopped() {
                if (activeSession != sessionId || !isRecording) return
                startAutoStop()
                // Defer the advisory commit until every preceding local chunk is accepted.
                commitWhenFlushed = true
                flushAudio()
            }
            override fun onRecordingStopped() {
                if (activeSession == sessionId) Log.i(TAG, "Microphone stopped")
            }
            override fun onRecordingError(error: String) {
                failCapture(error)
            }
        })
        if (!recorder.startRecording() || captureFailed) {
            failCapture("Failed to start microphone recording. Try again.")
            return false
        }
        updateState(State.RECORDING)
        startAutoStop()
        if (!ready && !connecting && !draining) connect()
        else if (ready) scheduleRotation()
        return true
    }

    fun stopRecording() {
        if (isIdle || stopRequested) return
        stopRequested = true
        cancelAutoStop()
        cancelRotation()
        recorder.stopRecording()
        updateState(State.IDLE)
        val activeSession = sessionId
        // AudioRecord callbacks already queued on the main looper include the last chunk.
        handler.post { if (activeSession == sessionId) finishConnection() }
    }

    fun cancelRecording() {
        invalidateSession()
        recorder.stopRecording()
        updateState(State.IDLE)
    }

    fun pauseRecording() {
        if (!isRecording) return
        recorder.pauseRecording()
        updateState(State.PAUSED)
        cancelAutoStop()
        val activeSession = sessionId
        handler.post {
            if (activeSession != sessionId || !isPaused) return@post
            commitWhenFlushed = true
            flushAudio()
        }
    }

    fun resumeRecording() {
        if (!isPaused) return
        recorder.resumeRecording()
        updateState(State.RECORDING)
        startAutoStop()
        if (!ready && !connecting && !draining) connect()
    }

    fun togglePause() {
        when (currentState) {
            State.RECORDING -> pauseRecording()
            State.PAUSED -> resumeRecording()
            State.IDLE -> Unit
        }
    }

    fun destroy() {
        cancelRecording()
        listener = null
    }

    private fun connect() {
        cancelRotation()
        connecting = true
        ready = false
        val activeSession = sessionId
        val activeConnection = ++connectionId
        Log.i(TAG, "VOICE_STEP_3 connecting to ${MaiTranscriptionClient.MODEL}")
        client.startStreaming(config, currentLanguageTag(), object : MaiTranscriptionClient.StreamingCallback {
            private fun isCurrent() = activeSession == sessionId && activeConnection == connectionId
            override fun onStreamReady() {
                if (!isCurrent()) return
                connecting = false
                ready = true
                connectionReadyAt = SystemClock.elapsedRealtime()
                flushAudio()
                if (!isCurrent() || !ready) return
                if (stopRequested) finishConnection()
                else {
                    if (isPaused) { commitWhenFlushed = true; flushAudio() }
                    scheduleRotation()
                }
            }
            override fun onTranscriptionResult(segment: TranscriptSegment) {
                if (!isCurrent()) return
                listener?.onProcessingStarted()
                if (isCurrent()) listener?.onTranscriptionResult(segment.text, segment.attachesToPrevious)
            }
            override fun onPendingProcessingChanged() {
                if (!isCurrent()) return
                if (client.hasPendingProcessing) listener?.onProcessingStarted()
                notifyIdle()
            }
            override fun onStreamError(error: String, incomplete: Boolean) {
                if (isCurrent()) failSession(error, incomplete)
            }
            override fun onAudioWriteAvailable() {
                if (!isCurrent()) return
                if (stopRequested) finishConnection()
                else if (rotating && ready) drainForReplacement()
                else flushAudio()
            }
            override fun onFinalConfirmationStalled() {
                if (!isCurrent() || awaitingIntegrityDrain) return
                awaitingIntegrityDrain = true
                stopRecording()
                listener?.onProcessingStarted()
                listener?.onError("Azure Speech final confirmation is taking too long. Microphone stopped; waiting for pending speech.")
            }
            override fun onStreamClosed() {
                if (!isCurrent()) return
                ready = false
                connecting = false
                draining = false
                if (rotating) {
                    rotating = false
                    if (currentState != State.IDLE || pendingAudio.isNotEmpty()) {
                        connect()
                        return
                    }
                }
                stopRequested = false
                notifyIdle()
            }
            override fun onStreamDrainRequired() {
                if (!isCurrent() || !ready) return
                if (stopRequested) finishConnection() else drainForReplacement()
            }
        })
    }

    private fun flushAudio() {
        val activeSession = sessionId
        while (ready && pendingAudio.isNotEmpty()) {
            val next = pendingAudio.first()
            if (!client.sendAudioChunk(next.pcm)) return
            if (activeSession != sessionId) return
            pendingAudio.removeFirst()
            armAudioQueueTimeout()
        }
        if (ready && pendingAudio.isEmpty() && commitWhenFlushed && !stopRequested) {
            commitWhenFlushed = false
            client.finalizeTurn()
        }
    }

    private fun finishConnection() {
        if (!stopRequested) return
        if (ready) {
            flushAudio()
            if (!ready || pendingAudio.isNotEmpty()) return
            ready = false
            draining = true
            client.finishStreaming()
        } else if (!connecting && !draining) {
            if (pendingAudio.isNotEmpty()) connect()
            else {
                stopRequested = false
                notifyIdle()
            }
        }
    }

    private fun scheduleRotation() {
        cancelRotation()
        val activeSession = sessionId
        rotation = Runnable {
            rotation = null
            if (activeSession != sessionId || stopRequested || !ready) return@Runnable
            drainForReplacement()
        }.also { handler.postDelayed(it, (MaiTranscriptionClient.SESSION_ROTATE_AFTER_MS -
            (SystemClock.elapsedRealtime() - connectionReadyAt)).coerceAtLeast(0)) }
    }

    private fun drainForReplacement() {
        cancelRotation()
        rotating = true
        flushAudio()
        if (!ready || pendingAudio.isNotEmpty()) return
        Log.i(TAG, "Draining MAI session before replacement")
        ready = false
        draining = true
        // Capture continues into the bounded buffer. Open the replacement after
        // EOF so outgoing final results precede the next session's transcript.
        client.finishStreaming()
    }

    private fun failSession(error: String, incomplete: Boolean = false) {
        val interrupted = incomplete || pendingAudio.isNotEmpty() || captureInterrupted
        cancelRecording()
        if (interrupted) listener?.onTranscriptionInterrupted()
        Log.e(TAG, error)
        listener?.onError(error)
    }

    private fun invalidateSession() {
        val hadPending = hasPendingProcessing()
        sessionId++
        connectionId++
        cancelAutoStop()
        cancelRotation()
        client.cancelAll()
        network.stop()
        ready = false
        connecting = false
        draining = false
        rotating = false
        stopRequested = false
        captureInterrupted = false
        awaitingIntegrityDrain = false
        commitWhenFlushed = false
        connectionReadyAt = 0
        pendingAudio.clear()
        clearAudioQueueTimeout()
        if (hadPending) listener?.onPendingProcessingCancelled()
        notifyIdle()
    }

    private fun reloadRecorderConfig() {
        val prefs = context.prefs()
        recorder.updateSilenceConfig(
            TranscriptionPreferences.readVoiceChunkSilenceMs(prefs).toLong(),
            prefs.getInt(Settings.PREF_VOICE_SILENCE_THRESHOLD, Defaults.PREF_VOICE_SILENCE_THRESHOLD)
                .coerceIn(40, 5000).toDouble(),
        )
        autoStopSilenceMs = prefs.getInt(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS,
            Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS).coerceIn(5, 300) * 1000L
    }

    private fun currentLanguageTag(): String? = try { Settings.getValues()?.mLocale?.toLanguageTag() }
        catch (_: Exception) { null }

    private fun armAudioQueueTimeout() {
        clearAudioQueueTimeout()
        val oldest = pendingAudio.firstOrNull() ?: return
        val token = sessionId
        audioQueueTimeout = Runnable {
            if (token == sessionId) failSession("Voice audio waited too long to upload. Dictation stopped.", true)
        }.also { handler.postDelayed(it,
            (audioQueueTimeoutMs - (SystemClock.elapsedRealtime() - oldest.capturedAt)).coerceAtLeast(0)) }
    }
    private fun clearAudioQueueTimeout() {
        audioQueueTimeout?.let { handler.removeCallbacks(it) }
        audioQueueTimeout = null
    }

    private fun updateState(value: State) {
        if (currentState == value) return
        currentState = value
        listener?.onStateChanged(value)
    }
    private fun notifyIdle() {
        if (hasPendingProcessing()) return
        awaitingIntegrityDrain = false
        if (captureInterrupted) {
            captureInterrupted = false
            listener?.onTranscriptionInterrupted()
        }
        if (currentState == State.IDLE && !ready) network.stop()
        listener?.onProcessingIdle()
    }
    private fun startAutoStop() {
        cancelAutoStop()
        handler.postDelayed(autoStop, autoStopSilenceMs)
    }
    private fun cancelAutoStop() { handler.removeCallbacks(autoStop) }
    private fun cancelRotation() {
        rotation?.let { handler.removeCallbacks(it) }
        rotation = null
    }
}
