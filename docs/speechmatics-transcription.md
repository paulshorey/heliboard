# Speechmatics Transcription Architecture

HeliBoard uses Speechmatics realtime transcription for voice input.

## Runtime flow

1. `VoiceRecorder` captures 16 kHz mono PCM16 audio immediately when the mic button is tapped.
2. `VoiceInputManager` starts `SpeechmaticsTranscriptionClient` in parallel and buffers audio until the provider acknowledges `RecognitionStarted`.
3. `SpeechmaticsTranscriptionClient` sends:
   - `StartRecognition` JSON
   - binary PCM audio chunks
   - `EndOfStream(last_seq_no=...)` on graceful stop after all audio acks arrive
4. Finalized transcript text arrives as `AddTranscript` messages.
5. `VoiceInputManager` queues transcript spans in FIFO order.
6. `LatinIME` calls `finishInput()` and then `commitText(...)` to insert the finalized text at the caret.

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
- Local silence settings remain unchanged:
  - chunk silence duration
  - silence threshold
  - new paragraph delay
  - auto-stop delay

## Notes

- The app no longer uses the previous provider websocket client or its API key preference.
- A stale legacy provider key is cleared from shared preferences so setup reflects the new backend accurately.
