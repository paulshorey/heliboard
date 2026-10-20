# Grok Voice Transcribe 2.0 dictation

This branch runs `grok-voice-transcribe-2.0` in the keyboard, using native Android
microphone capture and a direct WebSocket. Enter a personal xAI API key in
Settings → Transcription (or Setup this app), then use the fixed strip microphone.
An account with API credits is required. No provider probe is a prerequisite.

## Accuracy and speaker selection

The app uploads every mono PCM16/16 kHz microphone frame, including quiet audio.
Attribution is enabled with `diarize=true`. Only completed, attributed utterances
are inserted. Interim hypotheses never become editor text, including on timeout,
EOF or failure. Chunk finals are retained until an utterance final or normal EOF.

The first finalized nonempty word in audio order chooses the session speaker;
do not assume the label is zero. The choice survives pause/resume and resets on
an explicit new recording. Other speakers are filtered by word, with progress
still tracked. Missing attribution fails explicitly. Speech that overlaps or has
similar voices may be misattributed. Speak first; stop/start if a TV or another
person was selected. This is anonymous diarization, not recognition of an enrolled
voice or audio separation. All recorded voices are uploaded even when filtered.

`GrokTranscriptAssembler` reconciles final chunks, stitched utterances and EOF
using word timing and attribution, not text-prefix guesses. Exact repetitions at
new audio positions are retained. A revision of already inserted words or labels
fails instead of silently rewriting the editor. The ledger is bounded to 50,000
released words, 10,000 pending words and 131,072 pending characters. Server messages
are limited to 1 MiB. These are app safeguards, not advertised provider limits.

Provider wording, casing and punctuation are preserved, apart from redundant
punctuation cleanup and explicit sentence-form spoken punctuation/paragraph
commands. Grok does its own filler removal. Former provider disfluency deletion,
leading-case lowering and trailing sentence punctuation stripping are bypassed.
No separate text model or punctuation model receives the transcript.

## Wire protocol and configuration

- `wss://api.x.ai/v1/stt`, model explicitly pinned to `grok-voice-transcribe-2.0`.
- `Authorization: Bearer <personal key>` header. No credential in a URL.
- `encoding=pcm`, `sample_rate=16000`, `diarize=true`, `interim_results=true`,
  `filler_words=false`. Audio is binary, with no WAV header or base64.
- Wait for `transcript.created` before sending any audio. HTTP upgrade is not
  sufficient readiness; there is no client setup frame.
- Stream approximately 100 ms / 3,200-byte frames in real time. A monotonic pacing
  budget allows at most one frame of headroom and does not accumulate credit
  through long pauses. Local startup backlog drains at the same bounded pace.
- `smart_turn=0.7` and `smart_turn_timeout=5000` are the starting defaults.
  Settings allow threshold 0–1 and timeout 1–5000 ms. Try 0.9 for more patient
  detection. The timeout can still split a thought after a five-second pause.
- The supported keyboard language supplies a language hint. Auto-detect omits it.
  `format=true` is optional, defaults on, and is sent only with an explicit
  supported language. It controls number/currency normalization, not grammar.
- Repeated `keyterm` parameters merge user terms, built-in terms and optional
  proper nouns harvested near the caret: at most 100, 50 characters each. Full
  editor paragraphs are never transmitted. Only vocabulary is inherited once;
  comparison-provider keys and settings are preserved.
- `transcript.partial` uses `is_final` for chunk finality and `speech_final` for
  completed utterances. Progress timestamps must fit already submitted audio.
- Ordinary local silence is advisory and never sends `finalize`, so it cannot
  override semantic endpointing. Explicit Pause sends `{"type":"finalize"}`
  behind preceding audio. It retains the connection and speaker; it sends no
  microphone audio while paused. Long idle behavior remains a phone/provider test.
- Stop joins capture and orders its already posted tail before `audio.done`.
  Accept `transcript.done`, reconcile repeated/remaining finals, then close. A
  normal peer close may race the local close acknowledgement; that is not failure.

## Failure, queues and editor safety

