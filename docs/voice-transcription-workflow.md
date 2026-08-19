# Custom Voice Transcription — End-to-End Workflow

This document explains how HeliBoard's **own** voice-to-text pipeline works, from
the moment the user taps the microphone until the session is torn down. It is the
narrative companion to [`soniox-transcription.md`](soniox-transcription.md)
(provider/API reference) and to
[`pluggable-transcription-providers-plan.md`](pluggable-transcription-providers-plan.md)
(the plan to make the provider interchangeable).

---

## 0. Which provider is actually integrated

The integrated provider is **Soniox**, not Speechify.

- Soniox is reached over a raw WebSocket at
  `wss://stt-rt.soniox.com/transcribe-websocket` with the model pinned to
  `stt-rt-v4` (`SonioxTranscriptionClient`). That pin is **stale** — Soniox's
  current realtime model is `stt-rt-v5` and `v4` was retired after 30 June 2026;
  see [`soniox-transcription.md`](soniox-transcription.md) and section 8.1 of the
  [provider plan](pluggable-transcription-providers-plan.md).
- **Speechify** appears nowhere in the codebase. The only other provider names
  present are `speechmatics_api_key` and `deepgram_api_key`, and both exist
  purely as *legacy preference keys that get deleted* — `TranscriptionPreferences`
  removes them the first time the Soniox key is read, because a key issued by a
  different vendor cannot authenticate against Soniox. There is no Speechmatics
  or Deepgram client code left in the tree.

So when this document says "the provider", it means Soniox.

## 1. Two unrelated microphone buttons

HeliBoard has two voice entry points and they do completely different things.
Only the first one is our own pipeline.

| Control | Where | What it does |
| --- | --- | --- |
| Fixed mic `R.id.voice_input_key` | Right edge of the suggestion strip, in the `custom_buttons_overlay` group of `res/layout/suggestions_strip.xml`. Always present. | **Our custom pipeline.** Calls `SuggestionStripView.Listener.onVoiceInputClicked()` → `LatinIME.onVoiceInputClicked()` → `VoiceInputManager.toggleRecording()`. |
| `ToolbarKey.VOICE` | Expandable toolbar / pinned secondary toolbar, plus the optional on-keyboard shortcut key | **Not ours.** Emits `KeyCode.VOICE_INPUT`, which `LatinIME` turns into `RichInputMethodManager.switchToShortcutIme(...)` — i.e. it hands off to Android's system voice IME (usually Google's full-screen "Speak now" dialog). `InputLogic` deliberately does nothing for this key code. |

`SuggestionStripView.updateVoiceKey()` only toggles the **toolbar** `VOICE`
button's visibility (via `SettingsValues.mShowsVoiceInputKey`, which
`InputAttributes` turns off for password/email fields, `noMicrophone` editors,
and when no shortcut IME is available). The fixed strip mic that drives our
pipeline is never hidden by that setting.

Two sibling buttons live next to the fixed mic and are `gone` until a session
starts: `voice_cancel_key` and `voice_pause_key`.

## 2. Components

```
                       ┌──────────────────────────────────────────┐
  tap mic              │ SuggestionStripView                      │
  ───────────────────▶ │  voice_input_key / voice_cancel_key /    │
                       │  voice_pause_key, VoiceState tinting     │
                       └───────────────┬──────────────────────────┘
                                       │ Listener callbacks
                                       ▼
                       ┌──────────────────────────────────────────┐
                       │ LatinIME                                 │
                       │  session control, wake lock, toasts,     │
                       │  prior-text provider, text insertion,    │
                       │  interruption guards                     │
                       └───┬───────────────────────────┬──────────┘
                           │ toggle/stop/cancel        │ commit
                           ▼                           │
       ┌───────────────────────────────────────┐       │
       │ VoiceInputManager                     │       │
       │  IDLE↔RECORDING↔PAUSED state machine, │       │
       │  session fencing, audio buffer,       │       │
       │  FIFO transcript queue, reconnects,   │       │
       │  timers + latency watchdogs           │       │
       └──┬────────────────────────────┬───────┘       │
          │ PCM                        │ config/PCM    │
          ▼                            ▼               │
  ┌────────────────────┐   ┌──────────────────────────┐│
  │ VoiceRecorder      │   │ SonioxTranscriptionClient││
  │ AudioRecord PCM16  │   │ OkHttp WebSocket,        ││
  │ 16 kHz mono,       │   │ start-config JSON,       ││
  │ adaptive RMS VAD   │   │ token → TranscriptSegment││
  └────────────────────┘   └──────────────────────────┘│
                                                       ▼
                                    ┌──────────────────────────────┐
                                    │ TranscriptPostProcessor      │
                                    │ pre-commit casing/punct.,    │
                                    │ post-commit paragraph rules  │
                                    └──────────────┬───────────────┘
                                                   ▼
                                        InputConnection (host app)
```

