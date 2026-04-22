# app/src/test

JVM and Robolectric tests.

## Subfolders
- `java/` - all current unit/Robolectric tests live under `helium314/keyboard`.

## Non-obvious notes
- This project relies heavily on Robolectric shadows for IME-specific behavior; test files may contain custom shadow classes alongside the actual tests.
- For typing regressions, start with `InputLogicTest.kt`; for voice, start with the `latin/voice` tests.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
