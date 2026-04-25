# Voice Transcription Data Flow

End-to-end voice transcription pipeline: local capture, AssemblyAI Universal-Streaming, FIFO transcript delivery, and immediate caret insertion.

## Overview

1. **VoiceRecorder** captures PCM16 audio locally; silence detection drives paragraph breaks and auto-stop.
2. **AssemblyAITranscriptionClient** opens the realtime WebSocket with all session parameters in the URL, sends raw PCM binary frames once `Begin` is received, only forwards `Turn` messages where `end_of_turn: true` (and `turn_is_formatted: true` when `format_turns=true`), and gracefully terminates with `{"type":"Terminate"}` on stop.
3. **VoiceInputManager** buffers audio until the stream is ready, retries broken sessions, derives a session config from preferences, queues finalized turns in FIFO order, and performs graceful shutdown.
4. **LatinIME** inserts each finalized transcript immediately at the current caret through `InputConnection`, restoring a leading space only when the new segment is a continuation rather than starting punctuation.

## Architecture

```
┌─────────────────┐     ┌──────────────────────┐     ┌──────────────────────┐
│   Microphone    │────▶│   VoiceRecorder      │────▶│ AssemblyAI Universal │
│   (Hardware)    │     │   (PCM16 16kHz)      │     │ Streaming (wss /v3/) │
└─────────────────┘     │   Silence detection  │     └──────────┬───────────┘
                        │   Chunking/timers    │                ▼
                        └──────────────────────┘     ┌──────────────────────┐
┌─────────────────┐     ┌──────────────────────┐◀────│ end_of_turn Turn     │
│   Text Field    │◀────│   LatinIME           │     │ messages (formatted)  │
│   (App)         │     │   (Orchestrator)     │     └──────────────────────┘
└─────────────────┘     └──────────────────────┘
```

## Components

### VoiceRecorder.kt
Captures audio from the microphone with client-side silence detection.
- **Format**: PCM16, 16 kHz, mono
- **Silence detection**: Adaptive RMS threshold on each 100 ms chunk
- **Callbacks**: Supplies PCM chunks to `VoiceInputManager`; long silence can request a new paragraph or auto-stop

### AssemblyAITranscriptionClient.kt
WebSocket client for AssemblyAI Universal-Streaming.
- **URL**: `wss://streaming.assemblyai.com/v3/ws` (or EU equivalent), with all session config as query parameters
- **Handshake**: `Authorization: <API_KEY>` header (no `Bearer` prefix). The server replies with a `Begin` message once the session is open.
- **Transport**: Binary PCM frames over the socket
- **Output**: Forwards only the formatted, end-of-turn `Turn` messages so the editor never receives draft text
- **Graceful stop**: Sends `{"type":"Terminate"}` and closes after the server replies with the final `Turn` and `Termination`

### VoiceInputManager.kt
Orchestrates recording, AssemblyAI streaming, and ordered transcript delivery.
- **State machine**: IDLE → RECORDING ↔ PAUSED → IDLE
- **Buffered audio**: Holds PCM chunks until `Begin`
- **Transcript queue**: Preserves FIFO delivery of finalized AssemblyAI turns, including whether a segment attaches to previous text
- **Reconnects**: Retries transient WebSocket failures while the recording session remains active
- **Session config**: Maps preferences (speech model, format toggle, end-of-turn confidence/silence, keyterms, EU endpoint) to AssemblyAI URL parameters
- **New Paragraph Timer**: Requests a paragraph break after long silence
- **Graceful stop**: Lets the tail turn drain before closing the socket

### LatinIME.java
Main orchestrator that coordinates all components and inserts text into the editor.
- Uses `InputConnection.commitText(...)` at the caret, or to replace the active selection when text is highlighted
- Calls `mInputLogic.finishInput()` first to keep composing state in sync
- Runs `TranscriptPostProcessor` for spelled-out punctuation and mid-sentence casing/punctuation correction
- Defers paragraph insertion until manager processing is idle if needed

## Data Flow Steps

### 1. Recording Start
```
User taps mic button
    → LatinIME.onVoiceInputClicked()
    → VoiceInputManager.toggleRecording()
    → VoiceRecorder.startRecording()
    → State = RECORDING
```