Source locations:

| Concern | File |
| --- | --- |
| Trigger UI, recording overlay | `latin/suggestions/SuggestionStripView.kt`, `res/layout/suggestions_strip.xml` |
| Orchestration, text insertion | `latin/LatinIME.java` |
| Session state machine | `latin/voice/VoiceInputManager.kt` |
| Microphone + local VAD | `latin/voice/VoiceRecorder.kt` |
| Provider protocol | `latin/voice/SonioxTranscriptionClient.kt` |
| Transcript chunk model | `latin/voice/TranscriptSegment.kt` |
| Text shaping rules | `latin/voice/TranscriptPostProcessor.kt` |
| Preference access | `latin/settings/TranscriptionPreferences.kt`, `Settings.java`, `Defaults.kt` |
| Settings UI | `settings/screens/TranscriptionScreen.kt`, `SonioxContextTermsScreen.kt`, `VoiceDiagnosticsScreen.kt`, `SetupAppScreen.kt` |
| Busy spinner | `keyboard/KeyboardSwitcher.java` (`showProcessingIndicator` / `hideProcessingIndicator`), `res/layout/input_view.xml` |
| Diagnostics filtering | `latin/utils/Log.kt` |

---

## 3. Step-by-step lifecycle

### Step 0 — Preconditions

Two things must be configured before anything happens, both surfaced on the
**Setup App** checklist screen and on the **Transcription** settings screen:

1. `RECORD_AUDIO` permission.
2. A Soniox API key in `Settings.PREF_SONIOX_API_KEY`.

If the permission is missing, `VoiceInputManager` fires
`onPermissionRequired()` and `LatinIME` opens the Setup App screen. If the key is
blank, it fires `onError("Soniox API key not configured...")` and nothing starts.

### Step 1 — The user taps the mic

`VoiceInputManager.toggleRecording()` dispatches on the current state:
`IDLE → startRecording()`, `RECORDING → stopRecording()`, `PAUSED → resumeRecording()`.

`startRecording()` then does, in this order:

1. Checks mic permission and API key (above).
2. `reloadRuntimeConfig()` — reads `PREF_VOICE_CHUNK_SILENCE_SECONDS`,
   `PREF_VOICE_SILENCE_THRESHOLD`, `PREF_VOICE_AUTO_STOP_SILENCE_SECONDS` (each
   clamped) and the whole `SonioxConfig`, and pushes the silence settings into
   `VoiceRecorder`. **Settings are snapshotted per session**, so changing them
   mid-recording has no effect until the next session.
3. `beginNewSession()` → `invalidateActiveSession()`, which increments
   `activeSessionId` and clears every queue, timer, and flag. This monotonic id is
   the **fencing token**: every recorder callback, socket callback, timer, and
   queue entry carries the id it was created under and is dropped if it no longer
   matches `activeSessionId`. That is how late callbacks from a cancelled session
   can never insert text into a newer one.
4. Installs the `VoiceRecorder.RecordingCallback`.
5. **Starts the network session first** (`startStreamingSession`), which is
   asynchronous and returns immediately.
6. **Then starts the microphone** (`voiceRecorder.startRecording()`), which is
   synchronous. If it fails, the whole session is cancelled.
7. Synchronously enters `State.RECORDING` and arms the auto-stop timer.

The important property: recording begins on the very next audio buffer, with **no
network round trip on the critical path**. Audio captured before the socket is
usable is buffered rather than dropped.

