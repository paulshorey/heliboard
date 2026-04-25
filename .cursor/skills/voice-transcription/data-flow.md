# Voice Transcription Data Flow

End-to-end voice transcription pipeline: local capture, Soniox real-time streaming, FIFO transcript delivery, and immediate caret insertion.

## Overview

1. **VoiceRecorder** captures PCM16 audio locally; silence detection drives paragraph breaks and auto-stop.
2. **SonioxTranscriptionClient** opens the real-time WebSocket, sends the JSON start config, streams binary PCM frames, parses `tokens` arrays from each response, and surfaces finalized transcript segments.
3. **VoiceInputManager** buffers audio until the start config is queued, retries broken sessions, derives a session config from preferences + current subtype locale, queues finalized transcript segments in FIFO order, and performs graceful empty-frame shutdown.
4. **LatinIME** inserts each finalized transcript immediately at the current caret position through `InputConnection`, restoring a leading space only when the new segment is a continuation of previous text rather than punctuation.

## Architecture

```
┌─────────────────┐     ┌──────────────────────┐     ┌──────────────────────┐
│   Microphone    │────▶│   VoiceRecorder      │────▶│  Soniox Realtime API │
│   (Hardware)    │     │   (PCM16 16kHz)      │     │  (WebSocket)         │
└─────────────────┘     │   Silence detection  │     └──────────┬───────────┘
                        │   Chunking/timers     │                ▼
                        └──────────────────────┘     ┌──────────────────────┐
┌─────────────────┐     ┌──────────────────────┐◀────│  Final-token spans   │
│   Text Field    │◀────│   LatinIME           │     │  (is_final: true)    │
│   (App)         │     │   (Orchestrator)     │     └──────────────────────┘
└─────────────────┘     └──────────────────────┘
```

## Components

### VoiceRecorder.kt
Captures audio from the microphone with client-side silence detection.
- **Format**: PCM16, 16kHz, mono
- **Silence detection**: Adaptive RMS threshold on each 100ms chunk
- **Callbacks**: Supplies PCM chunks to `VoiceInputManager`; long silence can request a new paragraph or auto-stop

### SonioxTranscriptionClient.kt
WebSocket client for Soniox real-time transcription.
- **URL**: `wss://stt-rt.soniox.com/transcribe-websocket`
- **Authentication**: `api_key` field inside the start config JSON; no HTTP headers
- **Startup**: Sends a single JSON config text frame (`api_key`, `model`, `audio_format`, `sample_rate`, `num_channels`, optional `language_hints`, plus the endpoint-detection and diarization flags)
- **Transport**: Binary PCM frames over the socket
- **Output**: Concatenates final-token text in arrival order (Soniox encodes inter-word whitespace inside token text), trims, and emits a `TranscriptSegment`
- **Graceful stop**: Sends an empty WebSocket frame, waits for `{"finished": true}` (8 s grace), closes 1000

### VoiceInputManager.kt
Orchestrates recording, Soniox streaming, and ordered transcript delivery.
- **State machine**: IDLE → RECORDING ↔ PAUSED → IDLE
- **Buffered audio**: Holds PCM chunks until the stream is ready
- **Transcript queue**: Preserves FIFO delivery for finalized Soniox segments, including whether a segment attaches to previous text
- **Reconnects**: Retries transient WebSocket failures while the recording session remains active (3 attempts with exponential backoff)
- **Session config**: Maps the active keyboard subtype's base language to a single Soniox `language_hints` entry; sanitizes `max_endpoint_delay_ms` to Soniox's 500–3000 ms range
- **New Paragraph Timer**: Requests a paragraph break after long silence
- **Graceful stop**: Posts a finalize task that the client converts into the empty-frame shutdown handshake

### LatinIME.java
Main orchestrator that coordinates all components and inserts text into the editor.
- Uses `InputConnection.commitText(...)` at the caret, or replaces an active selection when text is highlighted
- Calls `mInputLogic.finishInput()` first to keep composing state in sync
- Defers paragraph insertion until manager processing is idle if needed

## Data Flow Steps

### 1. Recording Start
```
User taps mic
    → LatinIME.onVoiceInputClicked()
    → VoiceInputManager.toggleRecording()
    → VoiceRecorder.startRecording()
    → State = RECORDING
```

### 2. Speech → Soniox
```
User speaks
    → VoiceRecorder captures PCM chunks
    → VoiceInputManager buffers/sends them to SonioxTranscriptionClient
    → SonioxTranscriptionClient streams binary PCM frames
    → Soniox emits JSON responses with `tokens` arrays
```

### 2b. Soniox session config
```
Active subtype locale + transcription preferences
    → SonioxTranscriptionClient.buildSessionConfig()
    → language_hints  = single ISO language code or omitted
    → enable_endpoint_detection / max_endpoint_delay_ms (500–3000)
    → enable_speaker_diarization
    → model = "stt-rt-preview", audio_format = "pcm_s16le", 16 kHz / mono
```

### 3. Transcript → Immediate Insert
```
Soniox response arrives
    → SonioxTranscriptionClient collects is_final:true tokens
    → Concatenates token text directly, trims
    → attachesToPrevious = (first char is . , ! ? : ; ) ] } %)
    → VoiceInputManager queues and delivers the segment to LatinIME in FIFO order
    → LatinIME conditionally restores a leading space and commits via InputConnection.commitText(...)
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
- Soniox transcript spans are queued and delivered in FIFO order by `VoiceInputManager`.
- `LatinIME` inserts each finalized transcript immediately when received.
- Paragraph breaks are deferred until manager processing drains, so they do not interleave in the middle of pending transcript insertion.
- Cancelling voice input invalidates the active manager session so stale Soniox callbacks are dropped before they reach the IME.

## Configuration

### Settings (TranscriptionScreen.kt)
- **Soniox API Key**: required for transcription
- **Speaker diarization**: when on, Soniox tags each token with a `speaker` ID and the client locks onto the first observed speaker
- **Enable endpoint detection**: lets Soniox finalize tokens immediately once it detects the speaker has stopped talking; reduces latency for dictation
- **Max endpoint delay (ms)**: Soniox-documented bounds 500–3000 (default 2000)
- **Chunk Silence Duration**: silence window before detecting a speech boundary
- **Silence Threshold**: RMS threshold floor for silence/speech detection
- **New Paragraph Silence Duration**: delay before inserting a paragraph break
- **Auto-stop Silence Duration**: delay before automatically stopping voice recording

Soniox decides punctuation automatically and does not expose a sensitivity knob, custom vocabulary, replacements list, output locale, or disfluency removal flag — none of those settings exist.

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
- **Soniox `error_code` JSON**: routed verbatim to `onStreamError` (e.g. `Authentication failed: Invalid API key`)

## Thread Safety

Callbacks are marshalled back to the main thread before UI/editor operations:
- Audio recording runs on a background thread
- Soniox callbacks are forwarded onto the main thread
- Timer callbacks run on the main thread

This keeps text insertion sequential and avoids concurrent editor mutations.
