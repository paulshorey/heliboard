---
name: android-workspace-setup
description: Set up the Android SDK for cloud agents and CI environments. Use when Gradle builds fail due to missing SDK, when setting up a new development environment, or when troubleshooting build tool configuration.
---

# Android Workspace Setup

Gradle builds (`./gradlew :app:compileDebugKotlin`) work out of the box in Cursor cloud agents. `.cursor/environment.json` runs `./tools/setup-android-sdk.sh` automatically at startup.

## What the setup configures

- **SDK path**: `/workspace/.android-sdk` (platform-tools, platforms;android-35, build-tools;35.0.0, NDK 28)
- **local.properties**: `sdk.dir=/workspace/.android-sdk` (Gradle reads this)
- **Env vars**: `ANDROID_HOME`, `ANDROID_SDK_ROOT` (set in `.cursor/environment.json`)

## Setup script

The setup is handled by `./tools/setup-android-sdk.sh`, which:

1. Downloads Android command-line tools if not present
2. Accepts SDK licenses non-interactively
3. Installs platform-tools, platform 35, build-tools 35.0.0, NDK 28.0.13004108
4. Creates `local.properties` with `sdk.dir`
5. Exports `ANDROID_HOME` and `ANDROID_SDK_ROOT` for the current shell

**Platform note**: The script downloads Linux command-line tools (`commandlinetools-linux-*`). It is designed for Linux cloud agents and CI. On macOS, set `ANDROID_SDK_ROOT` to your existing Android Studio SDK path (typically `~/Library/Android/sdk`) before running — the script skips the download when `sdkmanager` already exists and only ensures the required components are installed.

## Manual setup (CI or other environments)

1. Run `./tools/setup-android-sdk.sh` once. This installs the Android SDK to `/workspace/.android-sdk` (or `$ANDROID_SDK_ROOT` if already set), creates `local.properties` with `sdk.dir`, and sets `ANDROID_HOME`/`ANDROID_SDK_ROOT` when sourced.
2. After setup, `./gradlew :app:compileDebugKotlin` works out of the box (Gradle reads `local.properties`).
3. To get env vars in the current shell: `source ./.android-env` or `source ./tools/setup-android-sdk.sh` (idempotent if SDK already installed).

## macOS local development

If you have Android Studio installed, Gradle already finds the SDK through `local.properties` (created by Android Studio) or the `ANDROID_HOME` env var. You do **not** need to run the setup script. See the [development skill](../development/SKILL.md) for local build commands.

## Environment JSON

`.cursor/environment.json` configures automatic setup for Cursor cloud agents:

```json
{
  "install": "./tools/setup-android-sdk.sh",
  "env": {
    "ANDROID_HOME": "/workspace/.android-sdk",
    "ANDROID_SDK_ROOT": "/workspace/.android-sdk"
  }
}
```

## System requirements

| Requirement | Version |
|-------------|---------|
| **Java JDK** | 17 or higher (JDK 21 recommended) |
| **Android SDK** | API Level 35 (Android 15) |
| **Android NDK** | 28.0.13004108 |
| **Gradle** | 8.14 (managed by wrapper) |
| **Min Android Version** | Android 5.0 (API 21) |

## Keep this skill up to date

If the SDK setup flow, required components, or environment configuration changes, update this skill file immediately.
