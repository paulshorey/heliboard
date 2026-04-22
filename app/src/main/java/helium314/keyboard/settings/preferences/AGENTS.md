# settings/preferences

Reusable Compose preference components used across many settings screens.

## Direct files
- `BackgroundImagePreference.kt` - preference row/editor for background images.
- `BackupRestorePreference.kt` - backup/restore action preference.
- `CustomFontPreference.kt` - custom font preference component.
- `ListPreference.kt` - single-choice list preference component.
- `LoadGestureLibPreference.kt` - gesture library loading preference.
- `MultiSliderPreference.kt` - multi-value slider preference component.
- `Preference.kt` - base/shared preference composable building blocks.
- `ReorderSwitchPreference.kt` - reorderable switch-list preference.
- `SliderPreference.kt` - slider preference component.
- `SwitchPreference.kt` - toggle preference component.
- `TextInputPreference.kt` - text-entry preference component.

## Non-obvious notes
- Keep component behavior and summaries aligned with the underlying preference semantics in `latin/settings/`.
- These are shared primitives; changes here can affect many screens at once.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
