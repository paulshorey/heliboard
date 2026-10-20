# latin/voice

Active provider: Grok Voice Transcribe 2.0 (`grok-voice-transcribe-2.0`).

## Direct files
- `GrokTranscriptionClient.kt` - header-authenticated binary PCM WebSocket, remote readiness, paced/bounded sender, ordered controls, bounded pre-audio HTTP retries and generation fencing.
- `GrokTranscriptAssembler.kt` - finalized word/timestamp ledger, first-speaker filtering, utterance/EOF reconciliation and bounded duplicate/revision handling.
- `GrokFormattedTranscript.kt` - bounded lexical alignment to native formatted text; retains punctuation/casing/quotes when filtering guests and falls back to attributed tokens if alignment fails.
- `GrokEventDiagnostics.kt` + `TranscriptDiagnostics.kt` - content-free event, attribution, punctuation/casing and editor-stage metadata; never raw transcript words or numbers.
- `VoiceInputManager.kt` - recording lifecycle, network monitoring, audio/control/transcript FIFO, watchdogs and acknowledged local editor insertion.
- `VoiceRecorder.kt` - mono PCM16/16 kHz capture with advisory local RMS activity detection.
- `VoiceNetworkMonitor.kt` - validated default-network preflight/loss observation, with API 21 compatibility.
- `TranscriptSegment.kt` - append-only finalized editor segment.
- `TranscriptPostProcessor.kt` - Grok native-text path preserves words/casing; explicit commands and redundant punctuation cleanup remain. Historical heuristics are retained for comparison tests.
- `VoiceContextVocabulary.kt` - proper-noun harvesting and prioritized keyword hints, never editor paragraphs.
- `GeminiTranscriptionClient.kt` - inactive previous provider, retained with its focused tests; not instantiated by this branch.

## Contracts
- Patient defaults: semantic confidence 0.9, forced timeout 5000 ms, native punctuation on, provider VAD 0.08. Preserve saved settings; the patient preset applies recommended punctuation/turn values explicitly. Semantic-only mode omits timeout. Fixed-pause mode omits both Smart Turn parameters and sends endpointing (default 2000 ms). Session config is fixed through drain.
- Read `docs/grok-transcription.md` for protocol, limits, retry policy, privacy and phone acceptance. Accuracy takes priority over latency; only completed attributed utterances or authoritative EOF finals enter the editor. Never promote interims.
- Wait for `transcript.created`; stream raw PCM with `encoding=pcm`, not base64. One recording owns one connection. No active-stream reconnect/replay or automatic speaker reset.
- Only explicit HTTP 429/503 before readiness/audio can retry, twice inside the original 12-second startup budget. Network/transport loss and account/configuration errors are terminal.
- Select the earliest finalized nonempty word's speaker in audio order, not numeric label zero or interim arrival. Keep the selection through pause/resume. Fail unresolved attribution; do not fall back to all-speaker text. Guest speech settles progress without insertion.
- Chunk-final, utterance-final and EOF text may overlap. Reconcile by audio positions; repeated phrases at new positions remain. Never prefix-diff arbitrary transcript strings or rewrite already inserted words/labels.
- Align selected word runs to the complete native formatted text. Preserve formatting through held chunks and empty EOF; only fall back to attributed tokens when complete lexical alignment fails. Never insert extra native words without attribution. Bound cached formatting to 131,072 characters. Native punctuation is on by default and bypasses all local command/paragraph cleanup. Local silence is advisory; only explicit Pause sends `finalize`, and Stop queues `audio.done` after preceding PCM.
- Retain quiet PCM. Bound raw FIFO to 960 KB, socket frames to 256 KiB, controls/transcripts to 64. Sender pacing does not accumulate credit during idle time. Backpressure retains the exact queue head; controls and newer audio cannot overtake it.
- Keep current 30-second upload/response/final safeguards. Boundary-anchored final waits cannot be postponed by interims. Long ongoing speech with live progress remains allowed. Stop has 15 seconds for EOF/close inside one absolute 20-second budget. The 30-minute capture limit is app policy, not a provider claim.
- Final progress must cover captured speech timestamps and held words, not merely drained socket bytes. EOF duration can settle processed quiet/noise tails. Missing data, overflow or protocol errors stop explicitly.
- Advance editor FIFO only after local insertion/cleanup success. Android remote InputConnection booleans report dispatch, not host acceptance. Never retry a potentially partial editor edit.
- Failure invalidates generations before stopping capture. Preserve inserted text, clear remaining work, and attempt one literal interruption marker after readiness/capture. Startup rejection leaves the editor untouched. Editor failure suppresses the marker.
- Interactive keyboard hiding, input/view finish, editor loss and verified host clears cancel active/draining work immediately. Screen-off hiding preserves recording; explicit Stop drains. Preserve the recorder-tail barrier and reentrant cancellation checks.
- Grok key/config access lives in `latin/settings/GrokPreferences.kt`. Store personal credentials outside backups and preserve other providers' credentials. Log diagnostics version 2: effective settings, event kind/confidence/timing, anonymous speaker counts, missing lexical/punctuation labels before rejection, rendering/hold time and content-free punctuation/casing shapes through editor insertion. Throttle interims; bound missing-token intervals and speaker summaries. Never payloads, keys, hints, words, numbers from the transcript, or transcript hashes.
- JVM/MockWebServer checks establish local contracts only. Human streaming speech, punctuation, diarization, microphone/editor and radio-loss behavior remain phone acceptance gates.

Update this file and neighboring AGENTS/docs when these contracts change.
