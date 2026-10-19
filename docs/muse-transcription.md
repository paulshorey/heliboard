# Muse Voice Transcribe dictation

This branch uses Meta `muse-voice-transcribe-1.0`, with a first-speaker filter.
Accuracy takes priority over latency. Only completed, attributed turns enter the
host editor in default protected mode. Opt-in Responsive dictation inserts completed
turns from all speakers using ENDPOINTING. An arbitrary two-second output deadline would commit speculation;
there is no such deadline and no client sentence-finalization command.

## Setup and privacy

Create an individual account at https://dev.meta.ai/, add billing and create an
API key. Settings → Transcription and the setup screen accept your own key, masked
as a password. The key is stored through AtomicFile in Android noBackupFilesDir,
inside the app sandbox, outside preferences/backup. Clearing the field deletes it.
It is not encrypted separately from Android device storage. No shared key is built
into the APK. A distributed shared-credential product needs a server proxy.

Existing provider credentials remain untouched to permit comparison-build swaps.
User vocabulary is copied once from Gemini (or Soniox if absent); only Muse's list
is subsequently changed. No credentials migrate between providers.

All microphone audio while recording, including surrounding voices and silence,
is sent to Meta. Filtering prevents insertion; it does not prevent upload. Standard
mode is documented as excluding model training, but is not ZDR. ZDR needs separate
organizational enablement. Do not assume personal-key retention is zero.

## Wire protocol and speaker selection

- Endpoint: `wss://api.meta.ai/v1/asr/realtime`.
- First text frame: `authorization.accessToken = "Bearer <key>"`, model,
  `audioEncoding`, `mode: DIARIZATION` by default or explicit `ENDPOINTING`, `partialMode: CUMULATIVE`,
  `emitAudioProgress: true`, optional `keywords` and `languageBias`.
- Wait for the bare `{sessionId: ...}` acknowledgement. Then send raw binary PCM16
  little-endian mono, not base64 or a WAV container.
- Prefer 24 kHz; initialize 16 kHz capture if 24 kHz cannot initialize. Keep the
  selected format fixed and advertise it accurately in the handshake.
- `speechStart` creates a turn. `transcript` updates provisional evidence only,
  even with `final: true`. `speechEnd` is a boundary. `speechComplete` contains the
  entire completed turn, including provider punctuation/capitalization.
- A live session can flush an empty final `transcript` after its last completed
  turn at EOF. Ignore that wordless flush; nonempty or nonfinal transcripts without
  an unsettled active turn remain protocol errors.
- `speaker` labels the preceding active span and has no turnId. Associate it with
  the active speechStart. Keep completed state keyed by turnId, using observed
  start order, not numeric IDs or completion arrival order.
- Require speechEnd as well as completed text and (in DIARIZATION) attribution before release; an
  early completion cannot overtake a later conflicting speaker label.
- In DIARIZATION the earliest nonempty attributed turn selects the speaker. Other labels are
  discarded before insertion but still settle processing. Labels persist through
  pause/resume and reset at the next explicit recording.
- Repeated same-label events are harmless. Different labels in one turn cannot be
  sliced safely without word-level attribution: hold and fail rather than guess.
  Missing attribution blocks later text until resolved; close/timeout reports it.
- Deduplicate by session/turn identity, not transcript contents. Repeating a whole
  sentence in a new turn must type it again.
- Ended turns without words retain their place for the same 30-second final wait,
  since a completed transcript can arrive without partials. At that deadline, or
  on normal server closure when no more results can arrive, retire wordless turns
  without choosing a speaker and release later ready text. Unexpected nonempty
  text after such expiry fails visibly rather than being dropped or reordered.

Diarization covers non-overlapping speech. Radio/TV or another person speaking
first may be selected, and overlapping/similar voices can be misclassified. These
labels are not verified identity, enrollment, or target-speaker audio isolation.
Speak first after tapping the mic; stop/start to reset a wrong selection.

## Audio, pause and lifecycle

The recorder uses ~100 ms chunks and forwards quiet PCM too. Its sample-rate-sized
FIFO holds at most 30 seconds; overflow/upload stall fails instead of dropping or
reordering data. The sender paces against a monotonic clock with one capture frame
of headroom. Submitted audio leads reported processing by at most two seconds,
below Meta's five-second policy limit; the socket queue is also bounded. Progress
is evidence of processing, not a receipt for editor insertion or individual words.
Track the last submitted speech-frame byte offset against provider processing as
well as socket backlog. An empty socket queue alone cannot acknowledge a newer
speech suffix; normal closure with unprocessed submitted speech is an interruption.

Pause stops native microphone capture. A main-looper barrier orders its posted
tail before generated zero PCM, emitted on an absolute 100 ms schedule. Resume
cancels the silence generator and resumes the same native capture/session. Paused
time can be billed. No captured audio is synthesized over a missing network span.

