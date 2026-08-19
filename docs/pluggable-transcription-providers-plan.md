# Pluggable Transcription Providers — Technical Plan

## 1. Goal

Make the speech-to-text provider a **user-selectable plugin** instead of a
hardcoded dependency, so that HeliBoard can ship several providers side by side
and the user picks one in Settings.

The requirement is stronger than "put an interface in front of the WebSocket
client". Providers differ at *every* stage of the pipeline — authentication,
audio encoding, session configuration, result shape, how partial/final is
signalled, whether finals repeat, how to force a flush, how to close, how errors
are reported, and how much text shaping the model already did. A plugin must be
able to participate at each of those stages, while the parts that are genuinely
common (microphone capture, local voice activity detection, session fencing,
FIFO ordering, editor insertion, interruption guards) stay written once.

Read [`voice-transcription-workflow.md`](voice-transcription-workflow.md) first;
this plan assumes its vocabulary.

## 2. Why the current shape cannot absorb a second provider

Section 8 of the workflow doc has the full coupling inventory. Condensed, the
blockers are:

**Provider knowledge has leaked into six layers.** `VoiceInputManager` holds a
concrete `SonioxTranscriptionClient`, builds a
`SonioxTranscriptionClient.SessionConfig`, reads a `SonioxConfig` preference
bundle, classifies Soniox error strings, and names Soniox in user-facing error
text. `LatinIME.buildVoiceContextText()` exists to fill Soniox's `context.text`
with a Soniox-derived 4 000-char cap. `TranscriptionScreen` renders Soniox's exact
knobs; `SonioxContextTermsScreen` calls
`SonioxTranscriptionClient.defaultContextTerms()` directly. Preference keys are
all `PREF_SONIOX_*`, with one global API key and Soniox's 500–3000 ms endpoint
bounds hardcoded in `TranscriptionPreferences`.

**Two assumptions are baked into the contract, not into a file.** These are the
ones that actually make a naive swap fail:

1. *Finals are incremental and never repeated.* `TranscriptSegment` is an
   append-only chunk and `commitText()` is irreversible. Providers that re-send a
   growing utterance, or revise an already-final span, break this silently — as
   duplicated text in the user's editor.
2. *Segmentation is co-driven by our local VAD and a provider finalize control
   frame.* `requestManualFinalizeOnSilence()` only works because Soniox has
   `{"type":"finalize"}`. A provider without an equivalent needs a different
   mechanism to deliver the same "your trailing phrase always lands" guarantee.

Any design that does not solve those two problems explicitly is a re-skin, not an
abstraction.

## 3. Target architecture

### 3.1 Layers

```
┌───────────────────────────────────────────────────────────────┐
│ LatinIME                    provider-agnostic                 │
│  insertion, guards, wake lock, editor-context supplier        │
└───────────────────────────┬───────────────────────────────────┘
                            │ TranscriptSegment (unchanged shape)
┌───────────────────────────┴───────────────────────────────────┐
│ VoiceInputManager           provider-agnostic                 │
│  state machine, session fencing, audio buffer, FIFO queue,    │
│  timers, reconnect policy, finalization POLICY                │
└──────┬─────────────────────────┬──────────────────────────────┘
       │                         │
┌──────┴────────────┐  ┌─────────┴─────────────────────────────┐
│ VoiceRecorder     │  │ normalize/                            │
│ + audio/          │  │  TranscriptAssembler  (deltas)        │
│ AudioEncoder      │  │  SegmentJoiner        (spacing)       │
│ (format adapt)    │  │  TextShapingPipeline  (casing/punct.) │
└───────────────────┘  └─────────┬─────────────────────────────┘
                                 │ TranscriptionEvent
┌────────────────────────────────┴──────────────────────────────┐
│ provider/  TranscriptionProvider + TranscriptionSession        │
│            capabilities, settings schema, credentials          │
├───────────────────────────────────────────────────────────────┤
│ providers/soniox   providers/deepgram   providers/…            │
├───────────────────────────────────────────────────────────────┤
│ provider/transport/  WebSocketTransport │ HttpTransport │ Fake │
└───────────────────────────────────────────────────────────────┘
```

Proposed file layout under `app/src/main/java/helium314/keyboard/latin/voice/`:

