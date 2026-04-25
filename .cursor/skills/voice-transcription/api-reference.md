# API Reference

Quick reference for AssemblyAI Universal-Streaming, the settings keys we ship, and the local IME data-flow handshake.

## AssemblyAI Universal-Streaming

HeliBoard uses AssemblyAI's [Universal-Streaming](https://www.assemblyai.com/docs/streaming/universal-streaming) WebSocket endpoint at:

- Default: `wss://streaming.assemblyai.com/v3/ws`
- EU: `wss://streaming.eu.assemblyai.com/v3/ws`

The session is authenticated via the `Authorization` request header (raw API key, no `Bearer` prefix). Session configuration is carried in **WebSocket connection URL query parameters**, not a JSON request body.

### Connection parameters

| Parameter | Type | Default (HeliBoard) | Description |
|-----------|------|---------------------|-------------|
| `speech_model` | string | `universal-streaming-english` | Required by AssemblyAI on every connection. Options: `universal-streaming-english`, `universal-streaming-multilingual`, `u3-rt-pro`, `whisper-rt`. |
| `sample_rate` | integer | `16000` | Must match the audio we send. |
| `format_turns` | boolean | `true` | Returns punctuated/cased/ITN-formatted text on each completed turn. |
| `end_of_turn_confidence_threshold` | number 0–1 | `0.7` | Semantic end-of-turn threshold. AssemblyAI's API default is `0.4`; we bias higher so dictation pauses don't fragment sentences. |
| `min_turn_silence` | integer ms | `600` | Silence floor before a semantic end-of-turn check fires. |
| `max_turn_silence` | integer ms | `2400` | Hard ceiling on silence before forcing the turn to end. |
| `keyterms_prompt` | JSON array of strings | seeded list | Up to 100 entries, each ≤ 50 chars. Boosts recognition of names/brands/jargon. |

We do not currently set `vad_threshold`, `inactivity_timeout`, `language_detection`, or `speaker_labels`; these can be added in `AssemblyAITranscriptionClient.SessionConfig` when needed.

### Audio format

- **Encoding**: PCM 16-bit little-endian, mono, 16 kHz
- **Transport**: Binary WebSocket frames, sent immediately once `Begin` is received
- **Headers / framing**: none — raw PCM bytes

### Server messages

```json
{ "type": "Begin", "id": "session-uuid", "expires_at": 1700000000 }
```

```json
{
  "type": "Turn",
  "turn_order": 1,
  "end_of_turn": true,
  "turn_is_formatted": true,
  "transcript": "Hello, my name is Sonny.",
  "end_of_turn_confidence": 0.92,
  "words": [ { "text": "Hello,", "word_is_final": true, "start": 0, "end": 480 }, ... ]
}
```

```json
{ "type": "Termination", "audio_duration_seconds": 12.4, "session_duration_seconds": 12.7 }
```

```json
{ "type": "Error", "error": "<reason>" }
```

We forward to the IME only `Turn` messages where `end_of_turn: true` AND (when `format_turns=true`) `turn_is_formatted: true`. This guarantees the editor never sees the unformatted draft of a turn before its formatted final version.

### Client messages

- **Audio**: raw PCM binary frames.
- **Update mid-stream** (not currently used by HeliBoard):
  ```json
  { "type": "UpdateConfiguration", "keyterms_prompt": ["account number"], "min_turn_silence": 1000 }
  ```
- **Force end-of-turn** (not currently used; AssemblyAI's semantic detector handles this for us):
  ```json
  { "type": "ForceEndpoint" }
  ```
- **Terminate** (sent on graceful stop):
  ```json
  { "type": "Terminate" }
  ```

### Common HTTP / connection errors

| Code | Meaning |
|------|---------|
| 401/403 | Invalid AssemblyAI API key |
| 429 | Rate limited |
| 5xx | AssemblyAI service failure |

### Temporary tokens

For browser-side use, AssemblyAI supports [temporary tokens](https://www.assemblyai.com/docs/streaming/authenticate-with-a-temporary-token) generated via a server POST. HeliBoard runs entirely on-device and uses the API key directly; the client preserves the option to plumb a `token` query parameter through `AssemblyAITranscriptionClient.buildConnectionUrl` if that ever becomes desirable.

---

## Settings Keys

| Key | Type | Description |
|-----|------|-------------|
| `PREF_ASSEMBLYAI_API_KEY` | String | AssemblyAI API key for transcription. |
| `PREF_ASSEMBLYAI_SPEECH_MODEL` | String | Speech model name passed to AssemblyAI (`universal-streaming-english`, `u3-rt-pro`, etc.). |
| `PREF_ASSEMBLYAI_FORMAT_TURNS` | Boolean | Request formatted final transcripts. |
| `PREF_ASSEMBLYAI_END_OF_TURN_CONFIDENCE_PERCENT` | Int | Semantic end-of-turn confidence as a 0–100 percentage. |
| `PREF_ASSEMBLYAI_MIN_TURN_SILENCE_MILLIS` | Int | `min_turn_silence` (ms). |
| `PREF_ASSEMBLYAI_MAX_TURN_SILENCE_MILLIS` | Int | `max_turn_silence` (ms). |
| `PREF_ASSEMBLYAI_USE_EU_ENDPOINT` | Boolean | Connect to the EU streaming endpoint. |
| `PREF_ASSEMBLYAI_KEYTERMS` | String | Newline (or comma) separated list of custom keyterms. |
| `PREF_VOICE_CHUNK_SILENCE_SECONDS` | Int | Local silence window before treating speech as paused. |
| `PREF_VOICE_SILENCE_THRESHOLD` | Int | RMS threshold used for local silence detection. |
| `PREF_VOICE_NEW_PARAGRAPH_SILENCE_SECONDS` | Int | Local silence duration before inserting a new paragraph. |
| `PREF_VOICE_AUTO_STOP_SILENCE_SECONDS` | Int | Local silence duration before auto-stopping recording. |

Legacy Speechmatics preference keys (`speechmatics_*`, `deepgram_api_key`) are unconditionally cleared from `SharedPreferences` the first time `TranscriptionPreferences.readAssemblyAIApiKey` runs after upgrade.
