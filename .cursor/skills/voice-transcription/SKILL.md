---
name: voice-transcription
description: Voice-to-text pipeline using AssemblyAI Universal-Streaming with semantic end-of-turn detection, immutable transcripts, formatted finals, and direct caret insertion. Use when working on microphone recording, audio streaming, transcription results, end-of-turn handling, or voice input UI.
---

# Voice Transcription

Local recording + AssemblyAI Universal-Streaming + immediate caret insertion of formatted, immutable end-of-turn results.

## Architecture

```
Microphone → VoiceRecorder (PCM16 16kHz) → AssemblyAI Universal-Streaming WebSocket → end_of_turn Turn messages
                                                                                              ↓
Text Field ← LatinIME (commitText) ← VoiceInputManager (FIFO queue + reconnect + Terminate)
```

Recording starts **instantly** on mic tap — no network round-trip delay.

## Why Universal-Streaming

The previous Speechmatics integration used silence-based punctuation, which fragmented dictation: a brief mid-sentence pause was treated as a sentence boundary, producing a period and a capitalized next word. AssemblyAI Universal-Streaming combines a neural turn-detection model (semantic) with VAD (acoustic). It only emits `end_of_turn: true` when the speaker has actually finished a thought, not on every short silence. With `format_turns=true`, the final turn arrives punctuated, cased, and ITN-formatted (dates, times, currency, phone numbers).

## Key Files

| File | Role |
|------|------|
| `VoiceRecorder.kt` | PCM16 capture, adaptive RMS silence detection (drives paragraph/auto-stop, not transcription). |
| `AssemblyAITranscriptionClient.kt` | WebSocket client for `wss://streaming.assemblyai.com/v3/ws` (or EU). All session config goes in connection-URL query params. Handles `Begin` / `Turn` / `Termination` / `Error`. Forwards only end-of-turn results, and waits for `turn_is_formatted: true` when formatting is on. |
| `VoiceInputManager.kt` | State machine (IDLE→RECORDING↔PAUSED→IDLE), FIFO transcript queue, reconnects, paragraph timer, AssemblyAI session config assembly from prefs. |
| `TranscriptionPreferences.kt` | Reads/writes sanitized AssemblyAI settings; deletes legacy Speechmatics keys on first read so prefs reflect the new backend. |
| `TranscriptPostProcessor.kt` | Paragraph-level post-processing (spelled-out punctuation replacement, mid-sentence casing and trailing-punctuation fixes). |
| `LatinIME.java` | Orchestrator — finalizes composing state, commits transcript text at the caret, and triggers post-processing. |
| `TranscriptionScreen.kt` / `SetupAppScreen.kt` | Settings UI for API key, speech model, formatted-finals toggle, end-of-turn confidence/silence settings, EU endpoint, custom keyterms, and local silence/auto-stop. |

All source files live under `app/src/main/java/helium314/keyboard/latin/voice/` except `LatinIME.java` (parent package) and the settings UI/preferences helpers in `latin/settings` and `settings/screens`.

## Chunked Audio Flow

1. Mic chunks are captured locally in `VoiceRecorder`.
2. `VoiceInputManager` builds an AssemblyAI session config from prefs.
3. `AssemblyAITranscriptionClient` opens the WebSocket with all parameters as query args; auth via `Authorization` request header (no `Bearer` prefix).
4. Once `Begin` arrives, buffered PCM16 frames stream as binary websocket messages.
5. `Turn` messages arrive continuously; we ignore non-end-of-turn turns. When `format_turns=true`, we also ignore the unformatted end-of-turn message and wait for the `turn_is_formatted: true` companion.
6. The finalized transcript is forwarded to `VoiceInputManager` as a `TranscriptSegment`.
7. `VoiceInputManager` delivers segments in FIFO order to `LatinIME`.
8. `LatinIME` inserts each finalized text with `commitText(...)` (replacing any active selection) and runs paragraph-level post-processing.

