// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.settings.TranscriptionPreferences.MuseConfig
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs

/**
 * One recording owns one Live connection. All capture, control, transcript and
 * local insertion results are serialized on the main looper. A failed
 * connection is never resumed: Live has no per-audio delivery acknowledgment.
 */
class VoiceInputManager internal constructor(
    private val context: Context,
    private val voiceRecorder: VoiceRecorder,
    private val transcriptionClient: MuseTranscriptionClient,
    private val networkMonitor: VoiceNetworkMonitor,
) {
    constructor(context: Context) : this(
        context, VoiceRecorder(context), MuseTranscriptionClient(), AndroidVoiceNetworkMonitor(context)
    )

    companion object {
        private const val TAG = "VoiceInputManager"
        private const val MIN_CHUNK_SILENCE_SECONDS = 1
        private const val MAX_CHUNK_SILENCE_SECONDS = 30
        private const val MIN_AUTO_STOP_SILENCE_SECONDS = 5
        private const val MAX_AUTO_STOP_SILENCE_SECONDS = 300
        private const val MIN_SILENCE_THRESHOLD = 40
        private const val MAX_SILENCE_THRESHOLD = 5000
        internal const val MAX_PENDING_AUDIO_BYTES = 1_440_000 // 30 s at preferred 24 kHz
        internal const val MAX_PENDING_TRANSCRIPTS = 64
        internal const val STREAM_CONNECT_TIMEOUT_MS = 12_000L
        internal const val PROGRESS_TIMEOUT_MS = 30_000L
        internal const val DRAIN_TIMEOUT_MS = 60_000L
        internal const val STOP_TIMEOUT_MS = 60_000L
        private const val AUDIO_RETRY_MS = 25L
        internal const val INTERRUPTION_MARKER = "[Dictation interrupted]"
    }

    enum class State { IDLE, RECORDING, PAUSED }
    private enum class StopReason { USER, SILENCE_TIMEOUT, SESSION_LIMIT }

    interface VoiceInputListener {
        fun onStateChanged(state: State)
        /** Return local dispatch/cleanup success; Android IPC is not an editor receipt. */
        fun onTranscriptionResult(text: String, attachesToPrevious: Boolean): Boolean
        /** Insert literally, without transcript cleanup or paragraph replacement. */
        fun onTranscriptionInterrupted(marker: String): Boolean
        fun onProcessingStarted()
        fun onProcessingIdle()
        fun onPendingProcessingCancelled()
        fun onError(error: String)
        fun onPermissionRequired()
    }

    fun interface PriorTextProvider { fun getPriorText(): String? }

    private sealed interface Outbound {
        data class Audio(val pcm: ByteArray, val capturedAtMs: Long, val containsSpeech: Boolean) : Outbound
        data object End : Outbound
    }

    private var listener: VoiceInputListener? = null
    private var priorTextProvider: PriorTextProvider? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentState = State.IDLE
    private var activeSessionId = 0L
    private var sessionOpen = false
    private var acceptingAudio = false
    private var isStreamingReady = false
    private var isStreamingConnecting = false
    private var isSessionStopping = false
    private var waitingForClose = false
    private var hasCapturedAudio = false
    private var unconfirmedSpeech = false
    private var unconfirmedTranscript = false
    private var localSpeechActive = false
    private var stopReason: StopReason? = null
    private var autoStopAtMs: Long? = null
    private var isDispatchingTranscripts = false
    private var pendingAudioBytes = 0
    private val outbound = ArrayDeque<Outbound>()
    private data class PendingTranscript(val segment: TranscriptSegment, val receivedAtMs: Long)
    private val pendingTranscripts = ArrayDeque<PendingTranscript>()
    private var lastAudioTimingAtMs: Long? = null
    private var maxCaptureToSendMs = 0L
    private var pausedSilence: Runnable? = null
    private var audioRetry: Runnable? = null
    private var connectTimeout: Runnable? = null
    private var responseTimeout: Runnable? = null
    private var sessionLimit: Runnable? = null
    private var drainTimeout: Runnable? = null
    private var stopTimeout: Runnable? = null
    private var sessionStartedAtMs = 0L
    private var capturedChunks = 0
    private var capturedSpeechChunks = 0
    private var capturedBytes = 0L
    private var acceptedAudioChunks = 0
    private var acceptedAudioBytes = 0L
    private var acceptedFinals = 0
    private var backpressureSeen = false
    private var interimSeen = false
    private var lastResponseAtMs: Long? = null
    private var lastInterimAtMs: Long? = null
    private var lastFinalAtMs: Long? = null

    private var chunkSilenceDurationMs = Defaults.PREF_VOICE_CHUNK_SILENCE_SECONDS * 1000L
    private var chunkSilenceThreshold = Defaults.PREF_VOICE_SILENCE_THRESHOLD.toDouble()
    private var autoStopSilenceMs = Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS * 1000L
    private var museConfig: MuseConfig = TranscriptionPreferences.readMuseConfig(context.prefs())
    private val autoStopSilenceRunnable = Runnable {
        if (currentState == State.RECORDING) stopRecording(StopReason.SILENCE_TIMEOUT)
    }

    val isRecording: Boolean get() = currentState == State.RECORDING
    val isPaused: Boolean get() = currentState == State.PAUSED
    val isIdle: Boolean get() = currentState == State.IDLE && !sessionOpen
    val state: State get() = currentState

    fun hasPendingProcessing(): Boolean = sessionOpen && (
        isSessionStopping || isStreamingConnecting || waitingForClose || unconfirmedSpeech ||
            outbound.isNotEmpty() || pendingTranscripts.isNotEmpty() || isDispatchingTranscripts
        )

    fun setListener(listener: VoiceInputListener?) { this.listener = listener }
    fun setPriorTextProvider(provider: PriorTextProvider?) { priorTextProvider = provider }
    fun toggleRecording() {
        when (currentState) {
            State.IDLE -> startRecording()
            State.RECORDING -> stopRecording()
            State.PAUSED -> resumeRecording()
        }
    }

    fun startRecording(): Boolean {
        // IDLE also means the mic has stopped while the previous connection drains.
        if (currentState != State.IDLE) return false
        if (sessionOpen) {
            listener?.onError("Finishing dictation. Please wait for processing to finish.")
            return false
        }
        if (!voiceRecorder.hasRecordPermission()) {
            Log.w(TAG, "start rejected: microphone permission required")
            listener?.onPermissionRequired()
            return false
        }
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            Log.w(TAG, "start rejected: Muse API key is not configured")
            listener?.onError("Muse API key not configured. Please set it in Settings.")
            return false
        }
        reloadRuntimeConfig()
        activeSessionId += 1
        val sessionId = activeSessionId
        if (!networkMonitor.start {
                if (isCurrent(sessionId)) failSession("Internet connection lost. Tap the mic to start again.")
            }) {
            networkMonitor.stop()
            Log.w(TAG, "start rejected: no usable internet route")
            listener?.onError("Internet access is required for dictation.")
            return false
        }
        sessionOpen = true
        sessionStartedAtMs = SystemClock.elapsedRealtime()
        capturedChunks = 0
        capturedSpeechChunks = 0
        capturedBytes = 0L
        acceptedAudioChunks = 0
        acceptedAudioBytes = 0L
        acceptedFinals = 0
        backpressureSeen = false
        interimSeen = false
        lastResponseAtMs = null
        lastInterimAtMs = null
        lastFinalAtMs = null
        lastAudioTimingAtMs = null
        maxCaptureToSendMs = 0
        Log.i(TAG, "session=$sessionId start mode=${if (museConfig.responsiveDictation) "ENDPOINTING" else "DIARIZATION"} autoStopMs=$autoStopSilenceMs")
        acceptingAudio = true
        hasCapturedAudio = false
        unconfirmedSpeech = false
        unconfirmedTranscript = false
        localSpeechActive = false
        stopReason = null
        voiceRecorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() { }
            override fun onAudioChunk(pcmData: ByteArray, containsSpeech: Boolean) {
                onCapturedAudioChunk(pcmData, containsSpeech, SystemClock.elapsedRealtime())
            }
            override fun onCapturedAudioChunk(pcmData: ByteArray, containsSpeech: Boolean, capturedAtMs: Long) {
                if (!isCurrent(sessionId) || !acceptingAudio) return
                if (pcmData.isEmpty() || pcmData.size % 2 != 0) {
                    failSession("Invalid microphone audio")
                    return
                }
                hasCapturedAudio = true
                capturedChunks++
                capturedBytes += pcmData.size
                if (containsSpeech) {
                    capturedSpeechChunks++
                    noteUnconfirmedSpeech(sessionId, "pcm")
                }
                if (pcmData.size > voiceRecorder.sampleRate.coerceAtLeast(16000) * 2 * 30 - pendingAudioBytes) {
                    failSession("Voice audio queue is full. Dictation stopped before skipping audio.")
                    return
                }
                outbound.addLast(Outbound.Audio(pcmData, capturedAtMs, containsSpeech))
                pendingAudioBytes += pcmData.size
                flushOutbound(sessionId)
            }
            override fun onSpeechStarted() {
                // Tail speech evidence lives in the annotated PCM. A boundary
                // posted before a pause/stop must not restart capture watchdogs.
                if (!isCurrent(sessionId) || !acceptingAudio || !isRecording || isSessionStopping) return
                localSpeechActive = true
                cancelAutoStopTimer()
                // Muse owns turn boundaries and final deadlines. Local onset
                // only affects the activity watchdog and silence auto-stop.
                armResponseTimeout(sessionId)
                // Pending speech is recorded by the preceding annotated PCM
                // callback. The boundary flag includes silence/smoothing tails.
            }
            override fun onSpeechStopped() {
                if (!isCurrent(sessionId) || currentState != State.RECORDING) return
                localSpeechActive = false
                startAutoStopTimer()
            }
            override fun onRecordingStopped() { }
            override fun onRecordingError(error: String) {
                if (isCurrent(sessionId)) failSession(error)
            }
        })
        if (!voiceRecorder.startRecording()) {
            if (isCurrent(sessionId)) failSession("Failed to start recording")
            return false
        }
        // A synchronous recorder error may already have invalidated this session.
        if (!isCurrent(sessionId)) return false
        startStreamingSession(sessionId, apiKey)
        if (!isCurrent(sessionId)) return false
        updateState(State.RECORDING)
        startAutoStopTimer()
        return true
    }

    fun stopRecording() = stopRecording(StopReason.USER)

    private fun stopRecording(reason: StopReason) {
        if (!sessionOpen || isSessionStopping) return
        val sessionId = activeSessionId
        val requestedAtMs = SystemClock.elapsedRealtime()
        isSessionStopping = true
        stopPausedSilence()
        stopReason = reason
        localSpeechActive = false
        Log.i(TAG, "stop requested reason=$reason ${sessionDiagnostics()}")
        cancelAutoStopTimer()
        // The drain has its own bounded wait. A timer from an earlier speech
        // boundary must not cancel the recorder-tail barrier or a late final.
        responseTimeout?.let(mainHandler::removeCallbacks)
        responseTimeout = null
        sessionLimit?.let(mainHandler::removeCallbacks)
        sessionLimit = null
        voiceRecorder.stopRecording()
        // Capture shutdown or a listener can cancel the session reentrantly.
        // Do not restore processing UI or schedule work for that old session.
        if (!isCurrent(sessionId)) return
        stopTimeout = Runnable {
            stopTimeout = null
            if (isCurrent(sessionId)) failSession("Dictation took too long to finish. Please try again.")
        }.also {
            // Include capture shutdown, setup, queued upload and EOF/close in
            // one budget. Readiness, backpressure retries and finals cannot reset it.
            val remainingMs = (requestedAtMs + STOP_TIMEOUT_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            mainHandler.postDelayed(it, remainingMs)
        }
        updateState(State.IDLE)
        if (!isCurrent(sessionId)) return
        listener?.onProcessingStarted()
        // AudioRecord.stop/join ensures the recorder's last posted chunks precede
        // this barrier. Retain those chunks, then enqueue EOF behind all of them.
        mainHandler.post {
            if (!isCurrent(sessionId)) return@post
            acceptingAudio = false
            enqueueEnd(sessionId)
        }
    }

    fun cancelRecording() { endSession(cancelled = true) }
    fun pauseRecording() {
        if (!isRecording || !sessionOpen) return
        val sessionId = activeSessionId
        Log.i(TAG, "session=$sessionId pause")
        cancelAutoStopTimer()
        localSpeechActive = false
        voiceRecorder.pauseRecording()
        updateState(State.PAUSED)
        // A barrier keeps already-posted microphone tail ahead of synthetic silence.
        mainHandler.post {
            if (isCurrent(sessionId) && isPaused && !isSessionStopping) startPausedSilence(sessionId)
        }
    }
    private fun startPausedSilence(sessionId: Long) {
        stopPausedSilence()
        val startedAt = SystemClock.elapsedRealtime()
        var frames = 0L
        pausedSilence = object : Runnable {
            override fun run() {
                if (!isCurrent(sessionId) || !isPaused || isSessionStopping) return
                val pcm = ByteArray(voiceRecorder.sampleRate.coerceAtLeast(16000) * 2 / 10)
                if (pcm.size > voiceRecorder.sampleRate.coerceAtLeast(16000) * 2 * 30 - pendingAudioBytes) {
                    failSession("Paused voice audio queue is full")
                    return
                }
                outbound.addLast(Outbound.Audio(pcm, SystemClock.elapsedRealtime(), false))
                pendingAudioBytes += pcm.size
                flushOutbound(sessionId)
                if (!isCurrent(sessionId) || !isPaused || isSessionStopping) return
                frames++
                mainHandler.postDelayed(this, (startedAt + frames * 100 - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            }
        }.also { mainHandler.post(it) }
    }
    private fun stopPausedSilence() {
        pausedSilence?.let(mainHandler::removeCallbacks)
        pausedSilence = null
    }

    fun resumeRecording() {
        if (!isPaused || !sessionOpen || isSessionStopping) return
        val sessionId = activeSessionId
        Log.i(TAG, "session=$sessionId resume")
        stopPausedSilence()
        voiceRecorder.resumeRecording()
        if (!isCurrent(sessionId)) return
        updateState(State.RECORDING)
        startAutoStopTimer()
    }
    fun togglePause() {
        when (currentState) {
            State.RECORDING -> pauseRecording()
            State.PAUSED -> resumeRecording()
            else -> { }
        }
    }
    fun destroy() { cancelRecording(); listener = null }

    private fun isCurrent(sessionId: Long) = sessionOpen && sessionId == activeSessionId

    private fun startStreamingSession(sessionId: Long, apiKey: String) {
        val editorContext = if (museConfig.useEditorContext) {
            try { priorTextProvider?.getPriorText() } catch (_: Exception) { null }
        } else null
        val config = MuseTranscriptionClient.buildSessionConfig(
            languageTag = getCurrentLanguageTag(),
            autoDetectLanguage = museConfig.autoDetectLanguage,
            userVocabulary = museConfig.customVocabulary,
            editorContext = editorContext,
            sampleRate = voiceRecorder.sampleRate.coerceAtLeast(16000),
            responsiveDictation = museConfig.responsiveDictation,
            timingDiagnostics = museConfig.timingDiagnostics,
        )
        isStreamingConnecting = true
        scheduleConnectTimeout(sessionId)
        transcriptionClient.startStreaming(apiKey, config, object : MuseTranscriptionClient.StreamingCallback {
            override fun onStreamReady() {
                if (!isCurrent(sessionId)) return
                connectTimeout?.let(mainHandler::removeCallbacks)
                connectTimeout = null
                isStreamingConnecting = false
                isStreamingReady = true
                Log.i(TAG, "ready ${sessionDiagnostics()}")
                // Stop capture at the session limit; never replace a socket that
                // could still be carrying an unconfirmed prefix.
                sessionLimit = Runnable {
                    if (isCurrent(sessionId) && !isSessionStopping) {
                        listener?.onError("Dictation session limit reached. Tap the mic after processing finishes.")
                        stopRecording(StopReason.SESSION_LIMIT)
                    }
                }.also { mainHandler.postDelayed(it, MuseTranscriptionClient.SESSION_CAPTURE_LIMIT_MS) }
                flushOutbound(sessionId)
            }
            override fun onTranscriptionResult(segment: TranscriptSegment) {
                if (!isCurrent(sessionId) || segment.text.isBlank()) return
                if (pendingTranscripts.size >= MAX_PENDING_TRANSCRIPTS) {
                    failSession("Voice text queue is full. Dictation stopped before skipping text.")
                    return
                }
                pendingTranscripts.addLast(PendingTranscript(segment, SystemClock.elapsedRealtime()))
                processTranscripts(sessionId)
                if (!isCurrent(sessionId)) return
                acknowledgeFinal(sessionId)
            }
            override fun onInterimTranscription() {
                if (!isCurrent(sessionId)) return
                lastInterimAtMs = SystemClock.elapsedRealtime()
                unconfirmedTranscript = true
                if (!interimSeen) {
                    interimSeen = true
                    Log.i(TAG, "session=$sessionId first interim elapsedMs=${SystemClock.elapsedRealtime() - sessionStartedAtMs}")
                }
                noteUnconfirmedSpeech(sessionId, "interim")
            }
            override fun onServerResponse(hasSettledSpeech: Boolean) {
                if (!isCurrent(sessionId)) return
                lastResponseAtMs = SystemClock.elapsedRealtime()
                responseTimeout?.let(mainHandler::removeCallbacks)
                responseTimeout = null
                // Completed turns and verified closure settle speech even when
                // no editor write is needed (filtered guests or wordless noise).
                if (hasSettledSpeech) acknowledgeFinal(sessionId, logProgress = false)
                // Audio progress proves processing, not completed words.
            }
            override fun onStreamError(error: String) {
                if (isCurrent(sessionId)) failSession(error)
            }
            override fun onStreamClosed() {
                if (!isCurrent(sessionId)) return
                if (!waitingForClose || outbound.isNotEmpty() ||
                    (unconfirmedSpeech && stopReason != StopReason.SILENCE_TIMEOUT)) {
                    failSession("Dictation ended before all pending speech was confirmed.")
                } else {
                    if (unconfirmedSpeech) Log.i(TAG, "Silence timeout completed without another final ${sessionDiagnostics()}")
                    endSession(cancelled = false)
                }
            }
        })
    }

    private fun enqueueEnd(sessionId: Long) {
        if (!isCurrent(sessionId)) return
        outbound.addLast(Outbound.End)
        flushOutbound(sessionId)
    }

    private fun flushOutbound(sessionId: Long) {
        if (!isCurrent(sessionId) || !isStreamingReady || waitingForClose) return
        audioRetry?.let(mainHandler::removeCallbacks)
        audioRetry = null
        while (isCurrent(sessionId)) {
            val next = outbound.firstOrNull() ?: break
            when (next) {
                is Outbound.Audio -> {
                    if (SystemClock.elapsedRealtime() - next.capturedAtMs >= PROGRESS_TIMEOUT_MS) {
                        failSession("Voice audio upload stalled")
                        return
                    }
                    when (transcriptionClient.offerAudioChunk(next.pcm, next.containsSpeech)) {
                        MuseTranscriptionClient.AudioSendResult.ACCEPTED -> {
                            acceptedAudioChunks++
                            acceptedAudioBytes += next.pcm.size
                            outbound.removeFirst()
                            pendingAudioBytes -= next.pcm.size
                            if (museConfig.timingDiagnostics) {
                                val now = SystemClock.elapsedRealtime()
                                maxCaptureToSendMs = maxOf(maxCaptureToSendMs, now - next.capturedAtMs)
                                if (lastAudioTimingAtMs == null || now - lastAudioTimingAtMs!! >= 1000) {
                                    lastAudioTimingAtMs = now
                                    Log.i(TAG, "timing phase=audio session=$sessionId elapsedMs=${now - sessionStartedAtMs} " +
                                        "maxCaptureToSendMs=$maxCaptureToSendMs pendingPcmBytes=$pendingAudioBytes " +
                                        "socketBytes=${transcriptionClient.queuedFrameBytes()}")
                                }
                            }
                            if (acceptedAudioChunks == 1) Log.i(TAG, "first PCM accepted ${sessionDiagnostics()}")
                            armResponseTimeout(sessionId)
                        }
                        MuseTranscriptionClient.AudioSendResult.BACKPRESSURE -> {
                            if (!backpressureSeen) {
                                backpressureSeen = true
                                Log.i(TAG, "backpressure; retaining audio FIFO head ${sessionDiagnostics()}")
                            }
                            // Keep the exact head until the bounded socket queue has
                            // capacity. Controls and later audio cannot overtake it.
                            audioRetry = Runnable { flushOutbound(sessionId) }.also {
                                mainHandler.postDelayed(it, AUDIO_RETRY_MS)
                            }
                            return
                        }
                        MuseTranscriptionClient.AudioSendResult.FAILED -> {
                            failSession("Voice audio could not be sent")
                            return
                        }
                    }
                }
                is Outbound.End -> {
                    val sent = transcriptionClient.finishStreaming()
                    if (!sent) {
                        failSession("Voice turn could not be finalized")
                        return
                    }
                    outbound.removeFirst()
                    Log.i(TAG, "session=$sessionId EOF queued pendingSpeech=$unconfirmedSpeech")
                    waitingForClose = true
                    drainTimeout = Runnable {
                        drainTimeout = null
                        if (isCurrent(sessionId)) failSession("Transcription did not finish closing")
                    }.also { mainHandler.postDelayed(it, DRAIN_TIMEOUT_MS) }
                    listener?.onProcessingStarted()
                    return
                }
            }
        }
        notifyProcessingIdleIfDrained()
    }

    private fun processTranscripts(sessionId: Long) {
        if (isDispatchingTranscripts) return
        isDispatchingTranscripts = true
        try {
            while (isCurrent(sessionId)) {
                val pending = pendingTranscripts.firstOrNull() ?: break
                val head = pending.segment
                val dispatchAtMs = SystemClock.elapsedRealtime()
                listener?.onProcessingStarted()
                val accepted = try {
                    listener?.onTranscriptionResult(head.text, head.attachesToPrevious) == true
                } catch (e: Exception) {
                    Log.e(TAG, "Editor voice insertion failed: ${e.message}")
                    false
                }
                if (!isCurrent(sessionId)) return
                if (museConfig.timingDiagnostics) Log.i(TAG,
                    "timing phase=editor session=$sessionId accepted=$accepted " +
                        "queueMs=${dispatchAtMs - pending.receivedAtMs} " +
                        "dispatchMs=${SystemClock.elapsedRealtime() - dispatchAtMs}")
                if (!accepted) {
                    // The editor may have partially applied the operation. Do not
                    // retry it, advance the FIFO, or attempt a marker in that editor.
                    failSession("The editor rejected dictation. Dictation stopped.", mark = false)
                    return
                }
                pendingTranscripts.removeFirst()
                acceptedFinals++
                Log.i(TAG, "session=$sessionId final accepted index=$acceptedFinals chars=${head.text.length} " +
                    "attaches=${head.attachesToPrevious} pendingText=${pendingTranscripts.size}")
            }
        } finally {
            isDispatchingTranscripts = false
        }
    }

    private fun noteUnconfirmedSpeech(sessionId: Long, source: String) {
        if (!unconfirmedSpeech) Log.i(TAG, "session=$sessionId speech pending source=$source " +
            "capturedSpeechChunks=$capturedSpeechChunks")
        unconfirmedSpeech = true
        armResponseTimeout(sessionId)
        listener?.onProcessingStarted()
    }

    private fun acknowledgeFinal(sessionId: Long, logProgress: Boolean = true) {
        lastFinalAtMs = SystemClock.elapsedRealtime()
        // This final may describe an earlier utterance. Never let it acknowledge
        // a suffix still queued locally, unprocessed by Muse, or in a pending turn.
        val queuedPcmSpeech = outbound.any { it is Outbound.Audio && it.containsSpeech }
        val queuedSocketSpeech = transcriptionClient.hasQueuedSpeechFrames() || transcriptionClient.hasPendingTurns()
        if (!queuedPcmSpeech && !queuedSocketSpeech) {
            unconfirmedSpeech = false
            unconfirmedTranscript = false
            responseTimeout?.let(mainHandler::removeCallbacks)
            responseTimeout = null
        }
        if (logProgress) Log.i(TAG, "session=$sessionId final progress pendingSpeech=$unconfirmedSpeech " +
            "queuedPcmSpeech=$queuedPcmSpeech queuedSocketSpeech=$queuedSocketSpeech " +
            "capturedSpeechChunks=$capturedSpeechChunks")
        notifyProcessingIdleIfDrained()
    }

    private fun armResponseTimeout(sessionId: Long) {
        if (isSessionStopping || (!unconfirmedTranscript && !localSpeechActive) || responseTimeout != null) return
        responseTimeout = Runnable {
            responseTimeout = null
            // Quiet audio with no recognized words does not require a server
            // response. Leave that case to the intentional silence auto-stop.
            if (isCurrent(sessionId) && !isSessionStopping && (unconfirmedTranscript || localSpeechActive)) {
                if (stopForElapsedSilence()) return@Runnable
                failSession("Transcription stopped responding")
            }
        }.also { mainHandler.postDelayed(it, PROGRESS_TIMEOUT_MS) }
    }

    private fun scheduleConnectTimeout(sessionId: Long) {
        connectTimeout = Runnable {
            connectTimeout = null
            if (isCurrent(sessionId) && isStreamingConnecting) failSession("Transcription connection timed out")
        }.also { mainHandler.postDelayed(it, STREAM_CONNECT_TIMEOUT_MS) }
    }

    private fun failSession(error: String, mark: Boolean = true) {
        if (!sessionOpen) return
        // No audio crosses the socket before the handshake acknowledgement. Startup rejection
        // leaves the editor untouched and reports why dictation could not start.
        val startupFailure = !isStreamingReady
        val insertMarker = mark && hasCapturedAudio && !startupFailure
        Log.e(TAG, "failure error=$error marker=$insertMarker ${sessionDiagnostics()}")
        // Invalidate before stopping the mic or calling the editor: callbacks
        // already queued by either transport or recorder become harmless.
        endSession(cancelled = true, failed = true)
        if (insertMarker) {
            try {
                if (listener?.onTranscriptionInterrupted(INTERRUPTION_MARKER) != true) {
                    Log.w(TAG, "Editor did not accept the interruption marker")
                }
            }
            catch (e: Exception) { Log.e(TAG, "Could not insert interruption marker: ${e.message}") }
        }
        listener?.onError(if (startupFailure) "Could not start dictation. $error" else error)
    }

    private fun sessionDiagnostics(): String {
        val now = SystemClock.elapsedRealtime()
        val phase = when {
            waitingForClose -> "draining"
            isSessionStopping -> "stopping"
            isStreamingReady -> "streaming"
            else -> "connecting"
        }
        return "session=$activeSessionId phase=$phase state=$currentState elapsedMs=${now - sessionStartedAtMs} " +
            "capturedChunks=$capturedChunks capturedSpeechChunks=$capturedSpeechChunks " +
            "capturedBytes=$capturedBytes acceptedAudioChunks=$acceptedAudioChunks " +
            "acceptedAudioBytes=$acceptedAudioBytes pendingPcmBytes=$pendingAudioBytes " +
            "socketQueuedBytes=${transcriptionClient.queuedFrameBytes()} finals=$acceptedFinals " +
            "pendingText=${pendingTranscripts.size} pendingSpeech=$unconfirmedSpeech " +
            "pendingWords=$unconfirmedTranscript localSpeech=$localSpeechActive stopReason=${stopReason ?: "none"} " +
            "lastResponseAgoMs=${lastResponseAtMs?.let { now - it } ?: "none"} " +
            "lastInterimAgoMs=${lastInterimAtMs?.let { now - it } ?: "none"} " +
            "lastFinalAgoMs=${lastFinalAtMs?.let { now - it } ?: "none"}"
    }

    private fun endSession(cancelled: Boolean, failed: Boolean = false) {
        if (sessionOpen && !failed) {
            Log.i(TAG, "end outcome=${if (cancelled) "cancelled" else "completed"} ${sessionDiagnostics()}")
        }
        val hadPendingWork = hasPendingProcessing()
        stopPausedSilence()
        sessionOpen = false
        acceptingAudio = false
        activeSessionId += 1
        networkMonitor.stop()
        cancelAutoStopTimer()
        listOf(audioRetry, connectTimeout, responseTimeout, sessionLimit, drainTimeout, stopTimeout)
            .forEach { it?.let(mainHandler::removeCallbacks) }
        audioRetry = null
        connectTimeout = null
        responseTimeout = null
        sessionLimit = null
        drainTimeout = null
        stopTimeout = null
        isStreamingReady = false
        isStreamingConnecting = false
        isSessionStopping = false
        waitingForClose = false
        unconfirmedSpeech = false
        unconfirmedTranscript = false
        localSpeechActive = false
        stopReason = null
        outbound.clear()
        pendingTranscripts.clear()
        pendingAudioBytes = 0
        transcriptionClient.cancelAll()
        voiceRecorder.stopRecording()
        updateState(State.IDLE)
        if (cancelled && hadPendingWork) listener?.onPendingProcessingCancelled()
        listener?.onProcessingIdle()
    }

    private fun notifyProcessingIdleIfDrained() {
        if (!hasPendingProcessing()) listener?.onProcessingIdle()
    }
    private fun reloadRuntimeConfig() {
        val prefs = context.prefs()

        val chunkSilenceSeconds = prefs.getInt(
            Settings.PREF_VOICE_CHUNK_SILENCE_SECONDS,
            Defaults.PREF_VOICE_CHUNK_SILENCE_SECONDS
        ).coerceIn(MIN_CHUNK_SILENCE_SECONDS, MAX_CHUNK_SILENCE_SECONDS)

        val autoStopSilenceSeconds = prefs.getInt(
            Settings.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS,
            Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS
        ).coerceIn(
            MIN_AUTO_STOP_SILENCE_SECONDS,
            MAX_AUTO_STOP_SILENCE_SECONDS
        )

        val silenceThreshold = prefs.getInt(
            Settings.PREF_VOICE_SILENCE_THRESHOLD,
            Defaults.PREF_VOICE_SILENCE_THRESHOLD
        ).coerceIn(MIN_SILENCE_THRESHOLD, MAX_SILENCE_THRESHOLD)

        chunkSilenceDurationMs = chunkSilenceSeconds * 1000L
        autoStopSilenceMs = autoStopSilenceSeconds * 1000L
        chunkSilenceThreshold = silenceThreshold.toDouble()
        museConfig = TranscriptionPreferences.readMuseConfig(prefs)

        voiceRecorder.updateSilenceConfig(
            silenceDurationMs = chunkSilenceDurationMs,
            silenceThreshold = chunkSilenceThreshold
        )

    }

    private fun updateState(newState: State) {
        if (currentState != newState) {
            currentState = newState
            listener?.onStateChanged(newState)
        }
    }

    // ── Timers ─────────────────────────────────────────────────────────

    private fun startAutoStopTimer() {
        cancelAutoStopTimer()
        if (currentState == State.RECORDING) {
            autoStopAtMs = SystemClock.elapsedRealtime() + autoStopSilenceMs
            mainHandler.postDelayed(autoStopSilenceRunnable, autoStopSilenceMs)
        }
    }

    private fun cancelAutoStopTimer() {
        mainHandler.removeCallbacks(autoStopSilenceRunnable)
        autoStopAtMs = null
    }

    private fun stopForElapsedSilence(): Boolean {
        val deadline = autoStopAtMs ?: return false
        if (currentState != State.RECORDING || SystemClock.elapsedRealtime() < deadline) return false
        // Equal-deadline callbacks can be dequeued in either order. The normal
        // silence stop takes precedence over a speech watchdog at that instant.
        stopRecording(StopReason.SILENCE_TIMEOUT)
        return true
    }

    // ── Settings ───────────────────────────────────────────────────────

    private fun getApiKey(): String {
        return try {
            TranscriptionPreferences.readMuseApiKey(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting API key: ${e.message}")
            ""
        }
    }

    /** Active keyboard locale, mapped to a supported Muse language name. */
    private fun getCurrentLanguageTag(): String? {
        return try {
            val locale = Settings.getValues()?.mLocale ?: return null
            if (locale.language.isBlank() || locale.language == "und") return null
            locale.toLanguageTag().takeIf { it.isNotBlank() && it != "und" }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting language: ${e.message}")
            null
        }
    }
}
