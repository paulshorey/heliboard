# Soniox Transcription Architecture

HeliBoard uses Soniox real-time transcription for voice input.

This file is the **provider/API reference**. For the provider-agnostic control
flow — session lifecycle, threading, interruption guards, and where Soniox is
baked into the pipeline — see
[`voice-transcription-workflow.md`](voice-transcription-workflow.md). For the plan
to make the provider swappable, see
[`pluggable-transcription-providers-plan.md`](pluggable-transcription-providers-plan.md).

## Runtime flow

1. `VoiceRecorder` captures 16 kHz mono PCM16 audio immediately when the mic button is tapped.
2. `VoiceInputManager` starts `SonioxTranscriptionClient` in parallel and buffers audio until the WebSocket has accepted the start config message.
3. `SonioxTranscriptionClient` sends:
   - A single JSON config text frame as the first message (containing `api_key`, `model`, `audio_format`, `sample_rate`, `num_channels`, optional `language_hints`, the merged `context.terms` (built-in + user-defined), `context.text` (recent editor text, capped at 4 000 chars) when available, plus the endpoint-detection and diarization flags from preferences).
   - Binary PCM audio chunks as the user speaks.
   - An empty WebSocket frame on graceful stop, then waits for `{"finished": true}` and closes with code 1000. An 8 second grace timer guards against the server never emitting `finished`.
4. Each Soniox response includes a `tokens` array. Tokens with `is_final: true` are confirmed and never repeated; non-final tokens are partials we drop. The IME only commits text that won't change.
5. The client concatenates final-token `text` directly (Soniox already encodes inter-word whitespace inside the token text), trims whitespace, and emits a `TranscriptSegment` with `attachesToPrevious=true` when the segment starts with attaching punctuation (`. , ! ? : ; ) ] } %`) or when Soniox split a word across responses (`"head"` + `"ing"`).
6. `VoiceInputManager` queues transcript segments in FIFO order; if the queue reaches 64 entries, it coalesces the oldest entries rather than reordering newer text.
7. `LatinIME` prepares the chunk, calls `finishInput()`, then `commitText(...)` to insert finalized text at the caret. A leading space is injected only when the segment is **not** attaching to previous text and the preceding editor character needs a separator.

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
- Local silence settings:
  - chunk silence duration (speech boundary -> manual Soniox finalize)
  - silence threshold
  - auto-stop delay (longer silence -> stop recording)

## Notes