```
VoiceInputManager.kt          unchanged responsibilities, provider-agnostic
VoiceRecorder.kt              unchanged
TranscriptSegment.kt          unchanged
TranscriptPostProcessor.kt    becomes profile-driven
audio/
  AudioFormatSpec.kt          encoding, sample rate, channels, frame size
  AudioEncoder.kt             + Pcm16Passthrough, Downsampler, Base64Json, Opus
normalize/
  TranscriptAssembler.kt      snapshot→delta, dedupe, revision policy
  SegmentJoiner.kt            attachesToPrevious decision
  TextShapingPipeline.kt      wraps TranscriptPostProcessor by profile
provider/
  TranscriptionProvider.kt    descriptor + factory
  TranscriptionSession.kt     per-session control surface
  TranscriptionEvent.kt       sealed normalized event stream
  TranscriptionRequest.kt     neutral session request
  ProviderCapabilities.kt     what this provider supports
  ProviderSetting.kt          declarative settings schema
  ProviderCredentials.kt      static key or minted ephemeral token
  TranscriptionFailure.kt     neutral error classification
  TranscriptionProviderRegistry.kt
  transport/
    TranscriptionTransport.kt WebSocket/HTTP seam (enables fake transports)
providers/
  soniox/    SonioxProvider.kt  SonioxSession.kt  SonioxProtocol.kt
  deepgram/  …
```

### 3.2 Core contracts

Sketches below are illustrative, not final. They intentionally keep the existing
**Handler/callback** style rather than introducing coroutines into the voice core;
coroutines stay available *inside* a plugin (e.g. for minting a token) but the
main-thread-confined, fenced callback model that makes `VoiceInputManager`
lock-free is worth preserving. Rewriting concurrency and introducing plugins in
one change would make regressions hard to attribute.

**Provider descriptor and factory.** Cheap to construct — it is queried to build
the settings UI, so it must not touch the network.

```kotlin
interface TranscriptionProvider {
    val id: String                       // stable, used in pref keys: "soniox"
    val displayNameRes: Int
    val capabilities: ProviderCapabilities
    val settingsSchema: List<ProviderSetting>

    /** Blocking-free validation used to gate the mic button and show setup hints. */
    fun readiness(prefs: SharedPreferences): ProviderReadiness

    fun createSession(context: Context, prefs: SharedPreferences): TranscriptionSession
}
```

**Capabilities — the negotiation surface.** Every field here exists because some
real provider forces the pipeline to behave differently.

```kotlin
data class ProviderCapabilities(
    val mode: TranscriptionMode,              // STREAMING | SEGMENTED
    val audioFormats: List<AudioFormatSpec>,  // preference order
    val finalization: FinalizationSupport,    // manual flush? server endpointing?
    val resultDelivery: ResultDelivery,       // INCREMENTAL_FINALS | CUMULATIVE_UTTERANCE
    val whitespace: WhitespaceConvention,     // TOKENS_CARRY_WHITESPACE | JOIN_WITH_SPACE
    val textShaping: TextShapingProfile,      // what the model already did to the text
    val supportsPriorTextBias: Boolean,
    val maxPriorTextChars: Int,
    val supportsVocabularyBias: Boolean,
    val maxVocabularyTerms: Int,
    val supportsDiarization: Boolean,
    val supportsLanguageHints: LanguageHintSupport, // NONE | SINGLE | MULTIPLE | AUTO_DETECT
    val requiresNetwork: Boolean,             // false for on-device plugins
)
```

**Session — the per-recording control surface.** One instance per recording
session; the manager never reuses one across sessions, which removes a class of
state-leak bugs that the current `activeConnectionToken` juggling exists to
prevent.

```kotlin
interface TranscriptionSession {
    /** Negotiated from capabilities + what VoiceRecorder can produce. */
    val audioFormat: AudioFormatSpec

    /**
     * Resolve credentials (may perform a network call to mint an ephemeral
     * token) and open the stream. Must return immediately; progress is
     * reported through [TranscriptionEvent].
     */
    fun start(request: TranscriptionRequest, sink: (TranscriptionEvent) -> Unit)

    /** @return false when the frame could not be queued (triggers reconnect). */
    fun sendAudio(frame: ByteArray): Boolean

    /** Best-effort mid-stream flush. No-op returning false when unsupported. */
    fun requestFinalize(): Boolean

    /** Graceful end-of-stream; provider owns its own handshake. */
    fun finish()

    /** Hard teardown; must be safe to call at any time, repeatedly. */
    fun cancel()
}
```

**Neutral request.** Assembled once by `VoiceInputManager` and passed through on
reconnects, with the plugin free to ignore anything it does not support.

