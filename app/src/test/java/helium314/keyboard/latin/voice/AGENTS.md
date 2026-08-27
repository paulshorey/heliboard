# app/src/test/java/helium314/keyboard/latin/voice

Tests for the Gemini Live voice pipeline.

## Direct files
- `GeminiTranscriptionClientTest.kt` - `setup` wire format, audio framing, language resolution, server-message parsing, and transcript assembly.
- `GeminiTranscriptionClientStreamTest.kt` - end-to-end WebSocket lifecycle against a local `MockWebServer`, including the close-code-1007 setup-tier fallback.
- `TranscriptPostProcessorTest.kt` - finalized-text cleanup tests.
- `TranscriptionPreferencesTest.kt` - Gemini preference defaults, sanitization, and cleanup of previous providers' keys.
- `VoiceContextVocabularyTest.kt` - editor-derived speech-biasing vocabulary.

## Non-obvious notes
- Voice bugs split cleanly between transport/session setup and local post-processing; keep that distinction clear in new tests.
- `GeminiTranscriptionClientTest.kt` needs Robolectric even though it tests pure functions: the client builds payloads with `org.json`, which is an unimplemented stub on the plain JVM test classpath.
- `GeminiTranscriptionClientTest.kt` should keep asserting the two `setup` placement rules that fail catastrophically at runtime — `inputAudioTranscription` beside `generationConfig`, and `responseModalities: ["TEXT"]` inside it — plus that each `SetupTier` drops exactly one feature.
- `GeminiTranscriptionClientStreamTest.kt` points `GeminiTranscriptionClient.streamingEndpoint` at `MockWebServer` and restores it in `@After`. It also resets `negotiatedSetupTier`, which is process-wide state that would otherwise leak between tests.
- Robolectric's main looper is paused, and the client posts callbacks there from OkHttp's reader thread, so the stream tests pump with `awaitUntil { }` (idle the looper, check, sleep) instead of a bare latch.
- `VoiceContextVocabularyTest.kt` covers what must *not* be biased as much as what must: common words, sentence-initial capitals, digits, and URL/path/email/identifier fragments.
- Neither suite can confirm what Google's server does with a given `setup` field. Use `tools/gemini-live-smoke-test.py` with a real `GEMINI_API_KEY` for that.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
