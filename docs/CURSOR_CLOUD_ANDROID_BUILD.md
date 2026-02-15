# Cursor Agent Skill: Build installable Android APK into `dist/`

## Skill intent

Use this skill when the goal is to produce an installable APK artifact for this repository in:

- `/workspace/dist/`

This skill is for automated execution by a Cursor cloud agent only.

## Repository-specific build requirements

Read from this repo:

- App module: `:app`
- Required SDK Platform: `android-35`
- Required Build Tools: `35.0.0`
- Required NDK: `28.0.13004108`
- Java target: `17`
- Primary build task: `:app:assembleDebug`

## Execution policy

1. Run commands from `/workspace` unless explicitly noted.
2. Ensure Android SDK + NDK are present before building.
3. Ensure Gradle can resolve SDK via `local.properties`.
4. Produce APK artifacts in `/workspace/dist/`.
5. Fail fast on command errors.

## Step-by-step command sequence

### 1) Install system dependencies

```bash
cd /workspace
export DEBIAN_FRONTEND=noninteractive
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk unzip wget zip ca-certificates
```

### 2) Install Android command-line tools and required SDK components

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

### 3) Configure SDK path for Gradle in this repo

```bash
cd /workspace
cat > local.properties <<EOF
sdk.dir=$HOME/Android/Sdk
EOF
```

### 4) Build debug APK

```bash
cd /workspace
chmod +x ./gradlew
./gradlew :app:assembleDebug
```

### 5) Collect artifacts into `dist/`

```bash
cd /workspace
mkdir -p dist
cp app/build/outputs/apk/debug/*.apk dist/
ls -lh dist/*.apk
```

## Expected result

At least one APK file exists matching:

- `/workspace/dist/HeliBoard_*-debug.apk`

## Recovery sequence (if build fails)

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
