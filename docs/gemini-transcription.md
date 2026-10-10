# Gemini Live Transcription Architecture

HeliBoard uses Google Gemini's Live API for voice input, with the dedicated
real-time speech-to-text model **`gemini-3.5-transcribe-live`**.

The pipeline is tuned for **accuracy over latency**. Dictated text is committed
straight into the user's editor, so a wrong word or a period in the wrong place
costs the user more than a second of waiting.

## Runtime flow

1. Require a usable default network before starting the mic. Android 23+ requires
   validated internet; API 21–22 can only report connectedness. Observe losses
   through capture, pause, and graceful drain.
2. Capture every PCM16 chunk, including quiet audio. Queue audio and
   `audioStreamEnd` in one FIFO. Wait for `setupComplete` before sending any audio.
3. Bound the local PCM queue to 960,000 bytes (30 seconds), and the OkHttp audio
   queue to 256 KiB of encoded JSON. Local backpressure retains the exact head;
   later audio and control frames cannot overtake it. Limit locally queued
   speech-boundary controls to 64 too. Overflow stops the session.
4. Only `serverContent.inputTranscription` creates editor segments.
   `interimInputTranscription` is provisional and never inserted, including after
   silence, EOF, a timeout, socket replacement, or failure. `modelTurn` is ignored.
5. Deliver finalized segments in FIFO order. Retain the head until `LatinIME`
   returns local dispatch/cleanup success, including paragraph cleanup. Android
   remote `InputConnection` booleans do not return the actual host editor's result.
   A false result or exception stops the session; never retry a potentially partial edit.
   Cleanup and punctuation correction select the original range, verify actual
   host selection positions and selected text, and replace it with one commit, so
   a rejected replacement does not first delete confirmed text.
   Temporarily close/reopen the host batch before readback so deferred editors
   apply edits. Prepare paragraph correction from the cache and verify matching
   original host text after flushing. Ignore delayed intermediate selection
   callbacks only when the host's current caret matches the expected voice caret.
6. On terminal failure, invalidate the recording and connection generations before
   stopping capture, clearing pending work, and notifying the editor. Already
   inserted text remains. If the stream reached readiness and audio was captured,
   insert one literal
   `[Dictation interrupted]` marker without transcript cleanup. If text is
   highlighted, collapse to the end of that range and verify the host caret first;
   skip marker insertion if that move cannot be verified. An editor failure
   suppresses the marker because the editor is no longer trustworthy.
   Rejection before `setupComplete` leaves the editor untouched and reports why
   dictation could not start; no PCM was submitted to that connection.
7. Restoring internet never resumes the failed recording. The user must tap the
   mic explicitly. A normal stop blocks restart until the outgoing stream closes.
   Interactive keyboard hiding, finished input/view callbacks, and host clears
   instead cancel all work immediately and remove the spinner, allowing a fresh
   mic tap without waiting for an old socket to drain.

## Important files

- `app/src/main/java/helium314/keyboard/latin/voice/GeminiTranscriptionClient.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceContextVocabulary.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/TranscriptSegment.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceRecorder.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceNetworkMonitor.kt`
- `app/src/main/java/helium314/keyboard/latin/settings/TranscriptionPreferences.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/TranscriptionScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/VoiceVocabularyScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/SetupAppScreen.kt`
- `app/src/main/java/helium314/keyboard/latin/LatinIME.java`
- `tools/gemini-live-smoke-test.py` — verifies the wire protocol against the real
  endpoint

## The `setup` payload

```json
{
  "setup": {
    "model": "models/gemini-3.5-transcribe-live",
    "generationConfig": { "responseModalities": ["TEXT"] },
    "inputAudioTranscription": {
      "languageCodes": ["en-US"],
      "mode": "SMART",
      "customVocabulary": ["HeliBoard", "Roentgen"]
    },
    "realtimeInputConfig": {
      "automaticActivityDetection": {
        "disabled": false,
        "startOfSpeechSensitivity": "START_SENSITIVITY_HIGH",
        "prefixPaddingMs": 300,
        "endOfSpeechSensitivity": "END_SENSITIVITY_LOW",
        "silenceDurationMs": 1500
      }
    },
    "systemInstruction": { "parts": [{ "text": "…dictation guidance…" }] }
  }
}
```

