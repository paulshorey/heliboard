# latin/settings

Preference keys, defaults, runtime snapshots, and transcription-specific preference access.

## Direct files
- `DebugSettings.java` - debug-only preference definitions/helpers.
- `Defaults.kt` - default values for settings, including transcription defaults.
- `Settings.java` - canonical preference keys and core read/write helpers.
- `Settings.kt` - Kotlin convenience helpers for settings access.
- `SettingsSubtype.kt` - subtype-related settings helpers.
- `SettingsValues.java` - loaded settings snapshot for runtime logic.
- `SettingsValuesForSuggestion.java` - suggestion-specific settings snapshot.
- `SpacingAndPunctuations.java` - punctuation/spacing rules loaded from settings/resources.
- `TranscriptionPreferences.kt` - typed access to Soniox/voice preferences with legacy provider key cleanup.

## Non-obvious notes
- This is not the settings UI package; Compose screens live in `helium314.keyboard.settings`.
- Soniox prefs: `PREF_SONIOX_API_KEY`, endpoint detection, max endpoint delay, diarization, and custom `context.terms` (`PREF_SONIOX_CUSTOM_TERMS`). `TranscriptionPreferences` clears legacy Speechmatics/Deepgram API-key prefs on first read.
- New user-visible settings usually require work in four places: `Settings.java`, `Defaults.kt`, the UI screen, and any runtime snapshot class that consumes them.
- `Defaults.PREF_KEYBOARD_HEIGHT_SCALE` is indexed by `findIndexOfDefaultSetting(landscape)` (portrait = 0, landscape = 1); the portrait default is intentionally slightly below 1.0 so new installs start a bit shorter until the user changes Appearance sliders.
- Custom keyboards: `PREF_USE_CUSTOM_KEYBOARDS` (opt-in toggle) + `PREF_CUSTOM_KEYBOARDS_JSON` (the user-editable preset document) live here; `Settings.onSharedPreferenceChanged` calls `KeyboardLayoutSet.onKeyboardThemeChanged` when either flips so the layout cache reloads. The default JSON value is seeded inside `Defaults.kt` using a Kotlin raw triple-quoted string (literal `$` is written via the `D` constant or as `$` followed by whitespace, since the string is JSON and must not contain `\$` escape sequences).

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
