---
name: voice-transcription
description: Maintain HeliBoard's MAI-Transcribe-2-Streaming recording, direct Azure Speech SDK client, final transcript insertion, settings, and diagnostics.
---

# Voice transcription

Read `docs/mai-transcription.md` and the nearest folder AGENTS.md before changing the pipeline. Use [Microsoft's Speech SDK guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk) and the pinned SDK source/API as the source of truth. See `api-reference.md` and `data-flow.md` for integration and ownership.

Preserve these contracts:

- Capture mono signed little-endian PCM16 at 16 kHz without headers. Forward every captured chunk, including locally quiet audio; use VAD only for commits and auto-stop, never to discard audio.
- Configure the resource key, matching region, and fixed MAI-Transcribe-2-Streaming model.
- Keep JNI operations off the main thread and lifecycle callbacks on the main looper.
- Gate native recognition to supported Android versions/ABIs without raising the keyboard's minimum.
- Bound local audio and SDK worker bytes. Retain the FIFO head on backpressure and resume on capacity. EOF waits for the local FIFO; commit records the captured boundary/speech epoch and is queued after its preceding audio but before resumed speech. Bound controls to 64; never drop/coalesce requests. Local writes do not acknowledge service receipt.
- Request advisory nonempty push-stream commits at silence/pause. Settle by final offsets and echoed tokens; NoMatch can settle without text. Missing confirmation triggers EOF drain/replacement after 60 seconds or 64 pending boundaries; never assume an advisory token must be echoed.
- Only final recognized segments reach InputConnection. Retain the 64-entry transcript FIFO head until local dispatch/cleanup succeeds; rejection/exception cancels later writes without retry or marker. Android remote booleans report dispatch, not host acceptance. Intermediate SDK events forward only liveness metadata, never provisional text.
- Deduplicate result IDs, preserving repeated phrases with separate IDs.
- Close input and wait for EOF before disposing a stopped or rotating session.
- Accept a mic restart during drain, buffering capture until outgoing EOF and preserving transcript order.
- Preserve model punctuation apart from spoken commands, adjacent punctuation correction, and single periods before horizontal whitespace plus a lowercase letter. Guard common/dotted abbreviations, ellipses and paragraph breaks; apply within finals and verified cross-final replacement, preserving whitespace. This is a casing heuristic and can join intentional lowercase sentence starts. Verify streaming-model support before adding generic SDK formatting/segmentation options. The integration leaves OutputFormat and ProfanityOption unset; do not assume generic Azure options are supported.
- Invalidate tokens on cancel/editor changes. Release all SDK handles on the worker.
- Let the SDK own acknowledgements and recovery. Do not replay audio from the application.
- Require validated internet before capture and observe network loss through drain. Keep the SDK Connection alive; disconnect while input is open and SDK errors end dictation without automatic retries. Mark uncertain audio with `[Dictation interrupted]` and require a deliberate new start.
- Bound local upload wait to 30 seconds. Active speech/pending words have a 30-second response watchdog refreshed by interims/finals; a separate missing-final wait starts at the current speech epoch's submitted boundary. Interims/controls cannot extend that wait, and new speech clears it. Quiet PCM alone requires no final. Stop clears speech timers and uses the separate 60-second EOF deadline. Progress stalls stop capture and preserve accepted finals to EOF; intentional silence auto-stop takes precedence at equal deadlines.
- Capture failures retain accepted speech to EOF, ignore later recorder callbacks, block restart while draining, and keep processing visible. Cancel dictation on editor rejection; no later final may follow an unwritten result. Cleanup and punctuation replacement select the range and use one commit operation; never delete accepted text before attempting its replacement. Restore text/cursor caches on rejected commits.
- Before readiness, failure leaves the editor untouched. After readiness/capture, actual failures insert one literal marker, bypassing punctuation/cleanup and verifying the host caret to preserve highlighted text.
- Carry editor protections through the MAI pipeline: verify host ranges/selections after flushing deferred batches, check belated selection callbacks against the actual caret, and cancel all work immediately on interactive hiding, input loss, or verified host clears. Null reads are unavailable; a clear does not prove submission. Screen-off hiding preserves recording.
- Keep key/region settings and diagnostics free of raw credentials, transcript contents, or service error details. Incoming MAI final diagnostics may include only length and punctuation counts before normalization.

Validate with the client, manager, preference, transcript-formatting, and diagnostics tests, and the canonical APK build. `tools/mai-streaming-smoke-test.py` uses the Python Speech SDK for a credentialed check; a supported Android device is required to validate native execution and latency.
