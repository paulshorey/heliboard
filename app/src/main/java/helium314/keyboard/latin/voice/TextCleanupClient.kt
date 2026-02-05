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
import java.util.concurrent.TimeUnit

/**
 * Client for Claude text cleanup.
 * Used to intelligently fix capitalization and punctuation in transcribed text.
 * Uses Anthropic's Claude API (claude-haiku-4-5).
 */
class TextCleanupClient {

    companion object {
        private const val TAG = "TextCleanupClient"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val ANTHROPIC_VERSION = "2023-06-01"
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

    /**
     * Clean up transcribed text using GPT.
     * 
     * @param apiKey OpenAI API key
     * @param systemPrompt The system prompt for cleanup instructions
     * @param existingContext Text from last newline to cursor (context)
     * @param newText Newly transcribed text to append
     * @param callback Callback for result
     */
    fun cleanupText(
        apiKey: String,
        systemPrompt: String,
        existingContext: String,
        newText: String,
        callback: CleanupCallback
    ) {
        val fullText = if (existingContext.isNotEmpty()) {
            "$existingContext $newText"
        } else {
            newText
        }
        
        Log.d(TAG, "CLEANUP_INPUT: \"$existingContext\" + \"$newText\"")

        // Anthropic Claude API format
        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 500)
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

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Cleanup request failed: ${e.message}")
                mainHandler.post {
                    // On error, return the original text (graceful degradation)
                    callback.onCleanupError(e.message ?: "Network error")
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val responseBody = response.body?.string()
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Cleanup API error: ${response.code} - $responseBody")
                        mainHandler.post {
                            callback.onCleanupError("API error: ${response.code}")
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
}
