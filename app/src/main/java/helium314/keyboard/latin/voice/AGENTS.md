# latin/voice

Muse Voice Transcribe realtime dictation (`muse-voice-transcribe-1.0`).

## Direct files
- `MuseTranscriptionClient.kt` - JSON handshake, paced raw binary PCM, provider events, per-turn deadlines and EOF/close.
- `MuseTurnAssembler.kt` - completed-turn ordering, first-speaker selection/filtering and duplicate final handling.
- `TranscriptSegment.kt` - finalized append-only editor chunk.
- `TranscriptPostProcessor.kt` - `processMuseParagraph` handles explicit spoken commands; legacy pure shaping helpers are not on the Muse path.
- `VoiceContextVocabulary.kt` - keyword hints from user terms, built-ins and optionally harvested editor words.
- `VoiceInputManager.kt` - capture/transport orchestration, bounded FIFO and local insertion acknowledgment, pause-silence generation, stop and cancellation.
- `VoiceRecorder.kt` - PCM16 capture, 24 kHz preferred/16 kHz fallback, local RMS activity and native pause/resume ownership.
- `VoiceNetworkMonitor.kt` - validated default-network preflight and terminal loss observation, with API 21–22 fallback.

## Contracts
- Read `docs/muse-transcription.md` before changes. Accuracy takes priority over latency. Partials never reach the editor. DIARIZATION is the only app mode and requires completed attributed turns. Preserve the original recorder callback, PCM queue and request configuration after the responsive/timing experiment's phone punctuation regression; retired experiment preferences are ignored.
- Select the earliest nonempty speech turn's speaker, by observed start order. Labels are session-scoped; filtering only promises sequential-speaker behavior. Missing/mixed labels hold text and fail instead of guessing. Never split a completed turn using partial text.
- One recording owns one socket. No reconnect/replay/automatic rotation. Stop/drain at 55 minutes; explicit restart resets speaker selection.
- Send `Bearer <key>` in the first JSON frame, wait for `{sessionId: ...}`, then raw binary PCM. Local silence cannot finalize a turn. Only EOF sends `endStream`; read until close 1000.
- Bound raw PCM to 30 seconds at the actual sample rate and transcript/pending turn FIFOs to 64. Retain exact FIFO heads on backpressure. Never drop/coalesce audio. Pace near real time and cap audio lead/socket queue at two seconds.
- Pause stops microphone capture and uses generated silence on the same audio FIFO, behind posted capture tail. Resume/end/cancel removes the generator. Paused time may be billed.
- Recognized turns wait at most 30 seconds after provider speechEnd for a completed attributed result. Long speech with progress continues. Overall stop is bounded to 60 seconds, including capture, setup, upload and close.
- Keep ended no-word turns in start order until their 30-second deadline or normal close, then retire them and drain ready finals without selecting a speaker. This permits late finals with no partials; nonempty text after retirement fails visibly. Do not acknowledge submitted speech from an empty socket queue: its byte offset must also be covered by provider processing. Normal close rejects unprocessed speech.
- Callbacks, controls, timers and insertion acknowledgments are main-looper serialized and generation fenced. Retain transcript FIFO head until local dispatch/cleanup success. Android InputConnection booleans are dispatch, not host acceptance. Rejection/exception stops later writes without retrying partial edits.
- Startup rejection leaves the editor untouched. Actual failure after readiness/capture attempts one literal interruption marker. Editor rejection and cancellation suppress markers; intentional discarded speakers and quiet tails do not create missing-text failures.
- Verified normal closure acknowledges settlement to the manager even without completed words, clearing local RMS false positives. Send that acknowledgment only after checking pending recognized turns and submitted speech processing; preserve the generation fence if a callback restarts/cancels dictation.
- Preserve interactive hiding, host-clear and input-target cancellation, native capture thread/phase ownership, verified selected-range replacement and deferred-editor checks. Screen-off hiding alone preserves capture. Explicit stop drains.
- Credentials and transcript payloads stay out of diagnostics. Only words harvested for keywords may leave editor context. Settings keys/UI live in their respective settings packages.
- The fixed strip mic starts this pipeline; toolbar VOICE invokes the Android system voice IME.

## Keep this file current
Update this map and neighboring AGENTS/docs when files or contracts change.