```kotlin
data class TranscriptionRequest(
    val languageHints: List<String>,
    val priorText: String?,          // already capped to maxPriorTextChars
    val vocabularyTerms: List<String>,
    val diarizationEnabled: Boolean,
    val isReconnect: Boolean,
)
```

**Normalized event stream.** A sealed hierarchy replaces the five-method
`StreamingCallback`, so adding a concept later does not break every plugin.

```kotlin
sealed interface TranscriptionEvent {
    /** Provider is configured and ready to accept audio. */
    data object StreamReady : TranscriptionEvent

    /**
     * Text the provider considers stable.
     *  - INCREMENTAL_FINALS providers emit only text never sent before.
     *  - CUMULATIVE_UTTERANCE providers emit the whole current utterance every
     *    time; TranscriptAssembler turns that into deltas.
     */
    data class Transcript(
        val text: String,
        val kind: TranscriptKind,           // DELTA | UTTERANCE_SNAPSHOT
        val utteranceId: Long,              // increments on each utterance boundary
        val rawStartsWithWhitespace: Boolean,
        val isUtteranceComplete: Boolean,
    ) : TranscriptionEvent

    /** Provider acknowledged a flush/endpoint without necessarily new text. */
    data class FinalizeAcknowledged(val producedText: Boolean) : TranscriptionEvent

    data class Failed(val failure: TranscriptionFailure) : TranscriptionEvent

    data object Closed : TranscriptionEvent
}
```

**Neutral failures.** The manager must never substring-match provider error
prose again. Each plugin classifies its own errors; the manager only reads the
class.

```kotlin
data class TranscriptionFailure(
    val category: Category,
    val userMessageRes: Int,          // localized, provider-neutral wording
    val diagnostic: String,           // logged, redacted, never shown raw
) {
    enum class Category {
        AUTH,            // fatal: bad/expired key → stop, prompt setup
        QUOTA_OR_BILLING, // fatal
        RATE_LIMITED,    // fatal for this session
        CONFIG_REJECTED, // fatal: model/param unsupported
        NETWORK,         // retryable
        TRANSPORT,       // retryable
        AUDIO,           // local mic problem
        UNKNOWN,         // retryable, capped
    }
}
```

**Declarative settings.** The Transcription screen renders whatever the selected
provider declares, so adding a provider needs no new Compose code.

```kotlin
sealed interface ProviderSetting {
    val key: String            // stored as "voice.<providerId>.<key>"
    val titleRes: Int
    val summaryRes: Int?

    data class Secret(...) : ProviderSetting            // API key, masked
    data class Toggle(..., val default: Boolean) : ProviderSetting
    data class IntRange(..., val min: Int, val max: Int, val default: Int) : ProviderSetting
    data class Choice(..., val options: List<Option>) : ProviderSetting
    data class MultilineList(..., val maxEntries: Int, val maxEntryLength: Int) : ProviderSetting
    data class SubScreen(..., val route: String) : ProviderSetting
}
```

### 3.3 The three hard problems

Everything above is plumbing. These three are the actual design work.

**Problem 1 — cumulative vs. incremental finals.** Soniox never repeats a final
token. Other providers re-send a growing utterance string on every update and
mark the end of turn separately, so naively committing each message would type
`Hello` / `Hello world` / `Hello world today`.

*Solution:* `TranscriptAssembler`, owned by `VoiceInputManager`, not by plugins.
It tracks, per `utteranceId`, how many characters have already been committed. For
`UTTERANCE_SNAPSHOT` events it emits only the suffix beyond the committed prefix.
For `DELTA` events it passes through. Rules:

- Snapshot shorter than what was committed, or diverging inside the committed
  prefix, means the provider **revised already-committed text**. Default policy is
  `IGNORE_REVISIONS`: keep the committed text, log a `VOICE_REVISION` diagnostic,
  and resume from the new snapshot's end. Rewriting the editor is unsafe because
  the user may have moved the caret or edited in between, and our own
  post-processing has already rewritten the paragraph.
- An `isUtteranceComplete` event closes the utterance and resets the prefix.
- Assembler state is part of the fenced session state and is cleared by
  `invalidateActiveSession()`.

This is the component that lets a provider whose protocol is structurally unlike
Soniox's plug in without touching `LatinIME`.

**Problem 2 — spacing and word-splitting.** `attachesToPrevious` is currently
derived from Soniox's "whitespace arrives as its own tokens" convention. A
provider that emits bare words needs spaces synthesized instead.

