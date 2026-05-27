## Number Row Changes — Context for Troubleshooting

### What the feature set did (custom keyboard layouts)

Three sequential changes were made to allow users to edit/fork keyboard layouts per language:

1.  Part 1 — Bake Number Row into Layout Files: A Python script (`tools/bake_number_row.py`) prepended the number row (digits 1-0 with their popup keys) directly into 76 built-in layout asset files under `assets/layouts/main/`, `symbols/`, and `more_symbols/`. For example, `qwerty.txt` went from 3 rows to 4 rows, with the top row being `1 ! ¹ ½ ⅓ ¼ ⅛` through `0 ) ⁰ ⁿ ∅`. Three files were skipped: `pcqwerty.json` (already had numbers), `lao.json`, and `thai.json` (have locale digits in popups).
2.  Part 2 — Parser Cleanup: `KeyboardParser.kt` was changed to stop dynamically prepending `number_row.json`at runtime. The layout file became the sole source of truth for row count. A localized-digits pass (`convertToLocalizedNumbers`) was added to swap Western digits in the baked top row with locale-specific digits (e.g. Persian ۱۲۳) for 4+ row layouts. The `+` layout extra-keys offset was adjusted so extras append to alphabet rows, not the number row. Dead settings (`mShowsNumberRow`, `mShowsNumberRowInSymbols`, `mNumberRowEnabled`) were removed.
3.  Part 3 — UI Parity: `LayoutSlotEditor` was extracted in `SubtypeScreen.kt` so all layout slots (MAIN, SYMBOLS, FUNCTIONAL, etc.) have identical add/edit/delete/fork controls.

### The most recent change (NUMBER_ROW removal)

On branch `cursor/ui-icon-fixes-2be4` (now merged to `main`), the `NUMBER_ROW` layout type was fully removed:

Deleted code:

- `LayoutType.NUMBER_ROW` enum entry from `LayoutType.kt`
- `KeyboardParser.getNumberRow()` — loaded `LayoutParser.parseLayout(LayoutType.NUMBER_ROW, ...)` to get the number row template
- `KeyboardParser.addNumberRowOrPopupKeys()` — for 3-row alphabet layouts, this injected `numberLabel` (digit hints) onto top-row keys from the number row template. Conditions: `isAlphabetKeyboard && baseKeys.size == 3 && !hasBuiltInNumbers()`
- `KeyboardParser.hasBuiltInNumbers()` — returned true for `pcqwerty`, `lao`, `thai`
- The call chain in `createRows()`: `val numberRow = getNumberRow()` then `addNumberRowOrPopupKeys(baseKeys, numberRow)` — both removed, only `convertToLocalizedNumbers(baseKeys)` remains
- `assets/layouts/number_row/number_row.json` and `number_row_basic.txt` — deleted
- `Settings.PREF_SHOW_NUMBER_ROW_HINTS` and `SettingsValues.mShowNumberRowHints` — removed (was a dead pref, loaded but never read)
- `Defaults.PREF_SHOW_NUMBER_ROW_HINTS` — removed
- `AppUpgrade.kt` migration for `custom.number_row.` files — removed

Kept intact:

- `convertToLocalizedNumbers()` in `KeyboardParser.kt` — for 4+ row alphabet layouts, swaps digit labels in `baseKeys.first()` with locale-specific digits when `mLocalizedNumberRow` is enabled. Uses `params.mLocaleKeyboardInfos.localizedNumberKeys` (from `[number_row]` in `locale_key_texts/*.txt`)
- `Settings.PREF_LOCALIZED_NUMBER_ROW` and `Defaults.PREF_LOCALIZED_NUMBER_ROW`
- `ExtraValue.LOCALIZED_NUMBER_ROW` and its toggle in `SubtypeScreen.kt`
- `hasLocalizedNumberRow()` function in `LocaleKeyboardInfos.kt` and its use to conditionally show the localized toggle
- `[number_row]` sections in `locale_key_texts/*.txt` files (ar, bn-BD, bn-IN, ckb, fa, gu, hi, kn, mr, ne, th, ur)

### Key files to investigate

- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt` — the `createRows()` method and `convertToLocalizedNumbers()`
- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/LayoutParser.kt` — parses layout files, handles `+` layout offset
- `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardBuilder.kt` — line 117 adds `config_number_row_top_extra_gap` padding (still references number row concept)
- `app/src/main/assets/layouts/main/*.txt` and `*.json` — the baked layout files with number rows in row 1
- `app/src/main/assets/layouts/symbols/symbols.txt` — also has a baked number row
- `app/src/main/assets/locale_key_texts/*.txt` — `[number_row]` sections providing localized digit data

### Potential risk areas

1.  `convertToLocalizedNumbers` guard: It checks `baseKeys.size < 4` and returns early. If a layout somehow parses as 3 rows, localization won't run — but since `addNumberRowOrPopupKeys` (the old 3-row hint path) was also removed, 3-row layouts now get neither baked digits nor digit hints.
2.  `KeyboardBuilder.kt` top gap: Line 117 still adds `config_number_row_top_extra_gap` (3dp) when `true` (was formerly `Settings.getValues().mShowsNumberRow`, pinned to `true` in Part 2). This always adds the gap now, which is correct for baked 4-row layouts but might be wrong if something else changed.
3.  Shift-state behavior on number row keys: The baked `.txt` format doesn't support `shift_state_selector`. The old runtime-prepended `number_row.json` used `shift_state_selector` to show `!` when shifted and `1`normally. Baked `.txt` files use `1 ! ¹ ½ ...` (plain text format), where `!` is just a popup, not a shift-state variant. The `.json` layouts (like `azerty.json`) do have proper `shift_state_selector` entries baked in.
