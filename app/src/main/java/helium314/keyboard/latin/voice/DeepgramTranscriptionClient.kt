// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import java.net.ConnectException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Client for Deepgram's pre-recorded (batch) transcription API.
 *
 * Sends a WAV audio file via POST /v1/listen and receives the complete
 * transcription in the response. No streaming, no WebSocket — just a
 * simple HTTP request per audio segment.
 *
 * API docs: https://developers.deepgram.com/docs/pre-recorded-audio
 */
class DeepgramTranscriptionClient {

    companion object {
        private const val TAG = "DeepgramTranscription"
        private const val BASE_URL = "https://api.deepgram.com/v1/listen"

        /** Delay before a single retry on transient failures. */
        private const val RETRY_DELAY_MS = 2000L
    }

    interface TranscriptionCallback {
        /** Transcription succeeded. [text] is the full transcript for this segment. */
        fun onTranscriptionComplete(text: String)

        /** Transcription failed. */
        fun onTranscriptionError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)    // fail fast — the transcription queue is sequential,
                                              // so a stuck request blocks every subsequent chunk.
                                              // Better to drop one chunk and move on.
        .build()

    private val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())

    /**
     * Transcribe a WAV audio segment using Deepgram's pre-recorded API.
     *
     * Retries once on transient **server** failures (5xx, 408) where the server
     * responded quickly with an error. Network-level failures (timeouts,
     * connection errors) are NOT retried — the queue is sequential, so a
     * stuck request blocks every subsequent chunk. Better to drop one chunk
     * and move on immediately.
     *
     * @param apiKey   Deepgram API key
     * @param wavData  Complete WAV file bytes (header + PCM data)
     * @param language Optional ISO-639-1 language code (e.g. "en"). Null = auto-detect.
     * @param callback Result callback (called on main thread)
     */
    fun transcribe(
        apiKey: String,
        wavData: ByteArray,
        language: String? = null,
        callback: TranscriptionCallback
    ) {
        // Build query parameters
        val urlBuilder = StringBuilder(BASE_URL)
        urlBuilder.append("?model=nova-3")
        urlBuilder.append("&smart_format=true")
        urlBuilder.append("&punctuate=true")
        if (!language.isNullOrBlank()) {
            urlBuilder.append("&language=").append(language)
        }

        val requestBody = wavData.toRequestBody("audio/wav".toMediaType())

        val request = Request.Builder()
            .url(urlBuilder.toString())
            .addHeader("Authorization", "Token $apiKey")
            .post(requestBody)
            .build()

        Log.i(
            TAG,
            "VOICE_STEP_3 send to Deepgram (${wavData.size} bytes, language=${language ?: "auto"})"
        )

        enqueueWithRetry(request, callback, retriesRemaining = 1)
    }

    /** Cancel all in-flight transcription requests (best effort). */
    fun cancelAll() {
        val calls = synchronized(activeCalls) {
            val snapshot = activeCalls.toList()
            activeCalls.clear()
            snapshot
        }
        for (call in calls) {
            call.cancel()
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Enqueue [request] with automatic single retry on transient failures.
     *
     * On a retryable error the same [Request] object is re-used (OkHttp requests are
     * immutable value objects, and byte-array request bodies can be written multiple times).
     */
    private fun enqueueWithRetry(
        request: Request,
        callback: TranscriptionCallback,
        retriesRemaining: Int
    ) {
        val call = client.newCall(request)
        activeCalls.add(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCalls.remove(call)
                if (call.isCanceled()) {
                    Log.i(TAG, "Transcription request cancelled")
                    return
                }
                // Don't retry network failures (timeout, connection error) — the queue
                // is sequential, so retrying blocks every subsequent chunk. Fail fast.
                Log.e(TAG, "Transcription request failed: ${e.message}")
                mainHandler.post {
                    callback.onTranscriptionError(mapNetworkError(e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                activeCalls.remove(call)
                try {
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        if (retriesRemaining > 0 && isRetryableStatus(response.code)) {
                            Log.w(TAG, "Deepgram API error ${response.code}, retrying in ${RETRY_DELAY_MS}ms...")
                            mainHandler.postDelayed({
                                enqueueWithRetry(request, callback, retriesRemaining - 1)
                            }, RETRY_DELAY_MS)
                            return
                        }
                        Log.e(TAG, "Deepgram API error: ${response.code} - $body")
                        val errorMsg = when (response.code) {
                            401, 403 -> "Invalid Deepgram API key"
                            429 -> "Rate limited — too many requests"
                            408 -> "Deepgram request timed out"
                            in 500..599 -> "Deepgram service error (${response.code})"
                            else -> "API error ${response.code}"
                        }
                        mainHandler.post { callback.onTranscriptionError(errorMsg) }
                        return
                    }

                    val transcript = parseTranscript(body)
                    Log.i(TAG, "Transcription result: \"$transcript\"")
                    mainHandler.post { callback.onTranscriptionComplete(transcript) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing response: ${e.message}")
                    mainHandler.post {
                        callback.onTranscriptionError("Parse error: ${e.message}")
                    }
                }
            }
        })
    }

    /** Whether the HTTP status code indicates a transient server error worth retrying. */
    private fun isRetryableStatus(code: Int): Boolean {
        return code == 408 || code in 500..599
    }

    private fun mapNetworkError(e: IOException): String {
        return when (e) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Transcription request timed out"
            is ConnectException -> "Could not connect to Deepgram"
            else -> "Network error: ${e.message ?: "unknown"}"
        }
    }

    /**
     * Parse the transcript text from Deepgram's JSON response.
     *
     * Response structure:
     * ```json
     * {
     *   "results": {
     *     "channels": [{
     *       "alternatives": [{
     *         "transcript": "the transcribed text",
     *         "confidence": 0.98
     *       }]
     *     }]
     *   }
     * }
     * ```
     */
    private fun parseTranscript(body: String?): String {
        if (body.isNullOrEmpty()) return ""
        val json = JSONObject(body)
        val results = json.optJSONObject("results") ?: return ""
        val channels = results.optJSONArray("channels") ?: return ""
        if (channels.length() == 0) return ""
        val channel = channels.getJSONObject(0)
        val alternatives = channel.optJSONArray("alternatives") ?: return ""
        if (alternatives.length() == 0) return ""
        return alternatives.getJSONObject(0).optString("transcript", "")
    }
}