*Solution:* move the decision into `SegmentJoiner`, driven by
`WhitespaceConvention` plus the `rawStartsWithWhitespace` flag that each plugin
reports. `TOKENS_CARRY_WHITESPACE` reproduces today's exact logic (including the
`previousTailIsWordy` mid-word-continuation rule). `JOIN_WITH_SPACE` inserts a
separator between words and keeps only the punctuation-attachment rule. The
existing `SonioxTranscriptionClientTest` cases for `attachesToPrevious` move here
essentially unchanged, which is a useful parity check.

**Problem 3 — finalization guarantee without a finalize frame.** The
"trailing phrase always lands" guarantee currently depends on Soniox's finalize
control message.

*Solution:* `VoiceInputManager` picks a policy from `FinalizationSupport`:

| Provider support | Policy on local `onSpeechStopped` |
| --- | --- |
| Manual flush available | Today's behaviour: send the flush, once per speech-stop transition. |
| Server endpointing only | Do nothing; rely on the server. Arm a longer watchdog and, if nothing finalizes, close and reopen the stream to force a flush (`STREAM_CYCLE_ON_STALL`). |
| Neither (`SEGMENTED` mode) | Treat the silence boundary as an utterance boundary: finish the current request, deliver its result, and open the next request on the following speech onset. |

`SEGMENTED` mode is what makes chunked-HTTP and on-device engines expressible at
all. The local VAD already produces exactly the boundaries such engines need, so
this is a scheduling change in the manager rather than new machinery.

### 3.4 Which capability changes which pipeline step

The point of the capability object is that each field has one consumer. Reviewers
should be able to check that mapping.

| Capability | Consumed by | Effect |
| --- | --- | --- |
| `mode` | `VoiceInputManager` | Continuous stream vs. per-utterance request cycling |
| `audioFormats` | `audio/AudioEncoder` selection | Which encoder sits between recorder and session |
| `finalization` | `VoiceInputManager` | Which policy from the table in 3.3 |
| `resultDelivery` | `TranscriptAssembler` | Pass-through vs. prefix diffing |
| `whitespace` | `SegmentJoiner` | How `attachesToPrevious` is computed |
| `textShaping` | `TextShapingPipeline` | Whether leading-casing fix, trailing-punctuation strip, and spoken-punctuation rules run |
| `supportsPriorTextBias`, `maxPriorTextChars` | `LatinIME.buildVoiceContextText` | Whether to read editor context at all, and how much |
| `supportsVocabularyBias`, `maxVocabularyTerms` | Settings UI + request builder | Whether the vocabulary screen is offered |
| `supportsDiarization` | Settings UI + request builder | Whether the toggle is shown |
| `supportsLanguageHints` | Request builder | Omit / single code / list / auto |
| `requiresNetwork` | `VoiceInputManager`, readiness UI | Skip reconnect logic and network error paths |

`TextShapingProfile` deserves a note: today's `adjustLeadingCasing` and
`stripTrailingPunctuationIfMidSentence` exist because Soniox capitalizes every
utterance and appends sentence punctuation. A provider configured *without* smart
formatting would have those corrections applied to text that never needed them.
Making the profile explicit (`capitalizesUtteranceStart`,
`appendsSentencePunctuation`, `emitsSpokenPunctuationLiterally`) turns that from
an accident into a declared property.

## 4. Anatomy of a provider plugin

A new provider means one package under `providers/<id>/` containing: a
`TranscriptionProvider` (descriptor, capabilities, settings schema, readiness),
a `TranscriptionSession` (credentials, wire encode/decode, control frames,
shutdown), and a protocol object holding pure, testable request-building and
response-parsing functions — the pattern `SonioxTranscriptionClient`'s companion
object already uses, and the reason its tests are fast and network-free.

Registration is one line in `TranscriptionProviderRegistry`. A plain object with
a static list, not a DI framework or reflection-based discovery: the registry is
touched during IME startup and in direct-boot conditions, so startup cost and
predictability matter more than elegance.

**Conformance checklist** for a new plugin — each item corresponds to a shared
test in the contract suite (Section 6):

1. Declares capabilities that match observed behaviour (verified by fixtures).
2. Never emits a `Transcript` the provider had not marked stable.
3. Filters provider control tokens/markers out of user-visible text.
4. Classifies auth, quota, rate-limit, and config errors as fatal categories and
   transient socket/DNS failures as retryable.
