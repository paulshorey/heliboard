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
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Client for OpenAI Whisper API to transcribe audio files to text.
 * Uses the whisper-1 model for speech-to-text transcription.
 */
class WhisperApiClient {

    companion object {
        private const val TAG = "WhisperApiClient"
        private const val WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val MODEL = "whisper-1"
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
     * Transcribe an audio file using OpenAI Whisper API.
     *
     * @param audioFile The WAV audio file to transcribe
     * @param apiKey The OpenAI API key
     * @param language Optional language code (e.g., "en", "es", "fr"). If null, auto-detect.
     * @param callback Callback for transcription results
     */
    fun transcribe(
        audioFile: File,
        apiKey: String,
        language: String? = null,
        callback: TranscriptionCallback
    ) {
        if (apiKey.isBlank()) {
            mainHandler.post {
                callback.onTranscriptionError("OpenAI API key is not configured")
            }
            return
        }

        if (!audioFile.exists() || audioFile.length() == 0L) {
            mainHandler.post {
                callback.onTranscriptionError("Audio file is empty or doesn't exist")
            }
            return
        }

        mainHandler.post { callback.onTranscriptionStarted() }

        thread {
            try {
                val result = sendTranscriptionRequest(audioFile, apiKey, language)
                mainHandler.post { callback.onTranscriptionComplete(result) }
            } catch (e: Exception) {
                Log.e(TAG, "Transcription error: ${e.message}")
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
        val boundary = "----${UUID.randomUUID()}"
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val url = URL(WHISPER_API_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.useCaches = false
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                // Add model field
                writer.append(twoHyphens).append(boundary).append(lineEnd)
                writer.append("Content-Disposition: form-data; name=\"model\"").append(lineEnd)
                writer.append(lineEnd)
                writer.append(MODEL).append(lineEnd)

                // Add language field if specified
                if (!language.isNullOrBlank()) {
                    writer.append(twoHyphens).append(boundary).append(lineEnd)
                    writer.append("Content-Disposition: form-data; name=\"language\"").append(lineEnd)
                    writer.append(lineEnd)
                    writer.append(language).append(lineEnd)
                }

                // Add response_format field
                writer.append(twoHyphens).append(boundary).append(lineEnd)
                writer.append("Content-Disposition: form-data; name=\"response_format\"").append(lineEnd)
                writer.append(lineEnd)
                writer.append("json").append(lineEnd)

                // Write multipart header for file
                writer.append(twoHyphens).append(boundary).append(lineEnd)
                writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"").append(lineEnd)
                writer.append("Content-Type: audio/wav").append(lineEnd)
                writer.append(lineEnd)
                writer.flush()

                // Write file content
                audioFile.inputStream().use { input ->
                    input.copyTo(connection.outputStream)
                }
                connection.outputStream.flush()

                // Write closing boundary
                writer.append(lineEnd)
                writer.append(twoHyphens).append(boundary).append(twoHyphens).append(lineEnd)
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
                Log.d(TAG, "Response: $response")

                val jsonResponse = JSONObject(response)
                return jsonResponse.optString("text", "").trim()
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
                    errorJson.optJSONObject("error")?.optString("message")
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
