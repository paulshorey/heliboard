# latin/voice

Soniox realtime transcription pipeline.

## Direct files
- `SonioxTranscriptionClient.kt` - WebSocket client, session config, transcript reconstruction from Soniox `tokens`.
- `TranscriptSegment.kt` - finalized transcript chunk shared between the client and the IME pipeline.
- `TranscriptPostProcessor.kt` - local cleanup/formatting for finalized transcript text.
- `VoiceInputManager.kt` - record/stream/orchestrate voice sessions and deliver finalized text.
- `VoiceRecorder.kt` - microphone capture of PCM audio.

## Non-obvious notes
- Soniox recording starts from the fixed right-edge mic in `suggestions_strip.xml` (`R.id.voice_input_key`), not from `ToolbarKey.VOICE`; the toolbar VOICE key switches to Android's shortcut/voice IME.
- Final insertion happens in `LatinIME`, not in this folder: `prepareVoiceTranscriptionText()` handles leading spaces, mid-sentence casing, and trailing punctuation before `commitVoiceTranscriptionText()` clears typed-word state with `finishInput()` and commits directly through `InputConnection`.
- Paragraph-level cleanup runs after commit through `runTranscriptPostProcessing()` / `TranscriptPostProcessor.processCurrentParagraph()`. Current processing removes common comma-attached filler fragments such as "um," / "uh,", covers spoken punctuation and paragraph commands, and optionally strips all ASCII commas last when `PREF_SONIOX_REMOVE_COMMAS` is enabled (default on; toggle on `TranscriptionScreen`).
- Preference keys/defaults for this feature live in `latin/settings/`, while the settings screens live in `settings/screens/TranscriptionScreen.kt`, `settings/screens/SonioxContextTermsScreen.kt`, and `settings/screens/VoiceDiagnosticsScreen.kt`.
- Soniox encodes inter-word whitespace as separate space tokens, so transcript assembly concatenates final tokens directly and trims the result rather than re-inserting spaces.
- When Soniox finalizes inside a word (endpoint detection or internal segmentation can split a word like `"heading"` into `"head"` + `"ing"`), the second response's first content token has no preceding space token. `SonioxTranscriptionClient` tracks whether the previous chunk's final tail was a "wordy" character and, if the next chunk's raw text does not start with whitespace, marks the segment `attachesToPrevious` so the IME does not auto-insert a separator space (which previously produced `"head ing"`).
- Soniox emits `<end>` (after every endpoint detection) and `<fin>` (after every manual finalize) as final tokens that look like real text. Raw WebSocket consumers must filter them; HeliBoard does it via `STREAM_MARKERS` in `SonioxTranscriptionClient`.
- HeliBoard only commits `is_final` tokens. Tokens become final via server endpoint detection, manual finalize, or end-of-stream; local `VoiceRecorder` silence triggers manual finalize, while a separate longer silence timer auto-stops recording.
- `VoiceInputManager` preserves FIFO transcript delivery, coalesces the oldest transcript entries if its queue reaches 64, and drops the oldest buffered audio chunks if the stream is not ready after 300 chunks.
- `context.text` is supplied per session by `LatinIME.buildVoiceContextText` through `VoiceInputManager.setPriorTextProvider` (up to 4 000 chars before the cursor). `context.terms` is the union of a small built-in list and the user's `PREF_SONIOX_CUSTOM_TERMS`.
- Graceful end-of-stream is an empty WebSocket frame; the client then waits for `{"finished": true}` before closing.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
