// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Client for Deepgram's streaming transcription API (WebSocket).
 *
 * Deepgram receives raw PCM16 chunks continuously and sends transcription events
 * back over the same socket. We only surface finalized transcript updates so the
 * downstream cleanup queue stays deterministic.
 *
 * API docs: https://developers.deepgram.com/docs/streaming
 */
class DeepgramTranscriptionClient {

    companion object {
        private const val TAG = "DeepgramTranscription"
        private const val STREAMING_BASE_URL = "wss://api.deepgram.com/v1/listen"
        private const val FINALIZE_CLOSE_GRACE_MS = 1_500L
    }

    interface StreamingCallback {
        /** WebSocket opened and ready to receive audio chunks. */
        fun onStreamReady()

        /** Finalized transcription text from Deepgram's streaming result event. */
        fun onTranscriptionResult(text: String)

        /** Streaming failed and this stream can no longer be used. */
        fun onStreamError(error: String)

        /** Stream closed (gracefully or remotely). */
        fun onStreamClosed()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // WebSockets are long-lived; disable read timeout so the socket can stay open.
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
    private var isOpen = false

    @Volatile
    private var lastFinalResultFingerprint = ""

    @Volatile
    private var pendingFinalizeCloseRunnable: Runnable? = null

    /**
     * Start a new Deepgram streaming session.
     *
     * Any previous stream is cancelled.
     */
    fun startStreaming(
        apiKey: String,
        language: String? = null,
        callback: StreamingCallback
    ) {
        val newToken = activeConnectionToken + 1
        activeConnectionToken = newToken
        stopStreamingInternal(cancel = true, clearCallback = false)

        this.callback = callback
        isClosing = false
        isOpen = false
        lastFinalResultFingerprint = ""
        clearFinalizeCloseTimer()

        val url = buildStreamingUrl(language)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Token $apiKey")
            .build()

        Log.i(
            TAG,
            "VOICE_STEP_3 opening Deepgram streaming socket " +
                "(language=${language ?: "auto"}, endpointing=deepgram-default)"
        )

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (newToken != activeConnectionToken) return
                isOpen = true
                Log.i(TAG, "Deepgram stream connected")
                postIfCurrent(newToken) {
                    this@DeepgramTranscriptionClient.callback?.onStreamReady()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (newToken != activeConnectionToken) return
                handleMessage(newToken, text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (newToken != activeConnectionToken) return
                Log.i(TAG, "Deepgram stream closing: code=$code, reason=$reason")
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (newToken != activeConnectionToken) return
                clearFinalizeCloseTimer()
                isOpen = false
                this@DeepgramTranscriptionClient.webSocket = null
                Log.i(TAG, "Deepgram stream closed: code=$code, reason=$reason")
                postIfCurrent(newToken) {
                    this@DeepgramTranscriptionClient.callback?.onStreamClosed()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (newToken != activeConnectionToken) return
                clearFinalizeCloseTimer()
                isOpen = false
                this@DeepgramTranscriptionClient.webSocket = null
                if (isClosing) {
                    Log.i(TAG, "Deepgram stream failure after close request: ${t.message}")
                    postIfCurrent(newToken) {
                        this@DeepgramTranscriptionClient.callback?.onStreamClosed()
                    }
                    return
                }
                Log.e(TAG, "Deepgram stream failure: ${t.message}")
                postIfCurrent(newToken) {
                    this@DeepgramTranscriptionClient.callback?.onStreamError(mapNetworkError(t))
                }
            }
        })
    }

