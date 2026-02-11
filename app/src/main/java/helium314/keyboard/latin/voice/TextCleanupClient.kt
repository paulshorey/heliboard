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
 * Sends recent context (last ~3 sentences + new transcription) to Claude and
 * receives the corrected text back. The caller is responsible for replacing the
 * old context in the editor with the cleaned result.
 *
 * Automatically retries once on transient failures (5xx, 408, timeout,
 * connection error).
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
         * Must be large enough to accommodate the full corrected context,
         * since the response contains the entire context window (not just the new chunk).
         * 4096 tokens ≈ 3000 words — generous for any reasonable context window.
         */
        private const val MAX_TOKENS = 4096

        /** Delay before a single retry on transient failures. */
        private const val RETRY_DELAY_MS = 2000L
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
     * The context is split into two parts to protect paragraph breaks:
     * - [referenceContext]: Text from earlier paragraphs (before the last line break).
     *   Included in the system prompt so Claude can understand the surrounding context,
     *   but Claude is instructed not to include it in its response.
     * - [editableText]: Text in the current paragraph (after the last line break).
     *   This is the text Claude will clean up and return. Only this portion is replaced
     *   in the editor, so paragraph breaks are never touched.
     *
     * @param apiKey Anthropic API key
     * @param systemPrompt The system prompt for cleanup instructions
     * @param referenceContext Read-only context from earlier paragraphs. Goes into the
     *                         system prompt so Claude can understand surrounding text.
     *                         Empty string when the context doesn't cross paragraph breaks.
     * @param editableText Text from the current paragraph (after last line break).
     *                      This is what Claude cleans up and what gets replaced in the editor.
     * @param newText Newly transcribed text to append after the editable text.
     * @param callback Callback for result (called on main thread)
     */
    fun cleanupText(
        apiKey: String,
        systemPrompt: String,
        referenceContext: String,
        editableText: String,
        newText: String,
        callback: CleanupCallback
    ) {
        // Build the user message: current paragraph + new transcription.
        // Trim trailing whitespace from editable text to avoid double-spaces
        // when the previous insertion added a trailing space.
        val trimmedEditable = editableText.trimEnd()
        val userMessage = if (trimmedEditable.isNotEmpty()) {
            "$trimmedEditable $newText"
        } else {
            newText
        }

        // Skip cleanup if no text to process
        if (userMessage.isBlank()) {
            mainHandler.post { callback.onCleanupError("Empty text") }
            return
        }

        // Build system prompt: cleanup instructions + optional reference context.
        // The reference context gives Claude understanding of the surrounding text
        // without being part of the editable scope.
        val fullSystemPrompt = if (referenceContext.isNotBlank()) {
            "$systemPrompt\n\nPrevious context for reference (do not include in your response):\n$referenceContext"
        } else {
            systemPrompt
        }

        // Anthropic Claude API format
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("system", fullSystemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
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

        Log.i(
            TAG,
            "VOICE_STEP_5 send to Anthropic cleanup " +
                "(editable=${userMessage.length} chars, reference=${referenceContext.length} chars)"
        )

        enqueueWithRetry(request, callback, retriesRemaining = 1)
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

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Enqueue [request] with automatic single retry on transient failures.
     */
    private fun enqueueWithRetry(
        request: Request,
        callback: CleanupCallback,
        retriesRemaining: Int
    ) {
        val call = client.newCall(request)
        activeCalls.add(call)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCalls.remove(call)
                if (call.isCanceled()) {
                    Log.i(TAG, "Cleanup request cancelled")
                    return
                }
                if (retriesRemaining > 0 && isRetryableError(e)) {
                    Log.w(TAG, "Cleanup failed (${e.message}), retrying in ${RETRY_DELAY_MS}ms...")
                    mainHandler.postDelayed({
                        enqueueWithRetry(request, callback, retriesRemaining - 1)
                    }, RETRY_DELAY_MS)
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
                        if (retriesRemaining > 0 && isRetryableStatus(response.code)) {
                            Log.w(TAG, "Cleanup API error ${response.code}, retrying in ${RETRY_DELAY_MS}ms...")
                            mainHandler.postDelayed({
                                enqueueWithRetry(request, callback, retriesRemaining - 1)
                            }, RETRY_DELAY_MS)
                            return
                        }
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

    /** Whether the IOException is a transient network error worth retrying. */
    private fun isRetryableError(e: IOException): Boolean {
        return e is SocketTimeoutException || e is ConnectException
    }

    /** Whether the HTTP status code indicates a transient server error worth retrying. */
    private fun isRetryableStatus(code: Int): Boolean {
        return code == 408 || code in 500..599
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
