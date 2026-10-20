---
name: voice-transcription
description: HeliBoard Grok Voice Transcribe 2.0 streaming dictation, first-speaker filtering, native punctuation, recording lifecycle, queues, credentials and editor insertion. Use for microphone capture, streaming, transcription results, silence or voice UI.
---

# Voice transcription

The active provider on this branch is Grok Voice Transcribe 2.0. Read
`docs/grok-transcription.md` and `latin/voice/AGENTS.md` before changes. Accuracy
and speaker filtering take priority over latency. No protocol probe or working
key is required to implement the app; real streaming speech acceptance is on the
owner's phone after installing the PR APK and entering their key.

Microphone → VoiceRecorder (PCM16/16 kHz mono) → bounded paced WebSocket →
GrokTranscriptAssembler → VoiceInputManager FIFO → LatinIME/InputConnection.

- Use current official [STT docs](https://docs.x.ai/developers/model-capabilities/audio/speech-to-text)
  and [streaming schemas](https://docs.x.ai/stt-streaming.ws.json). Do not reuse
  Speech-to-Speech session/auth assumptions for STT.
- `GrokTranscriptionClient.kt` owns header auth, `transcript.created` readiness,
  raw binary PCM, paced bounded sender, ordered `finalize`/`audio.done`, startup
  retries and generation fencing. Only explicit pre-audio HTTP 429/503 may retry,
  twice inside 12 seconds. Active transport/provider failures are terminal.
- `GrokTranscriptAssembler.kt` waits for completed attributed utterances/EOF,
  filters the first finalized speaker by words, and reconciles audio positions.
  Never insert interims, guess missing labels, select numeric speaker zero, or
  deduplicate distinct utterances by text. Diarization is not enrolled identity.
- Preserve native wording/casing/punctuation. Only explicit spoken commands,
  redundant punctuation and caret spacing are local transforms. Historical
  Gemini heuristics must not run against Grok output.
- Forward quiet PCM. Local silence is advisory, never a forced semantic turn.
  Explicit Pause finalizes; Stop drains captured tail/PCM before EOF. A normal
  peer close can race the local acknowledgement. Do not treat close(false) alone
  as failure after a valid EOF.
- Keep 960 KB PCM, 256 KiB socket, 64 control/transcript FIFO bounds, 30-second
  upload/response/final limits, and the absolute 20-second Stop budget. Final
  progress must cover speech audio positions, not just drained socket bytes.
- Invalidate recording/connection generations before cancellation. Keyboard hide,
  host clear/loss and input finish cancel immediately. Preserve verified editor
  selection/replacement and never retry a possibly partial editor edit.
- `GrokPreferences.kt` stores the user's key with AtomicFile outside backups;
  preserve other providers' settings/keys and inherit only vocabulary. No shared
  credential belongs in the APK. No raw transcript/audio/provider payloads in logs.
- Run relevant checks through `./tools/android-dev.sh`. Every PR requires the
  final canonical APK and immutable download per `android-build-apk`.

Retained Gemini code, tests, vendored skills and MCP are inactive comparison
reference. For work specifically on that provider, use its own skills and docs.
