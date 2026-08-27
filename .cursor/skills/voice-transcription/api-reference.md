# API Reference

Quick reference for the external API and settings used in voice transcription.

For general Gemini Live API guidance (all models, SDKs, non-transcription
features), read the `gemini-live-api-dev` skill and query the Gemini Docs MCP.
This file records only what HeliBoard sends and expects.

## Gemini Live API (streaming transcription)

HeliBoard uses the **Live API bidirectional stream** with the dedicated real-time
speech-to-text model `gemini-3.5-transcribe-live`. Documentation:
<https://ai.google.dev/gemini-api/docs/live-api/live-transcribe> and
<https://ai.google.dev/api/live>.

- **Endpoint**:
  `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<GEMINI_API_KEY>`
- **Authentication**: API key in the query string. No HTTP header, no body field.
- **Framing**: JSON **text** frames in both directions. Audio is base64 inside
  JSON, not sent as binary frames.
- **Handshake**: the first client message must be `setup`, and the client must wait
  for `setupComplete` before sending anything else.

### Setup message (first WebSocket text frame)

```json
{
  "setup": {
    "model": "models/gemini-3.5-transcribe-live",
    "generationConfig": {
      "responseModalities": ["TEXT"]
    },
    "inputAudioTranscription": {
      "languageCodes": ["en-US"],
      "mode": "SMART",
      "customVocabulary": ["HeliBoard", "Gemini", "Kubernetes", "API", "gnocchi", "MyProject"]
    },
    "realtimeInputConfig": {
      "automaticActivityDetection": {
        "disabled": false,
        "startOfSpeechSensitivity": "START_SENSITIVITY_HIGH",
        "prefixPaddingMs": 300,
        "endOfSpeechSensitivity": "END_SENSITIVITY_LOW",
        "silenceDurationMs": 1500
      }
    },
    "systemInstruction": {
      "parts": [{ "text": "<static dictation guidance, no editor text>" }]
    }
  }
}
```

### Key config fields

- **`model`** (required): `models/{model}`. The `models/` prefix **is** required on
  the raw socket even though the SDKs accept a bare id.
- **`generationConfig.responseModalities`** (required): `["TEXT"]` for this model.
  `AUDIO` is for the conversational Live Agent models, and only one modality per
  session is allowed — `["AUDIO","TEXT"]` is a 1007.
- **`inputAudioTranscription`** (required to get transcripts): a **sibling of
  `generationConfig`**, not a child. Nesting it inside `generationConfig` closes
  the socket with `1007 Invalid JSON payload received. Unknown name
  "inputAudioTranscription" at 'setup.generation_config'`. It is also an **object**,
  never a boolean.
  - **`languageCodes`**: array of BCP-47 codes from the model's supported list.
    HeliBoard sends one code derived from the active keyboard subtype via
    `GeminiTranscriptionClient.resolveLanguageCode`, or `[]` for auto-detection
    (when the user opts in, or when no supported code matches the subtype). Google
    recommends an explicit hint because auto-detection misfires on short prompts.
  - **`mode`**: `"VERBATIM"` (API default) or `"SMART"`. HeliBoard defaults to
    `SMART`: disfluency removal, inline self-correction resolution, list/number/date
    formatting, grammar and casing polish.
  - **`customVocabulary`**: up to 1 000 phrases, best results around 100. HeliBoard
    sends at most **100**: the user's terms from `PREF_GEMINI_CUSTOM_VOCABULARY`
    (one per line, UI-limited to 200 terms and 100 chars each), then the built-in
    list (`HeliBoard`, `Gemini`, `Kubernetes`, `API`, `gnocchi`), then proper nouns
    harvested from editor text by `VoiceContextVocabulary`.
- **`realtimeInputConfig.automaticActivityDetection`**: server VAD. HeliBoard keeps
  it **enabled** (`disabled: false`) so speech onset keeps its prefix padding, and
  tunes only the end of speech.
  - **`silenceDurationMs`**: non-speech duration before end-of-speech is committed.
    Larger tolerates longer gaps at the cost of latency. HeliBoard clamps to
    **400–5000 ms**, default **1500 ms**. Google warns that 100–200 ms values split
    one utterance into fragments and that the model then loses cross-fragment
    context, lowering transcription quality.
  - **`endOfSpeechSensitivity`**: `END_SENSITIVITY_HIGH` (API default) ends speech
    more often; `END_SENSITIVITY_LOW` ends it less often. HeliBoard pins **LOW**.
  - **`startOfSpeechSensitivity`**: HeliBoard pins **`START_SENSITIVITY_HIGH`** to
    detect onset eagerly.
  - **`prefixPaddingMs`**: speech duration required before start-of-speech is
    committed; the server retains this much prefix audio, which is what prevents
    front-word truncation. HeliBoard sends **300**.
