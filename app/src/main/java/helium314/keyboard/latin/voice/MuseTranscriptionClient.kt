// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import helium314.keyboard.latin.utils.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/** One Muse connection per recording. Every state transition runs on the main looper. */
class MuseTranscriptionClient {
    companion object {
        private const val TAG = "MuseTranscription"
        const val MODEL = "muse-voice-transcribe-1.0"
        const val SESSION_CAPTURE_LIMIT_MS = 55 * 60_000L
        const val MAX_USER_VOCABULARY_TERMS = 100 // App policy, not a claimed service limit.
        private const val FINAL_TIMEOUT_MS = 30_000L
        internal const val STREAMING_ENDPOINT = "wss://api.meta.ai/v1/asr/realtime"
        internal fun defaultVocabulary() = listOf("HeliBoard", "Muse", "Gemini", "GitHub", "API", "Android")
        internal fun resolveLanguageCode(tag: String?): String? {
            val code = tag?.replace('_', '-')?.substringBefore('-')?.lowercase(Locale.ROOT)
            return mapOf("ar" to "Arabic", "bn" to "Bengali", "nl" to "Dutch", "en" to "English",
                "fr" to "French", "de" to "German", "he" to "Hebrew", "hi" to "Hindi", "id" to "Indonesian",
                "it" to "Italian", "ja" to "Japanese", "kn" to "Kannada", "ko" to "Korean", "ms" to "Malay",
                "zh" to "Mandarin Chinese", "mr" to "Marathi", "pl" to "Polish", "pt" to "Portuguese",
                "es" to "Spanish", "tl" to "Tagalog", "fil" to "Tagalog", "ta" to "Tamil", "te" to "Telugu",
                "th" to "Thai", "tr" to "Turkish", "vi" to "Vietnamese")[code]
        }
        internal fun buildSessionConfig(languageTag: String?, autoDetectLanguage: Boolean,
            userVocabulary: List<String>, editorContext: String?, sampleRate: Int = 24000,
            responsiveDictation: Boolean = false, timingDiagnostics: Boolean = false) = SessionConfig(
            sampleRate, if (autoDetectLanguage) emptyList() else listOfNotNull(resolveLanguageCode(languageTag)),
            VoiceContextVocabulary.build(userVocabulary, defaultVocabulary(), editorContext, MAX_USER_VOCABULARY_TERMS),
            if (responsiveDictation) Mode.ENDPOINTING else Mode.DIARIZATION, timingDiagnostics)
        internal fun handshake(key: String, config: SessionConfig): String = JSONObject().apply {
            require(config.sampleRate == 16000 || config.sampleRate == 24000)
            put("authorization", JSONObject().put("accessToken", "Bearer ${key.trim().removePrefix("Bearer ")}"))
            put("model", MODEL)
            put("audioEncoding", if (config.sampleRate == 24000) "PCM_24KHZ" else "PCM_16KHZ")
            put("mode", config.mode.name)
            put("partialMode", "CUMULATIVE")
            put("emitAudioProgress", true)
            if (config.languageBias.isNotEmpty()) put("languageBias", JSONArray(config.languageBias))
            if (config.keywords.isNotEmpty()) put("keywords", JSONArray(config.keywords))
        }.toString()
    }
    internal enum class Mode { DIARIZATION, ENDPOINTING }
    internal data class SessionConfig(val sampleRate: Int, val languageBias: List<String>, val keywords: List<String>,
        val mode: Mode = Mode.DIARIZATION, val timingDiagnostics: Boolean = false)
    interface StreamingCallback {
        fun onStreamReady()
        fun onTranscriptionResult(segment: TranscriptSegment)
        fun onInterimTranscription()
        /** True for settled turns, including intentionally filtered speakers. */
        fun onServerResponse(hasTranscriptText: Boolean)
        fun onStreamError(error: String)
        fun onStreamClosed()
    }
    internal enum class AudioSendResult { ACCEPTED, BACKPRESSURE, FAILED }
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).pingInterval(5, TimeUnit.SECONDS).build()
    internal var socketFactory: WebSocket.Factory = http
    internal var streamingEndpoint = STREAMING_ENDPOINT
    private var socket: WebSocket? = null
    private var callback: StreamingCallback? = null
    private var generation = 0L
    private var ready = false
    private var finishing = false
    private var bytesPerSecond = 48000L
    private var sentBytes = 0L
    private var processedMs = 0L
    private var firstAudioAt: Long? = null
    private var speechSuffixBytes = 0L
    private var hasSpeech = false
    private var key = ""
    private var turns = MuseTurnAssembler()
    private var timing: MuseLatencyDiagnostics? = null
    private val deadlines = mutableMapOf<Int, Runnable>()

    internal fun startStreaming(apiKey: String, sessionConfig: SessionConfig, callback: StreamingCallback) {
        cancelAll()
        this.callback = callback
        key = apiKey.trim().removePrefix("Bearer ")
        bytesPerSecond = sessionConfig.sampleRate * 2L
        turns = MuseTurnAssembler(filterSpeakers = sessionConfig.mode == Mode.DIARIZATION)
        timing = if (sessionConfig.timingDiagnostics) MuseLatencyDiagnostics() else null
        val token = generation
        Log.i(TAG, "opening mode=${sessionConfig.mode} sampleRate=${sessionConfig.sampleRate}")
        socket = socketFactory.newWebSocket(Request.Builder().url(streamingEndpoint).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = post(token) {
                if (!webSocket.send(handshake(key, sessionConfig))) fail("Muse session could not start")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val receivedAtMs = SystemClock.elapsedRealtime()
                post(token) { receive(text, receivedAtMs) }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = post(token) { fail("Muse sent an unexpected binary response") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = post(token) {
                if (code != 1000 || !finishing || !ready) {
                    fail(when (code) {
                        1008 -> "Muse rejected the request or audio pacing. Check configuration and connection."
                        1013 -> "Muse rate limit reached. Wait before starting again."
                        else -> "Muse connection ended before dictation finished (code $code). Tap the mic to restart."
                    })
                } else {
                    try { turns.verifyFinished() } catch (_: Exception) {
                        fail("Muse ended with unresolved text or speaker attribution. Tap the mic and speak first again.")
                        return@post
                    }
                    val sink = this@MuseTranscriptionClient.callback
                    cancelAll()
                    sink?.onStreamClosed()
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post(token) {
                fail(when (response?.code) {
                    401, 403 -> "Muse rejected the API key or account. Check Settings and Meta billing."
                    429 -> "Muse rate limit reached. Wait before starting again."
                    else -> "Could not connect to Muse. Check your internet connection and API access."
                })
            }
        })
    }
    private fun post(token: Long, work: () -> Unit) { handler.post { if (token == generation) work() } }
    private fun receive(raw: String, receivedAtMs: Long) {
        val token = generation
        try {
            val event = JSONObject(raw)
            if (event.optString("type") == "error") {
                // Do not expose arbitrary service text: it can contain credentials or supplied context.
                fail("Muse rejected the session. Check your API key, account billing, and model access.")
                return
            }
            if (!ready) {
                check(!event.has("type") && event.getString("sessionId").isNotBlank())
                ready = true
                Log.i(TAG, "handshake accepted")
                callback?.onStreamReady()
                return
            }
            val type = event.optString("type")
            if (type !in setOf("speechStart", "speechEnd", "speechComplete", "speaker", "transcript", "audioProgress")) return
            val progress = event.getLong("audioProcessedMs")
            // Asynchronous completed turns may carry an older progress value.
            processedMs = maxOf(processedMs, progress)
            timing?.event(event, sentBytes * 1000 / bytesPerSecond - processedMs, queuedFrameBytes(), receivedAtMs)
            val completed = turns.accept(event)
            syncDeadlines()
            if (type == "transcript" && event.getString("transcript").isNotBlank()) callback?.onInterimTranscription()
            if (token != generation) return
            for (turn in completed) {
                timing?.released(turn.id, turn.selected)
                if (turn.selected) callback?.onTranscriptionResult(TranscriptSegment(turn.text, turn.text.first() in ".,!?:;)]}%"))
                if (token != generation) return
            }
            callback?.onServerResponse(completed.isNotEmpty())
        } catch (_: Exception) {
            if (token == generation) fail("Muse returned invalid or ambiguous transcription events. Tap the mic to restart.")
        }
    }
    private fun syncDeadlines() {
        val pending = turns.pendingFinalIds()
        for (id in deadlines.keys.toList()) if (id !in pending) handler.removeCallbacks(deadlines.remove(id)!!)
        val token = generation
        for (id in pending) if (id !in deadlines) {
            deadlines[id] = Runnable { if (generation == token) fail("Muse did not finish a speech turn or identify its speaker") }
                .also { handler.postDelayed(it, FINAL_TIMEOUT_MS) }
        }
    }
    internal fun offerAudioChunk(pcmData: ByteArray, containsSpeech: Boolean = true): AudioSendResult {
        val ws = socket ?: return AudioSendResult.FAILED
        if (!ready || finishing || pcmData.isEmpty() || pcmData.size % 2 != 0 || pcmData.size > bytesPerSecond / 10) return AudioSendResult.FAILED
        val now = SystemClock.elapsedRealtime()
        val start = firstAudioAt ?: now
        // A single capture frame of headroom; startup/backlogged audio cannot burst.
        if ((sentBytes + pcmData.size) * 1000 / bytesPerSecond > now - start + 100 ||
            (sentBytes + pcmData.size) * 1000 / bytesPerSecond - processedMs > 2000 ||
            ws.queueSize() + pcmData.size > bytesPerSecond * 2) return AudioSendResult.BACKPRESSURE
        if (!ws.send(pcmData.toByteString())) return AudioSendResult.FAILED
        firstAudioAt = start
        sentBytes += pcmData.size
        if (containsSpeech) { hasSpeech = true; speechSuffixBytes = 0 } else speechSuffixBytes += pcmData.size
        return AudioSendResult.ACCEPTED
    }
    internal fun queuedFrameBytes(): Long = socket?.queueSize() ?: 0
    internal fun hasQueuedFrames() = queuedFrameBytes() > 0
    internal fun hasQueuedSpeechFrames() = hasSpeech && queuedFrameBytes() > speechSuffixBytes
    internal fun hasPendingTurns() = turns.hasPendingTurns()
    fun finishStreaming(): Boolean {
        if (!ready || finishing) return false
        if (socket?.send("{\"type\":\"endStream\"}") != true) return false
        finishing = true
        return true
    }
    fun cancelAll() {
        generation++
        deadlines.values.forEach(handler::removeCallbacks)
        deadlines.clear()
        socket?.cancel()
        socket = null
        callback = null
        key = ""
        ready = false
        finishing = false
        sentBytes = 0
        processedMs = 0
        firstAudioAt = null
        speechSuffixBytes = 0
        hasSpeech = false
        turns = MuseTurnAssembler()
        timing = null
    }
    private fun fail(message: String) {
        val sink = callback ?: return
        Log.e(TAG, message)
        cancelAll()
        sink.onStreamError(message)
    }
}
