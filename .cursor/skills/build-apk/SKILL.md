---
name: build-apk
description: If the user asks to build the app, they mean generate a downloadable .apk file. In Cursor cloud, always produce dist/HeliBoard.apk after substantive app changes, commit and push it, and give the user the raw GitHub download URL.
---

# Build the App APK

## Mandatory for cloud agents (read this first)

If you are running in the **Cursor cloud** environment (this workspace), you **must** do all of the following after you finish building or changing the Android app:

1. **Build the canonical APK** — run `./tools/build-dist-apk.sh` so `dist/HeliBoard.apk` exists and matches the current code.
2. **Commit `dist/HeliBoard.apk`** together with your other task commits (or as a dedicated commit right after them).
3. **Push** to the feature branch: `git push -u origin "$(git branch --show-current)"`.
4. **Tell the user the raw download link** — after push, print the URL below so they can download the APK directly from GitHub (no UI navigation required).

Skipping the APK build/commit/push in cloud means the user cannot install what you just built. **Do not assume Gradle alone is enough** — the deliverable for sideloading is `dist/HeliBoard.apk` in the repo.

If you are **not** in cloud (local desktop IDE only), skip committing the APK unless the user explicitly wants it; building locally is optional per their request.

## When to run

Only do the full **commit + push + URL** flow in the Cursor **cloud** environment.

## Before building the APK (optional but recommended)

Commit and push your code changes to the feature branch. Merge latest `main` into the branch if the project expects it:

```bash
git fetch origin main && git merge origin/main
```

## Build command

Run from the repository root:

```bash
./tools/build-dist-apk.sh
```

This script:

- sources `./tools/setup-android-sdk.sh` (installs the SDK if needed)
- runs `./gradlew :app:assembleDebug`
- copies the single debug APK to **`./dist/HeliBoard.apk`** (replaces prior contents of `dist/`)

## Commit, push, and give the user the raw GitHub URL

After the build succeeds:

```bash
BRANCH="$(git branch --show-current)"
git add dist/HeliBoard.apk
git commit -m "Build: refresh dist/HeliBoard.apk"
git push -u origin "$BRANCH"
```

**Raw download URL** (works after push; user can paste into a browser or `curl -O`):

```bash
BRANCH="$(git branch --show-current)"
ORIGIN_URL="$(git remote get-url origin)"
REPO_PATH="$(printf '%s\n' "$ORIGIN_URL" | sed -E 's#^https://[^/]+/##; s#\.git$##; s#^git@github\.com:##')"
echo "https://github.com/${REPO_PATH}/raw/refs/heads/${BRANCH}/dist/HeliBoard.apk"
```

**Always include that full `https://github.com/.../raw/refs/heads/.../dist/HeliBoard.apk` URL in your final message to the user** so they can download and sideload the APK immediately.

Notes:

- Branch names with slashes (e.g. `cursor/feature-name`) are valid in this URL path.
- If the repo is private, the user needs GitHub access; the link format is still the standard raw file URL.