- **`systemInstruction`**: short static dictation guidance. Live Transcription's
  documented feature list does not include system instructions, so this may be
  silently ignored; it therefore lives in the highest `SetupTier` only.

Not used: `tools`, `speechConfig`, `outputAudioTranscription`, `translationConfig`,
`proactivity`, `historyConfig`, `contextWindowCompression`, `sessionResumption`.
Sessions are capped at 10 minutes and each dictation utterance is independent, so
HeliBoard rotates connections rather than resuming them. `activityHandling` and
`turnCoverage` are conversational-agent concepts and are left at their defaults.

### Setup tiers

`GeminiTranscriptionClient.SetupTier` renders four variants of the payload above,
retrying one lower when the server closes with 1007:

| Tier | Contents |
|---|---|
| `FULL` | everything above |
| `NO_SYSTEM_INSTRUCTION` | drops `systemInstruction` |
| `NO_REALTIME_CONFIG` | also drops `realtimeInputConfig` |
| `MINIMAL` | `model` + `generationConfig.responseModalities` + `inputAudioTranscription.languageCodes` |

`MINIMAL` matches the documented quickstart exactly. The working tier is cached in
`negotiatedSetupTier` for the process. Run
`tools/gemini-live-smoke-test.py --probe-setup` with a real key to see which tiers
the server accepts today.

### Audio format

- **Encoding**: PCM16 (16-bit signed, little-endian), mono, 16 kHz — the model's
  native input format.
- **MIME type**: `audio/pcm;rate=16000`.
- **Chunk size**: 100 ms (3 200 bytes at 16 kHz mono PCM16), matching
  `VoiceRecorder`'s read interval and Google's documented guidance.
- **Transport**: base64 in a JSON text frame. Base64 must be unwrapped —
  HeliBoard uses okio's `ByteString.base64()`, and on Android
  `Base64.encodeToString` would need `Base64.NO_WRAP`.

```json
{"realtimeInput":{"audio":{"data":"<base64 PCM16>","mimeType":"audio/pcm;rate=16000"}}}
```

`realtimeInput.mediaChunks` is deprecated; all but the first entry are ignored.

### Turn finalize (Hybrid VAD)

```json
{"realtimeInput":{"audioStreamEnd":true}}
```

The server treats this as immediate turn finalization, bypassing its silence wait,
with server VAD as fallback if the client's detector misses. **The session stays
open** — "the client can reopen the stream by sending an audio message" — so
HeliBoard uses it after local silence, on mic pause, and on stop. It is only valid
while automatic activity detection is enabled.

Manual VAD (`activityStart` / `activityEnd`, both empty objects) requires
`automaticActivityDetection.disabled: true` and gives up the server's pre-speech
buffer, so HeliBoard does not use it.

### Server responses

```json
{"setupComplete":{}}
{"serverContent":{"interimInputTranscription":{"text":"hello wor"}}}
{"serverContent":{"inputTranscription":{"text":"Hello world, how are you?"}}}
{"serverContent":{"turnComplete":true}}
{"goAway":{"timeLeft":"30s"}}
{"usageMetadata":{"promptTokenCount":1520,"responseTokenCount":18,"totalTokenCount":1538}}
```

- **`serverContent.interimInputTranscription`**: low-latency speculative partial,
  updated while the speaker is talking. HeliBoard **never commits it** — it only
  proves the stream is alive and satisfies the response watchdog.
- **`serverContent.inputTranscription`**: the authoritative transcript for a speech
  segment, emitted when the speaker pauses or the turn completes. In `SMART` mode
  this is the cleaned, formatted text. This is what reaches the editor.
- **`serverContent.turnComplete`**: utterance finished. Resets
  `TranscriptAccumulator`. `generationComplete` is advisory and unused.
- **`serverContent.modelTurn`**: a generated response. **Ignored**, so nothing the
  model produces can leak into the editor.
- A single server event can carry several of these fields at once, so each is
  checked independently rather than in an `else if` chain.
- Finalized transcripts have historically arrived both as per-utterance deltas and
  as text that grows on each message. `TranscriptAccumulator` compares each message
  against the previous one so either semantic yields correct text.
- `goAway.timeLeft` is a protobuf Duration, so it serializes as a **string**
  (`"30s"`, `"10.5s"`), not a number.
