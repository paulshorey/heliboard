#!/usr/bin/env bash
# Bootstrap a Linux cloud-agent / CI machine for HeliBoard.
#
# HeliBoard is an Android IME (no web server, no hosted database). This script
# installs the host toolchain, Android SDK/NDK, and a sourceable env file so an
# agent can compile, unit-test, and produce the debug APK. Clipboard persistence
# is on-device SQLite (heliboard.db), created at runtime by the app.
#
# Usage:
#   ./tools/agent-environment-startup.sh
#   ./tools/agent-environment-startup.sh --verify
#   source ./.android-env
#
# Flags also accept matching HELIBOARD_AGENT_* environment variables.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

SKIP_APT="${HELIBOARD_AGENT_SKIP_APT:-0}"
SKIP_SDK="${HELIBOARD_AGENT_SKIP_SDK:-0}"
SKIP_COMPILE="${HELIBOARD_AGENT_SKIP_COMPILE:-0}"
RUN_TESTS="${HELIBOARD_AGENT_RUN_TESTS:-0}"
FULL_TESTS="${HELIBOARD_AGENT_FULL_TESTS:-0}"
BUILD_APK="${HELIBOARD_AGENT_BUILD_APK:-0}"

usage() {
  cat <<'EOF'
HeliBoard cloud-agent startup

Usage:
  ./tools/agent-environment-startup.sh [options]

Options:
  --help            Show this help
  --skip-apt        Do not install host packages
  --skip-sdk        Do not install or refresh the Android SDK
  --skip-compile    Do not warm Gradle / compile Kotlin
  --quick           Same as --skip-compile
  --verify          Also run a Robolectric smoke suite (InputLogic + voice)
  --tests           Same as --verify
  --full-tests      Run :app:testRunTestsUnitTest (CI variant; some debug-only tests are skipped)
  --apk             Also write dist/HeliBoard.apk

Environment:
  ANDROID_SDK_ROOT / ANDROID_HOME   SDK location (default: <repo>/.android-sdk)
  JAVA_HOME                         JDK 17+ (21 recommended)
  HELIBOARD_AGENT_SKIP_APT=1
  HELIBOARD_AGENT_SKIP_SDK=1
  HELIBOARD_AGENT_SKIP_COMPILE=1
  HELIBOARD_AGENT_RUN_TESTS=1
  HELIBOARD_AGENT_FULL_TESTS=1
  HELIBOARD_AGENT_BUILD_APK=1

After a successful run:
  source ./.android-env
  ./gradlew :app:compileDebugKotlin
  ./gradlew :app:testDebugUnitTest --tests helium314.keyboard.latin.InputLogicTest
  ./tools/build-dist-apk.sh

This project has no server database to start. Clipboard history uses the
on-device SQLite file heliboard.db (schema in
app/src/main/java/helium314/keyboard/latin/database/).
EOF
}

log() { printf '==> %s\n' "$*"; }
warn() { printf 'warning: %s\n' "$*" >&2; }

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

truthy() {
  case "${1:-}" in
    1|true|TRUE|yes|YES|on|ON) return 0 ;;
    *) return 1 ;;
  esac
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --help|-h) usage; exit 0 ;;
      --skip-apt) SKIP_APT=1 ;;
      --skip-sdk) SKIP_SDK=1 ;;
      --skip-compile|--quick) SKIP_COMPILE=1 ;;
      --verify|--tests) RUN_TESTS=1 ;;
      --full-tests) FULL_TESTS=1 ;;
      --apk) BUILD_APK=1 ;;
      *) die "unknown argument: $1 (try --help)" ;;
    esac
    shift
  done
}

have_cmd() { command -v "$1" >/dev/null 2>&1; }

apt_prefix() {
  if [[ "$(id -u)" -eq 0 ]]; then
    return 0
  fi
  if have_cmd sudo && sudo -n true >/dev/null 2>&1; then
    echo sudo -n
    return 0
  fi
  if have_cmd sudo; then
    echo sudo
    return 0
  fi
  return 1
}

