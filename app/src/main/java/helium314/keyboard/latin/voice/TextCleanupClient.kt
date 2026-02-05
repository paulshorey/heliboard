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
 * Client for GPT-4.1-nano text cleanup.
 * Used to intelligently fix capitalization and punctuation in transcribed text.
 */
class TextCleanupClient {

    companion object {
        private const val TAG = "TextCleanupClient"
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
        private const val MODEL = "gpt-4.1-nano"
        
        private const val SYSTEM_PROMPT = """You are a transcription cleanup assistant. Your task is to fix capitalization and punctuation in transcribed speech.

Rules:
- Fix capitalization (sentences start with capital letters, proper nouns capitalized)
- Add or fix punctuation (periods, commas, question marks)
- Do NOT change any words or the meaning
- Do NOT add or remove any words
- Return ONLY the cleaned text, nothing else
- If the text appears to be a continuation (doesn't start a new sentence), keep the first letter lowercase"""
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
     * Clean up transcribed text using GPT-4.1-nano.
     * 
     * @param apiKey OpenAI API key
     * @param existingContext Text from last newline to cursor (context)
     * @param newText Newly transcribed text to append
     * @param callback Callback for result
     */
    fun cleanupText(
        apiKey: String,
        existingContext: String,
        newText: String,
        callback: CleanupCallback
    ) {
        val fullText = if (existingContext.isNotEmpty()) {
            "$existingContext $newText"
        } else {
            newText
        }

        val requestBody = JSONObject().apply {
            put("model", MODEL)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", fullText)
                })
            })
            put("max_tokens", 500)
            put("temperature", 0.1)  // Low temperature for consistent output
        }

        val request = Request.Builder()
            .url(API_URL)
            .addHeader("Authorization", "Bearer $apiKey")
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
                    val choices = json.optJSONArray("choices")
                    val cleanedText = choices
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content", "")
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
