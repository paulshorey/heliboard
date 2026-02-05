// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import android.util.Base64
import helium314.keyboard.latin.utils.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client for OpenAI Realtime API for streaming audio transcription.
 * Uses WebSockets to stream audio and receive real-time transcription.
 *
 * Key features:
 * - Streams audio continuously via WebSocket
 * - Server-side Voice Activity Detection (VAD)
 * - Incremental transcription results
 * - Uses gpt-4o-transcribe model
 *
 * Audio requirements:
 * - Format: PCM16 (16-bit signed, little-endian)
 * - Sample rate: 24kHz
 * - Channels: Mono
 */
class RealtimeTranscriptionClient {

    companion object {
        private const val TAG = "RealtimeTranscription"
        private const val MODEL = "gpt-4o-transcribe"
        // For transcription-only mode, use intent=transcription
        private const val REALTIME_URL = "wss://api.openai.com/v1/realtime?intent=transcription"

        // Connection timeouts
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 0L // No timeout for WebSocket reads
        private const val WRITE_TIMEOUT_SECONDS = 30L
    }

    /**
     * Callback interface for transcription events.
     */
    interface TranscriptionCallback {
        /** Called when WebSocket connection is established */
        fun onConnected()

        /** Called when session is configured and ready to receive audio */
        fun onSessionReady()

        /** Called with incremental transcription text (partial results) */
        fun onTranscriptionDelta(text: String)

        /** Called when a speech segment's transcription is complete */
        fun onTranscriptionComplete(text: String)

        /** Called when an error occurs */
        fun onError(error: String)

        /** Called when connection is closed */
        fun onDisconnected()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webSocket: WebSocket? = null
    private var callback: TranscriptionCallback? = null
    private val isConnected = AtomicBoolean(false)
    private val isSessionReady = AtomicBoolean(false)

    // Current transcription state
    private var currentTranscript = StringBuilder()
    private var currentItemId: String? = null

    // Configuration
    private var language: String? = null
    private var prompt: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Check if currently connected and ready to stream audio.
     */
    val isReady: Boolean
        get() = isConnected.get() && isSessionReady.get()

    /**
     * Connect to the Realtime API.
     *
     * @param apiKey OpenAI API key
     * @param language Optional ISO-639-1 language code
     * @param prompt Optional prompt to guide transcription
     * @param callback Callback for transcription events
     */
    fun connect(
        apiKey: String,
        language: String? = null,
        prompt: String? = null,
        callback: TranscriptionCallback
    ) {
        if (isConnected.get()) {
            Log.w(TAG, "Already connected, disconnecting first")
            disconnect()
        }

        this.callback = callback
        this.language = language
        this.prompt = prompt

        Log.i(TAG, "Connecting to Realtime API...")

        val request = Request.Builder()
            .url(REALTIME_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected")
                isConnected.set(true)
                mainHandler.post { callback.onConnected() }

                // Configure the transcription session
                configureSession()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val responseCode = response?.code ?: -1
                val responseBody = try { response?.body?.string() } catch (e: Exception) { null }
                Log.e(TAG, "WebSocket failure: code=$responseCode, message=${t.message}, body=$responseBody", t)
                isConnected.set(false)
                isSessionReady.set(false)
                mainHandler.post {
                    val errorMsg = when (responseCode) {
                        401 -> "Invalid API key"
                        403 -> "API access denied - check your API key permissions"
                        429 -> "Rate limited - too many requests"
                        else -> "Connection failed: ${t.message}"
                    }
                    callback.onError(errorMsg)
                    callback.onDisconnected()
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: $code - $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code - $reason")
                isConnected.set(false)
                isSessionReady.set(false)
                mainHandler.post { callback.onDisconnected() }
            }
        })
    }

    /**
     * Send audio data to the Realtime API.
     * Audio must be PCM16, 24kHz, mono.
     *
     * @param audioData Raw PCM audio bytes
     */
    fun sendAudio(audioData: ByteArray) {
        if (!isReady) {
            Log.w(TAG, "Cannot send audio, not ready")
            return
        }

        val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)

