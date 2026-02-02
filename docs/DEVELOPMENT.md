# HeliBoard Development Guide

This guide covers how to set up your development environment, build, debug, and install HeliBoard on your Android device.

# Install app on phone

To install HeliBoard permanently on your phone (sideloading), you have two options:
Option 1: Debug Build (Easiest)
Debug APKs are automatically signed and work fine for personal use:

Build

```
./gradlew assembleDebug

adb install -r app/build/outputs/apk/release/HeliBoard_3.6-release.apk
```

Install adb

```
brew install android-platform-tools
```

Check if Mac Sees the Device at All

```
system_profiler SPUSBDataType | grep -A 5 -i android
```

Restart ADB

```
adb kill-server
sudo adb start-server
adb devices
```

## Prerequisites

### System Requirements

| Requirement             | Version                           |
| ----------------------- | --------------------------------- |
| **Java JDK**            | 17 or higher (JDK 21 recommended) |
| **Android Studio**      | Ladybug (2024.2.1) or newer       |
| **Android SDK**         | API Level 35 (Android 15)         |
| **Android NDK**         | 28.0.13004108                     |
| **Gradle**              | 8.14 (managed by wrapper)         |
| **Min Android Version** | Android 5.0 (API 21)              |

### No API Keys Required

**HeliBoard does not require any API keys or external services.** It is designed to be 100% offline and privacy-focused. There are no:

- Google API keys
- Firebase configuration
- Analytics services
- Network permissions

## Setting Up Development Environment

### Option 1: Android Studio (Recommended)

1. **Install Android Studio**

   - Download from: https://developer.android.com/studio
   - Install with default settings

2. **Clone and Open Project**

   ```bash
   git clone <your-repository-url>
   cd heliboard
   ```

   - Open Android Studio
   - Select "Open" and navigate to the project folder
   - Wait for Gradle sync to complete

3. **Install SDK Components**
   Android Studio will prompt you to install missing SDK components. Accept all prompts, or manually install via:

   - Go to `Tools > SDK Manager`
   - **SDK Platforms tab**: Install Android 15 (API 35)
   - **SDK Tools tab**: Install:
     - Android SDK Build-Tools 35
     - NDK (Side by side) version 28.0.13004108
     - CMake (latest)

4. **Sync Gradle**
   - Click "Sync Project with Gradle Files" (elephant icon in toolbar)
   - Wait for sync to complete

### Option 2: Command Line Only

1. **Install Java JDK 17+**

   ```bash
   # Ubuntu/Debian
   sudo apt install openjdk-17-jdk

   # macOS (using Homebrew)
   brew install openjdk@17

   # Verify installation
   java -version
   ```

2. **Install Android SDK**

   ```bash
   # Download command line tools from:
   # https://developer.android.com/studio#command-line-tools-only

   # Set environment variables (add to ~/.bashrc or ~/.zshrc)
   export ANDROID_HOME=$HOME/Android/Sdk
   export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
   export PATH=$PATH:$ANDROID_HOME/platform-tools
   ```

3. **Install Required SDK Components**
   ```bash
   sdkmanager "platforms;android-35"
   sdkmanager "build-tools;35.0.0"
   sdkmanager "ndk;28.0.13004108"
   ```

## Building the App

### Build Variants

| Variant         | Purpose                   | Features                                         |
| --------------- | ------------------------- | ------------------------------------------------ |
| `debug`         | Development testing       | Minified, debug suffix (.debug)                  |
| `debugNoMinify` | Fast IDE builds           | No minification, faster builds                   |
| `release`       | Production                | Minified, optimized                              |
| `nouserlib`     | Release without user libs | Like release, but blocks user-provided libraries |
| `runTests`      | CI testing                | For running automated tests                      |

### Building via Android Studio

1. Select build variant from: `Build > Select Build Variant`
2. Build APK: `Build > Build Bundle(s) / APK(s) > Build APK(s)`
3. Find APK at: `app/build/outputs/apk/<variant>/`

### Building via Command Line

```bash
# Debug build (recommended for development)
./gradlew assembleDebug

# Release build (requires signing - see below)
./gradlew assembleRelease

# Fast debug build without minification
./gradlew assembleDebugNoMinify

# Build all variants
./gradlew assemble
```

**APK Output Locations:**

- Debug: `app/build/outputs/apk/debug/HeliBoard_3.6-debug.apk`
- Release: `app/build/outputs/apk/release/HeliBoard_3.6-release.apk`

## Signing the Release APK

For release builds, you need to sign the APK:

### Create a Keystore (One-time setup)

```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-key-alias
```

### Configure Signing in Gradle

Create `keystore.properties` in project root (add to `.gitignore`):

```properties
storeFile=/path/to/my-release-key.jks
storePassword=your-store-password
keyAlias=my-key-alias
keyPassword=your-key-password
```

