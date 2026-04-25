// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.Log
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Server messages received over the AssemblyAI Universal-Streaming WebSocket.
 *
 * Universal-Streaming uses three message types:
 *  - `Begin` — session opened, includes `id` and `expires_at` epoch seconds.
 *  - `Turn` — running transcription for the current speaking turn. The same turn
 *    can be delivered multiple times as more words finalize. When a turn ends
 *    (semantic or acoustic), the server sets `end_of_turn: true`. With
 *    `format_turns=true` we also receive a follow-up `Turn` with
 *    `turn_is_formatted: true` whose `transcript` carries punctuation/casing/ITN.
 *  - `Termination` — session is closing.
 *
 * We surface end-of-turn results only — anything before that is partial and
 * Universal-Streaming guarantees no rewriting of words already marked
 * `word_is_final: true`, so a finalized turn already accumulates everything
 * needed to insert at the caret in one shot.
 */
internal sealed interface AssemblyAIServerEvent {
    data class Begin(val sessionId: String?) : AssemblyAIServerEvent
    data class Turn(
        val turnOrder: Int,
        val transcript: String,
        val isFormatted: Boolean,
        val endOfTurn: Boolean,
        val endOfTurnConfidence: Double,
    ) : AssemblyAIServerEvent
    data class Termination(val audioDurationSeconds: Double) : AssemblyAIServerEvent
    data class Error(val description: String) : AssemblyAIServerEvent
}

/**
 * A finalized transcript fragment ready for insertion into the editor.
 *
 * `attachesToPrevious` mirrors the Speechmatics-era semantics so the rest of
 * the IME (LatinIME, VoiceInputManager) does not need to change: when `true`,
 * no leading space is inserted before the text. AssemblyAI Universal-Streaming
 * always emits a fresh "turn" of speech (~one utterance) so this flag is
 * derived locally from leading punctuation only.
 */
data class TranscriptSegment(
    val text: String,
    val attachesToPrevious: Boolean
)

/**
 * Client for AssemblyAI Universal-Streaming realtime transcription.
 *
 * Endpoint: `wss://streaming.assemblyai.com/v3/ws`
 *
 * Auth: `Authorization: <API_KEY>` request header (no `Bearer` prefix). Supports
 * the standard streaming API and EU streaming (`streaming.eu.assemblyai.com`).
 *
 * Why Universal-Streaming for HeliBoard:
 *   - **Semantic + acoustic end-of-turn detection** — the server combines a
 *     neural turn-detection model with VAD. Pauses do not produce a fragmented
 *     transcript when the speaker is still mid-sentence; the model holds the
 *     turn open until either the speech is semantically complete or the
 *     acoustic ceiling (`max_turn_silence`) is reached.
 *   - **Immutable transcripts** — finalized words never get rewritten, so we
 *     can commit text once and trust it.
 *   - **Formatted finals** — with `format_turns=true` we receive a punctuated,
 *     case-corrected, ITN-formatted version of every completed turn (dates,
 *     times, currency, phone numbers).
 *
 * Audio format: Raw PCM 16-bit little-endian mono at 16 kHz, sent as binary
 * websocket frames (no header). On stop we send `{"type":"Terminate"}` to
 * flush the tail and then close the socket.
 *
 * API docs:
 *  - https://www.assemblyai.com/docs/streaming/universal-streaming
 *  - https://www.assemblyai.com/docs/streaming/universal-streaming/turn-detection
 *  - https://www.assemblyai.com/docs/streaming/keyterms-prompting
 */
class AssemblyAITranscriptionClient {

