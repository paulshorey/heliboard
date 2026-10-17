# Agent development, APK handoff and validation plan

This infrastructure audit moves the repository from Cursor-specific assumptions to
Codex desktop, CLI and portable Linux agents. The goal is a short, repeatable path
from an agent's changes to a phone-installable APK and an honest testing handoff.

## What is installed by this change

| Item | Decision and reason |
| --- | --- |
| `.codex/skills` | Canonical physical skill files, as requested |
| `.agents/skills` | Symlink to the canonical skills; Codex's documented repository discovery path |
| `.cursor` | Compatibility symlink to `.codex`, with no second skill copy |
| `.codex/config.toml` | Codex project MCP configuration, with an empty server map for the MAI integration |
| `.codex/mcp.json` | Small compatibility adapter; checked against TOML for drift |
| Cursor `environment.json` | Deleted: its startup format is not Codex local-environment configuration |
| Old layout plans | Moved to `docs/archive/layout-plans` and labeled historical; useful design evidence, not current instructions |
| `android-build-apk` | Rewritten: every new/updated PR builds and links an APK on desktop and cloud |
| `android-workspace-setup`, `development` | Rewritten around tool discovery, worktrees, targeted checks and explicit device selection |
| `full-app-mode`, `voice-transcription` | Retained with their references: both describe current product architecture |
| `key-hint-sizing` | Retained; stale numeric snapshot replaced with a lookup in authoritative resources |
| APK handoff check | Source and binary fingerprints, pushed-commit URL, PR template and a lightweight CI workflow |

The voice skill and its API/data-flow references describe MAI through the Azure
Speech SDK. Provider-specific configuration and refresh tools from the previous
integration remain removed.

## Daily workflow

```bash
./tools/android-dev.sh doctor
./tools/android-dev.sh smoke
# Choose full/lint or focused Gradle tests as the change requires.
python3 tools/check-agent-infrastructure.py
python3 -m unittest discover -s tools/tests -v
./tools/build-dist-apk.sh
python3 tools/apk-handoff.py check
# Commit intended changes, dist/HeliBoard.apk and dist/apk-metadata.json; push.
python3 tools/apk-handoff.py link
```

Put the printed immutable Markdown download link in the PR body and final reply.
The metadata detects changes to any Git-visible file, including docs/tooling, new
files, executable modes and symlink targets, and detects changed APK bytes. It
excludes `dist` outputs and ignored local SDK/cache/credential files. It checks for
source edits during Gradle and requires a clean tree and pushed HEAD for links.
This is freshness evidence; ignored build inputs and the builder environment are
not a hermetic attestation. The build also verifies the APK signing structure
with the installed `apksigner`.

CI checks the PR head commit, not GitHub's synthetic merge commit, so the artifact
is compared to the source it was built from. The check is visible on PRs; making
it a required branch-protection check is a separate repository settings decision.
A passing check does not cryptographically prove that Gradle produced the binary;
the trusted builder is still responsible for running the canonical build.

If tools or downloads are blocked, preserve unfinished work in a draft PR with the
actual build blocker and clearly unavailable APK. Do not link an inherited binary
as current. The root instructions and the build skill specify the same policy.

## What agents can check locally

| Check | Command | Establishes / limitation |
| --- | --- | --- |
| Tool inventory | `./tools/android-dev.sh doctor` | Installed JDK, platform 35, NDK, ADB devices, AVD names; no package installation |
| Compile | `./tools/android-dev.sh compile` | Kotlin/Java build wiring; no runtime behavior |
| Focused smoke | `./tools/android-dev.sh smoke` | InputLogic and voice JVM/Robolectric logic; no real host app, mic, radio or provider |
| Broader unit suite | `./tools/android-dev.sh full` | CI `runTests` variant; known failing/network checks are skipped by existing project convention |
| Static analysis | `./tools/android-dev.sh lint` | Android lint findings; inspect baseline before claiming regressions |
| Canonical APK | `./tools/android-dev.sh apk` | Minified debug build, native ABIs, signature verification, current artifact |
| Device update | `./tools/android-dev.sh install SERIAL` | Installs the verified APK with `adb -s SERIAL install -r`; preserves app data |

