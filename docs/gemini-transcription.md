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
   mic explicitly. A mic tap during normal stop queues one restart after the outgoing stream closes.
   Interactive keyboard hiding, finished input/view callbacks, and host clears
   instead cancel all work immediately and remove the spinner, allowing a fresh
   mic tap without waiting for an old socket to drain.

## Important files

- `app/src/main/java/helium314/keyboard/latin/voice/GeminiTranscriptionClient.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceContextVocabulary.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/TranscriptSegment.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceRecorder.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceMicrophoneRouter.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceNetworkMonitor.kt`
- `app/src/main/java/helium314/keyboard/latin/settings/TranscriptionPreferences.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/TranscriptionScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/VoiceVocabularyScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/SetupAppScreen.kt`
- `app/src/main/java/helium314/keyboard/latin/LatinIME.java`
- `tools/gemini-live-smoke-test.py` — verifies the wire protocol against the real
  endpoint

## Microphone selection

The first setting under **Transcription → Microphone** persists one of three policies:

- **Automatic** (default): request a connected BLE or SCO headset
  microphone when available. Without Bluetooth input, Android selects the input,
  including a wired or USB microphone when connected.
- **Always use phone microphone**: explicitly request the phone's microphone even
  when Bluetooth, wired or USB input is connected. If the built-in input is absent
  or Android refuses the route, report an error instead of using an external mic.
- **Always use Bluetooth headset microphone**: require a connected BLE/SCO headset
  microphone. If none is available, reject Start/Resume with a reconnect/change-setting
  message. A headset connected only for playback is not a microphone. Never fall
  back to the phone in this policy.

The choice is read at every start/resume; it never stores transient device IDs or
names. Android 6+ supports explicit input preference and actual-route queries.
Android 5 retains its platform route and shows a compatibility explanation in settings.