    companion object {
        private const val TAG = "AssemblyAITranscription"

        /**
         * Standard streaming endpoint. EU customers can switch to
         * `streaming.eu.assemblyai.com`. We construct the URL using `https`
         * scheme so OkHttp's URL builder accepts it; OkHttp's WebSocket layer
         * upgrades the request to `wss` automatically when calling
         * `client.newWebSocket(...)` on an HTTPS URL.
         */
        private const val STREAMING_BASE_URL_DEFAULT = "https://streaming.assemblyai.com/v3/ws"
        private const val STREAMING_BASE_URL_EU = "https://streaming.eu.assemblyai.com/v3/ws"

        /** Defaults match AssemblyAI's "balanced" recommendation. Keyterms appended at runtime. */
        const val DEFAULT_SPEECH_MODEL = "universal-streaming-english"
        const val DEFAULT_END_OF_TURN_CONFIDENCE_THRESHOLD = 0.7
        const val DEFAULT_MIN_TURN_SILENCE_MS = 400
        const val DEFAULT_MAX_TURN_SILENCE_MS = 2400

        /** Grace period for the server to flush any final `Turn` after `Terminate` is sent. */
        private const val FINALIZE_CLOSE_GRACE_MS = 6_000L

        internal data class SessionConfig(
            val speechModel: String,
            val sampleRate: Int,
            val formatTurns: Boolean,
            val endOfTurnConfidenceThreshold: Double,
            val minTurnSilenceMs: Int,
            val maxTurnSilenceMs: Int,
            val keyterms: List<String>,
            val useEuEndpoint: Boolean,
        )

        /**
         * Curated keyterm prompts. AssemblyAI supports up to 100 keyterms, each
         * <= 50 characters; the list below stays well under both. We seed it
         * with HeliBoard branding plus a small set of commonly-confused
         * dictation targets.
         */
        internal fun defaultKeyterms(): List<String> = listOf(
            "HeliBoard",
            "AssemblyAI",
            "Universal-Streaming",
            "Kubernetes",
            "API",
            "iPhone",
            "Android",
            "OK Google",
            "Hey Siri",
            "gnocchi"
        )

        internal fun buildSessionConfig(
            speechModel: String = DEFAULT_SPEECH_MODEL,
            sampleRate: Int = VoiceRecorder.SAMPLE_RATE,
            formatTurns: Boolean = true,
            endOfTurnConfidenceThreshold: Double = DEFAULT_END_OF_TURN_CONFIDENCE_THRESHOLD,
            minTurnSilenceMs: Int = DEFAULT_MIN_TURN_SILENCE_MS,
            maxTurnSilenceMs: Int = DEFAULT_MAX_TURN_SILENCE_MS,
            keyterms: List<String> = defaultKeyterms(),
            useEuEndpoint: Boolean = false,
        ): SessionConfig = SessionConfig(
            speechModel = speechModel,
            sampleRate = sampleRate,
            formatTurns = formatTurns,
            endOfTurnConfidenceThreshold = endOfTurnConfidenceThreshold.coerceIn(0.0, 1.0),
            minTurnSilenceMs = minTurnSilenceMs.coerceIn(0, 10_000),
            maxTurnSilenceMs = maxTurnSilenceMs.coerceIn(80, 30_000)
                .coerceAtLeast(minTurnSilenceMs),
            keyterms = keyterms
                .map { it.trim() }
                .filter { it.isNotEmpty() && it.length <= 50 }
                .distinct()
                .take(100),
            useEuEndpoint = useEuEndpoint,
        )

        /**
         * Build the connection URL with all session parameters as query
         * arguments. AssemblyAI's Universal-Streaming endpoint receives the
         * full session configuration in the WebSocket connect URL — there is
         * no equivalent of Speechmatics' `StartRecognition` JSON message.
         */
        internal fun buildConnectionUrl(config: SessionConfig): HttpUrl {
            val base = if (config.useEuEndpoint) STREAMING_BASE_URL_EU else STREAMING_BASE_URL_DEFAULT
            val parsed = base.toHttpUrlOrNull()
                ?: error("Invalid AssemblyAI streaming base URL: $base")
            val builder = parsed.newBuilder()
                .addQueryParameter("speech_model", config.speechModel)
                .addQueryParameter("sample_rate", config.sampleRate.toString())
                .addQueryParameter("format_turns", config.formatTurns.toString())
                .addQueryParameter(
                    "end_of_turn_confidence_threshold",
                    config.endOfTurnConfidenceThreshold.toString()
                )
                .addQueryParameter("min_turn_silence", config.minTurnSilenceMs.toString())
                .addQueryParameter("max_turn_silence", config.maxTurnSilenceMs.toString())

            if (config.keyterms.isNotEmpty()) {
                builder.addQueryParameter(
                    "keyterms_prompt",
                    JSONArray(config.keyterms).toString()
                )
            }
            return builder.build()
        }

        internal fun parseServerEvent(message: String): AssemblyAIServerEvent? {
            val json = JSONObject(message)
            return when (json.optString("type")) {
                "Begin" -> AssemblyAIServerEvent.Begin(
                    sessionId = json.optString("id").ifBlank { null }
                )
                "Turn" -> AssemblyAIServerEvent.Turn(
                    turnOrder = json.optInt("turn_order", -1),
                    transcript = json.optString("transcript", "").trim(),
                    isFormatted = json.optBoolean("turn_is_formatted", false),
                    endOfTurn = json.optBoolean("end_of_turn", false),
                    endOfTurnConfidence = json.optDouble("end_of_turn_confidence", 0.0),
                )
                "Termination" -> AssemblyAIServerEvent.Termination(
                    audioDurationSeconds = json.optDouble("audio_duration_seconds", 0.0)
                )
                "Error" -> AssemblyAIServerEvent.Error(
                    description = json.optString("error", "AssemblyAI reported an unknown error")
                )
                else -> null
            }
        }
    }

