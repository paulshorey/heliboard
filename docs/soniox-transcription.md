# Soniox Transcription Architecture

HeliBoard uses Soniox real-time transcription for voice input.

## Runtime flow

1. `VoiceRecorder` captures 16 kHz mono PCM16 audio immediately when the mic button is tapped.
2. `VoiceInputManager` starts `SonioxTranscriptionClient` in parallel and buffers audio until the WebSocket has accepted the start config message.
3. `SonioxTranscriptionClient` sends:
   - A single JSON config text frame as the first message (containing `api_key`, `model`, `audio_format`, `sample_rate`, `num_channels`, optional `language_hints`, the merged `context.terms` (built-in + user-defined), `context.text` (recent editor text, capped at 4 000 chars) when available, plus the endpoint-detection and diarization flags from preferences).
   - Binary PCM audio chunks as the user speaks.
   - An empty WebSocket frame on graceful stop, then waits for `{"finished": true}` and closes with code 1000. An 8 second grace timer guards against the server never emitting `finished`.
4. Each Soniox response includes a `tokens` array. Tokens with `is_final: true` are confirmed and never repeated; non-final tokens are partials we drop. The IME only commits text that won't change.
5. The client concatenates final-token `text` directly (Soniox already encodes inter-word whitespace inside the token text), trims whitespace, and emits a `TranscriptSegment` with `attachesToPrevious=true` whenever the segment starts with attaching punctuation (`. , ! ? : ; ) ] } %`).
6. `VoiceInputManager` queues transcript segments in FIFO order and tracks whether the next segment should attach to previous text.
7. `LatinIME` calls `finishInput()` and then `commitText(...)` to insert the finalized text at the caret, restoring a leading space only when the segment is a continuation rather than punctuation.

## Important files

- `app/src/main/java/helium314/keyboard/latin/voice/SonioxTranscriptionClient.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/TranscriptSegment.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceRecorder.kt`
- `app/src/main/java/helium314/keyboard/latin/settings/TranscriptionPreferences.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/TranscriptionScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/SonioxContextTermsScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/SetupAppScreen.kt`
- `app/src/main/java/helium314/keyboard/latin/LatinIME.java`

## Configuration

- API key preference: `Settings.PREF_SONIOX_API_KEY` (legacy `speechmatics_api_key` and `deepgram_api_key` keys are cleared automatically the first time `TranscriptionPreferences.readSonioxApiKey` is called because those provider-specific keys cannot authenticate with Soniox).
- Soniox session settings:
  - `Settings.PREF_SONIOX_ENABLE_ENDPOINT_DETECTION` (boolean, default `true`)
  - `Settings.PREF_SONIOX_MAX_ENDPOINT_DELAY_MS` (int, range 500–3000, default 3000; upgrades from the previous 2000 default)
  - `Settings.PREF_SONIOX_DIARIZATION` (boolean, default `true`)
  - `Settings.PREF_SONIOX_CUSTOM_TERMS` (string, one term per line; merged with the built-in `context.terms` list at session start)
- Local silence settings remain unchanged:
  - chunk silence duration
  - silence threshold
  - auto-stop delay

## Notes

- Authentication is in the start config JSON body (`api_key`), not in HTTP headers. The WebSocket itself is opened anonymously.
- The client pins `model = "stt-rt-v4"`, the active real-time model in current Soniox docs.
- `context.terms` is the union of a built-in list (`HeliBoard`, `Soniox`, `Kubernetes`, `API`, `gnocchi`) and any user-defined custom terms from `PREF_SONIOX_CUSTOM_TERMS`, deduped and trimmed in `SonioxTranscriptionClient.buildSessionConfig`.
- `context.text` is populated at the start of each Soniox session from up to the most recent 4 000 characters of editor text before the cursor, as supplied by `LatinIME.buildVoiceContextText` via `VoiceInputManager.setPriorTextProvider`. Soniox uses this to inform sentence-structure punctuation, mid-sentence casing, and proper-noun spelling. Reconnects re-fetch the prior text so the running transcript is always part of the context.
- Soniox returns tokens continuously; there is no separate "recognition started" event. The client treats the stream as ready as soon as the start config frame is queued. Authentication failures still surface via `error_code` JSON responses, which are routed to `onStreamError`.
- HeliBoard does not configure direct replacement rules, punctuation sensitivity, disfluency/filler-word removal, or output locale for Soniox — those knobs are not in the real-time WebSocket API. Punctuation is model-driven; `context.text` helps mid-sentence formatting. The main user-facing pause/punctuation levers are endpoint detection and `max_endpoint_delay_ms` (higher waits longer before finalizing).
- When diarization is enabled, the client locks onto the first non-empty `speaker` label it sees and drops tokens from any other speaker. Soniox uses string speaker IDs (`"1"`, `"2"`, …); the locked label is not guaranteed to be the local speaker.
- The empty-frame end-of-stream handshake is the documented graceful-shutdown mechanism. Manual finalize (`{"type": "finalize"}`) is a separate Soniox feature for mid-stream finalization that HeliBoard does not currently use.
- The client filters Soniox's special control tokens — `<end>` (emitted on every endpoint detection event) and `<fin>` (emitted by manual finalize) — from the final-token concatenation. Without this filter both markers would leak into the editor as literal text; the Soniox SDKs filter them automatically but raw WebSocket consumers must do it themselves (see `STREAM_MARKERS` in `SonioxTranscriptionClient.kt`).
- Silence-driven automatic paragraph insertion is disabled because line breaks on host-app silence caused unintended side effects. Explicit spoken commands such as "New paragraph." are still handled by `TranscriptPostProcessor`.
