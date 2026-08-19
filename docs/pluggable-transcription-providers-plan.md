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
  ProviderCredentials.kt      CredentialFlow: static key, minted token, REST init
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
    val credentialFlow: CredentialFlow,       // see 3.2.1
    val audioFormats: List<AudioFormatSpec>,  // preference order; includes framing
    val finalDelivery: FinalDelivery,         // see 3.3 Problem 1 — four modes, not two
    val manualFinalize: ManualFinalizeSupport,// UNSUPPORTED | FLUSH_AND_CONTINUE | ENDS_SESSION
    val serverEndpointing: Boolean,           // provider signals end-of-speech itself
    val keepAlive: KeepAlivePolicy,           // None | Message(interval, payload)
    val maxSessionDuration: Duration?,        // null = effectively unbounded
    val whitespace: WhitespaceConvention,     // TOKENS_CARRY_WHITESPACE | PROVIDER_RENDERED_TEXT
    val textShaping: TextShapingProfile,      // what the model already did to the text
    val priorTextBias: BiasSupport,           // Unsupported | Supported(maxChars)
    val vocabularyBias: VocabularyBiasKind,   // Unsupported | Terms | WeightedPhrases | Prompt
    val maxVocabularyTerms: Int,
    val diarization: DiarizationSupport,      // Unsupported | Stable | MayReviseLabels
    val languageHints: LanguageHintSupport,   // None | Single | Candidates(max) | CodeSwitching
    val requiresNetwork: Boolean,             // false for on-device plugins
)
```

Six of these fields exist only because the provider survey in Section 8 proved a
boolean was not enough. `manualFinalize` is three-valued because Google, Azure,
and Gladia cannot flush-and-continue at all — the closest equivalent ends the
session. `keepAlive` exists because Deepgram closes an idle socket within roughly
10 seconds unless a `KeepAlive` message arrives, and Soniox requires one every 20
seconds while no audio flows. `maxSessionDuration` exists because Google caps a
stream at five minutes and OpenAI at sixty, which forces mid-dictation rollover
rather than being an edge case. `diarization` distinguishes stable labels from
providers that revise earlier speaker assignments. `vocabularyBias` is an enum
because weighted phrases, keyterm lists, and free-text prompts are semantically
different features that happen to share a purpose.

#### 3.2.1 Credentials are a two-step, provider-owned flow

Sending a long-lived API key straight from the client is the *exception* among
current providers, not the norm. AssemblyAI expects a one-use token, Gladia
returns a temporary WebSocket URL from a REST init call (which also carries the
session configuration, so config and credentials arrive together), OpenAI expects
an ephemeral client secret, Speechmatics a temporary JWT, and Azure a refreshable
authorization token. Google's credentials cannot safely live in an app at all.

So `CredentialFlow` must cover: `StaticKeyInFirstMessage` (Soniox today),
`StaticKeyInHeader` (Deepgram), `MintedToken` (exchange a stored key for a
short-lived credential before connecting), and `ProviderInitCall` (a REST call
returns both the connect URL and the session handle). The minting step is a
suspend call inside the plugin, run from `start()` — it sits off the audio
critical path because audio is already buffering, which is a property today's
architecture gives us for free.

This also means the honest framing of the security question is not "should we
store a key" but "several providers are only safely usable with a backend to mint
tokens". A relay-style plugin should stay expressible for exactly that reason.

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
     * Text the provider considers stable. [revisionKey] identifies the segment
     * this text belongs to, so REPLACE_REVISION and RECONCILE_ITEM providers can
     * supersede their own earlier output; APPEND_* providers mint a fresh key
     * per segment. Interpretation is governed by capabilities.finalDelivery.
     */
    data class Transcript(
        val text: String,
        val revisionKey: String,
        val rawStartsWithWhitespace: Boolean,
        val isSegmentComplete: Boolean,
    ) : TranscriptionEvent

    /**
     * The provider believes the speaker stopped. Deliberately separate from
     * transcript stability: Deepgram's `speech_final` and `is_final` are
     * different things, and conflating them either drops text or commits text
     * that is still subject to change.
     */
    data class Endpoint(val cause: EndpointCause) : TranscriptionEvent

    /** A flush was acknowledged, with or without new text. */
    data class FinalizeAcknowledged(val producedText: Boolean) : TranscriptionEvent

    /** Provider revised earlier speaker assignments. Advisory; never rewrites committed text. */
    data class SpeakerRevision(val revisions: Map<String, String>) : TranscriptionEvent

    data class Failed(val failure: TranscriptionFailure) : TranscriptionEvent

    data object Closed : TranscriptionEvent
}
```