`LatinIME`'s state listener reacts to `RECORDING` by tinting the mic, cancel, and
pause buttons red, revealing cancel/pause, showing a "Listening..." toast, and
calling `acquireVoiceWakeLock()` — which both sets
`FLAG_KEEP_SCREEN_ON` on the IME window and takes a `PARTIAL_WAKE_LOCK` with a
10-minute safety timeout.

### Step 2 — Opening the provider stream (in parallel)

`startStreamingSession()` assembles a `SessionConfig` from three sources:

- **Preferences** — endpoint detection on/off, `max_endpoint_delay_ms`
  (clamped 500–3000), diarization on/off, user custom terms.
- **The keyboard subtype locale** — `Settings.getValues().mLocale.language`,
  normalized to a bare lowercase language code, becomes a single-element
  `language_hints`. `und`/blank means "omit and let Soniox auto-detect".
- **The host editor** — `PriorTextProvider`, registered once in `LatinIME.onCreate`
  as `LatinIME::buildVoiceContextText`, is invoked synchronously on the main
  thread and returns up to 4 000 characters of text before the cursor. It is
  re-invoked on every reconnect so the already-dictated text stays in context.

`SonioxTranscriptionClient.startStreaming()` opens the socket with OkHttp
(`readTimeout = 0`, 10 s ping interval) and, in `onOpen`, sends a single JSON text
frame containing `api_key`, `model`, `audio_format: "pcm_s16le"`, `sample_rate`,
`num_channels`, the endpoint/diarization flags, `language_hints`, and a `context`
object with `terms` (built-in list ∪ user terms, deduped) and `text`.

Soniox has **no "session started" event**, so the client declares the stream ready
as soon as that config frame is queued — OkHttp preserves frame order, so PCM sent
immediately afterwards still arrives after the config. Authentication failures
surface later as an `error_code` JSON response.

Two safety nets guard this phase: a 12 s `STREAM_CONNECT_TIMEOUT_MS` watchdog in
the manager, and per-connection `activeConnectionToken` fencing in the client that
mirrors the manager's session fencing.

### Step 3 — Capturing audio

`VoiceRecorder` runs one background thread:

- `AudioRecord` with `MIC` source, PCM 16-bit, 16 kHz, mono.
- Reads in 100 ms slices (`BYTES_PER_READ = 3200`). Each slice is copied and
  posted to the main thread as `onAudioChunk(pcmData)`.
- Zero-length reads are tolerated up to 50 consecutive times (5 s), after which
  the session errors out with "Microphone produced no audio data".

Alongside forwarding audio it runs **adaptive silence detection**, which is
entirely local and is the app's own VAD:

- Per-chunk RMS energy, smoothed with an EMA (`alpha = 0.2`).
- A noise floor estimated as the 20th percentile of the last 300 raw energy
  readings (30 s), recomputed once per second, allowed to rise by at most 50 per
  step but to fall instantly.
- Hysteresis: the speech threshold is `max(userThreshold + 140, floor + 260)`;
  the silence threshold is `max(userThreshold, floor + 140)`.
- `onSpeechStarted()` fires on the rising edge. `onSpeechStopped()` fires once
  the accumulated silence reaches `PREF_VOICE_CHUNK_SILENCE_SECONDS`.

In `VoiceInputManager`, each chunk goes into `pendingAudioChunks` (an
`ArrayDeque`, capped at `MAX_PENDING_AUDIO_CHUNKS = 300`, i.e. ~30 s) and is then
flushed in FIFO order when the stream is ready. Overflow drops the *oldest* chunk
and reports an error. A failed send un-marks the stream as ready, keeps the rest
of the queue intact, and schedules a reconnect so ordering is preserved.

### Step 4 — Chunk-by-chunk transcription while the user speaks

Every Soniox response is a JSON object with a `tokens` array; each token has
`text`, `is_final`, and optionally `speaker`.

`SonioxTranscriptionClient.buildSegmentFromFinalTokens()` reduces one response to
at most one `TranscriptSegment`:

1. **Only `is_final: true` tokens are used.** Non-final partials are discarded
   outright — the IME never shows speculative text, so it never has to retract
   anything. This is the single most consequential design decision in the
   pipeline.
