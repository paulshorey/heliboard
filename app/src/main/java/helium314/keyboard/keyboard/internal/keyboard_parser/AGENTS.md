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

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
