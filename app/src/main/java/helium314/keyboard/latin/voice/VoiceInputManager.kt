// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.settings.TranscriptionPreferences.GeminiConfig
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
    private val transcriptionClient: GeminiTranscriptionClient,
    private val networkMonitor: VoiceNetworkMonitor,
) {
    constructor(context: Context) : this(
        context, VoiceRecorder(context), GeminiTranscriptionClient(), AndroidVoiceNetworkMonitor(context)
    )

    companion object {
        private const val TAG = "VoiceInputManager"
        private const val MIN_CHUNK_SILENCE_SECONDS = 1
        private const val MAX_CHUNK_SILENCE_SECONDS = 30
        private const val MIN_AUTO_STOP_SILENCE_SECONDS = 5
        private const val MAX_AUTO_STOP_SILENCE_SECONDS = 300
        private const val MIN_SILENCE_THRESHOLD = 40
        private const val MAX_SILENCE_THRESHOLD = 5000
        internal const val MAX_PENDING_AUDIO_BYTES = 960_000 // 30 s of PCM16 at 16 kHz
        internal const val MAX_PENDING_TRANSCRIPTS = 64
        internal const val MAX_PENDING_CONTROLS = 64
        internal const val STREAM_CONNECT_TIMEOUT_MS = 12_000L
        internal const val PROGRESS_TIMEOUT_MS = 30_000L
        internal const val DRAIN_TIMEOUT_MS = 15_000L
        private const val AUDIO_RETRY_MS = 25L
        internal const val INTERRUPTION_MARKER = "[Dictation interrupted]"
    }

    enum class State { IDLE, RECORDING, PAUSED }
    private enum class StopReason { USER, SILENCE_TIMEOUT, SESSION_LIMIT, SERVER_END }

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
        data class End(val finish: Boolean, val speechEpoch: Long) : Outbound
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
    private var speechEpoch = 0L
    private var stopReason: StopReason? = null
    private var autoStopAtMs: Long? = null
    private var hasFinalizedCurrentSilence = false
    private var isDispatchingTranscripts = false
    private var pendingAudioBytes = 0
    private val outbound = ArrayDeque<Outbound>()
    private val pendingTranscripts = ArrayDeque<TranscriptSegment>()
    private var audioRetry: Runnable? = null
    private var connectTimeout: Runnable? = null
    private var responseTimeout: Runnable? = null
    private var finalTimeout: Runnable? = null
    private var sessionLimit: Runnable? = null
    private var drainTimeout: Runnable? = null
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
    private var finalizedAtMs: Long? = null

    private var chunkSilenceDurationMs = Defaults.PREF_VOICE_CHUNK_SILENCE_SECONDS * 1000L
    private var chunkSilenceThreshold = Defaults.PREF_VOICE_SILENCE_THRESHOLD.toDouble()
    private var autoStopSilenceMs = Defaults.PREF_VOICE_AUTO_STOP_SILENCE_SECONDS * 1000L
    private var geminiConfig: GeminiConfig = TranscriptionPreferences.readGeminiConfig(context.prefs())
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
        if (!isIdle) return false
        if (!voiceRecorder.hasRecordPermission()) {
            Log.w(TAG, "start rejected: microphone permission required")
            listener?.onPermissionRequired()
            return false
        }
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            Log.w(TAG, "start rejected: Gemini API key is not configured")
            listener?.onError("Gemini API key not configured. Please set it in Settings.")
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
        finalizedAtMs = null
        Log.i(TAG, "session=$sessionId start mode=${geminiConfig.transcriptionMode} " +
            "localSilenceMs=$chunkSilenceDurationMs threshold=$chunkSilenceThreshold " +
            "autoStopMs=$autoStopSilenceMs serverSilenceMs=${geminiConfig.endOfSpeechSilenceMs} " +
            "autoLanguage=${geminiConfig.autoDetectLanguage} editorVocabulary=${geminiConfig.useEditorContext}")
        acceptingAudio = true
        hasCapturedAudio = false
        unconfirmedSpeech = false
        unconfirmedTranscript = false
        localSpeechActive = false
        speechEpoch = 0L
        stopReason = null
        hasFinalizedCurrentSilence = false
        voiceRecorder.setCallback(object : VoiceRecorder.RecordingCallback {
            override fun onRecordingStarted() { }
            override fun onAudioChunk(pcmData: ByteArray, containsSpeech: Boolean) {
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
                if (pcmData.size > MAX_PENDING_AUDIO_BYTES - pendingAudioBytes) {
                    failSession("Voice audio queue is full. Dictation stopped before skipping audio.")
                    return
                }
                outbound.addLast(Outbound.Audio(pcmData, SystemClock.elapsedRealtime(), containsSpeech))
                pendingAudioBytes += pcmData.size
                flushOutbound(sessionId)
            }
            override fun onSpeechStarted() {
                // Tail speech evidence lives in the annotated PCM. A boundary
                // posted before a pause/stop must not restart capture watchdogs.
                if (!isCurrent(sessionId) || !acceptingAudio || !isRecording || isSessionStopping) return
                localSpeechActive = true
                speechEpoch++
                cancelAutoStopTimer()
                hasFinalizedCurrentSilence = false
                // A new utterance may follow a long thinking pause. Finals are
                // emitted on speech completion, not on a fixed cadence while
                // speaking. Keep the liveness watchdog, but stop waiting for an
                // end-of-speech final until this utterance is finalized too.
                clearFinalTimeout()
                finalizedAtMs = null
                armResponseTimeout(sessionId)
                // Pending speech is recorded by the preceding annotated PCM
                // callback. The boundary flag includes silence/smoothing tails.
            }
            override fun onSpeechStopped() {
                if (!isCurrent(sessionId) || currentState != State.RECORDING) return
                localSpeechActive = false
                startAutoStopTimer()
                if (!hasFinalizedCurrentSilence) {
                    hasFinalizedCurrentSilence = true
                    enqueueEnd(sessionId, finish = false)
                }
            }
            override fun onRecordingStopped() { }
            override fun onRecordingError(error: String) {
                if (isCurrent(sessionId)) failSession(error)
            }
        })
        startStreamingSession(sessionId, apiKey)
        if (!isCurrent(sessionId)) return false
        if (!voiceRecorder.startRecording()) {
            if (isCurrent(sessionId)) failSession("Failed to start recording")
            return false
        }
        // A synchronous recorder error may already have invalidated this session.
        if (!isCurrent(sessionId)) return false
        updateState(State.RECORDING)
        startAutoStopTimer()
        return true
    }

    fun stopRecording() = stopRecording(StopReason.USER)

    private fun stopRecording(reason: StopReason) {
        if (!sessionOpen || isSessionStopping) return
        val sessionId = activeSessionId
        isSessionStopping = true
        stopReason = reason
        localSpeechActive = false
        Log.i(TAG, "stop requested reason=$reason ${sessionDiagnostics()}")
        cancelAutoStopTimer()
        // The drain has its own bounded wait. A timer from an earlier speech
        // boundary must not cancel the recorder-tail barrier or a late final.
        clearFinalTimeout()
        responseTimeout?.let(mainHandler::removeCallbacks)
        responseTimeout = null
        sessionLimit?.let(mainHandler::removeCallbacks)
        sessionLimit = null
        voiceRecorder.stopRecording()
        updateState(State.IDLE)
        listener?.onProcessingStarted()
        // AudioRecord.stop/join ensures the recorder's last posted chunks precede
        // this barrier. Retain those chunks, then enqueue EOF behind all of them.
        mainHandler.post {
            if (!isCurrent(sessionId)) return@post
            acceptingAudio = false
            enqueueEnd(sessionId, finish = true)
        }
    }

    fun cancelRecording() { endSession(cancelled = true) }
    fun pauseRecording() {
        if (!isRecording || !sessionOpen) return
        val sessionId = activeSessionId
        val boundaryEpoch = speechEpoch
        Log.i(TAG, "session=$sessionId pause")
        cancelAutoStopTimer()
        localSpeechActive = false
        voiceRecorder.pauseRecording()
        updateState(State.PAUSED)
        mainHandler.post {
            if (isCurrent(sessionId) && !isSessionStopping) {
                enqueueEnd(sessionId, finish = false, boundaryEpoch = boundaryEpoch)
            }
        }
    }
    fun resumeRecording() {
        if (!isPaused || !sessionOpen || isSessionStopping) return
        val sessionId = activeSessionId
        Log.i(TAG, "session=$sessionId resume")
        voiceRecorder.resumeRecording()
        if (!isCurrent(sessionId)) return
        hasFinalizedCurrentSilence = false
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
        val editorContext = if (geminiConfig.useEditorContext) {
            try { priorTextProvider?.getPriorText() } catch (_: Exception) { null }
        } else null
        val config = GeminiTranscriptionClient.buildSessionConfig(
            languageTag = getCurrentLanguageTag(),
            autoDetectLanguage = geminiConfig.autoDetectLanguage,
            transcriptionMode = geminiConfig.transcriptionMode,
            endOfSpeechSilenceMs = geminiConfig.endOfSpeechSilenceMs,
            userVocabulary = geminiConfig.customVocabulary,
            editorContext = editorContext,
        )
        isStreamingConnecting = true
        scheduleConnectTimeout(sessionId)
        transcriptionClient.startStreaming(apiKey, config, object : GeminiTranscriptionClient.StreamingCallback {
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
                }.also { mainHandler.postDelayed(it, GeminiTranscriptionClient.SESSION_CAPTURE_LIMIT_MS) }
                flushOutbound(sessionId)
            }
            override fun onHandshakeRestarted() {
                // Only schema negotiation before setupComplete can retry. No
                // audio has crossed the connection yet. Keep the original deadline.
            }
            override fun onTranscriptionResult(segment: TranscriptSegment) {
                if (!isCurrent(sessionId) || segment.text.isBlank()) return
                if (pendingTranscripts.size >= MAX_PENDING_TRANSCRIPTS) {
                    failSession("Voice text queue is full. Dictation stopped before skipping text.")
                    return
                }
                pendingTranscripts.addLast(segment)
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
            override fun onServerResponse(hasTranscriptText: Boolean) {
                if (!isCurrent(sessionId)) return
                lastResponseAtMs = SystemClock.elapsedRealtime()
                responseTimeout?.let(mainHandler::removeCallbacks)
                responseTimeout = null
                // Repeated authoritative finals still prove transcript progress
                // even when the client has suppressed a duplicate editor write.
                if (hasTranscriptText) acknowledgeFinal(sessionId, logProgress = false)
                // Interim and turnComplete prove responsiveness, not audio coverage.
            }
            override fun onSessionExpiring(timeLeftMs: Long) {
                if (!isCurrent(sessionId) || isSessionStopping) return
                listener?.onError("Dictation session is ending. Tap the mic after processing finishes.")
                stopRecording(StopReason.SERVER_END)
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

    private fun enqueueEnd(sessionId: Long, finish: Boolean, boundaryEpoch: Long = speechEpoch) {
        if (!isCurrent(sessionId)) return
        if (outbound.count { it is Outbound.End } >= MAX_PENDING_CONTROLS) {
            failSession("Voice control queue is full. Dictation stopped before skipping a speech boundary.")
            return
        }
        outbound.addLast(Outbound.End(finish, boundaryEpoch))
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
                        GeminiTranscriptionClient.AudioSendResult.ACCEPTED -> {
                            acceptedAudioChunks++
                            acceptedAudioBytes += next.pcm.size
                            outbound.removeFirst()
                            pendingAudioBytes -= next.pcm.size
                            if (acceptedAudioChunks == 1) Log.i(TAG, "first PCM accepted ${sessionDiagnostics()}")
                            armResponseTimeout(sessionId)
                        }
                        GeminiTranscriptionClient.AudioSendResult.BACKPRESSURE -> {
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
                        GeminiTranscriptionClient.AudioSendResult.FAILED -> {
                            failSession("Voice audio could not be sent")
                            return
                        }
                    }
                }
                is Outbound.End -> {
                    val sent = if (next.finish) transcriptionClient.finishStreaming()
                        else transcriptionClient.finalizeTurn()
                    if (!sent) {
                        failSession("Voice turn could not be finalized")
                        return
                    }
                    outbound.removeFirst()
                    // Backpressure may delay an older boundary until after a
                    // newer utterance has started. It must not impose a final
                    // deadline on speech currently being captured.
                    if (next.speechEpoch == speechEpoch && finalizedAtMs == null) {
                        finalizedAtMs = SystemClock.elapsedRealtime()
                    }
                    armResponseTimeout(sessionId)
                    armFinalTimeout(sessionId)
                    Log.i(TAG, "session=$sessionId finalize finish=${next.finish} " +
                        "pendingSpeech=$unconfirmedSpeech finalWait=${finalTimeout != null}")
                    if (next.finish) {
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
        }
        notifyProcessingIdleIfDrained()
    }

    private fun processTranscripts(sessionId: Long) {
        if (isDispatchingTranscripts) return
        isDispatchingTranscripts = true
        try {
            while (isCurrent(sessionId)) {
                val head = pendingTranscripts.firstOrNull() ?: break
                listener?.onProcessingStarted()
                val accepted = try {
                    listener?.onTranscriptionResult(head.text, head.attachesToPrevious) == true
                } catch (e: Exception) {
                    Log.e(TAG, "Editor voice insertion failed: ${e.message}")
                    false
                }
                if (!isCurrent(sessionId)) return
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
        // An interim may arrive after EOF was sent without local speech
        // evidence. It must use that boundary's deadline, not wait forever.
        armFinalTimeout(sessionId)
    }

    private fun acknowledgeFinal(sessionId: Long, logProgress: Boolean = true) {
        lastFinalAtMs = SystemClock.elapsedRealtime()
        // This final may describe an earlier utterance. Never let it acknowledge
        // a suffix that has not even left our local queues.
        val queuedPcmSpeech = outbound.any { it is Outbound.Audio && it.containsSpeech }
        val queuedSocketSpeech = transcriptionClient.hasQueuedSpeechFrames()
        if (!queuedPcmSpeech && !queuedSocketSpeech) {
            unconfirmedSpeech = false
            unconfirmedTranscript = false
            clearFinalTimeout()
            finalizedAtMs = null
            responseTimeout?.let(mainHandler::removeCallbacks)
            responseTimeout = null
        }
        if (logProgress) Log.i(TAG, "session=$sessionId final progress pendingSpeech=$unconfirmedSpeech " +
            "queuedPcmSpeech=$queuedPcmSpeech queuedSocketSpeech=$queuedSocketSpeech " +
            "capturedSpeechChunks=$capturedSpeechChunks")
        notifyProcessingIdleIfDrained()
    }

    private fun clearFinalTimeout() {
        finalTimeout?.let(mainHandler::removeCallbacks)
        finalTimeout = null
    }

    private fun armFinalTimeout(sessionId: Long) {
        // Only a submitted speech boundary starts the final wait. Interims
        // while speaking prove liveness and must not impose a maximum utterance
        // duration. Repeated controls/interims cannot extend this boundary's wait.
        val boundaryAtMs = finalizedAtMs ?: return
        // A raw RMS spike may be breathing or room noise. Only recognized words
        // justify waiting for a final transcript after a speech boundary.
        if (isSessionStopping || localSpeechActive || !unconfirmedTranscript || finalTimeout != null) return
        finalTimeout = Runnable {
            finalTimeout = null
            if (isCurrent(sessionId) && !isSessionStopping && unconfirmedTranscript) {
                if (stopForElapsedSilence()) return@Runnable
                failSession("No final transcript arrived for pending speech")
            }
        }.also {
            val remainingMs = (boundaryAtMs + PROGRESS_TIMEOUT_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            mainHandler.postDelayed(it, remainingMs)
        }
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
        // No audio crosses the socket before setupComplete. Startup rejection
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
            "lastFinalAgoMs=${lastFinalAtMs?.let { now - it } ?: "none"} " +
            "finalizedAgoMs=${finalizedAtMs?.let { now - it } ?: "none"}"
    }

    private fun endSession(cancelled: Boolean, failed: Boolean = false) {
        if (sessionOpen && !failed) {
            Log.i(TAG, "end outcome=${if (cancelled) "cancelled" else "completed"} ${sessionDiagnostics()}")
        }
        val hadPendingWork = hasPendingProcessing()
        sessionOpen = false
        acceptingAudio = false
        activeSessionId += 1
        networkMonitor.stop()
        cancelAutoStopTimer()
        listOf(audioRetry, connectTimeout, responseTimeout, finalTimeout, sessionLimit, drainTimeout)
            .forEach { it?.let(mainHandler::removeCallbacks) }
        audioRetry = null
        connectTimeout = null
        responseTimeout = null
        finalTimeout = null
        sessionLimit = null
        drainTimeout = null
        isStreamingReady = false
        isStreamingConnecting = false
        isSessionStopping = false
        waitingForClose = false
        unconfirmedSpeech = false
        unconfirmedTranscript = false
        localSpeechActive = false
        stopReason = null
        finalizedAtMs = null
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
        geminiConfig = TranscriptionPreferences.readGeminiConfig(prefs)

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
            TranscriptionPreferences.readGeminiApiKey(context.prefs())
        } catch (e: Exception) {
            Log.e(TAG, "Error getting API key: ${e.message}")
            ""
        }
    }

    /**
     * Full language tag of the active keyboard subtype (for example `en_US`), so
     * the client can pick the matching BCP-47 regional variant Gemini expects
     * rather than a bare language code.
     */
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
