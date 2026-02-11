# Voice Transcription Data Flow

This document describes the architecture and data flow for voice transcription with intelligent text cleanup.

## Overview

The voice input system uses a **local recording + batch transcription** architecture:
1. **VoiceRecorder** — captures audio locally, detects silence, emits WAV segments
2. **Deepgram API** — transcribes each audio segment into text
3. **Anthropic Claude API** — cleans up the transcribed text with recent context (capitalization, punctuation, grammar)

## Architecture

```
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│   Microphone    │────▶│   VoiceRecorder      │────▶│  Deepgram API   │
│   (Hardware)    │     │   (PCM16 16kHz)      │     │  (POST /v1/listen)
└─────────────────┘     │   Silence detection  │     └────────┬────────┘
                        │   WAV segmentation   │              │
                        └──────────────────────┘              │
                                                              ▼
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│   Text Field    │◀────│   LatinIME           │◀────│  Transcription  │
│   (App)         │     │   (Orchestrator)     │     │  Result (text)  │
└─────────────────┘     └──────────┬───────────┘     └─────────────────┘
                                   │
                                   ▼ (after 3s silence)
                        ┌──────────────────────┐     ┌─────────────────┐
                        │  TextCleanupClient   │────▶│  Anthropic API  │
                        │  (HTTP POST)         │◀────│  (Claude Haiku) │
                        └──────────────────────┘     └─────────────────┘
```

## Key Design Principle: Instant Recording

Recording starts **instantly** when the user presses the microphone button (~20ms).
There is **no network dependency** to begin recording. The microphone uses the device's
built-in AudioRecord API. Network is only needed later, when a completed audio segment
is sent for transcription.

## Components

### VoiceRecorder.kt
Captures audio from the microphone with client-side silence detection.
- **Format**: PCM16, 16kHz, mono
- **Silence detection**: Adaptive RMS threshold on each 100ms chunk
- **Segmentation**: After configured silence duration, emits accumulated audio as a WAV file
- **Output**: Complete WAV files (44-byte header + PCM data)

### DeepgramTranscriptionClient.kt
HTTP client for Deepgram's pre-recorded transcription API.
- **Endpoint**: `POST https://api.deepgram.com/v1/listen`
- **Model**: `nova-3`
- **Content-Type**: `audio/wav` (raw bytes in request body)
- **Features**: `smart_format=true`, `punctuate=true`
- **Retry**: Single automatic retry after 2s on transient failures (5xx, 408, timeout, connection error)

### VoiceInputManager.kt
Orchestrates the voice input flow and manages timers.
- **State machine**: IDLE → RECORDING ↔ PAUSED → IDLE
- **Chunk Watchdog** (dynamic): Forces a segment flush if silence detection misses a boundary
- **New Paragraph Timer** (configurable): Insert paragraph break after long silence

### TextCleanupClient.kt
HTTP client for Anthropic's Claude API.
- **Model**: `claude-haiku-4-5-20251001`
- **Purpose**: Intelligent capitalization, punctuation, and grammar cleanup
- **Input (system prompt)**: Cleanup instructions + optional reference context from earlier paragraphs
- **Input (user message)**: `{current paragraph text} + {new transcription}`
- **Output**: Corrected current paragraph (only this portion is replaced in editor)
- **max_tokens**: 4096 (accommodates full paragraph responses)
- **Cancellation**: Tracks active HTTP calls; `cancelAll()` cancels in-flight requests
- **Retry**: Single automatic retry after 2s on transient failures (5xx, 408, timeout, connection error)

### LatinIME.java
Main orchestrator that coordinates all components and manages text insertion.

## Data Flow

### 1. Recording Start (Instant)
```
User taps mic button
    → LatinIME.onVoiceInputClicked()
    → VoiceInputManager.toggleRecording()
    → VoiceRecorder.startRecording()     ← ~20ms, no network
    → State = RECORDING (red indicator)
    → Microphone is live, buffering audio
```

### 2. Speech → Silence → Segment
```
User speaks...
    → VoiceRecorder accumulates PCM data
    → User pauses (configured silence duration detected)
    → VoiceRecorder wraps PCM data in WAV header
    → onSegmentReady(wavData) callback
```

### 3. Transcription + Cleanup + Replace Current Paragraph
```
VoiceInputManager.enqueueSegment(wavData)
    → DeepgramTranscriptionClient.transcribe(wavData)
    → POST /v1/listen with audio/wav body
    → Deepgram returns JSON with transcript
    → onTranscriptionComplete(text)
    → LatinIME captures recent context (last 3 sentences or 300 chars)
    → Split at last newline:
        referenceContext = text before last \n  (read-only, for Claude's understanding)
        editableText    = text after last \n   (current paragraph, will be replaced)
    → Send to Claude:
        system prompt += referenceContext (do not include in response)
        user message   = editableText + new transcription
    → Claude returns corrected current paragraph
    → LatinIME.replaceContextWithCleanedText():
        1. Delete editableText.length() chars before cursor
        2. Insert corrected text + trailing space
    → Paragraph breaks untouched; corrected text appears in text field
```

