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
- writes the latest installable artifact to:
  - `./dist/HeliBoard.apk`

Only one installable APK should exist in `./dist` at a time, and regenerating it should overwrite the previous artifact.

## Related docs

- Fullapp architecture: `docs/fullapp-keyboard.md`
- Agent instructions: `AGENTS.md`

## Run in debug mode:
```
./gradlew installDebug && adb logcat -c && adb logcat -v time | grep -E 'LatinIME|VoiceInputManager|SpeechmaticsTranscription|VoiceRecorder|Speechmatics|VOICE_'
```
or just install without logs:
```
./gradlew installDebug
```

