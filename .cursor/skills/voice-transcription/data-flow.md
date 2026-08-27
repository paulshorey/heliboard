# Voice Transcription Data Flow

End-to-end voice transcription pipeline: local capture, Gemini Live real-time streaming, FIFO transcript delivery, and immediate caret insertion.

## Overview

1. **VoiceRecorder** captures PCM16 audio locally; silence detection drives an early turn finalize at speech boundaries and auto-stop after a longer pause.
2. **GeminiTranscriptionClient** opens the Live API WebSocket, sends one `setup` frame, waits for `setupComplete`, streams base64 PCM as JSON text frames, parses `serverContent` transcripts, and surfaces finalized transcript segments.
3. **VoiceInputManager** buffers audio until the session is ready, retries broken sessions, rotates sessions before the 10-minute cap, derives a session config from preferences + subtype locale + editor text, queues finalized segments in FIFO order, and performs the graceful `audioStreamEnd` shutdown.
4. **LatinIME** inserts each finalized transcript immediately at the current caret position through `InputConnection`, adding a leading space only when the segment is not attaching to previous text and surrounding editor text needs a separator.

## Architecture

```
┌─────────────────┐     ┌──────────────────────┐     ┌──────────────────────┐
│   Microphone    │────▶│   VoiceRecorder      │────▶│  Gemini Live API     │
│   (Hardware)    │     │   (PCM16 16kHz)      │     │  (WebSocket)         │
└─────────────────┘     │   Silence detection  │     └──────────┬───────────┘
                        │   Chunking/timers    │                ▼
                        └──────────────────────┘     ┌──────────────────────┐
┌─────────────────┐     ┌──────────────────────┐◀────│  serverContent       │
│   Text Field    │◀────│   LatinIME           │     │  .inputTranscription │
│   (App)         │     │   (Orchestrator)     │     └──────────────────────┘
└─────────────────┘     └──────────────────────┘
```

## Components

### VoiceRecorder.kt
Captures audio from the microphone with client-side silence detection.
- **Format**: PCM16 little-endian, 16 kHz, mono — the Live API's native input format
- **Silence detection**: adaptive RMS threshold on each 100 ms chunk
- **Callbacks**: supplies PCM chunks to `VoiceInputManager`; speech-stop silence requests a turn finalize, and longer silence requests auto-stop

### GeminiTranscriptionClient.kt
WebSocket client for the Gemini Live API.
- **URL**: `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=<API_KEY>`
- **Model**: `models/gemini-3.5-transcribe-live` (the `models/` prefix is required on the raw socket)
- **Authentication**: API key in the query string; no HTTP header
- **Startup**: sends one `setup` text frame at the negotiated `SetupTier`, then **waits for `{"setupComplete":{}}`** before reporting the stream ready
- **Transport**: JSON text frames only; audio is base64 inside `realtimeInput.audio`
- **Output**: commits `serverContent.inputTranscription` through `TranscriptAccumulator`; discards `interimInputTranscription` and ignores `modelTurn`
- **Turn finalize**: `{"realtimeInput":{"audioStreamEnd":true}}` ends the turn without ending the session
- **Graceful stop**: sends `audioStreamEnd`, keeps reading for 8 s, closes 1000
- **Schema resilience**: retries one `SetupTier` lower when the server closes with 1007, caching the working tier in `negotiatedSetupTier`

### VoiceContextVocabulary.kt
Builds `inputAudioTranscription.customVocabulary`.
- Merges user terms, built-in terms, and proper nouns harvested from editor text, nearest the caret first
- Capped at 100 terms; only individual words are sent, never the editor text

### VoiceInputManager.kt
Orchestrates recording, Gemini streaming, and ordered transcript delivery.
- **State machine**: IDLE → RECORDING ↔ PAUSED → IDLE
- **Buffered audio**: holds PCM chunks until `setupComplete` arrives
- **Transcript queue**: preserves FIFO delivery, including `attachesToPrevious`; coalesces oldest entries if the queue reaches 64
- **Reconnects**: retries transient WebSocket failures while the session is active (3 attempts, exponential backoff)
- **Session rotation**: on `goAway` (1.5 s before the announced deadline) and unconditionally after 9 minutes; does not consume a reconnect attempt
- **Session config**: maps the subtype locale to a documented BCP-47 code, clamps `silenceDurationMs` to 400–5000 ms
- **Auto-stop timer**: stops recording after prolonged silence
- **Turn finalize**: sends `audioStreamEnd` after local speech-stop silence, on mic pause, and before graceful stop
- **Response watchdog**: 15 s (generous, because the session is tuned to prefer a correct transcript over a fast one); logs `VOICE_RESPONSE` lines for diagnostics

### LatinIME.java
Main orchestrator that coordinates all components and inserts text into the editor.
- Uses `InputConnection.commitText(...)` at the caret, or replaces an active selection when text is highlighted
- Calls `mInputLogic.finishInput()` first to keep composing state in sync
- Applies pre-commit spacing/casing/trailing-punctuation shaping, then runs paragraph-level post-processing
- Wraps commit and post-processing in one batch edit so intermediate `onUpdateSelection` callbacks do not cancel voice input
- Supplies editor text for vocabulary harvesting through `buildVoiceContextText`