    /**
     * Send one PCM16 chunk to Deepgram.
     *
     * @return true if the chunk was accepted by OkHttp for sending.
     */
    fun sendAudioChunk(pcmData: ByteArray): Boolean {
        if (pcmData.isEmpty()) return true
        val socket = webSocket ?: return false
        if (!isOpen) return false
        return try {
            socket.send(pcmData.toByteString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk: ${e.message}")
            false
        }
    }

    /**
     * Gracefully finalize the stream.
     *
     * Deepgram may emit one last finalized transcript after receiving Finalize.
     */
    fun finishStreaming() {
        val socket = webSocket ?: return
        isClosing = true
        try {
            socket.send("""{"type":"Finalize"}""")
        } catch (_: Exception) {
            // Best effort.
        }

        // Give Deepgram a brief window to emit the final transcript before closing.
        clearFinalizeCloseTimer()
        val connectionToken = activeConnectionToken
        val closeRunnable = Runnable {
            if (connectionToken != activeConnectionToken) return@Runnable
            val activeSocket = webSocket ?: return@Runnable
            activeSocket.close(1000, "client_stop")
        }
        pendingFinalizeCloseRunnable = closeRunnable
        mainHandler.postDelayed(closeRunnable, FINALIZE_CLOSE_GRACE_MS)
    }

    /** Cancel the stream immediately and clear callbacks. */
    fun cancelAll() {
        activeConnectionToken += 1
        stopStreamingInternal(cancel = true, clearCallback = true)
    }

    private fun stopStreamingInternal(cancel: Boolean, clearCallback: Boolean) {
        clearFinalizeCloseTimer()
        isClosing = true
        isOpen = false
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

    private fun buildStreamingUrl(language: String?): String {
        return buildString {
            append(STREAMING_BASE_URL)
            append("?model=nova-3")
            append("&smart_format=true")
            append("&punctuate=true")
            append("&encoding=linear16")
            append("&sample_rate=").append(VoiceRecorder.SAMPLE_RATE)
            append("&channels=1")
            append("&interim_results=true")
            append("&vad_events=true")
            if (!language.isNullOrBlank()) {
                append("&language=").append(language)
            }
        }
    }

    private fun handleMessage(connectionToken: Long, message: String) {
        try {
            val json = JSONObject(message)
            when (json.optString("type")) {
                "Results" -> {
                    val transcript = json.optJSONObject("channel")
                        ?.optJSONArray("alternatives")
                        ?.optJSONObject(0)
                        ?.optString("transcript", "")
                        ?.trim()
                        .orEmpty()
                    val isFinal = json.optBoolean("is_final", false) ||
                        json.optBoolean("speech_final", false)
                    if (!isFinal || transcript.isBlank()) {
                        return
                    }

                    // Deduplicate identical replayed final events from reconnect/flush edges.
                    val start = json.optDouble("start", -1.0)
                    val duration = json.optDouble("duration", -1.0)
                    val fingerprint = "$start|$duration|$transcript"
                    if (fingerprint == lastFinalResultFingerprint) {
                        return
                    }
                    lastFinalResultFingerprint = fingerprint

                    Log.i(
                        TAG,
                        "VOICE_STEP_4 Deepgram final transcript (${transcript.length} chars)"
                    )
                    postIfCurrent(connectionToken) {
                        callback?.onTranscriptionResult(transcript)
                    }

                    if (isClosing) {
                        // Final result arrived after Finalize request; close immediately.
                        clearFinalizeCloseTimer()
                        webSocket?.close(1000, "client_stop")
                    }
                }

                "Metadata", "SpeechStarted", "UtteranceEnd" -> {
                    if (isClosing && json.optString("type") == "UtteranceEnd") {
                        clearFinalizeCloseTimer()
                        webSocket?.close(1000, "client_stop")
                    }
                }

                else -> {
                    val error = json.optString("error", "")
                    if (error.isNotBlank()) {
                        Log.e(TAG, "Deepgram stream error event: $error")
                        postIfCurrent(connectionToken) {
                            callback?.onStreamError(error)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Deepgram event: ${e.message}")
        }
    }

    private fun mapNetworkError(error: Throwable): String {
        return when (error) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Deepgram streaming timed out"
            is ConnectException -> "Could not connect to Deepgram streaming"
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
