---
name: voice-transcription
description: Voice-to-text pipeline using Soniox real-time transcription with local audio capture and direct finalized-text insertion. Use when working on microphone recording, audio streaming, transcription results, silence detection, or voice input UI.
---

# Voice Transcription

Local recording + Soniox real-time streaming transcription + immediate caret insertion.

## Architecture

```
Microphone → VoiceRecorder (PCM16 16kHz) → Soniox WebSocket → final-token spans
                                                                        ↓
Text Field ← LatinIME (commitText) ← VoiceInputManager (FIFO queue + reconnect + graceful stop)
```

Recording starts **instantly** on mic tap — no network round-trip delay.

## Key Files

| File | Role |
|------|------|
| `VoiceRecorder.kt` | PCM16 capture, adaptive RMS silence detection |
| `SonioxTranscriptionClient.kt` | WebSocket client for `wss://stt-rt.soniox.com/transcribe-websocket`. Sends JSON start config (auth via `api_key` field, model `stt-rt-v5`), streams binary PCM, parses responses, filters `<end>`/`<fin>` control markers, sends `{"type":"keepalive"}` during outbound gaps, handles graceful shutdown (empty frame → wait for `finished:true`) |
| `TranscriptSegment.kt` | Finalized chunk passed from the client to the IME pipeline |
| `VoiceInputManager.kt` | State machine (IDLE→RECORDING↔PAUSED→IDLE), FIFO transcript queue, reconnects, auto-stop timers, Soniox session config assembly (incl. `context.text` via `setPriorTextProvider`) |
| `TranscriptionPreferences.kt` | Reads/writes Soniox preferences (incl. user-editable `context.terms`) and clears legacy provider keys (Speechmatics, Deepgram) |
| `TranscriptPostProcessor.kt` | Local transcript shaping helpers: pre-commit casing/trailing-punctuation adjustment plus paragraph-level spoken-command replacement after commit |
| `LatinIME.java` | Orchestrator — finalizes composing state, commits transcript text at the caret, supplies `context.text` to Soniox via `buildVoiceContextText`, triggers post-processing |
| `TranscriptionScreen.kt` | Settings UI for API key, endpoint detection, diarization, silence thresholds, and auto-stop timing |
| `SonioxContextTermsScreen.kt` | Settings UI for editing user-defined `context.terms` (one per line). Merged with the built-in list at session start. |

All source files live under `app/src/main/java/helium314/keyboard/latin/voice/` except `LatinIME.java` (parent package) and the settings UI/preferences helpers in `latin/settings` and `settings/screens`.

## Chunked Audio Flow

1. Mic chunks are captured locally in `VoiceRecorder`.
2. `VoiceInputManager` builds a Soniox session config from preferences + the current subtype locale.
3. The `SonioxTranscriptionClient` opens the socket and sends the JSON start config; PCM frames are streamed immediately afterwards.
4. Soniox emits JSON responses containing a `tokens` array. Each token has a `text` and `is_final` flag.
5. `SonioxTranscriptionClient` collects only `is_final: true` tokens, drops Soniox's special markers (`<end>` from endpoint detection, `<fin>` from manual finalize), concatenates the remaining text directly (Soniox encodes inter-word whitespace as separate space tokens), trims, and emits a `TranscriptSegment`. The client also tracks whether the previous response's finalized text ended on a "wordy" character (letter/digit/`'`/`-`); when the next response's raw text does **not** start with whitespace and the previous tail was wordy, Soniox is signaling a mid-word continuation (e.g. `"head"` then `"ing"` for `"heading"`) and the segment is marked `attachesToPrevious` so the IME does not insert a separating space.
6. `VoiceInputManager` delivers segments in FIFO order to `LatinIME`.
7. `LatinIME` inserts each finalized segment with `commitText(...)`, replacing any active selection range and restoring a leading space only when the segment is **not** flagged `attachesToPrevious` (which covers both leading attaching punctuation and Soniox-split mid-word continuations).

