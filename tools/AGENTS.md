# tools

Repository automation scripts for builds, SDK setup, releases, and asset maintenance.

## Direct files
- `test_azure_speech_local.py` - credential status regressions with a fake Keychain; run `python3 -B -m unittest discover -s tools -p 'test_azure_speech_local.py'`.
- `mai-streaming-smoke-test.py` - credentialed MAI Speech SDK smoke test with mono 16 kHz PCM input and EOF draining.
- `azure-speech-local.sh` + `azure-speech-local.py` - macOS Speech SDK environment, Keychain credentials, nonsecret local resource metadata, and credentialed commands.
- `agent-environment-startup.sh` - portable Linux cloud-agent bootstrap: host packages, JDK 21, Android SDK/NDK, sourceable env, optional compile/tests/APK.
- `android-env.sh` - sourceable SDK/JDK discovery for local builds and new worktrees; no downloads.
- `android-dev.sh` - doctor, compile, smoke/full tests, lint, APK, explicit-serial device install.
- `apk-handoff.py` - source/APK freshness metadata and pushed-commit download links.
- `check-agent-infrastructure.py` - discovery links, skill metadata, matching project MCP adapters.
- `build-dist-apk.sh` - canonical signed debug APK plus `dist/apk-metadata.json`.
- `bake_number_row.py` - one-off layout migration helper; not a routine regeneration step.
- `diacritics.py` - offline diacritics-analysis helper for external wordlist data.
- `release.py` - maintainer script for translation import, dictionary index refresh, Khipro mapping refresh, and changelog checks.
- `setup-android-sdk.sh` - Linux/macOS SDK bootstrap, using `ANDROID_SDK_ROOT`, `ANDROID_HOME` or `<repo>/.android-sdk`.

## Subfolders
- `tests/` - stdlib Python regression tests for APK handoff and SDK/JDK discovery.
- `make-emoji-keys/` - standalone tool for regenerating bundled emoji data/resources.

## Non-obvious notes
- `status` reports `key_available: false` for a missing Keychain entry. Commands needing credentials still reject a missing key; backend access errors remain errors instead of being reported as an absent key.
- On macOS, use `azure-speech-local.sh` to keep the key in the native Keychain and inject credentials only into a requested child command. Its Python environment and metadata live in `~/Library/Application Support/HeliBoard/`, outside the checkout. The helper does not provision device preferences or embed credentials in an APK.
- The MAI smoke test requires Python with `azure-cognitiveservices-speech==1.52.0`; use `SPEECH_KEY` and `SPEECH_REGION` from the environment and never commit credentials.
- For a fresh Linux agent environment, run `./tools/agent-environment-startup.sh` once, then `source ./.android-env`. There is no hosted database or dev server to start; clipboard data is on-device SQLite (`heliboard.db`).
- `build-dist-apk.sh` discovers installed tools through `android-env.sh`; install missing SDK components with SDK Manager or the setup/bootstrap scripts. It verifies the signature and writes freshness metadata.
- `python3 -m unittest discover -s tools/tests -v` checks tooling without Android/network; see `docs/agent-development.md` for validation and manual acceptance.
- `release.py` performs network fetches and can overwrite generated data files; treat it as a maintainer workflow, not a harmless local utility.
- `diacritics.py` expects a sibling wordlist setup outside this repo and is not part of the standard build.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
