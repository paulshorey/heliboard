# HeliBoard

HeliBoard is an Android keyboard app based on AOSP / OpenBoard, extended here with experimental features such as voice-to-text, smart auto-capitalization, and the standalone "full app" editing mode.

## Build an installable APK

To generate the phone-installable debug APK and place it in the canonical repository location, run:

```bash
./tools/build-dist-apk.sh
```

This script:

- sets up the Android SDK for the current shell if needed
- builds the debug APK with Gradle
- removes older files from `./dist`
- writes the latest installable artifact into `./dist/`

Only one installable APK should exist in `./dist` at a time, and regenerating it should overwrite the previous artifact.

## Run in debug mode

```
./gradlew installDebug && adb logcat -c && adb logcat -v time | grep -E 'LatinIME|VoiceInputManager|MaiTranscription|VoiceRecorder|VOICE_'
```

or just install without logs:

```
./gradlew installDebug
```

## Remove disfluencies

app/src/main/java/helium314/keyboard/latin/voice/TranscriptPostProcessor.kt
line 14

```
object TranscriptPostProcessor {

    data class Rule(val find: String, val replace: String)

    val rules: List<Rule> = buildRules()

    private val disfluencyReplacements = listOf(
        Rule("—", ""),
        Rule(", hmm.", ""),
        Rule(" hmm.", ""),
        Rule("hmm.", ""),
        Rule(", um.", "."),
        Rule(" um.", "."),
        Rule(", uh.", "."),
        Rule(" uh.", "."),
        Rule(", and.", "."),
        Rule(" and.", "."),
    )
```

---

## MAI voice transcription

HeliBoard uses [Microsoft MAI-Transcribe-2-Streaming](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming) directly through **Azure Speech SDK 1.52.0** for Android.

Create an Azure Speech resource in **Central US**, **Sweden Central**, or **Southeast Asia**. Enter its **API key** and matching **region** (`centralus`, `swedencentral`, or `southeastasia`) in **Settings → Transcription**. Grant microphone permission and tap the fixed right-edge mic. Microsoft hosts the model; no model deployment name is needed. SDK dictation requires Android 8.0+ on ARM or x86-64; the keyboard remains available on older supported Android versions.

The app streams mono 16 kHz PCM audio, requests finals at local pauses, and inserts completed segments through the editor. Pause allows resume on the same SDK session. Stop closes the audio input and waits for final results before disposing the recognizer. Language hints, local silence controls, auto-stop, and transcript formatting remain configurable.

See [the MAI pipeline guide](docs/mai-transcription.md) for account setup, architecture, lifecycle tests, and a credentialed check. [Microsoft's Speech SDK guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk) provides current model capabilities and supported regions.

## Related docs

- Fullapp architecture: `docs/fullapp-keyboard.md`
- MAI transcription pipeline: `docs/mai-transcription.md`
- Agent instructions: `AGENTS.md`
- MAI API notes for this app: `.cursor/skills/voice-transcription/api-reference.md`