On graceful stop, the client sends an empty WebSocket frame, waits for `{"finished": true}`, and closes with code 1000. An 8-second grace timer guards against the server not emitting `finished`. While the socket is open but idle (mic paused, or any other outbound gap of ~10 s), the client sends `{"type":"keepalive"}` so Soniox does not close the session after ~20 s without audio.

## Transcript Handling

Soniox returns smart-formatted text with punctuation already inserted; HeliBoard does not tune it client-side. Key Soniox config features the client uses:

- **`model`** — pinned to `"stt-rt-v5"` in code. Not user-configurable. v4 is retired 2026-06-30 and already aliased to v5.
- **`audio_format` / `sample_rate` / `num_channels`** — `pcm_s16le` / `16000` / `1`, matching `VoiceRecorder` output.
- **`language_hints`** — single-element ISO language code from the keyboard subtype (e.g. `["en"]`). Omitted when the subtype has no usable language so Soniox auto-detects.
- **`language_hints_strict`** — `true` whenever a language hint is sent. Strongly biases v5 toward that one language (best-effort; documented as the right setting when the app already knows the language).
- **`context.general`** — structured key/value pairs that v5 treats as more influential than free-form `text`. HeliBoard always sends domain/setting/topic/product for keyboard dictation, plus `language`/`instructions` when a hint is available, plus `speakers` when diarization is on.
- **`context.terms`** — built-in list (`HeliBoard`, `Soniox`, `Kubernetes`, `API`, `gnocchi`) merged with the user-editable list from `PREF_SONIOX_CUSTOM_TERMS` (managed in `SonioxContextTermsScreen`), then deduped/trimmed in `SonioxTranscriptionClient.buildSessionConfig`.
- **`context.text`** — populated at session start (and on reconnect) from up to the most recent 4 000 chars of editor text before the cursor, supplied by `LatinIME.buildVoiceContextText` via `VoiceInputManager.setPriorTextProvider`. Soniox uses this for sentence-structure punctuation, mid-sentence casing, and proper-noun spelling.
- **`enable_endpoint_detection`** + **`max_endpoint_delay_ms`** + **`endpoint_sensitivity`** — when enabled, Soniox finalizes tokens once it detects the speaker has stopped talking (semantic endpointing: intonation, pauses, and context — not plain VAD). `max_endpoint_delay_ms` is the **maximum** wait after speech ends before the endpoint is returned; it must be between 500 and 3000 ms. `endpoint_sensitivity` is v5-only (`-1.0`–`1.0`); HeliBoard pins **`-0.3`** (Soniox's documented dictation/slow-speaker starting point) so mid-sentence pauses are less likely to insert an early period. `endpoint_latency_adjustment_level` is left at the API default `0` — the low-latency voice-agent profile is too aggressive for dictation. Higher `max_endpoint_delay_ms` only raises the worst-case wait. HeliBoard defaults that delay to **3000 ms** (Soniox's API default is 2000 ms).
- **Manual finalize on local silence** — because HeliBoard only commits `is_final` tokens and the server endpoint can be delayed or never fire, `VoiceInputManager` sends a manual finalize control frame (`SonioxTranscriptionClient.finalizeNow()` → `{"type":"finalize"}`) whenever the local `VoiceRecorder` silence detector reports `onSpeechStopped`, plus once more right before the empty end-of-stream frame on mic stop. Soniox then re-emits all pending tokens as final (plus a filtered `<fin>` marker) and keeps the stream open. This guarantees the trailing phrase is committed after the user's `PREF_VOICE_CHUNK_SILENCE_SECONDS` pause (or immediately on stop) regardless of the server endpoint, and makes disabling endpoint detection viable (finalization then runs purely off local VAD at the user's chosen pause, which avoids the model's premature sentence endings). It fires at most once per speech-stop transition (re-armed on the next `onSpeechStarted`); the chunk-silence window itself keeps finalize calls naturally spaced, so no extra global rate limit is used.
- **`enable_speaker_diarization`** — when enabled, the client locks onto the first non-empty `speaker` label observed and drops tokens from other speakers. Soniox uses string speaker IDs (`"1"`, `"2"`, …); the locked ID isn't guaranteed to be the local speaker.

