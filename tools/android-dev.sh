#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"
case "${1:-help}" in
  help|--help|-h)
    echo "Usage: tools/android-dev.sh {doctor|compile|smoke|full|lint|apk|install SERIAL}"
    echo "doctor discovers installed tools; install updates only the explicitly named device."
    exit 0 ;;
  doctor|compile|smoke|full|lint|apk|install) mode="$1"; shift ;;
  *) echo "Unknown command: $1" >&2; exit 2 ;;
esac
source "$ROOT_DIR/tools/android-env.sh"
case "$mode" in
  doctor)
    echo "SDK: $ANDROID_SDK_ROOT"
    java -version
    echo "Platform 35 and NDK 28.0.13004108 found."
    if command -v adb >/dev/null; then adb devices -l; fi
    if command -v emulator >/dev/null; then emulator -list-avds; fi
    ;;
  compile) ./gradlew :app:compileDebugKotlin ;;
  smoke)
    ./gradlew :app:testDebugUnitTest \
      --tests helium314.keyboard.latin.InputLogicTest \
      --tests 'helium314.keyboard.latin.voice.*'
    ;;
  full) ./gradlew :app:testRunTestsUnitTest ;;
  lint) ./gradlew :app:lintDebug ;;
  apk) "$ROOT_DIR/tools/build-dist-apk.sh" ;;
  install)
    [[ $# -eq 1 && -n "$1" ]] || { echo "Supply one serial from adb devices -l." >&2; exit 2; }
    python3 tools/apk-handoff.py check
    adb -s "$1" install -r dist/HeliBoard.apk
    ;;
esac
