# HeliBoard

HeliBoard is an Android keyboard app derived from AOSP/OpenBoard. This fork adds a Speechmatics-backed voice pipeline, smart auto-capitalization work, and a standalone full-app editing mode.

## Repo map
- `app/` - Android application module.
- `app/src/main/java/helium314/keyboard/latin/` - IME runtime, text pipeline, dictionaries, voice, spell checker.
- `app/src/main/java/helium314/keyboard/keyboard/` - keyboard view layer, key geometry, pointer handling, popup keys, emoji/clipboard surfaces.
- `app/src/main/java/helium314/keyboard/settings/` - standalone settings/fullapp UI in Compose.
- `app/src/main/res/` and `app/src/main/assets/` - Android resources, layout definitions, keyboard data, popup text, emoji, bundled dictionaries.
- `app/src/main/jni/` - native dictionary/suggestion/proximity code used through JNI.
- `app/src/test/` - JVM and Robolectric tests.
- `docs/` - deeper design notes.
- `tools/` - SDK setup, APK build, release and asset-generation scripts.

## High-value entry points
- `LatinIME.java` - main `InputMethodService` and top-level orchestration.
- `InputLogic.java` + `WordComposer.java` + `RichInputConnection.java` - core text entry pipeline.
- `EditorWordMirror.java` - mirrors the keyboard-owned current word into the host app.
- `VoiceInputManager.kt` + `SpeechmaticsTranscriptionClient.kt` - voice recording and realtime transcription.
- `FullappEditorActivity.kt` - standalone full-screen editor mode.
- `Settings.java` + `Defaults.kt` + `TranscriptionPreferences.kt` - preference keys, defaults, and typed access.

## Cross-folder rules worth remembering
- There are two settings trees: `latin/settings` holds preference keys and runtime snapshots; `settings/` holds the Compose UI.
- Ordinary typing should flow through `InputConnection`; do not write directly into the extract/fullapp text widgets.
- `res/xml/method.xml` and `assets/layouts/` must stay aligned when adding or renaming layouts/subtypes.
- Native dictionary behavior is split between `latin/dictionary`, `latin/utils/JniUtils.java`, and `app/src/main/jni/`.
- Build the canonical installable artifact with `./tools/build-dist-apk.sh`, which writes `dist/HeliBoard.apk`.

## Suggested reading order
1. Root `AGENTS.md`
2. The `AGENTS.md` in the folder you plan to edit
3. Relevant long-form docs under `docs/` or `.cursor/skills/`

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