5. `cancel()` is idempotent and safe before `start()`, mid-stream, and after
   `Closed`.
6. Emits exactly one terminal event (`Closed` or `Failed`) per session.
7. Redacts credentials from every log line and diagnostic string.
8. Applies its own documented caps (prior text, vocabulary size) defensively
   rather than trusting the caller.

## 5. Preferences and settings UI

**New keys.**

- `voice_provider` (string, default `"soniox"`) — the selected provider id.
- `voice.<providerId>.<settingKey>` — per-provider settings, so two providers can
  each hold their own API key and the user can switch back and forth without
  re-entering credentials.

**Migration.** `AppUpgrade` copies the existing values forward once:
`soniox_api_key` → `voice.soniox.api_key`, and likewise for
`soniox_enable_endpoint_detection`, `soniox_max_endpoint_delay_ms`,
`soniox_diarization`, `soniox_custom_terms`. `voice_provider` defaults to
`soniox`, so an upgrading user sees no change. The three local capture settings
(`voice_chunk_silence_seconds`, `voice_silence_threshold`,
`voice_auto_stop_silence_seconds`) are **not** namespaced — they configure our
microphone and VAD, not a provider, and should persist across provider switches.

**UI.** `TranscriptionScreen` becomes: a provider picker, a readiness banner
(missing key / missing permission), a generic renderer over
`settingsSchema`, then the unchanged local capture section. The
Soniox-specific composables collapse into schema entries; the custom-vocabulary
screen becomes a generic `MultilineList` sub-screen parameterized by provider,
replacing `SonioxContextTermsScreen`'s direct call into
`SonioxTranscriptionClient.defaultContextTerms()`.

Two things to fix while in there: the API key field is currently a plain
unmasked `OutlinedTextField` (`ProviderSetting.Secret` should render masked with a
reveal toggle), and the strings become provider-neutral (`soniox_api_key_title` →
a generic "API key" title plus a per-provider display name and help URL supplied
by the descriptor).

**Security note.** Every network provider here requires a long-lived secret in
client-side `SharedPreferences`. That is the status quo and this plan does not
change it, but it is worth designing the credentials seam
(`ProviderCredentials`, with support for exchanging a key for a short-lived
token before connecting) so that a future "relay through my own endpoint" plugin
is expressible without another refactor.

## 6. Testing strategy

The risk in this refactor is not a crash — it is **silent text-quality
regression**, where dictation still works but spacing, casing, or chunk joining
degrade subtly. So the tests need to assert on final editor text, not on internal
calls.

1. **Transport seam + fake transport.** Introduce `TranscriptionTransport` so a
   session can be driven by a scripted fake that replays recorded provider
   messages. This is what makes everything below possible offline.
2. **Provider contract suite.** An abstract test class parameterized over
   `(provider, fixture set)` asserting the conformance checklist in Section 4.
   Every plugin must pass it. New providers get correctness pressure for free.
3. **Golden end-to-end transcript tests.** Feed a recorded provider message
   sequence through assembler → joiner → shaping → a fake `InputConnection`, and
   assert the exact resulting editor string. Capture goldens for the current
   Soniox behaviour **before** refactoring; Phase 0 is done when they all still
   pass byte-for-byte.
4. **Assembler unit tests.** Snapshot growth, snapshot shrink, divergence inside
   the committed prefix, utterance boundaries, interleaved deltas, and
   session-reset clearing.
5. **Migrated tests.** `attachesToPrevious` cases move to `SegmentJoinerTest`;
   start-config assembly stays in `SonioxProtocolTest`;
   `TranscriptionPreferencesTest` grows namespacing and migration cases.
6. **Manual device verification** per provider: dictate a multi-sentence
   paragraph, dictate mid-sentence into existing text, pause mid-word, stop while
   speaking, kill the network mid-session, and use a deliberately invalid key.
   These are the paths that fixtures model least well.

## 7. Phased delivery

Each phase is independently shippable and leaves the app working.

**Phase 0 — extract the seams, change no behaviour.** Add the `provider/`,
`normalize/`, and `audio/` packages. Move Soniox into
`providers/soniox/` behind the new interfaces, with capabilities describing
exactly what it does today (`STREAMING`, `INCREMENTAL_FINALS`,
`TOKENS_CARRY_WHITESPACE`, manual flush supported). `VoiceInputManager` talks only
to `TranscriptionSession`. No new preferences, no UI change. Gate: the golden
transcript tests from Section 6 pass unchanged, and the existing voice test suite
passes.