The recorder, transport and editor callbacks are serialized on the main looper.
Session and attempt generations reject stale results after cancellation or retry.
Raw PCM is bounded to 960,000 bytes (30 seconds), socket frames to 256 KiB,
controls and editor FIFO entries to 64 each. Backpressure retains the exact head;
controls cannot overtake audio, and newer audio cannot overtake pending controls.
No captured PCM is dropped to catch up. Stalled controls have a 30-second limit.

Only explicit HTTP 429/503 startup rejection before readiness/audio may retry:
at most twice with 500/1500 ms backoff (or numeric Retry-After within budget),
inside the original 12-second deadline. Auth/configuration/credits failures and
transport failures are terminal. The app never automatically reconnects/replays
an active recording: the documented STT protocol has no resumable audio cursor or
cross-connection speaker identity.

Existing 30-second upload/response/final watchdogs are retained to tolerate
patient speech and thinking pauses. A boundary anchors the missing-final wait;
interims cannot extend it. Live interims allow long ongoing speech. A final
cannot acknowledge a newer speech suffix just because the socket drained: its
processed audio interval and the held-word ledger must also cover that speech.
Normal EOF can settle a processed wordless/noise tail without inventing text.

Default-network loss stops capture immediately, clears pending audio/text, rejects
late results, and requires a new mic tap even when connectivity returns. Explicit
Stop has a 15-second EOF/close wait within one absolute 20-second budget including
capture/setup/upload. A 30-minute app capture limit stops and drains without
automatic rotation; it is not a claimed provider limit.

Already inserted finals survive failures. Possible lost speech is marked once
with literal `[Dictation interrupted]` after readiness/capture. Startup rejection
leaves the editor untouched. Editor rejection/exception terminates later writes;
never retry a potentially partial edit or write a marker into a failed editor.
Verified selection/replacement, host-clear detection, fullapp behavior and
interactive keyboard-hide cancellation remain owned by LatinIME/InputConnection.
Screen-off hiding alone preserves recording. Explicit Stop drains; cancellation
removes all processing immediately.

## Credentials and diagnostics

The personal key is stored via AtomicFile under `Context.noBackupFilesDir`, in the
app sandbox, outside shared preferences and Android backups. It is not separately
encrypted beyond device storage. Both entry fields are masked password inputs.
Clearing the field deletes the file. A shared production credential needs a backend
proxy; Speech-to-Speech ephemeral tokens are not assumed to authenticate STT.

Diagnostics retain phase, byte counts, filtered-word counts, settings, timing and
local protocol failure reasons, never audio, keyterms, keys or provider payloads.
Settings → Transcription → Voice diagnostics is useful after a failed session.
The general xAI API policy describes 30-day audit retention and no training without
explicit permission; this personal-key build does not claim zero retention.

The previous Gemini implementation and focused tests remain as inactive comparison
reference; neither its endpoint nor key is used by this branch. Its provider
skills/MCP configuration are retained rather than changed incidentally.

## Validation and phone acceptance

Run `./tools/android-dev.sh smoke`, relevant editor/log suites, agent/tool checks,
`./tools/android-dev.sh lint`, and the canonical `./tools/build-dist-apk.sh`.
Grok tests include a real local WebSocket with binary audio, word-level filtering,
stitch/EOF deduplication, production pacing, queue controls, startup retries,
failed attribution, stale results, private credentials and preserved native text.
They use no real key and cannot establish provider speech quality.

Install the PR APK as an update, enter your key, and evaluate real continuous
speech. Compare short/long thinking pauses, questions/lists, corrections, names,
numbers, quiet speech, TV, sequential and overlapping speakers, Pause/Stop,
network loss, keyboard hiding, host clears and different editors. Record unwanted
speaker words, missing target words, punctuation/casing, duplication, latency and
failure behavior separately. Keep the PR draft until human phone acceptance.

## Official references (checked 2026-10-19)

- [Speech to Text](https://docs.x.ai/developers/model-capabilities/audio/speech-to-text)
- [Streaming event schemas](https://docs.x.ai/stt-streaming.ws.json)
- [STT API reference](https://docs.x.ai/developers/rest-api-reference/inference/speech-to-text)
- [Pricing and limits](https://docs.x.ai/developers/models/speech-to-text)
- [Data and privacy](https://docs.x.ai/developers/faq/security)
- [Ephemeral token scope](https://docs.x.ai/developers/model-capabilities/audio/ephemeral-tokens)
