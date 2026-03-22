# Voice Transcription Data Flow

This document describes the end-to-end voice transcription pipeline: local capture, Deepgram
streaming, a small local filter hook on each finalized span, and immediate caret insertion.

## Overview

The voice input system uses **local recording + streaming transcription**:
1. **VoiceRecorder** captures PCM16 audio locally; silence detection drives paragraph breaks and auto-stop.
2. **Deepgram API** streams audio and returns finalized transcript spans.
3. **VoicePostTranscriptionFilter** applies local post-processing to the transcript text.
4. **LatinIME** inserts the processed text immediately at the current caret position through `InputConnection`.

## Architecture

```
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│   Microphone    │────▶│   VoiceRecorder      │────▶│  Deepgram API   │
│   (Hardware)    │     │   (PCM16 16kHz)      │     │  (WebSocket /v1/listen)
└─────────────────┘     │   Silence detection  │     └────────┬────────┘
                        │   Chunking/timers     │              ▼
                        └──────────────────────┘     ┌─────────────────┐
┌─────────────────┐     ┌──────────────────────┐◀────│  Transcription  │
│   Text Field    │◀────│   LatinIME           │     │  Result (text)  │
│   (App)         │     │   (Orchestrator)     │     └─────────────────┘
└─────────────────┘     └──────────────────────┘
```

## Key Design Principle: Instant Recording

Recording starts **instantly** when the user presses the microphone button. The microphone
is local and does not wait on any network round-trip. Network access is only needed after
audio has already been captured and is sent for transcription.

## Components

### VoiceRecorder.kt
Captures audio from the microphone with client-side silence detection.
- **Format**: PCM16, 16kHz, mono
- **Silence detection**: Adaptive RMS threshold on each 100ms chunk
- **Callbacks**: Supplies PCM chunks to `VoiceInputManager`; long silence can request a new paragraph or auto-stop

### DeepgramTranscriptionClient.kt
WebSocket client for Deepgram live transcription.
- **URL**: `wss://api.deepgram.com/v1/listen`
- **Transport**: Raw PCM frames over the socket
- **Output**: Finalized transcript spans delivered in stream order

### VoiceInputManager.kt
Orchestrates recording, Deepgram streaming, and ordered transcript delivery.
- **State machine**: IDLE → RECORDING ↔ PAUSED → IDLE
- **Chunk Watchdog**: Forces a segment flush if silence detection misses a boundary
- **Transcript queue**: Preserves FIFO delivery for finalized Deepgram spans
- **New Paragraph Timer**: Requests a paragraph break after long silence

### VoicePostTranscriptionFilter.java
Local filter hook that runs on each transcript span before insertion.
- **Current behavior**: placeholder passthrough
- **Purpose**: central place for future deterministic rules on each chunk (length, substitutions, etc.)

### LatinIME.java
Main orchestrator that coordinates all components and inserts text into the editor.
- Uses `InputConnection.commitText(...)` at the caret
- Calls `mInputLogic.finishInput()` first to keep composing state in sync
- Defers paragraph insertion until manager processing is idle if needed

## Data Flow

### 1. Recording Start
```
User taps mic button
    → LatinIME.onVoiceInputClicked()
    → VoiceInputManager.toggleRecording()
    → VoiceRecorder.startRecording()
    → State = RECORDING
```

### 2. Speech → Deepgram
```
User speaks
    → VoiceRecorder captures PCM chunks
    → VoiceInputManager streams them to Deepgram
    → Deepgram emits finalized transcript text
```

### 3. Transcript → Local Processing → Immediate Insert
```
Finalized transcript span arrives
    → VoiceInputManager delivers it to LatinIME in FIFO order
    → LatinIME applies VoicePostTranscriptionFilter
    → LatinIME adjusts capitalization based on text before the caret
    → LatinIME appends a trailing space if needed
    → LatinIME commits the text at the caret via InputConnection.commitText(...)
```

There is no second pass over the field: each processed chunk is inserted at the current
caret via `commitText` only.

### 4. New Paragraph
```
Speech stops
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
- Deepgram transcript spans are queued and delivered in FIFO order by `VoiceInputManager`.
- `LatinIME` inserts each processed transcript immediately when received.
- Paragraph breaks are deferred until manager processing drains, so they do not interleave
  in the middle of pending transcript insertion.
- Cancelling voice input invalidates the active manager session so stale Deepgram callbacks
  are dropped before they reach the IME.

## Configuration

### Settings (TranscriptionScreen.kt)
- **Deepgram API Key**: Required for transcription
- **Chunk Silence Duration**: Silence window before detecting a speech boundary
- **Silence Threshold**: RMS threshold floor for silence/speech detection
- **New Paragraph Silence Duration**: Delay before inserting a paragraph break
- **Auto-stop Silence Duration**: Delay before automatically stopping voice recording

### Silence Detection (VoiceRecorder.kt)
```kotlin
silenceThreshold (configurable via settings)
silenceDurationMs (configurable via settings)
MIN_SILENCE_DURATION_MS = 1000L
MAX_SILENCE_DURATION_MS = 30000L
```

## Error Handling

- **Network/transcription failures**: surfaced to the user; recording may continue or stop depending on stream state
- **Empty transcriptions**: ignored
- **Session cancellation**: pending stream/transcript work is invalidated through the manager session ID
- **Insertion failures**: logged and the processing indicator is cleared

## Thread Safety

Callbacks are marshalled back to the main thread before UI/editor operations:
- Audio recording runs on a background thread
- Deepgram callbacks are forwarded onto the main thread
- Timer callbacks run on the main thread

This keeps text insertion sequential and avoids concurrent editor mutations.
