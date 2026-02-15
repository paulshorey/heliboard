# Build this project in Cursor AI Cloud and download an installable APK

This guide is specific to the current HeliBoard codebase in this repository.

## 0) Current project requirements (from Gradle files)

- Java: **17**
- Android SDK Platform: **35**
- Android Build Tools: **35.0.0**
- Android NDK: **28.0.13004108**
- Gradle wrapper: **8.14** (already included in this repo)
- Main module: `:app`
- Installable test build: `debug` (applicationId suffix: `.debug`)

---

## 1) Install required Linux packages in Cursor Cloud

Run in the repo root (`/workspace`):

```bash
sudo apt-get update
sudo apt-get install -y openjdk-17-jdk unzip wget zip ca-certificates
```

Verify Java:

```bash
java -version
```

---

## 2) Install Android command-line tools + SDK/NDK

```bash
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"

cd /tmp
wget -O commandlinetools-linux.zip "https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip"
unzip -q -o commandlinetools-linux.zip -d "$ANDROID_SDK_ROOT/cmdline-tools"

# Replace old "latest" folder if it exists.
rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
mv "$ANDROID_SDK_ROOT/cmdline-tools/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"

export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0" "ndk;28.0.13004108"
```

Optional: persist Android env vars for future shell sessions:

```bash
cat <<'EOF' >> ~/.bashrc
export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
EOF

# Load the vars in the current shell now.
source ~/.bashrc
```

Create `local.properties` in this repo so Gradle can always resolve the SDK path:

```bash
cd /workspace
cat > local.properties <<'EOF'
sdk.dir=/home/ubuntu/Android/Sdk
EOF
```

---

## 3) Build an installable APK (recommended: debug)

From repo root:

```bash
cd /workspace
chmod +x ./gradlew
./gradlew :app:assembleDebug
```

For faster iteration builds (no minify), you can also use:

```bash
./gradlew :app:assembleDebugNoMinify
```

---

## 4) Collect APK into a simple downloadable folder

```bash
cd /workspace
mkdir -p dist
cp app/build/outputs/apk/debug/*.apk dist/
ls -lh dist/*.apk
```

Expected file pattern:

- `dist/HeliBoard_*-debug.apk`

---

## 5) Download and install on your Android phone

1. In Cursor, download the APK from `dist/`.
2. Transfer it to your phone (Drive, email, USB, etc.).
3. On the phone, allow install from unknown sources for your file manager/browser.
4. Open the APK and install.

For future Cursor AI Cloud changes, rebuild with the same steps and install the newer debug APK over the old one.

---

## 6) Repeatable command set for future development cycles

```bash
cd /workspace
./gradlew :app:assembleDebug
mkdir -p dist
cp app/build/outputs/apk/debug/*.apk dist/
ls -lh dist/*.apk
```

---

## 7) Troubleshooting

- **`sdkmanager: command not found`**
  - Re-export PATH:
    ```bash
    export ANDROID_SDK_ROOT="$HOME/Android/Sdk"
    export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"
    ```

- **License errors during build**
  - Run:
    ```bash
    yes | sdkmanager --licenses
    ```

- **Gradle cache/build corruption**
  - Run:
    ```bash
    ./gradlew --stop
    rm -rf .gradle build app/build
    ./gradlew :app:assembleDebug
    ```

- **Need a production release APK**
  - `./gradlew :app:assembleRelease` builds the release variant, but production distribution requires proper signing configuration/keystore management.
