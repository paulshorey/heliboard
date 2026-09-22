# tools

Repository automation scripts for builds, SDK setup, releases, and asset maintenance.

## Direct files
- `agent-environment-startup.sh` - portable Linux cloud-agent bootstrap: host packages, JDK 21, Android SDK/NDK, sourceable env, optional compile/tests/APK.
- `build-dist-apk.sh` - canonical APK build script that writes `dist/HeliBoard.apk`.
- `diacritics.py` - offline diacritics-analysis helper for external wordlist data.
- `gemini-skills-fixups.py` - declarative local corrections for the vendored Gemini skills; `--check` reports drift, `--apply` writes them.
- `release.py` - maintainer script for translation import, dictionary index refresh, Khipro mapping refresh, and changelog checks.
- `setup-android-sdk.sh` - cloud/CI Android SDK bootstrap script. Defaults the SDK to `<repo>/.android-sdk` when `ANDROID_SDK_ROOT` is unset.
- `sync-gemini-skills.sh` - refreshes `.cursor/skills/gemini-*` from upstream, relocates the skills CLI output out of `.agents/`, and re-applies the fixups.

## Subfolders
- `make-emoji-keys/` - standalone tool for regenerating bundled emoji data/resources.

## Non-obvious notes
- For a non-Cursor Linux agent environment, run `./tools/agent-environment-startup.sh` once, then `source ./.android-env`. There is no hosted database or dev server to start; clipboard data is on-device SQLite (`heliboard.db`).
- `build-dist-apk.sh` sources `setup-android-sdk.sh` only when needed; prefer it over ad-hoc Gradle commands when you need the canonical installable artifact.
- Never run `npx skills add/update` directly for the Gemini skills. The CLI writes to `.agents/skills/`, which Cursor loads *in addition to* `.cursor/skills/`, so a direct run leaves two copies of every skill that then drift apart. Use `sync-gemini-skills.sh`, which relocates the output and deletes `.agents/`.
- Local edits to `.cursor/skills/gemini-*` belong in `gemini-skills-fixups.py`, not in the vendored markdown, or the next refresh silently reverts them.
- `release.py` performs network fetches and can overwrite generated data files; treat it as a maintainer workflow, not a harmless local utility.
- `diacritics.py` expects a sibling wordlist setup outside this repo and is not part of the standard build.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
