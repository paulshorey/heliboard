# app/src/test/java/helium314/keyboard/latin

Core IME pipeline tests.

## Direct files
- `InputLogicTest.kt` - broad regression suite for typing, deletion, correction, and mirror-based input behavior.
- `LatinIMETextSnapshotTest.java` - tests for fullapp text snapshot helpers.
- `LocaleUtilsTest.kt` - locale utility tests.
- `ScriptUtilsTest.kt` - script utility tests.
- `StringUtilsTest.kt` - string helper tests.
- `SuggestTest.kt` - suggestion pipeline tests.

## Subfolders
- `utils/` - focused utility tests.
- `voice/` - transcription pipeline tests.

## Non-obvious notes
- `InputLogicTest.kt` is the most important behavior-regression suite for ordinary typing.
- The mix of Java and Kotlin tests here is intentional; keep whichever language is clearest for the target code.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
