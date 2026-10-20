# app/src/test/java/helium314/keyboard/latin/voice

Tests for the active Grok pipeline and inactive Gemini comparison implementation.

## Direct files
- `GeminiTranscriptionClientTest.kt` - `setup` wire format, audio framing, language resolution, server-message parsing, and transcript assembly.
- `GeminiTranscriptionClientStreamTest.kt` - end-to-end WebSocket lifecycle against a local `MockWebServer`, including the close-code-1007 setup-tier fallback, authoritative-only insertion, malformed response handling, and failure during drain.
- `TranscriptPostProcessorTest.kt` - finalized-text cleanup tests.
- `TranscriptionPreferencesTest.kt` - Gemini preference defaults, sanitization, and cleanup of previous providers' keys.
- `VoiceContextVocabularyTest.kt` - editor-derived speech-biasing vocabulary.

- `GeminiAudioQueueTest.kt` - production bounded sender, queue acceptance/backpressure/failure, speech versus trailing quiet/control frames, stale frames, and EOF queue deadline.
- `VoiceInputManagerTest.kt` - acknowledged transcript FIFO, bounded audio/control FIFO, terminal failures, immediate cancellation/restart during drain, and anchored deadlines using injected recorder/Grok client/network dependencies. Finals wait from the current speech epoch's submitted boundary; delayed older boundaries and live interims must allow long utterances. Covers intentional silence auto-stop, noisy PCM after a final, equal-deadline timer ordering, watchdog cancellation at stop, late drain finals, and duplicate final progress with queued-speech safeguards. Whole-stop tests bound setup/upload/EOF/close to one 20-second budget, release processing on expiry, reject late callbacks, preserve newer recordings, and prevent reentrant stop failure/cancellation from restoring the spinner.
- `VoiceNetworkMonitorTest.kt` - validated internet, route loss/handoff, stale observations, and API-21 receiver cleanup.
- `VoiceRecorderLifecycleTest.kt` - missed capture join/restart guard, callback ownership across an in-flight native read, immutable raw-energy speech evidence on quiet PCM during the speaking window, and errors/exceptions from a read interrupted by rapid pause/resume while new-phase errors still surface.

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
- Startup-rejection tests use the truncated depleted-credit detail from a device log (1011), an HTTP-429 handshake body, and queued pre-readiness PCM. No live billable request is needed; startup rejection must leave existing editor text untouched.
- InputLogic tests cover host commit/selection rejection, exceptions, preservation of confirmed text and caret after rejected replacements, cache refresh, balanced batches, and literal interruption markers. Acceptance means the editor call succeeded, not proof of semantic completeness upstream.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.

## Grok coverage
- `GrokTranscriptAssemblerTest.kt`: first-speaker filtering, provisional/complete distinctions, overlapping finals/EOF, repeated phrases, missing/revised attribution, timing, formatted mixed-speaker punctuation/quote/currency/emoji preservation, safe lexical-mismatch fallback and zero-duration EOF deduplication.
- `TranscriptDiagnosticsTest.kt`: punctuation/casing/short-span metadata and missing lexical/punctuation-label intervals, without raw transcript words/numbers or malformed-field echoes.
- `GrokTranscriptionClientTest.kt`: production header/URL configuration (semantic/timeout omission, fixed pause and VAD), safe pre-rejection diagnostics, readiness, binary/paced/bounded sender, controls, retry budgets, stale attempt fencing and terminal failure.
- `GrokTranscriptionStreamTest.kt`: real local WebSocket binary audio through filtered final/EOF/normal close. No live provider credentials.
- `GrokPreferencesTest.kt`: backup-excluded atomic key storage, interrupted-write recovery and clearing on API 21/35 (including API 21 backup-only recovery), preserved comparison credentials, vocabulary inheritance and limits.
- `VoiceInputManagerTest.kt` now injects Grok. Its advisory local-silence markers are logical boundaries, not wire finalization commands. Keep editor/network/lifecycle regression coverage. Human phone speech quality remains unverified.
