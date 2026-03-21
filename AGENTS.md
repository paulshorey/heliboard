# HeliBoard

HeliBoard is an Android app, open-source project based on AOSP / OpenBoard keyboard.

## This project rewrites HeliBoard with custom experimental features

1. Voice to text (using Deepgram Nova-3 streaming transcription + local post-processing)
2. Smart auto-capitalization
3. UI features

## Voice to text UI

1. User taps microphone button in the top right corner of the keyboard
2. Recording starts
3. After a period of silence, recording is chunked (stops to save the file and start processing, but restarts immediately)
4. Send recorded audio chunk to Deepgram API for transcription
5. Received transcribed text, apply post-processing
6. Received transcribed text, apply local post-processing
7. Immediately insert the processed text at the current caret position through `InputConnection`

## Handling chunked audio recordings

1 ChunkA audio → Deepgram
2 ChunkB audio queued in VoiceInputManager
3 ChunkA transcription received → onTranscriptionResult(textA)
4 Local post-processing runs on textA
5 textA is committed immediately at the caret via `InputConnection`
6 processNextSegment() → ChunkB sent to Deepgram
7 ChunkB transcription arrives in FIFO order
8 Local post-processing runs on textB
9 textB is committed immediately at the caret

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

## Installable APK artifact

When a task requires a phone-installable build artifact, generate a debug APK and save exactly one canonical copy at:

- `/workspace/dist/HeliBoard.apk`

Preferred flow:

1. Run `./tools/build-dist-apk.sh`

This helper script:

- sources `./tools/setup-android-sdk.sh`
- builds the debug APK with Gradle
- removes older files in `/workspace/dist`
- copies the generated APK to `/workspace/dist/HeliBoard.apk`

Always overwrite `/workspace/dist/HeliBoard.apk` with the latest build when regenerating it.

Required final step for task completion:

- After finishing any implementation task, run `./tools/build-dist-apk.sh` so `/workspace/dist/HeliBoard.apk` is up to date.
- Commit the rebuilt `/workspace/dist/HeliBoard.apk` to the current feature branch together with the task changes before giving the final summary.

## Shared debug keystore

Debug builds use `keystore/debug.keystore` so local Gradle builds and cloud-agent builds produce APKs with the **same signature**. You can install a cloud-built APK over a locally-built install (or vice versa) without uninstalling, preserving user preferences and data.

**One-time migration** (if you had HeliBoard installed from a different machine's Gradle debug build):
1. Backup app data: `adb backup -f heliboard.ab -noapk helium314.keyboard.debug`
2. Uninstall the existing app
3. Install the new APK (from cloud or local; both now use the shared key)
4. Restore data: `adb restore heliboard.ab`

After migration, you can switch between local and cloud builds without losing data.