- The `/api/live` reference is stale on transcription: it omits
  `interimInputTranscription` and claims `AudioTranscriptionConfig` has no fields.
  The Live Transcription guide is authoritative for those.

### Session lifecycle and errors

- Live transcription sessions run for up to **10 minutes**.
- There is **no application-level keepalive message** in this protocol. HeliBoard
  sets OkHttp `pingInterval` to 20 s. The dominant cause of 1011 is not the network
  but a turn left open with no incoming audio, which `audioStreamEnd` on mic pause
  prevents.
- Graceful shutdown: send `audioStreamEnd`, keep reading for the final
  `inputTranscription` (HeliBoard allows 8 s), then close 1000. Closing right after
  the last audio chunk loses the final segment.

| Close code | Meaning |
|------|---------|
| 1000 | Normal |
| 1007 | `INVALID_ARGUMENT` — setup schema problem, or an auth failure; the reason string names the offending field path |
| 1008 | Policy / billing |
| 1011 | Stalled turn or internal error |
| 1006 | Network |

Errors can also arrive in band:

```json
{"error":{"code":401,"status":"UNAUTHENTICATED","message":"API key not valid. Please pass a valid API key."}}
```

| Status | Surfaced as |
|---|---|
| `UNAUTHENTICATED` | Invalid Gemini API key. Please check Settings. |
| `PERMISSION_DENIED` | This Gemini API key is not allowed to use the model. |
| `RESOURCE_EXHAUSTED` | Gemini rate limited — too many requests |
| `FAILED_PRECONDITION` | Gemini requires billing to be enabled for this API key. |
| `UNAVAILABLE` | Gemini is temporarily unavailable. Please try again. |
| `NOT_FOUND` | The model is not available for this key. |

Unknown statuses keep the server's own wording rather than hiding it.

### Ephemeral tokens (not used)

Google recommends ephemeral tokens for client apps:
`POST https://generativelanguage.googleapis.com/v1beta/auth_tokens` with
`liveConnectConstraints`, then connect to
`…GenerativeService.BidiGenerateContentConstrained?access_token=<token>`. Note
`newSessionExpireTime` defaults to only 60 seconds.

HeliBoard uses a user-supplied API key instead, because there is no HeliBoard
backend to mint tokens: the key belongs to the user's own Google account, is
entered by them in Settings, and never leaves the device except to Google. Adding
ephemeral tokens would require a server component.

---

## Settings Keys

| Key | Type | Description |
|-----|------|-------------|
| `PREF_GEMINI_API_KEY` | String | Google Gemini API key for transcription |
| `PREF_GEMINI_TRANSCRIPTION_MODE` | String | `SMART` (default) or `VERBATIM` |
| `PREF_GEMINI_END_OF_SPEECH_SILENCE_MS` | Int | `silenceDurationMs`, clamped 400–5000, default 1500 |
| `PREF_GEMINI_AUTO_DETECT_LANGUAGE` | Boolean | Send `languageCodes: []` instead of the subtype's language (default `false`) |
| `PREF_GEMINI_USE_EDITOR_CONTEXT` | Boolean | Harvest proper nouns near the caret into `customVocabulary` (default `true`) |
| `PREF_GEMINI_CUSTOM_VOCABULARY` | String | User-defined `customVocabulary`, one per line. Merged with the built-in list at session start. |
| `PREF_VOICE_CHUNK_SILENCE_SECONDS` | Int | Local silence window before sending `audioStreamEnd` (default 2) |
| `PREF_VOICE_SILENCE_THRESHOLD` | Int | RMS threshold used for silence detection |
| `PREF_VOICE_AUTO_STOP_SILENCE_SECONDS` | Int | Silence duration before auto-stopping recording |

`PREF_VOICE_CHUNK_SILENCE_SECONDS` and `PREF_VOICE_AUTO_STOP_SILENCE_SECONDS` drive
different behaviors: chunk silence sends `audioStreamEnd` so a pending phrase is
committed, while auto-stop silence ends the recording session after a longer pause.
Keep chunk silence **longer** than `PREF_GEMINI_END_OF_SPEECH_SILENCE_MS` so the
server's semantic end-of-speech detection normally decides where a sentence ends.

The model id, endpoint, VAD sensitivities, `prefixPaddingMs`, the system
instruction, and the 100-term vocabulary cap are hardcoded in
`GeminiTranscriptionClient` rather than exposed as preferences.

Preference keys from the providers used before Gemini (`soniox_*`,
`speechmatics_api_key`, `deepgram_api_key`) are erased by
`TranscriptionPreferences.migrateLegacyProviderPrefs`, which carries only the
user's own vocabulary list across.