On graceful stop, the client sends `{"type":"Terminate"}`, awaits the final formatted `Turn` plus `Termination`, and then closes.

## Transcript Handling

Universal-Streaming guarantees immutability of finalized words — once `word_is_final: true`, that word is never revised in a later message. So the end-of-turn `transcript` field is final and can be committed directly without manual token reassembly. Notable session config:

- **`speech_model`** (required by AssemblyAI on every connection): defaults to `universal-streaming-english`. Other supported: `universal-streaming-multilingual` (English/Spanish/German/French/Portuguese/Italian), `u3-rt-pro` (Universal-3 Pro, highest accuracy with built-in turn-detection prompt), `whisper-rt`.
- **`format_turns`** (default true): inserts punctuation, casing, and inverse text normalization on every completed turn.
- **`end_of_turn_confidence_threshold`** (default 0.7 in HeliBoard, AssemblyAI API default 0.4): semantic confidence required for the model to end a turn. Higher values bias toward holding the turn open through brief mid-sentence pauses. Setting to 0 is the legacy silence-only failure mode and is intentionally not surfaced as a recommendation.
- **`min_turn_silence` / `max_turn_silence`** (defaults 600 ms / 2400 ms in HeliBoard): silence floor for triggering an end-of-turn check, and the hard ceiling beyond which a turn is forced closed regardless of semantic confidence.
- **`keyterms_prompt`**: up to 100 entries (≤ 50 chars each), boosting recognition of brand names, technical jargon, contacts, etc. HeliBoard ships a default list (`HeliBoard`, `AssemblyAI`, `Universal-Streaming`, etc.) and merges any user-provided keyterms on top.
- **EU endpoint**: switch to `streaming.eu.assemblyai.com` for lower latency in Europe.

After commit, `LatinIME.runTranscriptPostProcessing()` runs through `TranscriptPostProcessor` to handle spelled-out punctuation names like "exclamation point" (case-insensitive, longest-first).

## Leading-Casing Correction

Universal-Streaming with `format_turns=true` capitalizes the first letter of each turn (it treats each turn as a new sentence). When the user dictates mid-sentence — caret placed inside existing text, or resumed after deleting a trailing period — that capitalization is wrong.

`TranscriptPostProcessor.adjustLeadingCasing(chunk, previousContext)` handles this **before commit**. It is called from `LatinIME.prepareVoiceTranscriptionText`, which reads `VOICE_CASING_LOOKBACK` (16) characters before the cursor and passes them in alongside the chunk.

The first character is lowercased only when all of these hold:
- the chunk is not `attachesToPrevious` (continuation segments are already correctly cased)
- the first character is an uppercase letter
- the previous visible character (ignoring trailing whitespace and closing `"`, `'`, `“”`, `‘’`, `)`, `]`, `}`, `»`) is **not** `.`, `!`, `?`, or a newline — and the context is not empty/whitespace
- the first word is **not** `I`/`I'm`/`I'll`/`I've`/`I'd`, an all-uppercase acronym (`NASA`), or a camel/Pascal-case word with internal uppercase (`iPhone`, `McDonald's`)

Known tradeoff: proper nouns dictated as the first word of a mid-sentence chunk (e.g. `Amazon`, `Paris`) are lowercased. Mitigated by `keyterms_prompt` for known brands.

## Paragraph Breaks

After configured silence, `VoiceInputManager` fires `onNewParagraphRequested()` → LatinIME inserts `"\n\n"` when processing is idle. This is independent of how AssemblyAI segments turns — it is driven by HeliBoard's local mic silence timer.

## Thread Safety

- Audio recording: background thread
- AssemblyAI callbacks: forwarded to main thread
- Timer callbacks: main thread
- Text insertion: always sequential on main thread

## Additional Resources

- Detailed data flow: [data-flow.md](data-flow.md)
- AssemblyAI API reference and settings keys: [api-reference.md](api-reference.md)

## Update documentation

IMPORTANT: When you change something, or discover that the code or functionality differs from what's described, update this skill and its supporting docs immediately.
