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
- Soniox prefs: `PREF_SONIOX_API_KEY`, endpoint detection, max endpoint delay (default 3000 ms; `LEGACY_PREF_SONIOX_MAX_ENDPOINT_DELAY_MS` = 2000 for upgrade migration), diarization, and custom `context.terms` (`PREF_SONIOX_CUSTOM_TERMS`). The realtime model (`stt-rt-v5`), dictation `endpoint_sensitivity`, `language_hints_strict`, keepalive, and `context.general` are hardcoded in `SonioxTranscriptionClient`, not user prefs. `TranscriptionPreferences` clears legacy Speechmatics/Deepgram API-key prefs on first read.
- Local voice prefs (`PREF_VOICE_CHUNK_SILENCE_SECONDS`, `PREF_VOICE_SILENCE_THRESHOLD`, `PREF_VOICE_AUTO_STOP_SILENCE_SECONDS`) feed `VoiceRecorder`: chunk silence triggers manual Soniox finalize in `VoiceInputManager`, while auto-stop silence stops recording after a longer pause.
- `PREF_EDIT_HISTORY_ENABLED` gates regular-keyboard edit-history capture in `LatinIME` (default on). Password, no-learning, and incognito fields are always excluded.
- `PREF_EDIT_HISTORY_RETENTION_HOURS` (default 24; `EDIT_HISTORY_RETENTION_HOURS_NO_LIMIT` = 721 for “no limit”) ages out both `EditHistoryStore` entries/pending slots and live `FullappEditorResult` drafts.
- Toolbar prefs are mode-sensitive. `SettingsValues` derives `mSuggestionStripHiddenPerUserSettings`, `mSecondaryStripVisible`, `mAutoShowToolbar`, `mAutoHideToolbar`, and `mQuickPinToolbarKeys` from `PREF_TOOLBAR_MODE` plus `PREF_TOOLBAR_KEYS`, `PREF_PINNED_TOOLBAR_KEYS`, `PREF_CLIPBOARD_TOOLBAR_KEYS`, and related flags.
- `PREF_SHOW_NUMBER_ROW`, `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS`, and `PREF_SHOW_NUMBER_ROW_HINTS` have been removed; the number row is always baked into layout files. The `NUMBER_ROW` layout type, its asset files, and the `getNumberRow()` / `addNumberRowOrPopupKeys()` parser methods have also been removed.
- New user-visible settings usually require work in four places: `Settings.java`, `Defaults.kt`, the UI screen, and any runtime snapshot class that consumes them.
- `Defaults.PREF_KEYBOARD_HEIGHT_SCALE` is indexed by `findIndexOfDefaultSetting(landscape)` (portrait = 0, landscape = 1); the portrait default is intentionally slightly below 1.0 so new installs start a bit shorter until the user changes Appearance sliders.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
