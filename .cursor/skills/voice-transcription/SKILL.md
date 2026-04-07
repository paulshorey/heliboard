---
name: voice-transcription
description: Voice-to-text pipeline using Deepgram Nova-3 streaming transcription with local post-processing. Use when working on microphone recording, audio streaming, transcription results, silence detection, voice input UI, or the VoicePostTranscriptionFilter.
---

# Voice Transcription

Local recording + Deepgram streaming transcription + local post-processing + immediate caret insertion.

## Architecture

```
Microphone → VoiceRecorder (PCM16 16kHz) → Deepgram WebSocket → finalized spans
                                                                       ↓
Text Field ← LatinIME (commitText) ← VoicePostTranscriptionFilter ← VoiceInputManager (FIFO)
```

Recording starts **instantly** on mic tap — no network round-trip delay.

## Key Files

| File | Role |
|------|------|
| `VoiceRecorder.kt` | PCM16 capture, adaptive RMS silence detection |
| `DeepgramTranscriptionClient.kt` | WebSocket client for `wss://api.deepgram.com/v1/listen` |
| `VoiceInputManager.kt` | State machine (IDLE→RECORDING↔PAUSED→IDLE), FIFO transcript queue, paragraph timer |
| `VoicePostTranscriptionFilter.java` | Alias pass (spoken numbers/symbols), cleanup pass, capitalization, leading-space logic |
| `LatinIME.java` | Orchestrator — calls `prepareForInsertion()` then `commitText()` at caret |
| `TranscriptionScreen.kt` | Settings UI for API key, silence thresholds, paragraph timing |

All source files live under `app/src/main/java/helium314/keyboard/latin/voice/` except `LatinIME.java` (parent package) and `TranscriptionScreen.kt` (settings package).

## Chunked Audio Flow

1. ChunkA audio → Deepgram
2. ChunkB streams while ChunkA finalizes
3. ChunkA transcript → `VoicePostTranscriptionFilter.prepareForInsertion(textA, textBeforeCursor)` → `commitText(textA)`
4. ChunkB transcript arrives in FIFO order → same pipeline → `commitText(textB)`

Each processed chunk is inserted once via `commitText`; no second pass over the field.

## Post-Transcription Filter Pipeline

`VoicePostTranscriptionFilter.prepareForInsertion(text, textBeforeCursor)`:

1. **Alias pass** — single longest-match scan: spoken numbers (`zero`..`ninety nine`), spoken symbols (`open parenthesis`, `slash`, `comma`, etc.)
2. **Cleanup pass** — fixes spacing between adjacent symbols/numbers, ordered edge-case rewrites (`one hundred → 100`, `negative five → -5`), spoken `dash`/`hyphen`/`minus` → `-`, collapse spaces around typed dashes between letter-words, lowercase letter-only hyphen/dash compounds (for mid-sentence capitalization)
3. **Insertion prep** — strips invisible Unicode control characters, adjusts capitalization from text before caret, prepends space only when the finished chunk starts with an ASCII letter

## Paragraph Breaks

After configured silence, `VoiceInputManager` fires `onNewParagraphRequested()` → LatinIME inserts `"\n\n"` when processing is idle (deferred so it doesn't interleave with pending transcript spans).

## Thread Safety

- Audio recording: background thread
- Deepgram callbacks: forwarded to main thread
- Timer callbacks: main thread
- Text insertion: always sequential on main thread

## Additional Resources

- Detailed data flow: [data-flow.md](data-flow.md)
- Deepgram API reference and settings keys: [api-reference.md](api-reference.md)

## Update documentation

IMPORTANT: When you change something, or discover that the code or functionality differs from what's described, update this skill and its supporting docs immediately.
