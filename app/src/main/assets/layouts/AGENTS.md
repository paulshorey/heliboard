# assets/layouts

Runtime keyboard layouts. Files here define rows, popup sets, width hints, and per-layout variants.

## Subfolders
- `clipboard_bottom/` - clipboard-mode bottom rows.
- `emoji_bottom/` - emoji-mode bottom rows.
- `functional/` - shared functional bottom rows, including tablet and Khipro variants.
- `main/` - primary alphabet/script layouts by locale and physical arrangement.
- `more_symbols/` - shifted extra symbols.
- `number/` - number-mode layout.
- `number_row/` - top-row number layouts.
- `numpad/` - standard numeric keypad.
- `numpad_landscape/` - landscape numeric keypad.
- `phone/` - phone keypad layout.
- `phone_symbols/` - symbols for phone-mode layouts.
- `symbols/` - main symbol layouts.

## Important direct files inside those folders
- `functional/functional_keys.json` - default functional bottom row.
- `functional/functional_keys_khipro.json` - Khipro-specific functional row.
- `functional/functional_keys_tablet.json` - tablet functional row.
- `number/number.json` - number layer.
- `number_row/number_row.json` and `number_row_basic.txt` - two styles of number-row data.
- `numpad/numpad.json` - numeric keypad.
- `numpad_landscape/numpad_landscape.json` - landscape numeric keypad.
- `phone/phone.json` - phone keypad.
- `phone_symbols/phone_symbols.json` - phone symbols.
- `symbols/symbols.txt` and `symbols_arabic.txt` - primary symbol layers.
- `more_symbols/symbols_shifted.txt` - shifted symbols.
- `clipboard_bottom/*.json` and `emoji_bottom/*.json` - bottom rows with and without action-key variants.
- `main/*.json` and `main/*.txt` - per-layout main keyboards; use a nearby layout with the same script or arrangement as the starting point for new work.

## Non-obvious notes
- `res/xml/method.xml` documents and references the layout-set names expected here.
- The mix of `.json` and `.txt` files is intentional; do not convert formats casually unless the parser path is updated too.
- Khipro layouts also depend on `assets/khipro-mappings.json` and event combiner logic.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
