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

    val hasPendingProcessing: Boolean get() = finishing || totalBytes > finalizedBytes || boundaries.isNotEmpty()
    internal fun configurationError(config: MaiConfig): String? = platformError() ?: config.validationError()

    fun startStreaming(config: MaiConfig, languageTag: String?, callback: StreamingCallback) {
        cancelAll()
        this.callback = callback
        configurationError(config)?.let { fail(it); return }
        val active = sessionFactory()
        session = active
        val token = connectionToken
        armTimeout(START_TIMEOUT_MS, "Azure Speech session startup timed out.")
        active.start(config, resolveLanguage(languageTag, config.autoDetectLanguage), object : MaiSpeechSession.Listener {
            override fun onReady() = post(token) {
                if (ready || finishing) return@post
                ready = true
                clearTimeout()
                Log.i(TAG, "VOICE_RESPONSE Azure Speech SDK ready")
                this@MaiTranscriptionClient.callback?.onStreamReady()
            }
            override fun onFinal(resultId: String, text: String, audioEndBytes: Long, commitToken: Int) = post(token) {
                if (resultId.isNotEmpty() && !completedIds.add(resultId)) return@post
                if (audioEndBytes < 0 || audioEndBytes > totalBytes ||
                    (audioEndBytes > 0 && audioEndBytes < lastResultEndBytes)) {
                    fail("Azure Speech returned inconsistent audio timing. Dictation stopped.")
                    return@post
                }
                lastResultEndBytes = maxOf(lastResultEndBytes, audioEndBytes)
                if (commitToken > 0) completedTokens.add(commitToken)
                finalizedBytes = maxOf(finalizedBytes, audioEndBytes.coerceAtMost(totalBytes))
                settleBoundaries()
                if (text.isNotBlank()) {
                    val finalText = text.trim()
                    Log.i(TAG, "VOICE_STEP_4 MAI completed transcript (${finalText.length} chars)")
                    this@MaiTranscriptionClient.callback?.onTranscriptionResult(TranscriptSegment(finalText,
                        finalText.first() in ",.!?;:%)]}、。，！？：；…"))
                    if (token != connectionToken) return@post
                }
                armCompletionTimeout()
                this@MaiTranscriptionClient.callback?.onPendingProcessingChanged()
            }
            override fun onCommitRequested(token: Int, audioEndBytes: Long) = post(tokenOfConnection) {
                boundaries.firstOrNull { it.endBytes == audioEndBytes }?.token = token
                settleBoundaries()
                armCompletionTimeout()
                this@MaiTranscriptionClient.callback?.onPendingProcessingChanged()
            }
            private val tokenOfConnection = token
            override fun onEnded() = post(token) {
                if (!finishing) { fail("Azure Speech ended the dictation session unexpectedly."); return@post }
                // EOF is the SDK's definitive drain signal, even for advisory commits
                // that never echo a token or speech that produces NoMatch.
                val listener = this@MaiTranscriptionClient.callback
                cancelAll()
                listener?.onStreamClosed()
            }
            override fun onError(message: String) = post(token) { fail(message) }
            override fun onWriteAvailable() = post(token) { this@MaiTranscriptionClient.callback?.onAudioWriteAvailable() }
        })
        Log.i(TAG, "VOICE_STEP_3 starting direct Azure Speech dictation")
    }

    fun sendAudioChunk(pcmData: ByteArray): Boolean {
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
        totalBytes += pcmData.size
        unfinalizedAudio.addLast(AudioProgress(totalBytes, SystemClock.elapsedRealtime()))
        armProgressTimeout()
        if (!wasPending) callback?.onPendingProcessingChanged()
        return true
    }

    fun finalizeTurn(): Boolean {
        if (!ready || finishing || totalBytes <= committedBytes || totalBytes <= finalizedBytes) return false
        if (boundaries.size >= MAX_PENDING_COMMITS) {
            requestDrain()
            return false
        }
        committedBytes = totalBytes
        boundaries.addLast(Boundary(totalBytes, SystemClock.elapsedRealtime()))
        session?.commit(totalBytes)
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
        armTimeout(completionTimeoutMs, "Azure Speech timed out draining the final audio.")
        callback?.onPendingProcessingChanged()
    }

    fun cancelAll() {
        connectionToken++
        clearTimeout()
        clearProgressTimeout()
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
    }

    private fun settleBoundaries() {
        // Tokens correlate explicit commits. Ordinary finals use their audio offsets.
        for (boundary in boundaries) {
            if (boundary.token > 0 && boundary.token in completedTokens) {
                finalizedBytes = maxOf(finalizedBytes, boundary.endBytes)
            }
        }
        while (boundaries.firstOrNull()?.endBytes?.let { it <= finalizedBytes } == true) boundaries.removeFirst()
        settleAudioProgress()
    }

    private fun fail(message: String) {
        val pendingAudio = totalBytes > finalizedBytes || boundaries.isNotEmpty()
        val listener = callback
        cancelAll()
        val description = if (pendingAudio) "$message The last voice segment could not be finalized." else message
        Log.e(TAG, description)
        // A detected failure is terminal even when the SDK might reconnect.
        listener?.onStreamError(description, pendingAudio)
    }

    private fun settleAudioProgress() {
        while (unfinalizedAudio.firstOrNull()?.endBytes?.let { it <= finalizedBytes } == true)
            unfinalizedAudio.removeFirst()
        armProgressTimeout()
    }
    private fun armProgressTimeout() {
        clearProgressTimeout()
        if (finishing || awaitingIntegrityDrain) return
        val oldest = unfinalizedAudio.firstOrNull() ?: return
        val token = connectionToken
        progressTimeout = Runnable {
            if (token == connectionToken) {
                awaitingIntegrityDrain = true
                callback?.onFinalConfirmationStalled()
            }
        }.also { handler.postDelayed(it,
            (progressTimeoutMs - (SystemClock.elapsedRealtime() - oldest.acceptedAt)).coerceAtLeast(0)) }
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
