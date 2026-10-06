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

The model's display name is **MAI-Transcribe-2-Streaming**. Microsoft's current examples also use that capitalization as the SDK selector. A live Central US check on October 5, 2026 with SDK 1.52.0 rejected that selector with `Unknown speech.context.model.name`; changing only the selector to `mai-transcribe-2-streaming` returned a successful final transcript. The Android client and smoke test use this verified lowercase service ID.

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

`MaiTranscriptionClient` owns main-looper lifecycle state and stale-session guards. `AzureMaiSpeechSession` in `MaiSpeechSession.kt` owns the SDK's `SpeechConfig`, `AudioStreamFormat`, `PushAudioInputStream`, `AudioConfig`, and `SpeechRecognizer`. JNI operations, startup waits, and cleanup run on a serial background executor. The JNI upload work queue is bounded to 256 KB. Backpressure leaves the next chunk at the head of the manager FIFO; a worker-capacity callback resumes upload. All 300 full-size startup chunks can drain across multiple capacity windows. Silence commits, EOF, and rotation wait until that FIFO is accepted, and the worker serializes each write before its commit or input close. A local write is an SDK memory copy, not a service acknowledgment. The SDK owns acknowledgment handling; the app never replays audio.

The model detects language automatically when no hint is configured. When automatic detection is disabled, the client supplies the current keyboard's BCP-47 locale as a recognition hint. No editor text or recognition vocabulary is sent to the service.

## Final results and lifecycle

Only `RecognizedSpeech` final results reach the editor. `NoMatch` settles processing without inserting text. The SDK's intermediate hypotheses never enter the editor. Duplicate result IDs are ignored; identical text with different IDs is preserved for deliberate repetitions.

Local silence and pause request `PushAudioInputStream.commit()` only for new, unfinalized audio. This SDK API is advisory: a positive token can correlate a subsequent final result; zero means the request was rejected. Tokens are not guaranteed to be echoed. Final audio offsets also settle pending boundaries. Microsoft documents these offsets as covering submitted segments, which can include silence; the app does not assume every final reaches the requested boundary.

When a boundary remains unconfirmed for 60 seconds, or the pending-boundary count reaches 64, the manager closes input and drains the session to definitive EOF. It keeps receiving outgoing finals and buffers new microphone audio before starting a replacement. Missing an advisory acknowledgment alone never reports lost speech. A failure to reach EOF within the separate 60-second drain deadline is an error.

Pause commits speech and retains the SDK session for resume. Stop drains queued microphone callbacks, closes the push input stream, and **waits for EOF/session termination** while final results continue arriving. It does not immediately stop recognition, which could discard the last words. EOF settles remaining advisory boundaries, including non-speech input. The application drain deadline is 60 seconds, not a service latency guarantee.

A mic restart during drain immediately resumes local capture and buffers new audio until outgoing EOF. The next SDK session starts afterward, preserving transcript order. A restart before input has closed continues the open session and keeps its buffered tail. Cancel remains the explicit way to discard pending results.

A microphone initialization/read failure or local audio-buffer overflow stops capture and reports one error without cancelling the outgoing recognizer. Already uploaded speech continues to final results and EOF. Accepted buffered audio drains through a replacement when needed, in order; audio exceeding the buffer limit cannot be retained. Later microphone callbacks are ignored, and restarting is blocked until accepted speech drains. The processing indicator stays visible while any work remains, including across errors and individual final insertions. After draining a failed capture, the app inserts `[Dictation interrupted]` before allowing a new recording. Explicit cancel discards pending work and the marker.

Cancellation, destruction, and editor changes invalidate tokens immediately. Stale SDK callbacks cannot insert into a new editor. Session rotation after 55 minutes closes/drains the outgoing input while new microphone chunks buffer, then starts a replacement.

## Connection loss and transcript integrity

Dictation uses a conservative failure policy: **a detected failure stops the microphone and the transcription session; it never automatically resumes**. Start requires an Android default network with both `INTERNET` and `VALIDATED` capabilities. An active network callback watches for loss of the default network or its validation. Android validation is only a system probe and cannot prove the Azure endpoint is reachable. A switch to another validated default network does not itself indicate lost speech; SDK connection events still monitor the existing service connection. The observer remains active while pending speech drains and is unregistered when the session ends or is cancelled.

The adapter keeps a `Connection.fromRecognizer` handle and treats `disconnected` while input is open as terminal, even if the SDK would recover automatically. SDK cancellation errors, unexpected session end, invalid PCM, JNI write failure, and inconsistent/regressing final timing also end dictation. Intentional input close waits for normal EOF; a disconnect during this close is not itself evidence of loss, so network monitoring and the separate EOF deadline remain responsible for detecting failure. Detection takes effect when Android or the SDK reports it; neither signal promises instantaneous detection of a radio dead spot.

