# HeliBoard

HeliBoard is an Android keyboard app derived from AOSP/OpenBoard. This fork adds a Gemini Live voice pipeline, smart auto-capitalization work, and a standalone full-app editing mode.

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
- `docs/` - deeper design notes such as `input-simplified.md` and `gemini-transcription.md`.
- `tools/` - SDK setup, portable cloud-agent startup, canonical APK build, release scripts, and the `tools:make-emoji-keys` helper module.
- `.codex/` - canonical agent skills and Codex Gemini Docs MCP (`config.toml`); `.agents/skills` links here for discovery and `.cursor` is a compatibility symlink.
- `skills-lock.json` - lockfile for the vendored `google-gemini/gemini-skills` packages; refresh them with `./tools/sync-gemini-skills.sh`.

## High-value entry points
- `app/src/main/AndroidManifest.xml` - declares the IME service, spell checker service, settings activities, receivers, and direct-boot behavior.
- `app/src/main/java/helium314/keyboard/latin/LatinIME.java` - main `InputMethodService` and top-level orchestration.
- `app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java` + `app/src/main/java/helium314/keyboard/latin/WordComposer.java` + `app/src/main/java/helium314/keyboard/latin/RichInputConnection.java` - core text entry pipeline.
- `app/src/main/java/helium314/keyboard/latin/inputlogic/EditorWordMirror.java` - mirrors the keyboard-owned current word into the host app.
- `app/src/main/java/helium314/keyboard/latin/Suggest.kt` - suggestion request pipeline entry point.
- `app/src/main/java/helium314/keyboard/latin/suggestions/SuggestionStripView.kt` + `app/src/main/java/helium314/keyboard/latin/utils/ToolbarUtils.kt` - suggestion strip, toolbar buttons, pinned keys, and voice/fullapp strip controls.
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt` + `app/src/main/java/helium314/keyboard/latin/voice/GeminiTranscriptionClient.kt` - voice recording and realtime transcription.
- `app/src/main/java/helium314/keyboard/settings/FullappEditorActivity.kt` - standalone full-screen editor mode.
- `app/src/main/java/helium314/keyboard/latin/settings/Settings.java` + `Settings.kt` + `Defaults.kt` + `TranscriptionPreferences.kt` - runtime preference keys, defaults, and typed access.
- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt` + `app/src/main/assets/layouts/` - keyboard layout parsing and source layout data.
- `.codex/config.toml` + `.codex/skills/gemini-api-dev/` + `.codex/skills/gemini-live-api-dev/` - Gemini Docs MCP and API skills for current models (including `gemini-3.5-transcribe` / `gemini-3.5-transcribe-live`).