2. Soniox's control markers `<end>` (emitted after each endpoint detection) and
   `<fin>` (emitted after each manual finalize) are filtered out. They arrive as
   ordinary-looking final tokens and would otherwise be typed literally into the
   user's text field.
3. When diarization is on, the client locks onto the first non-empty `speaker`
   label it sees and drops tokens from any other speaker.
4. Surviving token texts are **concatenated verbatim** and then trimmed. Soniox
   encodes inter-word whitespace as its own space tokens, so no space injection is
   needed at this layer.
5. `attachesToPrevious` is computed, and it is the mechanism that keeps chunked
   dictation from looking chunked:
   - `true` if the trimmed text starts with punctuation that hugs the previous
     word (`. , ! ? : ; ) ] } %`).
   - `true` if the previous segment ended on a "wordy" character
     (letter/digit/apostrophe/hyphen) **and** this response's raw text does not
     start with whitespace. That combination is Soniox signalling a mid-word
     split — finalizing inside `heading` yields `head` then `ing`, and without
     this rule the IME would type `head ing`. The tail state is carried across
     responses in `lastFinalTokenTailIsWordy`.

The segment is posted to the main thread and handed to
`VoiceInputManager.enqueueTranscript()`, which appends it to `pendingTranscripts`
and drains that queue **strictly FIFO** into the listener. If the queue somehow
reaches 64 entries, the two oldest are merged (with the same
attach/space rule) rather than reordered or dropped — order is never sacrificed.
The first delivery in a drain triggers `onProcessingStarted()` (spinner on); a
fully drained pipeline triggers `onProcessingIdle()` (spinner off).

### Step 5 — Inserting each chunk into the editor

`LatinIME`'s `onTranscriptionResult(text, attachesToPrevious)` runs two phases.

**Phase A — `prepareVoiceTranscriptionText()` (pure text shaping).** Skipped
entirely when the chunk attaches to the previous one. Otherwise:

1. `TranscriptPostProcessor.adjustLeadingCasing(chunk, 16 chars before cursor)`
   lowercases the first letter when the editor context proves we are *not* at a
   sentence boundary. Realtime STT capitalizes every utterance's first word, which
   is wrong when dictating into the middle of a sentence. Exceptions keep
   `I`/`I'm`/…, single letters, all-caps acronyms, and internal-uppercase words
   like `iPhone`. The known cost is that a proper noun starting a mid-sentence
   chunk gets lowercased.
2. `stripTrailingPunctuationIfMidSentence(chunk, 16 chars after cursor)` drops a
   trailing `.`/`!`/`?` when the next visible character is lowercase.
3. A single separator space is prepended unless the preceding character is
   whitespace or an opening delimiter.

**Phase B — `commitVoiceTranscriptionText()` (editor mutation).** Everything
happens inside **one** `beginBatchEdit()`/`endBatchEdit()` pair:

1. `mInputLogic.finishInput()` — clears `WordComposer`'s composing state. This is
   mandatory: `commitText()` clears the connection's composing region, and if
   `WordComposer` still believed it was composing, later backspaces would edit a
   phantom buffer instead of real text. Voice is therefore a *deliberate bypass*
   of the `EditorWordMirror` path that normal typing uses.
2. `mConnection.commitText(text, 1)` — inserts at the caret, replacing any
   selection (matching normal typing behaviour).
3. `runTranscriptPostProcessing()` — reads the current paragraph (from the last
   newline to the cursor, up to `EDITOR_CONTENTS_CACHE_SIZE` chars), runs
   `TranscriptPostProcessor.processCurrentParagraph()`, and if any rule matched,
   replaces the paragraph via `deleteTextBeforeCursor()` + `commitText()`.
   Rules cover comma-attached fillers (`um,`, `uh,`), disfluency fragments, and
   spelled-out punctuation/structure commands (`Comma.`, `Question mark.`,
   `New paragraph.`, …), matched case-sensitively in sentence form and applied
   longest-first.

The single batch edit matters for correctness, not just efficiency: it collapses
the commit and the post-processing rewrite into **one** `onUpdateSelection`
callback whose position matches `mExpectedSelStart`. Without it, the intermediate
callback would look like a foreign cursor move and the guard described in Step 9
would kill the recording session mid-dictation.

