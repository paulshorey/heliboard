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
- `TranscriptionPreferences.kt` - inactive previous-provider preferences, retained for comparison tests.
- `GrokPreferences.kt` - active Grok settings, backup-excluded personal key and nondestructive vocabulary inheritance.

## Non-obvious notes
- This is not the settings UI package; Compose screens live in `helium314.keyboard.settings`.
- `GrokPreferences.kt` owns active provider configuration: smart-turn threshold 0.7, maximum silence 5000 ms, number formatting on, auto-language off, editor vocabulary on, and up to 100 terms of 50 characters. Personal keys use AtomicFile in noBackupFilesDir, never shared preferences or backups.
- Read keys through `AtomicFile.openRead()` before treating them as absent; API 21 can retain only the committed recovery file after an interrupted replacement. Explicit clearing must delete recovery files too.
- Only vocabulary is copied once from prior providers. App upgrade no longer invokes credential-deleting legacy migrations. `TranscriptionPreferences.kt`, its Gemini constants and focused tests remain inactive comparison reference.
- Local voice activity and auto-stop preferences still feed VoiceRecorder. Local silence only advises final waiting; it does not force Grok semantic turns. Explicit Pause finalizes and Stop ends audio on the same connection.
- `PREF_EDIT_HISTORY_ENABLED` gates regular-keyboard edit-history capture in `LatinIME` (default on). Password, no-learning, and incognito fields are always excluded.
- `PREF_EDIT_HISTORY_RETENTION_HOURS` (default 24; `EDIT_HISTORY_RETENTION_HOURS_NO_LIMIT` = 721 for “no limit”) ages out both `EditHistoryStore` entries/pending slots and live `FullappEditorResult` drafts.
- Toolbar prefs are mode-sensitive. `SettingsValues` derives `mSuggestionStripHiddenPerUserSettings`, `mSecondaryStripVisible`, `mAutoShowToolbar`, `mAutoHideToolbar`, and `mQuickPinToolbarKeys` from `PREF_TOOLBAR_MODE` plus `PREF_TOOLBAR_KEYS`, `PREF_PINNED_TOOLBAR_KEYS`, `PREF_CLIPBOARD_TOOLBAR_KEYS`, and related flags.
- `InputAttributes.mShouldShowSuggestions` is true for non-password text even when the host set `TYPE_TEXT_FLAG_NO_SUGGESTIONS`. `mIsNoSuggestionsField` keeps autocorrect (`PREF_MORE_AUTO_CORRECTION` does not override it) and `shouldLearnFromCurrentField()` off for those exact-entry fields. Password and non-text fields still never look up suggestions. The old `PREF_ALWAYS_SHOW_SUGGESTIONS` toggle was removed because it no longer had an effect.
- `PREF_SHOW_NUMBER_ROW`, `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS`, and `PREF_SHOW_NUMBER_ROW_HINTS` have been removed; the number row is always baked into layout files. The `NUMBER_ROW` layout type, its asset files, and the `getNumberRow()` / `addNumberRowOrPopupKeys()` parser methods have also been removed.
- Upgrades through version 3603 migrate the legacy built-in three-key emoji/clipboard bottom-row selections to the `*_with_action` variants; custom layouts are not changed.
- New user-visible settings usually require work in four places: `Settings.java`, `Defaults.kt`, the UI screen, and any runtime snapshot class that consumes them.
- `Defaults.PREF_KEYBOARD_HEIGHT_SCALE` is indexed by `findIndexOfDefaultSetting(landscape)` (portrait = 0, landscape = 1); the portrait default is intentionally slightly below 1.0 so new installs start a bit shorter until the user changes Appearance sliders.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