Tests report under `app/build/reports/tests/`; lint reports under
`app/build/reports/`. Keep screenshots and temporary logs under ignored
`artifacts/`, and redact user text, query strings and credentials before sharing.
Do not clear logcat globally just to capture this app.

At audit time on the development Mac, Android Studio, its bundled JDK 21, SDK 35,
NDK 28.0.13004108, ADB, the emulator and an AVD named `Medium_Phone_API_36.1` were
installed. No ADB device was connected. These are observations, not portable
settings; the helpers discover paths at runtime. Inherited Android environment
variables pointed at a stale home directory, so discovery skips unusable env
paths and uses an installed SDK. Existing `local.properties` remains authoritative.

Open the repository in Android Studio, select its bundled JDK for Gradle and let
sync finish. Use `debugNoMinify` for debugger iteration; `debug` is the shared-key
signed PR artifact. SDK/Device Manager can install missing components and create
an emulator. No live Azure Speech key is needed for JVM tests, compilation or settings
smoke checks. Real transcription requires the developer's Speech key and matching region in app settings.

The infrastructure audit's `:app:lintDebug` run reported **820 errors, 125 warnings
and 3 hints**. A subsequent cleanup reproduced that count and reduced it to
**600 errors, 120 warnings and 3 hints**, without disabling checks, changing
severities or adding a baseline:

