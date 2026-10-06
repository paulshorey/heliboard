# app/src/test/java/helium314/keyboard/latin/voice

Tests for the Gemini Live voice pipeline.

## Direct files
- `GeminiTranscriptionClientTest.kt` - `setup` wire format, audio framing, language resolution, server-message parsing, and transcript assembly.
- `GeminiTranscriptionClientStreamTest.kt` - end-to-end WebSocket lifecycle against a local `MockWebServer`, including the close-code-1007 setup-tier fallback, authoritative-only insertion, malformed response handling, and failure during drain.
- `TranscriptPostProcessorTest.kt` - finalized-text cleanup tests.
- `TranscriptionPreferencesTest.kt` - Gemini preference defaults, sanitization, and cleanup of previous providers' keys.
- `VoiceContextVocabularyTest.kt` - editor-derived speech-biasing vocabulary.

- `GeminiAudioQueueTest.kt` - production bounded sender, queue acceptance/backpressure/failure, stale frames, and EOF queue deadline.
- `VoiceInputManagerTest.kt` - acknowledged transcript FIFO, audio/control FIFO, terminal failures, restart/drain guards, and anchored deadlines using injected recorder/client/network dependencies.
- `VoiceNetworkMonitorTest.kt` - validated internet, route loss/handoff, stale observations, and API-21 receiver cleanup.
- `VoiceRecorderLifecycleTest.kt` - missed capture join/restart guard and callback ownership across an in-flight native read.

## Non-obvious notes
- Voice bugs split cleanly between transport/session setup and local post-processing; keep that distinction clear in new tests.
- `GeminiTranscriptionClientTest.kt` needs Robolectric even though it tests pure functions: the client builds payloads with `org.json`, which is an unimplemented stub on the plain JVM test classpath.
- `GeminiTranscriptionClientTest.kt` should keep asserting the two `setup` placement rules that fail catastrophically at runtime — `inputAudioTranscription` beside `generationConfig`, and `responseModalities: ["TEXT"]` inside it — plus that each `SetupTier` drops exactly one feature.
- `GeminiTranscriptionClientStreamTest.kt` points `GeminiTranscriptionClient.streamingEndpoint` at `MockWebServer` and restores it in `@After`. It also resets `negotiatedSetupTier`, which is process-wide state that would otherwise leak between tests.
- Robolectric's main looper is paused, and the client posts callbacks there from OkHttp's reader thread, so the stream tests pump with `awaitUntil { }` (idle the looper, check, sleep) instead of a bare latch.
- `VoiceContextVocabularyTest.kt` covers what must *not* be biased as much as what must: common words, sentence-initial capitals, digits, and URL/path/email/identifier fragments.
- Neither suite can confirm what Google's server does with a given `setup` field. Use `tools/gemini-live-smoke-test.py` with a real `GEMINI_API_KEY` for that.

- Manager fakes capture the actual callbacks and drive the paused main looper. Client `internal` methods have mangled JVM names; Mockito answers normalize them with `substringBefore('$')`.
- API-21 network tests use `android.app.Application` to isolate the monitor from unrelated app subtype initialization.
- InputLogic tests cover host commit/selection rejection, exceptions, preservation of confirmed text and caret after rejected replacements, cache refresh, balanced batches, and literal interruption markers. Acceptance means the editor call succeeded, not proof of semantic completeness upstream.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
