# tools

Repository automation scripts for builds, SDK setup, releases, and asset maintenance.

## Direct files
- `build-dist-apk.sh` - canonical APK build script that writes `dist/HeliBoard.apk`.
- `diacritics.py` - offline diacritics-analysis helper for external wordlist data.
- `release.py` - maintainer script for translation import, dictionary index refresh, Khipro mapping refresh, and changelog checks.
- `setup-android-sdk.sh` - cloud/CI Android SDK bootstrap script.

## Subfolders
- `make-emoji-keys/` - standalone tool for regenerating bundled emoji data/resources.

## Non-obvious notes
- `build-dist-apk.sh` sources `setup-android-sdk.sh` only when needed; prefer it over ad-hoc Gradle commands when you need the canonical installable artifact.
- `release.py` performs network fetches and can overwrite generated data files; treat it as a maintainer workflow, not a harmless local utility.
- `diacritics.py` expects a sibling wordlist setup outside this repo and is not part of the standard build.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
