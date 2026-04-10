# Voice Transcription Data Flow

End-to-end voice transcription pipeline: local capture, Speechmatics realtime streaming, FIFO transcript delivery, and immediate caret insertion.

## Overview

1. **VoiceRecorder** captures PCM16 audio locally; silence detection drives paragraph breaks and auto-stop.
2. **SpeechmaticsTranscriptionClient** opens the realtime websocket, sends `StartRecognition`, streams binary PCM frames, tracks `AudioAdded` acknowledgements, and surfaces finalized `AddTranscript` text.
3. **VoiceInputManager** buffers audio until the stream is ready, retries broken sessions, queues finalized transcript spans in FIFO order, and performs graceful `EndOfStream` shutdown.
4. **LatinIME** inserts each finalized transcript immediately at the current caret position through `InputConnection`.

## Architecture

```
┌─────────────────┐     ┌──────────────────────┐     ┌──────────────────────┐
│   Microphone    │────▶│   VoiceRecorder      │────▶│  Speechmatics RT API │
│   (Hardware)    │     │   (PCM16 16kHz)      │     │  (WebSocket /v2/)    │
└─────────────────┘     │   Silence detection  │     └──────────┬───────────┘
                        │   Chunking/timers     │                ▼
                        └──────────────────────┘     ┌──────────────────────┐
┌─────────────────┐     ┌──────────────────────┐◀────│  Finalized transcript │
│   Text Field    │◀────│   LatinIME           │     │  spans (AddTranscript)│
│   (App)         │     │   (Orchestrator)     │     └──────────────────────┘
└─────────────────┘     └──────────────────────┘
```

## Components

### VoiceRecorder.kt
Captures audio from the microphone with client-side silence detection.
- **Format**: PCM16, 16kHz, mono
- **Silence detection**: Adaptive RMS threshold on each 100ms chunk
- **Callbacks**: Supplies PCM chunks to `VoiceInputManager`; long silence can request a new paragraph or auto-stop

### SpeechmaticsTranscriptionClient.kt
WebSocket client for Speechmatics realtime transcription.
- **URL**: `wss://eu.rt.speechmatics.com/v2/`
- **Handshake**: `Authorization: Bearer <API_KEY>`
- **Startup**: Sends `StartRecognition` with raw PCM16 audio settings
- **Transport**: Binary PCM frames over the socket
- **Acks**: Tracks `AudioAdded.seq_no` so `EndOfStream(last_seq_no=...)` can be sent safely on stop
- **Output**: Finalized `AddTranscript.metadata.transcript` spans delivered in stream order

### VoiceInputManager.kt
Orchestrates recording, Speechmatics streaming, and ordered transcript delivery.
- **State machine**: IDLE → RECORDING ↔ PAUSED → IDLE
- **Buffered audio**: Holds PCM chunks until `RecognitionStarted`
- **Transcript queue**: Preserves FIFO delivery for finalized Speechmatics spans
- **Reconnects**: Retries transient websocket failures while the recording session remains active
- **New Paragraph Timer**: Requests a paragraph break after long silence
- **Graceful stop**: Waits for pending acks, sends `EndOfStream`, then lets the tail transcript drain

### LatinIME.java
Main orchestrator that coordinates all components and inserts text into the editor.
- Uses `InputConnection.commitText(...)` at the caret
- Calls `mInputLogic.finishInput()` first to keep composing state in sync
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

### 2. Speech → Speechmatics
```
User speaks
    → VoiceRecorder captures PCM chunks
    → VoiceInputManager buffers/sends them to SpeechmaticsTranscriptionClient
    → SpeechmaticsTranscriptionClient streams them to Speechmatics
    → Speechmatics emits finalized AddTranscript messages
```

### 3. Transcript → Immediate Insert
```
Finalized transcript span arrives
    → SpeechmaticsTranscriptionClient.onTranscriptionResult(text)
    → VoiceInputManager queues and delivers the text to LatinIME in FIFO order
    → LatinIME trims empty spans and commits the finalized text at the caret via InputConnection.commitText(...)
```

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
- Speechmatics transcript spans are queued and delivered in FIFO order by `VoiceInputManager`.
- `LatinIME` inserts each finalized transcript immediately when received.
- Paragraph breaks are deferred until manager processing drains, so they do not interleave in the middle of pending transcript insertion.
- Cancelling voice input invalidates the active manager session so stale Speechmatics callbacks are dropped before they reach the IME.

## Configuration

### Settings (TranscriptionScreen.kt)
- **Speechmatics API Key**: Required for transcription
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
- Speechmatics callbacks are forwarded onto the main thread
- Timer callbacks run on the main thread

This keeps text insertion sequential and avoids concurrent editor mutations.
