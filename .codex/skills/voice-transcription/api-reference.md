# Muse API reference for HeliBoard

- Model: `muse-voice-transcribe-1.0`.
- URL: `wss://api.meta.ai/v1/asr/realtime`.
- First JSON frame: authorization.accessToken = `Bearer <key>`, model,
  audioEncoding = `PCM_24KHZ` / `PCM_16KHZ`, mode = `DIARIZATION` by default / opt-in `ENDPOINTING` (all voices),
  partialMode = `CUMULATIVE`, emitAudioProgress = true, optional keywords and
  languageBias (supported language names).
- Server acknowledges with sessionId and no type; only then send binary raw mono
  little-endian PCM16. Do not send WAV/base64 or an Authorization header.
- speechStart / speechEnd / speechComplete correlate by turnId. Speaker and
  cumulative transcript events belong to the active span and have no turnId.
- speechComplete is full final text including punctuation; transcript.final does
  not complete a DIARIZATION or ENDPOINTING turn. Speaker labels are retrospective/per-session.
- Empty final transcript flushes can arrive at EOF after a settled turn. Ignore
  these wordless events only; unattributed nonempty text remains an error.
- End input with `{"type":"endStream"}` after all audio. Read until close 1000.
  There is no turn-commit command or resume token.
- Close 1008: configuration/pacing failure; 1011: backend/session limit;
  1013: stream rate limit. HeliBoard requires explicit restart after failures.
- Native 24 kHz / accepted 16 kHz, <=60-minute session. Meta rejects >5-second audio
  backlog and sustained below-real-time ingress. Pause requires PCM silence.
- No documented end-of-speech tuning, smart/verbatim, confidence scores, word
  timestamps, enrolled identity or general transcript rewriting.

Authoritative schemas: https://dev.meta.ai/docs/api-reference/voice/schemas
Transport: https://dev.meta.ai/docs/api-reference/voice/realtime
Guide: https://dev.meta.ai/docs/speech-to-text

Use the reference Bearer prefix even though one cookbook helper omits it. Never
copy its dropped-audio behavior. Verify actual service behavior with the opt-in
probe; local tests cannot establish account access or speaker accuracy.
