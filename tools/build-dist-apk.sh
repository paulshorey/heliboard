#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
CANONICAL_APK="$DIST_DIR/HeliBoard.apk"
DEBUG_APK_DIR="$ROOT_DIR/app/build/outputs/apk/debug"

cd "$ROOT_DIR"

source "$ROOT_DIR/tools/setup-java.sh"

if [[ -f "$ROOT_DIR/local.properties" ]] && grep -q '^sdk\.dir=' "$ROOT_DIR/local.properties"; then
  echo "Using existing SDK from local.properties"
else
  source "$ROOT_DIR/tools/setup-android-sdk.sh"
fi

./gradlew :app:assembleDebug

shopt -s nullglob
debug_apks=("$DEBUG_APK_DIR"/HeliBoard_*-debug.apk)
shopt -u nullglob

if [[ "${#debug_apks[@]}" -ne 1 ]]; then
  echo "Expected exactly one debug APK in $DEBUG_APK_DIR, found ${#debug_apks[@]}." >&2
  exit 1
fi

mkdir -p "$DIST_DIR"
rm -f "$DIST_DIR"/*
cp "${debug_apks[0]}" "$CANONICAL_APK"

echo "Installable APK written to $CANONICAL_APK"
