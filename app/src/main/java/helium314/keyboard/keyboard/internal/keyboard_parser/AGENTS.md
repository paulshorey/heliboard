# keyboard/internal/keyboard_parser

Converts layout assets and XML metadata into typed keyboard models.

## Direct files
- `EmojiParser.kt` - parser for emoji keyboard data.
- `KeyboardParser.kt` - main keyboard parser entry point.
- `LayoutParser.kt` - parser for layout asset structure/content.
- `LocaleKeyboardInfos.kt` - mapping of locales/layout names to parser metadata.

## Floris model files
- `floris/KeyCode.kt` - structured key code definitions.
- `floris/KeyData.kt` - core parsed key data model.
- `floris/KeyLabel.kt` - label model for parsed keys.
- `floris/KeyType.kt` - parsed key type classification.
- `floris/PopupSet.kt` - popup-key set model.
- `floris/TextKeyData.kt` - parsed text key data model.
- `floris/Unicode.kt` - Unicode/parser helper data.

## Non-obvious notes
- `res/xml/method.xml` and `assets/layouts/` are the external inputs most likely to break parser assumptions.
- Android is pointed at `method_dummy.xml`, but runtime subtype/layout data is read from `R.xml.method` by app code and from user subtype prefs; do not rely on system IME subtype APIs to list the real catalog.
- Layout resolution is not one file per language. MAIN comes from the subtype's `KeyboardLayoutSet` extra value (implicit `qwerty` if absent); other `LayoutType` slots resolve subtype override -> global default-layout pref -> `Defaults.LayoutType.default`.
- Treat parser model changes as schema changes for the layout assets, not as isolated refactors.
- `KeyboardParser` no longer prepends a number row at runtime. Layout files are the sole source of truth for row count: 4-row files render 4 rows (with baked-in number row); 3-row files render 3 rows. The `NUMBER_ROW` layout type has been fully removed.
- When a layout has more or fewer than 4 rows, `KeyboardParser` rescales row heights; it also rescales `mVerticalGap` so the visible gap between rows matches the four-row baseline.
- For 4+ row alphabet layouts, `KeyboardParser.convertToLocalizedNumbers` swaps Western digits in the baked top row with locale-specific digits (e.g. Persian/Bengali) when `mLocalizedNumberRow` is enabled.
- `locale_key_texts` files are behavior overlays: popup sets, `+` layout extra keys, localized labels, localized number rows, and TLD popups. Popup ordering is also affected by the active MorePopups tier and hint/popup-order prefs.
- For `+` layouts, `LayoutParser` adjusts extra-key row indices so keys append to alphabet rows, not the baked number row. Fork/edit preview uses `LayoutUtils.getContentWithPlus()` and only works for simple text layouts, not JSON `+` layouts.
- Custom symbol layouts (`isCustomLayout`) clear `LABEL_FLAGS_DISABLE_HINT_LABEL` so user-defined popup hints are visible.
- Emoji palette pages come from `assets/emoji/*.txt`; the `EMOJI_BOTTOM` and `CLIPBOARD_BOTTOM` layout slots are separate bottom-row layouts under `assets/layouts/`.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