`revisionKey` is deliberately an opaque provider-supplied string rather than a
monotonic counter. A counter would assume segments complete in order, which is not
safe: OpenAI does not guarantee completion ordering across turns and keys results by
`item_id`. An opaque key covers AssemblyAI's `turn_order`, Gladia's `data.id`, and
OpenAI's `item_id` without the core needing to know which is which.

`SpeakerRevision` is advisory only. AssemblyAI can revise earlier speaker labels
and Google's diarization can re-emit words from the start of the stream, but our
insertion contract is append-only — so a revision may change which *future* tokens
we accept, never text already committed to the host editor.

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

### 3.3 The hard problems

Everything above is plumbing. These four are the actual design work, and each is
solved once in shared code rather than being re-solved (or quietly ignored) by every
plugin.

**Problem 1 — "final" means four different things.** This is the single most
important finding from the provider survey, and it is why a `Boolean isFinal`
cannot be the abstraction. Current providers divide into four delivery modes:

| Mode | Providers | What arrives |
| --- | --- | --- |
| `APPEND_TOKENS` | Soniox | Final tokens, never repeated, whitespace carried as its own tokens |
| `APPEND_TEXT` | Deepgram, Google, Azure, Speechmatics | Final *segments* of already-rendered text, never repeated |
| `REPLACE_REVISION` | AssemblyAI, Gladia | Each message **supersedes** the previous one for the same turn/utterance id |
| `RECONCILE_ITEM` | OpenAI, Together | Appendable deltas, then one `completed` message repeating the whole item |

Committing naively across these produces different bugs: `REPLACE_REVISION` types
`Hello` / `Hello world` / `Hello world today`, and `RECONCILE_ITEM` types the whole
utterance twice (once as deltas, once as the completed transcript).

*Solution:* `TranscriptAssembler`, owned by `VoiceInputManager`, not by plugins. It
tracks per `revisionKey` how many characters have already been emitted downstream,
and reduces all four modes to a stream of append-only deltas:

- `APPEND_TOKENS` / `APPEND_TEXT` — pass through; each key is seen once.
- `REPLACE_REVISION` — emit only the suffix beyond what was already emitted for
  that key.
- `RECONCILE_ITEM` — emit deltas as they arrive; when the `completed` message for
  the same key arrives, emit only its unseen suffix (normally nothing). This is
  the same prefix arithmetic as `REPLACE_REVISION`, which is why one component
  covers both.

The awkward case is a message that **contradicts** already-emitted text — shorter
than the emitted prefix, or diverging inside it. Default policy is
`IGNORE_REVISIONS`: keep what was committed, log a `VOICE_REVISION` diagnostic, and
resume from the new text's end. Rewriting the editor is unsafe because the user may
have moved the caret or typed in between, and our own paragraph post-processing has
already rewritten that text. AssemblyAI's `format_turns` behaviour is the practical
example: it emits an unformatted final and then a formatted final for the same
turn, so the plugin should withhold until both `end_of_turn` and
`turn_is_formatted` are set rather than making the assembler resolve a
contradiction it cannot.

Assembler state is part of the fenced session state and is cleared by
`invalidateActiveSession()`.

This is the component that lets a provider whose protocol is structurally unlike
Soniox's plug in without touching `LatinIME`.

**Problem 1b — do not rebuild text from words.** Every surveyed provider ships a
rendered text field alongside a word array, and the rendered field is the one to
use. Soniox embeds literal space tokens, Deepgram exposes `punctuated_word`
separately from `word`, and Speechmatics documents `metadata.transcript` as
concatenation-ready. Reconstructing display text from word arrays reintroduces
spacing and punctuation bugs that the provider already solved, so plugins must
emit provider-rendered text and keep word data for diagnostics only.

