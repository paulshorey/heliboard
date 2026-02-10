# Data Flow Documentation

This directory contains documentation for the data flow in HeliBoard's voice transcription system.

## Documents

- **[voice-transcription.md](./voice-transcription.md)** - Complete architecture and data flow for voice transcription with intelligent text cleanup

- **[api-reference.md](./api-reference.md)** - Quick reference for Deepgram and Anthropic Claude API integrations

## Quick Start

1. Configure API keys in Settings → Transcription
2. Tap the microphone button to start recording (instant — no connection delay)
3. Speak naturally — after each pause, text is transcribed and inserted
4. After 3 seconds of silence, the paragraph is cleaned up automatically
5. After 12 seconds of silence, a new paragraph is started

## Key Files

| File | Purpose |
|------|---------|
| `LatinIME.java` | Main orchestrator |
| `VoiceInputManager.kt` | Recording state, timers, segment pipeline |
| `VoiceRecorder.kt` | Audio capture, silence detection, WAV segmentation |
| `DeepgramTranscriptionClient.kt` | Deepgram HTTP client (batch transcription) |
| `TextCleanupClient.kt` | Anthropic HTTP client (text cleanup) |
| `TranscriptionScreen.kt` | Settings UI |