java_major_version() {
  local line major
  line="$(java -version 2>&1 | head -n1 || true)"
  major="$(printf '%s\n' "$line" | sed -E 's/.*version "([0-9]+).*/\1/')"
  if [[ "$major" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "$major"
  fi
}

detect_java_home() {
  local candidate
  # Codex universal and other mise-based images install pinned JDKs here even
  # when their inherited JAVA_HOME still points at a newer default.
  for candidate in \
      "${HOME}"/.local/share/mise/installs/java/21* \
      /usr/lib/jvm/java-21-openjdk-amd64 \
      /usr/lib/jvm/java-21-openjdk \
      /usr/lib/jvm/java-17-openjdk-amd64 \
      /usr/lib/jvm/java-17-openjdk \
      /usr/lib/jvm/default-java; do
    if [[ -x "${candidate}/bin/javac" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/javac" ]]; then
    printf '%s\n' "$JAVA_HOME"
    return 0
  fi
  if have_cmd javac; then
    dirname "$(dirname "$(readlink -f "$(command -v javac)")")"
    return 0
  fi
  return 1
}

configure_java() {
  local home
  home="$(detect_java_home || true)"
  if [[ -n "$home" ]]; then
    export JAVA_HOME="$home"
    export PATH="$JAVA_HOME/bin:$PATH"
  fi

  if ! have_cmd java || ! have_cmd javac; then
    die "JDK 17+ is required (21 recommended). Install openjdk-21-jdk or set JAVA_HOME."
  fi

  local major
  major="$(java_major_version || true)"
  if [[ -z "$major" ]]; then
    warn "could not parse 'java -version'; continuing"
    return 0
  fi
  if (( major < 17 )); then
    die "JDK ${major} is too old; HeliBoard needs JDK 17 or newer (21 recommended)"
  fi
  log "Using Java ${major} (JAVA_HOME=${JAVA_HOME:-unset})"
}

debian_like() {
  [[ -r /etc/os-release ]] && grep -Eq '^ID(_LIKE)?=.*(debian|ubuntu)' /etc/os-release
}

package_installed() {
  dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -q 'install ok installed'
}

install_host_packages() {
  if truthy "$SKIP_APT"; then
    log "Skipping host package install"
    return 0
  fi

  local packages=(
    ca-certificates
    curl
    wget
    unzip
    zip
    git
    python3
    sqlite3
    build-essential
    file
  )

  if ! detect_java_home >/dev/null; then
    packages+=(openjdk-21-jdk)
  fi

  if ! debian_like; then
    warn "Host is not Debian/Ubuntu; install JDK 21, curl, unzip, git, python3, and sqlite3 yourself"
    return 0
  fi

  local missing=()
  local pkg
  for pkg in "${packages[@]}"; do
    if ! package_installed "$pkg"; then
      missing+=("$pkg")
    fi
  done

  if [[ ${#missing[@]} -eq 0 ]]; then
    log "Host packages already installed"
    return 0
  fi

  local prefix
  if ! prefix="$(apt_prefix)"; then
    die "Need root or passwordless sudo to install: ${missing[*]}"
  fi

  log "Installing host packages: ${missing[*]}"
  local apt_log
  apt_log="$(mktemp)"
  trap 'rm -f "${apt_log}"' RETURN
  # Avoid dpkg's PTY progress stream overwhelming cloud-agent web terminals.
  # Print the captured output only when an install step fails.
  # shellcheck disable=SC2086
  if ! DEBIAN_FRONTEND=noninteractive $prefix apt-get -qq -o Dpkg::Use-Pty=0 update -y >"${apt_log}" 2>&1; then
    cat "${apt_log}" >&2
    die "apt-get update failed"
  fi
  # shellcheck disable=SC2086
  if ! DEBIAN_FRONTEND=noninteractive $prefix apt-get -qq -o Dpkg::Use-Pty=0 install -y --no-install-recommends "${missing[@]}" >"${apt_log}" 2>&1; then
    cat "${apt_log}" >&2
    die "apt-get install failed"
  fi
  log "Host packages are ready"
}

configure_android_sdk() {
  export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$ROOT_DIR/.android-sdk}}"
  export ANDROID_HOME="$ANDROID_SDK_ROOT"

  if truthy "$SKIP_SDK"; then
    log "Skipping Android SDK setup (ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT)"
  else
    log "Setting up Android SDK at $ANDROID_SDK_ROOT"
    # setup-android-sdk.sh exports ANDROID_* for the current process.
    # shellcheck disable=SC1091
    source "$ROOT_DIR/tools/setup-android-sdk.sh"
  fi

  export PATH="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

  [[ -x "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]] \
    || die "sdkmanager missing at $ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
  [[ -d "$ANDROID_SDK_ROOT/platforms/android-35" ]] \
    || die "Android platform 35 is missing; re-run without --skip-sdk"
  [[ -d "$ANDROID_SDK_ROOT/ndk/28.0.13004108" ]] \
    || die "NDK 28.0.13004108 is missing; re-run without --skip-sdk"
  [[ -f "$ROOT_DIR/local.properties" ]] \
    || die "local.properties was not created"

  if ! grep -q '^sdk\.dir=' "$ROOT_DIR/local.properties"; then
    die "local.properties has no sdk.dir"
  fi
}

write_android_env() {
  cat > "$ROOT_DIR/.android-env" <<'EOF'
# Source this to export HeliBoard build env for the current shell.
# Paths are repo-relative, so this works outside /workspace checkouts.
_HELIO_REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${_HELIO_REPO_ROOT}/.android-sdk}}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /usr/lib/jvm/java-21-openjdk-amd64/bin/javac ]]; then
    export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  elif command -v javac >/dev/null 2>&1; then
    JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
    export JAVA_HOME
  fi
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
unset _HELIO_REPO_ROOT
EOF
  log "Wrote $ROOT_DIR/.android-env"
}

describe_database() {
  log "Database: none to start (on-device SQLite only)"
  cat <<'EOF'
    Clipboard history is stored on the Android device as heliboard.db
    (helium314.keyboard.latin.database.Database, version 1).
    Schema: CLIPBOARD(ID INTEGER PRIMARY KEY, TIMESTAMP INTEGER NOT NULL,
            PINNED TINYINT NOT NULL, TEXT TEXT)
    There is no Postgres/MySQL/Redis process, migration runner, or seed
    step for cloud agents. Inspect a pulled file with:
      sqlite3 heliboard.db '.schema'
EOF
}

warm_or_build() {
  chmod +x "$ROOT_DIR/gradlew"

  if ! truthy "$SKIP_COMPILE"; then
    log "Warming Gradle and compiling :app:compileDebugKotlin"
    ./gradlew --stacktrace :app:compileDebugKotlin
  else
    log "Skipping compile"
  fi

  if truthy "$RUN_TESTS"; then
    log "Running Robolectric smoke tests (InputLogic + voice)"
    ./gradlew --stacktrace :app:testDebugUnitTest \
      --tests helium314.keyboard.latin.InputLogicTest \
      --tests 'helium314.keyboard.latin.voice.*'
  fi

  if truthy "$FULL_TESTS"; then
    # runTests is the CI variant: it skips known-failing / network-spamming checks.
    log "Running JVM/Robolectric unit tests (runTests variant)"
    ./gradlew --stacktrace :app:testRunTestsUnitTest
  fi

  if truthy "$BUILD_APK"; then
    log "Building canonical debug APK"
    "$ROOT_DIR/tools/build-dist-apk.sh"
  fi
}

print_summary() {
  local java_ver sdk_dir
  java_ver="$(java -version 2>&1 | head -n1)"
  sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$ROOT_DIR/local.properties" | tail -n1)"

  cat <<EOF

HeliBoard agent environment is ready.

  Java:        $java_ver
  JAVA_HOME:   ${JAVA_HOME:-unset}
  Android SDK: $sdk_dir
  Env file:    $ROOT_DIR/.android-env

Load env in this shell:
  source $ROOT_DIR/.android-env

Build / run:
  ./gradlew :app:compileDebugKotlin
  ./gradlew :app:testDebugUnitTest --tests helium314.keyboard.latin.InputLogicTest
  ./tools/build-dist-apk.sh
  ./gradlew installDebug          # only when an adb device/emulator is attached

Notes:
  - This is an Android keyboard. There is no dev server to keep running.
  - Persistence is on-device SQLite (heliboard.db), not a hosted database.
  - Gemini Live voice needs a user-supplied API key in the app settings;
    do not put secrets in this script.
EOF
}

main() {
  parse_args "$@"
  log "Repository root: $ROOT_DIR"
  install_host_packages
  configure_java
  configure_android_sdk
  write_android_env
  describe_database
  warm_or_build
  print_summary
}

main "$@"