*This is the phase that carries almost all the risk, and it is the one with no
user-visible payoff — which is exactly why the goldens must be captured before it
starts.*

**Phase 1 — registry and declarative settings.** Add
`TranscriptionProviderRegistry`, the `voice_provider` preference, namespaced keys,
`AppUpgrade` migration, and the generic settings renderer. Still only Soniox
registered, so the picker shows one entry. Gate: an upgrading user's existing key
and settings survive, verified by a migration test.

**Phase 2 — normalization hardening.** Land `TranscriptAssembler`,
`SegmentJoiner`, and `TextShapingPipeline` as real components with their own
tests, and route Soniox through them (Soniox exercises only the pass-through
paths). Gate: goldens unchanged; assembler tests cover the cumulative cases no
shipped provider uses yet.

**Phase 3 — second provider, architecturally similar.** Pick a WebSocket provider
that accepts raw PCM, emits incremental finals, and has both a flush control
message and an explicit close message. This validates the seams with the least
new machinery, and it is the first phase with user-visible value. Gate: the
contract suite passes for the new provider on fixtures, plus the manual device
checklist.

**Phase 4 — second provider, architecturally different.** Pick one that forces
the hard paths: cumulative utterance snapshots (exercises the assembler),
base64-in-JSON audio framing (exercises `AudioEncoder`), and/or an ephemeral token
exchange before connecting (exercises `ProviderCredentials`). Gate: no changes
needed in `VoiceInputManager` or `LatinIME` to land it. If either needs changing,
the abstraction was wrong and should be fixed here rather than papered over.

**Phase 5 — segmented and on-device.** Implement `SEGMENTED` mode and add an
offline engine. This is the phase that proves the abstraction is not merely
"several WebSocket vendors" and gives the feature a no-network, no-API-key story.

Phases 0–2 are internal refactoring with test-only visible output; 3–5 each add a
user-facing provider. The ordering deliberately front-loads the risk and defers
provider breadth.

## 8. What real provider APIs demand from the abstraction

*Provider survey pending — to be filled from current (2026) API documentation
before Phase 3 provider selection is finalized. It should record, per candidate:
transport, auth mechanism (including whether an ephemeral token exchange is
required), accepted audio encodings and framing, result message shape, the
partial/final signal, whether finals repeat, the flush and close control
messages, vocabulary/context biasing fields, endpointing controls, diarization,
error shape, and session limits.*

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Silent text-quality regression during Phase 0 | Goldens captured before refactoring; Phase 0 gate is byte-identical output |
| Abstraction fits only WebSocket vendors | `SEGMENTED` mode and an on-device plugin are explicit phases, not "future work"; Phase 4's gate is "no core changes needed" |
| Providers needing an ephemeral token can't be used from a client | `ProviderCredentials` supports a pre-connect exchange, run off the audio critical path since audio already buffers |
| Duplicated or lost text from cumulative providers | `TranscriptAssembler` with an explicit, tested revision policy rather than per-plugin ad-hoc diffing |
| APK size and dependency creep from vendor SDKs | Prefer raw protocol over vendor SDKs (OkHttp 4.12 already covers WebSocket and HTTP); note that gRPC-only providers would be a significant dependency addition and may be out of scope. `minSdk = 21` also constrains codec availability |
| Secrets for several providers in client prefs | Masked entry, redaction in diagnostics, and a credentials seam that leaves room for a relay-based plugin |
| Concurrency regressions | Keep the main-thread-confined fenced callback model; do not mix a coroutine rewrite into this work |
| Scope creep into the UI | Phase 1 delivers the generic renderer once; no per-provider Compose code is allowed afterwards |

## 10. Non-goals

- Running multiple providers simultaneously or racing them for lower latency.
- Automatic provider fallback on failure. The plumbing makes it possible later;
  choosing failover semantics is a separate product decision.
- Replacing the toolbar `VOICE` key's hand-off to the system voice IME. That is a
  different feature and stays as is.
- Changing the append-only insertion contract with the host editor. Committed text
  stays committed; providers must adapt to that, not the reverse.
- Migrating the voice pipeline to coroutines.

## Keep this file current

- Update this plan as phases land, and record deviations rather than silently
  diverging from it.
- When a phase completes, fold the resulting architecture into
  [`voice-transcription-workflow.md`](voice-transcription-workflow.md) and the
  folder-local `AGENTS.md` files.
