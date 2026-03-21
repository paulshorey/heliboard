---
name: build-apk
description: when finished a feature and bugfix and confident that the requirements and edge cases are satisfied, build the app APK
---

# Build the app

When a task requires a phone-installable artifact, generate exactly one canonical APK copy at:

- `/workspace/dist/HeliBoard.apk`

## Build command

Run:

```bash
./tools/build-dist-apk.sh
```

This helper script:

- sources `./tools/setup-android-sdk.sh`
- builds the debug APK with Gradle
- removes older files in `/workspace/dist`
- overwrites `./dist/HeliBoard.apk` with the latest build

## Commit and push the APK

After the build succeeds:

1. Stage the rebuilt APK together with the task changes.
2. Commit everything to the current feature branch with a descriptive message.
3. Push the current branch:

```bash
BRANCH="$(git branch --show-current)"
git add dist/HeliBoard.apk
# Stage any additional task files if they changed too.
git commit -m "Add message describing the feature and rebuilt APK"
git push -u origin "$BRANCH"
```

## Give the user a GitHub download URL

After pushing, give the user a direct GitHub download URL for the APK committed on the current feature branch. Derive the repository path from `origin` so the link stays correct even if the repo name changes:

```bash
BRANCH="$(git branch --show-current)"
ORIGIN_URL="$(git remote get-url origin)"
REPO_PATH="$(printf '%s\n' "$ORIGIN_URL" | sed -E 's#^https://[^/]+/##; s#\.git$##; s#^git@github\.com:##')"
echo "https://github.com/${REPO_PATH}/raw/refs/heads/${BRANCH}/dist/HeliBoard.apk"
```

Include that URL in your final response so the user can download the APK from GitHub for the pushed branch.

## Keep this skill up to date

If the build flow or output path changes, update this skill file immediately.
