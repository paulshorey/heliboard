# Project: HeliBoard

HeliBoard is an Android keyboard app derived from AOSP/OpenBoard. This fork adds a Gemini Live voice pipeline, smart auto-capitalization work, and a standalone full-app editing mode.

## Layout and entry points

Java/Kotlin paths below are relative to `app/src/main/java/helium314/keyboard/`.

- `latin/`: IME, text pipeline, voice, and spell checker.
  Start with `LatinIME.java`, `inputlogic/InputLogic.java`, `WordComposer.java`,
  `RichInputConnection.java`, and `voice/VoiceInputManager.kt`.
- `keyboard/`: key geometry, pointers, layouts, emoji/clipboard UI.
- `settings/`: Compose settings/full-app editor; `latin/settings/`: runtime preferences.
- `app/src/main/res/`, `app/src/main/assets/`, and `app/src/main/jni/`: resources, layouts/dictionaries, and native code.
- `app/src/test/`: JVM/Robolectric tests. `tools/`: environment, build, and release tooling.
- `docs/`: design guides. `.codex/`: canonical skills/MCP; `.agents/skills` links there.
  Read nested guides for the exact subtree before changing it.

## Cross-folder rules worth remembering

- There are two settings trees: `latin/settings` holds runtime preference keys, defaults, and snapshots; `settings/` holds the Compose UI and fullapp screens.
- Ordinary typing, voice insertion, and fullapp sync should flow through `InputConnection`; do not write directly into the extract/fullapp text widgets.
- `WordComposer` is the source of truth for the current word; `inputlogic/EditorWordMirror.java` is the bridge that mirrors it into the host editor.
- Gemini dictation uses one connection per recording, bounded audio/control and transcript FIFOs that advance after local insertion checks, terminal failure with a literal interruption marker after readiness, and explicit restart. Intentional silence auto-stop drains and closes without a marker for unconfirmed noise/hypotheses; actual failures still report interruptions. Startup rejection leaves the editor untouched and reports the service reason. Interims never become editor text; no reconnect or automatic rotation. See `docs/gemini-transcription.md`.
- Missing-final waits begin at a submitted speech boundary, allowing long utterances with live interims. Interactive keyboard hiding, editor disconnection, and verified host clears cancel all voice work immediately; explicit mic stop still drains. Host clears use extracted-text monitoring plus actual host reads, including unchanged selection, and do not alone prove form submission.
- Voice text intentionally bypasses `EditorWordMirror`: `LatinIME` first clears the typed-word state with `finishInput()`, then commits each finalized Gemini transcript segment directly. Fullapp replay is another bypass, using raw `InputConnection` replacement when the IME reconnects to the original field.
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

Every new/updated PR, including docs, must follow
[the APK skill](.codex/skills/android-build-apk/SKILL.md): after final edits, run
`./tools/build-dist-apk.sh` and `python3 tools/apk-handoff.py check`, commit/push
`dist/HeliBoard.apk` and `dist/apk-metadata.json`, then run
`python3 tools/apk-handoff.py link`. Include its verified immutable download link
in the PR and final response. Later edits require rebuilding. External build
blockers require a draft PR with the actual error and APK explicitly unavailable.

Run relevant checks through `./tools/android-dev.sh`; use
[the testing matrix](docs/agent-development.md). Report phone testing separately:
a build/JVM test does not prove typing, editor, or microphone behavior. Keep
folder guides current when ownership or cross-folder contracts change; read
`.codex/AGENTS.md` for Gemini/MCP/agent-setup work.

# Research and report

If something goes wrong, stop the affected operation and report relevant findings, data, and logs.
Redact secrets and private content.
If unsure about something, research, search the web, find latest techniques and best practices.

# Next steps

Consider what was recently done, what's in progress, and how it contributes to the goal.
Decide and recommend what to do next.

# Goal: Make Android typing, dictation, and full-screen editing reliable and efficient