    interface StreamingCallback {
        /** Connection is open and the session has begun (server `Begin`). */
        fun onStreamReady()

        /** A finalized turn has arrived. */
        fun onTranscriptionResult(segment: TranscriptSegment)

        /** Streaming failed and this stream can no longer be used. */
        fun onStreamError(error: String)

        /** Stream closed (gracefully or remotely). */
        fun onStreamClosed()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var callback: StreamingCallback? = null

    @Volatile
    private var activeConnectionToken = 0L

    @Volatile
    private var isClosing = false

    @Volatile
    private var isSessionBegun = false

    @Volatile
    private var formattedTurnsRequested = false

    /**
     * For each `turn_order`, the most recent end-of-turn payload we forwarded.
     * When `format_turns=true`, AssemblyAI may emit two end-of-turn messages:
     * the first carries the unformatted transcript, the second carries the
     * formatted version. We only forward the formatted text in that case so
     * the editor never receives the raw pre-formatted transcript first.
     */
    @Volatile
    private var lastEmittedTurnOrder: Int = -1

    @Volatile
    private var pendingFinalizeCloseRunnable: Runnable? = null

    internal fun startStreaming(
        apiKey: String,
        sessionConfig: SessionConfig,
        callback: StreamingCallback
    ) {
        val newToken = activeConnectionToken + 1
        activeConnectionToken = newToken
        stopStreamingInternal(cancel = true, clearCallback = false)

        this.callback = callback
        isClosing = false
        isSessionBegun = false
        formattedTurnsRequested = sessionConfig.formatTurns
        lastEmittedTurnOrder = -1
        clearFinalizeCloseTimer()

        val url = buildConnectionUrl(sessionConfig)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", apiKey)
            .build()

        Log.i(
            TAG,
            "VOICE_STEP_3 opening AssemblyAI Universal-Streaming socket " +
                "(model=${sessionConfig.speechModel}, format_turns=${sessionConfig.formatTurns}, " +
                "eot_conf=${sessionConfig.endOfTurnConfidenceThreshold}, " +
                "min_turn_silence=${sessionConfig.minTurnSilenceMs}ms, " +
                "max_turn_silence=${sessionConfig.maxTurnSilenceMs}ms, " +
                "keyterms=${sessionConfig.keyterms.size}, eu=${sessionConfig.useEuEndpoint})"
        )

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (newToken != activeConnectionToken) return
                this@AssemblyAITranscriptionClient.webSocket = webSocket
                // Universal-Streaming has no client-side StartRecognition step:
                // the session begins automatically and we wait for `Begin`.
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (newToken != activeConnectionToken) return
                handleMessage(newToken, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (newToken != activeConnectionToken) return
                Log.i(TAG, "AssemblyAI stream closing: code=$code, reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (newToken != activeConnectionToken) return
                clearFinalizeCloseTimer()
                isSessionBegun = false
                this@AssemblyAITranscriptionClient.webSocket = null
                Log.i(TAG, "AssemblyAI stream closed: code=$code, reason=$reason")
                postIfCurrent(newToken) {
                    this@AssemblyAITranscriptionClient.callback?.onStreamClosed()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (newToken != activeConnectionToken) return
                clearFinalizeCloseTimer()
                isSessionBegun = false
                this@AssemblyAITranscriptionClient.webSocket = null
                if (isClosing) {
                    Log.i(TAG, "AssemblyAI stream failure after close request: ${t.message}")
                    postIfCurrent(newToken) {
                        this@AssemblyAITranscriptionClient.callback?.onStreamClosed()
                    }
                    return
                }
                val errorMessage = mapConnectionError(t, response)
                Log.e(TAG, "AssemblyAI stream failure: $errorMessage (raw: ${t.message})")
                postIfCurrent(newToken) {
                    this@AssemblyAITranscriptionClient.callback?.onStreamError(errorMessage)
                }
            }
        })
    }

