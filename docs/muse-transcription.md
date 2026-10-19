# Muse Voice Transcribe dictation

This branch uses Meta `muse-voice-transcribe-1.0`, with a first-speaker filter.
Accuracy takes priority over latency. Only completed, attributed turns enter the
host editor. An arbitrary two-second output deadline would commit speculation;
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
  `audioEncoding`, `mode: DIARIZATION`, `partialMode: CUMULATIVE`,
  `emitAudioProgress: true`, optional `keywords` and `languageBias`.
- Wait for the bare `{sessionId: ...}` acknowledgement. Then send raw binary PCM16
  little-endian mono, not base64 or a WAV container.
- Prefer 24 kHz; initialize 16 kHz capture if 24 kHz cannot initialize. Keep the
  selected format fixed and advertise it accurately in the handshake.
- `speechStart` creates a turn. `transcript` updates provisional evidence only,
  even with `final: true`. `speechEnd` is a boundary. `speechComplete` contains the
  entire completed turn, including provider punctuation/capitalization.
- `speaker` labels the preceding active span and has no turnId. Associate it with
  the active speechStart. Keep completed state keyed by turnId, using observed
  start order, not numeric IDs or completion arrival order.
- Require speechEnd as well as completed text and attribution before release; an
  early completion cannot overtake a later conflicting speaker label.
- The earliest nonempty attributed turn selects the speaker. Other labels are
  discarded before insertion but still settle processing. Labels persist through
  pause/resume and reset at the next explicit recording.
- Repeated same-label events are harmless. Different labels in one turn cannot be
  sliced safely without word-level attribution: hold and fail rather than guess.
  Missing attribution blocks later text until resolved; close/timeout reports it.
- Deduplicate by session/turn identity, not transcript contents. Repeating a whole
  sentence in a new turn must type it again.

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

Pause stops native microphone capture. A main-looper barrier orders its posted
tail before generated zero PCM, emitted on an absolute 100 ms schedule. Resume
cancels the silence generator and resumes the same native capture/session. Paused
time can be billed. No captured audio is synthesized over a missing network span.

Local RMS VAD only controls activity and the configurable silence auto-stop
(default 30 seconds). It does not finalize turns or discard quiet audio. Each
recognized turn has a 30-second final/attribution deadline once it ends or has
completed text (the baseline also starts this wait for early completions);
interims/progress cannot postpone it. Long speech without an endpoint can continue
while the response watchdog sees progress. Raw noise alone requires no final.

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


## Full baseline restoration and acceptance hold (October 19, 2026)

The author twice compared the original `05a37649` APK with later builds on the
same phone. The original reliably punctuated after a sufficiently long pause;
`ef081a49` and the partial rollback `45620443` consistently omitted punctuation.
The partial rollback retained reviewed fixes in turn assembly, speech tracking
and normal closure. Those are now reverted too. All application code, resources,
build configuration and JVM tests are restored to `05a37649`; later developer
probe changes are also reverted. Only explanatory documentation differs from
the baseline. The individual causal change remains unisolated.

A freshly built APK must be checked against the original packaged runtime, not
only source fingerprints. Passing offline tests do not establish phone quality.
Keep the original immutable APK available as the already-tested reference and
keep this PR draft. Do not reapply reviewed fixes automatically while the
version-dependent punctuation regression is unresolved.

The rollback deliberately restores these known baseline defects, which remain
open review findings:

- An ended no-word turn can remain at the ordering queue head and block later
  completed speech. There is no empty-turn retirement deadline.
- An empty outgoing socket queue can let an older final acknowledge a newer
  speech suffix that has not yet been reported processed by the provider.
- Local RMS noise with no completed words can leave an interruption pending at
  normal closure. There is no separate verified wordless-settlement callback.
- Early completed text starts the final wait even before speechEnd, and an empty
  final transcript flush without an unsettled turn can be rejected.

These findings are deferred during recovery, not fixed or disproven. Reintroduce
any change only in an isolated, reversible contribution with a same-phone
comparison to the accepted baseline. Do not merge this draft merely because the
baseline tests or an automated review pass.

## Proposed punctuation restoration (research only)

