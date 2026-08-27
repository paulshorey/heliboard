# Gemini Live Transcription Architecture

HeliBoard uses Google Gemini's Live API for voice input, with the dedicated
real-time speech-to-text model **`gemini-3.5-transcribe-live`**.

The pipeline is tuned for **accuracy over latency**. Dictated text is committed
straight into the user's editor, so a wrong word or a period in the wrong place
costs the user more than a second of waiting.

## Runtime flow

1. `VoiceRecorder` captures 16 kHz mono PCM16 audio immediately when the mic
   button is tapped, in ~100 ms chunks.
2. `VoiceInputManager` starts `GeminiTranscriptionClient` in parallel and buffers
   audio until the session is ready.
3. `GeminiTranscriptionClient` opens
   `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=API_KEY`
   and sends exactly one `setup` JSON text frame.
4. Audio is held until the server answers `{"setupComplete":{}}`. Chunks are then
   sent as JSON text frames containing base64 PCM
   (`realtimeInput.audio`, `mimeType: audio/pcm;rate=16000`).
5. The server emits two transcript streams inside `serverContent`:
   - `interimInputTranscription` — speculative partials. **Never committed.** They
     only prove the stream is alive and satisfy the response watchdog.
   - `inputTranscription` — the authoritative transcript for a speech segment.
     This is what reaches the editor.
   `turnComplete` closes an utterance.
6. `GeminiTranscriptionClient.TranscriptAccumulator` turns those transcripts into
   editor segments (see *Transcript assembly*), and emits a `TranscriptSegment`.
7. `VoiceInputManager` queues segments in FIFO order; if the queue reaches 64
   entries it coalesces the oldest rather than reordering newer text.
8. `LatinIME` prepares the chunk, calls `finishInput()`, then `commitText(...)` to
   insert it at the caret. A leading space is injected only when the segment is
   **not** attaching to previous text and the preceding editor character needs a
   separator.

## Important files

- `app/src/main/java/helium314/keyboard/latin/voice/GeminiTranscriptionClient.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceContextVocabulary.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/TranscriptSegment.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceInputManager.kt`
- `app/src/main/java/helium314/keyboard/latin/voice/VoiceRecorder.kt`
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

Server-side VAD stays enabled so speech onset keeps its prefix padding, but it is
configured to be patient about *ending* speech. That patience means a trailing
phrase can sit unfinalized, so the client provides a backstop:

- On local silence (`PREF_VOICE_CHUNK_SILENCE_SECONDS`, default **2 s** — longer
  than the server window so the server's semantic endpointing normally wins),
  `VoiceInputManager` sends `{"realtimeInput":{"audioStreamEnd":true}}`. The
  server treats this as an immediate end of turn, bypassing its silence wait. The
  session stays open and the next audio chunk reopens the stream.
- On **mic pause**, the same signal is sent. A turn left open with no incoming
  audio is the dominant cause of the Live API dropping a connection with 1011.
- On **stop**, `finishStreaming()` sends it and then keeps reading for up to 8 s,
  because closing right after the last audio chunk is the usual way to lose the
  final phrase.

It fires at most once per speech-stop transition, re-armed on the next speech
onset.

## Transcript assembly

The Live API has emitted finalized input transcriptions both as per-utterance
deltas and as text that grows on each message, and the semantics changed between
model generations. `TranscriptAccumulator` compares each transcript against the
previous one, which covers both:

- text that extends the previous message contributes only its suffix
- unrelated text contributes all of itself
- an identical repeat (which happens on reconnect) contributes nothing
- `turnComplete` resets the comparison, so a phrase genuinely repeated in a new
  turn is still inserted

`attachesToPrevious` is set when a segment starts with punctuation that hugs the
previous word (`. , ! ? : ; ) ] } %`), or when a growing transcript resumes
mid-word (`head` then `heading` yields `ing`, not `head ing`).

`serverContent.modelTurn` is ignored, so a generated response can never leak into
the editor.

## Session lifecycle

- Live transcription sessions are capped at **10 minutes**.
- The server warns with `{"goAway":{"timeLeft":"30s"}}`. `timeLeft` is a protobuf
  Duration, so it arrives as a **string**, not a number.
- `VoiceInputManager` rotates onto a fresh connection 1.5 s before the announced
  deadline, and unconditionally after 9 minutes as a backstop. Rotation is not an
  error, so it does not consume a reconnect attempt; buffered audio carries across
  the gap.
- There is **no application-level keepalive** in this protocol. OkHttp protocol
  pings run every 20 s; the real fix for dropped connections is the audio
  lifecycle above.
- Close codes: **1007** = setup schema or auth (the reason string names the
  offending field path and is surfaced), **1008** = policy/billing, **1011** =
  stalled turn, **1006** = network.

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
