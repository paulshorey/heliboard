# tools

Repository automation scripts for builds, SDK setup, releases, and asset maintenance.

## Direct files
- `agent-environment-startup.sh` - portable Linux cloud-agent bootstrap: host packages, JDK 21, Android SDK/NDK, sourceable env, optional compile/tests/APK.
- `android-env.sh` - sourceable SDK/JDK discovery for local builds and new worktrees; no downloads.
- `android-dev.sh` - doctor, compile, smoke/full tests, lint, APK, explicit-serial device install.
- `apk-handoff.py` - source/APK freshness metadata and pushed-commit download links.
- `check-agent-infrastructure.py` - discovery links, skill metadata, MCP adapters and provider fixups.
- `build-dist-apk.sh` - canonical signed debug APK plus `dist/apk-metadata.json`.
- `bake_number_row.py` - one-off layout migration helper; not a routine regeneration step.
- `gemini-live-smoke-test.py` - opt-in real-provider protocol probes with a user-supplied key; not part of local smoke tests.
- `diacritics.py` - offline diacritics-analysis helper for external wordlist data.
- `gemini-skills-fixups.py` - declarative local corrections for the vendored Gemini skills; `--check` reports drift, `--apply` writes them.
- `release.py` - maintainer script for translation import, dictionary index refresh, Khipro mapping refresh, and changelog checks.
- `setup-android-sdk.sh` - Linux/macOS SDK bootstrap, using `ANDROID_SDK_ROOT`, `ANDROID_HOME` or `<repo>/.android-sdk`.
- `sync-gemini-skills.sh` - refreshes the two retained provider skills from an isolated temporary installer directory and re-applies fixups.

## Subfolders
- `tests/` - stdlib Python regression tests for APK handoff and tooling mechanics.
- `make-emoji-keys/` - standalone tool for regenerating bundled emoji data/resources.

## Non-obvious notes
- For a fresh Linux agent environment, run `./tools/agent-environment-startup.sh` once, then `source ./.android-env`. There is no hosted database or dev server to start; clipboard data is on-device SQLite (`heliboard.db`).
- `build-dist-apk.sh` discovers installed tools through `android-env.sh`; install missing SDK components with SDK Manager or the setup/bootstrap scripts. It verifies the signature and writes freshness metadata.
- Never run `npx skills add/update` directly in this checkout. `.agents/skills` is a tracked discovery symlink; `sync-gemini-skills.sh` downloads in isolation and preserves it.
- Local edits to `.codex/skills/gemini-*` belong in `gemini-skills-fixups.py`, not in the vendored markdown, or the next refresh silently reverts them.
- `python3 -m unittest discover -s tools/tests -v` checks tooling without Android/network; see `docs/agent-development.md` for validation and manual acceptance.
- `release.py` performs network fetches and can overwrite generated data files; treat it as a maintainer workflow, not a harmless local utility.
- `diacritics.py` expects a sibling wordlist setup outside this repo and is not part of the standard build.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
