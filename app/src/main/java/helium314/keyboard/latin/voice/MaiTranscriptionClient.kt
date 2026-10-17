// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.settings.TranscriptionPreferences
import helium314.keyboard.latin.settings.TranscriptionPreferences.MaiConfig
import helium314.keyboard.latin.utils.Log

/** Main-looper lifecycle around Microsoft's MAI streaming Speech SDK. */
class MaiTranscriptionClient internal constructor(
    private val sessionFactory: () -> MaiSpeechSession = { AzureMaiSpeechSession() },
    private val completionTimeoutMs: Long = 60_000L,
    private val platformError: () -> String? = { deviceSupportError() },
    private val progressTimeoutMs: Long = 30_000L,
    private val recoveryTimeoutMs: Long = 20_000L,
    private val eofTimeoutMs: Long = 20_000L,
) {
    companion object {
        private const val TAG = "MaiTranscription"
        private const val START_TIMEOUT_MS = 30_000L
        private const val MAX_PENDING_COMMITS = 64
        // The service model identifier is case-sensitive; use its verified lowercase ID.
        const val MODEL = "mai-transcribe-2-streaming"
        const val SESSION_ROTATE_AFTER_MS = 55 * 60 * 1000L

        fun buildSpeechEndpoint(region: String): String {
            require(region in TranscriptionPreferences.supportedRegions) { "Unsupported Azure Speech region." }
            return "wss://$region.stt.speech.microsoft.com/speech/universal/v2"
        }

        internal fun resolveLanguage(languageTag: String?, autoDetect: Boolean): String? {
            if (autoDetect) return null
            val tag = languageTag?.replace('_', '-')?.takeIf { it.isNotBlank() } ?: return null
            if (tag.substringBefore('-') in setOf("und", "zz")) return null
            return tag // Speech SDK accepts BCP-47, including the keyboard's locale.
        }

        internal fun deviceSupportError(sdk: Int = Build.VERSION.SDK_INT,
            abi: String? = Build.SUPPORTED_ABIS.firstOrNull()): String? = when {
            sdk < 26 -> "MAI dictation requires Android 8.0 or newer."
            abi !in setOf("arm64-v8a", "armeabi-v7a", "x86_64") ->
                "MAI dictation is unavailable for this device's processor."
            else -> null
        }
    }

    interface StreamingCallback {
        fun onStreamReady()
        fun onServiceConnected() {}
        fun onStreamRecovering() {}
        fun onStartupRejected() {}
        fun onTranscriptionResult(segment: TranscriptSegment)
        fun onPendingProcessingChanged()
        fun onStreamError(error: String, incomplete: Boolean)
        fun onAudioWriteAvailable()
        /** Stop new capture while preserving accepted audio for definitive EOF. */
        fun onFinalConfirmationStalled()
        fun onStreamClosed()
        /** Keep microphone capture buffered while closing input to obtain definitive EOF. */
        fun onStreamDrainRequired()
    }

    private data class Boundary(val endBytes: Long, val requestedAt: Long, var token: Int = 0)
    private data class AudioProgress(val endBytes: Long, val acceptedAt: Long)
    private val handler = Handler(Looper.getMainLooper())
    private var connectionToken = 0L
    private var session: MaiSpeechSession? = null
    private var callback: StreamingCallback? = null
    private var ready = false
    private var finishing = false
    private var totalBytes = 0L
    private var finalizedBytes = 0L
    private var committedBytes = 0L
    private val boundaries = ArrayDeque<Boundary>()
    private val completedIds = HashSet<String>()
    private val completedTokens = HashSet<Int>()
    private var timeout: Runnable? = null
    private val unfinalizedAudio = ArrayDeque<AudioProgress>()
    private var progressTimeout: Runnable? = null
    private var lastResultEndBytes = 0L
    private var awaitingIntegrityDrain = false
    private var lastResponseAt: Long? = null
    private var finalBoundaryAt: Long? = null
    private var pendingWords = false
    private var localSpeechActive = false
    private var speechStartedAt: Long? = null
    private var activeConfig: MaiConfig? = null
    private var activeLanguage: String? = null
    private val replayAudio = PendingAudioReplay()
    private val replayQueue = ArrayDeque<ByteArray>()
    private var audioBase = 0L
    private var attemptSubmittedBytes = 0L
    private var replaySafe = true
    private var retries = 0
    private var recovering = false
    private var recoveryStartedAt: Long? = null
    private var recoveryTimeout: Runnable? = null
    private var startupTimeout: Runnable? = null
    private var eofDeadline: Long? = null
    private var sdkReady = false
    private var serviceConnected = false
    private var serviceResponded = false
    private var transportPaused = false

    val hasPendingProcessing: Boolean get() = finishing || unfinalizedAudio.isNotEmpty() ||
        pendingWords || boundaries.isNotEmpty() || recovering
    val hasUnconfirmedSpeech: Boolean get() = unfinalizedAudio.isNotEmpty() || pendingWords
    internal fun configurationError(config: MaiConfig): String? = platformError() ?: config.validationError()

    fun startStreaming(config: MaiConfig, languageTag: String?, callback: StreamingCallback) {
        cancelAll()
        this.callback = callback
        configurationError(config)?.let { fail(it); return }
        activeConfig = config
        activeLanguage = resolveLanguage(languageTag, config.autoDetectLanguage)
        openSession()
        Log.i(TAG, "VOICE_STEP_3 starting direct Azure Speech dictation")
    }

    private fun openSession() {
        transportPaused = false
        attemptSubmittedBytes = 0
        val active = sessionFactory()
        session = active
        val token = connectionToken
        startupTimeout?.let { handler.removeCallbacks(it) }
        startupTimeout = Runnable { if (token == connectionToken)
            recoverOrFail("Azure Speech service startup timed out.")
        }.also { handler.postDelayed(it, START_TIMEOUT_MS) }
        active.start(activeConfig!!, activeLanguage, object : MaiSpeechSession.Listener {
            override fun onReady() = post(token) {
                sdkReady = true
                Log.i(TAG, "VOICE_RESPONSE Azure Speech local recognizer started attempt=$retries")
                flushReplay()
            }
            override fun onConnected() = post(token) {
                serviceConnected = true
                transportPaused = false
                startupTimeout?.let { handler.removeCallbacks(it) }; startupTimeout = null
                if (replayQueue.isEmpty()) clearRecoveryTimeout()
                Log.i(TAG, "VOICE_RESPONSE Azure Speech transport connected attempt=$retries")
                this@MaiTranscriptionClient.callback?.onServiceConnected()
                // Local startup can finish after Connected; it owns write readiness.
                if (recovering) recovering = false
                flushReplay()
            }
            override fun onDisconnected() = post(token) {
                serviceConnected = false
                Log.w(TAG, "VOICE_RESPONSE Azure Speech transport disconnected finishing=$finishing " +
                    "acceptedBytes=$totalBytes finalizedBytes=$finalizedBytes attempt=$retries")
                if (!finishing) beginRecovery()
                // EOF can disconnect normally; keep its original absolute deadline.
            }
            override fun onFinal(resultId: String, text: String, audioEndBytes: Long, commitToken: Int) = post(token) {
                if (resultId.isNotEmpty() && !completedIds.add(resultId)) return@post
                val absoluteEnd = audioBase + audioEndBytes - audioEndBytes % 2
                if (audioEndBytes < 0 || audioEndBytes > attemptSubmittedBytes || absoluteEnd > totalBytes ||
                    (audioEndBytes > 0 && absoluteEnd < lastResultEndBytes)) {
                    fail("Azure Speech returned inconsistent audio timing. Dictation stopped.")
                    return@post
                }
                lastResultEndBytes = maxOf(lastResultEndBytes, absoluteEnd)
                serviceResponded = true
                lastResponseAt = SystemClock.elapsedRealtime()
                if (commitToken > 0) completedTokens.add(commitToken)
                finalizedBytes = maxOf(finalizedBytes, absoluteEnd)
                settleBoundaries(finalReceived = true)
                replayAudio.confirm(finalizedBytes)
                if (text.isNotBlank() && audioEndBytes == 0L && finalizedBytes <= audioBase) replaySafe = false
                // Recognition proves remote service progress even if Connected was delayed.
                startupTimeout?.let { handler.removeCallbacks(it) }; startupTimeout = null
                this@MaiTranscriptionClient.callback?.onServiceConnected()
                if (text.isNotBlank()) {
                    val finalText = text.trim()
                    Log.i(TAG, "VOICE_STEP_4 MAI completed transcript (${finalText.length} chars) " +
                        "periods=${finalText.count { it == '.' }} " +
                        "lowercaseContinuations=${TranscriptPostProcessor.countMidSentencePeriods(finalText)} " +
                        "endBytes=$absoluteEnd acceptedBytes=$totalBytes attempt=$retries")
                    this@MaiTranscriptionClient.callback?.onTranscriptionResult(TranscriptSegment(finalText,
                        finalText.first() in ",.!?;:%)]}、。，！？：；…"))
                    if (token != connectionToken) return@post
                }
                armCompletionTimeout()
                this@MaiTranscriptionClient.callback?.onPendingProcessingChanged()
            }
            override fun onInterim() = post(token) {
                serviceResponded = true
                lastResponseAt = SystemClock.elapsedRealtime()
                pendingWords = true
                // Interims prove liveness, never final coverage. They cannot move
                // the missing-final deadline anchored at a submitted boundary.
                armProgressTimeout()
            }
            override fun onCommitRequested(token: Int, audioEndBytes: Long) = post(tokenOfConnection) {
                boundaries.firstOrNull { it.endBytes == audioBase + audioEndBytes }?.token = token
                settleBoundaries()
                armCompletionTimeout()
                this@MaiTranscriptionClient.callback?.onPendingProcessingChanged()
            }
            private val tokenOfConnection = token
            override fun onEnded() = post(token) {
                if (!finishing) { recoverOrFail("Azure Speech ended the dictation session unexpectedly."); return@post }
                // EOF is the SDK's definitive drain signal, even for advisory commits
                // that never echo a token or speech that produces NoMatch.
                val listener = this@MaiTranscriptionClient.callback
                cancelAll()
                listener?.onStreamClosed()
            }
            override fun onError(message: String, retryable: Boolean, startupRejection: Boolean) = post(token) {
                Log.w(TAG, "VOICE_RESPONSE SDK cancellation retryable=$retryable attempt=$retries cause=$message")
                if (startupRejection && !serviceResponded) this@MaiTranscriptionClient.callback?.onStartupRejected()
                if (token != connectionToken) return@post
                if (retryable) recoverOrFail(message) else fail(message)
            }
            override fun onWriteAvailable() = post(token) {
                flushReplay()
                if (ready) this@MaiTranscriptionClient.callback?.onAudioWriteAvailable()
            }
        })
    }

    fun sendAudioChunk(pcmData: ByteArray, containsSpeech: Boolean = true): Boolean {
        if (!ready || finishing) return false
        if (pcmData.isEmpty() || pcmData.size % 2 != 0 || pcmData.size > 3200) {
            fail("Microphone audio must contain at most 100 ms of complete PCM16 samples.")
            return false
        }
        when (session?.write(pcmData)) {
            MaiSpeechSession.WriteResult.ACCEPTED -> Unit
            MaiSpeechSession.WriteResult.BACKPRESSURE -> return false // Caller retains the FIFO head.
            else -> { fail("Azure Speech could not accept microphone audio. Dictation stopped."); return false }
        }
        val wasPending = hasPendingProcessing
        attemptSubmittedBytes += pcmData.size
        replayAudio.add(totalBytes, pcmData)
        totalBytes += pcmData.size
        if (containsSpeech) unfinalizedAudio.addLast(AudioProgress(totalBytes, SystemClock.elapsedRealtime()))
        armProgressTimeout()
        if (!wasPending) callback?.onPendingProcessingChanged()
        return true
    }

    /** New capture must clear an older boundary even while its PCM is backpressured. */
    fun onSpeechStarted() {
        localSpeechActive = true
        speechStartedAt = SystemClock.elapsedRealtime()
        finalBoundaryAt = null
        armProgressTimeout()
    }

    fun onSpeechStopped() {
        localSpeechActive = false
        speechStartedAt = null
        armProgressTimeout()
    }

    fun finalizeTurn(currentSpeechBoundary: Boolean = true): Boolean {
        if (!ready || finishing || totalBytes <= committedBytes || totalBytes <= finalizedBytes) return false
        if (boundaries.size >= MAX_PENDING_COMMITS) {
            requestDrain()
            return false
        }
        committedBytes = totalBytes
        boundaries.addLast(Boundary(totalBytes, SystemClock.elapsedRealtime()))
        session?.commit(totalBytes - audioBase)
        if (currentSpeechBoundary && finalBoundaryAt == null) {
            localSpeechActive = false
            finalBoundaryAt = SystemClock.elapsedRealtime()
            armProgressTimeout()
        }
        armCompletionTimeout()
        callback?.onPendingProcessingChanged()
        return true
    }

    /** Close input, then keep receiving final results until the SDK signals EOF. */
    fun finishStreaming() {
        if (!ready || finishing) return
        finishing = true
        ready = false
        clearProgressTimeout()
        session?.finishInput()
        // Never call stopContinuousRecognition here: it may truncate buffered audio.
        eofDeadline = eofDeadline ?: (SystemClock.elapsedRealtime() + minOf(eofTimeoutMs, completionTimeoutMs))
        Log.i(TAG, "VOICE_STOP closing input acceptedBytes=$totalBytes finalizedBytes=$finalizedBytes")
        armTimeout((eofDeadline!! - SystemClock.elapsedRealtime()).coerceAtLeast(0),
            "Azure Speech timed out draining the final audio.")
        callback?.onPendingProcessingChanged()
    }

    fun cancelAll() {
        connectionToken++
        clearTimeout()
        clearProgressTimeout()
        clearRecoveryTimeout()
        startupTimeout?.let { handler.removeCallbacks(it) }; startupTimeout = null
        session?.close()
        session = null
        callback = null
        ready = false
        finishing = false
        totalBytes = 0
        finalizedBytes = 0
        committedBytes = 0
        boundaries.clear()
        completedIds.clear()
        completedTokens.clear()
        unfinalizedAudio.clear()
        lastResultEndBytes = 0
        awaitingIntegrityDrain = false
        lastResponseAt = null
        finalBoundaryAt = null
        pendingWords = false
        localSpeechActive = false
        speechStartedAt = null
        activeConfig = null
        replayAudio.clear()
        replayQueue.clear()
        audioBase = 0
        attemptSubmittedBytes = 0
        replaySafe = true
        retries = 0
        recovering = false
        recoveryStartedAt = null
        eofDeadline = null
        sdkReady = false
        serviceConnected = false
        serviceResponded = false
        transportPaused = false
    }

    private fun settleBoundaries(finalReceived: Boolean = false) {
        var settledByFinalToken = false
        // Tokens correlate explicit commits. Ordinary finals use their audio offsets.
        for (boundary in boundaries) {
            if (boundary.token > 0 && boundary.token in completedTokens) {
                finalizedBytes = maxOf(finalizedBytes, boundary.endBytes)
                settledByFinalToken = true
            }
        }
        while (boundaries.firstOrNull()?.endBytes?.let { it <= finalizedBytes } == true) boundaries.removeFirst()
        settleAudioProgress(finalReceived || settledByFinalToken)
        replayAudio.confirm(finalizedBytes)
    }

    /** Android route observations stop capture; recognition/EOF still decide the outcome. */
    fun onNetworkUnavailable() { if (session != null) beginRecovery() }

    private fun beginRecovery() {
        val token = connectionToken
        if (!recovering) {
            recovering = true
            ready = false
            transportPaused = true
            recoveryStartedAt = recoveryStartedAt ?: SystemClock.elapsedRealtime()
            callback?.onStreamRecovering()
        }
        if (token != connectionToken) return
        if (recoveryTimeout != null) return
        // Give the existing SDK connection time to recover or report Canceled.
        recoveryTimeout = Runnable {
            recoveryTimeout = null
            if (token == connectionToken) recoverOrFail("Azure Speech could not restore its connection.")
        }.also { handler.postDelayed(it, 3_000L) }
    }

    private fun recoverOrFail(message: String) {
        val now = SystemClock.elapsedRealtime()
        val started = recoveryStartedAt ?: now
        recoveryStartedAt = started
        val suffix = if (replaySafe) replayAudio.suffix(finalizedBytes, totalBytes) else null
        if (retries >= 2 || now - started >= recoveryTimeoutMs ||
            eofDeadline?.let { now >= it } == true || suffix == null) {
            fail(when {
                !replaySafe -> "$message A final transcript lacked timing needed for safe audio recovery."
                suffix == null -> "$message Unfinished audio exceeds the safe recovery buffer."
                else -> message
            })
            return
        }
        val previousToken = connectionToken
        beginRecovery()
        if (previousToken != connectionToken) return
        clearRecoveryTimeout()
        clearTimeout()
        clearProgressTimeout()
        startupTimeout?.let { handler.removeCallbacks(it) }; startupTimeout = null
        val old = session
        connectionToken++ // Late events from the failed recognizer cannot write text.
        old?.close()
        session = null
        ready = false
        sdkReady = false
        serviceConnected = false
        finishing = false
        audioBase = finalizedBytes
        committedBytes = finalizedBytes
        boundaries.clear()
        completedTokens.clear()
        completedIds.clear()
        replayQueue.clear()
        replayQueue.addAll(suffix)
        retries++
        Log.w(TAG, "VOICE_RECOVERY replacement attempt=$retries replayBytes=${totalBytes - audioBase} " +
            "confirmedBytes=$audioBase cause=$message")
        val token = connectionToken
        val deadline = minOf(started + recoveryTimeoutMs, eofDeadline ?: Long.MAX_VALUE)
        recoveryTimeout = Runnable { if (token == connectionToken)
            fail("Azure Speech recovery timed out. Microphone stopped.")
        }.also { handler.postDelayed(it, (deadline - now).coerceAtLeast(0)) }
        handler.postDelayed({ if (token == connectionToken) openSession() }, if (retries == 1) 500L else 1_500L)
    }

    private fun flushReplay() {
        if (!sdkReady || ready || transportPaused || finishing) return
        while (replayQueue.isNotEmpty()) {
            when (session?.write(replayQueue.first())) {
                MaiSpeechSession.WriteResult.ACCEPTED -> attemptSubmittedBytes += replayQueue.removeFirst().size
                MaiSpeechSession.WriteResult.BACKPRESSURE -> return
                else -> { recoverOrFail("Azure Speech could not accept recovery audio."); return }
            }
        }
        ready = true
        if (serviceConnected) {
            recovering = false
            clearRecoveryTimeout()
        }
        callback?.onStreamReady()
    }

    private fun clearRecoveryTimeout() {
        recoveryTimeout?.let { handler.removeCallbacks(it) }
        recoveryTimeout = null
    }

    private fun fail(message: String) {
        val pendingAudio = hasUnconfirmedSpeech
        val listener = callback
        cancelAll()
        val description = if (pendingAudio) "$message The last voice segment could not be finalized." else message
        Log.e(TAG, description)
        listener?.onStreamError(description, pendingAudio)
    }

    private fun settleAudioProgress(authoritativeFinal: Boolean) {
        while (unfinalizedAudio.firstOrNull()?.endBytes?.let { it <= finalizedBytes } == true)
            unfinalizedAudio.removeFirst()
        if (authoritativeFinal && unfinalizedAudio.isEmpty()) {
            pendingWords = false
            finalBoundaryAt = null
        }
        armProgressTimeout()
    }
    private fun armProgressTimeout() {
        clearProgressTimeout()
        if (finishing || awaitingIntegrityDrain) return
        val oldest = unfinalizedAudio.firstOrNull()
        val responseDeadline = oldest?.takeIf { localSpeechActive || pendingWords }?.let {
            maxOf(it.acceptedAt, lastResponseAt ?: it.acceptedAt,
                speechStartedAt ?: it.acceptedAt) + progressTimeoutMs
        }
        val finalDeadline = if (pendingWords) finalBoundaryAt?.plus(progressTimeoutMs) else null
        val deadline = listOfNotNull(responseDeadline, finalDeadline).minOrNull() ?: return
        val token = connectionToken
        progressTimeout = Runnable {
            if (token == connectionToken) {
                awaitingIntegrityDrain = true
                callback?.onFinalConfirmationStalled()
            }
        }.also { handler.postDelayed(it,
            (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)) }
    }
    private fun clearProgressTimeout() {
        progressTimeout?.let { handler.removeCallbacks(it) }
        progressTimeout = null
    }

    private fun armCompletionTimeout() {
        if (finishing) return // Preserve the EOF drain deadline while results arrive.
        clearTimeout()
        val oldest = boundaries.firstOrNull() ?: return
        val token = connectionToken
        timeout = Runnable { if (token == connectionToken) requestDrain() }.also {
            handler.postDelayed(it,
                (completionTimeoutMs - (SystemClock.elapsedRealtime() - oldest.requestedAt)).coerceAtLeast(0))
        }
    }
    private fun requestDrain() {
        clearTimeout()
        // Inline commits are advisory, so missing progress is not an error or proof
        // of lost speech. Close input and retain every final until definitive EOF.
        callback?.onStreamDrainRequired()
    }
    private fun armTimeout(delay: Long, message: String) {
        clearTimeout()
        val token = connectionToken
        timeout = Runnable { if (token == connectionToken) fail(message) }
            .also { handler.postDelayed(it, delay) }
    }
    private fun clearTimeout() { timeout?.let { handler.removeCallbacks(it) }; timeout = null }
    private fun post(token: Long, action: () -> Unit) { handler.post { if (token == connectionToken) action() } }
}