### Step 6 — What actually causes a chunk to be finalized

Three independent triggers, which is why dictation feels chunked the way it does:

1. **Server endpoint detection** — when `enable_endpoint_detection` is on, Soniox
   finalizes once it *semantically* decides the utterance ended (intonation,
   pauses, context). `max_endpoint_delay_ms` is only the **upper** bound on that
   wait, so raising it to 3000 does not stop early sentence endings; it only makes
   the worst case slower.
2. **Manual finalize on local silence** — because the IME only commits `is_final`
   tokens, a delayed-or-never server endpoint would leave the trailing phrase
   stranded. So when `VoiceRecorder` reports `onSpeechStopped`,
   `VoiceInputManager.requestManualFinalizeOnSilence()` sends
   `{"type":"finalize"}`; Soniox re-emits everything pending as final (plus a
   filtered `<fin>`) and keeps the session open. Gated to once per speech-stop
   transition and re-armed on the next `onSpeechStarted`. With endpoint detection
   turned off this becomes the *only* finalization trigger, which makes the user's
   pause length — not the model's guess — decide sentence boundaries.
3. **End of stream** — see Step 7.

A separate, longer `autoStopSilenceRunnable`
(`PREF_VOICE_AUTO_STOP_SILENCE_SECONDS`, default 30 s) stops the whole session
after prolonged silence. It is cancelled on `onSpeechStarted` and re-armed on
`onSpeechStopped`.

A `VOICE_RESPONSE` watchdog (6 s) logs, but does not act on, cases where audio was
sent or a finalize was requested and no final tokens came back.

### Step 7 — The user stops recording

Tapping the mic again while `RECORDING` calls
`stopRecordingInternal(cancelPending = false)`, the **graceful** path:

1. Auto-stop and connect-timeout timers are cancelled.
2. `isSessionStopping = true` and pending reconnects are cancelled — but the
   session id is **not** invalidated, so in-flight transcripts still count.
3. `voiceRecorder.stopRecording()` stops `AudioRecord` and joins the recording
   thread (2 s timeout).
4. `finalizeStreamingSession()` is posted to the main thread *behind* any queued
   `onAudioChunk` callbacks, so the tail audio is not dropped.
5. State goes to `IDLE`: red tint cleared, cancel/pause hidden, wake lock and
   `FLAG_KEEP_SCREEN_ON` released. Crucially the processing spinner is **not**
   cleared here, because transcripts may still be arriving.

`finalizeStreamingSession()` then, if the stream is ready: flushes remaining
audio, sends `finalizeNow()` (so a phrase spoken right before the tap is not
lost), and calls `finishStreaming()`. That sends an **empty WebSocket frame**,
which is Soniox's documented end-of-stream signal; the server flushes remaining
tokens, sends `{"finished": true}`, and the client closes with code 1000. An 8 s
grace timer closes the socket anyway if `finished` never arrives.

If the socket is still connecting, `finalizeWhenStreamReady` is latched and the
finalize runs from `onStreamReady`. If the socket already died with audio still
buffered, the manager is allowed to reconnect *even while stopping*
(`shouldReconnectWhileStopping()`), and only gives up with "Final voice segment
could not be transcribed completely".

The final chunks arrive through the ordinary Step 4 → Step 5 path, and the spinner
disappears when `onProcessingIdle()` fires with nothing left queued.

### Step 8 — Cancel vs. graceful stop

| | Graceful (`stopRecording`) | Abrupt (`cancelRecording`) |
| --- | --- | --- |
| Trigger | Mic tap while recording, auto-stop timer, keyboard hidden, fullapp launch | Cancel button, user types/swipes/picks a suggestion, cursor moved away, field cleared, `onFinishInput` |
| Session id | Preserved | Incremented → all in-flight work fenced off |
| Buffered audio | Flushed and transcribed | Discarded |
| Queued transcripts | Delivered and inserted | Dropped, `onPendingProcessingCancelled()` |
| Socket | Empty frame → `finished` → close 1000 | `socket.cancel()` |

