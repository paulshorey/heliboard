# latin/voice

Speechmatics realtime transcription pipeline.

## Direct files
- `SpeechmaticsTranscriptionClient.kt` - WebSocket client, session config, transcript reconstruction.
- `TranscriptPostProcessor.kt` - local cleanup/formatting for finalized transcript text.
- `VoiceInputManager.kt` - record/stream/orchestrate voice sessions and deliver finalized text.
- `VoiceRecorder.kt` - microphone capture of PCM audio.

## Non-obvious notes
- Final insertion still happens through `LatinIME` and `InputConnection`, not by writing directly into UI widgets.
- Preference keys/defaults for this feature live in `latin/settings/`, while the settings screen lives in `settings/screens/TranscriptionScreen.kt`.
- Transcript text is rebuilt from token content and attachment metadata, so spacing bugs are often protocol/assembly bugs rather than simple string trimming issues.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
