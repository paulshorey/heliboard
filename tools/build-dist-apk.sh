#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
CANONICAL_APK="$DIST_DIR/HeliBoard.apk"
DEBUG_APK_DIR="$ROOT_DIR/app/build/outputs/apk/debug"

cd "$ROOT_DIR"

source "$ROOT_DIR/tools/android-env.sh"
SOURCE_SHA256="$(python3 tools/apk-handoff.py snapshot)"

./gradlew :app:assembleDebug

shopt -s nullglob
debug_apks=("$DEBUG_APK_DIR"/HeliBoard_*-debug.apk)
shopt -u nullglob

if [[ "${#debug_apks[@]}" -ne 1 ]]; then
  echo "Expected exactly one debug APK in $DEBUG_APK_DIR, found ${#debug_apks[@]}." >&2
  exit 1
fi

mkdir -p "$DIST_DIR"
"$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" verify "${debug_apks[0]}"
cp "${debug_apks[0]}" "$CANONICAL_APK.tmp"
mv "$CANONICAL_APK.tmp" "$CANONICAL_APK"
python3 tools/apk-handoff.py record --source-sha256 "$SOURCE_SHA256"

echo "Installable APK written to $CANONICAL_APK"