Meta's [cookbook](https://dev.meta.ai/docs/cookbook/muse-voice-transcribe) explicitly
says Muse handles punctuation. The [voice schema](https://dev.meta.ai/docs/api-reference/voice/schemas)
exposes no punctuation toggle, aggressiveness control or endpoint pause setting.
The author's playground observation and app results establish inconsistent
punctuation for this use, not the absence of a native punctuation capability.

Recommendation: evaluate a separate punctuation/capitalization stage on saved,
non-sensitive human dictation before touching capture or transport again. No
second model, model download, cloud account or extra text upload is added in the
baseline recovery. The following is a proposed follow-up, not shipping behavior.

### Candidates and limits

- **First local English candidate:** Edge-Punct-Casing, distributed as
  `sherpa-onnx-online-punct-en-2024-08-06`. The [maintainer's model documentation](https://k2-fsa.github.io/sherpa/onnx/punctuation/pretrained_models.html)
  lists a 7.1 MB INT8 ONNX model and a 146 KB BPE vocabulary. Its
  [paper](https://arxiv.org/abs/2407.13142) targets on-device streaming punctuation
  and casing. Small size makes it worth evaluating; it does not establish better
  accuracy or Android integration/performance in HeliBoard. Check exact model
  licensing, punctuation labels, supported Android ABIs/API levels, tokenizer,
  RAM, startup, battery and inference latency before adding a dependency.
- **Larger classification reference:** [kredor/punctuate-all](https://huggingface.co/kredor/punctuate-all)
  uses XLM-RoBERTa-base and supports twelve languages. Its model card names
  Europarl training data. Evaluate spontaneous dictation and domain-specific
  names separately; its published score is not a phone-dictation guarantee.
- **Cloud text model comparison:** allow a model to propose punctuation labels at
  fixed input-word boundaries, rather than freely rewrite the transcript. Score
  it against the local candidates before choosing. Network latency, cost,
  availability and a further text recipient are tradeoffs; no such service is
  selected or called in this work.

[NVIDIA's archived punctuation model documentation](https://docs.nvidia.com/nemo-framework/user-guide/24.12/nemotoolkit/nlp/punctuation_and_capitalization.html)
illustrates word-level punctuation/case labels and overlap for long text. Its
[lexical/audio model discussion](https://docs.nvidia.com/nemo-framework/user-guide/24.12/nemotoolkit/nlp/punctuation_and_capitalization_lexical_audio.html)
explains why text alone can be ambiguous without prosody. These are architectural
references, not a claim that an old NeMo API is the selected current runtime.

### Proposed integration and evaluation

1. Keep the accepted Muse connection, PCM pacing, turn completion and speaker
   filter unchanged. Accept only completed selected-speaker text as input to the
   restoration stage. This does not make Muse's final words arrive sooner.
2. Evaluate untouched Muse output, the small English model, a larger classifier
   and an optional cloud candidate on the same human-labeled text. Include fast
   paragraphs, long pauses, questions, fragments, self-corrections, names,
   acronyms, numbers/decimals, URLs, code-switching and spoken commands.
3. Prefer labels at original word offsets. Reconstruct from original word
   substrings, never accept inserted/deleted/substituted/reordered words. Protect
   internal apostrophes, hyphens, decimals, URLs, acronyms and mixed-case names.
   Any candidate returning text must pass strict word/order validation; reject
   invalid output and retain the untouched Muse result. Do not treat whitespace
   splitting or removing all punctuation as sufficient validation.
4. Give a bounded amount of session-owned preceding text as context; apply labels
   only to the new pending segment. Do not expose general host-editor content,
   rewrite prior committed text or mix another speaker's words into the context.
   Preserve explicit spoken paragraph/punctuation commands and already-correct
   provider punctuation in the initial conservative experiment.
5. Run inference off the main looper with a bounded queue, generation/target
   checks, ordered insertion, timeout and original-text fallback. Keyboard hide,
   host clear or target loss cancels pending results. Keep restoration opt-in and
   disabled by default until actual phone acceptance.
6. Measure sentence-boundary precision/recall, commas, question marks, casing,
   wrong extra punctuation, zero lexical changes, fallback rate and additional
   latency. Accuracy is the selection criterion; small size alone is not.
7. Only after offline comparison, add one selected model on a separate feature
   branch, then repeat same-phone comparisons with restoration on/off. Verify
   long-session memory and editor/lifecycle behavior as well as punctuation.

Text-only restoration can infer grammatical boundaries without a long acoustic
pause, but cannot recover every intonational distinction or correct a misheard
word under a word-preservation contract. It cannot remove the existing wait for
Muse's completed attributed turn. Any later goal of earlier streaming insertion
requires a separate provider/attribution design; do not relax final-only or
first-speaker guarantees to conceal that limitation.
