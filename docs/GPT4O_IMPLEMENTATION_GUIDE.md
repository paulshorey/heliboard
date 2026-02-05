# GPT-4o Audio Implementation Guide

Step-by-step guide to implementing GPT-4o audio transcription in HeliBoard.

---

## Prerequisites

### 1. API Access
```bash
# Test if your API key has GPT-4o audio access
curl https://api.openai.com/v1/chat/completions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "gpt-4o-audio-preview",
    "messages": [{"role": "user", "content": "test"}]
  }'

# Should return valid response, not "model_not_found" error
```

### 2. Test Audio File
Create a test WAV file (16kHz, mono, 16-bit PCM):
```bash
# Record 5 seconds of test audio
adb shell "media record -d 5 /sdcard/test.wav"
adb pull /sdcard/test.wav
```

---

## Step 1: Create TranscriptionClient Interface

Create a common interface both clients will implement.

**File**: `app/src/main/java/helium314/keyboard/latin/voice/TranscriptionClient.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import java.io.File

/**
 * Common interface for audio transcription clients.
 * Allows switching between Whisper and GPT-4o implementations.
 */
interface TranscriptionClient {
    
    /**
     * Transcribe an audio file to text.
     *
     * @param audioFile The audio file to transcribe
     * @param apiKey The OpenAI API key
     * @param language Optional language hint (ISO 639-1 code)
     * @param prompt Optional style/context prompt
     * @param callback Callback for transcription results
     */
    fun transcribe(
        audioFile: File,
        apiKey: String,
        language: String? = null,
        prompt: String? = null,
        callback: TranscriptionCallback
    )
    
    /**
     * Callback interface for transcription results.
     */
    interface TranscriptionCallback {
        fun onTranscriptionStarted()
        fun onTranscriptionComplete(text: String)
        fun onTranscriptionError(error: String)
    }
    
    /**
     * Get the model name (for logging/debugging).
     */
    fun getModelName(): String
}
```

---

## Step 2: Update WhisperApiClient

Make `WhisperApiClient` implement the interface.

**File**: `app/src/main/java/helium314/keyboard/latin/voice/WhisperApiClient.kt`

```kotlin
// Update class declaration
class WhisperApiClient : TranscriptionClient {
    
    // ... existing code ...
    
    override fun getModelName(): String = MODEL
    
    // Note: transcribe() already matches interface signature
}
```

---

## Step 3: Create Gpt4oAudioClient

