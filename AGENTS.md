# HeliBoard

HeliBoard is an Android app, open-source project based on AOSP / OpenBoard keyboard.

## This project rewrites HeliBoard with custom experimental features

1. Voice to text (using Deepgram Nova-3 streaming transcription + local post-processing)
2. Smart auto-capitalization
3. UI features

## Voice to text UI

1. User taps microphone button in the top right corner of the keyboard
2. Recording starts immediately on-device
3. PCM audio streams continuously to Deepgram while silence timers run locally
4. Deepgram returns finalized transcript spans in order
5. Apply local post-processing to each finalized transcript span
6. Immediately insert the processed text at the current caret position through `InputConnection`

### Voice transcription ownership

1. Deepgram streaming transcription:
   `app/src/main/java/helium314/keyboard/latin/voice/DeepgramTranscriptionClient.kt`
2. Post-transcription text preparation:
   `app/src/main/java/helium314/keyboard/latin/voice/VoicePostTranscriptionFilter.java`
3. Insert-at-caret orchestration:
   `app/src/main/java/helium314/keyboard/latin/LatinIME.java`

`VoiceInputManager.kt` sits between these layers. It owns recording flow, streams audio to
Deepgram, preserves FIFO ordering for finalized transcript spans, and forwards each span to
`LatinIME`.

## Handling chunked audio recordings

1 ChunkA audio frames → Deepgram
2 ChunkB audio frames continue streaming while ChunkA is being finalized
3 ChunkA transcription received → `onTranscriptionResult(textA)`
4 `VoicePostTranscriptionFilter.prepareForInsertion(textA, textBeforeCursor)` runs on `textA`
5 `LatinIME` commits `textA` immediately at the caret via `InputConnection`
6 ChunkB transcription arrives later in FIFO order
7 `VoicePostTranscriptionFilter.prepareForInsertion(textB, textBeforeCursor)` runs on `textB`
8 `LatinIME` commits `textB` immediately at the caret

## Fullapp keyboard

See [docs/fullapp-keyboard.md](docs/fullapp-keyboard.md) for architecture and lessons learned.

**Key rule**: The extract view is a mirror of the app's field. All text input (typing, voice) must go through `InputConnection` to the app — never write to the extract view directly. The framework syncs app → extract view via `setExtractedText()`.

## Android workspace setup (cloud agents)

Gradle builds (`./gradlew :app:compileDebugKotlin`) work out of the box in Cursor cloud agents. `.cursor/environment.json` runs `./tools/setup-android-sdk.sh` automatically at startup.

**What the setup configures:**
- **SDK path**: `/workspace/.android-sdk` (platform-tools, platforms;android-35, build-tools;35.0.0, NDK 28)
- **local.properties**: `sdk.dir=/workspace/.android-sdk` (Gradle reads this)
- **Env vars**: `ANDROID_HOME`, `ANDROID_SDK_ROOT` (set in environment.json)

**Manual setup (CI or other environments):**
1. Run `./tools/setup-android-sdk.sh` once. This installs the Android SDK to `/workspace/.android-sdk`, creates `local.properties` with `sdk.dir`, and sets `ANDROID_HOME`/`ANDROID_SDK_ROOT` when sourced.
2. After setup, `./gradlew :app:compileDebugKotlin` works out of the box (Gradle reads `local.properties`).
3. To get env vars in the current shell: `source ./.android-env` or `source ./tools/setup-android-sdk.sh` (idempotent if SDK already installed).

## Shared debug keystore

Debug builds use `keystore/debug.keystore` so local Gradle builds and cloud-agent builds produce APKs with the **same signature**. You can install a cloud-built APK over a locally-built install (or vice versa) without uninstalling, preserving user preferences and data.

**One-time migration** (if you had HeliBoard installed from a different machine's Gradle debug build):
1. Backup app data: `adb backup -f heliboard.ab -noapk helium314.keyboard.debug`
2. Uninstall the existing app
3. Install the new APK (from cloud or local; both now use the shared key)
4. Restore data: `adb restore heliboard.ab`

After migration, you can switch between local and cloud builds without losing data.
