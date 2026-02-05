# Data Flow Documentation

This directory contains documentation for the data flow in HeliBoard's voice transcription system.

## Documents

- **[voice-transcription.md](./voice-transcription.md)** - Complete architecture and data flow for real-time voice transcription with intelligent text cleanup

- **[api-reference.md](./api-reference.md)** - Quick reference for OpenAI Realtime API and Anthropic Claude API integrations

## Quick Start

1. Configure API keys in Settings → Transcription
2. Tap the microphone button to start recording
3. Speak naturally - text appears in real-time
4. After 3 seconds of silence, text is cleaned up automatically
5. After 12 seconds of silence, a new paragraph is started

## Key Files

| File | Purpose |
|------|---------|
| `LatinIME.java` | Main orchestrator |
| `VoiceInputManager.kt` | Recording state and timers |
| `RealtimeTranscriptionClient.kt` | OpenAI WebSocket client |
| `TextCleanupClient.kt` | Anthropic HTTP client |
| `VoiceRecorder.kt` | Audio capture |
| `TranscriptionScreen.kt` | Settings UI |
