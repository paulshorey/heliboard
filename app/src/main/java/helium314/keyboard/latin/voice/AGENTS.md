# latin/voice

AssemblyAI Universal-Streaming realtime transcription pipeline.

## Direct files
- `AssemblyAITranscriptionClient.kt` - WebSocket client, session config (URL query parameters), Turn-message handling, and graceful `Terminate` shutdown.
- `TranscriptPostProcessor.kt` - local cleanup/formatting for finalized transcript text (spelled-out punctuation names, mid-sentence casing/punctuation correction).
- `VoiceInputManager.kt` - record/stream/orchestrate voice sessions and deliver finalized text to LatinIME.
- `VoiceRecorder.kt` - microphone capture of PCM16 audio.

## Non-obvious notes
- Final insertion still happens through `LatinIME` and `InputConnection`, not by writing directly into UI widgets.
- Preference keys/defaults for this feature live in `latin/settings/`, while the settings screen lives in `settings/screens/TranscriptionScreen.kt`.
- Universal-Streaming is configured entirely through query parameters on the WebSocket connect URL — there is no JSON `StartRecognition` step. See `AssemblyAITranscriptionClient.buildConnectionUrl`.
- Universal-Streaming transcripts are immutable. We only insert text on `end_of_turn: true`, and we wait for the `turn_is_formatted: true` follow-up when `format_turns=true` so the editor never receives the unformatted draft of a turn first.
- Setting `end_of_turn_confidence_threshold` to `0` reverts the model to silence-only endpointing — that is the legacy failure mode we just left, so do not surface it as a recommended option.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
