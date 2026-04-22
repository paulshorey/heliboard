# res/layout

XML layouts for the IME UI, popup keys, emoji, suggestions, and clipboard surfaces.

## Direct files
- `clipboard_entry_key.xml` - one clipboard history entry row/key.
- `clipboard_history_view.xml` - clipboard history container.
- `clipboard_suggestion.xml` - clipboard item shown in a strip/suggestion context.
- `emoji_category_view.xml` - emoji category tab/item layout.
- `emoji_keyboard_page.xml` - one emoji page layout.
- `emoji_palettes_view.xml` - emoji palette host layout.
- `fake_toast.xml` - lightweight in-app toast/overlay layout.
- `input_view.xml` - root IME layout.
- `main_keyboard_frame.xml` - main keyboard frame/layout.
- `more_suggestions.xml` - expanded suggestions layout.
- `popup_keys_keyboard.xml` - popup keys keyboard layout.
- `popup_keys_keyboard_for_action_lxx.xml` - LXX-specific popup keys action layout.
- `strip_container.xml` - suggestion strip container layout.
- `suggestion_divider.xml` - divider between suggestion items.
- `suggestions_strip.xml` - main suggestion strip layout.

## Non-obvious notes
- These layouts are tightly coupled to `keyboard/`, `latin/suggestions/`, and clipboard/emoji UI code.
- `popup_keys_keyboard_for_action_lxx.xml` is a reminder that theme-specific forks exist; check for sibling variants before assuming one layout drives all themes.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
