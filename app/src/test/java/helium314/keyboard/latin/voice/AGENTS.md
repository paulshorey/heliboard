# app/src/test/java/helium314/keyboard/latin/voice

Tests for the Soniox voice pipeline.

## Direct files
- `SonioxTranscriptionClientTest.kt` - session config and transcript-assembly tests.
- `TranscriptPostProcessorTest.kt` - finalized-text cleanup tests.
- `TranscriptionPreferencesTest.kt` - Soniox preference cleanup tests.

## Non-obvious notes
- Voice bugs often split cleanly between transport/session setup and local post-processing, so keep that distinction clear in new tests.
- `SonioxTranscriptionClientTest.kt` should cover token assembly details such as `<end>`/`<fin>` filtering and `attachesToPrevious` for punctuation or mid-word continuations.
- `TranscriptPostProcessorTest.kt` covers both paragraph command processing and pre-commit helpers such as leading-casing adjustment and mid-sentence trailing-punctuation stripping.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
