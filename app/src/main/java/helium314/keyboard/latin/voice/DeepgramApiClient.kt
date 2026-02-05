// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import helium314.keyboard.latin.utils.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Client for Deepgram API to transcribe audio files to text.
 * Uses the Nova-3 model for speech-to-text transcription.
 */
class DeepgramApiClient {

    companion object {
        private const val TAG = "DeepgramApiClient"
        private const val DEEPGRAM_API_URL = "https://api.deepgram.com/v1/listen"
        private const val MODEL = "nova-3"
        private const val CONNECT_TIMEOUT = 30000 // 30 seconds
        private const val READ_TIMEOUT = 60000 // 60 seconds
    }

    interface TranscriptionCallback {
        fun onTranscriptionStarted()
        fun onTranscriptionComplete(text: String)
        fun onTranscriptionError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Transcribe an audio file using Deepgram API.
     *
     * @param audioFile The WAV audio file to transcribe
     * @param apiKey The Deepgram API key
     * @param language Optional language code (e.g., "en", "es", "fr"). If null, auto-detect.
     * @param callback Callback for transcription results
     */
    fun transcribe(
        audioFile: File,
        apiKey: String,
        language: String? = null,
        callback: TranscriptionCallback
    ) {
        Log.i(TAG, "transcribe() called - file: ${audioFile.absolutePath}, size: ${audioFile.length()}, apiKey length: ${apiKey.length}")

        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank")
            mainHandler.post {
                callback.onTranscriptionError("Deepgram API key is not configured")
            }
            return
        }

        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.e(TAG, "Audio file doesn't exist or is empty: exists=${audioFile.exists()}, size=${audioFile.length()}")
            mainHandler.post {
                callback.onTranscriptionError("Audio file is empty or doesn't exist")
            }
            return
        }

        Log.i(TAG, "Starting transcription thread")
        mainHandler.post { callback.onTranscriptionStarted() }

        thread {
            try {
                Log.i(TAG, "Sending transcription request...")
                val result = sendTranscriptionRequest(audioFile, apiKey, language)
                Log.i(TAG, "Transcription result received: '$result'")
                mainHandler.post { callback.onTranscriptionComplete(result) }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error: ${e.message}", e)
                mainHandler.post {
                    callback.onTranscriptionError(e.message ?: "Unknown transcription error")
                }
            }
        }
    }

    private fun sendTranscriptionRequest(
        audioFile: File,
        apiKey: String,
        language: String?
    ): String {
        Log.i(TAG, "sendTranscriptionRequest starting...")

        // Build URL with query parameters
        val urlBuilder = StringBuilder(DEEPGRAM_API_URL)
        urlBuilder.append("?model=$MODEL")
        urlBuilder.append("&smart_format=true")
        if (!language.isNullOrBlank()) {
            urlBuilder.append("&language=$language")
        }

        val url = URL(urlBuilder.toString())
        Log.i(TAG, "Connecting to: $url")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("Authorization", "Token $apiKey")
            connection.setRequestProperty("Content-Type", "audio/wav")
            Log.i(TAG, "Connection configured, sending data...")

            // Send audio file as binary data
            audioFile.inputStream().use { input ->
                connection.outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            Log.i(TAG, "Request sent, waiting for response...")
            val responseCode = connection.responseCode
            Log.i(TAG, "Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                Log.d(TAG, "Response: $response")

                val jsonResponse = JSONObject(response)
                // Navigate: results -> channels[0] -> alternatives[0] -> transcript
                val results = jsonResponse.optJSONObject("results")
                val channels = results?.optJSONArray("channels")
                val firstChannel = channels?.optJSONObject(0)
                val alternatives = firstChannel?.optJSONArray("alternatives")
                val firstAlternative = alternatives?.optJSONObject(0)
                val transcript = firstAlternative?.optString("transcript", "") ?: ""

                return transcript.trim()
            } else {
                val errorStream = connection.errorStream
                val errorResponse = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                } else {
                    "No error details"
                }
                Log.e(TAG, "API Error: $errorResponse")

                // Parse error message from response
                val errorMessage = try {
                    val errorJson = JSONObject(errorResponse)
                    errorJson.optString("err_msg")
                        ?: errorJson.optString("message")
                        ?: "API request failed with code $responseCode"
                } catch (e: Exception) {
                    "API request failed with code $responseCode"
                }

                throw Exception(errorMessage)
            }
        } finally {
            connection.disconnect()
        }
    }
}
