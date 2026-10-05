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

HeliBoard uses [Microsoft MAI-Transcribe-2-Streaming](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming) through Azure's Realtime WebSocket API.

Deploy the model in Microsoft Foundry, then enter your **Azure resource endpoint**, **deployment name**, and **resource API key** in **Settings → Transcription**. Use a resource root such as `https://your-resource.services.ai.azure.com`; the deployment name is the one you chose in Foundry. Grant microphone permission and tap the fixed right-edge mic to dictate. Onboarding links to the same configuration screen.

The app streams mono 16 kHz PCM audio, requests completed transcripts at local pauses, and inserts completed segments into the editor. Pause commits speech and allows resume on the same connection. Stop keeps processing until all requested final results have arrived. Local punctuation and filler cleanup runs after each insertion. Language detection, local pause duration, silence threshold, and auto-stop are configurable.

See [the MAI pipeline guide](docs/mai-transcription.md) for architecture, error handling, settings, protocol tests, and a credentialed smoke test. [Microsoft's Realtime guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-realtime) provides deployment prerequisites and current service capabilities.

## Related docs

- Fullapp architecture: `docs/fullapp-keyboard.md`
- MAI transcription pipeline: `docs/mai-transcription.md`
- Agent instructions: `AGENTS.md`
- MAI API notes for this app: `.cursor/skills/voice-transcription/api-reference.md`
