# latin/suggestions

Suggestion strip UI and the expanded more-suggestions panel.

## Direct files
- `MoreSuggestions.java` - model/helper for expanded suggestion panels.
- `MoreSuggestionsView.kt` - expanded suggestion panel view.
- `SuggestionStripLayoutHelper.java` - sizing/layout helper for strip content.
- `SuggestionStripViewAccessor.java` - bridge between strip view and IME logic.
- `SuggestionStripView.kt` - main suggestion strip view.

## Non-obvious notes
- UI refresh timing matters here; sluggish updates often come from `LatinIME.UIHandler.postUpdateSuggestionStrip`, async dictionary lookup, or `InputLogic.performUpdateSuggestionStripSync`, not from strip layout alone.
- `Suggest.kt` and `SuggestedWords.java` live in the parent `latin/` package; this folder renders their results and hosts more-suggestions/external suggestion views.
- `strip_container.xml` multiplexes the word suggestion strip, emoji tabs, and clipboard strip. Only one primary child is visible at a time, so do not assume `SuggestionStripView` is always the active strip.
- Toolbar state is split: `ToolbarUtils.kt` defines `ToolbarKey`, defaults, serialized prefs, and key-code mapping; `SuggestionStripView.kt` renders the expandable toolbar and reacts to pref changes.
- `ToolbarMode` changes the meaning of prefs: `EXPANDABLE` shows suggestions plus a toggled toolbar, `SUGGESTION_STRIP` keeps suggestions and pinned keys only, `TOOLBAR_KEYS` hides word suggestions and shows toolbar keys, and `HIDDEN` removes `SuggestionStripView` from `LatinIME`.
- Pinned toolbar keys render in the **Secondary Toolbar** (`R.id.pinned_keys`), a sibling strip below `SuggestionStripView` in `main_keyboard_frame.xml`; `KeyboardSwitcher` calls `populatePinnedKeys()` after full inflation, and `LatinIME` includes that height in insets.
- There are two voice controls: the fixed right-edge `voice_input_key` starts/stops Soniox through `LatinIME.onVoiceInputClicked`, while `ToolbarKey.VOICE` sends `KeyCode.VOICE_INPUT` and switches to the system shortcut/voice IME.
- External/inline suggestion views replace the word row and disable more-suggestions gestures while shown. `ToolbarKey.CLOSE_HISTORY` is an internal key used for those close buttons, not a normal user-facing toolbar item.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
