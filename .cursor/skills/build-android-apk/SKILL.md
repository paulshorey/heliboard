---
name: build-android-apk
description: Build an installable HeliBoard debug APK in /workspace/dist when the user asks for an Android build artifact from this repository.
---

# Build Android APK into `dist/`

## When to use

- The user asks for a cloud build of this Android project.
- The user asks for an installable APK artifact.
- The user asks to refresh/update APK artifacts after code changes.

## Repository requirements

- Module: `:app`
- Android SDK Platform: `android-35`
- Build Tools: `35.0.0`
- NDK: `28.0.13004108`
- Java: `17`
- Build task: `:app:assembleDebug`

## Execution steps

Run commands exactly in this order.

```bash
cd /workspace
export DEBIAN_FRONTEND=noninteractive
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk unzip wget zip ca-certificates
```

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

cd /tmp
wget -O commandlinetools-linux.zip "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
unzip -q -o commandlinetools-linux.zip -d "$ANDROID_SDK_ROOT/cmdline-tools"
rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"

export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;28.0.13004108"
```

```bash
cd /workspace
cat > local.properties <<EOF
sdk.dir=$HOME/Android/Sdk
EOF
```

```bash
cd /workspace
chmod +x ./gradlew
./gradlew :app:assembleDebug
```

```bash
cd /workspace
mkdir -p dist
cp app/build/outputs/apk/debug/*.apk dist/
ls -lh dist/*.apk
```

## Success criteria

At least one file exists matching:

- `/workspace/dist/HeliBoard_*-debug.apk`

## Recovery sequence

If build fails, run:

```bash
cd /workspace
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
yes | sdkmanager --licenses
./gradlew --stop
rm -rf .gradle build app/build
./gradlew :app:assembleDebug
mkdir -p dist
cp app/build/outputs/apk/debug/*.apk dist/
ls -lh dist/*.apk
```
