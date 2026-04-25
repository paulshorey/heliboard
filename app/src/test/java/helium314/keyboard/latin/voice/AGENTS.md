# app/src/test/java/helium314/keyboard/latin/voice

Tests for the AssemblyAI Universal-Streaming voice pipeline.

## Direct files
- `AssemblyAITranscriptionClientTest.kt` - connection-URL/parameter assembly and server-event parsing tests.
- `TranscriptPostProcessorTest.kt` - finalized-text cleanup tests (spelled-out punctuation, casing/punctuation correction).

## Non-obvious notes
- Voice bugs often split cleanly between transport/session setup and local post-processing, so keep that distinction clear in new tests.
- Universal-Streaming carries session configuration in WebSocket query parameters, so `AssemblyAITranscriptionClientTest` reads the parsed `HttpUrl` rather than a JSON request body.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
