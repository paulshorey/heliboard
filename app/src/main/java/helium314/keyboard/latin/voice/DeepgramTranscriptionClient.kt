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
    }

    interface TranscriptionCallback {
        /** Transcription succeeded. [text] is the full transcript for this segment. */
        fun onTranscriptionComplete(text: String)

        /** Transcription failed. */
        fun onTranscriptionError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())

    /**
     * Transcribe a WAV audio segment using Deepgram's pre-recorded API.
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

        Log.i(TAG, "Sending ${wavData.size} bytes to Deepgram...")

        val call = client.newCall(request)
        activeCalls.add(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCalls.remove(call)
                if (call.isCanceled()) {
                    Log.i(TAG, "Transcription request cancelled")
                    return
                }
                Log.e(TAG, "Transcription request failed: ${e.message}")
                mainHandler.post {
                    callback.onTranscriptionError("Network error: ${e.message}")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                activeCalls.remove(call)
                try {
                    val body = response.body?.string()
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Deepgram API error: ${response.code} - $body")
                        val errorMsg = when (response.code) {
                            401, 403 -> "Invalid Deepgram API key"
                            429 -> "Rate limited — too many requests"
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
