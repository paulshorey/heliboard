---
name: android-build-apk
description: Build and publish HeliBoard's canonical APK for every new or updated PR, on desktop or cloud, and provide a verified download link for manual testing. Also use for explicit APK build requests.
---

# APK and PR handoff

Every PR, including infrastructure/docs PRs, needs a freshly built `dist/HeliBoard.apk`
and its download link in the PR description and final response. This applies to
Codex desktop, CLI and cloud. Read-only work needs no build or PR.

1. Finish the task and appropriate checks on its feature branch. Fetch `origin`
   and inspect divergence from `origin/main`; integrate changes needed for the
   task, then resolve and validate conflicts before the final build. Preserve
   unrelated work. Do not merge unrelated feature branches just to get their APK.
2. Run `./tools/build-dist-apk.sh`. It discovers the installed SDK/JDK, builds the
   shared-key signed `debug` variant, verifies its signature, and replaces only
   the canonical APK and `dist/apk-metadata.json`. For missing tools, use the
   [workspace skill](../android-workspace-setup/SKILL.md).
3. Run `python3 tools/apk-handoff.py check`. Metadata fingerprints Git-visible
   source (including new files), executable modes, symlink targets and APK bytes.
   Finish all source/doc edits before building; any later change needs a rebuild.
4. Commit the task, APK and metadata, push the feature branch to `origin`, then
   run `python3 tools/apk-handoff.py link`. It requires a clean tree and a pushed
   HEAD and prints a Markdown URL pinned to that commit. Use its output verbatim
   in the PR body and final response. Confirm the URL downloads the same bytes.
5. Include checks run and specific phone acceptance steps relevant to the change.
   Label device testing as pending unless it was actually performed. Shared debug
   signing supports updating the existing `.debug` app without clearing data.

Build failure is a handoff blocker: report the actual failure and repair it when
possible. If external prerequisites prevent a build, a draft PR may preserve work,
but explicitly mark the APK unavailable; do not present an inherited APK as current.
For a user who asks only for a local build, return the local file without requiring
publication. The metadata is freshness evidence, not a hermetic build attestation.
