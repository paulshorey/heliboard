---
name: voice-transcription
description: Maintain HeliBoard's MAI-Transcribe-2-Streaming recording, Azure Realtime client, completed transcript insertion, settings, and diagnostics.
---

# Voice transcription

Read `docs/mai-transcription.md` and the nearest folder AGENTS.md before changing this pipeline. Use [Microsoft's official Realtime documentation](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-realtime) as the API source of truth. See `api-reference.md` for the app's wire configuration and `data-flow.md` for ownership.

Preserve these contracts:

- Record mono signed little-endian PCM16 at 16 kHz; no WAV headers in append frames.
- Use the resource API key in a handshake header and configure the user-selected Azure deployment.
- Buffer until session.updated. Server VAD and noise reduction must be null.
- Request explicit nonempty commits at local silence, pause, and stop.
- Insert only completed segments, in commit order, and never flush provisional text on a timer.
- Keep pending-processing state active until all stop/rotation completions drain.
- Deduplicate by item ID, allowing repeated text across independent commits.
- Keep callbacks and state on the main looper with stale-session guards.
- Report incomplete uploaded speech on failure; bound audio and socket buffers.
- Insert through LatinIME's InputConnection path and preserve paragraph cleanup.

Validate with `MaiTranscriptionClientTest`, `MaiTranscriptionClientStreamTest`, `TranscriptionPreferencesTest`, `TranscriptPostProcessorTest`, and `LogVoiceDiagnosticsTest`. Use `tools/mai-streaming-smoke-test.py` with an Azure deployment to test service behavior; unit tests alone cannot establish cloud access or recognition quality.
