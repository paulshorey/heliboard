# MAI voice transcription

HeliBoard sends microphone audio directly to **Microsoft MAI-Transcribe-2-Streaming** using **Azure Speech SDK 1.52.0** for Android. Microsoft hosts the model; the app needs a Speech resource key and matching region. Completed segments enter the active editor through `InputConnection`.

## Azure setup

1. Sign in to [the Azure portal](https://portal.azure.com/) with a Microsoft or organizational account and an Azure subscription with billing enabled.
2. Create a **Speech** resource and a resource group. Select **Central US** (`centralus`), **Sweden Central** (`swedencentral`), or **Southeast Asia** (`southeastasia`). Use the region nearest the device, among the supported regions.
3. Select a pricing tier suitable for paid usage and review the current [Speech pricing](https://azure.microsoft.com/pricing/details/speech/). Do not assume the standard Speech free allowance covers this preview model.
4. Open the resource's **Keys and Endpoint** page. Copy KEY 1 or KEY 2 and note its region.
5. In **HeliBoard Settings → Transcription**, enter the **Azure Speech API key** and **Azure Speech region**. Grant microphone permission and use the fixed right-edge microphone.

The app defaults the region to `centralus`; the value must match the resource that issued the key. There is no model deployment name or server to manage. Onboarding routes to the same Transcription screen. Local validation checks required configuration but does not establish cloud access.

Use the [official MAI Speech SDK guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk) for current regions, capabilities, and preview status. The model is in public preview. The integration selects the case-sensitive service identifier `mai-transcribe-2-streaming` explicitly, and constructs `wss://<region>.stt.speech.microsoft.com/speech/universal/v2` from the region. The SDK authenticates with the resource key.

The model's display name is **MAI-Transcribe-2-Streaming**. Earlier Microsoft examples also used that capitalization as the SDK selector; the current guide uses lowercase. A live Central US check on October 5, 2026 with SDK 1.52.0 rejected that selector with `Unknown speech.context.model.name`; changing only the selector to `mai-transcribe-2-streaming` returned a successful final transcript. The Android client and smoke test use this verified lowercase service ID.

Keys are masked in settings and stored in the app's existing private preferences. Never embed a shared billing key in an APK, source, or script. Each installation can use its owner's resource key. A centrally billed deployment should provide short-lived authorization tokens through an authenticated backend rather than distributing a shared key.

## Local macOS credentials

Use the repository helper to install an isolated Python Speech SDK environment and store your existing resource key in the native macOS Keychain:

```bash
./tools/azure-speech-local.sh setup
./tools/azure-speech-local.sh configure \
  --region centralus --resource YOUR_SPEECH_RESOURCE \
  --tenant YOUR_TENANT --subscription YOUR_SUBSCRIPTION_ID
./tools/azure-speech-local.sh status
```

`configure` prompts for the key without echoing it. Automation can supply it over stdin with `--key-stdin`; do not put a literal key in a command, shell history, or argument. The key is stored under Keychain service `HeliBoard.AzureSpeech` and account matching the resource name. The metadata file `~/Library/Application Support/HeliBoard/azure-speech.json` contains region, resource, and optional tenant/subscription identifiers. The Python environment is in the same directory under `speech-venv`. Neither location is part of the repository.

Run commands with credentials supplied only to that child process:

```bash
./tools/azure-speech-local.sh smoke-test --pcm /path/to/mono-16khz.pcm --language en-US
./tools/azure-speech-local.sh run -- python3 /path/to/your-speech-tool.py
```

The helper sets `SPEECH_KEY` and `SPEECH_REGION` for the command and uses the isolated environment's Python. It does not export the key globally or change shell startup files. `status` reports availability without printing the key, including `key_available: false` for a missing entry. Credentialed commands still fail when the key is absent, and Keychain access errors remain errors. To configure a device, `./tools/azure-speech-local.sh copy-key` deliberately copies it to the macOS clipboard; paste it into **Settings → Transcription → Azure Speech API key** and use the profile's region. Installing or rebuilding an APK does not transfer Mac credentials to Android.

Azure CLI management uses a separate interactive `az login --tenant YOUR_TENANT`, followed by `az account set --subscription YOUR_SUBSCRIPTION_ID`. Speech SDK authentication uses the resource key and region and does not require a CLI sign-in.

## Android integration

The Android Gradle dependency is `com.microsoft.cognitiveservices.speech:client-sdk:1.52.0`. Kotlin calls the SDK's Java API directly. The package includes consumer shrinker rules and device-native libraries for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

SDK dictation requires **Android 8.0/API 26 or newer** on one of those architectures. The keyboard's overall minimum remains API 21. Unsupported devices receive a dictation error before SDK creation. The SDK's context provider does not load its native recognition libraries at app startup.

`VoiceRecorder` emits headerless, signed little-endian PCM16, mono at 16 kHz in 100 ms chunks. `VoiceInputManager` buffers up to 300 startup chunks and stops with an explicit error on overflow. Every captured PCM chunk, including locally quiet audio, enters the FIFO. Local VAD requests commits and controls auto-stop; it never discards audio based on a silence classification. This avoids cutting quiet speech before a detected onset and sends silence until pause/stop/auto-stop. Local silence, pause, and auto-stop remain configurable.

**Chunk silence duration** uses milliseconds in the UI, stored preferences, and recording configuration. Its default is **1000 ms**, and its range is **100–30000 ms**; values such as **750 ms** remain subsecond in the recorder. Silence detection evaluates audio every 100 ms, so observed boundaries follow that cadence. Saved durations in seconds are converted once without changing the user's chosen duration. **Silence threshold** defaults to **100 RMS**. Auto-stop remains in seconds, defaulting to 30 seconds. Explicit saved settings survive upgrades.

`MaiTranscriptionClient` owns main-looper lifecycle state and stale-session guards. `AzureMaiSpeechSession` in `MaiSpeechSession.kt` owns the SDK's `SpeechConfig`, `AudioStreamFormat`, `PushAudioInputStream`, `AudioConfig`, and `SpeechRecognizer`. JNI operations, startup waits, and cleanup run on a serial background executor. The JNI upload work queue is bounded to 256 KB. Backpressure leaves the next chunk at the head of the manager FIFO; a worker-capacity callback resumes upload. All 300 full-size startup chunks can drain across multiple capacity windows. Silence commits wait for their recorded pause boundary; EOF and rotation wait for the full FIFO. The worker serializes writes and control operations so resumed audio follows its preceding commit. A local write is an SDK memory copy, not a service acknowledgment. The SDK owns acknowledgment handling; the app never replays audio.

The model detects language automatically when no hint is configured. When automatic detection is disabled, the client supplies the current keyboard's BCP-47 locale as a recognition hint. No editor text or recognition vocabulary is sent to the service.

## Final results and lifecycle

Only `RecognizedSpeech` final results reach the editor. `NoMatch` settles processing without inserting text. The SDK's intermediate hypotheses never enter the editor. Duplicate result IDs are ignored; identical text with different IDs is preserved for deliberate repetitions.

Local silence and pause request `PushAudioInputStream.commit()` only for new, unfinalized audio. Each request records the captured-chunk sequence at that pause. If backpressure delays upload, the commit is queued as soon as that boundary is accepted, before audio captured after the pause. Resuming cannot move the boundary into the next phrase. Captured advisory requests form a FIFO bounded to 64 controls. Overflow is terminal; requests are never dropped or coalesced. A speech epoch prevents a delayed old boundary from starting a final deadline for newer speech. This SDK API is advisory: a positive token can correlate a subsequent final result; zero means the request was rejected. Tokens are not guaranteed to be echoed. Final audio offsets also settle pending boundaries. Microsoft documents these offsets as covering submitted segments, which can include silence; the app does not assume every final reaches the requested boundary.

When a boundary remains unconfirmed for 60 seconds, or the pending-boundary count reaches 64, the manager closes input and drains the session to definitive EOF. It keeps receiving outgoing finals and buffers new microphone audio before starting a replacement. Missing an advisory acknowledgment alone never reports lost speech. A failure to reach EOF within the separate 60-second drain deadline is an error.

Pause commits speech and retains the SDK session for resume. Stop drains queued microphone callbacks, closes the push input stream, and **waits for EOF/session termination** while final results continue arriving. It does not immediately stop recognition, which could discard the last words. EOF settles remaining advisory boundaries, including non-speech input. The application drain deadline is 60 seconds, not a service latency guarantee.

A mic restart during drain immediately resumes local capture and buffers new audio until outgoing EOF. The next SDK session starts afterward, preserving transcript order. A restart before input has closed continues the open session and keeps its buffered tail. Cancel remains the explicit way to discard pending results.

A microphone initialization/read failure or local audio-buffer overflow stops capture and reports one error without cancelling the outgoing recognizer. Already uploaded speech continues to final results and EOF. Accepted buffered audio drains through a replacement when needed, in order; audio exceeding the buffer limit cannot be retained. Later microphone callbacks are ignored, and restarting is blocked until accepted speech drains. The processing indicator stays visible while any work remains, including across errors and individual final insertions. After draining a failed capture, the app inserts `[Dictation interrupted]` before allowing a new recording. Explicit cancel discards pending work and the marker.

Cancellation, destruction, and editor changes invalidate tokens immediately. Stale SDK callbacks cannot insert into a new editor. Session rotation after 55 minutes closes/drains the outgoing input while new microphone chunks buffer, then starts a replacement.

## Connection loss and transcript integrity

Dictation uses a conservative failure policy: **a detected failure stops the microphone and the transcription session; it never automatically resumes**. Start requires an Android default network with both `INTERNET` and `VALIDATED` capabilities. An active network callback watches for loss of the default network or its validation. Android validation is only a system probe and cannot prove the Azure endpoint is reachable. A switch to another validated default network does not itself indicate lost speech; SDK connection events still monitor the existing service connection. The observer remains active while pending speech drains and is unregistered when the session ends or is cancelled.

The adapter keeps a `Connection.fromRecognizer` handle and treats `disconnected` while input is open as terminal, even if the SDK would recover automatically. SDK cancellation errors, unexpected session end, invalid PCM, JNI write failure, and inconsistent/regressing final timing also end dictation. Intentional input close waits for normal EOF; a disconnect during this close is not itself evidence of loss, so network monitoring and the separate EOF deadline remain responsible for detecting failure. Detection takes effect when Android or the SDK reports it; neither signal promises instantaneous detection of a radio dead spot.

The oldest local PCM waiting for upload has a **30-second** deadline. Active speech/pending recognized words also have a **30-second response watchdog**, refreshed by SDK intermediate/final events. Intermediate events expose only a liveness notification; provisional text stays inside the SDK. The separate **30-second missing-final wait** begins when the current speech epoch's advisory boundary is submitted, only for pending recognized words. Repeated interims or boundaries cannot extend that wait. New speech clears it, and a delayed older boundary cannot apply a final deadline to a newer utterance. This allows long utterances with live interims. Quiet PCM alone does not require a final. These are application policy limits, not Azure latency guarantees. A stalled response/final stops capture, blocks restart, and preserves accepted finals through EOF. Intentional silence auto-stop takes precedence at equal deadlines and closes without a marker for unconfirmed noise/hypotheses. Actual microphone, network, queue, upload and EOF failures retain the interruption policy. Startup has a separate 30-second deadline; explicit stop clears speech watchdogs and uses a 60-second EOF deadline. Continuing capture during replacement remains bounded by the local FIFO deadline and capacity.

Previously inserted finals remain in the editor. Startup rejection before readiness leaves the editor untouched and reports the reason. Actual failure after readiness and capture attempts one literal `[Dictation interrupted]`, rejects stale callbacks, clears pending audio and requires a deliberate new start. The marker bypasses transcript normalization and paragraph cleanup, verifies the actual host caret, and preserves highlighted text. A final stays at the bounded transcript FIFO head until local insertion/cleanup succeeds; rejection or exception cancels all later writes without retry or marker. Remote `InputConnection` booleans report IPC dispatch rather than actual host acceptance; verification cannot certify full insertion in every editor.

A 100 ms PCM chunk is an ordered audio buffer, **not an independently transcribed text job**. The public Speech SDK provides finals for segments, segment timing, advisory commit correlation, and connection status; it does not expose a per-chunk transcription receipt or a proof that every spoken word was recognized. `NoMatch` is a legitimate recognition outcome. Timing gaps may be silence and cannot locate missing words reliably, so the app does not insert guessed placeholders between otherwise valid results. TCP/SDK ordering and a bounded FIFO preserve the order of accepted audio; terminal failure handling prevents continuation after a detected gap. An unnoticed service/model omission within apparently successful finals remains outside what this API can prove.

Microsoft documents SDK acknowledgment/replay recovery, but this app cancels at detected disconnect rather than recording through recovery. It does not parse undocumented ACK message payloads or resend audio. Raw SDK details, credentials, and transcripts never enter diagnostics.

Sources: [MAI streaming lifecycle and timing](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk), [Java Connection events](https://learn.microsoft.com/en-us/java/api/com.microsoft.cognitiveservices.speech.connection), [PushAudioInputStream.write](https://learn.microsoft.com/en-us/java/api/com.microsoft.cognitiveservices.speech.audio.pushaudioinputstream), and [Android network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state).

`LatinIME` clears typed-word state with `finishInput()`, commits finals through `InputConnection`, and runs local punctuation/paragraph/filler cleanup. Cleanup and punctuation replacement select a verified host range and replace it in one commit, never deleting confirmed text first. Temporarily close/reopen the host batch so deferred editors apply earlier edits before reading original/selected text. Unavailable/mismatched host selections stop dictation. On rejection/exception, restore the caret and refresh text/cursor caches best-effort without retrying a possibly partial edit. Delayed selection callbacks continue recording only when the current host caret matches the expected voice caret. See the [Android InputConnection contract](https://developer.android.com/reference/android/view/inputmethod/InputConnection).

Interactive keyboard hiding, input/view finish, target changes and verified host clears immediately cancel all voice work, including EOF drain. A dedicated extracted-text monitor plus actual host reads detect nonempty-to-empty transitions even without cursor movement or notifications. Preserve the framework's fullscreen extractor token. Null queries mean unavailable, and highlighted text is not an empty field. A clear alone does not prove submission or hide the keyboard. Screen-off hiding alone preserves recording; explicit mic stop drains. Capture errors retain processing UI through accepted finals; failed resume and no-op pause taps cannot show recording again.

## Sentence structure and punctuation

The streaming model supplies punctuation in its final text. Local cleanup preserves that punctuation, including em dashes, apart from explicit spoken commands and editor-aware insertion adjustments. Only confirmed finals enter the editor; provisional text can still change as more audio context arrives.

The [MAI streaming SDK guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk) explicitly says `OutputFormat` and `ProfanityOption` are ignored. It does not document a punctuation-strength option, custom prompts, phrase lists, or semantic-segmentation settings for this model. Do not apply generic Azure Speech options without verifying MAI support. `transcribeStyle: clean` is documented for the [file-transcription API](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe), not this streaming SDK integration.

For users whose sentences are split at thinking pauses, try increasing **Chunk silence duration** from the **1000 ms** default to **3000 or 4000 ms**. This delays explicit commit requests and the local silence gate, letting more audio arrive together; it cannot force the model's own sentence boundaries. Treat this as a tuning experiment with a tradeoff in final-result latency, not a guaranteed accuracy improvement. Keep auto-stop longer than the chunk-silence duration, accounting for its seconds unit. Use the correct keyboard-language hint for consistent single-language dictation, and automatic detection for mixed-language speech.

Compare the same recordings at different settings, including multi-clause sentences, short pauses, questions, names, and dictated punctuation. Measure both final-text quality and the delay after speaking before changing defaults. No live quality comparison has established a better default yet.

## Validation

Run:

```bash
./gradlew :app:testDebugNoMinifyUnitTest \
  --tests 'helium314.keyboard.latin.voice.*' \
  --tests 'helium314.keyboard.latin.utils.LogVoiceDiagnosticsTest' \
  --tests 'helium314.keyboard.latin.InputLogicTest'
python3 -B -m unittest discover -s tools -p 'test_azure_speech_local.py'
./tools/build-dist-apk.sh
```

Tests use `FakeMaiSpeechSession` to exercise startup gating, final delivery, NoMatch, advisory commits without token/timing confirmation, EOF draining and replacement, mic restart during drain, cancellation, offline preflight/network loss, terminal service failures, stale reconnection callbacks, bounded write backpressure with 300 full-size PCM chunks, upload/final-progress deadlines, error UI/editor rejection, rotation, punctuation preservation, and configuration without loading device-native libraries in the JVM. Compiling and packaging against the published SDK verifies API compatibility. Cloud access, recognition quality, latency, and JNI execution still require a live service/device check.

For a credentialed service check, install `azure-cognitiveservices-speech==1.52.0` in a compatible Python environment and set `SPEECH_KEY` and `SPEECH_REGION` outside the repository. Run:

```bash
python3 tools/mai-streaming-smoke-test.py --pcm /path/to/mono-16khz.pcm --language en-US
```

Use headerless 16 kHz mono PCM16. Omitting `--language` uses automatic detection. The tool paces audio, closes the input, and waits for final results/session termination. It prints final transcripts intentionally, and returns failure on service errors or drain timeout.

On a supported Android device, check short and long uninterrupted speech, pause/resume during backpressure, immediate stop/restart, repeated phrases, network interruption, editor rejection, keyboard hiding, and host clearing without cursor movement. Include punctuation across segments, highlighted text during failure, and editors that buffer batch edits. Inspect Settings → Transcription → Voice diagnostics for lifecycle and length messages without raw transcripts or credentials.

For device interruption testing, start dictation online and speak a confirmed prefix, then disable both Wi-Fi and mobile data while continuing to speak. Check that recording stops, an error and interruption marker appear when speech is uncertain, and re-enabling connectivity does not resume or append a late suffix. Tap the microphone explicitly to begin a fresh recording after the marker. Also verify an offline start, a valid Wi-Fi/mobile handoff, and a brief local silence/NoMatch without a false guessed gap. Do not assume toggling only Wi-Fi removes internet when mobile data is enabled.
