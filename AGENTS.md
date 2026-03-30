# HeliBoard

HeliBoard is an Android app, open-source project based on AOSP / OpenBoard keyboard.

## This project rewrites HeliBoard with custom experimental features

1. Voice to text (using Deepgram Nova-3 streaming transcription + local post-processing)
2. Smart auto-capitalization
3. Full-app keyboard mode (standalone full-screen editing)
4. UI features

## Skills (detailed guides)

Each feature area has a dedicated skill with architecture docs, key files, and lessons learned:

| Skill | Description |
|-------|-------------|
| [voice-transcription](.cursor/skills/voice-transcription/SKILL.md) | Deepgram streaming pipeline, post-transcription filter, chunked audio flow |
| [full-app-mode](.cursor/skills/full-app-mode/SKILL.md) | Activity-based fullapp editor, draft sync, extract-view pitfalls |
| [build-apk](.cursor/skills/build-apk/SKILL.md) | Building distributable APKs, shared debug keystore, GitHub download URLs |
| [android-workspace-setup](.cursor/skills/android-workspace-setup/SKILL.md) | Android SDK setup for cloud agents and CI |
| [development](.cursor/skills/development/SKILL.md) | Local dev environment, build variants, ADB install, debugging |
| [key-hint-sizing](.cursor/skills/key-hint-sizing/SKILL.md) | Key hint secondary character sizing, Holo vs LXX, resource qualifiers |

## Quick orientation

- **Voice transcription key files**: `VoiceInputManager.kt`, `DeepgramTranscriptionClient.kt`, `VoicePostTranscriptionFilter.java`, `VoiceRecorder.kt`, `LatinIME.java`
- **Fullapp key files**: `FullappEditorActivity.kt`, `FullappEditorResult`, `LatinIME.java`
- **Main input pipeline**: `LatinIME.java` → `InputLogic.java` → `WordComposer.java` / `RichInputConnection.java`
- **Current-word host sync**: `EditorWordMirror.java` mirrors keyboard-owned word edits into the host field
- **Build**: `./tools/build-dist-apk.sh` (distributable APK), `./tools/setup-android-sdk.sh` (SDK setup)
- **Keystore**: `keystore/debug.keystore` — shared across local and cloud builds for same-signature APKs

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