- Enabled core library desugaring, which already had its dependency declared.
  This backports `ConcurrentHashMap.merge` while preserving atomic email usage
  increments on API 21–23. Java language desugaring alone does not backport
  library APIs; see [Android's desugaring guide](https://developer.android.com/studio/write/java8-support).
- Removed 219 obsolete translations across 48 locale files for seven keys with
  no remaining default string or consumer: `number_row_summary`,
  `number_row_hints`, `number_row_in_symbols`, `layout_number_row`,
  `layout_number_row_basic`, `subtype_generic_sebeolsik_390` and
  `subtype_generic_sebeolsik_final`. Five related unused-resource warnings also
  disappeared.
- Changed the accessibility singleton to retain the application context and its
  services, rather than retaining a `LatinIME` service instance. Lint still
  reports two `StaticFieldLeak` warnings based on the singleton field types;
  those warnings were not suppressed. `RichInputMethodManager` is initialized
  from the application in `App.onCreate`.
- Replaced delete-then-rename email saves with `AtomicFile`, retaining the TSV
  format and serialized disk access. `openRead()` recovers interrupted commits,
  including API 21's backup-only case. Regression coverage runs on API 21 and 35.
- Excluded `AGENTS.md` from assets in every build variant. The inherited APK
  included four such files, and emoji validation tried to parse Markdown as
  emoji data. Variant-specific filters append to the common exclusions.
- Corrected external-link tests to require an HTTP(S) scheme instead of trying
  to open local filenames such as `HeliBoard.apk` as URLs. Connect/read waits
  are bounded to 10 seconds each.

All remaining lint errors are `MissingTranslation` findings. These need actual
translations or an explicit supported-language policy; copying English into each
locale would hide missing work. Other warning categories include unused resources
and attributes, dependency updates, plural candidates and layout conventions.
Lint remains diagnostic and currently fails. Keep its findings visible until the
remaining backlog and any baseline policy are deliberately addressed.

## Codex desktop actions

Codex supports project setup scripts and actions through its Local environments
settings. The public documentation describes the UI and `.codex` ownership but
does not specify the serialized schema. Configure these in the app and review its
generated file before committing it; do not rename Cursor JSON and assume it runs.

| UI entry | Script |
| --- | --- |
| macOS worktree setup | `./tools/android-dev.sh doctor` |
| Linux worktree setup | `./tools/agent-environment-startup.sh --skip-compile` |
| Check tools | `./tools/android-dev.sh doctor` |
| Smoke tests | `./tools/android-dev.sh smoke` |
| Build APK | `./tools/build-dist-apk.sh` |
| Open Android Studio (macOS) | `open -a 'Android Studio' .` |

A setup script is not an environment-export hook for subsequent shells; each
helper discovers/sources its own environment. Do not save an action with a fixed
worktree path or personal device serial. The scripts above are ready to use;
this PR does not edit the desktop application's private state or global settings.
New chats should discover six project skills through `.agents/skills`. Check
the skill selector in a fresh trusted project session after
merging; configuring files does not prove session discovery/connection.

## Human acceptance for each product change

Choose relevant cases and record the phone/app/version and result in the PR:

- Update the existing `.debug` app using the APK link. Confirm settings, vocabulary,
  clipboard and drafts survive. A signature mismatch should be investigated;
  uninstalling would erase this data.
- Type in a native multiline editor and a browser/WebView textarea. Check caret
  moves, selections, replacement, composing words, backspace, autocaps and newlines.
- Check changed keyboard layouts, themes, hints, long press, number/symbol rows,
  portrait/landscape and relevant languages.
- For fullapp work, edit a selected field, background/reopen, return to the original
  app, confirm exactly one replay to the right field and retained draft/history.
- For voice work, test short/long utterances, pause/resume, explicit stop with a
  trailing phrase, silence, network loss, field changes/hiding and host clears.
  Inspect preserved inserted text and interruption behavior; no provisional text
  should become editor content. Use `docs/mai-transcription.md` for its full matrix.

JVM results and emulator screenshots do not replace ergonomic typing, real radio,
real microphone, real provider or host-app acceptance on the developer's phone.

## Follow-up plan, in priority order

These are proposals, not installed tools or verified capabilities.

1. **Dedicated emulator smoke target.** Create a disposable AVD with SDK Manager
   (start with API 35 matching target SDK, plus a current Android image). Add a
   helper that selects an explicit AVD/port, waits for `sys.boot_completed` with a
   deadline, installs the canonical APK, launches settings and captures a screenshot
   and app-scoped crash evidence. Manage only its own emulator process. Acceptance:
   a clean boot/install/settings launch succeeds twice without touching a phone or
   changing an existing AVD. An initial manual command is
   `emulator -avd AVD_NAME -no-snapshot-save -port 5580`; target `emulator-5580` in ADB.
   Existing AVDs are developer-owned; no emulator was started or reset by this PR.
2. **Deterministic IME instrumentation.** Add `app/src/androidTest` with AndroidX
   Test/UI Automator dependencies and a debug-only fixture activity containing a
   native multiline field and a local WebView textarea. On the disposable emulator,
   record/restore the prior IME, enable this debug IME, type/select/replace, and
   exercise fullapp round trips. Use no API key and no external browser dependency.
   Acceptance: assertions verify actual host field contents, selection, no duplicate
   replay and no crashes. Keep voice transport logic in existing local fake-server
   tests; add real mic/provider cases only as explicit opt-in checks.
3. **Android CI beyond handoff checks.** Add a separate JVM/Robolectric job using
   JDK 21 and SDK components, with Gradle caches and uploaded test/lint reports.
   Establish current full-suite/lint baseline first, then choose required checks.
   Add emulator instrumentation after step 2 is reliable. Acceptance: a fresh runner
   runs without developer credentials and reports failures against the correct head.
4. **Artifact distribution outside Git.** If APK history size becomes a problem,
   build once per PR head in CI and publish a commit-associated artifact with
   checksum, signing identity and an accessible phone download link. Preserve the
   current shared debug signature for in-place updates. Decide retention and whether
   private-repo downloads need login before changing today's checked-in APK contract.
5. **Codex UI action export and fresh-session acceptance.** Save the actions above
   through Local environments, commit the generated configuration, then verify a
   new worktree runs setup, lists the six skills and runs the
   Build APK action. Acceptance is observed behavior in that client, not TOML parsing.

## Authoritative references

- [Codex skill discovery and symlinks](https://learn.chatgpt.com/docs/build-skills)
- [Codex project MCP configuration](https://learn.chatgpt.com/docs/extend/mcp)
- [Codex local environments and actions](https://learn.chatgpt.com/docs/environments/local-environment)
- [Android command-line tests](https://developer.android.com/studio/test/command-line)
- [Android emulator command line](https://developer.android.com/studio/run/emulator-commandline)
- [Gradle 8.14 Java compatibility](https://docs.gradle.org/8.14/userguide/compatibility.html)