Local RMS VAD only controls activity and the configurable silence auto-stop
(default 30 seconds). It does not finalize turns or discard quiet audio. Each
provider-ended recognized turn has a fixed 30-second final/attribution deadline;
interims/progress cannot postpone it. Long speech without an endpoint can continue
while the response watchdog sees progress. Raw noise alone requires no final.
Empty-ended turns expire harmlessly at the final deadline; recognized unresolved
turns still fail. A completion received before speechEnd does not start that wait.

One recording owns one connection. At 55 minutes stop capture and drain before the
60-minute service cap. Explicit stop joins capture, accepts its already-posted
tail, drains paced audio, sends `{"type":"endStream"}`, then reads until close 1000.
One 60-second overall stop budget includes capture/setup/upload/EOF/close. Restart
is blocked until completion, explicit cancellation, or failure. No automatic
reconnect, replay, or session rotation is attempted; there is no resume token.

Keyboard hiding while interactive, editor loss/clear, and input finish cancel
immediately, invalidate callbacks, and suppress interruption markers. Screen-off
hiding preserves capture. Actual failures preserve inserted finals and attempt
one literal `[Dictation interrupted]` after readiness/capture. Startup failure
leaves the editor untouched. Editor rejection/exception stops later writes and
suppresses markers. Android InputConnection booleans confirm dispatch, not remote
host acceptance; verified selection/readback protects replacement operations.

## Settings and text shaping

Muse has no documented smart/verbatim toggle, programmable endpoint pause,
confidence scores, word timestamps, or general transcript rewriting input.
Language bias uses supported language names derived from the keyboard subtype;
auto-detection omits it. Keywords combine user terms, built-in terms, and optional
proper nouns harvested near the caret. Full editor paragraphs are never sent.
The 100-term cap is app policy, not a claimed provider maximum.

Preserve completed wording/casing. Keep explicit sentence-form spoken punctuation
and paragraph commands, spacing at the caret, and redundant punctuation cleanup.
Do not run the former filler/disfluency deletion or mid-sentence casing/trailing
punctuation heuristics against Muse output. Commands use verified one-commit
paragraph replacement through InputConnection, never direct extract/fullapp edits.

## Validation and manual acceptance

Offline checks exercise turn selection/order, provisional revisions, missing or
conflicting labels, repeats, pacing/processing backlog, stale events, EOF/drain,
credentials, network/microphone lifecycle, editor rejection and cancellation.
They cannot establish actual Muse access or recognition/diarization quality.

With a key, run the opt-in probe (optional Python `websockets` package):

```sh
python3 tools/muse-streaming-smoke-test.py --audio /private/path/speech.wav --language English
```

Provide MUSE_API_KEY or MODEL_API_KEY through the environment. Never put it in
shell arguments or source. The probe prints lengths/counts/timing, not transcript
payloads unless `--show-transcripts` is explicitly requested. Nothing is saved.
The probe tests the wire protocol; the phone tests the Android implementation.

Compare identical human-labeled recordings against Gemini and MAI. Measure user
word errors, punctuation/capitalization, guest words inserted, user words falsely
suppressed, first completed-text delay, and stop/drain delay. Include names, jargon,
self-corrections, thinking pauses, long continuous speech, sequential speakers,
TV/radio, and overlapping voices. Report overlap separately from sequential speech.
On the phone also test 16 kHz fallback, pause/resume while backpressured, immediate
stop/restart, airplane mode/radio handoff, keyboard hiding, host clears without
cursor motion, highlighted text and deferred host edits. Keep PR draft until live
access and user phone/quality acceptance are complete.

## Authoritative references and known sample traps

- https://dev.meta.ai/docs/speech-to-text
- https://dev.meta.ai/docs/api-reference/voice/realtime
- https://dev.meta.ai/docs/api-reference/voice/schemas
- https://github.com/meta-models/meta-model-cookbook/tree/main/06_muse_voice/01_voice_api_fundamentals
- https://dev.meta.ai/docs/pricing-rate-limits
- https://dev.meta.ai/docs/authentication
- https://dev.meta.ai/help/policies-and-privacy/zero-data-retention

The reference requires a Bearer prefix in the JSON handshake; one cookbook helper
omits it. Use the reference and verify live. The microphone sample drops its oldest
frame on overflow; HeliBoard fails visibly instead. Prose describes one label per
turn, while an example repeats A; identical repeated labels are accepted, mixed
labels are unresolved. These differences are explicit live-probe cases.

## Responsive dictation and diagnosing delayed punctuation

Meta documents that DIARIZATION is not tuned for low-latency speech endpoints.
Punctuation can improve in speechComplete after speechEnd. A grammatical sentence
boundary does not necessarily end the model's turn. The app never commits an
intermediate hypothesis, even if it already contains punctuation.

Settings → Transcription:

