# API Reference

Quick reference for the external APIs used in voice transcription.

## Deepgram Pre-recorded API (Transcription)

### Endpoint
```
POST https://api.deepgram.com/v1/listen?model=nova-3&smart_format=true&punctuate=true&language=en
Headers:
  Authorization: Token <DEEPGRAM_API_KEY>
  Content-Type: audio/wav
Body: <raw WAV file bytes>
```

### Audio Format
- **Encoding**: PCM16 (16-bit signed, little-endian)
- **Sample Rate**: 16kHz
- **Channels**: Mono
- **Container**: WAV (44-byte RIFF header + PCM data)
- **Transmission**: Raw binary in request body

### Query Parameters
| Parameter | Value | Description |
|-----------|-------|-------------|
| `model` | `nova-3` | Deepgram's latest speech model |
| `smart_format` | `true` | Auto-format numbers, dates, etc. |
| `punctuate` | `true` | Add punctuation |
| `language` | `en` (optional) | ISO-639-1 language hint |

### Response Format
```json
{
  "results": {
    "channels": [
      {
        "alternatives": [
          {
            "transcript": "the transcribed text",
            "confidence": 0.98,
            "words": [...]
          }
        ]
      }
    ]
  }
}
```

### Error Codes
| Code | Meaning |
|------|---------|
| 401/403 | Invalid API key |
| 429 | Rate limited |
| 400 | Corrupt/unsupported audio format |

---

## Google Gemini API (Text Cleanup)

### Endpoint
```
POST https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent
Headers:
  x-goog-api-key: <GOOGLE_API_KEY>
  Content-Type: application/json
```

### Request Format
```json
{
  "systemInstruction": {
    "parts": [
      {
        "text": "<cleanup prompt from settings>"
      }
    ]
  },
  "contents": [
    {
      "role": "user",
      "parts": [
        {
          "text": "<text to cleanup>"
        }
      ]
    }
  ],
  "generationConfig": {
    "temperature": 0.2,
    "maxOutputTokens": 4096
  }
}
```

### Response Format
```json
{
  "candidates": [
    {
      "content": {
        "parts": [
          {
            "text": "<cleaned text>"
          }
        ]
      }
    }
  ]
}
```

---

## Settings Keys

| Key | Type | Description |
|-----|------|-------------|
| `PREF_DEEPGRAM_API_KEY` | String | Deepgram API key for transcription |
| `PREF_GOOGLE_API_KEY` | String | Google AI API key for Gemini cleanup |
| `PREF_CLEANUP_PROMPT` | String | Custom cleanup instructions for Gemini |
| `PREF_TRANSCRIPTION_PROMPT_PREFIX` | String | Transcription style prompt presets |
| `PREF_TRANSCRIPTION_PROMPT_SELECTED` | Int | Index of selected prompt preset |
