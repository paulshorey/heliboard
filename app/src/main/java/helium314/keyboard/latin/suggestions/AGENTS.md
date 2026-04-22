# latin/suggestions

Suggestion strip UI and the expanded more-suggestions panel.

## Direct files
- `MoreSuggestions.java` - model/helper for expanded suggestion panels.
- `MoreSuggestionsView.kt` - expanded suggestion panel view.
- `SuggestionStripLayoutHelper.java` - sizing/layout helper for strip content.
- `SuggestionStripViewAccessor.java` - bridge between strip view and IME logic.
- `SuggestionStripView.kt` - main suggestion strip view.

## Non-obvious notes
- UI refresh timing matters here; sluggish updates often come from upstream suggestion scheduling rather than this package alone.
- Keep this folder aligned with `SuggestedWords.java` and `InputLogic.java` contracts.
- Pinned toolbar keys render in the **Secondary Toolbar** (`R.id.pinned_keys`), a sibling strip below `SuggestionStripView` in `main_keyboard_frame.xml`; `SuggestionStripView` resolves that container from the root view for pin/unpin and visibility.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