### Step 9 — Interruption guards

`LatinIME` treats *any* sign of manual interaction as a reason to stop dictating,
via `stopVoiceRecordingOnUserInput()` → `discardPendingVoiceWork()`:

- `onEvent`/`onCodeInput` for any key other than `KeyCode.VOICE_INPUT`,
  `onTextInput`, batch (glide) input, suggestion picks, and consumed hardware key
  events.
- `onUpdateSelection`, which is the subtlest of the guards. It fires only when a
  voice session is active *or* still has pending work, the selection actually
  moved, and `RichInputConnection.isBelatedExpectedUpdate()` says the move was
  **not** a delayed echo of our own edit. Even then it does not always cancel:
  - Cursor is **not** at the end of the text → discard ("cursor moved away from
    end").
  - Cursor **is** at the end and the field is now empty → discard ("text field
    cleared"), which is the message-sent case.
  - Cursor **is** at the end and the field still has text → **keep recording.**
    The user may have merely tapped the field, and the caret is still exactly
    where transcripts get inserted.

  The whole block is wrapped in a try/catch that also discards on exception —
  fail-safe rather than fail-open, since the `InputConnection` can be invalid if
  the field disappeared mid-session.
- `onFinishInput`: the insertion target is gone, so all voice work is discarded.
- Keyboard hidden with the screen still on: **graceful** stop, so already-spoken
  audio still lands.
- `launchFullappEditorActivity()`: graceful stop before the activity switch.

### Step 10 — Errors and reconnection

Recoverable stream failures schedule up to `MAX_STREAM_RECONNECT_ATTEMPTS = 3`
reconnects with exponential backoff (500 / 1000 / 2000 ms), reusing the cached
`sessionApiKey` and re-fetching prior text. Buffered audio survives the reconnect
and is replayed in order.

`isUnrecoverableError()` short-circuits that retry loop by substring-matching the
error text for auth failures, `model_not_available`, rate limits, and
billing/quota problems, and stops the session immediately instead of hammering an
invalid session.

User-visible errors become a debounced (1.5 s) `"Voice error: ..."` toast.
`SonioxTranscriptionClient.mapConnectionError()` translates HTTP status codes and
exception types into readable messages (401/403 → "Invalid Soniox API key",
429 → rate limited, `UnknownHostException` → "No internet connection", …).

---

## 4. Threading model

| Work | Thread |
| --- | --- |
| `AudioRecord` reads, RMS/VAD math | `VoiceRecorder` background thread |
| OkHttp WebSocket callbacks, JSON parsing, segment building | OkHttp dispatcher thread |
| Every callback into `VoiceInputManager` and `LatinIME` | Main thread (`Handler(Looper.getMainLooper())` + `postIfCurrent` in the client) |
| Timers (auto-stop, reconnect, connect timeout, response watchdog, finalize grace) | Main thread |
| Text insertion | Main thread, always sequential |

`VoiceInputManager` state is therefore main-thread-confined and needs no locking;
`SonioxTranscriptionClient` marks its cross-thread fields `@Volatile`.

## 5. Settings surface

All under **Settings → Transcription** (`TranscriptionScreen.kt`), plus the API
key on the Setup App checklist.

| Setting | Key | Default | Notes |
| --- | --- | --- | --- |
| Soniox API key | `soniox_api_key` | `""` | Plain (unmasked) text field; trimmed on write; clears legacy vendor keys |
| Speaker diarization | `soniox_diarization` | `true` | Locks to first speaker |
| Enable endpoint detection | `soniox_enable_endpoint_detection` | `true` | |
| Max endpoint delay | `soniox_max_endpoint_delay_ms` | `3000` | Clamped 500–3000; `AppUpgrade` migrates the old `2000` default |
| Custom voice vocabulary | `soniox_custom_terms` | `""` | One term per line, ≤200 terms, ≤100 chars each; merged with a built-in list |
| Chunk silence duration | `voice_chunk_silence_seconds` | `1` | Clamped 1–30; drives manual finalize |
| Silence threshold (RMS) | `voice_silence_threshold` | `220` | Clamped 40–5000 |
| Auto-stop silence duration | `voice_auto_stop_silence_seconds` | `30` | Clamped 5–300 |

## 6. Diagnostics

`Log.kt` maintains a filtered in-memory ring buffer for voice work: it keeps lines
from tags `VoiceInputManager`, `VoiceRecorder`, `SonioxTranscription`, plus
`LatinIME` lines containing voice markers. **Settings → Transcription → Voice
diagnostics** renders it with optional auto-refresh and exports it through SAF.
Export redacts raw transcript text (`[N chars]`) and API keys.

The pipeline is instrumented with ordered breadcrumbs:
`VOICE_STEP_1` (recording start) → `VOICE_STEP_2` (local silence) →
`VOICE_STEP_3` (socket open) → `VOICE_STEP_4` (final transcript received /
inserted), plus `VOICE_RESPONSE` latency lines.

## 7. Tests

JVM/Robolectric tests under `app/src/test/java/helium314/keyboard/latin/voice/`:

- `SonioxTranscriptionClientTest` — start-config JSON assembly, language hints,
  context terms merging/dedup, context-text truncation, final-token segment
  building, `attachesToPrevious` cases, marker filtering, diarization locking,
  error description formatting.
- `TranscriptPostProcessorTest` — spoken punctuation/structure commands, filler
  removal, `adjustLeadingCasing`, `stripTrailingPunctuationIfMidSentence`, rule
  ordering.
- `TranscriptionPreferencesTest` — legacy key cleanup, endpoint-delay clamping,
  custom-term parsing round-trip.
- `../utils/LogVoiceDiagnosticsTest` — diagnostics filtering and redaction.

---

## 8. Where Soniox is baked in

This is the coupling inventory that the
[pluggable-provider plan](pluggable-transcription-providers-plan.md) has to
dissolve. Provider knowledge is not confined to `SonioxTranscriptionClient`; it
has leaked into six layers.

| Layer | Soniox-specific today |
| --- | --- |
| `VoiceInputManager` | Holds a concrete `SonioxTranscriptionClient` field. Builds `SonioxTranscriptionClient.SessionConfig`. Reads `TranscriptionPreferences.SonioxConfig`. Calls `finalizeNow()` on local silence, assuming the provider *has* a mid-stream finalize. Error classification (`isUnrecoverableError`) matches Soniox error strings. Error messages name Soniox. |
| `SonioxTranscriptionClient` | The whole protocol: URL, model, in-body `api_key`, `tokens`/`is_final` shape, `<end>`/`<fin>` markers, empty-frame shutdown, `finished:true`, whitespace-as-tokens assumption, `attachesToPrevious` derivation. |
| `VoiceRecorder` | Fixed PCM16/16 kHz/mono output — fine for most providers, but not negotiable per provider today. |
| `LatinIME` | `buildVoiceContextText()` exists specifically to fill Soniox's `context.text`, with a Soniox-derived 4 000-char cap. Pre-commit casing/punctuation fixes assume the provider capitalizes every utterance and appends sentence punctuation. |
| Preferences | `PREF_SONIOX_*` keys, a single global API key, `SonioxConfig` as *the* config type, endpoint-delay bounds hardcoded to Soniox's 500–3000. |
| Settings UI + strings | `TranscriptionScreen` renders Soniox's exact knobs; `SonioxContextTermsScreen` reads `SonioxTranscriptionClient.defaultContextTerms()` directly; ~20 `soniox_*` string resources. |

Two structural assumptions are worth calling out separately, because they are
baked into the *contract* rather than into any one file:

1. **Finals are incremental and never repeated.** `TranscriptSegment` is an
   append-only chunk, and `commitText` is irreversible. Providers that re-send a
   growing utterance (or revise a previous final) cannot be adapted by swapping a
   client alone.
2. **Segmentation is driven by a mix of provider endpointing and our local VAD.**
   The manual-finalize-on-silence trick only exists because Soniox has a finalize
   control frame. Providers without one need a different strategy for the same
   guarantee.

## Keep this file current

- Update this document when the voice pipeline's control flow, guards, or
  threading model change.
- Provider/API specifics belong in [`soniox-transcription.md`](soniox-transcription.md);
  keep the split so this file stays readable once multiple providers exist.
