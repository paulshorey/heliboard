---
name: voice-transcription
description: Maintain HeliBoard's MAI-Transcribe-2-Streaming recording, direct Azure Speech SDK client, final transcript insertion, settings, and diagnostics.
---

# Voice transcription

Read `docs/mai-transcription.md` and the nearest folder AGENTS.md before changing the pipeline. Use [Microsoft's Speech SDK guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk) and the pinned SDK source/API as the source of truth. See `api-reference.md` and `data-flow.md` for integration and ownership.

Preserve these contracts:

- Capture mono signed little-endian PCM16 at 16 kHz without headers.
- Configure the resource key, matching region, and fixed MAI-Transcribe-2-Streaming model.
- Keep JNI operations off the main thread and lifecycle callbacks on the main looper.
- Gate native recognition to supported Android versions/ABIs without raising the keyboard's minimum.
- Buffer startup audio with a fixed bound; bound queued SDK writes.
- Request advisory nonempty push-stream commits at silence/pause. Settle by final offsets and echoed tokens; NoMatch can settle without text. Missing confirmation triggers EOF drain/replacement after 60 seconds or 64 pending boundaries; never assume an advisory token must be echoed.
- Only final recognized segments reach InputConnection. Never promote a hypothesis on failure.
- Deduplicate result IDs, preserving repeated phrases with separate IDs.
- Close input and wait for EOF before disposing a stopped or rotating session.
- Accept a mic restart during drain, buffering capture until outgoing EOF and preserving transcript order.
- Preserve model punctuation during local cleanup. Verify streaming-model support before adding generic SDK formatting/segmentation options; OutputFormat and ProfanityOption are ignored.
- Invalidate tokens on cancel/editor changes. Release all SDK handles on the worker.
- Let the SDK own acknowledgements and recovery. Do not replay audio from the application.
- Report unfinished speech on unrecoverable failure; keep bounded startup-only backoff.
- Keep key/region settings and diagnostics free of raw credentials or service error details.

Validate with the client, manager, preference, transcript-formatting, and diagnostics tests, and the canonical APK build. `tools/mai-streaming-smoke-test.py` uses the Python Speech SDK for a credentialed check; a supported Android device is required to validate native execution and latency.
