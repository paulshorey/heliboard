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
- `TranscriptionPreferences.kt` - typed access to Speechmatics/voice preferences.

## Non-obvious notes
- This is not the settings UI package; Compose screens live in `helium314.keyboard.settings`.
- New user-visible settings usually require work in four places: `Settings.java`, `Defaults.kt`, the UI screen, and any runtime snapshot class that consumes them.
- `Defaults.PREF_KEYBOARD_HEIGHT_SCALE` is indexed by `findIndexOfDefaultSetting(landscape)` (portrait = 0, landscape = 1); the portrait default is intentionally slightly below 1.0 so new installs start a bit shorter until the user changes Appearance sliders.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
