# Speechmatics Transcription Architecture

HeliBoard uses Speechmatics realtime transcription for voice input.

## Runtime flow

1. `VoiceRecorder` captures 16 kHz mono PCM16 audio immediately when the mic button is tapped.
2. `VoiceInputManager` starts `SpeechmaticsTranscriptionClient` in parallel and buffers audio until the provider acknowledges `RecognitionStarted`.
3. `SpeechmaticsTranscriptionClient` sends:
   - `StartRecognition` JSON
   - binary PCM audio chunks
   - `ForceEndOfUtterance` and `EndOfStream(last_seq_no=...)` on graceful stop after all audio acks arrive
4. Finalized transcript text arrives as `AddTranscript` messages.
5. The client rebuilds finalized text from Speechmatics token results so spacing and punctuation attachments are preserved across chunk boundaries.
6. `VoiceInputManager` queues transcript spans in FIFO order and tracks whether the next segment should attach to previous text.
7. `LatinIME` calls `finishInput()` and then `commitText(...)` to insert the finalized text at the caret, restoring a leading space only when the provider segment is a continuation rather than punctuation.

## Important files

- `app/src/main/java/helium314/keyboard/latin/voice/SpeechmaticsTranscriptionClient.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceRecorder.kt`
- `app/src/main/java/helium314/keyboard/latin/settings/TranscriptionPreferences.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/TranscriptionScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/SetupAppScreen.kt`
- `app/src/main/java/helium314/keyboard/latin/LatinIME.java`

## Configuration

- Provider key preference: `Settings.PREF_SPEECHMATICS_API_KEY`
- Speechmatics session settings:
  - `Settings.PREF_SPEECHMATICS_MAX_DELAY_MILLIS`
  - `Settings.PREF_SPEECHMATICS_END_OF_UTTERANCE_MILLIS`
  - `Settings.PREF_SPEECHMATICS_REMOVE_DISFLUENCIES`
  - `Settings.PREF_SPEECHMATICS_PUNCTUATION_SENSITIVITY`
- Local silence settings remain unchanged:
  - chunk silence duration
  - silence threshold
  - new paragraph delay
  - auto-stop delay

## Notes

- The app no longer uses the previous provider websocket client or its API key preference.
- A stale legacy provider key is cleared from shared preferences so setup reflects the new backend accurately.
- Speechmatics formatting is optimized by sending a base language (`language`) plus a locale-specific `output_locale` when the active subtype provides one.
- English sessions can remove disfluencies server-side so dictation inserts cleaner finalized text without extra client-side cleanup.
- Final transcript text is reconstructed from `results[].alternatives[].content` plus `attaches_to`, rather than trusting each finalized segment string to be self-contained for spacing.
- Punctuation balance for dictation: server end-of-utterance detection is disabled by default (`PREF_SPEECHMATICS_END_OF_UTTERANCE_MILLIS = 0`) and punctuation sensitivity defaults to `0.55` (slightly above Speechmatics' native default of 0.5). Enabling server end-of-utterance forces a sentence-end mark (period in English) at every pause past the threshold regardless of sensitivity, which produces runaway periods and suppresses commas. Leaving it at `0` lets Speechmatics insert commas at short pauses and periods at natural sentence boundaries based on prosody, while HeliBoard's local paragraph-silence timer still handles paragraph breaks from the mic stream.
