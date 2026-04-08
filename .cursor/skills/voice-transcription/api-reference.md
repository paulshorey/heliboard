# API Reference

Quick reference for the external APIs and settings used in voice transcription.

## Deepgram API (Transcription)

HeliBoard uses **live WebSocket** streaming (`wss://api.deepgram.com/v1/listen`) with raw PCM16 frames (`encoding=linear16`, `sample_rate=16000`, `channels=1`). Query parameters below apply to that URL and are representative of the active integration.

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
| `model` | `nova-3` | Deepgram speech model |
| `smart_format` | `true` | Deepgram-native formatting: punctuation, capitalization, paragraphs, numerals |
| `interim_results` | `true` | Show preliminary transcripts as the user speaks (composing text) |
| `endpointing` | `300` | Ms of silence before finalizing an utterance (`speech_final=true`) |
| `vad_events` | `true` | Receive SpeechStarted events for VAD state tracking |
| `language` | `en` (optional) | ISO-639-1 language hint |

**Removed parameters:**
- `punctuate=true` — redundant, already included in `smart_format`
- `dictation=true` — converted spoken punctuation words to symbols ("question mark" → "?"), causing incorrect substitutions mid-sentence

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

## Settings Keys

| Key | Type | Description |
|-----|------|-------------|
| `PREF_DEEPGRAM_API_KEY` | String | Deepgram API key for transcription |
| `PREF_VOICE_CHUNK_SILENCE_SECONDS` | Int | Silence window before treating speech as paused |
| `PREF_VOICE_SILENCE_THRESHOLD` | Int | RMS threshold used for silence detection |
| `PREF_VOICE_NEW_PARAGRAPH_SILENCE_SECONDS` | Int | Silence duration before inserting a new paragraph |
| `PREF_VOICE_AUTO_STOP_SILENCE_SECONDS` | Int | Silence duration before auto-stopping recording |
