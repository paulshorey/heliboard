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
- **Build**: `./tools/build-dist-apk.sh` (distributable APK), `./tools/setup-android-sdk.sh` (SDK setup)
- **Keystore**: `keystore/debug.keystore` — shared across local and cloud builds for same-signature APKs

## Key rules

- **Fullapp / extract view**: All text input (typing, voice) must go through `InputConnection` to the app — never write to the extract view directly. The framework syncs app → extract view via `setExtractedText()`.
- **Cloud builds**: Gradle works out of the box in Cursor cloud agents (`.cursor/environment.json` runs `./tools/setup-android-sdk.sh` at startup).