**Problem 2 — spacing and word-splitting.** `attachesToPrevious` is currently
derived from Soniox's "whitespace arrives as its own tokens" convention, which is
unique to Soniox among the surveyed providers. Everyone else hands back a rendered
segment where inter-word spacing is already correct *within* the segment, leaving
only the join between consecutive segments to decide.

*Solution:* move the decision into `SegmentJoiner`, driven by
`WhitespaceConvention` plus the `rawStartsWithWhitespace` flag each plugin reports.
`TOKENS_CARRY_WHITESPACE` reproduces today's exact logic, including the
`previousTailIsWordy` mid-word-continuation rule that prevents `head ing`.
`PROVIDER_RENDERED_TEXT` trusts the segment's internal spacing and applies only the
punctuation-attachment and separator rules at the boundary. The existing
`SonioxTranscriptionClientTest` cases for `attachesToPrevious` move here essentially
unchanged, which is a useful parity check.

**Problem 3 — finalization guarantee without a finalize frame.** The "trailing
phrase always lands" guarantee currently depends on Soniox's finalize control
message. Per the survey this is a minority feature: Google, Azure, and Gladia have
no flush-and-continue at all.

*Solution:* `VoiceInputManager` picks a policy from `manualFinalize` and
`serverEndpointing`:

| Provider support | Policy on local `onSpeechStopped` |
| --- | --- |
| `FLUSH_AND_CONTINUE` | Today's behaviour: send the flush, once per speech-stop transition. |
| `ENDS_SESSION`, or unsupported with server endpointing | Do nothing; rely on the server. Arm a longer watchdog and, if nothing finalizes, close and reopen the stream to force a flush (`STREAM_CYCLE_ON_STALL`). |
| Neither (`SEGMENTED` mode) | Treat the silence boundary as a request boundary: finish the current request, deliver its result, and open the next on the following speech onset. |

`SEGMENTED` mode is what makes chunked-HTTP and on-device engines expressible at
all. The local VAD already produces exactly the boundaries such engines need, so
this is a scheduling change in the manager rather than new machinery. It is also
the established shape of this problem elsewhere: Pipecat splits precisely this way
between `STTService` and `SegmentedSTTService`.

**Problem 4 — staying connected, and outliving the session cap.** Two facts from
the survey have no equivalent in the current pipeline, because Soniox-while-always-
sending-audio happens to avoid both.

*Keepalive.* Deepgram closes an idle socket after roughly ten seconds
(`NET-0001`/`NET-0002`, WebSocket `1011`) unless a `KeepAlive` message arrives every
few seconds; Soniox wants `{"type":"keepalive"}` at least every 20 seconds while no
audio flows. Our recorder streams PCM continuously *while recording*, silence
included, so this never bites today — except while **paused**, where
`VoiceRecorder.pauseRecording()` stops `AudioRecord` and audio genuinely stops. The
current code survives that by treating the resulting disconnect as expected and
reconnecting on resume, which works but discards the provider-side session. A
declared `KeepAlivePolicy` driven by a manager-owned timer is strictly better and is
mandatory for Deepgram.

*Session rollover.* Google caps a stream at five minutes and OpenAI at sixty.
Five minutes is well inside normal dictation, so rollover cannot be deferred as an
edge case for those providers: the manager must open a fresh session and continue
without losing buffered audio, reusing the existing reconnect machinery but treating
it as a *planned* transition rather than a failure. The honest limitation to record
is that provider-side context does not survive rollover; the best we can do is
re-seed `priorText` from the editor, which the `PriorTextProvider` hook already
supports on reconnect.

### 3.4 Which capability changes which pipeline step

The point of the capability object is that each field has one consumer. Reviewers
should be able to check that mapping.

