# GPT-4o Audio Implementation Plan

## Executive Summary

This document outlines the plan to migrate from Whisper's simple transcription API to GPT-4o's Chat Completions API with audio input. This change provides superior audio understanding, context awareness, and formatting capabilities.

## Current Implementation (Whisper)

### Architecture
```
Audio Recording → /v1/audio/transcriptions → Plain Text → Insert
```

### Characteristics
- **Endpoint**: `https://api.openai.com/v1/audio/transcriptions`
- **Model**: `whisper-1`
- **Input**: Audio file (WAV, 16kHz, mono)
- **Output**: Plain transcribed text
- **Request Type**: Multipart form-data
- **Response**: JSON with `text` field

### Current Code Flow
1. Record audio via `VoiceRecorder`
2. Save to WAV file
3. Send file to Whisper API via multipart/form-data
4. Parse `response.text`
5. Insert text into input field

---

## Target Implementation (GPT-4o Audio)

### Architecture
```
Audio Recording → Base64 Encode → /v1/chat/completions → 
Formatted Text → Insert
```

### Characteristics
- **Endpoint**: `https://api.openai.com/v1/chat/completions`
- **Model**: `gpt-4o-audio-preview`
- **Input**: Base64-encoded audio data
- **Output**: Chat completion with transcription/formatting
- **Request Type**: JSON (not multipart)
- **Response**: Chat completion message

### Key Differences

| Feature | Whisper API | GPT-4o Audio API |
|---------|-------------|------------------|
| Endpoint | `/audio/transcriptions` | `/chat/completions` |
| Model | `whisper-1` | `gpt-4o-audio-preview` |
| Input Format | Multipart file upload | JSON with base64 audio |
| Audio Format | Multiple (wav, mp3, etc.) | WAV, MP3, FLAC, etc. |
| Max Audio Size | 25 MB | 25 MB |
| Request Format | `multipart/form-data` | `application/json` |
| Response Format | `{"text": "..."}` | Chat completion object |
| Capabilities | Transcription only | Transcription + understanding + formatting |
| Prompt Support | Simple style hints | Full system/user prompts |
| Context Awareness | None | Full conversation context |
| Cost | ~$0.006/min | ~$0.06/min (10x more) |

---

## Implementation Plan

### Phase 1: Research & Preparation
**Goal**: Understand GPT-4o audio API requirements and limitations

**Tasks**:
- [ ] Review OpenAI's official GPT-4o audio documentation
- [ ] Test GPT-4o audio API with sample requests (Postman/curl)
- [ ] Verify audio format requirements (sample rate, encoding, etc.)
- [ ] Test base64 encoding of audio files
- [ ] Determine optimal chunk size for audio
- [ ] Verify API key has access to `gpt-4o-audio-preview` model

**Deliverables**:
- Working curl/HTTP examples
- Audio format specifications
- Sample request/response documentation

### Phase 2: Create New API Client
**Goal**: Build `Gpt4oAudioClient.kt` alongside existing `WhisperApiClient.kt`

**Tasks**:
- [ ] Create `Gpt4oAudioClient.kt` class
- [ ] Implement base64 audio encoding
- [ ] Build JSON request body with audio
- [ ] Implement chat completions API call
- [ ] Parse chat completion response
- [ ] Add error handling
- [ ] Add logging

**Files to Create/Modify**:
```
app/src/main/java/helium314/keyboard/latin/voice/
├── WhisperApiClient.kt          (keep as fallback)
├── Gpt4oAudioClient.kt          (new)
└── TranscriptionClient.kt       (new interface)
```

### Phase 3: Update Voice Input Manager
**Goal**: Integrate GPT-4o client while maintaining backward compatibility

**Tasks**:
- [ ] Create `TranscriptionClient` interface
- [ ] Make both clients implement interface
- [ ] Add setting to choose between Whisper/GPT-4o
- [ ] Update `VoiceInputManager` to use selected client
- [ ] Add fallback mechanism (GPT-4o fails → use Whisper)

**Files to Modify**:
```
app/src/main/java/helium314/keyboard/latin/voice/
└── VoiceInputManager.kt

app/src/main/java/helium314/keyboard/latin/settings/
├── Settings.java                (add model selection preference)
└── Defaults.kt                  (add default model preference)
```

### Phase 4: UI & Settings
**Goal**: Allow users to choose transcription model

**Tasks**:
- [ ] Add model selection preference (Whisper vs GPT-4o)
- [ ] Add cost warning for GPT-4o option
- [ ] Update transcription settings screen
- [ ] Add model indicator in UI
- [ ] Update strings.xml

