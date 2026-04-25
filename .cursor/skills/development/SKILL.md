---
name: development
description: Local development guide for HeliBoard — building, installing on device, debugging, signing, and project structure. Use when setting up a local dev environment, running builds, installing APKs via ADB, or troubleshooting build issues.
---

# HeliBoard Development Guide

## Quick Build & Install

```bash
./gradlew assembleDebug && ./gradlew installDebug

# With debugging:
./gradlew assembleDebug && ./gradlew installDebug && adb logcat | grep -E "(voice|assemblyai|transcri)"
```

## Debug Gradle

```bash
./gradlew --stop && rm -rf ~/.gradle/caches/8.14 .gradle build app/build && ./gradlew clean assembleDebug
# Clear all cache:
./gradlew --stop && rm -rf ~/.gradle/caches/* .gradle build app/build && ./gradlew clean assembleDebug
```

## Build Variants

| Variant | Purpose |
|---------|---------|
| `debug` | Development testing — minified, debug suffix (.debug) |
| `debugNoMinify` | Fast IDE builds — no minification |
| `release` | Production — minified, optimized |
| `nouserlib` | Release without user-provided libraries |
| `runTests` | CI testing |

### Build commands

```bash
./gradlew assembleDebug          # Debug (recommended for dev)
./gradlew assembleRelease        # Release (requires signing)
./gradlew assembleDebugNoMinify  # Fast debug without minification
./gradlew assemble               # All variants
```

**APK output**: `app/build/outputs/apk/<variant>/`

## Installing on Device

### Via ADB

```bash
adb install -r app/build/outputs/apk/debug/HeliBoard_3.6-debug.apk
```

### ADB setup (macOS)

```bash
brew install android-platform-tools
adb kill-server && sudo adb start-server && adb devices
```

### USB debugging

1. Settings → About Phone → tap "Build Number" 7 times
2. Settings → Developer Options → enable "USB Debugging"
3. Connect via USB and accept the debugging prompt

## Signing Release APKs

### Create keystore (one-time)

```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
```

### Configure signing

Create `keystore.properties` in project root (add to `.gitignore`):

```properties
storeFile=/path/to/my-release-key.jks
storePassword=your-store-password
keyAlias=my-key-alias
keyPassword=your-key-password
```

## Running Tests

```bash
./gradlew test                       # All unit tests
./gradlew testDebugUnitTest          # Debug variant
./gradlew testDebugUnitTestCoverage  # With coverage
```

## Debugging

```bash
adb logcat -s LatinIME:V             # HeliBoard logs
adb logcat | grep -i heliboard       # All logs
```

### Common issues

| Issue | Fix |
|-------|-----|
| NDK not found | Install NDK 28.0.13004108 via SDK Manager |
| Gradle sync fails | `./gradlew clean && rm -rf ~/.gradle/caches/ && ./gradlew build` |
| Memory error | Increase `org.gradle.jvmargs` in `gradle.properties` (default is `-Xmx1024m`; try `-Xmx2048m`) |

## Project Structure

```
heliboard/
├── app/src/main/java/    # Kotlin/Java source
├── app/src/main/jni/     # Native C++ (dictionary)
├── app/src/main/res/     # Android resources
├── app/src/main/assets/  # Layouts, dictionaries
├── app/src/test/         # Unit tests
├── tools/                # Build tools, SDK setup
├── keystore/             # Shared debug keystore
├── .cursor/skills/       # Agent skill docs (architecture, guides)
└── dist/                 # Cloud-built APK output (see build-apk skill)
```

## Useful Gradle Tasks

```bash
./gradlew tasks              # List all tasks
./gradlew clean              # Clean build
./gradlew lint               # Lint report
./gradlew dependencies       # Show dependencies
```

## Glide Typing (Optional)

Requires closed-source `libjni_latinimegoogle.so` files placed in `app/src/main/jniLibs/<abi>/`. See [OpenBoard swypelibs](https://github.com/erkserkserks/openboard/tree/46fdf2b550035ca69299ce312fa158e7ade36967/app/src/main/jniLibs) for source.

## Keep this skill up to date

If the build flow, project structure, or development workflow changes, update this skill file immediately.
