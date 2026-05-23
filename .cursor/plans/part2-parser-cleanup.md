# HeliBoard User-Editable Keyboard Layouts — Part 2: Parser Cleanup

This is the second of three plans to implement user-editable keyboard layouts. The goal of this phase is to make the keyboard parser stop prepending the number row dynamically and instead rely solely on the layout file's row count. We will clean up dead configuration fields, support localized digits on baked top rows, fix off-by-one bugs in the extra-keys mechanism, and add JUnit tests.

---

## 1. Goal & Branching

- **Branch name:** `cursor/custom-layouts-pr2-parser-cleanup`
- **Objective:** Modify keyboard parser, layout parser, and configuration code. Ensure that layout file row count is the *only* source of truth for layout structure.

---

## 2. Technical Instructions

Execution order is critical to avoid massive compiler errors. Each phase described below should be executed in order, ensuring that the app builds after each phase.

### 2.1 Phase A: Pin Reads to True
Before deleting any configuration fields, first replace their read-sites with `true` constants so we don't break downstream logic prematurely.

| File & Line | Old Code (field read) | New Code |
| --- | --- | --- |
| `KeyboardParser.kt` | `Settings.getValues().mShowsNumberRow` | `true` |
| `KeyboardParser.kt` | `params.mId.mNumberRowEnabled` | `true` |
| `KeyboardBuilder.kt` | `Settings.getValues().mShowsNumberRow` | `true` |
| `EmojiLayoutParams.kt` | `Settings.getValues().mShowsNumberRow` | `true` |
| `ClipboardLayoutParams.kt` | `Settings.getValues().mShowsNumberRow` | `true` |
| `ResourceUtils.java` | `Settings.getValues().mShowsNumberRow` | `true` |

Ensure the project compiles successfully.

### 2.2 Phase B: Remove Number-Row Prepend & Update Fallbacks
Modify `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt`:

1. **Remove Prepend Injection:**
   Delete lines ~109–112 that perform the runtime prepend:
   ```kotlin
   // DELETE THIS:
   // baseKeys.add(0, numberRow.mapTo(mutableListOf()) { it.copy(newLabelFlags = newLabelFlags) })
   ```
2. **Update 3-row Top-row Fallbacks:**
   Change `addNumberRowOrPopupKeys` to fire based on actual row count (`size == 3`) instead of the old preference toggle. Also add a null-check so that user-authored hints on 3-row top-row keys take priority over default numbers:
   ```kotlin
   private fun addNumberRowOrPopupKeys(baseKeys: MutableList<MutableList<KeyData>>, numberRow: List<KeyData>) {
       if (params.mId.isAlphabetKeyboard
               && baseKeys.size == 3
               && !hasBuiltInNumbers()) {
           baseKeys.first().forEachIndexed { i, keyData ->
               if (keyData.popup.getPopupKeyLabels(params).isNullOrEmpty()) {
                   keyData.popup.numberLabel = numberRow.getOrNull(i)?.label
               }
           }
       }
   }
   ```

### 2.3 Phase C: Port Localized Digits Pass to Baked Top Rows
Because we no longer prepend the number row at runtime, the per-locale digit-swap pass (`convertToLocalizedNumbers`) must now execute directly on the top row of our parsed 4+ row files.

Add this check immediately after the layout file is parsed into `baseKeys` inside `KeyboardParser.kt`:
```kotlin
if (params.mId.isAlphabetKeyboard
        && baseKeys.size >= 4
        && Settings.getValues().mLocalizedNumberRow
        && params.mLocaleKeyboardInfos.localizedNumberKeys != null) {
    convertToLocalizedNumbers(baseKeys.first())
}
```
This restores correct localized digit behavior (e.g., Persian, Bengali) for baked layouts.

### 2.4 Phase D: Fix "+" Extras Off-by-One Offset
With the top row being the number row in layout files, the existing dynamic extra-keys appender is offset by one row (appending to row 1 instead of row 2). We must adjust the index.

1. **`LayoutParser.kt` (`simpleKeyData` mapping):**
   ```kotlin
   val firstAlphabetRowIndex = if (simpleKeyData.size >= 4) 1 else 0
   simpleKeyData.mapIndexedTo(mutableListOf()) { i, row ->
       val newRow = row.toMutableList()
       if (params.mId.isAlphabetKeyboard
               && layoutName.endsWith("+")
               && i >= firstAlphabetRowIndex) {
           val alphabetRow = i - firstAlphabetRowIndex + 1
           params.mLocaleKeyboardInfos.getExtraKeys(alphabetRow)?.let { newRow.addAll(it) }
       }
       newRow
   }
   ```
2. **`LayoutUtils.kt` (`getContentWithPlus`):**
   Apply the same index adjustment using `rows.size >= 4` so that copying a `+` layout in settings correctly includes the extras.

### 2.5 Phase E: Custom Symbols Hints Carve-out
By default, symbols layout files have hints disabled. However, for a user-customized symbols layout, we want their custom popup choices to render as hints.