    /** Send raw PCM16 little-endian mono 16 kHz audio. Returns false if the socket cannot accept it. */
    fun sendAudioChunk(pcmData: ByteArray): Boolean {
        if (pcmData.isEmpty()) return true
        if (isClosing) return false
        val socket = webSocket ?: return false
        if (!isSessionBegun) return false
        return try {
            socket.send(pcmData.toByteString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
            false
        }
    }

    /**
     * Flush any in-progress turn and close the socket. AssemblyAI's
     * `Terminate` message triggers a final formatted `Turn` (when
     * `format_turns=true`) followed by a `Termination` reply, after which the
     * server closes the socket. We post a fallback close after a grace period
     * so the IME never deadlocks waiting on a missing reply.
     */
    fun finishStreaming() {
        val socket = webSocket ?: return
        isClosing = true
        clearFinalizeCloseTimer()

        val connectionToken = activeConnectionToken
        val closeRunnable = Runnable {
            if (connectionToken != activeConnectionToken) return@Runnable
            val activeSocket = webSocket ?: return@Runnable
            activeSocket.close(1000, "client_stop")
        }
        pendingFinalizeCloseRunnable = closeRunnable
        mainHandler.postDelayed(closeRunnable, FINALIZE_CLOSE_GRACE_MS)

        if (!isSessionBegun) {
            socket.close(1000, "client_stop")
            return
        }

        val payload = JSONObject().put("type", "Terminate").toString()
        val sent = try {
            socket.send(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Terminate: ${e.message}")
            false
        }
        if (sent) {
            Log.i(TAG, "AssemblyAI Terminate sent; awaiting final Turn / Termination")
        } else {
            socket.close(1000, "client_stop")
        }
    }

    fun cancelAll() {
        activeConnectionToken += 1
        stopStreamingInternal(cancel = true, clearCallback = true)
    }

    private fun stopStreamingInternal(cancel: Boolean, clearCallback: Boolean) {
        clearFinalizeCloseTimer()
        isClosing = true
        isSessionBegun = false
        lastEmittedTurnOrder = -1
        val socket = webSocket
        webSocket = null
        if (socket != null) {
            if (cancel) {
                socket.cancel()
            } else {
                socket.close(1000, "client_stop")
            }
        }
        if (clearCallback) {
            callback = null
        }
    }

    private fun handleMessage(connectionToken: Long, message: String) {
        val event = try {
            parseServerEvent(message)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse AssemblyAI event: ${e.message}")
            null
        } ?: return

        when (event) {
            is AssemblyAIServerEvent.Begin -> {
                isSessionBegun = true
                Log.i(TAG, "AssemblyAI session begun (id=${event.sessionId ?: "?"})")
                postIfCurrent(connectionToken) {
                    callback?.onStreamReady()
                }
            }

            is AssemblyAIServerEvent.Turn -> handleTurn(connectionToken, event)

            is AssemblyAIServerEvent.Termination -> {
                Log.i(
                    TAG,
                    "AssemblyAI session terminated (audio=${event.audioDurationSeconds}s)"
                )
                if (isClosing) {
                    clearFinalizeCloseTimer()
                    webSocket?.close(1000, "client_stop")
                }
            }

            is AssemblyAIServerEvent.Error -> {
                Log.e(TAG, "AssemblyAI stream error event: ${event.description}")
                postIfCurrent(connectionToken) {
                    callback?.onStreamError(event.description)
                }
            }
        }
    }

    private fun handleTurn(connectionToken: Long, turn: AssemblyAIServerEvent.Turn) {
        if (!turn.endOfTurn) return
        if (turn.transcript.isBlank()) return
        if (formattedTurnsRequested && !turn.isFormatted) {
            // The unformatted end-of-turn arrives first; wait for the formatted one
            // so we never insert pre-formatted text and then have to revise it.
            return
        }
        if (turn.turnOrder >= 0 && turn.turnOrder == lastEmittedTurnOrder) {
            // Duplicate / out-of-order delivery — ignore.
            return
        }
        if (turn.turnOrder >= 0) {
            lastEmittedTurnOrder = turn.turnOrder
        }

        val attachesToPrevious = startsWithAttachingPunctuation(turn.transcript)
        Log.i(
            TAG,
            "VOICE_STEP_4 AssemblyAI final turn order=${turn.turnOrder} " +
                "formatted=${turn.isFormatted} (${turn.transcript.length} chars)"
        )
        postIfCurrent(connectionToken) {
            callback?.onTranscriptionResult(
                TranscriptSegment(
                    text = turn.transcript,
                    attachesToPrevious = attachesToPrevious
                )
            )
        }
    }

    private fun startsWithAttachingPunctuation(text: String): Boolean {
        val first = text.firstOrNull() ?: return false
        return first in PUNCTUATION_ATTACHING_TO_PREVIOUS
    }

    private fun mapConnectionError(error: Throwable, response: Response?): String {
        val code = response?.code
        if (code != null) {
            return when (code) {
                401, 403 -> "Invalid AssemblyAI API key. Please check Settings."
                429 -> "AssemblyAI rate limited — too many requests"
                in 500..599 -> "AssemblyAI service error ($code)"
                else -> "AssemblyAI connection rejected ($code)"
            }
        }
        return when (error) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "AssemblyAI streaming timed out"
            is ConnectException -> "Could not connect to AssemblyAI streaming"
            else -> "Streaming error: ${error.message ?: "unknown"}"
        }
    }

    private fun postIfCurrent(connectionToken: Long, action: () -> Unit) {
        if (connectionToken != activeConnectionToken) return
        mainHandler.post {
            if (connectionToken != activeConnectionToken) return@post
            action()
        }
    }

    private fun clearFinalizeCloseTimer() {
        val runnable = pendingFinalizeCloseRunnable ?: return
        mainHandler.removeCallbacks(runnable)
        pendingFinalizeCloseRunnable = null
    }
}

private val PUNCTUATION_ATTACHING_TO_PREVIOUS = setOf(
    '.', ',', '!', '?', ':', ';', ')', ']', '}', '%'
)