| Capability | Consumed by | Effect |
| --- | --- | --- |
| `mode` | `VoiceInputManager` | Continuous stream vs. per-utterance request cycling |
| `credentialFlow` | Plugin `start()`, readiness UI | Static key vs. pre-connect token mint vs. REST init |
| `audioFormats` | `audio/AudioEncoder` selection | Encoder, sample rate, framing, and max frame size |
| `finalDelivery` | `TranscriptAssembler` | Pass-through vs. prefix diffing vs. item reconciliation |
| `manualFinalize`, `serverEndpointing` | `VoiceInputManager` | Which policy from the table in 3.3 |
| `keepAlive` | `VoiceInputManager` timer | Whether and how often to send a liveness message |
| `maxSessionDuration` | `VoiceInputManager` | Whether to schedule planned session rollover |
| `whitespace` | `SegmentJoiner` | How `attachesToPrevious` is computed |
| `textShaping` | `TextShapingPipeline` | Whether leading-casing fix, trailing-punctuation strip, and spoken-punctuation rules run |
| `priorTextBias` | `LatinIME.buildVoiceContextText` | Whether to read editor context at all, and how much |
| `vocabularyBias`, `maxVocabularyTerms` | Settings UI + request builder | Whether and how the vocabulary screen is offered |
| `diarization` | Settings UI, request builder, assembler | Whether the toggle is shown; whether labels may be revised |
| `languageHints` | Request builder | Omit / single code / candidate list / code-switching |
| `requiresNetwork` | `VoiceInputManager`, readiness UI | Skip reconnect, keepalive, and network error paths |

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

**Security note.** Storing a long-lived provider secret in client-side
`SharedPreferences` is the status quo for Soniox and this plan does not change it.
But per 3.2.1 it is not a universally available option: several providers expect a
short-lived credential minted server-side, and Google's cannot safely live in an
app at all. The `CredentialFlow` seam exists so those providers are expressible, and
so a future "relay through my own endpoint" plugin does not need another refactor.
Provider settings that are `ProviderSetting.Secret` must be masked in the UI and
redacted in diagnostics.

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
exactly what it does today (`STREAMING`, `APPEND_TOKENS`,
`TOKENS_CARRY_WHITESPACE`, `FLUSH_AND_CONTINUE`). `VoiceInputManager` talks only
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

**Phase 3 — Deepgram.** The least-new-machinery second provider, and the first
phase with user-visible value. It is a WebSocket that takes our PCM16/16 kHz
unchanged, uses a static header key (no token minting), and has both `Finalize`
and `CloseStream`. What it does add is exactly one new concept —
`APPEND_TEXT` delivery plus a **mandatory 3–5 s keepalive** — so it validates the
`whitespace`/`keepAlive` seams in isolation. Gate: the contract suite passes on
fixtures, plus the manual device checklist.

**Phase 4 — AssemblyAI.** The first genuinely dissimilar provider:
`REPLACE_REVISION` delivery exercises `TranscriptAssembler` for real, a
backend-minted one-use token exercises `CredentialFlow.MintedToken`, ~50 ms framing
exercises `AudioFormatSpec`, and `SpeakerRevision` events exercise the advisory
speaker path. Its `format_turns` double-final is a good test of the "withhold until
stable" rule. Gate: **no changes needed in `VoiceInputManager` or `LatinIME`.** If
either needs changing, the abstraction was wrong and gets fixed here rather than
papered over.

**Phase 4b (optional) — OpenAI or Together.** Adds `RECONCILE_ITEM` delivery,
base64-in-JSON framing, and 24 kHz resampling. Together's protocol is
deliberately close to OpenAI's, so one adapter covers both and it is a cheap way to
prove the `AudioEncoder` resampling path. Worth doing only if the three finality
modes from phases 0–4 have not already flushed out the design; those three plus
this one cover every pattern currently in the market.

**Phase 5 — segmented and on-device.** Implement `SEGMENTED` mode and add Vosk,
which maps cleanly onto the abstraction (PCM16 mono in, partial/endpoint-final/forced-final
out) and needs no key or network. This is the phase that proves the abstraction is
not merely "several WebSocket vendors" and gives the feature an offline story.

