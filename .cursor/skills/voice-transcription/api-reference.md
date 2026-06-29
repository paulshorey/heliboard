# API Reference

Quick reference for the external API and settings used in voice transcription.

## Soniox Real-Time WebSocket API (Transcription)

HeliBoard uses the Soniox **Real-Time STT WebSocket** API (`wss://stt-rt.soniox.com/transcribe-websocket`) with raw PCM16 frames. Authentication is in the JSON body, not in HTTP headers. Documentation: <https://soniox.com/docs/api-reference/stt/websocket-api>.

### Start config message (first WebSocket text frame)

```json
{
  "api_key": "<SONIOX_API_KEY>",
  "model": "stt-rt-v4",
  "audio_format": "pcm_s16le",
  "sample_rate": 16000,
  "num_channels": 1,
  "language_hints": ["en"],
  "context": {
    "terms": ["HeliBoard", "Soniox", "Kubernetes", "API", "gnocchi", "MyProject"],
    "text": "<up to 4000 chars of editor text before the cursor>"
  },
  "enable_endpoint_detection": true,
  "max_endpoint_delay_ms": 3000,
  "enable_speaker_diarization": false
}
```

### Key config fields

- **`api_key`** (required): Soniox API key. Authentication failures arrive later as JSON responses with `error_code` (`Authentication failed` etc.) and the connection closes.
- **`model`** (required): real-time STT model. HeliBoard pins `"stt-rt-v4"`.
- **`audio_format` / `sample_rate` / `num_channels`** (required for raw PCM): `pcm_s16le` / `16000` / `1` to match `VoiceRecorder` output.
- **`language_hints`** (optional): array of ISO language codes. HeliBoard sends a single-element array based on the active keyboard subtype, or omits the field entirely so Soniox auto-detects.
- **`context.terms`** (optional): recognition hints. HeliBoard sends the union of a built-in list (`HeliBoard`, `Soniox`, `Kubernetes`, `API`, `gnocchi`) and the user's custom terms from `PREF_SONIOX_CUSTOM_TERMS` (one per line, managed in `SonioxContextTermsScreen`; UI-limited to 200 terms and 100 chars per term). Merged, trimmed, and deduplicated by `SonioxTranscriptionClient.buildSessionConfig`.
- **`context.text`** (optional): free-form prior text. HeliBoard sends up to the last 4 000 characters of editor text before the cursor (`LatinIME.buildVoiceContextText` via `VoiceInputManager.setPriorTextProvider`). Soniox uses this for sentence-structure punctuation, mid-sentence casing, and proper-noun spelling. Reconnects re-fetch the prior text so the running transcript stays in context.
- **`enable_endpoint_detection`** (boolean, optional): when true, Soniox finalizes tokens once it detects the speaker has stopped talking (semantic endpointing).
- **`max_endpoint_delay_ms`** (number, optional): valid range **500–3000 ms**. Soniox API default is **2000 ms**; HeliBoard default is **3000 ms**. This is a **maximum**, not a fixed wait: semantic endpointing still finalizes earlier when the model thinks a sentence ended, so raising it does not stop premature punctuation — it only bounds the worst case. The VAD-driven manual finalize (see below) guarantees the trailing phrase is committed regardless.
- **`enable_speaker_diarization`** (boolean, optional): when true, every token includes a `speaker` field. HeliBoard uses this to lock onto the first observed speaker and drop tokens from later speakers.

Other documented fields (`enable_language_identification`, `translation`, `client_reference_id`) are not used.

### Audio format

- **Encoding**: PCM16 (16-bit signed, little-endian), mono, 16 kHz.
- **Transport**: Binary WebSocket frames after the start config has been queued.

### Server responses

A typical successful response:

```json
{
  "tokens": [
    {
      "text": "Hello",
      "start_ms": 600,
      "end_ms": 760,
      "confidence": 0.97,
      "is_final": true,
      "speaker": "1"
    }
  ],
  "final_audio_proc_ms": 760,
  "total_audio_proc_ms": 880
}
```

