# keyboard/emoji

Emoji palette pages, categories, and supporting UI widgets.

## Direct files
- `DynamicGridKeyboard.java` - grid keyboard model for an emoji page.
- `EmojiCategory.java` - emoji category model/state.
- `EmojiCategoryPageIndicatorView.java` - page/category indicator view.
- `EmojiLayoutParams.kt` - layout params for emoji surfaces.
- `EmojiPageKeyboardView.java` - keyboard view for one emoji page.
- `EmojiPalettesAdapter.java` - adapter for emoji pages/palettes.
- `EmojiPalettesView.java` - container/pager for emoji palettes.
- `EmojiViewCallback.java` - callback contract from emoji UI to the IME.
- `SupportedEmojis.kt` - supported emoji metadata helpers.

## Non-obvious notes
- The UI here depends on asset data under `app/src/main/assets/emoji/` and generated emoji resources.
- Check API gating when updating emoji data so older Android versions do not show unsupported glyphs.
- `EmojiLayoutParams` and `EmojiPalettesView#onMeasure` use `ResourceUtils.getKeyboardLayoutHeightForPanel` so the emoji grid and bottom functional row stay aligned when a second (pinned) toolbar sits above the typing area.
- Emoji category tabs occupy `emoji_tab_strip` inside the shared `strip_container`; the emoji bottom row is a separate `LayoutType.EMOJI_BOTTOM` keyboard layout under `assets/layouts/emoji_bottom/`.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