**Files to Modify**:
```
app/src/main/res/values/
└── strings.xml

app/src/main/java/helium314/keyboard/settings/screens/
└── TranscriptionScreen.kt
```

### Phase 5: Testing & Optimization
**Goal**: Ensure reliability and performance

**Tasks**:
- [ ] Test with various audio lengths
- [ ] Test with different accents/languages
- [ ] Test error handling (network failures, API errors)
- [ ] Test fallback mechanism
- [ ] Optimize base64 encoding performance
- [ ] Test memory usage with long recordings
- [ ] Verify continuous recording mode works
- [ ] Test pause/resume functionality

### Phase 6: Documentation
**Goal**: Document new features and usage

**Tasks**:
- [ ] Update `VOICE_INPUT_SETUP.md`
- [ ] Document model selection
- [ ] Document cost differences
- [ ] Add troubleshooting section
- [ ] Update README if needed

---

## Technical Specifications

### GPT-4o Audio Request Format

```json
{
  "model": "gpt-4o-audio-preview",
  "modalities": ["text", "audio"],
  "audio": {
    "voice": "alloy",
    "format": "wav"
  },
  "messages": [
    {
      "role": "system",
      "content": "You are a transcription assistant. Transcribe the audio accurately with proper punctuation and formatting."
    },
    {
      "role": "user",
      "content": [
        {
          "type": "input_audio",
          "input_audio": {
            "data": "<base64-encoded-audio>",
            "format": "wav"
          }
        }
      ]
    }
  ]
}
```

### Response Format

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1234567890,
  "model": "gpt-4o-audio-preview",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Transcribed text here..."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 150,
    "completion_tokens": 50,
    "total_tokens": 200
  }
}
```

### Audio Format Requirements

- **Sample Rate**: 16kHz (recommended) or 24kHz
- **Channels**: Mono (1 channel)
- **Bit Depth**: 16-bit PCM
- **Format**: WAV (current), MP3, FLAC also supported
- **Max Size**: 25 MB per request
- **Encoding**: Base64 for API transmission

### Base64 Encoding Implementation

```kotlin
fun encodeAudioToBase64(audioFile: File): String {
    return audioFile.readBytes().let { bytes ->
        android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }
}
```

---

## Code Structure

### Interface Design

```kotlin
interface TranscriptionClient {
    fun transcribe(
        audioFile: File,
        apiKey: String,
        language: String? = null,
        prompt: String? = null,
        callback: TranscriptionCallback
    )
    
    interface TranscriptionCallback {
        fun onTranscriptionStarted()
        fun onTranscriptionComplete(text: String)
        fun onTranscriptionError(error: String)
    }
}
```

### Gpt4oAudioClient Outline

```kotlin
class Gpt4oAudioClient : TranscriptionClient {
    companion object {
        private const val API_URL = "https://api.openai.com/v1/chat/completions"
        private const val MODEL = "gpt-4o-audio-preview"
    }
    
    override fun transcribe(
        audioFile: File,
        apiKey: String,
        language: String?,
        prompt: String?,
        callback: TranscriptionCallback
    ) {
        thread {
            try {
                // 1. Encode audio to base64
                val base64Audio = encodeAudioToBase64(audioFile)
                
                // 2. Build JSON request
                val request = buildChatCompletionRequest(
                    base64Audio, 
                    language, 
                    prompt
                )
                
                // 3. Send HTTP request
                val response = sendRequest(request, apiKey)
                
                // 4. Parse response
                val text = parseResponse(response)
                
                // 5. Callback with result
                callback.onTranscriptionComplete(text)
            } catch (e: Exception) {
                callback.onTranscriptionError(e.message ?: "Unknown error")
            }
        }
    }
    
    private fun buildChatCompletionRequest(
        base64Audio: String,
        language: String?,
        prompt: String?
    ): JSONObject {
        // Build the JSON structure shown above
    }
    
    private fun sendRequest(request: JSONObject, apiKey: String): JSONObject {
        // HTTP POST with JSON body
    }
    
    private fun parseResponse(response: JSONObject): String {
        // Extract choices[0].message.content
    }
}
```

---

## Settings Configuration

### New Preferences

```kotlin
// Settings.java
public static final String PREF_TRANSCRIPTION_MODEL = "transcription_model";
public static final String PREF_TRANSCRIPTION_MODEL_WHISPER = "whisper";
public static final String PREF_TRANSCRIPTION_MODEL_GPT4O = "gpt4o";

