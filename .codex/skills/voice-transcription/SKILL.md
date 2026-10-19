---
name: voice-transcription
description: Muse Voice Transcribe dictation, first-speaker filtering, PCM capture/pacing, pause silence, final-only editor insertion and lifecycle safeguards. Use for microphone, transcription or voice-input changes.
---

# Voice transcription

Read `docs/muse-transcription.md` for the current protocol and acceptance matrix.
This local skill is maintained with the product; the vendored Gemini skills are
retained for historical/future Gemini work and must not be edited incidentally.

## Flow and contracts

Microphone → VoiceRecorder (PCM16, 24 kHz preferred / 16 kHz fallback) → bounded
VoiceInputManager FIFO → MuseTranscriptionClient (paced binary WebSocket) →
MuseTurnAssembler → completed selected-speaker TranscriptSegment → acknowledged
manager FIFO → LatinIME/InputConnection.

- Accuracy over latency. Partials never become editor text. In DIARIZATION use
  speechComplete, not transcript.final or speechEnd, for completed text.
- First JSON frame authenticates with authorization.accessToken = Bearer <key>.
  Wait for sessionId acknowledgment. No query/header credentials for this socket.
- Speaker events label the preceding active span and have no turnId. Associate
  them with speechStart, retain completed turns keyed by ID and release in start
  order. Select the earliest nonempty attributed speaker, not the first final to
  arrive. Missing or mixed labels block and fail, never guess from partials.
- Labels are per-session and cover sequential speakers; no enrolled identity or
  guaranteed overlapping-voice rejection. The first speaker might be a TV.
- Forward quiet capture too. Local VAD controls activity/auto-stop only. There is
  no client turn commit; endStream ends the entire session. Keep reading until
  normal server closure.
- PCM FIFO: 30 seconds at actual rate. Pending turns/transcripts: 64. Pace against
  a monotonic clock with one 100 ms frame of headroom, <=2 s submitted processing
  lead and socket backlog. Stop visibly instead of dropping frames.
- Pause stops native capture and generates silence on the same FIFO after a
  capture-tail barrier. Resume/stop/cancel removes its timer. Silence may be billed.
- One recording owns one socket; no automatic reconnect/replay/rotation. Stop at
  55 minutes before the service cap. Stop budget is 60 seconds overall. Per-turn
  recognized finals wait 30 seconds once ended or completed text is present; progress cannot
  extend a missing final. Long uninterrupted speech can continue.
- Preserve main-looper fencing, transcript acknowledgment, verified replacements,
  actual host-clear checks, target/hide cancellation and recorder callback/phase
  ownership. Never retry a possibly partial editor operation.
- Startup rejection leaves the editor untouched. Actual failure after capture and
  readiness attempts a literal interruption marker. Editor rejection/cancellation
  suppress markers. Other-speaker settlement is intentional, not lost text.
- LatinIME preserves completed wording/casing. processMuseParagraph applies only
  explicit sentence-form spoken commands and punctuation cleanup. Legacy pure
  helpers are not on the shipping Muse path.
- The user's key is private to noBackupFilesDir. Preserve comparison-provider
  credentials. Vocabulary inherits once; only harvested words leave editor text.
- Diagnostics contain phase/counts/timing, never raw transcript or credentials.

## Validation

Use `./tools/android-dev.sh smoke`, relevant editor/log tests, and the canonical
APK handoff skill. Run the real-provider probe only with a user-supplied key:
`python3 tools/muse-streaming-smoke-test.py --audio /private/speech.wav`.
Its key comes from MUSE_API_KEY / MODEL_API_KEY. No transcripts print by default.
No live access or phone-quality claims follow from mocks or an APK build.

See [data-flow.md](data-flow.md) and [api-reference.md](api-reference.md).

## Full baseline recovery hold

Application runtime and tests are restored exactly to `05a37649`. All later fixes
are reverted after repeated phone A/B tests, including the partial rollback
that retained review fixes. The known queue/settlement/processing findings remain
open and are documented in `docs/muse-transcription.md`. Do not automatically
reintroduce these patches before isolated phone acceptance. Punctuation model
research is a separate proposed follow-up; no model or extra upload is enabled.
