#!/usr/bin/env bash
# Configure a Gradle-compatible JDK for cloud agents / CI.
set -euo pipefail

if [[ -n "${JAVA_HOME:-}" ]] && [[ -x "${JAVA_HOME}/bin/java" ]]; then
  current_major="$(${JAVA_HOME}/bin/java -XshowSettings:properties -version 2>&1 | awk -F'[ =.]' '/java.version =/{print $4; exit}')"
  if [[ "${current_major:-0}" -ge 17 && "${current_major:-0}" -le 21 ]]; then
    echo "Using existing JAVA_HOME=$JAVA_HOME"
    exit 0
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
    exit 0
  fi
 done

echo "No compatible JDK (17-21) found. Install JDK 17 and set JAVA_HOME before running Gradle." >&2
exit 1