- Authentication is in the start config JSON body (`api_key`), not in HTTP headers. The WebSocket itself is opened anonymously.
- The client pins `model = "stt-rt-v4"`. **This is stale**: Soniox's current realtime model is `stt-rt-v5`, and `stt-rt-v4` was retired/rerouted after 30 June 2026. Voice input still works, which suggests Soniox is silently rerouting rather than rejecting, so the app may be transcribing on a different model than it requests. Needs verification against a live key, then a version bump. See `pluggable-transcription-providers-plan.md` section 8.1.
- Soniox documents a `{"type":"keepalive"}` message that should be sent at least every 20 s while no audio is flowing. The client does not implement it. This is harmless while recording (PCM streams continuously, silence included) but means a long **pause** drops the provider session; `VoiceInputManager` absorbs that by reconnecting on resume.
- Soniox documents that *frequent* manual finalization degrades diarization and can drop sessions. HeliBoard sends a finalize on every speech-stop transition — as often as every ~1 s at the default `PREF_VOICE_CHUNK_SILENCE_SECONDS` — while diarization defaults to on. The interaction between those two defaults has not been measured.
- Not currently exposed: `endpoint_sensitivity`, `endpoint_latency_adjustment_level`, multi-language `language_hints`, `enable_language_identification`, and the structured `context.general` field.
- `context.terms` is the union of a built-in list (`HeliBoard`, `Soniox`, `Kubernetes`, `API`, `gnocchi`) and any user-defined custom terms from `PREF_SONIOX_CUSTOM_TERMS`, deduped and trimmed in `SonioxTranscriptionClient.buildSessionConfig`.
- `context.text` is populated at the start of each Soniox session from up to the most recent 4 000 characters of editor text before the cursor, as supplied by `LatinIME.buildVoiceContextText` via `VoiceInputManager.setPriorTextProvider`. Soniox uses this to inform sentence-structure punctuation, mid-sentence casing, and proper-noun spelling. Reconnects re-fetch the prior text so the running transcript is always part of the context.
- Soniox returns tokens continuously; there is no separate "recognition started" event. The client treats the stream as ready as soon as the start config frame is queued. Authentication failures still surface via `error_code` JSON responses, which are routed to `onStreamError`.
- HeliBoard does not configure direct replacement rules, punctuation sensitivity, or output locale for Soniox — those knobs are not in the real-time WebSocket API. Punctuation is model-driven; `context.text` helps mid-sentence formatting. The app locally removes common comma-attached filler fragments such as "um," and "uh," during transcript post-processing. The main user-facing pause/punctuation levers are endpoint detection and `max_endpoint_delay_ms`.
- Local pre-commit shaping in `LatinIME.prepareVoiceTranscriptionText()` handles separator-space insertion, mid-sentence leading-casing correction, and stripping trailing `.`, `!`, or `?` when dictating before lowercase text. Paragraph-level post-processing then runs after commit for spoken commands such as "Comma." or "New paragraph."
- `max_endpoint_delay_ms` is a **maximum**, not a fixed/minimum wait. Soniox endpointing is *semantic* (intonation, pauses, context), so the model can finalize a phrase early even with the delay maxed at 3000 ms — this is why sentences can still end "prematurely" (a comma/period after a short pause). Raising the delay only changes the worst case and can make a trailing phrase commit later, or never, since the keyboard only commits `is_final` tokens.
- **Manual finalization on local silence**: to bound the above, `VoiceInputManager` sends `{"type":"finalize"}` (via `SonioxTranscriptionClient.finalizeNow()`) when the local `VoiceRecorder` reports `onSpeechStopped` (after `PREF_VOICE_CHUNK_SILENCE_SECONDS` of silence), and once more right before the empty end-of-stream frame on mic stop. Soniox finalizes all pending tokens (returning a `<fin>` marker we filter) and keeps the session open. This guarantees the tail is committed at the user's pause even when the server endpoint is delayed or never fires. It is gated to once per speech-stop transition (re-armed on the next `onSpeechStarted`); the chunk-silence window itself (>= 1 s) keeps finalize calls naturally spaced, so no extra global rate limit is used. With endpoint detection turned off, this manual finalize becomes the sole finalization trigger, so the user's chunk-silence pause — not the model's semantic guess — decides when a sentence ends.
- Soniox does **not** expose a "minimum endpoint delay" and the realtime API cannot be forced to add a sentence-final period when the model omits one; that is a model/smart-formatting decision. `context.text` (recent editor text) is the only lever that nudges sentence-structure punctuation.
- When diarization is enabled, the client locks onto the first non-empty `speaker` label it sees and drops tokens from any other speaker. Soniox uses string speaker IDs (`"1"`, `"2"`, …); the locked label is not guaranteed to be the local speaker.
- The empty-frame end-of-stream handshake is the documented graceful-shutdown mechanism. Manual finalize (`{"type": "finalize"}`) is a separate Soniox feature for mid-stream finalization; HeliBoard now uses it on local-VAD silence and just before the empty end-of-stream frame to flush pending non-final tokens.
- The client filters Soniox's special control tokens — `<end>` (emitted on every endpoint detection event) and `<fin>` (emitted by manual finalize) — from the final-token concatenation. Without this filter both markers would leak into the editor as literal text; the Soniox SDKs filter them automatically but raw WebSocket consumers must do it themselves (see `STREAM_MARKERS` in `SonioxTranscriptionClient.kt`).
- Silence-driven automatic paragraph insertion is disabled because line breaks on host-app silence caused unintended side effects. Explicit spoken commands such as "New paragraph." are still handled by `TranscriptPostProcessor`.