**File**: `app/src/main/java/helium314/keyboard/latin/voice/Gpt4oAudioClient.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.voice

import android.os.Handler
import android.os.Looper
import android.util.Base64
import helium314.keyboard.latin.utils.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Client for OpenAI Chat Completions API with audio input (GPT-4o).
 * Uses gpt-4o-audio-preview model for advanced audio transcription.
 */
class Gpt4oAudioClient : TranscriptionClient {

    companion object {
        private const val TAG = "Gpt4oAudioClient"
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
        private const val MODEL = "gpt-4o-audio-preview"
        private const val CONNECT_TIMEOUT = 30000 // 30 seconds
        private const val READ_TIMEOUT = 90000 // 90 seconds (longer for GPT-4o)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun getModelName(): String = MODEL

    override fun transcribe(
        audioFile: File,
        apiKey: String,
        language: String?,
        prompt: String?,
        callback: TranscriptionClient.TranscriptionCallback
    ) {
        Log.i(TAG, "transcribe() called - file: ${audioFile.absolutePath}, size: ${audioFile.length()}")

        if (apiKey.isBlank()) {
            Log.e(TAG, "API key is blank")
            mainHandler.post {
                callback.onTranscriptionError("OpenAI API key is not configured")
            }
            return
        }

        if (!audioFile.exists() || audioFile.length() == 0L) {
            Log.e(TAG, "Audio file doesn't exist or is empty")
            mainHandler.post {
                callback.onTranscriptionError("Audio file is empty or doesn't exist")
            }
            return
        }

        // Check file size (25 MB limit, but base64 increases size by ~33%)
        val maxSizeBytes = 18 * 1024 * 1024 // 18 MB raw = ~24 MB base64
        if (audioFile.length() > maxSizeBytes) {
            Log.e(TAG, "Audio file too large: ${audioFile.length()} bytes")
            mainHandler.post {
                callback.onTranscriptionError("Audio file too large (max 18MB)")
            }
            return
        }

        Log.i(TAG, "Starting transcription thread")
        mainHandler.post { callback.onTranscriptionStarted() }

        thread {
            try {
                Log.i(TAG, "Encoding audio to base64...")
                val base64Audio = encodeAudioToBase64(audioFile)
                Log.i(TAG, "Base64 encoded, length: ${base64Audio.length}")

                Log.i(TAG, "Building request...")
                val request = buildChatCompletionRequest(base64Audio, language, prompt)

                Log.i(TAG, "Sending request to GPT-4o...")
                val result = sendChatCompletionRequest(request, apiKey)

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

    /**
     * Encode audio file to base64 string.
     */
    private fun encodeAudioToBase64(audioFile: File): String {
        return audioFile.readBytes().let { bytes ->
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }

    /**
     * Build the chat completion request JSON.
     */
    private fun buildChatCompletionRequest(
        base64Audio: String,
        language: String?,
        prompt: String?
    ): JSONObject {
        return JSONObject().apply {
            put("model", MODEL)
            put("modalities", JSONArray().put("text"))

            // Build messages array
            val messages = JSONArray()

            // System message with instructions
            val systemPrompt = buildSystemPrompt(language, prompt)
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })

            // User message with audio
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "input_audio")
                        put("input_audio", JSONObject().apply {
                            put("data", base64Audio)
                            put("format", "wav")
                        })
                    })
                })
            })

            put("messages", messages)
        }
    }

    /**
     * Build system prompt based on language and user prompt.
     */
    private fun buildSystemPrompt(language: String?, prompt: String?): String {
        val basePrompt = "You are a transcription assistant. Transcribe the audio accurately."

        val languageHint = if (!language.isNullOrBlank()) {
            " The audio is likely in $language."
        } else ""

        val customInstructions = if (!prompt.isNullOrBlank()) {
            " Additional instructions: $prompt"
        } else ""

        return basePrompt + languageHint + customInstructions
    }

    /**
     * Send HTTP request to Chat Completions API.
     */
    private fun sendChatCompletionRequest(request: JSONObject, apiKey: String): String {
        val url = URL(API_URL)
        Log.i(TAG, "Connecting to: $API_URL")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.doInput = true
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")

            // Write request body
            Log.i(TAG, "Writing request body...")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(request.toString())
            }

            Log.i(TAG, "Request sent, waiting for response...")
            val responseCode = connection.responseCode
            Log.i(TAG, "Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream))
                    .use { it.readText() }
                Log.d(TAG, "Response received, parsing...")

                return parseChatCompletionResponse(response)
            } else {
                val errorStream = connection.errorStream
                val errorResponse = if (errorStream != null) {
                    BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                } else {
                    "No error details"
                }
                Log.e(TAG, "API Error: $errorResponse")

                // Parse error message
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

    /**
     * Parse chat completion response and extract transcribed text.
     */
    private fun parseChatCompletionResponse(response: String): String {
        try {
            val jsonResponse = JSONObject(response)

            // Check for errors
            if (jsonResponse.has("error")) {
                val error = jsonResponse.getJSONObject("error")
                val message = error.optString("message", "Unknown API error")
                throw Exception(message)
            }

            // Extract text from choices[0].message.content
            val text = jsonResponse
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()

            return text
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing response: ${e.message}")
            throw Exception("Failed to parse transcription response: ${e.message}")
        }
    }
}
```

---

## Step 4: Add Model Selection Setting

### Update Settings.java

```kotlin
// app/src/main/java/helium314/keyboard/latin/settings/Settings.java

public static final String PREF_TRANSCRIPTION_MODEL = "transcription_model";
public static final String TRANSCRIPTION_MODEL_WHISPER = "whisper";
public static final String TRANSCRIPTION_MODEL_GPT4O = "gpt4o";
```

### Update Defaults.kt

```kotlin
// app/src/main/java/helium314/keyboard/latin/settings/Defaults.kt

const val PREF_TRANSCRIPTION_MODEL = Settings.TRANSCRIPTION_MODEL_WHISPER
```

---

## Step 5: Update VoiceInputManager

Modify to use selected transcription client.