// Defaults.kt
const val PREF_TRANSCRIPTION_MODEL = PREF_TRANSCRIPTION_MODEL_WHISPER
```

### Settings UI

```kotlin
// TranscriptionScreen.kt
RadioPreference(
    title = "Transcription Model",
    options = listOf(
        "Whisper (Fast, $0.006/min)" to Settings.PREF_TRANSCRIPTION_MODEL_WHISPER,
        "GPT-4o Audio (Best quality, $0.06/min)" to Settings.PREF_TRANSCRIPTION_MODEL_GPT4O
    ),
    selectedValue = currentModel
)
```

---

## Migration Strategy

### Approach: Dual Implementation (Recommended)

Keep both Whisper and GPT-4o clients available:

**Advantages**:
- Users can choose based on cost/quality preference
- Fallback if GPT-4o fails
- A/B testing possible
- Gradual migration

**Disadvantages**:
- More code to maintain
- Larger APK size

### Alternative: Full Migration

Replace Whisper completely with GPT-4o:

**Advantages**:
- Simpler codebase
- Best quality for all users

**Disadvantages**:
- 10x cost increase for all users
- No fallback option
- May break for some API keys

### Recommendation

**Use Dual Implementation** with Whisper as default:
1. Keep Whisper as default (cost-effective)
2. Add GPT-4o as opt-in premium option
3. Show clear cost comparison in settings
4. Auto-fallback to Whisper on GPT-4o errors

---

## Cost Analysis

### Current (Whisper)
- **Rate**: $0.006 per minute
- **30-second recording**: $0.003
- **100 recordings/day**: $0.30/day = $9/month

### GPT-4o Audio
- **Rate**: ~$0.06 per minute (estimated, 10x Whisper)
- **30-second recording**: $0.03
- **100 recordings/day**: $3/day = $90/month

### Recommendation
- Default to Whisper for cost-effectiveness
- Offer GPT-4o as premium option
- Show estimated costs in settings
- Consider hybrid: use Whisper, only use GPT-4o when user requests enhanced formatting

---

## Risk Assessment

### Technical Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| API changes | High | Medium | Keep Whisper fallback |
| Base64 memory issues | Medium | Low | Stream encoding for large files |
| Network failures | Medium | Medium | Retry logic + offline queue |
| API key lacks GPT-4o access | High | Medium | Graceful fallback to Whisper |

### User Experience Risks

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| High costs surprise users | High | High | Clear cost warnings in UI |
| Slower transcription | Medium | Low | Show "processing" indicator |
| No quality improvement | Medium | Low | User testing before release |

---

## Success Metrics

### Technical Metrics
- [ ] API success rate > 99%
- [ ] Average transcription time < 3 seconds
- [ ] Memory usage < 50MB for encoding
- [ ] No crashes related to audio processing

### User Metrics
- [ ] User satisfaction with transcription quality
- [ ] Cost acceptance (if premium feature)
- [ ] Feature adoption rate
- [ ] Support ticket volume

---

## Timeline Estimate

| Phase | Duration | Dependencies |
|-------|----------|--------------|
| Phase 1: Research | 2-3 days | Access to GPT-4o API |
| Phase 2: API Client | 3-5 days | Phase 1 complete |
| Phase 3: Integration | 2-3 days | Phase 2 complete |
| Phase 4: UI/Settings | 2-3 days | Phase 3 complete |
| Phase 5: Testing | 3-5 days | Phase 4 complete |
| Phase 6: Documentation | 1-2 days | Phase 5 complete |
| **Total** | **13-21 days** | - |

---

## Open Questions

1. **Audio Format**: Should we keep WAV or convert to MP3 for smaller payload?
2. **Chunk Size**: What's optimal audio chunk size for continuous mode?
3. **System Prompt**: Should we use different prompts for different contexts (chat, email, notes)?
4. **Caching**: Can we cache any part of the request for continuous recordings?
5. **Pricing**: Will we charge users differently for GPT-4o vs Whisper?
6. **Model Access**: Do all OpenAI API keys have access to gpt-4o-audio-preview?

---

## Next Steps

1. **Decision**: Approve this plan or request modifications
2. **Access**: Verify API key has GPT-4o audio access
3. **Testing**: Set up test environment with sample audio files
4. **Branch**: Create new branch `feature/gpt4o-audio-implementation`
5. **Start**: Begin Phase 1 (Research & Preparation)

---

## References

- OpenAI Chat Completions API: https://platform.openai.com/docs/api-reference/chat
- GPT-4o Audio Guide: https://platform.openai.com/docs/guides/audio
- OpenAI Pricing: https://openai.com/pricing
- Audio API Reference: https://platform.openai.com/docs/guides/audio

---

**Document Version**: 1.0  
**Last Updated**: 2026-02-05  
**Author**: Development Team  
**Status**: Draft - Awaiting Approval
