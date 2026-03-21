# Data Flow Documentation

This directory contains documentation for HeliBoard's current voice transcription flow.

## Documents

- **[voice-transcription.md](./voice-transcription.md)** - End-to-end architecture and runtime flow for voice transcription
- **[api-reference.md](./api-reference.md)** - Quick reference for the external transcription API and related settings

## Quick Start

1. Configure your Deepgram API key in Settings -> Transcription
2. Tap the microphone button to start recording (instant - no connection delay)
3. Speak naturally - after each pause, Deepgram returns finalized transcript spans
4. Each finalized span is run through the local post-transcription filter and inserted at the current caret position
5. After the configured silence window, a new paragraph is started

## Key Files

| File | Purpose |
|------|---------|
| `LatinIME.java` | Main orchestrator and caret insertion logic |
| `VoiceInputManager.kt` | Recording state, Deepgram stream coordination, paragraph/idle timing |
| `VoiceRecorder.kt` | Audio capture and silence detection |
| `DeepgramTranscriptionClient.kt` | Deepgram live transcription client |
| `VoicePostTranscriptionFilter.java` | Local post-transcription hook before insertion |
| `TranscriptionScreen.kt` | Settings UI |