```kotlin
// app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt

class VoiceInputManager(private val context: Context) {
    
    // Replace single client with dynamic selection
    private fun getTranscriptionClient(): TranscriptionClient {
        val selectedModel = context.prefs().getString(
            Settings.PREF_TRANSCRIPTION_MODEL,
            Defaults.PREF_TRANSCRIPTION_MODEL
        )
        
        return when (selectedModel) {
            Settings.TRANSCRIPTION_MODEL_GPT4O -> {
                Log.i(TAG, "Using GPT-4o transcription client")
                Gpt4oAudioClient()
            }
            else -> {
                Log.i(TAG, "Using Whisper transcription client")
                WhisperApiClient()
            }
        }
    }
    
    // Update transcribeAudioAsync to use selected client
    private fun transcribeAudioAsync(audioFile: File) {
        Log.i(TAG, "transcribeAudioAsync: file=${audioFile.absolutePath}")
        
        pendingTranscriptions.incrementAndGet()
        
        val client = getTranscriptionClient()
        Log.i(TAG, "Using ${client.getModelName()} model")
        
        val apiKey = getApiKey()
        // ... rest of existing code ...
        
        client.transcribe(
            audioFile = audioFile,
            apiKey = apiKey,
            language = language,
            prompt = prompt.ifBlank { null },
            callback = object : TranscriptionClient.TranscriptionCallback {
                // ... existing callback code ...
            }
        )
    }
}
```

---

## Step 6: Add UI Settings

### Update strings.xml

```xml
<!-- app/src/main/res/values/strings.xml -->

<!-- Transcription Model Selection -->
<string name="transcription_model_title">Transcription Model</string>
<string name="transcription_model_summary">Choose between Whisper (fast, cheap) or GPT-4o (best quality, expensive)</string>

<string name="transcription_model_whisper">Whisper</string>
<string name="transcription_model_whisper_desc">Fast and cost-effective ($0.006/min)</string>

<string name="transcription_model_gpt4o">GPT-4o Audio</string>
<string name="transcription_model_gpt4o_desc">Best quality with smart formatting ($0.06/min - 10x more expensive)</string>

<string name="transcription_cost_warning">⚠️ GPT-4o costs 10× more than Whisper</string>
```

### Update TranscriptionScreen.kt

```kotlin
// app/src/main/java/helium314/keyboard/settings/screens/TranscriptionScreen.kt

@Composable
fun TranscriptionScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    var selectedModel by remember {
        mutableStateOf(
            prefs.getString(
                Settings.PREF_TRANSCRIPTION_MODEL,
                Defaults.PREF_TRANSCRIPTION_MODEL
            ) ?: Defaults.PREF_TRANSCRIPTION_MODEL
        )
    }
    
    SettingsScaffold(
        title = stringResource(R.string.settings_screen_transcription),
        onClickBack = onClickBack
    ) {
        // API Key setting (existing)
        SettingsActivity.settingsContainer[Settings.PREF_WHISPER_API_KEY]?.Preference()
        
        // Model selection
        SettingsGroup(title = stringResource(R.string.transcription_model_title)) {
            RadioButtonPreference(
                title = stringResource(R.string.transcription_model_whisper),
                subtitle = stringResource(R.string.transcription_model_whisper_desc),
                selected = selectedModel == Settings.TRANSCRIPTION_MODEL_WHISPER,
                onClick = {
                    selectedModel = Settings.TRANSCRIPTION_MODEL_WHISPER
                    prefs.edit {
                        putString(Settings.PREF_TRANSCRIPTION_MODEL, selectedModel)
                    }
                }
            )
            
            RadioButtonPreference(
                title = stringResource(R.string.transcription_model_gpt4o),
                subtitle = stringResource(R.string.transcription_model_gpt4o_desc),
                selected = selectedModel == Settings.TRANSCRIPTION_MODEL_GPT4O,
                onClick = {
                    selectedModel = Settings.TRANSCRIPTION_MODEL_GPT4O
                    prefs.edit {
                        putString(Settings.PREF_TRANSCRIPTION_MODEL, selectedModel)
                    }
                }
            )
            
            // Warning for GPT-4o
            if (selectedModel == Settings.TRANSCRIPTION_MODEL_GPT4O) {
                TextPreference(
                    title = stringResource(R.string.transcription_cost_warning),
                    subtitle = "Make sure you understand the cost difference"
                )
            }
        }
        
        // Existing prompt settings...
    }
}
```

---

## Step 7: Testing

### Unit Test

Create `Gpt4oAudioClientTest.kt`:

```kotlin
class Gpt4oAudioClientTest {
    
    @Test
    fun testBase64Encoding() {
        val testFile = File("test_audio.wav")
        // ... test encoding ...
    }
    
    @Test
    fun testRequestFormat() {
        val client = Gpt4oAudioClient()
        // ... test request building ...
    }
    
    @Test
    fun testResponseParsing() {
        val mockResponse = """
            {
                "choices": [{
                    "message": {
                        "content": "Test transcription"
                    }
                }]
            }
        """
        // ... test parsing ...
    }
}
```