Two placement rules matter and are covered by tests:

- **`inputAudioTranscription` is a sibling of `generationConfig`, not a child.**
  Nesting it closes the socket with code 1007. Google's own Live Translate guide
  documents the broken shape, so this is easy to get wrong.
- **`responseModalities` must be `["TEXT"]`** for this model, and belongs inside
  `generationConfig`. `AUDIO` is for the conversational Live Agent models; only
  one modality per session is allowed.

## Setup-tier degradation

The transcribe model's documented feature list (speech biasing, language
detection, manual/hybrid VAD, smart transcription) is narrower than the shared
`setup` proto it accepts. An unsupported field is rejected with close code 1007,
which would leave voice input permanently dead.

So `GeminiTranscriptionClient` renders `setup` at one of four tiers and retries
one tier lower on 1007:

| Tier | Payload |
|---|---|
| `FULL` | dictation `systemInstruction` + tuned VAD + smart mode + vocabulary |
| `NO_SYSTEM_INSTRUCTION` | drops `systemInstruction` |
| `NO_REALTIME_CONFIG` | also drops `realtimeInputConfig` VAD tuning |
| `MINIMAL` | `model` + `responseModalities` + `languageCodes` only |

The tier that worked is cached in `negotiatedSetupTier` for the rest of the
process, so a schema mismatch costs one reconnect per app run rather than one per
session. A 1007 whose reason names an auth status (invalid key, permission
denied) is **not** retried — the schema is not the problem.

Run `tools/gemini-live-smoke-test.py --probe-setup` with a working key to see
which tiers the server actually accepts today.

## Accuracy levers