        val event = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", base64Audio)
        }

        webSocket?.send(event.toString())
    }

    /**
     * Commit the current audio buffer for transcription.
     * Call this when the user stops speaking (if not using server VAD).
     */
    fun commitAudio() {
        if (!isReady) {
            Log.w(TAG, "Cannot commit audio, not ready")
            return
        }

        val event = JSONObject().apply {
            put("type", "input_audio_buffer.commit")
        }

        webSocket?.send(event.toString())
        Log.i(TAG, "Audio buffer committed")
    }

    /**
     * Clear the audio buffer without transcribing.
     */
    fun clearAudio() {
        if (!isConnected.get()) {
            return
        }

        val event = JSONObject().apply {
            put("type", "input_audio_buffer.clear")
        }

        webSocket?.send(event.toString())
        Log.i(TAG, "Audio buffer cleared")
    }

    /**
     * Disconnect from the Realtime API.
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting...")
        isConnected.set(false)
        isSessionReady.set(false)
        webSocket?.close(1000, "Client disconnected")
        webSocket = null
        currentTranscript.clear()
        currentItemId = null
    }

    /**
     * Configure the transcription session with gpt-4o-transcribe model.
     * Structure: { type: "transcription_session.update", session: { ...config } }
     */
    private fun configureSession() {
        Log.i(TAG, "Configuring transcription session...")

        val sessionConfig = JSONObject().apply {
            put("type", "transcription_session.update")

            // All config goes inside "session" wrapper
            put("session", JSONObject().apply {
                // Audio format: PCM16
                put("input_audio_format", "pcm16")

                // Transcription settings
                put("input_audio_transcription", JSONObject().apply {
                    put("model", MODEL)
                    if (!language.isNullOrBlank()) {
                        put("language", language)
                    }
                    if (!prompt.isNullOrBlank()) {
                        put("prompt", prompt)
                    }
                })

                // Server-side Voice Activity Detection
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 500)
                })

                // Noise reduction
                put("input_audio_noise_reduction", JSONObject().apply {
                    put("type", "near_field")
                })
            })
        }

        val configStr = sessionConfig.toString()
        Log.i(TAG, "Sending session config: $configStr")
        webSocket?.send(configStr)
    }

    /**
     * Handle incoming WebSocket messages.
     */
    private fun handleMessage(text: String) {
        Log.d(TAG, "Received message: $text")
        try {
            val message = JSONObject(text)
            val type = message.optString("type", "")

            when (type) {
                "transcription_session.created", "transcription_session.updated" -> {
                    Log.i(TAG, "Session event: $type")
                    // Only fire onSessionReady once (on first event)
                    if (!isSessionReady.getAndSet(true)) {
                        Log.i(TAG, "Session ready, notifying callback")
                        mainHandler.post { callback?.onSessionReady() }
                    }
                }

                "input_audio_buffer.committed" -> {
                    val itemId = message.optString("item_id", "")
                    Log.i(TAG, "Audio committed, item_id: $itemId")
                    currentItemId = itemId
                    currentTranscript.clear()
                }

                "input_audio_buffer.speech_started" -> {
                    Log.i(TAG, "Speech started")
                }

                "input_audio_buffer.speech_stopped" -> {
                    Log.i(TAG, "Speech stopped")
                }

                "conversation.item.input_audio_transcription.delta" -> {
                    val delta = message.optString("delta", "")
                    if (delta.isNotEmpty()) {
                        currentTranscript.append(delta)
                        mainHandler.post { callback?.onTranscriptionDelta(delta) }
                    }
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = message.optString("transcript", "")
                    Log.i(TAG, "Transcription complete: '$transcript'")
                    mainHandler.post { callback?.onTranscriptionComplete(transcript) }
                    currentTranscript.clear()
                    currentItemId = null
                }

                "error" -> {
                    val error = message.optJSONObject("error")
                    val errorMessage = error?.optString("message") ?: "Unknown error"
                    val errorCode = error?.optString("code") ?: ""
                    Log.e(TAG, "API Error: $errorCode - $errorMessage")
                    mainHandler.post { callback?.onError(errorMessage) }
                }

                else -> {
                    Log.d(TAG, "Unhandled message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing message: ${e.message}", e)
        }
    }
}
