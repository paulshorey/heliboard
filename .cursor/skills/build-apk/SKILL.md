---
name: build-apk
description: If user asks to build the app, they mean to generate a downloadable .apk file. If in a cloud workspace - build the .apk file after done making changes. If working locally not in the cloud - ignore this.
---

# Build the App APK

## When to run

Only do this if you are in the Cursor **cloud** environment. Skip all this if developing locally in the user's desktop IDE.

## Before building the APK, merge latest changes from main

Commit and push latest changes to the feature branch. Merge main branch into the current feature branch.

## After merging from main

When running in a Cursor cloud agent environment, after finished making changes, generate a new canonical APK file. 

Run:
```bash
./tools/build-dist-apk.sh
```

This helper script:

- sources `./tools/setup-android-sdk.sh` (installs the SDK if needed)
- builds the debug APK with Gradle
- writes `./dist/HeliBoard.apk` (removes any older files in `dist/`)

### Commit, push, and give download URL

After the build succeeds:

```bash
BRANCH="$(git branch --show-current)"
git add dist/HeliBoard.apk
# Stage any additional task files too.
git commit -m "Describe the feature and rebuilt APK"
git push -u origin "$BRANCH"
```

Then derive a direct GitHub download URL:

```bash
BRANCH="$(git branch --show-current)"
ORIGIN_URL="$(git remote get-url origin)"
REPO_PATH="$(printf '%s\n' "$ORIGIN_URL" | sed -E 's#^https://[^/]+/##; s#\.git$##; s#^git@github\.com:##')"
echo "https://github.com/${REPO_PATH}/raw/refs/heads/${BRANCH}/dist/HeliBoard.apk"
```

Include that URL in your final response so the user can download and sideload the APK.