## Data Flow Steps

### 1. Recording Start
```
User taps mic
    → LatinIME.onVoiceInputClicked()
    → VoiceInputManager.toggleRecording()
    → VoiceRecorder.startRecording()
    → State = RECORDING
```

### 2. Speech → Gemini
```
User speaks
    → VoiceRecorder captures PCM chunks (~100 ms)
    → VoiceInputManager buffers them until setupComplete
    → GeminiTranscriptionClient sends {"realtimeInput":{"audio":{"data":"<base64>","mimeType":"audio/pcm;rate=16000"}}}
    → Gemini emits serverContent with interim then finalized transcriptions
```

### 2b. Gemini session config
```
Active subtype locale + transcription preferences + editor text
    → GeminiTranscriptionClient.buildSessionConfig()
    → languageCodes  = one documented BCP-47 code, or [] to auto-detect
    → mode           = SMART | VERBATIM
    → customVocabulary = user terms ∪ built-in terms ∪ editor-harvested terms (≤ 100)
    → silenceDurationMs = PREF_GEMINI_END_OF_SPEECH_SILENCE_MS (400–5000, default 1500)
    → endOfSpeechSensitivity = END_SENSITIVITY_LOW
    → startOfSpeechSensitivity = START_SENSITIVITY_HIGH, prefixPaddingMs = 300
    → model = "models/gemini-3.5-transcribe-live", responseModalities = ["TEXT"]
```

### 3. Transcript → Immediate Insert
```
serverContent arrives
    → interimInputTranscription is discarded (never committed)
    → inputTranscription goes through TranscriptAccumulator
        · extends the previous transcript → emit only the suffix
        · unrelated text                  → emit all of it
        · identical repeat                → emit nothing
    → attachesToPrevious = (starts with attaching punctuation OR resumes mid-word)
    → VoiceInputManager queues and delivers the segment to LatinIME in FIFO order
    → LatinIME conditionally adds a leading space and commits via InputConnection.commitText(...)
    → turnComplete resets the accumulator
```

### 4. Explicit New Paragraph Command
```
User says "New paragraph."
    → Gemini finalizes the text
    → LatinIME commits it
    → TranscriptPostProcessor replaces the spoken command with "\n\n"
```

## State Management

### Voice Input States
```
IDLE       → User taps mic    → RECORDING
RECORDING  → User taps mic    → IDLE (stop)
RECORDING  → User taps pause  → PAUSED   (also sends audioStreamEnd)
PAUSED     → User taps pause  → RECORDING (resume)
```

### Ordering Guarantees
- Transcript segments are queued and delivered in FIFO order by `VoiceInputManager`.
- `LatinIME` inserts each finalized transcript immediately when received.
- Silence-driven automatic paragraph breaks are disabled to avoid unintended host-app side effects.
- Cancelling voice input invalidates the active manager session so stale Gemini callbacks are dropped before they reach the IME.

## Configuration

### Settings (TranscriptionScreen.kt)
- **Google Gemini API Key**: required for transcription
- **Smart transcription**: `mode: SMART` — disfluency removal, self-correction resolution, punctuation/casing/list formatting. Off gives `VERBATIM`.
- **Learn names from the text field**: harvests proper nouns near the caret into `customVocabulary`
- **Custom voice vocabulary**: opens `VoiceVocabularyScreen` to view built-in terms and edit the user's own (one per line, merged at session start)
- **Detect spoken language**: sends `languageCodes: []` instead of the subtype's language. Off by default because auto-detection misfires on short utterances.
- **End-of-speech pause (ms)**: `silenceDurationMs`, 400–5000, default 1500. Higher is more accurate.
- **Chunk Silence Duration**: local pause that triggers `audioStreamEnd` as a backstop; keep it longer than the end-of-speech pause
- **Silence Threshold**: RMS threshold floor for silence/speech detection
- **Auto-stop Silence Duration**: delay before automatically stopping voice recording

Gemini decides punctuation itself. HeliBoard's levers are `mode`, the end-of-speech window, `customVocabulary`, and the language hint. Casing and punctuation continuity with already-typed text is handled locally by `TranscriptPostProcessor`, which also strips leftover comma-attached fillers such as "um," and "uh,".

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
- **Close code 1007**: retried at a lower `SetupTier` when the reason is a schema problem; surfaced as an error when the reason names an auth status
- **In-band `error` payloads**: routed to `onStreamError`, mapping gRPC statuses (`UNAUTHENTICATED`, `PERMISSION_DENIED`, `RESOURCE_EXHAUSTED`, `FAILED_PRECONDITION`, …) to actionable messages

## Thread Safety

Callbacks are marshalled back to the main thread before UI/editor operations:
- Audio recording runs on a background thread
- Gemini callbacks are forwarded onto the main thread
- Timer callbacks run on the main thread

This keeps text insertion sequential and avoids concurrent editor mutations.
