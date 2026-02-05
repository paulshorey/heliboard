# Voice Transcription Data Flow

This document describes the architecture and data flow for real-time voice transcription with intelligent text cleanup.

## Overview

The voice input system uses a streaming architecture with two API integrations:
1. **OpenAI Realtime API** - Real-time speech-to-text transcription
2. **Anthropic Claude API** - Intelligent text cleanup (capitalization, punctuation, grammar)

## Architecture

```
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│   Microphone    │────▶│   VoiceRecorder      │────▶│  RealtimeClient │
│   (Hardware)    │     │   (PCM16 24kHz)      │     │  (WebSocket)    │
└─────────────────┘     └──────────────────────┘     └────────┬────────┘
                                                              │
                                                              ▼
┌─────────────────┐     ┌──────────────────────┐     ┌─────────────────┐
│   Text Field    │◀────│   LatinIME           │◀────│  OpenAI API     │
│   (App)         │     │   (Orchestrator)     │     │  (Transcription)│
└─────────────────┘     └──────────┬───────────┘     └─────────────────┘
                                   │
                                   ▼ (after 3s silence)
                        ┌──────────────────────┐     ┌─────────────────┐
                        │  TextCleanupClient   │────▶│  Anthropic API  │
                        │  (HTTP)              │◀────│  (Claude)       │
                        └──────────────────────┘     └─────────────────┘
```

## Components

### VoiceRecorder.kt
Captures audio from the microphone and streams it to the transcription client.
- **Format**: PCM16, 24kHz, mono
- **Streaming**: Continuous audio chunks sent via WebSocket

### RealtimeTranscriptionClient.kt
WebSocket client for OpenAI's Realtime API.
- **Model**: `gpt-4o-transcribe`
- **Endpoint**: `wss://api.openai.com/v1/realtime?intent=transcription`
- **Features**:
  - Server-side Voice Activity Detection (VAD)
  - Incremental transcription results
  - Speech start/stop events

### VoiceInputManager.kt
Orchestrates the voice input flow and manages timers.
- **Silence Timeout** (30s): Auto-cancel recording after prolonged silence
- **Cleanup Timer** (3s): Trigger text cleanup after silence
- **New Paragraph Timer** (12s): Insert paragraph break after long silence

### TextCleanupClient.kt
HTTP client for Anthropic's Claude API.
- **Model**: `claude-haiku-4-5-20251001`
- **Purpose**: Intelligent capitalization, punctuation, and grammar cleanup

### LatinIME.java
Main orchestrator that coordinates all components and manages text insertion.

## Data Flow

### 1. Recording Start
```
User taps mic button
    → LatinIME.startVoiceInput()
    → VoiceInputManager.startRecording()
    → RealtimeTranscriptionClient.connect()
    → VoiceRecorder.startRecording()
```

### 2. Audio Streaming
```
Microphone captures audio
    → VoiceRecorder sends PCM chunks
    → RealtimeTranscriptionClient encodes to Base64
    → WebSocket sends to OpenAI
```

### 3. Transcription Results
```
OpenAI processes audio
    → WebSocket receives transcription
    → RealtimeTranscriptionClient.onTranscriptionComplete()
    → VoiceInputManager.listener.onTranscriptionResult()
    → LatinIME.insertTranscriptionText()
    → Text appears in text field
```

### 4. Cleanup Processing (after 3s silence)
```
Speech stops (VAD event)
    → VoiceInputManager starts cleanup timer
    → 3 seconds pass with no speech
    → LatinIME.onCleanupRequested()
    → Extract current paragraph
    → TextCleanupClient.cleanupText()
    → Claude processes text
    → LatinIME.onCleanupComplete()
    → Find and replace original paragraph with cleaned text
```

### 5. New Paragraph (after 12s silence)
```
Speech stops (VAD event)
    → VoiceInputManager starts new paragraph timer
    → 12 seconds pass with no speech
    → LatinIME.onNewParagraphRequested()
    → Insert "\n\n" to start new paragraph
```

## State Management

### Voice Input State Variables
```java
mCleanupInProgress      // true while cleanup API call is in flight
mPendingNewParagraph    // true if paragraph break is waiting for cleanup
mPendingTranscription   // StringBuilder for queued transcription during cleanup
```

### Race Condition Prevention

To prevent race conditions between transcription and cleanup:

1. **Queueing**: When cleanup is in progress, new transcriptions are queued
2. **Batch Edits**: All text modifications use `beginBatchEdit()`/`endBatchEdit()`
3. **Find-and-Replace**: Cleanup uses `lastIndexOf()` to find original text, preserving any text added during cleanup

```
Transcription arrives during cleanup
    → Queue in mPendingTranscription
    → Cleanup completes
    → processPendingVoiceInput()
    → Insert queued transcription
    → Insert pending new paragraph (if any)
```

## Configuration

### Settings (TranscriptionScreen.kt)
- **OpenAI API Key**: Required for transcription
- **Anthropic API Key**: Required for cleanup (optional feature)
- **Cleanup Prompt**: Customizable instructions for Claude

### Timeouts (VoiceInputManager.kt)
```kotlin
SILENCE_TIMEOUT_MS = 30000L    // Auto-cancel after 30s silence
CLEANUP_DELAY_MS = 3000L       // Cleanup after 3s silence  
NEW_PARAGRAPH_DELAY_MS = 12000L // New paragraph after 12s silence
```

## Error Handling

- **Network Errors**: Graceful degradation - original text preserved
- **API Errors**: Logged, user notified for critical errors
- **Empty Responses**: Cleanup skipped, original text preserved
- **Buffer Overflow**: If pending transcription > 5KB, insert immediately

## Thread Safety

All callbacks are posted to the main thread via `Handler(Looper.getMainLooper())`:
- WebSocket messages → main thread
- HTTP responses → main thread
- Timer callbacks → main thread

This ensures all text modifications happen sequentially on the UI thread.
