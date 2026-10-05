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
- Bound local audio and SDK worker bytes. Retain the FIFO head on backpressure and resume on capacity; queue commit/EOF only after all local audio is accepted. Local writes do not acknowledge service receipt.
- Request advisory nonempty push-stream commits at silence/pause. Settle by final offsets and echoed tokens; NoMatch can settle without text. Missing confirmation triggers EOF drain/replacement after 60 seconds or 64 pending boundaries; never assume an advisory token must be echoed.
- Only final recognized segments reach InputConnection. Never promote a hypothesis on failure.
- Deduplicate result IDs, preserving repeated phrases with separate IDs.
- Close input and wait for EOF before disposing a stopped or rotating session.
- Accept a mic restart during drain, buffering capture until outgoing EOF and preserving transcript order.
- Preserve model punctuation during local cleanup. Verify streaming-model support before adding generic SDK formatting/segmentation options; OutputFormat and ProfanityOption are ignored.
- Invalidate tokens on cancel/editor changes. Release all SDK handles on the worker.
- Let the SDK own acknowledgements and recovery. Do not replay audio from the application.
- Require validated internet before capture and observe network loss through drain. Keep the SDK Connection alive; disconnect while input is open and SDK errors end dictation without automatic retries. Mark uncertain audio with `[Dictation interrupted]` and require a deliberate new start.
- Bound the oldest local upload wait and oldest unfinalized submitted audio to 30 seconds; only confirmed progress settles their age. EOF has its separate 60-second deadline. An upload timeout is terminal; a final-progress timeout stops capture and blocks restart while preserving accepted speech through EOF. These policy limits may stop capture in healthy slow sessions. NoMatch or timing gaps alone are not proof of missing speech.
- Capture failures retain accepted speech to EOF, ignore later recorder callbacks, block restart while draining, and keep processing visible. Cancel dictation on editor rejection; no later final may follow an unwritten result.
- Keep key/region settings and diagnostics free of raw credentials or service error details.

Validate with the client, manager, preference, transcript-formatting, and diagnostics tests, and the canonical APK build. `tools/mai-streaming-smoke-test.py` uses the Python Speech SDK for a credentialed check; a supported Android device is required to validate native execution and latency.
