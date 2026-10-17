---
name: android-workspace-setup
description: Prepare or diagnose HeliBoard's Android SDK, NDK and JDK on Codex desktop, fresh worktrees, Linux agents or CI. Use for missing SDK/JDK and Gradle environment failures.
---

# Android build environment

Start with `./tools/android-dev.sh doctor`. This discovers existing tools without
installing packages. `tools/android-env.sh` is sourceable from Bash and is reused
by all local build/test helpers. It reads `local.properties` first, then usable
`ANDROID_SDK_ROOT` / `ANDROID_HOME`, Android Studio's macOS SDK, Linux's
`~/Android/Sdk`, or `.android-sdk`. Missing worktree `local.properties` is created;
an existing file is preserved. Invalid environment paths are skipped.

On macOS, install Android Studio if missing and use its SDK Manager for platform
35, build-tools 35.0.0, platform-tools and NDK 28.0.13004108. With no `JAVA_HOME`,
the helper selects Android Studio's bundled JDK. JDK 21 is recommended; the Gradle
8.14 wrapper supports JDK 17–24. Supply `JAVA_HOME` for a different compatible JDK.
No global shell settings, sudo ADB server, cache deletion, or SDK download is needed
when these tools already exist. See [development](../development/SKILL.md).

For a fresh Linux agent:

```bash
./tools/agent-environment-startup.sh
source ./.android-env
./tools/android-dev.sh doctor
```

The bootstrap installs host packages and SDK components, accepts SDK licenses,
writes ignored local environment files and compiles. Use `--skip-compile` for
setup only, `--verify` for InputLogic/voice tests, `--full-tests` for the CI suite,
or `--apk` for the canonical build. SDK-only installation is available through
`./tools/setup-android-sdk.sh`. It supports Linux and macOS command-line tools.
Do not point a macOS shell at a Linux SDK installation.

There is no app server or database service to start. Clipboard data lives in
on-device SQLite. Azure Speech keys and regions belong in app settings, never in agent config.
For Codex desktop setup scripts/actions and emulator work, read
`docs/agent-development.md`; app-generated local-environment files have their own
schema and must not be guessed from Cursor's former `environment.json`.
