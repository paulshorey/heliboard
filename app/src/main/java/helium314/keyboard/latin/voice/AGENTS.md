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
- Preference keys/defaults for this feature live in `latin/settings/`, while the settings screens live in `settings/screens/TranscriptionScreen.kt` and `settings/screens/SonioxContextTermsScreen.kt`.
- Soniox encodes inter-word whitespace as separate space tokens, so transcript assembly concatenates final tokens directly and trims the result rather than re-inserting spaces.
- When Soniox finalizes inside a word (endpoint detection or internal segmentation can split a word like `"heading"` into `"head"` + `"ing"`), the second response's first content token has no preceding space token. `SonioxTranscriptionClient` tracks whether the previous chunk's final tail was a "wordy" character and, if the next chunk's raw text does not start with whitespace, marks the segment `attachesToPrevious` so the IME does not auto-insert a separator space (which previously produced `"head ing"`).
- Soniox emits `<end>` (after every endpoint detection) and `<fin>` (after every manual finalize) as final tokens that look like real text. Raw WebSocket consumers must filter them; HeliBoard does it via `STREAM_MARKERS` in `SonioxTranscriptionClient`.
- `context.general` provides structured key-value hints (default: setting + instructions for voice dictation and comma reduction). Defined in `SonioxTranscriptionClient.defaultContextGeneral()`. `context.text` is supplied per session by `LatinIME.buildVoiceContextText` through `VoiceInputManager.setPriorTextProvider` (up to 4 000 chars before the cursor). `context.terms` is the union of a small built-in list and the user's `PREF_SONIOX_CUSTOM_TERMS`.
- Graceful end-of-stream is an empty WebSocket frame; the client then waits for `{"finished": true}` before closing.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
