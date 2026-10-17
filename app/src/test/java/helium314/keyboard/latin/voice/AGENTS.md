# latin/voice tests

MAI-Transcribe-2-Streaming SDK lifecycle and local transcript regression tests.

## Direct files
- `BoundedAudioWriterTest.kt` - real byte-capacity accounting, capacity notifications, ordered resumption, write failure containment, and cancellation/rejected executor.
- `FakeVoiceNetworkMonitor.kt` - deterministic network preflight/loss seam.
- `VoiceNetworkMonitorTest.kt` - validation, default-network handoff, registration cleanup, and stale observer suppression.
- `FakeMaiSpeechSession.kt` - device-independent SDK seam for deterministic callbacks and recorded audio/commit/EOF operations; deferred writes exercise the same bounded writer as the SDK adapter.
- `MaiRecoveryTest.kt` - local versus transport readiness, disconnect grace, suffix-only and partial-chunk replay, offset mapping, finite retries/deadlines, replay backpressure/capacity, quiet tails, unsafe timing, EOF and cancellation.
- `MaiTranscriptionClientTest.kt` - Speech endpoint construction, locale hints, and Android/ABI gating.
- `MaiTranscriptionClientStreamTest.kt` - startup readiness, advisory commit tokens/offsets, missing acknowledgments and bounded EOF fallback, NoMatch, EOF draining, deduplication/repetitions, cancellation, terminal failures, backpressure, inconsistent timing, and speech liveness, boundary-anchored final deadlines, long utterances with live interims, quiet PCM, watchdog cancellation during EOF, unchanged provider punctuation delivery, and punctuation-only diagnostics without raw text.
- `VoiceInputManagerTest.kt` - microphone callbacks, startup/pause/stop, deferred commits before post-pause audio, restart failures and overflow while retaining draining transcripts/buffered audio, offline/network/service failure stops and markers, full-size PCM backpressure and upload deadlines, quiet audio delivery after local silence, acknowledged/reentrant transcript FIFO, terminal editor rejection/exception, control FIFO/overflow, failed resume, startup rejection without markers, cancellation, buffering limits, session replacement/rotation, and subsecond recorder configuration.
- `TranscriptionPreferencesTest.kt` - Speech key/region defaults, storage, validation, and bounded/idempotent conversion of saved silence seconds to milliseconds.
- `TranscriptPostProcessorTest.kt` - spoken punctuation, em-dash preservation, period/comma correction, the reported lowercase-continuation example, Unicode/whitespace, abbreviation and ellipsis guards, paragraph commands, and filler cleanup.
- `VoiceRecorderLifecycleTest.kt` - missed capture join/reuse guard, callback ownership, captured speech evidence, and native read errors/exceptions across rapid pause/resume.

## Notes
- Lifecycle tests use Robolectric with a paused main looper. No device-native SDK objects are created in these JVM tests.
- Compile/package against the pinned Android AAR to verify API compatibility. Service acceptance requires a configured Speech resource; use `tools/mai-streaming-smoke-test.py` with the Python SDK.
- Android native execution, microphone behavior, and latency require a supported device. Local tests cannot establish recognition quality.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