Two **30-second application safety deadlines** bound silent stalls: the oldest local audio waiting for upload, and the oldest submitted audio without a final offset or echoed commit token covering it. New chunks, intermediate hypotheses, later partial finals, and commit-request callbacks cannot extend the oldest audio's deadline. Startup also has a 30-second deadline. Once EOF draining begins, its separate 60-second deadline applies; continuing capture into a replacement is still constrained by the local FIFO deadline and size bound. These deadlines are policy limits, not Azure latency guarantees. A local upload deadline is terminal. A submitted-audio confirmation deadline stops capture, blocks restart, and closes/drains the existing session before deciding whether speech was lost. It preserves accepted finals through EOF, rather than treating an absent advisory acknowledgment as proof of a gap. These limits can stop capture in a healthy but unusually slow session or a long utterance that never finalizes; strict interruption handling takes precedence over indefinite capture.

Previously inserted finals remain in the editor. A failure with uncertain pending audio inserts `[Dictation interrupted]` once at the current insertion point, rejects stale results and capacity/network callbacks, clears pending audio, and requires a deliberate tap to start a new session. It does not attach a reconnected suffix to the interrupted utterance. An editor `commitText` rejection or exception also cancels dictation immediately; if the editor rejects text, an interruption marker cannot be guaranteed either. The InputConnection wrapper exposes the host's acceptance result and refreshes its cache after rejection.

A 100 ms PCM chunk is an ordered audio buffer, **not an independently transcribed text job**. The public Speech SDK provides finals for segments, segment timing, advisory commit correlation, and connection status; it does not expose a per-chunk transcription receipt or a proof that every spoken word was recognized. `NoMatch` is a legitimate recognition outcome. Timing gaps may be silence and cannot locate missing words reliably, so the app does not insert guessed placeholders between otherwise valid results. TCP/SDK ordering and a bounded FIFO preserve the order of accepted audio; terminal failure handling prevents continuation after a detected gap. An unnoticed service/model omission within apparently successful finals remains outside what this API can prove.

Microsoft documents SDK acknowledgment/replay recovery, but this app cancels at detected disconnect rather than recording through recovery. It does not parse undocumented ACK message payloads or resend audio. Raw SDK details, credentials, and transcripts never enter diagnostics.

Sources: [MAI streaming lifecycle and timing](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk), [Java Connection events](https://learn.microsoft.com/en-us/java/api/com.microsoft.cognitiveservices.speech.connection), [PushAudioInputStream.write](https://learn.microsoft.com/en-us/java/api/com.microsoft.cognitiveservices.speech.audio.pushaudioinputstream), and [Android network state](https://developer.android.com/develop/connectivity/network-ops/reading-network-state).

`LatinIME` clears typed-word state with `finishInput()`, commits completed text through `InputConnection` in a batch edit, and runs local spoken-punctuation, paragraph, and filler cleanup. The toolbar VOICE action remains Android's system voice IME shortcut.

## Sentence structure and punctuation

The streaming model supplies punctuation in its final text. Local cleanup preserves that punctuation, including em dashes, apart from explicit spoken commands and editor-aware insertion adjustments. Only confirmed finals enter the editor; provisional text can still change as more audio context arrives.

The current [MAI streaming SDK guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk) explicitly says `OutputFormat` and `ProfanityOption` are ignored. It does not document a punctuation-strength option, custom prompts, phrase lists, or semantic-segmentation settings for this model. Do not apply generic Azure Speech options without verifying MAI support. `transcribeStyle: clean` is documented for the [file-transcription API](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe), not this streaming SDK integration.

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

On a supported Android device, check a short phrase, pause/resume, stop immediately after the last word, repeated phrases, network interruption, and editor switches. Inspect Settings → Transcription → Voice diagnostics for lifecycle and length messages without raw transcripts or credentials.

For device interruption testing, start dictation online and speak a confirmed prefix, then disable both Wi-Fi and mobile data while continuing to speak. Check that recording stops, an error and interruption marker appear when speech is uncertain, and re-enabling connectivity does not resume or append a late suffix. Tap the microphone explicitly to begin a fresh recording after the marker. Also verify an offline start, a valid Wi-Fi/mobile handoff, and a brief local silence/NoMatch without a false guessed gap. Do not assume toggling only Wi-Fi removes internet when mobile data is enabled.
