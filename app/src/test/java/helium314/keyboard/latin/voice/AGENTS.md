# Voice pipeline tests

- `MuseLatencyDiagnosticsTest.kt`: event/final/release timing, sampled progress and exclusion of payloads/credentials.
- `MuseTurnAssemblerTest.kt`: start-order selection, out-of-order completion, cumulative partial exclusion, repeated utterances, missing/mixed labels, bounded pending turns and speech-end final waits.
- `MuseTranscriptionClientTest.kt`: mode-specific handshake/assembly and safe restart defaults, raw sender pacing, processing lead, authoritative selected-speaker delivery, EOF/close, stale callbacks and invalid payloads.
- `MuseTranscriptionClientStreamTest.kt`: real local WebSocket transport, body authentication, binary audio and late final drainage.
- `VoiceInputManagerTest.kt`: capture/transcript FIFOs, editor acknowledgment/rejection, generation fencing, network loss, pause silence ordering, stop budgets and cancellation.
- `VoiceRecorderLifecycleTest.kt`: stuck capture joins, original callback ownership and rapid pause/resume read phases.
- `TranscriptionPreferencesTest.kt`: backup-excluded credential lifecycle, conservative mode/diagnostic defaults and non-destructive vocabulary migration.
- `TranscriptPostProcessorTest.kt`: explicit Muse commands/preservation, plus legacy pure helper behavior. The shipping path uses processMuseParagraph.
- `VoiceContextVocabularyTest.kt` and `VoiceNetworkMonitorTest.kt`: keyword harvesting and Android network semantics.

Use Robolectric for org.json and looper tests. Local mocks establish client behavior,
not Meta access or transcription quality. Run tools/muse-streaming-smoke-test.py
with a user-supplied key and perform the phone matrix in docs/muse-transcription.md.
Update this map when suites or contracts change.
