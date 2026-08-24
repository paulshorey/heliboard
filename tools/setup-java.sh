#!/usr/bin/env bash
# Configure a Gradle-compatible JDK for cloud agents / CI.
set -euo pipefail

_is_sourced() {
  [[ "${BASH_SOURCE[0]}" != "${0}" ]]
}

_finish_setup_java() {
  local code="$1"
  if _is_sourced; then
    return "$code"
  fi
  exit "$code"
}

if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "${JAVA_HOME}/bin/java" ]]; then
  current_major="$(${JAVA_HOME}/bin/java -version 2>&1 | sed -nE 's/.* version "([0-9]+).*/\1/p' | head -1)"
  if [[ "${current_major:-0}" -ge 17 && "${current_major:-0}" -le 21 ]]; then
    echo "Using existing JAVA_HOME=$JAVA_HOME"
    if _is_sourced; then return 0; else exit 0; fi
  fi
fi

for candidate in \
  /root/.local/share/mise/installs/java/17.0.2 \
  /root/.local/share/mise/installs/java/21.0.2 \
  /root/.local/share/mise/installs/java/17 \
  /root/.local/share/mise/installs/java/21
do
  if [[ -x "$candidate/bin/java" ]]; then
    export JAVA_HOME="$candidate"
    export PATH="$JAVA_HOME/bin:$PATH"
    echo "Configured JAVA_HOME=$JAVA_HOME"
    java -version
    if _is_sourced; then return 0; else exit 0; fi
  fi
done

echo "No compatible JDK (17-21) found. Install JDK 17 and set JAVA_HOME before running Gradle." >&2
if _is_sourced; then return 1; else exit 1; fi
