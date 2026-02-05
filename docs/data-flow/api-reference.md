# API Reference

Quick reference for the external APIs used in voice transcription.

## OpenAI Realtime API (Transcription)

### Connection
```
WebSocket: wss://api.openai.com/v1/realtime?intent=transcription
Headers:
  Authorization: Bearer <OPENAI_API_KEY>
  OpenAI-Beta: realtime=v1
```

### Session Configuration
```json
{
  "type": "transcription_session.update",
  "session": {
    "input_audio_format": "pcm16",
    "input_audio_transcription": {
      "model": "gpt-4o-transcribe",
      "language": "en",
      "prompt": "Capitalize first letter in every sentence. Add punctuation where necessary."
    },
    "turn_detection": {
      "type": "server_vad",
      "threshold": 0.5,
      "prefix_padding_ms": 300,
      "silence_duration_ms": 500
    },
    "input_audio_noise_reduction": {
      "type": "near_field"
    }
  }
}
```

### Audio Format
- **Encoding**: PCM16 (16-bit signed, little-endian)
- **Sample Rate**: 24kHz
- **Channels**: Mono
- **Transmission**: Base64 encoded

### Key Events
| Event Type | Direction | Description |
|------------|-----------|-------------|
| `transcription_session.update` | Send | Configure session |
| `input_audio_buffer.append` | Send | Stream audio data |
| `input_audio_buffer.speech_started` | Receive | User started speaking |
| `input_audio_buffer.speech_stopped` | Receive | User stopped speaking |
| `conversation.item.input_audio_transcription.delta` | Receive | Partial transcription |
| `conversation.item.input_audio_transcription.completed` | Receive | Final transcription |

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
  "max_tokens": 500,
  "system": "<cleanup prompt from settings>",
  "messages": [
    {
      "role": "user",
      "content": "<text to cleanup>"
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
      "text": "<cleaned text>"
    }
  ]
}
```

### Default Cleanup Prompt
```
Process this transcribed flow of consciousness. Return only the corrected text.
Do not explain. Do not return anything other than the fixed text.
Add capitalization and punctuation to complete sentences. Fix structure.
Make sure names of products such as "Claude Code" are capitalized.
Acronyms like IBKR should be uppercase.
Add or remove punctuation as needed, so the final text is grammatically correct.
If the text is a technical term, name, or file, such as "upgrade-gpt-transcribe"
or "./gradle/apk" then do not add grammatical punctuation or capitalization.
If you notice the name of any special character spelled out, such as
"slashsrcslashappslashpagedottsx" rewrite it to use the actual symbols
like this: "/src/app/page.tsx".
```

---

## Settings Keys

| Key | Type | Description |
|-----|------|-------------|
| `PREF_WHISPER_API_KEY` | String | OpenAI API key for transcription |
| `PREF_ANTHROPIC_API_KEY` | String | Anthropic API key for cleanup |
| `PREF_CLEANUP_PROMPT` | String | Custom cleanup instructions |
| `PREF_TRANSCRIBE_PROMPTS` | String | Transcription style prompts |
