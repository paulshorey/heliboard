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
) {
    constructor(context: Context) : this(context, VoiceRecorder(context), MaiTranscriptionClient())
    companion object {
        private const val TAG = "VoiceInputManager"
        private const val MAX_PENDING_AUDIO_CHUNKS = 300 // 30 seconds at the recorder's cadence
        private const val PREFIX_HOLD_CHUNKS = 3 // retain 300 ms before the next speech onset
        private const val MAX_RECONNECT_ATTEMPTS = 3
    }

    enum class State { IDLE, RECORDING, PAUSED }

    interface VoiceInputListener {
        fun onStateChanged(state: State)
        fun onTranscriptionResult(text: String, attachesToPrevious: Boolean)
        fun onProcessingStarted()
        fun onProcessingIdle()
        fun onPendingProcessingCancelled()
        fun onError(error: String)
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
    private var holdUntilSpeech = false
    private var reconnectAttempts = 0
    private var connectionReadyAt = 0L
    private var reconnect: Runnable? = null
    private var rotation: Runnable? = null
    private var autoStopSilenceMs = Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS * 1000L
    private val pendingAudio = ArrayDeque<ByteArray>()
    private val heldPrefix = ArrayDeque<ByteArray>()
    private val autoStop = Runnable { if (isRecording) stopRecording() }

    val isRecording: Boolean get() = currentState == State.RECORDING
    val isPaused: Boolean get() = currentState == State.PAUSED
    val isIdle: Boolean get() = currentState == State.IDLE && !connecting && !draining && !stopRequested
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
        if (currentState != State.IDLE) return false
        if (!recorder.hasRecordPermission()) {
            listener?.onPermissionRequired()
            return false
        }
        val continuing = !isIdle
        if (!continuing) config = TranscriptionPreferences.readMaiConfig(context.prefs())
        client.configurationError(config)?.let { listener?.onError(it); return false }
        if (!continuing) invalidateSession()
        else {
            // A mic restart must preserve the outgoing session's finals. Capture now
            // and buffer until its EOF; if input is still open, simply keep using it.
            stopRequested = false
            if (draining) rotating = true
            holdUntilSpeech = false
            releaseHeldPrefix()
            val previousSession = sessionId
            flushAudio()
            if (previousSession != sessionId) return false
        }
        val activeSession = sessionId
        reloadRecorderConfig()
        recorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() {
                if (activeSession == sessionId) Log.i(TAG, "VOICE_STEP_1 microphone started")
            }
            override fun onAudioChunk(pcmData: ByteArray) {
                if (activeSession != sessionId || pcmData.isEmpty()) return
                if (holdUntilSpeech) {
                    heldPrefix.addLast(pcmData)
                    while (heldPrefix.size > PREFIX_HOLD_CHUNKS) heldPrefix.removeFirst()
                    return
                }
                if (pendingAudio.size >= MAX_PENDING_AUDIO_CHUNKS) {
                    failSession("Voice audio buffer filled before the connection became ready. Try again.")
                    return
                }
                pendingAudio.addLast(pcmData)
                flushAudio()
            }
            override fun onSpeechStarted() {
                if (activeSession != sessionId) return
                cancelAutoStop()
                holdUntilSpeech = false
                releaseHeldPrefix()
                flushAudio()
            }
            override fun onSpeechStopped() {
                if (activeSession != sessionId || !isRecording) return
                startAutoStop()
                // Also hold during configuration: onStreamReady will upload buffered speech
                // and commit it even if silence happened before SDK startup completed.
                holdUntilSpeech = true
                heldPrefix.clear()
                flushAudio()
                if (ready) client.finalizeTurn()
            }
            override fun onRecordingStopped() {
                if (activeSession == sessionId) Log.i(TAG, "Microphone stopped")
            }
            override fun onRecordingError(error: String) {
                if (activeSession == sessionId) failSession(error)
            }
        })
        if (!recorder.startRecording()) {
            invalidateSession()
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
            releaseHeldPrefix()
            flushAudio()
            if (ready) client.finalizeTurn()
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
        cancelReconnect()
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
                reconnectAttempts = 0
                flushAudio()
                if (!isCurrent() || !ready) return
                if (stopRequested) finishConnection()
                else {
                    if (isPaused || holdUntilSpeech) client.finalizeTurn()
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
            override fun onStreamError(error: String, retryable: Boolean) {
                if (!isCurrent()) return
                ready = false
                connecting = false
                draining = false
                rotating = false
                cancelRotation()
                if (retryable && reconnectAttempts < MAX_RECONNECT_ATTEMPTS &&
                    (currentState != State.IDLE || (stopRequested && pendingAudio.isNotEmpty()))) {
                    scheduleReconnect()
                } else failSession(error)
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

    private fun releaseHeldPrefix() {
        while (heldPrefix.isNotEmpty()) pendingAudio.addLast(heldPrefix.removeFirst())
    }

    private fun flushAudio() {
        val activeSession = sessionId
        while (ready && pendingAudio.isNotEmpty()) {
            val next = pendingAudio.first()
            if (!client.sendAudioChunk(next)) return
            if (activeSession != sessionId) return
            pendingAudio.removeFirst()
        }
    }

    private fun finishConnection() {
        if (!stopRequested) return
        // The held onset prefix can contain a syllable captured just before stop,
        // even if local VAD has not yet detected another speech onset.
        releaseHeldPrefix()
        holdUntilSpeech = false
        if (ready) {
            flushAudio()
            if (!ready) return
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

    private fun scheduleReconnect() {
        cancelReconnect()
        connecting = true // includes backoff so a stop waits for queued audio
        val delayMs = 500L * (1L shl reconnectAttempts++)
        val activeSession = sessionId
        reconnect = Runnable {
            reconnect = null
            if (activeSession == sessionId) connect()
        }.also { handler.postDelayed(it, delayMs) }
        Log.w(TAG, "Retrying MAI connection in ${delayMs}ms (attempt $reconnectAttempts)")
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
        Log.i(TAG, "Draining MAI session before replacement")
        rotating = true
        ready = false
        draining = true
        // Capture continues into the bounded buffer. Open the replacement after
        // EOF so outgoing final results precede the next session's transcript.
        client.finishStreaming()
    }

    private fun failSession(error: String) {
        cancelRecording()
        Log.e(TAG, error)
        listener?.onError(error)
    }

    private fun invalidateSession() {
        val hadPending = hasPendingProcessing()
        sessionId++
        connectionId++
        cancelAutoStop()
        cancelRotation()
        cancelReconnect()
        client.cancelAll()
        ready = false
        connecting = false
        draining = false
        rotating = false
        stopRequested = false
        holdUntilSpeech = false
        reconnectAttempts = 0
        connectionReadyAt = 0
        pendingAudio.clear()
        heldPrefix.clear()
        if (hadPending) listener?.onPendingProcessingCancelled()
        notifyIdle()
    }

    private fun reloadRecorderConfig() {
        val prefs = context.prefs()
        recorder.updateSilenceConfig(
            prefs.getInt(Settings.PREF_VOICE_CHUNK_SILENCE_SECONDS, Defaults.PREF_VOICE_CHUNK_SILENCE_SECONDS)
                .coerceIn(1, 30) * 1000L,
            prefs.getInt(Settings.PREF_VOICE_SILENCE_THRESHOLD, Defaults.PREF_VOICE_SILENCE_THRESHOLD)
                .coerceIn(40, 5000).toDouble(),
        )
        autoStopSilenceMs = prefs.getInt(Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS,
            Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS).coerceIn(5, 300) * 1000L
    }

    private fun currentLanguageTag(): String? = try { Settings.getValues()?.mLocale?.toLanguageTag() }
        catch (_: Exception) { null }

    private fun updateState(value: State) {
        if (currentState == value) return
        currentState = value
        listener?.onStateChanged(value)
    }
    private fun notifyIdle() { if (!hasPendingProcessing()) listener?.onProcessingIdle() }
    private fun startAutoStop() {
        cancelAutoStop()
        handler.postDelayed(autoStop, autoStopSilenceMs)
    }
    private fun cancelAutoStop() { handler.removeCallbacks(autoStop) }
    private fun cancelRotation() {
        rotation?.let { handler.removeCallbacks(it) }
        rotation = null
    }
    private fun cancelReconnect() {
        reconnect?.let { handler.removeCallbacks(it) }
        reconnect = null
    }
}
