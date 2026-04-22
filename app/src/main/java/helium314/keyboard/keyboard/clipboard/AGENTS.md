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

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