- **Final tokens** (`is_final: true`) are confirmed and never repeated. HeliBoard concatenates them as they arrive.
- **Non-final tokens** (`is_final: false`) are partials that update on every response. HeliBoard drops them — the IME only commits stable text.
- Token text already encodes inter-word whitespace (e.g. `"Hello"`, `" world"`, `"."`); concatenating then trimming yields correctly spaced output.
- When `enable_speaker_diarization` is on, each token carries a string `speaker` ID.

### Finished response

```json
{
  "tokens": [],
  "final_audio_proc_ms": 1560,
  "total_audio_proc_ms": 1680,
  "finished": true
}
```

Sent after the client closes the audio stream. The server then closes the WebSocket.

### Error response

```json
{
  "tokens": [],
  "error_code": 401,
  "error_type": "unauthenticated",
  "error_message": "Incorrect API key",
  "request_id": "..."
}
```

Soniox emits this and immediately closes the connection. HeliBoard surfaces it via `onStreamError`.

### Graceful stop

To end the session cleanly:

1. Stop the microphone.
2. Send an **empty WebSocket frame** (binary or text).
3. Wait for `{"finished": true}` (HeliBoard uses an 8 s grace timeout).
4. Close the socket with code 1000.

There is no per-chunk audio acknowledgement to track. (Soniox also exposes a manual finalize control message `{"type": "finalize"}` for mid-stream finalization; HeliBoard sends it on local-VAD silence and before end-of-stream to flush pending non-final tokens.)

### Special control tokens

Soniox emits two reserved markers as final tokens inside the regular `tokens` array. Raw WebSocket consumers must filter them or they leak into the transcript as literal text. HeliBoard's `SonioxTranscriptionClient` filters both via the `STREAM_MARKERS` set:

- **`<end>`** — appears once at the end of every utterance when `enable_endpoint_detection` is on. Documented at <https://soniox.com/docs/stt/rt/endpoint-detection>.
- **`<fin>`** — appears at the end of every manual finalize segment. Documented at <https://soniox.com/docs/stt/rt/manual-finalization>. HeliBoard sends `{"type": "finalize"}` on local-VAD silence and before end-of-stream (see `VoiceInputManager.requestManualFinalizeOnSilence` / `finalizeStreamingSession`), so this marker is filtered out of committed text.

The Soniox SDKs filter these via `filterSpecialTokens()`; raw WebSocket users (HeliBoard) implement the same filter explicitly.

### Common HTTP / connection errors

| Code | Meaning |
|------|---------|
| 401/403 | Invalid API key |
| 429 | Rate limited |
| 5xx | Soniox service failure |

---

## Settings Keys

| Key | Type | Description |
|-----|------|-------------|
| `PREF_SONIOX_API_KEY` | String | Soniox API key for transcription |
| `PREF_SONIOX_ENABLE_ENDPOINT_DETECTION` | Boolean | Lets Soniox finalize tokens as soon as it detects the speaker has stopped talking |
| `PREF_SONIOX_MAX_ENDPOINT_DELAY_MS` | Int | Maximum endpoint delay in ms (Soniox-documented bounds: 500–3000) |
| `PREF_SONIOX_DIARIZATION` | Boolean | Enable speaker diarization to filter to primary speaker only |
| `PREF_SONIOX_CUSTOM_TERMS` | String | User-defined `context.terms`, one per line. Merged with the built-in list at session start. |
| `PREF_VOICE_CHUNK_SILENCE_SECONDS` | Int | Silence window before treating speech as paused |
| `PREF_VOICE_SILENCE_THRESHOLD` | Int | RMS threshold used for silence detection |
| `PREF_VOICE_AUTO_STOP_SILENCE_SECONDS` | Int | Silence duration before auto-stopping recording |

`PREF_VOICE_CHUNK_SILENCE_SECONDS` and `PREF_VOICE_AUTO_STOP_SILENCE_SECONDS` drive different behaviors: chunk silence triggers `VoiceInputManager` manual finalize (`{"type":"finalize"}`) so pending tokens are committed, while auto-stop silence stops the recording session after a longer pause.
