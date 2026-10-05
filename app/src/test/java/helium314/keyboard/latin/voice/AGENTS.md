# latin/voice tests

MAI-Transcribe-2-Streaming protocol and local transcript processing regression tests.

## Direct files
- `MaiTranscriptionClientTest.kt` - endpoint construction, deployment configuration, PCM format, null VAD/noise reduction, and language hints.
- `MaiTranscriptionClientStreamTest.kt` - real WebSocket frames against MockWebServer: header authentication, handshake gating, completion draining, pause/resume, ordered and repeated segments, cancellation, errors, and timeouts.
- `VoiceInputManagerTest.kt` - mocked microphone callbacks through a real local socket, covering startup/pause/stop, retry backoff, prefix draining, cancellation, and session rotation.
- `TranscriptionPreferencesTest.kt` - Azure settings defaults, storage, validation, and sanitization.
- `TranscriptPostProcessorTest.kt` - spoken punctuation, paragraph commands, and filler cleanup.

## Notes
- Protocol tests use Robolectric because org.json is stubbed in plain Android JVM tests.
- The main looper is paused; asynchronous socket tests pump it in `awaitUntil` and use protocol barriers rather than assuming a network callback already ran.
- Service acceptance and recognition accuracy require `tools/mai-streaming-smoke-test.py` with a configured Azure deployment; local tests verify the documented wire protocol and application behavior.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
