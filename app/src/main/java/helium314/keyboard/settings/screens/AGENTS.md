# settings/screens

Feature-specific Compose screens for the settings app.

## Direct files
- `AboutScreen.kt` - app/about information screen.
- `AdvancedScreen.kt` - advanced settings screen.
- `AppearanceScreen.kt` - appearance/theme settings screen (keyboard height appears first under a **Size and layout** heading).
- `ColorsScreen.kt` - color customization screen.
- `DebugScreen.kt` - debug/developer settings screen.
- `DictionaryScreen.kt` - dictionary management screen.
- `FullappDraftsScreen.kt` - saved fullapp drafts screen.
- `GestureTypingScreen.kt` - gesture typing settings screen.
- `LanguageScreen.kt` - language/subtype entry screen.
- `MainSettingsScreen.kt` - top-level settings home screen.
- `PersonalDictionariesScreen.kt` - list of personal dictionaries.
- `PersonalDictionaryScreen.kt` - one personal dictionary editor/view.
- `PreferencesScreen.kt` - general preference settings screen.
- `SecondaryLayoutScreen.kt` - secondary layout settings screen.
- `SetupAppScreen.kt` - app setup/onboarding utility screen.
- `SonioxContextTermsScreen.kt` - voice transcription custom vocabulary screen (built-in `context.terms` view + user-editable list).
- `SubtypeScreen.kt` - subtype detail/configuration screen. Contains `LayoutSlotEditor`, a reusable composable that provides add/edit/delete/fork controls for any `LayoutType` slot. All layout types (MAIN, SYMBOLS, FUNCTIONAL, etc.) use this unified component with identical affordances.
- `TextCorrectionScreen.kt` - text correction/autocorrect settings screen.
- `ToolbarScreen.kt` - toolbar customization screen.
- `TranscriptionScreen.kt` - voice transcription settings screen (links into `SonioxContextTermsScreen` and `VoiceDiagnosticsScreen`).
- `VoiceDiagnosticsScreen.kt` - on-device viewer for recent voice/transcription diagnostic log lines.

## Non-obvious notes
- These screens are UI only; the actual preference keys/defaults live in `latin/settings/`.
- When adding a screen, also update navigation wiring in `SettingsNavHost.kt` and search/discoverability if appropriate.
- `LayoutSlotEditor` in `SubtypeScreen.kt` is the single component for all layout slot editing. Every `LayoutType` (MAIN through CLIPBOARD_BOTTOM) gets identical add/edit/delete/fork affordances. The fork icon on built-in layouts creates a pre-filled copy via `LayoutEditDialog`. For MAIN slots, locale-aware `getContentWithPlus` is used; for other slots, plain `getContent` applies.
- When forking a `+` layout (like `qwerty+`), `LayoutEditDialog` shows a caption warning that locale extras are baked into the copy.
- `ToolbarScreen.kt` is mode-conditional: settings for pinned keys, auto show/hide, and quick pin only matter in the `ToolbarMode`s where `SettingsValues` enables those behaviors.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
