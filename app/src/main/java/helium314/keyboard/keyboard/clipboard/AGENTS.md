# keyboard/clipboard

Clipboard-history keyboard UI.

## Direct files
- `ClipboardAdapter.kt` - RecyclerView adapter for clipboard entries.
- `ClipboardHistoryRecyclerView.kt` - clipboard history list view.
- `ClipboardHistoryView.kt` - clipboard history container view.
- `ClipboardLayoutParams.kt` - layout parameters for clipboard UI.
- `OnKeyEventListener.kt` - callback contract for clipboard key actions.

## Non-obvious notes
- Data comes from `latin/database/` and `ClipboardHistoryManager.kt`; UI changes often need data-layer awareness too.
- Clipboard UI is part of the keyboard surface, so it must respect the same density/theme/layout constraints as regular keys.
- Clipboard toolbar keys are read from `PREF_CLIPBOARD_TOOLBAR_KEYS` and rendered with the same `ToolbarUtils.createToolbarKey()` path into `clipboard_strip`, which shares `strip_container` with suggestions and emoji tabs.
- Panel height uses `ResourceUtils.getKeyboardLayoutHeightForPanel()` so clipboard rows align with the main keyboard when the pinned Secondary Toolbar is visible.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
