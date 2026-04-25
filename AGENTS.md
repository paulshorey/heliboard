# HeliBoard

HeliBoard is an Android keyboard app derived from AOSP/OpenBoard. This fork adds an AssemblyAI Universal-Streaming voice pipeline, smart auto-capitalization work, and a standalone full-app editing mode.

## Repo map
- `app/` - Android application module and the main Gradle project to build for product work.
- `app/src/main/` - shipping Android app: manifest, Java/Kotlin sources, resources, assets, and JNI.
- `app/src/main/java/helium314/keyboard/` - package root that splits the product into `latin/`, `keyboard/`, `settings/`, `event/`, `compat/`, `accessibility/`, and `dictionarypack/`.
- `app/src/main/java/helium314/keyboard/latin/` - IME runtime, text pipeline, dictionaries, clipboard history, voice, and spell checker.
- `app/src/main/java/helium314/keyboard/keyboard/` - keyboard model/view layer, key geometry, pointer handling, popup keys, emoji, and clipboard surfaces.
- `app/src/main/java/helium314/keyboard/settings/` - standalone settings/fullapp UI built with Compose.
- `app/src/main/res/` and `app/src/main/assets/` - Android resources, IME metadata, layout definitions, popup text, emoji data, and bundled dictionaries.
- `app/src/main/jni/` - native dictionary/suggestion/proximity code used through JNI.
- `app/src/test/` - JVM and Robolectric tests.
- `docs/` - deeper design notes such as `input-simplified.md` and `assemblyai-transcription.md`.
- `tools/` - SDK setup, canonical APK build, release scripts, and the `tools:make-emoji-keys` helper module.

## High-value entry points
- `app/src/main/AndroidManifest.xml` - declares the IME service, spell checker service, settings activities, receivers, and direct-boot behavior.
- `app/src/main/java/helium314/keyboard/latin/LatinIME.java` - main `InputMethodService` and top-level orchestration.
- `app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java` + `app/src/main/java/helium314/keyboard/latin/WordComposer.java` + `app/src/main/java/helium314/keyboard/latin/RichInputConnection.java` - core text entry pipeline.
- `app/src/main/java/helium314/keyboard/latin/inputlogic/EditorWordMirror.java` - mirrors the keyboard-owned current word into the host app.
- `app/src/main/java/helium314/keyboard/latin/Suggest.kt` - suggestion request pipeline entry point.
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt` + `app/src/main/java/helium314/keyboard/latin/voice/AssemblyAITranscriptionClient.kt` - voice recording and AssemblyAI Universal-Streaming realtime transcription.
- `app/src/main/java/helium314/keyboard/settings/FullappEditorActivity.kt` - standalone full-screen editor mode.
- `app/src/main/java/helium314/keyboard/latin/settings/Settings.java` + `Settings.kt` + `Defaults.kt` + `TranscriptionPreferences.kt` - runtime preference keys, defaults, and typed access.
- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt` + `app/src/main/assets/layouts/` - keyboard layout parsing and source layout data.

## Cross-folder rules worth remembering
- There are two settings trees: `latin/settings` holds runtime preference keys, defaults, and snapshots; `settings/` holds the Compose UI and fullapp screens.
- Ordinary typing, voice insertion, and fullapp sync should flow through `InputConnection`; do not write directly into the extract/fullapp text widgets.
- `WordComposer` is the source of truth for the current word; `inputlogic/EditorWordMirror.java` is the bridge that mirrors it into the host editor.
- The manifest points the IME service at `res/xml/method_dummy.xml`, while real subtype/layout metadata lives in `res/xml/method.xml`; changes to subtypes or layout names usually also touch `assets/layouts/`.
- Keyboard layout work often spans `keyboard/internal/keyboard_parser/`, `assets/layouts/`, and XML keyboard templates under `res/xml/`.
- Native dictionary behavior is split between `latin/dictionary`, `latin/utils/JniUtils.java`, and `app/src/main/jni/`.
- The spell checker (`latin/spellcheck/`) is a separate Android entry point from `LatinIME`, so config or resource changes can affect one without the other.
- The closest folder-local `AGENTS.md` is usually more detailed than this root file; follow it once you know which subtree you are in.
- Build the canonical installable artifact with `./tools/build-dist-apk.sh`, which writes `dist/HeliBoard.apk`.

## Suggested reading order
1. Root `AGENTS.md`
2. `app/src/main/AGENTS.md` or `app/src/main/java/helium314/keyboard/AGENTS.md`, depending on whether you are changing Android app wiring or Java/Kotlin package code
3. The `AGENTS.md` in the exact folder you plan to edit
4. Relevant long-form docs under `docs/` or task-specific guides under `.cursor/skills/`

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