Phases 0–2 are internal refactoring with test-only visible output; 3–5 each add a
user-facing provider. The ordering front-loads risk and defers provider breadth.
Session rollover (Problem 4) is deliberately *not* on this path — it is only
required by Google and Azure, both of which are poor fits for other reasons, so it
should be built when and if such a provider is actually wanted.

## 8. What real provider APIs demand from the abstraction

Surveyed against current (August 2026) documentation. This section exists to
justify the capability fields in 3.2 and to inform provider selection in
phases 3–5.

| Provider | Transport | Client-safe auth | Audio | Final delivery | Manual flush | Keepalive | Session cap |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **Soniox** | WebSocket | Key in first message | Raw PCM16/16 k — exact match to our recorder | `APPEND_TOKENS` | `{"type":"finalize"}` | 20 s when idle | 300 min |
| **Deepgram** | WebSocket | `Authorization: Token` header | Raw PCM16, `encoding`+`sample_rate` params | `APPEND_TEXT` | `{"type":"Finalize"}` | **3–5 s when idle** | — |
| **AssemblyAI** | WebSocket | Backend-minted one-use `?token=` | Binary PCM16 mono, ~50 ms frames | `REPLACE_REVISION` | `{"type":"ForceEndpoint"}` | Only if inactivity timeout set | 3 h |
| **OpenAI** | WebSocket / WebRTC | Ephemeral client secret | **24 kHz**, base64 inside JSON | `RECONCILE_ITEM` | `input_audio_buffer.commit` | Transport-level | 60 min |
| **Together** | WebSocket | Bearer key (no ephemeral verified) | base64 JSON, `pcm_s16le_16000` | `RECONCILE_ITEM` | `input_audio_buffer.commit` | Unverified | Unverified |
| **Speechmatics** | WebSocket | Backend-minted temporary JWT | Raw `pcm_s16le` | `APPEND_TEXT` | `ForceEndOfUtterance` | Not required | 48 h |
| **Gladia** | REST init → temporary WS URL | Init returns a client-safe URL | Raw or base64, PCM16/16 k | `REPLACE_REVISION` | **None** (`stop_recording` ends it) | Not required | 3 h |
| **Google STT v2** | **gRPC only** | Not safely client-side | Protobuf, **≤15 KB/request** | `APPEND_TEXT` | **None** | Near-real-time audio required | **5 min** |
| **Azure** | **SDK only** | Refreshable auth token | SDK push stream, PCM16 8/16 k | `APPEND_TEXT` | **None** | SDK-managed | 100 concurrent |
| **Groq** | Multipart HTTP | Bearer key | Whole file/chunk | one response per request | n/a | n/a | n/a |
| **Vosk** | On-device JNI | None | PCM16 mono directly | partial + endpoint final + forced final | `getFinalResult()` | n/a | n/a |
| **ML Kit / platform** | On-device | None | Mic or file descriptor | partial → final via callbacks/`Flow` | close audio source | n/a | n/a |

What this forces on the design, beyond the four-mode finality taxonomy:

- **A WebSocket-only abstraction is wrong.** Google is bidirectional gRPC with no
  REST/WS streaming option, Azure is SDK-only, Groq is multipart HTTP, and the
  on-device engines are in-process. This is exactly why transport ownership belongs
  to the plugin and why `SEGMENTED` mode is a phase rather than "future work".
- **Flush-and-continue is a minority feature.** Google, Azure, and Gladia have no
  way to finalize the current utterance and keep going, which validates the
  three-valued `manualFinalize` and the fallback policies in 3.3.
- **Resampling is required, not optional.** OpenAI wants 24 kHz; everything else in
  the table takes our 16 kHz directly. Google additionally caps a request at
  15 KB and AssemblyAI prefers ~50 ms frames, so `AudioFormatSpec` must carry frame
  size, not just encoding and rate.
- **`context.text` is close to unique.** Soniox's prior-text field, OpenAI's
  `prompt`, and AssemblyAI's `prompt`/`agent_context` can carry preceding text;
  Deepgram Nova-3, Google, Azure, Speechmatics, and Gladia have vocabulary biasing
  but no verified free-text context field. So `LatinIME.buildVoiceContextText()`
  must become capability-gated rather than always-on — for most providers it should
  not run at all.

