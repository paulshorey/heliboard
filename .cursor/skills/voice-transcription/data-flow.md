# Voice Transcription Data Flow

End-to-end voice transcription pipeline: local capture, Speechmatics realtime streaming, FIFO transcript delivery, and immediate caret insertion.

## Overview

1. **VoiceRecorder** captures PCM16 audio locally; silence detection drives paragraph breaks and auto-stop.
2. **SpeechmaticsTranscriptionClient** opens the realtime websocket, sends `StartRecognition`, streams binary PCM frames, tracks `AudioAdded` acknowledgements, can request `ForceEndOfUtterance`, reconstructs finalized transcript text from tokenized `results`, and surfaces finalized transcript segments.
3. **VoiceInputManager** buffers audio until the stream is ready, retries broken sessions, derives a provider config from preferences + current subtype locale, queues finalized transcript segments in FIFO order, and performs graceful `EndOfStream` shutdown.
4. **LatinIME** inserts each finalized transcript immediately at the current caret position through `InputConnection`, restoring a leading space only when the new segment is a continuation of previous text rather than punctuation.

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
- **Startup**: Sends `StartRecognition` with raw PCM16 audio settings plus a configurable `transcription_config`
- **Transport**: Binary PCM frames over the socket
- **Acks**: Tracks `AudioAdded.seq_no` so `EndOfStream(last_seq_no=...)` can be sent safely on stop
- **Turn flush**: Sends `ForceEndOfUtterance` before `EndOfStream` during graceful stop to help flush the tail transcript
- **Output**: Rebuilds finalized spans from Speechmatics `results[]` tokens so spacing and punctuation attachment follow `attaches_to`

### VoiceInputManager.kt
Orchestrates recording, Speechmatics streaming, and ordered transcript delivery.
- **State machine**: IDLE → RECORDING ↔ PAUSED → IDLE
- **Buffered audio**: Holds PCM chunks until `RecognitionStarted`
- **Transcript queue**: Preserves FIFO delivery for finalized Speechmatics segments, including whether a segment attaches to previous text
- **Reconnects**: Retries transient websocket failures while the recording session remains active
- **Session config**: Maps current subtype locale to Speechmatics `language` and optional `output_locale`; sanitizes max-delay, conservative punctuation sensitivity, and disfluency settings from preferences
- **New Paragraph Timer**: Requests a paragraph break after long silence
- **Graceful stop**: Waits for pending acks, sends `ForceEndOfUtterance`, then `EndOfStream`, and lets the tail transcript drain

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

### 2b. Speechmatics session config
```
Current subtype locale + transcription preferences
    → VoiceInputManager.buildTranscriptionConfig()
    → language = base language / supported provider language
    → output_locale = locale-specific spelling when Speechmatics documents it
    → max_delay / end_of_utterance_silence_trigger sanitized to provider-safe ranges
    → remove_disfluencies enabled only for English when requested
```

### 3. Transcript → Immediate Insert
```
Finalized transcript span arrives
    → SpeechmaticsTranscriptionClient rebuilds text from token results
    → attaches_to metadata determines whether a leading space is needed
    → VoiceInputManager queues and delivers the segment to LatinIME in FIFO order
    → LatinIME trims empty spans, conditionally restores a leading space, and commits the finalized text at the caret via InputConnection.commitText(...)
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
- **Final transcript delay**: Upper bound for Speechmatics finalization latency
- **Punctuation sensitivity**: Lower values make Speechmatics more conservative about inserting punctuation
- **End of utterance trigger**: Server-side silence duration before Speechmatics finalizes an utterance (disabled by default for dictation)
- **Remove disfluencies**: Removes English hesitation sounds like “um” and “uh”
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