Add to `app/build.gradle.kts`:

```kotlin
android {
    signingConfigs {
        create("release") {
            val props = java.util.Properties()
            props.load(rootProject.file("keystore.properties").inputStream())
            storeFile = file(props["storeFile"] as String)
            storePassword = props["storePassword"] as String
            keyAlias = props["keyAlias"] as String
            keyPassword = props["keyPassword"] as String
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            // ... existing config
        }
    }
}
```

## Installing on Your Android Device

### Method 1: Via Android Studio

1. Enable **Developer Options** on your Android device:

   - Go to `Settings > About Phone`
   - Tap "Build Number" 7 times

2. Enable **USB Debugging**:

   - Go to `Settings > Developer Options`
   - Enable "USB Debugging"

3. Connect device via USB and accept the debugging prompt

4. In Android Studio, select your device from the device dropdown

5. Click **Run** (green play button) or press `Shift+F10`

### Method 2: Via ADB Command Line

```bash
# Build the APK
./gradlew assembleDebug

# Install on connected device
adb install app/build/outputs/apk/debug/HeliBoard_3.6-debug.apk

# Or install and replace existing version
adb install -r app/build/outputs/apk/debug/HeliBoard_3.6-debug.apk
```

### Method 3: Transfer APK Manually

1. Build the APK
2. Transfer `HeliBoard_*.apk` to your device (email, cloud storage, USB transfer)
3. On device, enable "Install from unknown sources" in settings
4. Open the APK file and install

## Running Tests

```bash
# Run all unit tests
./gradlew test

# Run specific test variant
./gradlew testDebugUnitTest

# Run with coverage
./gradlew testDebugUnitTestCoverage
```

## Debugging

### Log Output

```bash
# View HeliBoard logs
adb logcat -s LatinIME:V

# View all logs (verbose)
adb logcat | grep -i heliboard
```

### Common Issues

**1. NDK not found**

```
NDK not configured. Download it with SDK Manager.
```

Solution: Install NDK 28.0.13004108 via SDK Manager

**2. Gradle sync fails**

```bash
# Clear Gradle cache
./gradlew clean
rm -rf ~/.gradle/caches/
./gradlew build
```

**3. Build fails with memory error**
Add to `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m
```

## Optional: Enable Glide Typing

HeliBoard supports glide typing but requires a closed-source library that is **not included** in the repository for licensing reasons.

To enable glide typing:

1. Obtain the swype library files (`libjni_latinimegoogle.so`) from:

   - GApps packages (search for "swypelibs")
   - Or download from: https://github.com/erkserkserks/openboard/tree/46fdf2b550035ca69299ce312fa158e7ade36967/app/src/main/jniLibs

2. Place the `.so` files in:

   ```
   app/src/main/jniLibs/
   ├── arm64-v8a/
   │   └── libjni_latinimegoogle.so
   ├── armeabi-v7a/
   │   └── libjni_latinimegoogle.so
   ├── x86/
   │   └── libjni_latinimegoogle.so
   └── x86_64/
       └── libjni_latinimegoogle.so
   ```

3. Rebuild the app

## Project Structure

```
heliboard/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/         # Kotlin/Java source code
│   │   │   ├── jni/          # Native C++ code for dictionary
│   │   │   ├── res/          # Android resources
│   │   │   └── assets/       # Layouts, dictionaries
│   │   └── test/             # Unit tests
│   └── build.gradle.kts      # App build configuration
├── gradle/                   # Gradle wrapper
├── tools/                    # Build tools (emoji generator)
├── build.gradle.kts          # Root build configuration
└── layouts.md                # Custom layout documentation
```

## Useful Gradle Tasks

```bash
# List all tasks
./gradlew tasks

# Clean build
./gradlew clean

# Check for dependency updates
./gradlew dependencyUpdates

# Generate lint report
./gradlew lint

# Show project dependencies
./gradlew dependencies
```

## Adding Custom Dictionaries

HeliBoard supports custom dictionaries for word suggestions:

1. Download dictionaries from: https://codeberg.org/Helium314/aosp-dictionaries
2. Place `.dict` files in device storage
3. In HeliBoard settings, go to "Languages & Layouts" > select language > "Add Dictionary"
4. Select the dictionary file

## Customizing Layouts

See [layouts.md](layouts.md) for detailed documentation on:

- Creating custom keyboard layouts
- Modifying symbol/number layouts
- Adding new language layouts

## Resources

- **Project Repository**: https://github.com/Helium314/HeliBoard
- **Wiki & FAQ**: https://github.com/Helium314/HeliBoard/wiki
- **Discussions**: https://github.com/Helium314/HeliBoard/discussions
- **Issue Tracker**: https://github.com/Helium314/HeliBoard/issues
- **Dictionaries**: https://codeberg.org/Helium314/aosp-dictionaries