### Manual Testing

```bash
# Build and install
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/*.apk

# Test Whisper (default)
# 1. Open app
# 2. Enable keyboard
# 3. Tap microphone
# 4. Record 5 seconds
# 5. Verify text appears

# Test GPT-4o
# 1. Go to Settings > Transcription
# 2. Select GPT-4o Audio
# 3. Record 5 seconds
# 4. Verify text appears
# 5. Check logs for "Using gpt-4o-audio-preview model"

# Check logs
adb logcat | grep -E "(WhisperApiClient|Gpt4oAudioClient|VoiceInputManager)"
```

---

## Step 8: Error Handling & Fallback

Add automatic fallback to Whisper if GPT-4o fails:

```kotlin
// In VoiceInputManager.kt

private fun transcribeAudioAsync(audioFile: File) {
    val primaryClient = getTranscriptionClient()
    
    primaryClient.transcribe(
        audioFile = audioFile,
        apiKey = apiKey,
        language = language,
        prompt = prompt.ifBlank { null },
        callback = object : TranscriptionClient.TranscriptionCallback {
            override fun onTranscriptionError(error: String) {
                // If GPT-4o fails, try Whisper as fallback
                if (primaryClient is Gpt4oAudioClient) {
                    Log.w(TAG, "GPT-4o failed, falling back to Whisper: $error")
                    
                    val fallbackClient = WhisperApiClient()
                    fallbackClient.transcribe(
                        audioFile, apiKey, language, prompt,
                        callback = this // Use same callback
                    )
                } else {
                    // Whisper also failed, give up
                    Log.e(TAG, "Transcription failed: $error")
                    mainHandler.post {
                        listener?.onError(error)
                        notifyStateChange()
                    }
                }
            }
            
            // ... other callback methods ...
        }
    )
}
```

---

## Step 9: Documentation

Update `VOICE_INPUT_SETUP.md`:

```markdown
## Model Selection

HeliBoard supports two transcription models:

### Whisper (Default)
- Fast and cost-effective
- Excellent accuracy
- $0.006 per minute
- Recommended for most users

### GPT-4o Audio (Premium)
- Best-in-class quality
- Advanced formatting
- Context awareness
- $0.06 per minute (10x more expensive)
- Recommended for professional use

To change models:
1. Open HeliBoard Settings
2. Go to Transcription
3. Select your preferred model
```

---

## Step 10: Commit and Test

```bash
# Create new branch
git checkout -b feature/gpt4o-audio-implementation

# Add all files
git add app/src/main/java/helium314/keyboard/latin/voice/TranscriptionClient.kt
git add app/src/main/java/helium314/keyboard/latin/voice/Gpt4oAudioClient.kt
git add app/src/main/java/helium314/keyboard/latin/voice/WhisperApiClient.kt
git add app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt
git add app/src/main/java/helium314/keyboard/latin/settings/Settings.java
git add app/src/main/java/helium314/keyboard/settings/screens/TranscriptionScreen.kt
git add app/src/main/res/values/strings.xml

# Commit
git commit -m "Add GPT-4o audio transcription support

- Create TranscriptionClient interface
- Implement Gpt4oAudioClient with base64 encoding
- Add model selection in settings
- Implement automatic fallback to Whisper
- Update UI with model selection and cost warnings
- Maintain backward compatibility with Whisper"

# Push
git push -u origin feature/gpt4o-audio-implementation
```

---

## Troubleshooting

### Error: "Model not found"
```
Solution: Your API key doesn't have access to gpt-4o-audio-preview yet.
Either wait for access or stick with Whisper.
```

### Error: "Request entity too large"
```
Solution: Audio file is too large after base64 encoding.
Reduce chunk size in continuous recording mode.
```

### Error: "Invalid base64"
```
Solution: Check Base64.NO_WRAP flag is set.
Newlines in base64 will break the JSON request.
```

### Slow performance
```
Solution: GPT-4o takes longer than Whisper.
This is expected. Show "processing" indicator to user.
```

---

## Next Steps

1. ✅ Test with various audio lengths
2. ✅ Test with different languages
3. ✅ Monitor API costs in production
4. ✅ Gather user feedback
5. ✅ Consider adding usage analytics
6. ✅ Maybe add cost estimator in UI

---

**Implementation Complete!** 🎉

Users can now choose between Whisper and GPT-4o based on their needs and budget.
