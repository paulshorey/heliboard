#!/usr/bin/env bash
# Run local Speech tools with credentials in macOS Keychain, outside Git and APKs.
set -eu

if [[ "$(uname -s)" != Darwin ]]; then
    echo "This helper requires macOS. Other platforms can use SPEECH_KEY and SPEECH_REGION." >&2
    exit 1
fi

speech_tools_dir="$(cd "$(dirname "$0")" && pwd)"
speech_venv="${HOME}/Library/Application Support/HeliBoard/speech-venv"
speech_python="${speech_venv}/bin/python"

if [[ "${1:-}" == setup ]]; then
    python3 -m venv "$speech_venv"
    "$speech_python" -m pip install azure-cognitiveservices-speech==1.52.0 keyring==25.7.0
    echo "Local Speech environment ready. Run this helper with configure to store credentials."
    exit 0
fi

if [[ ! -x "$speech_python" ]]; then
    echo "Run ./tools/azure-speech-local.sh setup first." >&2
    exit 1
fi
exec "$speech_python" "$speech_tools_dir/azure-speech-local.py" "$@"
