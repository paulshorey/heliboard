# Whisper vs GPT-4o Audio: Quick Comparison

## TL;DR

**Whisper** = Simple transcription only, cheap, proven  
**GPT-4o Audio** = Smart transcription + understanding, expensive, new

---

## API Comparison

### Whisper Transcription API

```bash
curl https://api.openai.com/v1/audio/transcriptions \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: multipart/form-data" \
  -F file="@audio.wav" \
  -F model="whisper-1" \
  -F prompt="Use proper punctuation."
```

**Response:**

```json
{
  "text": "Hello, this is a test recording."
}
```

---

### GPT-4o Chat Completions (Audio Input)

```bash
curl https://api.openai.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -d '{
    "model": "gpt-4o-audio-preview",
    "modalities": ["text"],
    "messages": [
      {
        "role": "system",
        "content": "Transcribe with proper formatting."
      },
      {
        "role": "user",
        "content": [
          {
            "type": "input_audio",
            "input_audio": {
              "data": "'$(base64 -i audio.wav)'",
              "format": "wav"
            }
          }
        ]
      }
    ]
  }'
```

**Response:**

```json
{
  "choices": [
    {
      "message": {
        "role": "assistant",
        "content": "Hello, this is a test recording."
      }
    }
  ]
}
```

---

## Key Differences Table

| Feature              | Whisper API                | GPT-4o Audio API                      |
| -------------------- | -------------------------- | ------------------------------------- |
| **Endpoint**         | `/v1/audio/transcriptions` | `/v1/chat/completions`                |
| **Model**            | `whisper-1`                | `gpt-4o-audio-preview`                |
| **Purpose**          | Transcription only         | Understanding + transcription         |
| **Input Method**     | File upload (multipart)    | Base64 in JSON                        |
| **Max File Size**    | 25 MB                      | 25 MB                                 |
| **Request Type**     | `multipart/form-data`      | `application/json`                    |
| **Response Parse**   | `response.text`            | `response.choices[0].message.content` |
| **Prompt Support**   | Basic hint string          | Full system prompts                   |
| **Context**          | Single file only           | Conversation history                  |
| **Cost**             | $0.006/min (~$0.36/hour)   | $0.06/min (~$3.60/hour)               |
| **Speed**            | Fast (~1-2 sec for 30s)    | Slower (~2-4 sec for 30s)             |
| **Quality**          | Excellent                  | Excellent+                            |
| **Formatting**       | Basic                      | Advanced                              |
| **Language Support** | 50+ languages              | 50+ languages                         |
| **Model Updates**    | Stable                     | Preview (may change)                  |

---

## When to Use Each

### Use Whisper When:

- ✅ You need simple, accurate transcription
- ✅ Cost is a concern
- ✅ Speed is important
- ✅ You have high volume of audio
- ✅ You want a stable, proven API

### Use GPT-4o When:

- ✅ You need context-aware understanding
- ✅ You want intelligent formatting
- ✅ You need specialized output (bullet points, summaries, etc.)
- ✅ Cost is not a primary concern
- ✅ You want to process audio in conversation context

---

## Code Examples

### Whisper Implementation (Current)

```kotlin
class WhisperApiClient {
    private const val API_URL = "https://api.openai.com/v1/audio/transcriptions"
    private const val MODEL = "whisper-1"

    fun transcribe(audioFile: File, apiKey: String, callback: Callback) {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

        // Write multipart form data
        outputStream.write(audioFileBytes)

        // Read response
        val response = JSONObject(inputStream.readText())
        val text = response.getString("text")
        callback.onComplete(text)
    }
}
```

### GPT-4o Implementation (Proposed)

```kotlin
class Gpt4oAudioClient {
    private const val API_URL = "https://api.openai.com/v1/chat/completions"
    private const val MODEL = "gpt-4o-audio-preview"

    fun transcribe(audioFile: File, apiKey: String, callback: Callback) {
        // 1. Encode audio to base64
        val base64Audio = Base64.encodeToString(
            audioFile.readBytes(),
            Base64.NO_WRAP
        )

        // 2. Build JSON request
        val request = JSONObject().apply {
            put("model", MODEL)
            put("modalities", JSONArray().put("text"))
            put("messages", JSONArray().apply {
                // System message
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "Transcribe accurately with proper formatting.")
                })
                // User message with audio
                put(JSONObject().apply {
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
            })
        }

        // 3. Send POST request
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true

        connection.outputStream.write(request.toString().toByteArray())

        // 4. Parse response
        val response = JSONObject(connection.inputStream.readText())
        val text = response
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        callback.onComplete(text)
    }
}
```

---

## Testing Both APIs

### Test Script (Bash)

