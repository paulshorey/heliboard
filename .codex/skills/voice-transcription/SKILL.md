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
  `GrokFormattedTranscript` aligns full lexical content to native formatted spans,
  including mixed speakers. Unalignable text falls back to attributed tokens; never
  insert extra unattributed words. Bound formatting to 131,072 characters.
  Never insert interims, guess missing labels, select numeric speaker zero, or
  deduplicate distinct utterances by text. Diarization is not enrolled identity.
- Preserve native wording/casing/punctuation. Default native-punctuation mode applies only caret spacing and bypasses command
  conversion/paragraph cleanup. Turning it off explicitly enables legacy commands
  and redundant-punctuation cleanup. Historical
  Gemini heuristics must not run against Grok output.
- Default to semantic confidence 0.9 / forced timeout 5000 ms, with an explicit
  patient preset that preserves separately saved comparison values until applied.
  Semantic-only mode omits timeout; fixed-pause mode omits both Smart Turn params
  and sends endpointing (default 2000 ms). Pin provider VAD to 0.08 by default.
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
  credential belongs in the APK. Diagnostics v2 may log event kinds/confidence/intervals, anonymous labels/counts,
  missing lexical versus punctuation labels, rendering and punctuation/casing
  shapes at provider/filter/editor stages. Never raw transcript words/numbers,
  audio, provider payloads or transcript hashes. Local silence logs must say no
  command; wire finalize is explicit Pause only.
- Run relevant checks through `./tools/android-dev.sh`. Every PR requires the
  final canonical APK and immutable download per `android-build-apk`.

Retained Gemini code, tests, vendored skills and MCP are inactive comparison
reference. For work specifically on that provider, use its own skills and docs.