**Not viable as providers.** Speechify has **no speech-to-text API**: the Build API
is text-to-speech and voice cloning only, and its `POST /v1/audio/stream` endpoint —
easy to mistake for streaming STT — takes text and returns synthesized audio. The
consumer app's "Voice Typing" feature is not exposed as an API. Fireworks has
officially deprecated audio inference. Google and Azure are technically possible but
poor early candidates: gRPC and the Speech SDK are heavy dependencies for an IME
(`minSdk = 21`), neither supports flush-and-continue, neither can hold credentials
safely in a client, and Google's five-minute cap would make rollover a prerequisite.

**No shortcut exists.** There is no mature Kotlin/Android library that already
normalizes these providers behind one PCM-streaming interface. The closest Android
reference is **FluxVoice**, which separates an `SttProvider` interface from audio and
VAD but ships only Deepgram. The best-designed references are server-side:
**Pipecat** (`STTService` / `SegmentedSTTService` / `WebsocketSTTService`) and
**LiveKit Agents**, whose normalized `SpeechEvent` model and capability handling are
worth studying even though neither is usable from an IME. On the gateway side,
**LiveKit Inference** is the only true streaming aggregator but is bound to LiveKit
rooms; **OpenRouter's** transcription route is whole-file HTTP, not streaming; and
**Cloudflare AI Gateway** proxies provider-native sockets without normalizing their
protocols. So the normalization layer has to be ours.

### 8.1 Two Soniox findings worth acting on independently

Both are pre-existing issues in today's code, unrelated to this refactor, and
should be verified and fixed on their own rather than folded into it.

1. **The pinned model is retired.** `SonioxTranscriptionClient.MODEL` is
   `"stt-rt-v4"`, but Soniox's current realtime model is `stt-rt-v5`, and `v4` was
   retired or rerouted after 30 June 2026. Voice input still appears to work, which
   suggests requests are being silently rerouted rather than rejected — but the app
   is relying on a deprecation shim, and it may be transcribing on a different model
   than the one it asks for. Needs verification against a live key, then a version
   bump.
2. **Frequent manual finalize is discouraged with diarization on.** Soniox
   documents that frequent flushing degrades diarization and can drop sessions. We
   send a flush on every speech-stop transition (as often as every ~1 s, per
   `PREF_VOICE_CHUNK_SILENCE_SECONDS`) while diarization defaults to **on**. Worth
   measuring, and worth reconsidering the interaction between those two defaults.

Also unexposed today: Soniox supports `endpoint_sensitivity`,
`endpoint_latency_adjustment_level`, multi-language `language_hints`,
`enable_language_identification`, and a structured `context.general` field. These
become natural additions once the settings schema is declarative.

## 9. Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Silent text-quality regression during Phase 0 | Goldens captured before refactoring; Phase 0 gate is byte-identical output |
| Abstraction fits only WebSocket vendors | `SEGMENTED` mode and an on-device plugin are explicit phases, not "future work"; Phase 4's gate is "no core changes needed" |
| Providers needing an ephemeral token can't be used from a client | `CredentialFlow` supports a pre-connect mint or REST init, run off the audio critical path since audio already buffers. Some providers genuinely require a backend; the relay-plugin shape stays expressible |
| Duplicated or lost text from cumulative providers | `TranscriptAssembler` with an explicit, tested policy over all four delivery modes rather than per-plugin ad-hoc diffing |
| Silent socket drops from missing keepalive | `KeepAlivePolicy` is a declared capability with a manager-owned timer; Deepgram's ~10 s idle close makes this mandatory, not optional |
| APK size and dependency creep from vendor SDKs | Prefer raw protocol over vendor SDKs (OkHttp 4.12 already covers WebSocket and HTTP). Google (gRPC) and Azure (Speech SDK) would each be a large dependency for an IME at `minSdk = 21`, and are explicitly deferred |
| Provider deprecating a pinned model silently | Already happened — see 8.1 on `stt-rt-v4`. Model choice should become a declared, user-visible setting rather than a buried constant |
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