- **Responsive dictation (all voices)** is off by default. Enable it to try
  experimental ENDPOINTING, which may finish turns sooner but can punctuate less
  accurately. It inserts all completed turns,
  including other people. It cannot retain the first-speaker filter without
  speaker labels. Turn it off to restore protected DIARIZATION on the next mic
  start. Existing settings and keys are preserved; pause/resume never changes mode.
- **Measure transcription delays** is off by default. Enable it before starting
  a recording, reproduce the delay, then open Voice diagnostics. It adds only
  numeric event timing, counts and queue measurements. No audio, words, speaker
  labels, API keys or provider session identifiers enter these diagnostics.
  Disable it for subsequent recordings when finished. Existing logs are bounded.

The timing timeline uses local monotonic timestamps, not wall-clock time:

| Field / phase | Meaning |
| --- | --- |
| audio maxCaptureToSendMs | Largest recorder-read-to-socket-accept delay so far, including main-looper waiting; paused silence has its own creation timestamp |
| event elapsedMs | Receipt time on the socket thread, relative to opening the client |
| event handlerQueueMs | Socket receipt to handling/measurement on the main looper |
| progress processingLeadMs / socketBytes | Submitted audio minus reported processing, and local socket backlog; sampled once per second |
| firstPartialMs / firstPunctuationMs | First nonempty hypothesis / first hypothesis containing `.`, `!` or `?`; punctuation counts are not verified sentence boundaries |
| speechEndMs / completeMs / speakerMs | Local receipt times of provider boundary, completed text and last attribution event |
| endToFinalMs | Completion receipt minus boundary receipt; can be negative for an early completion |
| finalToReleaseMs | Completed-text receipt to ordered assembly release, including handler waiting and prior unresolved turns |
| editor queueMs / dispatchMs | Release-to-local-insertion wait and insertion/cleanup duration; Android dispatch is not proof of host acceptance |

A late speechEnd points to provider turn detection; a timely speechEnd followed
by a late completion points to provider completion work. A timely completion but
late release points to handler/ordering/attribution. Timely release but late
insertion points to the local editor path. audioProcessedMs is processing progress,
not an exact acoustic endpoint. Annotate actual speech/sentence endings in the WAV
when measuring post-speech latency. First-final time includes time spent speaking.

### Identical-recording comparison

Use short, non-sensitive human recordings that reproduce fast continuous speech,
natural sentence breaks, questions, thinking pauses, and quiet speech. Include
quiet and ordinary room noise, plus a separate A/B/A recording to validate the
protected filter. Replay identical PCM so microphone differences do not confound
mode comparisons. The desktop tool tests provider behavior; the phone tests the
Android capture/handler/editor path.

```sh
python3 tools/muse-streaming-smoke-test.py --audio /private/fast-speech.wav \
  --env-file .env --language English --compare-modes --timings
```

This sends the same audio twice (two billable sessions). `--mode DIARIZATION` or
`--mode ENDPOINTING` runs one mode. Environment keys override literal env-file
assignments; files are never executed or expanded. Default output is metadata
only. `--show-transcripts` explicitly prints private text for manual accuracy
review. No recordings/reports are saved by the tool; redirect metadata output to
a private local file if desired. Event receipt/release timestamps and punctuation
counts help separate partial availability from completed-text delivery. Compare
word and punctuation accuracy alongside latency; one synthetic pass cannot prove
human performance. ENDPOINTING deliberately has no guest-speaker rejection.

An October 19, 2026 live probe replayed the same 17.324-second synthetic A/B/A
recording in both modes. DIARIZATION delivered its first completed turn at
6.561 seconds and selected two turns while filtering the guest turn. ENDPOINTING
delivered its first completed turn at 5.739 seconds and selected all three. The
three completed turns contained 2/1/2 sentence punctuation marks in DIARIZATION
and 0/0/0 in ENDPOINTING. Both drained to normal closure (225/149 ms after EOF).
This is protocol/comparison evidence, not a human accuracy benchmark. It supports
keeping protected DIARIZATION as the default and labeling ENDPOINTING experimental.

Keep all captured PCM and real-time pacing. Do not insert fake pauses, commit
stable-looking partials, or reset sessions per sentence: these risk quiet-word
loss, revisions, guest insertion and loss of speaker identity. A provisional
preview is deferred because attribution may arrive late and it would not make
final punctuation arrive sooner. No additional rewriting model is introduced.

### Provider support packet (prepare locally; submit separately)

Record app build/model/mode, sample rate, relevant numeric timeline, actual pause
lengths, reproduction steps and the quiet/noisy comparison. Include a private
recording/transcript or session identifier only if you choose to share it with
Meta through its support channel. Ask whether DIARIZATION supports earlier
speechComplete, an endpoint sensitivity control, or stable attributed partials.
Do not include keys or general editor contents. The app and probe do not send
support reports automatically.
