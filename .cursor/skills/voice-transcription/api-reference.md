# API Reference

Quick reference for the external APIs and settings used in voice transcription.

## Speechmatics Realtime API (Transcription)

HeliBoard uses the Speechmatics **Realtime WebSocket** API (`wss://eu.rt.speechmatics.com/v2/`) with raw PCM16 frames. The websocket is authenticated with `Authorization: Bearer <API_KEY>`, then initialized with a `StartRecognition` JSON message.

### StartRecognition payload
```json
{
  "message": "StartRecognition",
  "audio_format": {
    "type": "raw",
    "encoding": "pcm_s16le",
    "sample_rate": 16000
  },
  "transcription_config": {
    "language": "en",
    "output_locale": "en-US",
    "max_delay": 1.2,
    "max_delay_mode": "flexible",
    "enable_partials": false,
    "enable_entities": false,
    "conversation_config": {
      "end_of_utterance_silence_trigger": 0.9
    },
    "transcript_filtering_config": {
      "remove_disfluencies": true
    }
  }
}
```

### Audio format
- **Encoding**: PCM16 (16-bit signed, little-endian), mono, 16 kHz
- **Transport**: Binary websocket frames after `RecognitionStarted`

### Important received messages
```json
{
  "message": "RecognitionStarted"
}
```

```json
{
  "message": "AudioAdded",
  "seq_no": 42
}
```

```json
{
  "message": "AddTranscript",
  "metadata": {
    "start_time": 0.0,
    "end_time": 1.2,
    "transcript": "hello world"
  }
}
```

```json
{
  "message": "EndOfTranscript"
}
```

### Graceful stop
- Client can send `ForceEndOfUtterance` before ending the stream to flush the tail of an utterance sooner
- Client keeps track of `AudioAdded.seq_no`
- On stop, once all sent audio chunks are acknowledged, client sends:

```json
{
  "message": "EndOfStream",
  "last_seq_no": 42
}
```

### Common HTTP / connection errors
| Code | Meaning |
|------|---------|
| 401/403 | Invalid API key |
| 429 | Rate limited |
| 5xx | Speechmatics service failure |

---

## Settings Keys

| Key | Type | Description |
|-----|------|-------------|
| `PREF_SPEECHMATICS_API_KEY` | String | Speechmatics API key for transcription |
| `PREF_SPEECHMATICS_MAX_DELAY_MILLIS` | Int | Final transcript latency target in milliseconds |
| `PREF_SPEECHMATICS_END_OF_UTTERANCE_MILLIS` | Int | Speechmatics server-side end-of-utterance trigger in milliseconds |
| `PREF_SPEECHMATICS_REMOVE_DISFLUENCIES` | Boolean | Removes English hesitation words like "um" and "uh" |
| `PREF_VOICE_CHUNK_SILENCE_SECONDS` | Int | Silence window before treating speech as paused |
| `PREF_VOICE_SILENCE_THRESHOLD` | Int | RMS threshold used for silence detection |
| `PREF_VOICE_NEW_PARAGRAPH_SILENCE_SECONDS` | Int | Silence duration before inserting a new paragraph |
| `PREF_VOICE_AUTO_STOP_SILENCE_SECONDS` | Int | Silence duration before auto-stopping recording |
