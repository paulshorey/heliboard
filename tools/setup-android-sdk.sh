#!/usr/bin/env bash
# Setup Android SDK for cloud agents / CI.
# Run this script before building. Creates local.properties and installs required components.
set -e

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/workspace/.android-sdk}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo "Android SDK will be installed to: $ANDROID_SDK_ROOT"

# Install command-line tools if not present
if [[ ! -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  echo "Downloading Android command-line tools..."
  mkdir -p "$ANDROID_SDK_ROOT"
  tmpdir=$(mktemp -d)
  trap "rm -rf $tmpdir" EXIT
  curl -fsSL -o "$tmpdir/cmdline-tools.zip" "$CMDLINE_TOOLS_URL"
  unzip -q "$tmpdir/cmdline-tools.zip" -d "$tmpdir"
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  mv "$tmpdir/cmdline-tools/"* "$ANDROID_SDK_ROOT/cmdline-tools/latest/"
fi

# Accept licenses non-interactively
yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" --licenses 2>/dev/null || true

# Install required components (compileSdk 35, build-tools 35, NDK 28)
echo "Installing platform 35, build-tools 35.0.0, NDK 28.0.13004108..."
"$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_SDK_ROOT" \
  "platform-tools" \
  "platforms;android-35" \
  "build-tools;35.0.0" \
  "ndk;28.0.13004108"

# Create local.properties
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCAL_PROPERTIES="$PROJECT_ROOT/local.properties"
echo "sdk.dir=$ANDROID_SDK_ROOT" > "$LOCAL_PROPERTIES"
echo "Created $LOCAL_PROPERTIES"

# Export for current shell
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT="$ANDROID_SDK_ROOT"
export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

echo "Done. ANDROID_HOME=$ANDROID_HOME"
echo "Run: source $PROJECT_ROOT/tools/setup-android-sdk.sh  # or ./gradlew :app:compileDebugKotlin"