## Cross-folder rules worth remembering
- There are two settings trees: `latin/settings` holds runtime preference keys, defaults, and snapshots; `settings/` holds the Compose UI and fullapp screens.
- Ordinary typing, voice insertion, and fullapp sync should flow through `InputConnection`; do not write directly into the extract/fullapp text widgets.
- `WordComposer` is the source of truth for the current word; `inputlogic/EditorWordMirror.java` is the bridge that mirrors it into the host editor.
- Gemini dictation uses one connection per recording, bounded audio/control and transcript FIFOs that advance after local insertion checks, terminal failure with a literal interruption marker after readiness, and explicit restart. Intentional silence auto-stop drains and closes without a marker for unconfirmed noise/hypotheses; actual failures still report interruptions. Startup rejection leaves the editor untouched and reports the service reason. Interims never become editor text; no reconnect or automatic rotation. See `docs/gemini-transcription.md`.
- The opt-in **Finish phrases manually (test)** mode disables server VAD and local silence finalization/auto-stop. Explicit Pause/Stop delimit speech with activity signals; open activity may wait for text until the boundary. Keep queue, network/editor, session-limit, bounded boundary/Stop and cancellation guards. See the manual-phrase comparison in the voice guide.
- Missing-final waits begin at a submitted speech boundary, allowing long utterances with live interims. Interactive keyboard hiding, editor disconnection, and verified host clears cancel all voice work immediately; explicit mic stop still drains. Host clears use extracted-text monitoring plus actual host reads, including unchanged selection, and do not alone prove form submission.
- Voice text intentionally bypasses `EditorWordMirror`: `LatinIME` first clears the typed-word state with `finishInput()`, then commits each finalized Gemini transcript segment directly. Fullapp replay is another bypass, using raw `InputConnection` replacement when the IME reconnects to the original field.
- Transcription's Microphone setting persists `automatic` (prefer Bluetooth) or `built_in`. On API 23+ `VoiceMicrophoneRouter` verifies the actual capture route before forwarding PCM; it releases owned Bluetooth routing on pause/stop/failure. API 21–22 retain Android routing and cannot select an input.
- The manifest points the IME service at `res/xml/method_dummy.xml`, while real subtype/layout metadata lives in `res/xml/method.xml`; changes to subtypes or layout names usually also touch `assets/layouts/`.
- Keyboard layout work often spans `keyboard/internal/keyboard_parser/`, `assets/layouts/`, and XML keyboard templates under `res/xml/`.
- Native dictionary behavior is split between `latin/dictionary`, `latin/utils/JniUtils.java`, and `app/src/main/jni/`.
- The spell checker (`latin/spellcheck/`) is a separate Android entry point from `LatinIME`, so config or resource changes can affect one without the other.
- The closest folder-local `AGENTS.md` is usually more detailed than this root file; follow it once you know which subtree you are in.
- Build the canonical installable artifact with `./tools/build-dist-apk.sh`, which writes `dist/HeliBoard.apk`.
- For a fresh Linux agent machine, run `./tools/agent-environment-startup.sh` (then `source ./.android-env`). Clipboard history is on-device SQLite (`heliboard.db`); there is no server database or long-running app process to start.
- Gemini API work (models, SDKs, Live API, `gemini-3.5-transcribe-live`) must use the skills under `.codex/skills/gemini-*` and the Docs MCP in `.codex/config.toml`. Do not invent endpoints from training data. If MCP search is unavailable, fetch `https://ai.google.dev/gemini-api/docs/llms.txt`.
- The `.codex/skills/gemini-*` files are vendored upstream copies. Refresh them only with `./tools/sync-gemini-skills.sh`, and put any local correction in `tools/gemini-skills-fixups.py` so a refresh cannot silently revert it. Use the isolated refresh script; direct installer runs can overwrite discovery links. See `.codex/AGENTS.md`.

## Required PR handoff

- Every new or updated PR, including infrastructure/documentation work, must build
  `./tools/build-dist-apk.sh` after the final file edits on its feature branch.
  Desktop agents have the same requirement as cloud agents. Read
  `.codex/skills/android-build-apk/SKILL.md` when preparing the handoff.
- Commit and push `dist/HeliBoard.apk` and `dist/apk-metadata.json` with the task.
  Run `python3 tools/apk-handoff.py link` after pushing and include its immutable
  download link in both the PR description and final response. Rebuild after
  later file edits; the lightweight PR workflow checks freshness and bytes.
- Run relevant local checks through `./tools/android-dev.sh`; report what passed
  and what remains for manual phone testing. A build or JVM test does not prove
  on-device typing, host-editor or microphone behavior. See
  `docs/agent-development.md` for the testing matrix and tooling roadmap.
- If a build is blocked by external prerequisites, preserve work in a draft PR
  with the actual blocker and an explicitly unavailable APK. Never link an old
  binary as though it contains the current changes.

## Suggested reading order
1. Root `AGENTS.md`
2. `app/src/main/AGENTS.md` or `app/src/main/java/helium314/keyboard/AGENTS.md`, depending on whether you are changing Android app wiring or Java/Kotlin package code
3. The `AGENTS.md` in the exact folder you plan to edit
4. Relevant long-form docs under `docs/` or task-specific guides under `.codex/skills/`
5. `.codex/AGENTS.md` when the task involves Gemini APIs, MCP, or Codex agent setup

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
