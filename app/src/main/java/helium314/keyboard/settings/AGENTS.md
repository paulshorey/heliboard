# settings package

Standalone settings and fullapp UI built mostly with Compose.

## Direct files
- `FilePicker.kt` - file-picker helpers/screens.
- `FullappEditorActivity.kt` - standalone full-screen editor activity.
- `Icons.kt` - shared Compose icon definitions/helpers.
- `Misc.kt` - small shared UI helpers.
- `Preview.kt` - Compose preview helpers.
- `SearchScreen.kt` - settings search UI.
- `SettingsActivity.kt` - launcher/settings activity; also defines `SettingsActivity2` alias class.
- `SettingsContainer.kt` - top-level settings UI container.
- `SettingsNavHost.kt` - Compose navigation graph for settings.
- `Theme.kt` - settings UI theming.
- `WelcomeWizard.kt` - onboarding/setup wizard.

## Subfolders
- `dialogs/` - reusable settings dialogs and pickers.
- `preferences/` - reusable preference composables.
- `screens/` - feature-specific settings screens.

## Non-obvious notes
- This package edits the same preference keys defined in `latin/settings/`; keep labels/defaults/contracts aligned.
- Fullapp mode is UI here, but text synchronization still depends on the IME pipeline rather than direct widget mutation.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
