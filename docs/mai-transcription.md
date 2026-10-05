# MAI voice transcription

HeliBoard sends microphone audio directly to **Microsoft MAI-Transcribe-2-Streaming** using **Azure Speech SDK 1.52.0** for Android. Microsoft hosts the model; the app needs a Speech resource key and matching region. Completed segments enter the active editor through `InputConnection`.

## Azure setup

1. Sign in to [the Azure portal](https://portal.azure.com/) with a Microsoft or organizational account and an Azure subscription with billing enabled.
2. Create a **Speech** resource and a resource group. Select **Central US** (`centralus`), **Sweden Central** (`swedencentral`), or **Southeast Asia** (`southeastasia`). Use the region nearest the device, among the supported regions.
3. Select a pricing tier suitable for paid usage and review the current [Speech pricing](https://azure.microsoft.com/pricing/details/speech/). Do not assume the standard Speech free allowance covers this preview model.
4. Open the resource's **Keys and Endpoint** page. Copy KEY 1 or KEY 2 and note its region.
5. In **HeliBoard Settings → Transcription**, enter the **Azure Speech API key** and **Azure Speech region**. Grant microphone permission and use the fixed right-edge microphone.

The app defaults the region to `centralus`; the value must match the resource that issued the key. There is no model deployment name or server to manage. Onboarding routes to the same Transcription screen. Local validation checks required configuration but does not establish cloud access.

Use the [official MAI Speech SDK guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk) for current regions, capabilities, and preview status. The model is in public preview. The integration selects `MAI-Transcribe-2-Streaming` explicitly, and constructs `wss://<region>.stt.speech.microsoft.com/speech/universal/v2` from the region. The SDK authenticates with the resource key.

Keys are masked in settings and stored in the app's existing private preferences. Never embed a shared billing key in an APK, source, or script. Each installation can use its owner's resource key. A centrally billed deployment should provide short-lived authorization tokens through an authenticated backend rather than distributing a shared key.

## Android integration

The Android Gradle dependency is `com.microsoft.cognitiveservices.speech:client-sdk:1.52.0`. Kotlin calls the SDK's Java API directly. The package includes consumer shrinker rules and device-native libraries for `arm64-v8a`, `armeabi-v7a`, and `x86_64`.

SDK dictation requires **Android 8.0/API 26 or newer** on one of those architectures. The keyboard's overall minimum remains API 21. Unsupported devices receive a dictation error before SDK creation. The SDK's context provider does not load its native recognition libraries at app startup.

`VoiceRecorder` emits headerless, signed little-endian PCM16, mono at 16 kHz in 100 ms chunks. `VoiceInputManager` buffers up to 300 startup chunks and stops with an explicit error on overflow. It retains a 300 ms onset prefix after local silence. Local silence, pause, and auto-stop remain configurable.

`MaiTranscriptionClient` owns main-looper lifecycle state and stale-session guards. `AzureMaiSpeechSession` in `MaiSpeechSession.kt` owns the SDK's `SpeechConfig`, `AudioStreamFormat`, `PushAudioInputStream`, `AudioConfig`, and `SpeechRecognizer`. JNI operations, startup waits, and cleanup run on a serial background executor. The upload work queue is bounded to 256 KB. The SDK owns acknowledgement handling and recovery of unconfirmed audio; the app must not add an audio replay loop.

The model detects language automatically when no hint is configured. When automatic detection is disabled, the client supplies the current keyboard's BCP-47 locale as a recognition hint. No editor text or recognition vocabulary is sent to the service.

## Final results and lifecycle

Only `RecognizedSpeech` final results reach the editor. `NoMatch` settles processing without inserting text. The SDK's intermediate hypotheses never enter the editor. Duplicate result IDs are ignored; identical text with different IDs is preserved for deliberate repetitions.

Local silence and pause request `PushAudioInputStream.commit()` only for new, unfinalized audio. This SDK API is advisory: a positive token correlates a subsequent final result; zero means the request was rejected. Tokens are not guaranteed to be echoed. Final audio offsets also settle pending boundaries. A boundary left unconfirmed for 60 seconds reports incomplete dictation.

Pause commits speech and retains the SDK session for resume. Stop drains queued microphone callbacks, closes the push input stream, and **waits for EOF/session termination** while final results continue arriving. It does not immediately stop recognition, which could discard the last words. EOF settles remaining advisory boundaries, including non-speech input. The application drain deadline is 60 seconds, not a service latency guarantee.

Cancellation, destruction, and editor changes invalidate tokens immediately. Stale SDK callbacks cannot insert into a new editor. Session rotation after 55 minutes closes/drains the outgoing input while new microphone chunks buffer, then starts a replacement.

The SDK handles transient recovery itself. The manager's bounded backoff only restarts a failed session without uploaded unconfirmed audio. An unrecoverable failure with unfinished speech reports incomplete dictation. Authentication failures stop immediately. Raw SDK error details are not logged because they may contain sensitive text or credentials.

`LatinIME` clears typed-word state with `finishInput()`, commits completed text through `InputConnection` in a batch edit, and runs local spoken-punctuation, paragraph, and filler cleanup. The toolbar VOICE action remains Android's system voice IME shortcut.

## Validation

Run:

```bash
./gradlew :app:testDebugNoMinifyUnitTest \
  --tests 'helium314.keyboard.latin.voice.*' \
  --tests 'helium314.keyboard.latin.utils.LogVoiceDiagnosticsTest'
./tools/build-dist-apk.sh
```

Tests use `FakeMaiSpeechSession` to exercise startup gating, final delivery, NoMatch, advisory commits, EOF draining, cancellation, startup backoff, rotation, and configuration without loading device-native libraries in the JVM. Compiling and packaging against the published SDK verifies API compatibility. Cloud access, recognition quality, latency, and JNI execution still require a live service/device check.

For a credentialed service check, install `azure-cognitiveservices-speech==1.52.0` in a compatible Python environment and set `SPEECH_KEY` and `SPEECH_REGION` outside the repository. Run:

```bash
python3 tools/mai-streaming-smoke-test.py --pcm /path/to/mono-16khz.pcm --language en-US
```

Use headerless 16 kHz mono PCM16. Omitting `--language` uses automatic detection. The tool paces audio, closes the input, and waits for final results/session termination. It prints final transcripts intentionally, and returns failure on service errors or drain timeout.

On a supported Android device, check a short phrase, pause/resume, stop immediately after the last word, repeated phrases, network interruption, and editor switches. Inspect Settings → Transcription → Voice diagnostics for lifecycle and length messages without raw transcripts or credentials.
