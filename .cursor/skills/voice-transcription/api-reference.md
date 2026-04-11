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
    "max_delay": 1.25,
    "max_delay_mode": "flexible",
    "enable_partials": false,
    "enable_entities": true,
    "operating_point": "enhanced",
    "punctuation_overrides": {
      "permitted_marks": ["all"],
      "sensitivity": 0.5
    },
    "diarization": "speaker",
    "speaker_diarization_config": {
      "max_speakers": 2,
      "prefer_current_speaker": true
    },
    "additional_vocab": [
      "HeliBoard",
      {"content": "gnocchi", "sounds_like": ["nyohki", "nokey", "nochi"]}
    ],
    "transcript_filtering_config": {
      "remove_disfluencies": true,
      "replacements": [
        {"from": "heli board", "to": "HeliBoard"},
        {"from": "/^[Oo]kay google$/", "to": "OK Google"}
      ]
    }
  }
}
```

### Key config features
- **operating_point**: Set to `"enhanced"` for best accuracy
- **output_locale**: Defaults to `en-US` for English (also supports `en-GB`, `en-AU`)
- **diarization**: When enabled, speaker labels (`S1`, `S2`, `UU`) appear in each token's `alternatives[].speaker` field. We filter to only the primary speaker (S1) to ignore background voices.
- **additional_vocab**: Custom dictionary for proper nouns, brand names, technical terms. Supports optional `sounds_like` pronunciations.
- **replacements**: Post-transcription word/regex replacements in `transcript_filtering_config`. Regex patterns use ECMAScript format with `/pattern/` delimiters.
- **punctuation_overrides**: `permitted_marks: ["all"]` enables all punctuation; `sensitivity` (0.0–1.0) controls how aggressively punctuation is inserted (default 0.5).
- **remove_disfluencies**: Removes English hesitation words (um, uh, hmm, etc.)

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
  },
  "results": [
    {
      "type": "word",
      "attaches_to": "none",
      "alternatives": [{"content": "hello", "speaker": "S1"}]
    },
    {
      "type": "punctuation",
      "attaches_to": "previous",
      "alternatives": [{"content": ".", "speaker": "S1"}]
    }
  ]
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
| `PREF_SPEECHMATICS_PUNCTUATION_SENSITIVITY_PERCENT` | Int | Speechmatics punctuation sensitivity as a percentage (default 50) |
| `PREF_SPEECHMATICS_DIARIZATION` | Boolean | Enable speaker diarization to filter to primary speaker only |
| `PREF_VOICE_CHUNK_SILENCE_SECONDS` | Int | Silence window before treating speech as paused |
| `PREF_VOICE_SILENCE_THRESHOLD` | Int | RMS threshold used for silence detection |
| `PREF_VOICE_NEW_PARAGRAPH_SILENCE_SECONDS` | Int | Silence duration before inserting a new paragraph |
| `PREF_VOICE_AUTO_STOP_SILENCE_SECONDS` | Int | Silence duration before auto-stopping recording |