Modify `KeyboardParser.kt` `defaultLabelFlags` generation:
```kotlin
private val defaultLabelFlags = when {
    params.mId.isAlphabetKeyboard -> params.mLocaleKeyboardInfos.labelFlags
    params.mId.isAlphaOrSymbolKeyboard
        && LayoutUtilsCustom.isCustomLayout(layoutNameFor(params.mId)) -> 0
    params.mId.isAlphaOrSymbolKeyboard -> Key.LABEL_FLAGS_DISABLE_HINT_LABEL
    else -> 0
}
```

### 2.6 Phase F: Delete Dead Configuration Fields (Safe Order)
Now that the parser is clean, we can safely sweep away the legacy fields, preferences, and setters. Compile after each step.

1. **`KeyboardId.java`:** Remove `mNumberRowEnabled` and `mNumberRowInSymbols` fields, constructor parameters, and adjust `equals`, `hashCode`, `toString` and creation callers.
2. **`KeyboardLayoutSet.java`:** Remove `setNumberRowEnabled` and `setNumberRowInSymbolsEnabled` builder fields and methods.
3. **`KeyboardSwitcher.java`:** Remove the `.setNumberRow…(...)` calls in setup.
4. **`SettingsValues.java`:** Remove `mShowsNumberRow` and `mShowsNumberRowInSymbols` declarations.
5. **`Settings.java`:** Remove the `PREF_SHOW_NUMBER_ROW` and `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS` constants.
6. **`Defaults.kt`:** Delete default pref values for those keys.

---

## 3. JVM Test Expansion

In `app/src/test/java/helium314/keyboard/KeyboardParserTest.kt`, add/extend tests to assert:
1. **4-row render behavior:** A custom 4-row layout parses with literal top-row digits, keeping hints defined in the file without injecting default fallback labels.
2. **3-row fallback behavior:** A custom 3-row layout sets `popup.numberLabel` automatically on top-row keys.
3. **Explicit 3-row hints priority:** A custom 3-row layout with an explicit popup (e.g. `q !`) preserves that popup as the hint, blocking the default number hint from overwriting it.
4. **Catalan "+" Extras:** Built-in `qwerty+` Catalan subtype appends `ç` to row 3 (not row 2 or row 1) when loaded on top of the new 4-row asset.
5. **No regressions on stock English:** Deleting the old toggles does not change English (US) 4-row layout structure or hints.
6. **Localized digits conversion:** Persian subtype renders Persian digits in the baked top row (proving top-row localized digits pass).
7. **Custom symbol hints:** A custom symbols layout has `LABEL_FLAGS_DISABLE_HINT_LABEL` cleared, while built-in `symbols.txt` retains it.

---

## 4. Progress Tracking Checklist

Tick a box (`[ ]` → `[x]`) when the work is done and committed.

- [x] **2.1 Prep:** Create branch `cursor/custom-layouts-pr2-parser-cleanup` off `main`.
- [x] **2.2 Phase A:** Pin all reads to `true` in `KeyboardParser.kt`, `KeyboardBuilder.kt`, etc. Ensure project compiles.
- [x] **2.3 Phase B:** Remove prepend line in `KeyboardParser.kt`, update `addNumberRowOrPopupKeys` for `baseKeys.size == 3` with priority nullcheck.
- [x] **2.4 Phase C:** Add top-row localized digits pass to `KeyboardParser.kt`.
- [x] **2.5 Phase D:** Fix off-by-one row offset in `LayoutParser.kt` and `LayoutUtils.kt` for `+` layouts.
- [x] **2.6 Phase E:** Add custom symbols layout hints exception in `KeyboardParser.kt`.
- [x] **2.7 Phase F:** Systematically delete dead fields from `KeyboardId`, `KeyboardLayoutSet`, `KeyboardSwitcher`, `SettingsValues`, `Settings.java`, and `Defaults.kt`. Verify build compiles clean.
- [x] **2.8 Documentation:** Update `keyboard/internal/keyboard_parser/AGENTS.md` and `latin/settings/AGENTS.md` to match the new behavior.
- [x] **2.9 Strings:** In `SubtypeScreen.kt` and `res/values/strings.xml`, update `PREF_LOCALIZED_NUMBER_ROW` description to: *"Show localised digits in the number row when this language has its own digits"*.
- [x] **2.10 Unit Tests:** Implement the 7 tests in `KeyboardParserTest.kt`.
- [x] **2.11 Test Verification:** Run `./gradlew :app:testDebugUnitTest` and verify all tests pass.
- [ ] **2.12 Manual Smoke Test:**
  - [ ] Build and install on device (`./gradlew installDebug`).
  - [ ] Persian (Farsi) — Verify number row displays localized Persian digits (`۱۲۳۴۵۶۷۸۹۰`).
  - [ ] Catalan — Verify `ç` is appended to row 3, not the number row.
  - [ ] Custom layout edit — Open English layout pencil editor, edit a character, verify layout renders with edits.