`VoiceMicrophoneRouter` calls `AudioRecord.setPreferredDevice` before capture.
For Bluetooth it also owns an `AudioManager.setCommunicationDevice` request on
Android 12+, selecting an **output** from `availableCommunicationDevices` (Android
selects its matching input). Android 6–11 uses `startBluetoothSco`/`stopBluetoothSco`.
Legacy SCO requires mono 8 kHz capture, per Android's
[`startBluetoothSco` contract](https://developer.android.com/reference/android/media/AudioManager#startBluetoothSco()).
Resolve the route before creating the recorder. `VoicePcmResampler` performs
streaming linear 2x interpolation into the unchanged 16 kHz upload format, with
no gain or noise gate. A start/resume selecting another rate recreates the recorder;
old-phase reads are discarded. Route/start logs include `captureRate`/`uploadRate`.
Other capture routes remain at 16 kHz.
Only the normal `MODIFY_AUDIO_SETTINGS` permission is added: there is no pairing,
scanning or direct Bluetooth-device access. No global audio mode is changed.

Android's preferred input is only a request. Before delivering each PCM read, the
router checks the actual `AudioRecord.getRoutedDevice()` type. During initial
activation, reads from another/unknown route are withheld for at most 8 seconds;
Bluetooth displays connecting and ready messages, so wait for ready before speaking.
The user's silence auto-stop window starts again at microphone readiness; its
startup guard allows route activation to finish even with a 5-second silence setting.
After readiness, route loss or a change to another input type stops dictation and
uses the existing interruption handling. Restart explicitly after disconnecting a
headset to choose the current automatic fallback. Quiet PCM from the verified
input is still forwarded without a loudness gate.

Pause releases the phase's Bluetooth request. Resume reapplies the current saved
preference and verifies the route again. Stop, cancellation and setup/resume failure
also release owned requests; platform routing is not reset when this app owns none.
Route diagnostics contain types only, never names, addresses or device IDs.

Research: FUTO exposes a [Bluetooth Microphone preference](https://docs.keyboard.futo.tech/settings/voiceinput).
Android documents [preferred recording inputs](https://developer.android.com/develop/connectivity/bluetooth/ble-audio/audio-recording)
and [communication-device selection and cleanup](https://developer.android.com/reference/android/media/AudioManager#setCommunicationDevice(android.media.AudioDeviceInfo)).

Phone acceptance remains separate from JVM tests: connect a Bluetooth headset,
choose **Always use phone microphone**, and speak near the phone with the headset
distant; reverse the positions under **Always use Bluetooth headset microphone**
after the ready message. Repeat after an app restart and pause/resume, changing
policies while paused. Check Automatic with no headset and with wired/USB input.
Start Bluetooth-only with no connected headset: expect an error, no phone fallback.
Disconnect during capture: expect visible stop and an explicit restart. Confirm
normal media routing returns after stop/cancel.
Test Bluetooth Classic and BLE on actual Android versions; Robolectric cannot
prove a manufacturer's audio policy or headset activation behavior.

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
        "silenceDurationMs": 1000
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
  **1000 ms**, range 400–5000). Google documents that short windows split one
  utterance into fragments and that the model then "loses cross-fragment context,
  resulting in lower transcription quality". The one-second default follows owner
  phone testing; increase it for longer pauses within a phrase. Saved timing
  choices are preserved rather than migrated to the new defaults.
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
2. editor-harvested terms

No vocabulary terms ship with the app. Toggle editor-derived terms with
`PREF_GEMINI_USE_EDITOR_CONTEXT` (default on). If both sources are empty, the
setup payload omits `customVocabulary`.

**Verbatim editor text is deliberately not sent to the model.** Neither
`systemInstruction` nor a seeded `clientContent` history is a documented input for
this model, and feeding an already-typed paragraph to a generative model risks it
echoing that text back as transcription. `customVocabulary` is the documented
channel, and local pre/post-processing in `LatinIME` handles the casing and
punctuation continuity that vocabulary cannot.

## Turn finalization (Hybrid VAD)

Keep server VAD enabled with patient end detection and prefix padding. Automatic
phrase endings come from Gemini, as documented in the [VAD strategies](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe#voice-activity-detection-vad-strategies).
Local RMS silence only starts the auto-stop timer; it no longer forces
`audioStreamEnd` in the middle of a continuous capture. Explicit Pause/Stop still
send that flush behind preceding PCM. Every captured chunk is forwarded, including
quiet speech and pauses. Manual phrase mode continues to use declared activity signals.

The processing indicator and response watchdog follow unfinished recognition,
not raw microphone energy. An authoritative final resolves the preceding interim;
subsequent interims become pending again. Manual mode additionally tracks its
declared PCM activity. Raw energy may be traffic, breathing or room noise and
cannot establish missing words. Transport/queue failures remain terminal.

Stop joins capture, processes its posted tail and queues EOF. With no unfinished
recognition it settles for two seconds; a late interim restores the original
eight-second final deadline, and further finals may shorten the remaining wait.
Duplicate final notifications with no new text or pending-state change do not
restart the settling timer. New finalized text still refreshes settling, and a
late recognition hypothesis still restores its wait. Neither responses nor
rescheduling can extend the original deadline. If bytes
remain queued at the short settle, they retain the full original upload budget.
EOF/close is still bounded to 15 seconds and the complete Stop to 20 seconds.
These are application tradeoffs, not provider latency guarantees; a final that
arrives after the chosen close window cannot be recovered.

A mic tap during drain retains one restart request and shows ordinary feedback.
Repeated taps do not duplicate it. The next recording starts after clean close,
refreshing settings/context, with no audio replay or overlapping sockets. It is
not yet listening while finishing; wait for the Listening/microphone indicator.
Cancellation, network/editor failure and newer generations invalidate that request.
Keyboard hiding and host clears still cancel immediately. Do not promote interims.

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
- Pending recognized words with no server response:
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
  An authoritative final resolves preceding recognition; a later interim becomes
  pending again. The submitted boundary timestamp remains available to bound a
  late interim after a prior final. Raw queued audio is not a word hypothesis.
  A duplicate authoritative final advances recognition without duplicate insertion.
  Every explicit Resume starts a new boundary epoch, including quiet resumed
  speech without a local onset; the old Pause cannot time it out.
- Stop request: cancel the earlier response/final watchdogs and start one
  20-second budget for capture shutdown, remaining setup, upload, EOF and close.
  Within that budget, settle for two seconds without unfinished recognition or
  allow the original eight-second final wait when recognition remains pending, and allow at most 15 seconds from EOF for close completion.

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
  - `PREF_GEMINI_END_OF_SPEECH_SILENCE_MS` (int, 400–5000, default 1000)
  - `PREF_GEMINI_AUTO_DETECT_LANGUAGE` (boolean, default `false`)
  - `PREF_GEMINI_USE_EDITOR_CONTEXT` (boolean, default `true`)
  - `PREF_GEMINI_CUSTOM_VOCABULARY` (string, one term per line)
- Local silence settings:
  - `PREF_VOICE_CHUNK_SILENCE_SECONDS` — local silence confirmation before the
    auto-stop timer (default 1 second); it does not force a phrase boundary
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

### Manual phrase comparison for quiet-speech omissions

**Finish phrases manually (test)** in Transcription settings is opt-in and defaults
off. It keeps the same microphone samples, gain, language, vocabulary and
Smart/Verbatim setting, while replacing automatic speech detection with explicit
activity boundaries. Google documents this as [manual VAD](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe#manual-vad-push-to-talk).

- Setup sends `automaticActivityDetection: {disabled: true}` and
  `activityHandling: "NO_INTERRUPTION"`. A cached automatic-only setup tier cannot
  remove this choice; rejection of both supported tiers fails before audio.
- `activityStart` immediately precedes each capture phase's first PCM; both fit
  the same bounded socket budget. Explicit Pause/Stop sends `activityEnd` behind
  preceding audio. Pause then Stop without resumed audio adds no empty activity.
  No `audioStreamEnd` is sent: Google's [wire reference](https://ai.google.dev/api/live#bidigeneratecontentrealtimeinput)
  reserves it for automatic detection.
- Local RMS boundaries and automatic silence stop are inactive. The saved
  automatic settings remain available when this mode is turned off. Every PCM
  frame in the declared activity counts as pending for final/queue accounting;
  raw capture speech counts still describe the original energy detector.
- An open declared activity can exceed 30 seconds without a transcription
  response. Text may wait until Pause/Stop. Resume waits until pending paused
  speech and its queued boundary are confirmed; otherwise it leaves capture paused
  and asks the user to wait. Finals have no phase ID, so overlapping unconfirmed
  phases could let an old final acknowledge newer quiet speech. After confirmation,
  Resume clears old boundary waits even if speech starts quietly. Explicit Pause still has a 30-second response/final
  bound; Stop keeps its existing 20-second overall budget and eight-second final
  grace. Network/transport, upload queues, editor rejection, cancellation and the
  nine-minute session limit retain their guards. Only authoritative finals enter
  the editor.

For a controlled finalization trial, keep the built-in microphone close and
compare the same short text with manual mode on/off. Include a 10–20 second pause,
resume with an unmistakable new sentence, then Stop and tap the mic once while
processing. Wait for Listening before speaking in the next recording. Export the
focused diagnostics promptly; note the mode, Pause/Stop/restart actions, missing
words and whether the spinner clears.

The October 28 interruption logs contained six `Transcription stopped responding`
failures and five normal client-close failures caused by pending raw audio despite
no unfinished interim. In session 87 a final was accepted at 18:13:29.834, raw PCM
reopened pending speech 25 ms later, and the client-initiated normal close became
an interruption at 18:13:39.055. Repeated restart taps during the fixed grace were
rejected. Captured/locally accepted byte counts matched at these failures; this
establishes local bookkeeping defects, not provider word coverage. No raw exports
or dictated text are committed. New regression tests reproduce these state transitions.

### Diagnostics for interruption and phrase-finalization problems

The volume investigation is complete: the intended microphone policy and microphone
position matter, and all captured PCM is forwarded. Volume windows, per-read RMS,
peak/clipping, noise-floor and threshold logging have been removed. The RMS detector
and its saved settings are unchanged.

Use **Transcription → Voice diagnostics** for session mode, actual microphone route
once per capture phase, final/interim progress, boundary submission, upload queues,
stop/close timing and terminal error details. **About → Debug log** includes those
same focused voice events plus broader app/lifecycle warnings. The voice ring holds
500 lines in memory; export promptly after reproducing the problem, before the app
process exits. Keep the observed output separately and note Pause/Stop/restart actions.
No audio, words, transcript hashes, provider payloads or device identities are saved.