- **`mode: SMART`** (`PREF_GEMINI_TRANSCRIPTION_MODE`, default `SMART`) — Gemini
  removes filler words, resolves inline self-corrections ("Tuesday — actually
  Wednesday"), formats lists/numbers/dates, and polishes grammar and casing.
  `VERBATIM` gives the literal words instead.
- **`endOfSpeechSensitivity: END_SENSITIVITY_LOW`** plus
  **`silenceDurationMs`** (`PREF_GEMINI_END_OF_SPEECH_SILENCE_MS`, default
  **1500 ms**, range 400–5000). Google documents that short windows split one
  utterance into fragments and that the model then "loses cross-fragment context,
  resulting in lower transcription quality". The default therefore sits well above
  the API's own and trades latency for correct sentence structure.
- **`startOfSpeechSensitivity: START_SENSITIVITY_HIGH`** with
  **`prefixPaddingMs: 300`** — detect speech onset eagerly and keep prefix audio,
  so the first syllable is not clipped.
- **`customVocabulary`** — see below.
- **`languageCodes`** — an explicit BCP-47 hint from the keyboard subtype, because
  Google notes auto-detection misfires on short utterances, which is the normal
  case for keyboard dictation. `PREF_GEMINI_AUTO_DETECT_LANGUAGE` (default off)
  sends `[]` instead for multilingual users.
- **`systemInstruction`** — short, static dictation guidance. Live Transcription
  does not advertise system-instruction support, so this may be silently ignored;
  it lives in the top tier so a rejection degrades instead of breaking.

## Vocabulary from the editor

`VoiceContextVocabulary` is how dictated text is made to agree with what the user
has already typed. `LatinIME.buildVoiceContextText` supplies up to 4 000
characters before the caret through `VoiceInputManager.setPriorTextProvider`; the
vocabulary builder harvests the words worth biasing and sends **only those
words**, never the text itself.

Harvested, nearest the caret first:

- words with internal capitals (`iPhone`, `McDonald's`, `HeliBoard`)
- all-caps runs up to 10 characters (`API`, `CLI`)
- capitalized words **not** at the start of a sentence, which in English is where
  ordinary words are not capitalized

Rejected: common English words, anything containing a digit, and tokens glued
together by `@ / \ : _` (URLs, paths, emails, code identifiers).

Final list order, capped at 100 terms because Google notes accuracy is best
around 100 even though 1 000 are accepted:

1. the user's list (`PREF_GEMINI_CUSTOM_VOCABULARY`, one term per line)
2. the built-in product/technical terms
3. editor-harvested terms

Toggle the third source with `PREF_GEMINI_USE_EDITOR_CONTEXT` (default on).

**Verbatim editor text is deliberately not sent to the model.** Neither
`systemInstruction` nor a seeded `clientContent` history is a documented input for
this model, and feeding an already-typed paragraph to a generative model risks it
echoing that text back as transcription. `customVocabulary` is the documented
channel, and local pre/post-processing in `LatinIME` handles the casing and
punctuation continuity that vocabulary cannot.

## Turn finalization (Hybrid VAD)

Keep server VAD enabled with patient end detection. Local speech-stop silence,
mic pause, and explicit stop enqueue `audioStreamEnd` behind preceding audio.
Forward quiet PCM too: withholding it based on local RMS detection can drop soft
speech or the beginning of a new utterance. Audio following `audioStreamEnd`
reopens the stream, as documented. Local VAD controls finalization and auto-stop,
not which recorded samples the service receives. Each chunk carries raw-energy
speech evidence snapshotted before posting to the main looper. The speaking flag
includes the silence window and its smoothing tail, so it is not used to rearm
pending speech. Quiet PCM after a final does not create another missing-final
deadline.

There is no stale-interim insertion timer. An interim is never evidence that all
speech was received. On explicit stop, stop/join the recorder, process its already
posted tail callbacks, enqueue EOF, and read for eight seconds before initiating
WebSocket close. A nonempty transport queue at that deadline is failure. The
manager also bounds the complete finish/close wait to 15 seconds. A capture thread still alive after
two seconds reports terminal failure before the EOF barrier, blocks native capture
reuse, and keeps its original recording callback for all late reads. Pause/resume
changes the capture phase so an interrupted native read cannot falsely abort
resumed recording. Missing finals after an explicit mic stop can produce an
interruption marker; a normal silence auto-stop does not.

These are application deadlines, not Google latency guarantees. A healthy slow
final can be interrupted; accuracy takes priority over inserting a provisional
hypothesis.

## Transcript assembly

The Live API has emitted finalized input transcriptions both as per-utterance
deltas and as text that grows on each message, and the semantics changed between
model generations. `TranscriptAccumulator` compares each transcript against the
previous one, which covers both:

- text that extends the previous message contributes only its suffix
- unrelated text contributes all of itself
- an identical repeat within the same turn contributes nothing
- `turnComplete` resets the comparison, so a phrase genuinely repeated in a new
  turn is still inserted

`attachesToPrevious` is set when a segment starts with punctuation that hugs the
previous word (`. , ! ? : ; ) ] } %`), or when a growing transcript resumes
mid-word (`head` then `heading` yields `ing`, not `head ing`).

`serverContent.modelTurn` is ignored, so a generated response can never leak into
the editor.

## Session lifecycle and progress deadlines

A recording owns one connection. There are no transport reconnects, automatic
resumption, or automatic socket rotations. An explicit new recording builds fresh
vocabulary and does not replay audio or send previous paragraphs as history.

The sole retry is setup-schema negotiation (`FULL` → `MINIMAL`) before
`setupComplete`; no audio has been sent yet. All tiers share the original
12-second connection deadline, so repeated schema rejection cannot keep recording
an ever-growing startup buffer.

On `goAway`, or after nine minutes, stop capture and drain the existing connection,
then require a new mic tap. This gives up seamless sessions longer than nine
minutes to avoid an unprovable transcript boundary at socket replacement.

Deadlines are anchored rather than reset by each PCM chunk:

- Oldest locally queued audio: 30 seconds to enter the bounded transport queue.
- Active local speech or pending recognized words with no server response:
  30 seconds. A response signals liveness; interims and `turnComplete` do not
  confirm the speech. Quiet audio with only raw RMS spikes does not require
  transcription responses.
- Pending recognized words with no accepted final: 30 seconds.
  Start this wait at a submitted `audioStreamEnd`, not when speech first becomes
  pending: [Google documents](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe)
  that finals arrive on speech completion while interims arrive during speech.
  A fixed deadline from onset interrupts healthy long utterances. Repeated
  controls/interims cannot postpone the boundary's wait. A new speech onset
  clears that final wait until the next boundary. Controls retain their captured
  speech epoch so a delayed older boundary cannot start a final deadline for a
  newer utterance. The response watchdog and
  oldest-audio deadlines still apply. Raw volume evidence alone does not arm a
  missing-final wait: breathing or room noise may cross that threshold.
  A final cannot clear pending speech while annotated speech frames are still
  queued locally: it may describe an earlier utterance. Pending speech remains until a
  later final arrives after speech frames drain; draining alone is not confirmation.
  Queued quiet PCM and controls do not block that final. The sender counts encoded
  bytes after the last speech frame; FIFO queue size distinguishes queued speech
  from its quiet/control suffix. A duplicate authoritative final also advances
  progress, even when transcript assembly suppresses the duplicate editor write.
- EOF: cancel the earlier response/final watchdogs, read finals for eight seconds
  before initiating close, and allow 15 seconds for the full close wait.

The configured local silence timeout is a normal stop reason. It drains the same
connection and accepts late authoritative finals, then completes without a marker
even if raw noise or a provisional hypothesis remains unconfirmed. Interims are
never promoted into editor text. If silence and a speech watchdog expire together,
the intentional silence stop takes precedence. Real microphone, network, upload,
protocol, queue, and drain failures still report an interruption. The auto-stop
timer continues to use adaptive local microphone activity rather than recognized
words; that timing policy is separate from failure detection.

Network loss, send/finalize failure, protocol error, unexpected close (including
remote 1000), or failure during graceful drain is terminal. OkHttp protocol pings
remain at 20 seconds as another transport failure signal.

### Keyboard hiding and host clears

Hiding the keyboard while the screen is interactive cancels capture, the socket,
all queues, and pending timers immediately. `onFinishInputView` and `onFinishInput`
invalidate voice work before deferred lifecycle housekeeping. Reopening the
keyboard does not inherit the old spinner or wait for its eight-second finalization
grace. Screen-off hiding alone preserves recording, as before. Explicit mic stop
still drains.

At recording start, subscribe to [host text changes](https://developer.android.com/reference/android/view/inputmethod/InputConnection#getExtractedText(android.view.inputmethod.ExtractedTextRequest,%20int))
with a dedicated extracted-text monitor token unless the framework's fullscreen
extract view is visible; that view retains its own monitor. Check actual text
before/after the caret and the selection on notifications, selection updates (including unchanged selection),
editor restart, and before voice insertion. A verified nonempty-to-empty
transition cancels all voice work without a marker. Null queries mean unavailable,
not empty; highlighted text is not a clear. Remember successful insertion as
nonempty-field evidence too, including when the field started empty. Re-read the
host rather than treating belated or partial extracted updates as current text. Editors that omit change
notifications are checked before a late final can refill the field; such editors
may not stop capture immediately if they send no notification or restart at all.

Android does not expose a universal event identifying a form submission made
through a host application's own button. A clear proves only that the field became
empty, so it stops dictation without automatically hiding the keyboard.

### What this can and cannot prove

[OkHttp 4.12's WebSocket contract](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/WebSocket.kt)
says `send(true)` is queue acceptance; queued messages may be lost on cancellation,
and `queueSize()` excludes OS/intermediary buffers. Neither proves server receipt.
[Google's Live API reference](https://ai.google.dev/api/live) sends input transcripts
independently of other server messages, with no guaranteed ordering relative to
those messages. `turnComplete` is not a watermark for submitted PCM.

Android's [remote InputConnection invoker](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/inputmethodservice/IRemoteInputConnectionInvoker.java)
returns success when an operation is dispatched without a `RemoteException`; the
[editor-side implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/inputmethod/RemoteInputConnectionImpl.java)
discards the host's commit/selection return values. Before cleanup/punctuation
replacement, query actual extracted selection positions (including `startOffset`)
and selected text. If these are unavailable or differ from the intended range,
stop without committing the replacement and restore the original caret best-effort.
Compose's [buffered input connection](https://android.googlesource.com/platform/frameworks/support/+/08274d3734d2e635eba3944c8def835b366ee65d/compose/foundation/foundation/src/androidMain/kotlin/androidx/compose/foundation/text/input/internal/StatelessInputConnection.android.kt)
applies queued edits only when its batch depth returns to zero. Verification
therefore runs with the host batch temporarily closed. The wrapper batch remains
balanced, and the voice selection guard checks the actual current caret before
treating an intermediate callback as a user move.
The marker always verifies the host caret, including when the cached selection
was collapsed, because a new highlight callback may still be pending. If needed,
collapse the known selection before verification. These
checks protect placement; they cannot prove semantic completeness or detect every
filtered/truncated insertion by a host editor.

This implementation enforces local FIFO/dispatch checks and stops on detected
uncertainty. It cannot certify every spoken word or detect an omission the service
silently makes while continuing to emit plausible finals. Local VAD and finals
are progress signals, not per-sample acknowledgments. The interruption marker
means speech may be missing; it does not reconstruct lost words or estimate gap
length. Failures after any captured audio are marked conservatively, even if that
audio might have been silence or already transcribed.

[Google's transcription guide](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe)
distinguishes speculative interim hypotheses from finalized input transcription.
That is why a timeout never promotes an interim into editor text.

[Android's connectivity guidance](https://developer.android.com/develop/connectivity/network-ops/reading-network-state)
informs the default-route observer. API 24+ uses default-network callbacks marshaled
to the main looper; API 21–23 uses a dynamic connectivity receiver. Generations
invalidate queued observations when the monitor stops. A validated replacement
route is allowed when its availability precedes loss of the old route; the socket
can still fail independently, which remains terminal.

## Configuration

- API key: `Settings.PREF_GEMINI_API_KEY`. The user pastes it in Settings →
  Transcription or Settings → Setup this app. Preference keys from the providers
  used before Gemini (Soniox, Speechmatics, Deepgram) are erased by
  `TranscriptionPreferences.migrateLegacyProviderPrefs`, which carries only the
  user's own vocabulary list across.
- Gemini session settings:
  - `PREF_GEMINI_TRANSCRIPTION_MODE` (`SMART` / `VERBATIM`, default `SMART`)
  - `PREF_GEMINI_END_OF_SPEECH_SILENCE_MS` (int, 400–5000, default 1500)
  - `PREF_GEMINI_AUTO_DETECT_LANGUAGE` (boolean, default `false`)
  - `PREF_GEMINI_USE_EDITOR_CONTEXT` (boolean, default `true`)
  - `PREF_GEMINI_CUSTOM_VOCABULARY` (string, one term per line)
- Local silence settings:
  - `PREF_VOICE_CHUNK_SILENCE_SECONDS` — local pause that triggers
    `audioStreamEnd`
  - `PREF_VOICE_SILENCE_THRESHOLD` — RMS threshold for the local detector
  - `PREF_VOICE_AUTO_STOP_SILENCE_SECONDS` — longer pause that stops recording

The model id, endpoint, VAD sensitivities, `prefixPaddingMs`, the system
instruction and the 100-term vocabulary cap are hardcoded in
`GeminiTranscriptionClient`, not exposed as preferences.

## Local pre/post-processing

- `LatinIME.prepareVoiceTranscriptionText()` handles separator-space insertion,
  mid-sentence leading-casing correction, and stripping a trailing `.`/`!`/`?`
  when dictating before lowercase text.
- `LatinIME.runTranscriptPostProcessing()` runs `TranscriptPostProcessor` over the
  current paragraph after commit, for spoken commands such as "Comma." or "New
  paragraph." and for leftover filler fragments. It also removes periods/commas
  directly before `!`, `?`, `,`, `:`, or `;`, plus commas before `.`, throughout that
  paragraph after command conversion.
- `TranscriptPostProcessor` owns the shared punctuation-correction set and
  normalization. Each incoming segment is normalized before insertion, covering
  every occurrence even across line breaks. A segment beginning with a correction
  mark replaces a redundant period or comma immediately before the editor caret
  through the verified replacement path, including segments containing additional
  text. A short editor suffix plus the segment's first character uses the same
  normalizer as complete transcripts. Mixed
  runs of periods/commas before these marks are removed in one pass; ordinary
  periods/commas, standalone ellipses, closing quotes/brackets, and marks separated
  by whitespace are preserved. Early normalization keeps the comma on `um,`/`uh,`
  until paragraph-level filler removal can recognize and remove the whole fragment.
  Interruption markers bypass this cleanup.
- Silence-driven automatic paragraph insertion stays disabled, because inserting
  line breaks on host-app silence caused form submissions and other side effects.

## Authentication notes

The key travels in the WebSocket query string (`?key=…`), so
`Log.redactVoiceDiagnosticMessage` strips `key=` from URLs as well as `api_key=`
from JSON before diagnostics are exported.

Google recommends **ephemeral tokens** (`POST /v1beta/auth_tokens`, passed as
`?access_token=` to `BidiGenerateContentConstrained`) rather than a raw API key in
a client app. HeliBoard uses a user-supplied key because there is no HeliBoard
backend to mint tokens: the key belongs to the user's own Google account, is
entered by them, and never leaves the device except to Google. Adding ephemeral
tokens would require a server component.

## Verifying against the real API

Unit and lifecycle tests cover the wire format and the client state machine
(`GeminiTranscriptionClientTest`, `GeminiTranscriptionClientStreamTest`,
`VoiceContextVocabularyTest`), including the 1007 tier fallback against a local
WebSocket server. They cannot confirm what Google's server does with each field.

For that, use the smoke test with a real key:

```bash
export GEMINI_API_KEY=...
tools/gemini-live-smoke-test.py --probe-setup            # which setup tiers are accepted
tools/gemini-live-smoke-test.py --audio speech.wav       # real transcripts
```

The WAV must be 16-bit PCM, 16 kHz, mono:
`ffmpeg -i input.m4a -ar 16000 -ac 1 -c:a pcm_s16le speech.wav`

## Integrity regression and device checks

Startup rejection is distinct from interruption of a usable stream. The service
can reject setup with a 1011 close whose detail says prepayment credits are depleted,
without a gRPC status name. Match that detail before generic close/quota wording;
HTTP-429 handshake bodies and in-band `RESOURCE_EXHAUSTED` errors can carry the
same billing problem. Check the API key's project in AI Studio, as described in
[Google's billing guidance](https://ai.google.dev/gemini-api/docs/billing#prepay).
The app cannot restore a rejected project's billing access.

Both About → Save log and Transcription → Voice diagnostics include recent voice
history, application/build and Android/device information. A separate 500-line
voice ring prevents keyboard geometry/key traces from evicting the failure. General
export puts voice history first, then up to 500 other app warnings/errors with
consecutive repeats collapsed and 200 recent native Android logcat lines excluding
duplicated app tags. Transcript payloads are not logged by insertion; exported
legacy transcript lines and API-key patterns are redacted.

Session diagnostics record phase, elapsed time, captured and accepted PCM counts,
raw/socket queue sizes, capture-time speech counts, final progress with queued
speech flags, accepted finals and last-response age. Voice selection diagnostics
record expected/actual positions, selected lengths, and verification outcome; no
editor/transcript contents are logged. Transport close records retain the code,
readiness, requested-finish state and sanitized service
detail, so an early billing rejection cannot be confused with a stalled upload.

The voice manager tests cover loss/reconnect callbacks, explicit restart, ordered
PCM/control delivery, queue overflow, oldest-audio and progress deadlines, editor
rejection/exception, setup fallback timeout, paused failures, and EOF/restart guards.
The bounded sender tests exercise the production queue limit and false sends with
a controllable socket. Network tests cover default-route validation, handoff,
stale callbacks, and API-21 receiver cleanup. Local WebSocket tests verify actual
frames, schema fallback, authoritative-only insertion, and terminal close/error
handling. InputLogic tests verify editor return values, failed cleanup, balanced
batch edits, and literal marker insertion that preserves selected text. They also
model a remotely dispatched selection the host ignores, unavailable selection
queries, mismatched selected content, and partial extracted-text offsets. Deferred
editor tests apply commits/selections only after batch closure, and check that
delayed cleanup callbacks preserve dictation while real user cursor moves and
cleared fields cancel it. Marker tests also cover a newly highlighted host range
whose callback has not yet reached the IME.

After checking out the branch in the device workspace: dictate a confirmed prefix,
disable both Wi-Fi and mobile data while continuing to speak, and restore them.
The mic must stop, the prefix must remain, a single interruption marker must show,
and no automatic suffix may appear. Deliberately tap the mic for a new recording.
Also test offline start, radio loss while paused and while stopping, quiet speech
after pauses, a slow final, host-editor rejection, and the nine-minute limit.

For selection and silence regressions, use the fullapp editor and ordinary notes
and messaging fields:

1. Enter `hello cruel world`, highlight `cruel`, start dictation, and disable both
   Wi-Fi and mobile data before a final appears. All original words must remain.
   A failure after readiness inserts a marker after `cruel` only if caret collapse
   can be verified; startup failure or unverifiable selection leaves text intact.
2. Dictate a short sentence, wait for the final, remain silent for five seconds,
   then stop. Repeat with pause/resume and with the silence auto-stop. Successful
   completion must not add a marker merely because quiet PCM followed the final.
   Also remain silent until auto-stop without speaking any words, and repeat
   after a final with normal breathing/brief room noise. Normal auto-stop must
   leave no marker and allow a new recording after the finalization grace.
3. Dictate a sentence, then a separate punctuation command such as `exclamation
   point`. Cleanup must replace the intended range once, without duplicating the
   paragraph. An unverifiable range stops dictation and preserves the accepted
   original text instead of appending a replacement.
4. In the fullapp editor, insert dictation in the middle of existing text, then
   use a spoken punctuation command that triggers cleanup. Dictation should keep
   running, preserve the text after the caret, and insert later segments at the
   final corrected caret. Moving the caret yourself into another word must still
   cancel dictation.
5. Speak continuously for more than 30 seconds, then pause and wait for the final.
   Repeat with a 20-second thinking pause before continuing. Live interims during
   speech must not trigger the missing-final timeout. A paused turn with pending
   recognized words but no final still times out; a completely unresponsive
   stream during active speech still stops.
6. Hide and immediately reopen the keyboard during recording and during drain.
   The spinner must disappear, a new mic tap must start, and no old words/marker
   may arrive. Repeat a host clear/send while recording or paused, including when
   the caret was already at zero. A cleared field must not be refilled by a late
   final. An unchanged field with selected text or unavailable queries must not
   be mistaken for an empty field.

Save About → Save log immediately after a failure. Report the test number, app,
whether the words remained, whether a marker appeared, and the approximate time.
The export retains `VOICE selection verification`, `speech pending`, and
`final progress` lines to identify the host-selection and speech-queue decisions,
plus finalization controls and separate response/interim/final/boundary ages.
