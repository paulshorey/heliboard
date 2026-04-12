---
name: voice-transcription
description: Voice-to-text pipeline using Speechmatics realtime transcription with local audio capture and direct finalized-text insertion. Use when working on microphone recording, audio streaming, transcription results, silence detection, or voice input UI.
---

# Voice Transcription

Local recording + Speechmatics realtime streaming transcription + immediate caret insertion.

## Architecture

```
Microphone → VoiceRecorder (PCM16 16kHz) → Speechmatics WebSocket → finalized AddTranscript spans
                                                                                ↓
Text Field ← LatinIME (commitText) ← VoiceInputManager (FIFO queue + reconnect + graceful stop)
```

Recording starts **instantly** on mic tap — no network round-trip delay.

## Key Files

| File | Role |
|------|------|
| `VoiceRecorder.kt` | PCM16 capture, adaptive RMS silence detection |
| `SpeechmaticsTranscriptionClient.kt` | WebSocket client for `wss://eu.rt.speechmatics.com/v2/` with configurable `StartRecognition`, binary audio, `AudioAdded`, `ForceEndOfUtterance`, `AddTranscript`, and `EndOfStream` |
| `VoiceInputManager.kt` | State machine (IDLE→RECORDING↔PAUSED→IDLE), FIFO transcript queue, reconnects, paragraph timer, Speechmatics locale/config assembly |
| `TranscriptionPreferences.kt` | Reads/writes sanitized Speechmatics settings and drops the legacy provider key |
| `TranscriptPostProcessor.kt` | Paragraph-level post-processing of committed text (spelled-out punctuation replacement, etc.) |
| `LatinIME.java` | Orchestrator — finalizes composing state, commits transcript text at the caret, and triggers post-processing |
| `TranscriptionScreen.kt` / `SetupAppScreen.kt` | Settings UI for API key, Speechmatics latency/formatting controls, silence thresholds, paragraph timing |

All source files live under `app/src/main/java/helium314/keyboard/latin/voice/` except `LatinIME.java` (parent package) and the settings UI/preferences helpers in `latin/settings` and `settings/screens`.

## Chunked Audio Flow

1. Mic chunks are captured locally in `VoiceRecorder`
2. `VoiceInputManager` builds a provider config from settings + current subtype locale
3. `VoiceInputManager` buffers chunks until Speechmatics sends `RecognitionStarted`
4. Chunks stream as binary PCM16 frames; Speechmatics replies with `AudioAdded` sequence acks
5. Final transcript spans arrive as `AddTranscript` messages
6. `SpeechmaticsTranscriptionClient` rebuilds span text from token results so spacing and punctuation attachment stay correct across finalized chunks
7. `VoiceInputManager` delivers them in FIFO order to `LatinIME`
8. `LatinIME` inserts each finalized span with `commitText(...)`, restoring a leading space only when the provider marks the span as a continuation

On graceful stop, the client waits for all sent audio to be acknowledged, sends `ForceEndOfUtterance`, then `EndOfStream(last_seq_no=...)`, and only closes after the tail transcript is flushed or a close timeout expires.

## Transcript Handling

Speechmatics smart formatting is used for finalized transcript text. Key Speechmatics config features:
- **operating_point**: `"enhanced"` for best accuracy
- **output_locale**: Defaults to `en-US` for English (supports `en-GB`, `en-AU` when detected)
- **diarization**: Speaker diarization with `prefer_current_speaker: true` and `max_speakers: 2`. Only the primary speaker (S1) is transcribed; other speakers are filtered out.
- **additional_vocab**: Custom dictionary for proper nouns, brand names, technical terms with optional `sounds_like` pronunciations
- **replacements**: Post-transcription word and regex replacements (e.g. brand name corrections, voice assistant trigger normalization)
- **punctuation**: All marks permitted, sensitivity defaults to 0.5
- **disfluency removal**: Optional removal of English hesitation words (um, uh, hmm)

HeliBoard rebuilds finalized text from Speechmatics token results so word spacing and punctuation attachment survive chunk boundaries, then commits the finalized text exactly once at the caret.

## Post-Processing (TranscriptPostProcessor)

After each transcript chunk is committed to the text field, `LatinIME.runTranscriptPostProcessing()` reads the current paragraph (text from the last newline to the cursor, up to 1024 chars) and runs it through `TranscriptPostProcessor.processCurrentParagraph()`. If any rules match, the paragraph text is replaced in-place via `deleteTextBeforeCursor` + `commitText`.

Current rules handle **spelled-out punctuation** (e.g. "exclamation point.", "comma", "question mark.", "period.", "colon.", "semicolon."). Rules are case-insensitive and sorted longest-first so that patterns with surrounding punctuation context (like ". Exclamation point.") are consumed before shorter ambiguous ones. The processor only fires when a rule actually modifies the paragraph — no-op paragraphs are skipped.

To add new post-processing rules, edit `TranscriptPostProcessor.buildRules()` in `voice/TranscriptPostProcessor.kt`. Unit tests are in `TranscriptPostProcessorTest.kt`.

## Paragraph Breaks

After configured silence, `VoiceInputManager` fires `onNewParagraphRequested()` → LatinIME inserts `"\n\n"` when processing is idle (deferred so it doesn't interleave with pending transcript spans).

## Thread Safety

- Audio recording: background thread
- Speechmatics callbacks: forwarded to main thread
- Timer callbacks: main thread
- Text insertion: always sequential on main thread

## Additional Resources

- Detailed data flow: [data-flow.md](data-flow.md)
- Speechmatics API reference and settings keys: [api-reference.md](api-reference.md)

## Update documentation

IMPORTANT: When you change something, or discover that the code or functionality differs from what's described, update this skill and its supporting docs immediately.
