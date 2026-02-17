#!/usr/bin/env bash
# Setup Android SDK for headless/CI/cloud environments (e.g. Cursor Cloud Agent)
# Run this script before ./gradlew assembleDebug when ANDROID_HOME is not set.
#
# Usage: ./scripts/setup-android-sdk.sh
# Or:    bash scripts/setup-android-sdk.sh

set -e

# SDK location: prefer workspace-local for cloud persistence, fallback to HOME
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-$PROJECT_ROOT/android-sdk}"
CMDLINE_TOOLS_DIR="$SDK_DIR/cmdline-tools/latest"

echo "==> Android SDK setup for Cursor Cloud / CI"
echo "    SDK will be installed to: $SDK_DIR"

# Check if already configured
if [ -f "$PROJECT_ROOT/local.properties" ]; then
  SDK_DIR_PROP=$(grep "sdk.dir" "$PROJECT_ROOT/local.properties" | cut -d= -f2)
  if [ -n "$SDK_DIR_PROP" ] && [ -d "$SDK_DIR_PROP" ]; then
    echo "==> local.properties already has valid sdk.dir. Skipping."
    exit 0
  fi
fi

# Create SDK directory
mkdir -p "$SDK_DIR"
mkdir -p "$CMDLINE_TOOLS_DIR"

# Download command-line tools if not present
if [ ! -f "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]; then
  echo "==> Downloading Android command-line tools..."
  CMDLINE_ZIP="/tmp/cmdline-tools.zip"
  # Use a known working version (11.0 - Dec 2024)
  CMDLINE_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
  if ! curl -fsSL -o "$CMDLINE_ZIP" "$CMDLINE_URL"; then
    echo "ERROR: Failed to download Android command-line tools from $CMDLINE_URL"
    echo "       Check your network connection and try again."
    exit 1
  fi
  echo "==> Extracting..."
  unzip -q -o "$CMDLINE_ZIP" -d "$SDK_DIR"
  rm -f "$CMDLINE_ZIP"
  # Zip extracts to cmdline-tools/ with bin, lib inside. We need cmdline-tools/latest/
  if [ -d "$SDK_DIR/cmdline-tools" ] && [ ! -f "$CMDLINE_TOOLS_DIR/bin/sdkmanager" ]; then
    mv "$SDK_DIR/cmdline-tools" "$SDK_DIR/cmdline-tools-tmp"
    mkdir -p "$SDK_DIR/cmdline-tools"
    mv "$SDK_DIR/cmdline-tools-tmp" "$CMDLINE_TOOLS_DIR"
  fi
fi

export ANDROID_HOME="$SDK_DIR"
export PATH="$CMDLINE_TOOLS_DIR/bin:$PATH"

# Accept licenses
echo "==> Accepting SDK licenses..."
yes | sdkmanager --sdk_root="$SDK_DIR" --licenses 2>/dev/null || true

# Install required components
echo "==> Installing SDK components (platform, build-tools, ndk)..."
sdkmanager --sdk_root="$SDK_DIR" "platforms;android-35"
sdkmanager --sdk_root="$SDK_DIR" "build-tools;35.0.0"
sdkmanager --sdk_root="$SDK_DIR" "ndk;28.0.13004108"

# Create local.properties
LOCAL_PROPERTIES="$PROJECT_ROOT/local.properties"
echo "sdk.dir=$SDK_DIR" > "$LOCAL_PROPERTIES"
echo "==> Created $LOCAL_PROPERTIES"

echo "==> Android SDK setup complete. You can now run: ./gradlew assembleDebug"