Direct replacement rules, punctuation sensitivity, and output locale are **not** exposed in Soniox's real-time WebSocket API. Punctuation is model-driven; `context.text` (editor text before the cursor) is the main lever for mid-sentence comma/period behavior. HeliBoard locally strips common comma-attached filler fragments such as "um," and "uh," during transcript post-processing.

## Post-Processing (TranscriptPostProcessor)

Before commit, `LatinIME.prepareVoiceTranscriptionText()` uses `TranscriptPostProcessor.adjustLeadingCasing()` and `stripTrailingPunctuationIfMidSentence()` to fit the chunk into surrounding editor text.

After each transcript chunk is committed to the text field, `LatinIME.runTranscriptPostProcessing()` reads the current paragraph (text from the last newline to the cursor, up to 1024 chars) and runs it through `TranscriptPostProcessor.processCurrentParagraph()`. If any rules match, the paragraph text is replaced in-place via `deleteTextBeforeCursor` + `commitText`.

Current processing removes common comma-attached filler fragments ("um,", "uh,") and handles **spelled-out punctuation** (e.g. "exclamation point.", "comma", "question mark.", "period.", "colon.", "semicolon."). Rules are case-insensitive and sorted longest-first so that patterns with surrounding punctuation context (like ". Exclamation point.") are consumed before shorter ambiguous ones. The processor only fires when a rule actually modifies the paragraph — no-op paragraphs are skipped.

To add new post-processing rules, edit `TranscriptPostProcessor.buildRules()` in `voice/TranscriptPostProcessor.kt`. Unit tests are in `TranscriptPostProcessorTest.kt`.

## Leading-Casing Correction

Real-time STT providers (including Soniox) typically capitalize the first letter of a new utterance. When the user dictates mid-sentence — caret placed inside existing text, or resumed after deleting a trailing period — that capitalization is wrong.

`TranscriptPostProcessor.adjustLeadingCasing(chunk, previousContext)` handles this **before commit**. It is called from `LatinIME.prepareVoiceTranscriptionText`, which reads `VOICE_CASING_LOOKBACK` (16) characters before the cursor and passes them in alongside the chunk.

The first character is lowercased only when all of these hold:
- the chunk is not `attachesToPrevious` (segments that start with attaching punctuation are short-circuited earlier in `prepareVoiceTranscriptionText`)
- the first character is an uppercase letter
- the previous visible character (ignoring trailing whitespace and closing `"`, `'`, `“”`, `‘’`, `)`, `]`, `}`, `»`) is **not** `.`, `!`, `?`, or a newline — and the context is not empty/whitespace
- the first word is **not** `I`/`I'm`/`I'll`/`I've`/`I'd`, an all-uppercase acronym (`NASA`), or a camel/Pascal-case word with internal uppercase (`iPhone`, `McDonald's`)

Known tradeoff: proper nouns dictated as the first word of a mid-sentence chunk (e.g. `Amazon`, `Paris`) are lowercased. Soniox does not currently expose a custom-vocab feature that would let us bias such words.

## Paragraph Breaks

Silence-driven automatic paragraph insertion is disabled because inserting `"\n\n"` into arbitrary host fields caused form submissions and other unintended side effects. Explicit spoken commands such as "New paragraph." are still handled by `TranscriptPostProcessor`.

## Thread Safety

- Audio recording: background thread
- Soniox callbacks: forwarded to main thread
- Timer callbacks: main thread
- Text insertion: always sequential on main thread

## Additional Resources

- Detailed data flow: [data-flow.md](data-flow.md)
- Soniox API reference and settings keys: [api-reference.md](api-reference.md)

## Update documentation

IMPORTANT: When you change something, or discover that the code or functionality differs from what's described, update this skill and its supporting docs immediately.
