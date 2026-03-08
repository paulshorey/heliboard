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
 * Client for Gemini text cleanup.
 *
 * Sends recent context (last ~3 sentences + new transcription) to Gemini and
 * receives the corrected text back. The caller is responsible for replacing the
 * old context in the editor with the cleaned result.
 *
 * Uses Google's Gemini API. Model is configurable (default gemini-3.1-flash-lite-preview).
 */
class TextCleanupClient {

    companion object {
        private const val TAG = "TextCleanupClient"
        private const val DEFAULT_MODEL = "gemini-3.1-flash-lite-preview"
        private fun apiUrlForModel(model: String): String {
            val m = model.trim().ifEmpty { DEFAULT_MODEL }
            return "https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent"
        }

        /**
         * Maximum output tokens for the cleanup response.
         * Must be large enough to accommodate the full corrected context,
         * since the response contains the entire context window (not just the new chunk).
         * 4096 tokens ≈ 3000 words — generous for any reasonable context window.
         */
        private const val MAX_TOKENS = 4096

        private val SAFETY_CATEGORIES = arrayOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        )

        // Hard ceiling on total call duration (DNS + connect + request + response).
        // Keep this below LatinIME's cleanup watchdog so normal OkHttp timeout
        // handling runs first in the common failure path.
        private const val CLEANUP_CALL_TIMEOUT_SECONDS = 10L
    }

    interface CleanupCallback {
        fun onCleanupComplete(cleanedText: String)
        fun onCleanupError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(CLEANUP_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val activeCalls = Collections.synchronizedSet(mutableSetOf<Call>())

    /**
     * Clean up transcribed text using Gemini.
     *
     * The context is split into two parts to protect paragraph breaks:
     * - [referenceContext]: Text from earlier paragraphs (before the last line break).
     *   Included in the system prompt so Gemini can understand the surrounding context,
     *   but Gemini is instructed not to include it in its response.
     * - [editableText]: Text in the current paragraph (after the last line break).
     *   This is the text Gemini will clean up and return. Only this portion is replaced
     *   in the editor, so paragraph breaks are never touched.
     *
     * @param apiKey Google AI API key
     * @param systemPrompt The system prompt for cleanup instructions
     * @param referenceContext Read-only context from earlier paragraphs. Goes into the
     *                         system prompt so Gemini can understand surrounding text.
     *                         Empty string when the context doesn't cross paragraph breaks.
     * @param editableText Text from the current paragraph (after last line break).
     *                      This is what Gemini cleans up and what gets replaced in the editor.
     * @param newText Newly transcribed text to append after the editable text.
     * @param model Gemini model name (e.g. gemini-3.1-flash-lite-preview). Empty = default.
     * @param callback Callback for result (called on main thread)
     */
    fun cleanupText(
        apiKey: String,
        systemPrompt: String,
        referenceContext: String,
        editableText: String,
        newText: String,
        model: String,
        callback: CleanupCallback
    ) {
        val effectiveModel = model.trim().ifEmpty { DEFAULT_MODEL }
        val requestUrl = apiUrlForModel(effectiveModel)
        // Build the user message: current paragraph + new transcription.
        // Trim trailing whitespace from editable text to avoid double-spaces
        // when the previous insertion added a trailing space.
        val trimmedEditable = editableText.trimEnd()
        val rawText = if (trimmedEditable.isNotEmpty()) {
            "$trimmedEditable $newText"
        } else {
            newText
        }

        // Wrap in XML tags to create an unambiguous boundary between
        // instructions and content. This prevents Gemini from interpreting
        // the transcribed text as a conversation or instruction, even when
        // the user is talking about transcription/AI topics.
        val userMessage = "<text_to_edit>$rawText</text_to_edit>"

        // Skip cleanup if no text to process
        if (rawText.isBlank()) {
            mainHandler.post { callback.onCleanupError("Empty text") }
            return
        }

        // Build system prompt: cleanup instructions + optional reference context.
        // The reference context gives Gemini understanding of the surrounding text
        // without being part of the editable scope.
        val fullSystemPrompt = if (referenceContext.isNotBlank()) {
            "$systemPrompt\n\nPrevious context for reference (do not include in your response):\n$referenceContext"
        } else {
            systemPrompt
        }

        // Google Gemini API format
        val requestBody = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", fullSystemPrompt)
                    })
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", userMessage)
                        })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.2)
                put("maxOutputTokens", MAX_TOKENS)
            })
            put("safetySettings", buildSafetySettings())
        }

        val request = Request.Builder()
            .url(requestUrl)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        // No retry: each queue item has a strict end-to-end timeout budget.
        enqueueWithRetry(request, callback, retriesRemaining = 0)
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
     * Enqueue [request] with optional retry policy.
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
                    Log.w(TAG, "Cleanup failed (${e.message}), retrying once...")
                    mainHandler.post {
                        enqueueWithRetry(request, callback, retriesRemaining - 1)
                    }
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
                            Log.w(TAG, "Cleanup API error ${response.code}, retrying once...")
                            mainHandler.post {
                                enqueueWithRetry(request, callback, retriesRemaining - 1)
                            }
                            return
                        }
                        Log.e(TAG, "Cleanup API error: ${response.code} - $responseBody")
                        val message = when (response.code) {
                            401, 403 -> "Invalid Google AI API key"
                            408 -> "Cleanup request timed out"
                            429 -> "Google Gemini rate limited — too many requests"
                            in 500..599 -> "Google Gemini service error (${response.code})"
                            else -> "Cleanup API error: ${response.code}"
                        }
                        mainHandler.post {
                            callback.onCleanupError(message)
                        }
                        return
                    }

                    val json = JSONObject(responseBody ?: "{}")
                    val rawCleanedText = extractCandidateText(json).trim()

                    // Extract text from <edited_text> XML tags.
                    // Falls back to the raw response if tags are missing,
                    // so the feature degrades gracefully.
                    val cleanedText = extractEditedText(rawCleanedText)

                    if (cleanedText.isNotEmpty()) {
                        mainHandler.post {
                            callback.onCleanupComplete(cleanedText)
                        }
                    } else {
                        val blockedReason = json.optJSONObject("promptFeedback")
                            ?.optString("blockReason", "")
                            ?.trim()
                        mainHandler.post {
                            callback.onCleanupError(
                                if (blockedReason.isNullOrEmpty()) {
                                    "Empty response from API"
                                } else {
                                    "Cleanup blocked by Gemini safety filters ($blockedReason)"
                                }
                            )
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

    private fun buildSafetySettings(): JSONArray {
        return JSONArray().apply {
            for (category in SAFETY_CATEGORIES) {
                put(JSONObject().apply {
                    put("category", category)
                    put("threshold", "BLOCK_NONE")
                })
            }
        }
    }

    private fun extractCandidateText(responseJson: JSONObject): String {
        val candidates = responseJson.optJSONArray("candidates") ?: return ""
        val content = candidates.optJSONObject(0)?.optJSONObject("content") ?: return ""
        val parts = content.optJSONArray("parts") ?: return ""
        val text = StringBuilder()
        for (index in 0 until parts.length()) {
            val partText = parts.optJSONObject(index)?.optString("text", "").orEmpty()
            if (partText.isNotEmpty()) {
                text.append(partText)
            }
        }
        return text.toString()
    }

    /**
     * Extract the cleaned text from `<edited_text>...</edited_text>` XML tags.
     *
     * If the model followed the instruction and wrapped its output, we extract
     * just the inner content. Otherwise we fall back to the full response text
     * so the feature degrades gracefully (e.g. custom user prompts that don't
     * include the XML tag instructions).
     */
    private fun extractEditedText(response: String): String {
        val openTag = "<edited_text>"
        val closeTag = "</edited_text>"
        val startIdx = response.indexOf(openTag)
        val endIdx = response.indexOf(closeTag)
        return if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
            response.substring(startIdx + openTag.length, endIdx).trim()
        } else {
            // Fallback: model didn't wrap response in tags — use as-is
            Log.d(TAG, "Response missing <edited_text> tags, using raw response")
            response
        }
    }

    private fun mapNetworkError(e: IOException): String {
        return when (e) {
            is UnknownHostException -> "No internet connection"
            is SocketTimeoutException -> "Cleanup request timed out"
            is ConnectException -> "Could not connect to Google Gemini cleanup API"
            else -> e.message ?: "Network error"
        }
    }
}