**Context window**: The last ~3 sentences are gathered as context (detected by
simplified punctuation matching: `.!?:;=`). If fewer than 3 sentence boundaries exist,
up to 300 characters are used. This crosses newline/paragraph boundaries — even at the
start of a new line, Claude always has adequate context.

**Paragraph break protection**: The context is split at the last `\n`. Text before it
(earlier paragraphs) is passed to Claude in the system prompt for understanding only —
Claude is told not to include it in its response. Text after it (the current paragraph)
is sent as the user message and is what Claude cleans up. Only this current-paragraph
portion is replaced in the editor, so `\n` and `\n\n` paragraph breaks are never touched.

**Retry**: Both transcription and cleanup requests automatically retry once after a
2-second delay on transient failures (5xx, 408, socket timeout, connection error).
Non-retryable errors (4xx) are reported immediately.

### 4. New Paragraph (after configured silence window)
```
Speech stops
    → VoiceInputManager starts new paragraph timer
    → Configured delay passes with no speech
    → LatinIME.onNewParagraphRequested()
    → Insert "\n\n" to start new paragraph
```

## State Management

### Voice Input States
```
IDLE       → User taps mic    → RECORDING
RECORDING  → User taps mic    → IDLE (stop)
RECORDING  → User taps pause  → PAUSED
PAUSED     → User taps pause  → RECORDING (resume)
```

### Race Condition Prevention
```java
mCleanupInProgress      // true while cleanup API call is in flight
mPendingNewParagraph    // true if paragraph break waiting for cleanup
mPendingTranscription   // StringBuilder for queued transcription during cleanup
mVoiceSessionId         // incremented on cancel/new session; stale callbacks are discarded
```

**Ordering guarantees:**
- When cleanup is in progress, new transcriptions are queued in `mPendingTranscription`
  and applied (with a fresh cleanup round) after the current cleanup completes.
- At the manager layer, audio chunks are transcribed in FIFO order (one request at a time).
- `mVoiceSessionId` invalidates all in-flight async callbacks when the session changes
  (user typed, cursor moved, recording cancelled, etc.).
- `TextCleanupClient.cancelAll()` is called on session cancellation to cancel HTTP requests.

**Context replacement safety:**
- The `replacementLength` (editable text after last `\n`) is captured *before* the
  cleanup request is sent and closed over in the callback. Since `mCleanupInProgress`
  prevents any text insertion during cleanup, the text before the cursor is guaranteed
  to be unchanged when the callback fires (within the same session).
- Paragraph breaks (`\n`, `\n\n`) are outside the replacement scope — they are never
  deleted or overwritten.

## Configuration

### Settings (TranscriptionScreen.kt)
- **Deepgram API Key**: Required for transcription
- **Anthropic API Key**: Required for cleanup (optional feature)
- **Cleanup Prompt**: Customizable instructions for Claude
- **Chunk Silence Duration**: Silence window before cutting a chunk
- **Silence Threshold**: RMS threshold floor for silence/speech detection
- **New Paragraph Silence Duration**: Delay before inserting a paragraph break

### Timers (VoiceInputManager.kt)
```kotlin
MIN_CHUNK_WATCHDOG_MS = 20000L // Base watchdog lower bound
newParagraphDelayMs (configurable via settings)
```

### Silence Detection (VoiceRecorder.kt)
```kotlin
silenceThreshold (configurable via settings) // RMS threshold floor
silenceDurationMs (configurable via settings)
MIN_SEGMENT_MS = 500L          // Minimum segment length
MAX_SEGMENT_MS = 60000L        // Force-split at 60s
```

## Error Handling

- **Transient failures** (5xx, 408, timeout, connection): Automatically retried once after 2s delay
- **Network errors**: Audio is captured locally; if transcription fails after retry, the segment is lost but recording continues
- **API errors**: Logged and surfaced to user (invalid key, rate limit, etc.)
- **Empty transcriptions**: Silently ignored
- **Cleanup errors**: Raw transcription is inserted as fallback (graceful degradation — no text is lost)
- **Session cancellation**: Both Deepgram and Anthropic in-flight HTTP requests are cancelled;
  any stale callbacks are discarded via `mVoiceSessionId` check

## Thread Safety

All callbacks are posted to the main thread via `Handler(Looper.getMainLooper())`:
- Audio recording runs on a dedicated background thread
- Deepgram HTTP callbacks → main thread
- Anthropic HTTP callbacks → main thread
- Timer callbacks → main thread

This ensures all text modifications happen sequentially on the UI thread.
