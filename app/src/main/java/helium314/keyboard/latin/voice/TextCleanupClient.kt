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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Client for Claude text cleanup.
 *
 * Sends the **full current paragraph** (existing context + new transcription) to
 * Claude and receives the corrected paragraph back. The caller is responsible for
 * replacing the old paragraph text with the cleaned result.
 *
 * Uses Anthropic's Claude API (claude-haiku-4-5).
 */
class TextCleanupClient {

    companion object {
        private const val TAG = "TextCleanupClient"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val ANTHROPIC_VERSION = "2023-06-01"

        /**
         * Maximum output tokens for the cleanup response.
         * Must be large enough to accommodate the full corrected paragraph,
         * since the response contains the entire paragraph (not just the new chunk).
         * 4096 tokens ≈ 3000 words — generous for any reasonable paragraph.
         */
        private const val MAX_TOKENS = 4096
    }

    interface CleanupCallback {
        fun onCleanupComplete(cleanedText: String)
        fun onCleanupError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())

    /**
     * Clean up transcribed text using Claude.
     *
     * @param apiKey Anthropic API key
     * @param systemPrompt The system prompt for cleanup instructions
     * @param existingContext Current paragraph text (from last newline to cursor).
     *                        This provides context so Claude can correct the whole paragraph.
     * @param newText Newly transcribed text to append after the existing context.
     * @param callback Callback for result (called on main thread)
     */
    fun cleanupText(
        apiKey: String,
        systemPrompt: String,
        existingContext: String,
        newText: String,
        callback: CleanupCallback
    ) {
        // Build the full text: existing paragraph context + new transcription.
        // Trim trailing whitespace from context to avoid double-spaces when the
        // previous insertion added a trailing space.
        val trimmedContext = existingContext.trimEnd()
        val fullText = if (trimmedContext.isNotEmpty()) {
            "$trimmedContext $newText"
        } else {
            newText
        }

        // Skip cleanup if no text to process
        if (fullText.isBlank()) {
            mainHandler.post { callback.onCleanupError("Empty text") }
            return
        }

        // Anthropic Claude API format
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("system", systemPrompt)  // Top-level system prompt for Anthropic
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", fullText)
                })
            })
        }

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        Log.i(TAG, "VOICE_STEP_5 send to Anthropic cleanup (${fullText.length} chars)")

        val call = client.newCall(request)
        activeCalls.add(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCalls.remove(call)
                if (call.isCanceled()) {
                    Log.i(TAG, "Cleanup request cancelled")
                    return
                }
                Log.e(TAG, "Cleanup request failed: ${e.message}")
                mainHandler.post {
                    callback.onCleanupError(mapNetworkError(e))
                }
            }

            override fun onResponse(call: Call, response: Response) {
                activeCalls.remove(call)
                try {
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Cleanup API error: ${response.code} - $responseBody")
                        val message = when (response.code) {
                            401, 403 -> "Invalid Anthropic API key"
                            408 -> "Cleanup request timed out"
                            429 -> "Anthropic rate limited — too many requests"
                            in 500..599 -> "Anthropic service error (${response.code})"
                            else -> "Cleanup API error: ${response.code}"
                        }
                        mainHandler.post {
                            callback.onCleanupError(message)
                        }
                        return
                    }

                    val json = JSONObject(responseBody ?: "{}")
                    // Anthropic response format: content[0].text
                    val content = json.optJSONArray("content")
                    val cleanedText = content
                        ?.optJSONObject(0)
                        ?.optString("text", "")
                        ?.trim()
                        ?: ""

                    if (cleanedText.isNotEmpty()) {
                        mainHandler.post {
                            callback.onCleanupComplete(cleanedText)
                        }
                    } else {
                        mainHandler.post {
                            callback.onCleanupError("Empty response from API")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing cleanup response: ${e.message}")
                    mainHandler.post {
                        callback.onCleanupError("Parse error: ${e.message}")
                    }
                }
            }
        })
    }

    /** Cancel all in-flight cleanup requests (best effort). */
    fun cancelAll() {
        val calls = synchronized(activeCalls) {
            val snapshot = activeCalls.toList()
            activeCalls.clear()
            snapshot
        }
        for (call in calls) {
            call.cancel()
        }
        if (calls.isNotEmpty()) {
            Log.i(TAG, "Cancelled ${calls.size} in-flight cleanup request(s)")
        }
    }

    private fun mapNetworkError(e: IOException): String {
        return when (e) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Cleanup request timed out"
            is ConnectException -> "Could not connect to Anthropic cleanup API"
            else -> e.message ?: "Network error"
        }
    }
}
