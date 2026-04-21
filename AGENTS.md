# HeliBoard

HeliBoard is an Android app, open-source project based on AOSP / OpenBoard keyboard.

## This project rewrites HeliBoard with custom experimental features

1. Voice to text (using Speechmatics realtime transcription + direct finalized text insertion)
2. Smart auto-capitalization
3. Full-app keyboard mode (standalone full-screen editing)
4. UI features

## Skills (detailed guides)

Each feature area has a dedicated skill with architecture docs, key files, and lessons learned:


| Skill                                                                      | Description                                                                        |
| -------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| [voice-transcription](.cursor/skills/voice-transcription/SKILL.md)         | Speechmatics streaming pipeline, finalized transcript flow, chunked audio delivery |
| [full-app-mode](.cursor/skills/full-app-mode/SKILL.md)                     | Activity-based fullapp editor, draft sync, extract-view pitfalls                   |
| [build-apk](.cursor/skills/build-apk/SKILL.md)                             | Building distributable APKs, shared debug keystore, GitHub download URLs           |
| [android-workspace-setup](.cursor/skills/android-workspace-setup/SKILL.md) | Android SDK setup for cloud agents and CI                                          |
| [development](.cursor/skills/development/SKILL.md)                         | Local dev environment, build variants, ADB install, debugging                      |
| [key-hint-sizing](.cursor/skills/key-hint-sizing/SKILL.md)                 | Key hint secondary character sizing, Holo vs LXX, resource qualifiers              |


## Quick orientation

- **Voice transcription key files**: `VoiceInputManager.kt`, `SpeechmaticsTranscriptionClient.kt`, `TranscriptPostProcessor.kt`, `TranscriptionPreferences.kt`, `VoiceRecorder.kt`, `LatinIME.java`
- **Fullapp key files**: `FullappEditorActivity.kt`, `FullappEditorResult`, `LatinIME.java`
- **Main input pipeline**: `LatinIME.java` → `InputLogic.java` → `WordComposer.java` / `RichInputConnection.java`
- **Current-word host sync**: `EditorWordMirror.java` mirrors keyboard-owned word edits into the host field
- **Build**: `./tools/build-dist-apk.sh` (distributable APK), `./tools/setup-android-sdk.sh` (SDK setup)
- **Keystore**: `keystore/debug.keystore` — shared across local and cloud builds for same-signature APKs

## Speechmatics transcription features

All Speechmatics session config is built in `SpeechmaticsTranscriptionClient.kt` (companion object). Key customization points:


| Feature                     | Where to edit                                                       | Function/location                                                                                |
| --------------------------- | ------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| **Custom dictionary**       | `SpeechmaticsTranscriptionClient.defaultAdditionalVocab()`          | Returns `List<VocabEntry>` — add words, brand names, technical terms with optional `sounds_like` |
| **Word replacement**        | `SpeechmaticsTranscriptionClient.defaultReplacements()`             | Returns `List<ReplacementRule>` — plain text `from`/`to` pairs                                   |
| **Regex replacement**       | Same `defaultReplacements()`                                        | Use `/pattern/` delimiters in `from` field (ECMAScript regex)                                    |
| **Speaker diarization**     | `buildStartRecognitionMessage()` + `buildTranscriptSegment()`       | Diarization JSON config; speaker filtering via `primarySpeaker` param                            |
| **Punctuation sensitivity** | `Defaults.kt` → `PREF_SPEECHMATICS_PUNCTUATION_SENSITIVITY_PERCENT` | Default `55` (0–100, maps to 0.0–1.0); higher = more commas at short pauses                      |
| **End-of-utterance trigger**| `Defaults.kt` → `PREF_SPEECHMATICS_END_OF_UTTERANCE_MILLIS`         | Default `0` (disabled). Non-zero forces a sentence-end period after that much silence            |
| **Disfluency removal**      | `Defaults.kt` → `PREF_SPEECHMATICS_REMOVE_DISFLUENCIES`             | Boolean, default `true` (English only)                                                           |
| **Output locale**           | `SpeechmaticsTranscriptionClient.normalizeOutputLocale()`           | Defaults to `en-US` for English; maps GB/AU when detected                                        |
| **Operating point**         | `buildSessionConfig()` param `operatingPoint`                       | Default `"enhanced"` for best accuracy                                                           |
| **Diarization toggle**      | `Defaults.kt` → `PREF_SPEECHMATICS_DIARIZATION`                     | Boolean, default `true`                                                                          |


Settings pref keys: `Settings.java`. Defaults: `Defaults.kt`. Read/write: `TranscriptionPreferences.kt`. UI: `TranscriptionScreen.kt`.

## File structure overview

- `app/src/main/java/helium314/keyboard/latin/`
  - `LatinIME.java`: main IME service and top-level orchestration
  - `WordComposer.java`: keyboard-owned current-word state
  - `RichInputConnection.java`: cached editor connection wrapper
  - `inputlogic/`: text-entry rules, suggestion flow, and host mirroring
  - `voice/`: voice recording, streaming, and transcript post-processing
  - `suggestions/`, `dictionary/`, `spellcheck/`: suggestion UI and dictionary/spellcheck support
- `app/src/main/java/helium314/keyboard/keyboard/`
  - key handling, layouts, views, and keyboard state
- `app/src/main/res/`
  - Android resources, subtype definitions, UI strings, and themes
- `app/src/main/assets/`
  - keyboard layouts and locale key text assets
- `app/src/test/`
  - unit and Robolectric tests, especially `InputLogicTest.kt`
- `docs/`
  - project-specific architecture notes and implementation guides
- `tools/`
  - SDK setup, APK build, and utility scripts

## Key rules

- **Fullapp / extract view**: All text input (typing, voice) must go through `InputConnection` to the app — never write to the extract view directly. The framework syncs app → extract view via `setExtractedText()`.
- **Cloud builds**: Gradle works out of the box in Cursor cloud agents (`.cursor/environment.json` runs `./tools/setup-android-sdk.sh` at startup).

