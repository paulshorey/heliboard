# API Reference

Quick reference for the external APIs used in voice transcription.

## Deepgram API (Transcription)

HeliBoard uses **live WebSocket** streaming (`wss://api.deepgram.com/v1/listen`) with raw PCM16 frames (`encoding=linear16`, `sample_rate=16000`, `channels=1`). Query parameters below apply to that URL (and are representative for REST batch calls too).

### Batch endpoint (reference)
```
POST https://api.deepgram.com/v1/listen?model=nova-3&smart_format=false&punctuate=false&endpointing=1000&language=en
Headers:
  Authorization: Token <DEEPGRAM_API_KEY>
  Content-Type: audio/wav
Body: <raw WAV file bytes>
```

### Audio format (streaming)
- **Encoding**: PCM16 (16-bit signed, little-endian), mono, 16 kHz
- **Transport**: Binary frames on the WebSocket (no WAV header per chunk)

### Audio format (batch)
- **Container**: WAV (44-byte RIFF header + PCM data) in POST body

### Query Parameters
| Parameter | Value | Description |
|-----------|-------|-------------|
| `model` | `nova-3` | Deepgram's latest speech model |
| `smart_format` | `false` | Leave formatting to the cleanup step |
| `punctuate` | `false` | Leave punctuation to the cleanup step |
| `endpointing` | `1000` | Ms of silence before finalizing a streaming span (live WebSocket) |
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

## OpenAI API (Text Cleanup)

### Endpoint
```
POST https://api.openai.com/v1/chat/completions
Headers:
  Authorization: Bearer <OPENAI_API_KEY>
  Content-Type: application/json
```

Cleanup uses the Responses API with `text.format` JSON schema (`edited_text` string). See `TextCleanupClient.kt` for the full payload (`instructions`, `input`, `reasoning.effort=low`, `text.verbosity=low`, `max_output_tokens`).

---

## Settings Keys

| Key | Type | Description |
|-----|------|-------------|
| `PREF_DEEPGRAM_API_KEY` | String | Deepgram API key for transcription |
| `PREF_OPENAI_API_KEY` | String | OpenAI API key for text cleanup |
| `PREF_OPENAI_MODEL` | String | OpenAI model name (default `gpt-5.4`) |
| `PREF_CLEANUP_PROMPT` | String | User-editable cleanup instructions (merged into system framing) |
| `PREF_TRANSCRIPTION_PROMPT_PREFIX` | String | Prefix for transcription style prompt preset keys |
| `PREF_TRANSCRIPTION_PROMPT_SELECTED` | Int | Index of selected prompt preset |
