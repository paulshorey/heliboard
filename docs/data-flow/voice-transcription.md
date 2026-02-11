# Voice Transcription Data Flow

This document describes the architecture and data flow for voice transcription with intelligent text cleanup.

## Overview

The voice input system uses a **local recording + batch transcription** architecture:
1. **VoiceRecorder** — captures audio locally, detects silence, emits WAV segments
2. **Deepgram API** — transcribes each audio segment into text
3. **Anthropic Claude API** — cleans up the transcribed paragraph (capitalization, punctuation, grammar)

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
- **Silence detection**: RMS energy threshold on each 100ms chunk
- **Segmentation**: After 3s of silence, emits accumulated audio as a WAV file
- **Output**: Complete WAV files (44-byte header + PCM data)

### DeepgramTranscriptionClient.kt
HTTP client for Deepgram's pre-recorded transcription API.
- **Endpoint**: `POST https://api.deepgram.com/v1/listen`
- **Model**: `nova-3`
- **Content-Type**: `audio/wav` (raw bytes in request body)
- **Features**: `smart_format=true`, `punctuate=true`

### VoiceInputManager.kt
Orchestrates the voice input flow and manages timers.
- **State machine**: IDLE → RECORDING ↔ PAUSED → IDLE
- **Chunk Watchdog** (20s): Forces a segment flush if silence detection misses a boundary
- **Cleanup Timer** (3s): Trigger text cleanup after transcription
- **New Paragraph Timer** (12s): Insert paragraph break after long silence

### TextCleanupClient.kt
HTTP client for Anthropic's Claude API.
- **Model**: `claude-haiku-4-5-20251001`
- **Purpose**: Intelligent capitalization, punctuation, and grammar cleanup

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
    → User pauses (3 seconds of silence detected)
    → VoiceRecorder wraps PCM data in WAV header
    → onSegmentReady(wavData) callback
```

### 3. Transcription
```
VoiceInputManager.processSegment(wavData)
    → DeepgramTranscriptionClient.transcribe(wavData)
    → POST /v1/listen with audio/wav body
    → Deepgram returns JSON with transcript
    → onTranscriptionComplete(text)
    → VoiceInputManager.listener.onTranscriptionResult(text)
    → LatinIME.insertTranscriptionText(text)
    → Text appears in text field
```

### 4. Cleanup Processing (after 3s silence following transcription)
```
Transcription inserted
    → VoiceInputManager starts cleanup timer
    → 3 seconds pass with no new speech
    → LatinIME.onCleanupRequested()
    → Extract current paragraph (text since last newline)
    → TextCleanupClient.cleanupText(paragraph)
    → Claude processes text
    → LatinIME.onCleanupComplete(cleanedText)
    → Find and replace original paragraph with cleaned text
```

### 5. New Paragraph (after 12s silence)
```
Speech stops
    → VoiceInputManager starts new paragraph timer
    → 12 seconds pass with no speech
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
RECORDING  → 60s silence      → IDLE (auto-cancel)
```

### Race Condition Prevention
```java
mCleanupInProgress      // true while cleanup API call is in flight
mPendingNewParagraph    // true if paragraph break waiting for cleanup
mPendingTranscription   // StringBuilder for queued transcription during cleanup
```

When cleanup is in progress, new transcriptions are queued and applied after cleanup completes.
At the manager layer, audio chunks are also transcribed in FIFO order (one request at a time)
to keep insertion order deterministic.

## Configuration

### Settings (TranscriptionScreen.kt)
- **Deepgram API Key**: Required for transcription
- **Anthropic API Key**: Required for cleanup (optional feature)
- **Cleanup Prompt**: Customizable instructions for Claude

### Timeouts (VoiceInputManager.kt)
```kotlin
CHUNK_WATCHDOG_MS = 20000L     // Force segment flush if split stalls
CLEANUP_DELAY_MS = 3000L       // Cleanup after 3s post-transcription
NEW_PARAGRAPH_DELAY_MS = 12000L // New paragraph after 12s silence
```

### Silence Detection (VoiceRecorder.kt)
```kotlin
SILENCE_THRESHOLD = 400.0      // RMS energy threshold
SILENCE_DURATION_MS = 3000L    // 3s silence to split segment
MIN_SEGMENT_MS = 500L          // Minimum segment length
MAX_SEGMENT_MS = 60000L        // Force-split at 60s
```

## Error Handling

- **Network errors**: Audio is captured locally; if transcription fails, the segment is lost but recording continues
- **API errors**: Logged, user notified for critical errors (invalid key, etc.)
- **Empty transcriptions**: Silently ignored
- **Cleanup errors**: Original text preserved (graceful degradation)

## Thread Safety

All callbacks are posted to the main thread via `Handler(Looper.getMainLooper())`:
- Audio recording runs on a dedicated background thread
- Deepgram HTTP callbacks → main thread
- Anthropic HTTP callbacks → main thread
- Timer callbacks → main thread

This ensures all text modifications happen sequentially on the UI thread.
