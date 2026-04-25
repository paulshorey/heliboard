# latin/voice

Soniox realtime transcription pipeline.

## Direct files
- `SonioxTranscriptionClient.kt` - WebSocket client, session config, transcript reconstruction from Soniox `tokens`.
- `TranscriptSegment.kt` - finalized transcript chunk shared between the client and the IME pipeline.
- `TranscriptPostProcessor.kt` - local cleanup/formatting for finalized transcript text.
- `VoiceInputManager.kt` - record/stream/orchestrate voice sessions and deliver finalized text.
- `VoiceRecorder.kt` - microphone capture of PCM audio.

## Non-obvious notes
- Final insertion still happens through `LatinIME` and `InputConnection`, not by writing directly into UI widgets.
- Preference keys/defaults for this feature live in `latin/settings/`, while the settings screen lives in `settings/screens/TranscriptionScreen.kt`.
- Soniox token text already encodes inter-word whitespace, so transcript assembly concatenates final tokens directly and trims the result rather than re-inserting spaces.
- Graceful end-of-stream is an empty WebSocket frame; the client then waits for `{"finished": true}` before closing.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
