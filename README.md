# HeliBoard

HeliBoard is an open-source keyboard, based on AOSP / OpenBoard.
Read original README.md:
https://github.com/Helium314/HeliBoard

## This project rewrites HeliBoard with custom experimental features

1. Voice to text (using OpenAI Whisper API)
2. Smart auto-capitalization
3. UI features

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
