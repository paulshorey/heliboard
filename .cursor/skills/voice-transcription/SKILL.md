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
| `SpeechmaticsTranscriptionClient.kt` | WebSocket client for `wss://eu.rt.speechmatics.com/v2/` with `StartRecognition`, binary audio, `AudioAdded`, `AddTranscript`, and `EndOfStream` |
| `VoiceInputManager.kt` | State machine (IDLE→RECORDING↔PAUSED→IDLE), FIFO transcript queue, reconnects, paragraph timer |
| `TranscriptionPreferences.kt` | Reads/writes the Speechmatics API key and drops the legacy provider key |
| `LatinIME.java` | Orchestrator — finalizes composing state and commits transcript text at the caret |
| `TranscriptionScreen.kt` / `SetupAppScreen.kt` | Settings UI for API key, silence thresholds, paragraph timing |

All source files live under `app/src/main/java/helium314/keyboard/latin/voice/` except `LatinIME.java` (parent package) and the settings UI/preferences helpers in `latin/settings` and `settings/screens`.

## Chunked Audio Flow

1. Mic chunks are captured locally in `VoiceRecorder`
2. `VoiceInputManager` buffers chunks until Speechmatics sends `RecognitionStarted`
3. Chunks stream as binary PCM16 frames; Speechmatics replies with `AudioAdded` sequence acks
4. Final transcript spans arrive as `AddTranscript` messages
5. `VoiceInputManager` delivers them in FIFO order to `LatinIME`
6. `LatinIME` inserts each finalized span with `commitText(...)`

On graceful stop, the client waits for all sent audio to be acknowledged, sends `EndOfStream(last_seq_no=...)`, and only closes after the tail transcript is flushed or a close timeout expires.

## Transcript Handling

Speechmatics smart formatting is used for finalized transcript text. HeliBoard does not run a second provider-specific normalization pass before insertion; it trims empty spans and commits the finalized text exactly once at the caret.

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
