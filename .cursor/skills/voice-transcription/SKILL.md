---
name: voice-transcription
description: Voice-to-text pipeline using the Google Gemini Live API (gemini-3.5-transcribe-live) with local audio capture and direct finalized-text insertion. Use when working on microphone recording, audio streaming, transcription results, silence detection, or voice input UI.
---

# Voice Transcription

Local recording + Gemini Live real-time transcription + immediate caret insertion.

For raw Gemini Live API details (models, endpoints, SDK patterns), read the
`gemini-live-api-dev` skill and query the Gemini Docs MCP. This skill covers how
HeliBoard uses that API.

## Architecture

```
Microphone → VoiceRecorder (PCM16 16kHz) → Gemini Live WebSocket → inputTranscription
                                                                        ↓
Text Field ← LatinIME (commitText) ← VoiceInputManager (FIFO queue + reconnect + graceful stop)
```

Recording starts **instantly** on mic tap — no network round-trip delay.

## Accuracy over latency

This pipeline is deliberately tuned for correctness, not responsiveness. Dictated
text goes straight into the user's editor, so a wrong word or a period in the
wrong place costs more than a second of waiting. When changing anything here, do
not "optimize" latency at the expense of transcript quality.

## Key Files

| File | Role |
|------|------|
| `VoiceRecorder.kt` | PCM16 capture, adaptive RMS silence detection |
| `GeminiTranscriptionClient.kt` | WebSocket client for `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=…`. Builds the `setup` payload at a negotiated [setup tier](#setup-tier-degradation), streams base64 PCM as JSON text frames, parses `inputTranscription` / `interimInputTranscription` / `turnComplete` / `goAway`, sends `audioStreamEnd` for Hybrid VAD, and reassembles editor segments |
| `VoiceContextVocabulary.kt` | Harvests proper nouns and acronyms from editor text and merges them with user + built-in terms into `customVocabulary` |
| `TranscriptSegment.kt` | Finalized chunk passed from the client to the IME pipeline |
| `VoiceInputManager.kt` | State machine (IDLE→RECORDING↔PAUSED→IDLE), FIFO transcript queue, reconnects, session rotation on `goAway`, auto-stop timers, session config assembly |
| `TranscriptionPreferences.kt` | Reads/writes Gemini preferences and erases the preference keys of previously used providers |
| `TranscriptPostProcessor.kt` | Local transcript shaping: pre-commit casing/trailing-punctuation adjustment plus paragraph-level spoken-command replacement after commit |
| `LatinIME.java` | Orchestrator — finalizes composing state, commits transcript text at the caret, supplies editor text for vocabulary harvesting via `buildVoiceContextText`, triggers post-processing |
| `TranscriptionScreen.kt` | Settings UI for API key, smart mode, editor-context biasing, language detection, end-of-speech pause, and local silence thresholds |
| `VoiceVocabularyScreen.kt` | Settings UI for editing user `customVocabulary` (one per line). Merged with the built-in list at session start. |

All source files live under `app/src/main/java/helium314/keyboard/latin/voice/`
except `LatinIME.java` (parent package) and the settings UI/preferences helpers in
`latin/settings` and `settings/screens`.

## Chunked Audio Flow

1. Mic chunks are captured locally in `VoiceRecorder` (~100 ms each).
2. `VoiceInputManager` builds a session config from preferences, the current
   subtype locale, and the editor text before the caret.
3. `GeminiTranscriptionClient` opens the socket and sends one `setup` frame, then
   **waits for `{"setupComplete":{}}`** before releasing audio. Buffered chunks
   flush on `onStreamReady`.
4. Audio goes out as JSON text frames:
   `{"realtimeInput":{"audio":{"data":"<base64>","mimeType":"audio/pcm;rate=16000"}}}`.
5. The server emits `serverContent.interimInputTranscription` (speculative,
   **never committed**) and `serverContent.inputTranscription` (authoritative).
   `serverContent.turnComplete` closes an utterance. `serverContent.modelTurn` is
   ignored so no generated response can leak into the editor.
6. `TranscriptAccumulator` converts finalized transcripts into segments and marks
   `attachesToPrevious`.
7. `VoiceInputManager` delivers segments in FIFO order to `LatinIME`.
8. `LatinIME` inserts each segment with `commitText(...)`, replacing any active
   selection and restoring a leading space only when the segment is **not**
   `attachesToPrevious`.

On graceful stop the client sends `audioStreamEnd` and keeps reading for up to 8 s
so the trailing phrase still arrives, then closes with 1000.

## Setup payload

```json
{"setup":{
  "model":"models/gemini-3.5-transcribe-live",
  "generationConfig":{"responseModalities":["TEXT"]},
  "inputAudioTranscription":{"languageCodes":["en-US"],"mode":"SMART","customVocabulary":["…"]},
  "realtimeInputConfig":{"automaticActivityDetection":{
    "disabled":false,
    "startOfSpeechSensitivity":"START_SENSITIVITY_HIGH","prefixPaddingMs":300,
    "endOfSpeechSensitivity":"END_SENSITIVITY_LOW","silenceDurationMs":1500}},
  "systemInstruction":{"parts":[{"text":"…"}]}
}}
```

Two placement rules, both covered by tests in `GeminiTranscriptionClientTest`:

- **`inputAudioTranscription` is a sibling of `generationConfig`, not a child.**
  Nesting it closes the socket with 1007. Google's Live Translate guide documents
  the broken shape, so this is easy to regress.
- **`responseModalities` must be `["TEXT"]`** and belongs inside
  `generationConfig`. `AUDIO` is for the conversational Live Agent models; only one
  modality per session is allowed.

## Setup-tier degradation

The transcribe model's documented feature list is narrower than the shared `setup`
proto it accepts, and an unsupported field is rejected with close code 1007 —
which would leave voice input permanently dead. So `setup` is rendered at one of
four `SetupTier` values and the client reconnects one tier lower on 1007:

`FULL` → `NO_SYSTEM_INSTRUCTION` → `NO_REALTIME_CONFIG` → `MINIMAL`

The working tier is cached in `negotiatedSetupTier` for the process. A 1007 whose
reason names an auth status is **not** retried. When adding a new `setup` field,
put it in the tier that matches how well-documented it is for this model.

## Config levers, in order of impact

- **`mode: SMART`** — disfluency removal, inline self-correction resolution,
  list/number/date formatting, grammar and casing polish. `VERBATIM` is the
  literal alternative.
- **`silenceDurationMs`** (default **1500 ms**, range 400–5000) with
  **`endOfSpeechSensitivity: END_SENSITIVITY_LOW`**. Google documents that short
  windows split one utterance into fragments and that the model then loses
  cross-fragment context, lowering quality. Do not reduce this for latency.
- **`customVocabulary`** — user terms, then built-in terms, then editor-harvested
  terms, capped at 100 (Google notes best results around 100 even though 1 000 are
  accepted).
- **`languageCodes`** — explicit BCP-47 hint from the subtype, mapped through
  `resolveLanguageCode` onto the codes Gemini documents. Unknown subtypes send `[]`
  rather than a code Gemini might reject. Auto-detect is opt-in because it misfires
  on short utterances.
- **`startOfSpeechSensitivity: START_SENSITIVITY_HIGH` + `prefixPaddingMs: 300`** —
  keeps prefix audio so the first syllable is not clipped.
- **`systemInstruction`** — static dictation guidance, no editor text. Live
  Transcription does not advertise system-instruction support, so it may be
  ignored; that is why it sits in the top tier.

## Editor context → vocabulary, not prompt

`LatinIME.buildVoiceContextText` supplies up to 4 000 chars before the caret;
`VoiceContextVocabulary` harvests words worth biasing and sends **only those
words**. Harvested: internal capitals (`iPhone`), all-caps ≤10 chars (`API`), and
capitalized words not at a sentence start. Rejected: common English words, words
containing digits, and tokens glued by `@ / \ : _` (URLs, paths, emails,
identifiers).

**Verbatim editor text is deliberately never sent.** Neither `systemInstruction`
nor a seeded `clientContent` history is a documented input for this model, and
feeding an already-typed paragraph to a generative model risks it echoing that
text back as transcription. Casing and punctuation continuity with surrounding
text is handled locally instead (see below).

## Turn finalization (Hybrid VAD)

Server VAD stays enabled for accurate speech onset but is configured to be patient
about ending speech, which means a trailing phrase can sit unfinalized. The client
backstops it with `{"realtimeInput":{"audioStreamEnd":true}}`:

- after local silence (`PREF_VOICE_CHUNK_SILENCE_SECONDS`, default **2 s** —
  longer than the server window, so the server's semantic endpointing normally
  wins), at most once per speech-stop transition;
- on **mic pause** — a turn left open with no audio is the dominant cause of the
  Live API dropping the connection with 1011;
- on **stop**, before the read grace period.

The session stays open; the next audio chunk reopens the stream.

## Transcript assembly

The Live API has emitted finalized transcripts both as per-utterance deltas and as
text that grows on each message, and the semantics changed between model
generations. `TranscriptAccumulator` compares each transcript against the previous
one, so text extending the previous message contributes only its suffix, unrelated
text contributes all of itself, and an identical repeat (seen on reconnect)
contributes nothing. `turnComplete` resets the comparison.

`attachesToPrevious` is set for segments starting with attaching punctuation
(`. , ! ? : ; ) ] } %`) or resuming mid-word (`head` then `heading` yields `ing`,
not `head ing`).

## Session lifecycle

- Sessions are capped at **10 minutes**.
- `{"goAway":{"timeLeft":"30s"}}` — `timeLeft` is a protobuf Duration and arrives
  as a **string**.
- `VoiceInputManager` rotates onto a fresh connection 1.5 s before the announced
  deadline and unconditionally after 9 minutes. Rotation does not consume a
  reconnect attempt; buffered audio carries across.
- There is **no application-level keepalive** in this protocol. OkHttp pings run
  every 20 s; the real fix for dropped connections is the audio lifecycle above.
- Close codes: 1007 setup schema or auth, 1008 policy/billing, 1011 stalled turn,
  1006 network.

## Post-Processing (TranscriptPostProcessor)

Before commit, `LatinIME.prepareVoiceTranscriptionText()` uses
`TranscriptPostProcessor.adjustLeadingCasing()` and
`stripTrailingPunctuationIfMidSentence()` to fit the chunk into surrounding text.

After each chunk is committed, `LatinIME.runTranscriptPostProcessing()` reads the
current paragraph (from the last newline to the cursor, up to 1024 chars) and runs
`TranscriptPostProcessor.processCurrentParagraph()`. If any rule matches, the
paragraph is replaced in place via `deleteTextBeforeCursor` + `commitText`.

Current processing removes comma-attached filler fragments ("um,", "uh,") and
handles **spelled-out punctuation** ("exclamation point.", "comma", "question
mark.", "period.", "colon.", "semicolon."). Rules are case-insensitive and sorted
longest-first so patterns with surrounding punctuation context are consumed before
shorter ambiguous ones. The processor only fires when a rule actually modifies the
paragraph.

To add rules, edit `TranscriptPostProcessor.buildRules()`. Unit tests are in
`TranscriptPostProcessorTest.kt`.

## Leading-Casing Correction

Real-time STT models capitalize the first letter of a new utterance. When the user
dictates mid-sentence — caret inside existing text, or resumed after deleting a
trailing period — that capitalization is wrong.

`TranscriptPostProcessor.adjustLeadingCasing(chunk, previousContext)` handles this
**before commit**, called from `LatinIME.prepareVoiceTranscriptionText` with
`VOICE_CASING_LOOKBACK` (16) characters of preceding text.

The first character is lowercased only when all of these hold:
- the chunk is not `attachesToPrevious`
- the first character is an uppercase letter
- the previous visible character (ignoring trailing whitespace and closing `"`,
  `'`, `“”`, `‘’`, `)`, `]`, `}`, `»`) is **not** `.`, `!`, `?`, or a newline — and
  the context is not empty/whitespace
- the first word is **not** `I`/`I'm`/`I'll`/`I've`/`I'd`, an all-uppercase acronym
  (`NASA`), or a camel/Pascal-case word with internal uppercase (`iPhone`,
  `McDonald's`)

Known tradeoff: a proper noun dictated as the first word of a mid-sentence chunk
(`Amazon`, `Paris`) is lowercased. Editor-harvested `customVocabulary` reduces how
often this matters, because such words are more likely to be transcribed as part
of a longer phrase.

## Paragraph Breaks

Silence-driven automatic paragraph insertion is disabled because inserting `"\n\n"`
into arbitrary host fields caused form submissions and other unintended side
effects. Explicit spoken commands such as "New paragraph." are still handled by
`TranscriptPostProcessor`.

## Thread Safety

- Audio recording: background thread
- Gemini callbacks: forwarded to main thread
- Timer callbacks: main thread
- Text insertion: always sequential on main thread

## Verifying against the real API

`GeminiTranscriptionClientTest`, `GeminiTranscriptionClientStreamTest` (local
WebSocket server, including the 1007 tier fallback) and `VoiceContextVocabularyTest`
cover the wire format and client state machine. They cannot confirm what Google's
server does with each field.

For that, `tools/gemini-live-smoke-test.py` sends the app's exact payloads to the
real endpoint:

```bash
export GEMINI_API_KEY=...
tools/gemini-live-smoke-test.py --probe-setup        # which setup tiers are accepted
tools/gemini-live-smoke-test.py --audio speech.wav   # real transcripts (16 kHz mono PCM16)
```

## Additional Resources

- Detailed data flow: [data-flow.md](data-flow.md)
- Gemini Live API reference and settings keys: [api-reference.md](api-reference.md)
- Long-form design notes: `docs/gemini-transcription.md`
- Raw Gemini Live API: the `gemini-live-api-dev` skill + the Gemini Docs MCP

## Update documentation

IMPORTANT: When you change something, or discover that the code or functionality
differs from what's described, update this skill and its supporting docs
immediately.
