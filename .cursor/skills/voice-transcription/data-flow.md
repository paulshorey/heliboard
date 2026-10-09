# Voice Transcription Data Flow

End-to-end voice transcription pipeline: local capture, Gemini Live real-time streaming, FIFO transcript delivery, and immediate caret insertion.

## Overview

1. **VoiceRecorder** captures PCM16 audio locally; silence detection drives an early turn finalize at speech boundaries and auto-stop after a longer pause.
2. **GeminiTranscriptionClient** opens the Live API WebSocket, sends one `setup` frame, waits for `setupComplete`, streams base64 PCM as JSON text frames, parses `serverContent` transcripts, and surfaces finalized transcript segments.
3. **VoiceInputManager** requires a usable network, buffers audio and control messages until readiness, stops broken or expiring sessions, derives a session config from preferences + subtype locale + editor text, queues finalized segments in FIFO order, and performs the graceful `audioStreamEnd` shutdown.
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
- **Output**: only `serverContent.inputTranscription` creates editor segments; interims remain provisional forever; ignores `modelTurn`
- **Turn finalize**: `{"realtimeInput":{"audioStreamEnd":true}}` is queued behind preceding PCM; every captured chunk is forwarded, including quiet audio
- **Graceful stop**: sends EOF after the recorder tail, reads for 8 s before closing; a nonempty socket queue at that deadline is failure
- **Schema resilience**: retries one `SetupTier` lower when the server closes with 1007, caching the working tier in `negotiatedSetupTier`

### VoiceContextVocabulary.kt
Builds `inputAudioTranscription.customVocabulary`.
- Merges user terms, built-in terms, and proper nouns harvested from editor text, nearest the caret first
- Capped at 100 terms; only individual words are sent, never the editor text

### VoiceInputManager.kt
Orchestrates recording, Gemini streaming, and ordered transcript delivery.
- **State machine**: IDLE → RECORDING ↔ PAUSED → IDLE
- **Buffered audio/control FIFO**: retains up to 960,000 PCM bytes until transport acceptance. Pause, silence, and stop cannot overtake earlier audio. The socket queue holds at most 256 KiB of encoded audio JSON; local backpressure retries the same head every 25 ms.
- **Transcript FIFO**: retains each head until the listener returns local dispatch/cleanup success. Android IPC does not return the host editor's commit result. Overflow at 64 entries is terminal, with no coalescing or skipping.
- **Terminal failure**: invalidate callback generations, stop capture, clear uncertain work, preserve inserted text, and mark possible loss with `[Dictation interrupted]` once. Editor rejection/exception suppresses a marker and stops later writes.
- **Explicit restart**: no reconnect, resumption, or rotation. `goAway` or nine minutes stops capture and drains; the next recording requires a mic tap.
- **Session config**: maps the subtype locale to a documented BCP-47 code, clamps `silenceDurationMs` to 400–5000 ms, and harvests vocabulary once per recording.
- **Deadlines**: 12 seconds across all setup tiers, 30 seconds for oldest queued PCM and pending-speech response/final progress, 15 seconds for EOF/close. PCM/interim updates cannot postpone missing final progress. Pure silence does not arm a speech deadline.
- **Final progress**: an earlier final cannot acknowledge annotated speech still in the PCM or socket queue. PCM carries capture-time raw-energy evidence, separate from the speaking silence window. Track the encoded quiet/control suffix in the socket FIFO so silence alone does not block a final. Keep pending speech until a later final arrives after queued speech drains.
- **Auto-stop**: prolonged local silence stops recording. Local speech-stop, pause, and stop enqueue `audioStreamEnd`.
- **Capture shutdown**: a missed two-second join is terminal before EOF; an old live thread blocks reuse and keeps its original recording callback.

### LatinIME.java
Main orchestrator that coordinates all components and inserts text into the editor.
- Uses `InputConnection.commitText(...)` at the caret, or replaces an active selection when text is highlighted
- Calls `mInputLogic.finishInput()` first to keep composing state in sync
- Applies pre-commit spacing/casing/trailing-punctuation shaping, then runs paragraph-level post-processing
- Wraps commit and post-processing in a wrapper batch edit, always closed in `finally`; temporarily closes/reopens the host batch before selection readback so deferred editors apply edits; reports false for rejected/throwing editor operations
- Verifies actual host selection positions and selected text before committing cleanup/punctuation replacements without deleting the original first; on failure, restores the caret best-effort and refreshes caches without retrying text
- Prepares paragraph correction from the current cache, then verifies matching original host text after flushing; ignores delayed intermediate selection callbacks only when the host's current caret matches the expected voice caret
- Literal interruption markers bypass casing and paragraph cleanup; always verify the current caret, even when the cached selection is collapsed, and preserve highlighted text by moving to a verified caret at its end, or skip insertion if the move cannot be verified
- Supplies editor text for vocabulary harvesting through `buildVoiceContextText`

## Data Flow Steps

### 1. Recording Start
```
User taps mic
    → LatinIME.onVoiceInputClicked()
    → VoiceInputManager.toggleRecording()
    → VoiceNetworkMonitor.start() (preflight + loss observation)
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
    → interimInputTranscription signals pending speech, never editor text
    → inputTranscription goes through TranscriptAccumulator
        · extends the previous transcript → emit only the suffix
        · unrelated text                  → emit all of it
        · identical repeat                → emit nothing
    → attachesToPrevious = (starts with attaching punctuation OR resumes mid-word)
    → VoiceInputManager queues and delivers the segment to LatinIME in FIFO order
    → LatinIME conditionally adds a leading space and commits via InputConnection
    → only an accepted commit/cleanup removes the FIFO head; failure ends the session
    → turnComplete resets the accumulator, without acknowledging audio
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
- `LatinIME` returns local dispatch/cleanup success synchronously; a rejected operation or unverifiable replacement selection blocks every later segment. This is not confirmation of complete insertion by a remote host editor.
- The same outgoing FIFO orders PCM and control frames. No buffered audio is evicted to admit later audio.
- IDLE during graceful drain does not allow a new recording until the old connection closes.
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

Network/transport failure, unexpected close, malformed JSON, invalid PCM, queue
overflow, or progress timeout terminates the recording. Callbacks from that
recording become stale before capture is stopped. Accepted editor text remains;
uncertain audio produces one literal interruption marker. Connectivity restoration
cannot append an automatic suffix. A user cancellation discards pending work
without a marker. Editor rejection/exception is terminal with no retry or marker.

The only retry is a 1007 setup-schema fallback before `setupComplete`; auth
failures are surfaced, and every tier shares the original connection deadline.

Neither `send(true)`, `queueSize()`, nor `turnComplete` proves all audio arrived.
These guards enforce local order and detect uncertainty; they cannot certify every
word recognized upstream. See `docs/gemini-transcription.md` for source links and
device acceptance checks.

## Thread Safety

Callbacks are marshalled back to the main thread before UI/editor operations:
- Audio recording runs on a background thread
- Gemini callbacks are forwarded onto the main thread
- Timer callbacks run on the main thread

This keeps text insertion sequential and avoids concurrent editor mutations.