### 2. Speech → AssemblyAI
```
User speaks
    → VoiceRecorder captures PCM chunks
    → VoiceInputManager buffers/sends them to AssemblyAITranscriptionClient
    → AssemblyAITranscriptionClient streams them to AssemblyAI
    → AssemblyAI emits running Turn messages and ultimately end_of_turn=true
```

### 2b. AssemblyAI session config
```
Transcription preferences
    → VoiceInputManager.startStreamingSession()
    → speech_model = configured model (defaults to universal-streaming-english)
    → format_turns = true
    → end_of_turn_confidence_threshold = sanitized 0–1 value
    → min_turn_silence / max_turn_silence sanitized to documented ranges
    → keyterms_prompt = default keyterms ∪ user keyterms (≤ 100 entries, ≤ 50 chars each)
    → use EU endpoint if configured
```

### 3. Transcript → Immediate Insert
```
end_of_turn=true (and turn_is_formatted=true when format_turns=true) arrives
    → AssemblyAITranscriptionClient surfaces TranscriptSegment(text, attachesToPrevious)
    → VoiceInputManager queues and delivers it in FIFO order
    → LatinIME trims empty turns, conditionally restores a leading space, runs casing/punctuation post-processing, and commits via InputConnection.commitText(...) (replacing any active selection)
```

### 4. New Paragraph
```
Speech stops locally
    → VoiceInputManager starts new paragraph timer
    → Delay elapses with no speech
    → LatinIME.onNewParagraphRequested()
    → Insert "\n\n" when processing is idle
```

## State Management

### Voice Input States
```
IDLE       → User taps mic    → RECORDING
RECORDING  → User taps mic    → IDLE (stop)
RECORDING  → User taps pause  → PAUSED
PAUSED     → User taps pause  → RECORDING (resume)
```

### Ordering Guarantees
- AssemblyAI end-of-turn results are queued and delivered in FIFO order by `VoiceInputManager`.
- `LatinIME` inserts each finalized transcript immediately when received.
- Paragraph breaks are deferred until manager processing drains, so they do not interleave in the middle of pending transcript insertion.
- Cancelling voice input invalidates the active manager session so stale AssemblyAI callbacks are dropped before they reach the IME.

## Configuration

### Settings (TranscriptionScreen.kt)
- **AssemblyAI API key**: Required for transcription.
- **Speech model**: AssemblyAI requires this on every connection. Default `universal-streaming-english`.
- **Formatted final transcripts**: `format_turns=true`. Recommended on for dictation; provides punctuation, casing, and inverse text normalization.
- **End-of-turn confidence (0–100)**: Higher values make the model wait for the speaker to actually finish a thought. Default 70 (above AssemblyAI's API default of 40).
- **Min turn silence (ms)**: Floor before a semantic end-of-turn check fires.
- **Max turn silence (ms)**: Hard ceiling beyond which a turn is forced closed.
- **EU endpoint**: switch to `streaming.eu.assemblyai.com`.
- **Custom keyterms**: newline-separated list of brand names, technical terms, contacts to boost.
- **Chunk silence / silence threshold / new paragraph silence / auto-stop silence**: local-only mic timing knobs unrelated to AssemblyAI.

### Silence Detection (VoiceRecorder.kt)
```kotlin
silenceThreshold (configurable via settings)
silenceDurationMs (configurable via settings)
MIN_SILENCE_DURATION_MS = 1000L
MAX_SILENCE_DURATION_MS = 30000L
```

## Error Handling

- **Network/transcription failures**: surfaced to the user; recording may continue or stop depending on stream state.
- **Empty transcriptions**: ignored.
- **Session cancellation**: pending stream/transcript work is invalidated through the manager session ID.
- **Insertion failures**: logged and the processing indicator is cleared.

## Thread Safety

Callbacks are marshalled back to the main thread before UI/editor operations:
- Audio recording runs on a background thread.
- AssemblyAI callbacks are forwarded onto the main thread.
- Timer callbacks run on the main thread.

This keeps text insertion sequential and avoids concurrent editor mutations.
