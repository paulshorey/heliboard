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

## Anthropic Claude API (Text Cleanup)

### Endpoint
```
POST https://api.anthropic.com/v1/messages
Headers:
  x-api-key: <ANTHROPIC_API_KEY>
  anthropic-version: 2023-06-01
  Content-Type: application/json
```

### Request Format
```json
{
  "model": "claude-haiku-4-5-20251001",
  "max_tokens": 4096,
  "temperature": 0.0,
  "system": "<cleanup prompt from settings + fixed boundary protocol>",
  "messages": [
    {
      "role": "user",
      "content": "REFERENCE_CONTEXT (read-only):\n<<HB_REFERENCE_CONTEXT_START>>\n<optional prior paragraph context>\n<<HB_REFERENCE_CONTEXT_END>>\n\nTEXT_TO_CLEAN:\n<<HB_EDITABLE_TEXT_START>>\n<editable context + new transcript>\n<<HB_EDITABLE_TEXT_END>>"
    }
  ]
}
```

### Response Format
```json
{
  "content": [
    {
      "type": "text",
      "text": "<<HB_CLEANED_TEXT_START>>\n<cleaned text>\n<<HB_CLEANED_TEXT_END>>"
    }
  ]
}
```

---

## Settings Keys

| Key | Type | Description |
|-----|------|-------------|
| `PREF_DEEPGRAM_API_KEY` | String | Deepgram API key for transcription |
| `PREF_ANTHROPIC_API_KEY` | String | Anthropic API key for cleanup |
| `PREF_CLEANUP_PROMPT` | String | Custom cleanup instructions for Claude |
| `PREF_TRANSCRIPTION_PROMPT_PREFIX` | String | Transcription style prompt presets |
| `PREF_TRANSCRIPTION_PROMPT_SELECTED` | Int | Index of selected prompt preset |
