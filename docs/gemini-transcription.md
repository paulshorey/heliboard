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
   later audio and control frames cannot overtake it. Overflow stops the session.
4. Only `serverContent.inputTranscription` creates editor segments.
   `interimInputTranscription` is provisional and never inserted, including after
   silence, EOF, a timeout, socket replacement, or failure. `modelTurn` is ignored.
5. Deliver finalized segments in FIFO order. Retain the head until `LatinIME`
   synchronously returns editor acceptance, including paragraph cleanup. A false
   result or exception stops the session; never retry a potentially partial edit.
   Cleanup and punctuation correction select the original range and replace it
   with one commit, so a rejected replacement does not first delete confirmed text.
6. On terminal failure, invalidate the recording and connection generations before
   stopping capture, clearing pending work, and notifying the editor. Already
   inserted text remains. If the stream reached readiness and audio was captured, insert one literal
   `[Dictation interrupted]` marker without transcript cleanup. An editor failure
   suppresses the marker because the editor is no longer trustworthy.
   Rejection before `setupComplete` leaves the editor untouched and reports why
   dictation could not start; no PCM was submitted to that connection.
7. Restoring internet never resumes the failed recording. The user must tap the
   mic explicitly. A normal stop blocks restart until the outgoing stream closes.

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
not which recorded samples the service receives.

There is no stale-interim insertion timer. An interim is never evidence that all
speech was received. On explicit stop, stop/join the recorder, process its already
posted tail callbacks, enqueue EOF, and read for eight seconds before initiating
WebSocket close. A nonempty transport queue at that deadline is failure. The
manager also bounds the complete finish/close wait to 15 seconds. A capture thread still alive after
two seconds reports terminal failure before the EOF barrier, blocks native capture
reuse, and keeps its original recording callback for all late reads. Missing finals
for locally detected speech or interim hypotheses produce an interruption marker.

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
- Pending speech with no server response: 30 seconds. A response signals liveness;
  interims and `turnComplete` do not confirm the speech.
- Pending locally detected speech/interim with no accepted final: 30 seconds.
  Interim updates cannot postpone this deadline. Pure silence does not arm it.
  A final cannot clear pending speech while PCM or socket frames are still queued
  locally: it may describe an earlier utterance. The deadline remains until a
  later final arrives after those queues drain; draining alone is not confirmation.
- EOF: eight seconds before initiating close, 15 seconds for the full close wait.

Network loss, send/finalize failure, protocol error, unexpected close (including
remote 1000), or failure during graceful drain is terminal. OkHttp protocol pings
remain at 20 seconds as another transport failure signal.

### What this can and cannot prove

[OkHttp 4.12's WebSocket contract](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/WebSocket.kt)
says `send(true)` is queue acceptance; queued messages may be lost on cancellation,
and `queueSize()` excludes OS/intermediary buffers. Neither proves server receipt.
[Google's Live API reference](https://ai.google.dev/api/live) sends input transcripts
independently of other server messages, with no guaranteed ordering relative to
those messages. `turnComplete` is not a watermark for submitted PCM.

This implementation enforces local FIFO/editor acceptance and stops on detected
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

Unchanged by the provider swap:

- `LatinIME.prepareVoiceTranscriptionText()` handles separator-space insertion,
  mid-sentence leading-casing correction, and stripping a trailing `.`/`!`/`?`
  when dictating before lowercase text.
- `LatinIME.runTranscriptPostProcessing()` runs `TranscriptPostProcessor` over the
  current paragraph after commit, for spoken commands such as "Comma." or "New
  paragraph." and for leftover filler fragments.
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
raw/socket queue sizes, accepted finals and last-response age. Transport close
records retain the code, readiness, requested-finish state and sanitized service
detail, so an early billing rejection cannot be confused with a stalled upload.

The voice manager tests cover loss/reconnect callbacks, explicit restart, ordered
PCM/control delivery, queue overflow, oldest-audio and progress deadlines, editor
rejection/exception, setup fallback timeout, paused failures, and EOF/restart guards.
The bounded sender tests exercise the production queue limit and false sends with
a controllable socket. Network tests cover default-route validation, handoff,
stale callbacks, and API-21 receiver cleanup. Local WebSocket tests verify actual
frames, schema fallback, authoritative-only insertion, and terminal close/error
handling. InputLogic tests verify editor return values, failed cleanup, balanced
batch edits, and literal marker insertion.

After checking out the branch in the device workspace: dictate a confirmed prefix,
disable both Wi-Fi and mobile data while continuing to speak, and restore them.
The mic must stop, the prefix must remain, a single interruption marker must show,
and no automatic suffix may appear. Deliberately tap the mic for a new recording.
Also test offline start, radio loss while paused and while stopping, quiet speech
after pauses, a slow final, host-editor rejection, and the nine-minute limit.