```bash
#!/bin/bash
AUDIO_FILE="test.wav"
API_KEY="your-api-key-here"

echo "Testing Whisper API..."
time curl https://api.openai.com/v1/audio/transcriptions \
  -H "Authorization: Bearer $API_KEY" \
  -F file="@$AUDIO_FILE" \
  -F model="whisper-1" \
  | jq '.text'

echo -e "\n\nTesting GPT-4o Audio API..."
BASE64_AUDIO=$(base64 -i "$AUDIO_FILE" | tr -d '\n')
time curl https://api.openai.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $API_KEY" \
  -d "{
    \"model\": \"gpt-4o-audio-preview\",
    \"modalities\": [\"text\"],
    \"messages\": [
      {
        \"role\": \"user\",
        \"content\": [
          {
            \"type\": \"input_audio\",
            \"input_audio\": {
              \"data\": \"$BASE64_AUDIO\",
              \"format\": \"wav\"
            }
          }
        ]
      }
    ]
  }" \
  | jq '.choices[0].message.content'
```

---

## Migration Checklist

### Before Starting

- [ ] Verify API key has access to `gpt-4o-audio-preview` model
- [ ] Test both APIs with sample audio files
- [ ] Estimate cost impact for your use case
- [ ] Decide: dual implementation or full migration

### During Development

- [ ] Keep Whisper client as fallback
- [ ] Implement base64 encoding efficiently
- [ ] Handle larger JSON payloads
- [ ] Update error handling for new response format
- [ ] Test with various audio lengths and formats

### Before Release

- [ ] Add user-facing cost warnings
- [ ] Document model selection in settings
- [ ] Test fallback mechanism
- [ ] Update all documentation
- [ ] Consider making GPT-4o opt-in

---

## Common Pitfalls

### ❌ Wrong: Using multipart with GPT-4o

```kotlin
// This won't work!
connection.setRequestProperty("Content-Type", "multipart/form-data")
```

### ✅ Correct: Using JSON with base64

```kotlin
connection.setRequestProperty("Content-Type", "application/json")
val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
```

---

### ❌ Wrong: Parsing response like Whisper

```kotlin
// This won't work for GPT-4o!
val text = response.getString("text")
```

### ✅ Correct: Parsing chat completion

```kotlin
val text = response
    .getJSONArray("choices")
    .getJSONObject(0)
    .getJSONObject("message")
    .getString("content")
```

---

### ❌ Wrong: Forgetting modalities

```json
{
  "model": "gpt-4o-audio-preview",
  // Missing modalities!
  "messages": [...]
}
```

### ✅ Correct: Specifying text output

```json
{
  "model": "gpt-4o-audio-preview",
  "modalities": ["text"],  // Important!
  "messages": [...]
}
```

---

## Performance Considerations

### Memory Usage

**Whisper (Multipart)**:

- Streams file directly
- Low memory overhead
- ✅ Good for large files

**GPT-4o (Base64)**:

- Entire file in memory
- ~33% size increase from base64
- ⚠️ Watch memory for long recordings

### Network Usage

**Whisper**:

- Raw file size
- Example: 500KB audio = 500KB upload

**GPT-4o**:

- Base64 encoded + JSON overhead
- Example: 500KB audio = ~670KB upload + JSON

### Response Time

**Whisper**:

- Usually faster
- 1-2 seconds for 30-second audio

**GPT-4o**:

- Slightly slower
- 2-4 seconds for 30-second audio
- Additional processing time for understanding

---

## Cost Calculator

### Example Usage

**Scenario**: User records 100 voice messages per day, averaging 30 seconds each

**Whisper Cost**:

```
100 messages × 0.5 minutes × $0.006/min = $0.30/day
Monthly: $9.00
Yearly: $109.50
```

**GPT-4o Cost**:

```
100 messages × 0.5 minutes × $0.06/min = $3.00/day
Monthly: $90.00
Yearly: $1,095.00
```

**Cost Increase**: 10x (900% more expensive)

---

## Recommendation

### For HeliBoard

**Strategy**: Dual Implementation

1. **Default**: Keep Whisper (cost-effective, proven)
2. **Optional**: Add GPT-4o as premium feature
3. **Settings**: Clear toggle with cost comparison
4. **Fallback**: Auto-switch to Whisper on GPT-4o errors

**Rationale**:

- Most users don't need GPT-4o's advanced features
- Cost-sensitive users can stick with Whisper
- Power users can opt into GPT-4o
- Gradual migration path
- Risk mitigation with fallback

---

## Further Reading

- [OpenAI Audio Guide](https://platform.openai.com/docs/guides/audio)
- [Chat Completions Reference](https://platform.openai.com/docs/api-reference/chat)
- [Audio Transcription Reference](https://platform.openai.com/docs/api-reference/audio)
- [Pricing](https://openai.com/pricing)

---

**Last Updated**: 2026-02-05
