#!/usr/bin/env bash
# Source this from build/test helpers. Discovery only: no downloads or machine settings.

heliboard_android_env() {
  local root sdk candidate major
  root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  sdk=""
  if [[ -f "$root/local.properties" ]]; then
    sdk="$(sed -n 's/^sdk\.dir=//p' "$root/local.properties" | tail -1 | tr -d '\r')"
  fi
  if [[ -z "$sdk" ]]; then
    for candidate in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" "$root/.android-sdk"; do
      if [[ -d "$candidate/platforms/android-35" && -d "$candidate/ndk/28.0.13004108" ]]; then sdk="$candidate"; break; fi
    done
  fi
  if [[ ! -d "$sdk/platforms/android-35" || ! -d "$sdk/ndk/28.0.13004108" || ! -x "$sdk/build-tools/35.0.0/apksigner" ]]; then
    echo "Android SDK platform 35, build-tools 35.0.0 and NDK 28.0.13004108 are required. See .codex/skills/android-workspace-setup/SKILL.md." >&2
    return 1
  fi
  if [[ -z "${JAVA_HOME:-}" && -x '/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/java' ]]; then
    export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
  fi
  if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
  if ! command -v java >/dev/null || ! command -v javac >/dev/null; then
    echo "A JDK is required; use Android Studio's JDK 21 or set JAVA_HOME." >&2
    return 1
  fi
  major="$(java -version 2>&1 | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
  if [[ ! "$major" =~ ^[0-9]+$ ]] || (( major < 17 || major > 24 )); then
    echo "Use JDK 17–24 with the Gradle 8.14 wrapper; JDK 21 is recommended (found ${major:-unknown})." >&2
    return 1
  fi
  export ANDROID_HOME="$sdk" ANDROID_SDK_ROOT="$sdk"
  export PATH="$sdk/platform-tools:$sdk/emulator:$sdk/cmdline-tools/latest/bin:$PATH"
  # Fresh worktrees have no local.properties. Preserve an existing Android Studio file.
  if [[ ! -f "$root/local.properties" ]]; then printf 'sdk.dir=%s\n' "$sdk" > "$root/local.properties"; fi
}

heliboard_android_env
