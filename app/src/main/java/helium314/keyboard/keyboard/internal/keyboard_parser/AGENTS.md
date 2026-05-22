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
- Treat parser model changes as schema changes for the layout assets, not as isolated refactors.
- When `KeyboardParser` inserts an extra row (for example the number row) it rescales row heights; it also rescales `mVerticalGap` so the visible gap between rows matches the four-row baseline instead of leaving a tall “dead” band under the new row.
- When the active **Custom keyboards** preset takes over (`LayoutParser` short-circuits to a preset-derived layout for `MAIN`/`SYMBOLS`/`MORE_SYMBOLS`), `KeyboardParser` does **not** prepend the built-in number row on top of the preset alphabet. The preset is the single source of truth: 4 rows = top row is the number row, 3 rows = no number row. Skipping that injection is what keeps a 4-row preset from getting an extra squished number row and what lets a 3-row preset render as a true no-number-row keyboard.
- The Custom keyboards override is **per-locale**, not global. `LayoutParser` reads `params.mId.mSubtype.locale` and calls `CustomKeyboards.presetForLocale(prefs, locale)`; when no preset matches the active subtype the parser falls through to the normal stock-layout path, so non-English languages keep working when the feature is enabled. The synthetic cache key includes the locale tag so a French preset never leaks into the English keyboard.
- When the **Custom Keyboards** override is on (see `latin/utils/CustomKeyboards.kt` and `PREF_USE_CUSTOM_KEYBOARDS`) **and** a preset targets the active subtype's locale, `LayoutParser.parseLayout` synthesizes MAIN, SYMBOLS, and MORE_SYMBOLS rows from that preset instead of reading the asset, using a synthetic cache key keyed on the preset hash AND the locale tag (so French and English presets don't share cache entries). `KeyboardParser` then drops `LABEL_FLAGS_DISABLE_HINT_LABEL` on symbol layouts (so the user-defined hint is shown above each primary) and skips the automatic number-row popup injection so the preset's hints win. Subtypes whose locale matches no preset run through the normal stock-layout path so language switching keeps working.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
