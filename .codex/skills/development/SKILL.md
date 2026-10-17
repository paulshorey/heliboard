---
name: development
description: Build, validate, install and debug HeliBoard locally with Gradle, Android Studio and ADB. Use for Android development checks or device/emulator testing; use android-build-apk for PR handoff.
---

# Local Android development

Use the command line for repeatable checks; Android Studio is useful for SDK/AVD
management, visual inspection and debugging. Run from the repository root:

```bash
./tools/android-dev.sh doctor   # SDK, JDK, devices, available AVDs
./tools/android-dev.sh compile  # quick Kotlin compilation
./tools/android-dev.sh smoke    # InputLogic + voice JVM/Robolectric tests
./tools/android-dev.sh full     # :app:testRunTestsUnitTest (CI variant)
./tools/android-dev.sh lint     # :app:lintDebug; report any existing baseline failures
./tools/android-dev.sh apk      # canonical signed/minified dist/HeliBoard.apk
```

Choose checks for the changed behavior; a build alone does not test typing or mic
behavior. `runTests` skips known failing/network checks by project convention;
report that limitation. Reports are in `app/build/reports/tests/` and
`app/build/reports/lint-results-debug.html`. For focused Gradle commands, first
`source tools/android-env.sh` in Bash. Avoid an unfiltered `./gradlew test`, which
runs multiple variants and includes external link checks.

For an already connected, authorized phone or disposable emulator:

```bash
./tools/android-dev.sh install SERIAL  # adb install -r; verifies APK freshness
```

Use an explicit serial from `adb devices -l`. Enabling/selecting a keyboard changes
the device's active input method; do that only on the intended test device and
record/restore its prior IME. Never uninstall to work around a signature mismatch:
that deletes settings, API keys, dictionaries and drafts. The checked-in debug key
is intentionally shared so the `.debug` app can be updated in place.

For IDE iteration, `:app:assembleDebugNoMinify` is faster and debuggable. Use
`debug` for PR artifacts; `release`/`nouserlib` are maintainer workflows. Do not
invent a release keystore configuration: inspect `app/build.gradle.kts` first.

Diagnose the first actual Gradle failure. Check SDK/JDK and rerun the failing task
with `--stacktrace` before changing caches. Scope any cleanup to a demonstrated
corrupt artifact; shared cache deletion disrupts other checkouts. ADB runs as the
current user. Collect only relevant app logs and redact text, credentials and
query strings before sharing.

The manual acceptance matrix, Android Studio workflow, emulator commands and
staged instrumentation plan are in `docs/agent-development.md`.
