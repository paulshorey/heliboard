# latin/voice tests

MAI-Transcribe-2-Streaming SDK lifecycle and local transcript regression tests.

## Direct files
- `BoundedAudioWriterTest.kt` - real byte-capacity accounting, capacity notifications, ordered resumption, write failure containment, and cancellation/rejected executor.
- `FakeVoiceNetworkMonitor.kt` - deterministic network preflight/loss seam.
- `VoiceNetworkMonitorTest.kt` - validation, default-network handoff, registration cleanup, and stale observer suppression.
- `FakeMaiSpeechSession.kt` - device-independent SDK seam for deterministic callbacks and recorded audio/commit/EOF operations; deferred writes exercise the same bounded writer as the SDK adapter.
- `MaiTranscriptionClientTest.kt` - Speech endpoint construction, locale hints, and Android/ABI gating.
- `MaiTranscriptionClientStreamTest.kt` - startup readiness, advisory commit tokens/offsets, missing acknowledgments and bounded EOF fallback, NoMatch, EOF draining, deduplication/repetitions, cancellation, terminal failures, backpressure, inconsistent timing, and oldest-audio deadlines.
- `VoiceInputManagerTest.kt` - microphone callbacks, startup/pause/stop, deferred commits before post-pause audio, restart failures and overflow while retaining draining transcripts/buffered audio, offline/network/service failure stops and markers, full-size PCM backpressure and upload deadlines, quiet audio delivery after local silence, cancellation, buffering limits, session replacement/rotation, and subsecond recorder configuration.
- `TranscriptionPreferencesTest.kt` - Speech key/region defaults, storage, validation, and bounded/idempotent conversion of saved silence seconds to milliseconds.
- `TranscriptPostProcessorTest.kt` - spoken punctuation, em-dash preservation across cleanup passes, paragraph commands, and filler cleanup.

## Notes
- Lifecycle tests use Robolectric with a paused main looper. No device-native SDK objects are created in these JVM tests.
- Compile/package against the pinned Android AAR to verify API compatibility. Service acceptance requires a configured Speech resource; use `tools/mai-streaming-smoke-test.py` with the Python SDK.
- Android native execution, microphone behavior, and latency require a supported device. Local tests cannot establish recognition quality.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
