# HeliBoard User-Editable Keyboard Layouts — Part 1: Bake Number Row

This is the first of three plans to implement user-editable keyboard layouts. The goal of this phase is to make built-in layout files WYSIWYG by baking the top number row directly into the asset files, without changing any rendering behavior.

---

## 1. Goal & Branching

- **Branch name:** `cursor/custom-layouts-pr1-bake-number-row`
- **Objective:** Pure layout asset modifications + one Python script + one new JVM asset-parsing test. No keyboard runtime code changes are made in this branch.

---

## 2. Technical Approach

Today, the 4th (number) row is prepended dynamically at runtime from `assets/layouts/number_row/number_row.json` to 3-row keyboards. In this step, we will use a Python script to prepend this row directly into the 76 layout files so that what the user sees in the editor in the future is exactly what renders (WYSIWYG).

### 2.1 The Baked Number Row Format

- **For simple-text (`.txt`) files:** Prepend these 10 lines followed by exactly one empty line:
  ```text
  1 ! ¹ ½ ⅓ ¼ ⅛
  2 @ ²
  3 # ³ ¾ ⅜
  4 $ ⁴
  5 % ⁵ ⅝
  6 ^ ⁶
  7 & ⁷
  8 * ⁸
  9 ( ⁹
  0 ) ⁰ ⁿ ∅
  ```
  *Note:* The first token is the primary label, the second token is the hint (`POPUP_KEYS_LAYOUT`), and the rest are long-press popup keys.

- **For JSON (`.json`) files:** Prepend this JSON array as the new index-0 row of the root layout array:
  ```json
  [
    { "$": "shift_state_selector",
      "manualOrLocked": { "label": "!" },
      "default": { "label": "1", "popup": { "main": { "label": "!" }, "relevant": [{ "label": "¹" }, { "label": "½" }, { "label": "⅓" }, { "label": "¼" }, { "label": "⅛" }] } } },
    { "$": "shift_state_selector",
      "manualOrLocked": { "label": "@" },
      "default": { "label": "2", "popup": { "main": { "label": "@" }, "relevant": [{ "label": "²" }, { "label": "⅔" }] } } },
    { "$": "shift_state_selector",
      "manualOrLocked": { "label": "#" },
      "default": { "label": "3", "popup": { "main": { "label": "#" }, "relevant": [{ "label": "³" }, { "label": "¾" }, { "label": "⅜" }] } } },
    { "$": "shift_state_selector",
      "manualOrLocked": { "label": "$" },
      "default": { "label": "4", "popup": { "main": { "label": "$" }, "relevant": [{ "label": "⁴" }] } } },
    { "$": "shift_state_selector",
      "manualOrLocked": { "label": "%" },
      "default": { "label": "5", "popup": { "main": { "label": "%" }, "relevant": [{ "label": "⁵" }, { "label": "⅝" }] } } },
    { "$": "shift_state_selector",
      "manualOrLocked": { "label": "^" },
      "default": { "label": "6", "popup": { "main": { "label": "^" }, "relevant": [{ "label": "⁶" }] } } },
    { "$": "shift_state_selector",
      "manualOrLocked": { "label": "&" },
      "default": { "label": "7", "popup": { "main": { "label": "&" }, "relevant": [{ "label": "⁷" }, { "label": "⅞" }] } } },
    { "$": "shift_state_selector",
      "manualOrLocked": { "label": "*" },
      "default": { "label": "8", "popup": { "main": { "label": "*" }, "relevant": [{ "label": "⁸" }] } } },
    { "$": "shift_state_selector",
      "manualOrLocked": { "label": "(" },
      "default": { "label": "9", "popup": { "main": { "label": "(" }, "relevant": [{ "label": "⁹" }] } } },
    { "$": "shift_state_selector",
      "manualOrLocked": { "label": ")" },
      "default": { "label": "0", "popup": { "main": { "label": ")" }, "relevant": [{ "label": "⁰" }, { "label": "ⁿ" }, { "label": "∅" }] } } }
  ]
  ```

---

## 3. Automation Script Specification

**Location:** `tools/bake_number_row.py`

**Usage:**
```bash
python3 tools/bake_number_row.py [--dry-run] [--root PATH]
```

### Script Requirements:
1. **Target Finding:** Walk `app/src/main/assets/layouts/main/`, `symbols/`, and `more_symbols/` to locate all `.txt` and `.json` layout files.
2. **Explicit Skip List:** Absolutely refuse to modify the following three files where the number row is already handled/baked:
   - `app/src/main/assets/layouts/main/pcqwerty.json` (numbers already in row 1)
   - `app/src/main/assets/layouts/main/lao.json` (Lao digits in popups; `hasBuiltInNumbers() = true`)
   - `app/src/main/assets/layouts/main/thai.json` (Thai digits in popups; `hasBuiltInNumbers() = true`)
3. **Idempotency Guard:** If the first row of a file already has a number row or digits `[0-9]|[١-٩]|[०-९]`, log a warning and skip that file.
4. **Safety Limits:** Reject any file whose modifications would result in `rows >= 7` (e.g. `kannada_extended.txt` has 5 rows, baking makes 6, which is accepted, but nothing should become 7+).
5. **Output Formatting:**
   - For `.json`, parse with `json.loads`, insert the baked array element as index 0, and write with `json.dumps(..., indent=2, ensure_ascii=False)`.
   - Preserve existing trailing newlines.

---

## 4. JVM Verification Test

Create a new test file: `app/src/test/java/helium314/keyboard/LayoutAssetsTest.kt`

Implement tests with these assertions:
1. Every file in `assets/layouts/main/*.{txt,json}` parses successfully using the existing `LayoutParser` and contains **3, 4, 5, or 6 rows** (`kannada_extended.txt` will be 6 after baking).
2. Every file in `assets/layouts/{symbols,more_symbols}/*.txt` parses successfully and has exactly **4 rows**.
3. The 3 skipped files (`pcqwerty.json`, `lao.json`, `thai.json`) are validated to have unchanged row counts (4).
4. The first row of every processed file contains exactly **10 keys**.

---

## 5. Progress Tracking Checklist

Tick a box (`[ ]` → `[x]`) when the work is done and committed.

- [x] **1.1 Prep:** Create branch `cursor/custom-layouts-pr1-bake-number-row` off `main`.
- [x] **1.2 Scripting:** Write `tools/bake_number_row.py` per specification; commit standalone.
- [x] **1.3 Verification:** Dry-run (`python3 tools/bake_number_row.py --dry-run`) and verify unified diff outputs for `qwerty.txt`, `azerty.json`, `farsi.txt`, and `symbols.txt`.
- [x] **1.4 Execution:** Run the script for real to bake in-place; commit changes under "Bake number row into built-in layouts".
- [x] **1.5 JVM Testing:** Create `app/src/test/java/helium314/keyboard/LayoutAssetsTest.kt` with all 4 asset validation assertions.
- [x] **1.6 Local Build:** Run `./gradlew :app:testDebugUnitTest` and ensure all tests are green.
- [x] **1.7 Documentation:** Update `app/src/main/assets/layouts/AGENTS.md` to document that "row 1 is the baked-in number row in built-in layouts".
- [ ] **1.8 Smoke Test:**
  - [ ] Build and install on device (`./gradlew installDebug`).
  - [ ] English (US) — check top row shows Western numbers with hints and full popups.
  - [ ] Russian — check layout has baked number row.
  - [ ] Symbols layer (`?123`) — top row shows `1 2 3 …`.
- [ ] **1.9 Deliver:** Open PR and merge to `main`.

---

## 6. Appendix: Exhaustive File List (76 files to bake)

### assets/layouts/main/ (73 files)
**Simple-text (.txt) - 48 files:**
- `akan.txt`, `arabic.txt`, `arabic_hijai.txt`, `arabic_pc.txt`, `armenian_phonetic.txt`, `belarusian.txt`, `bemba.txt`, `bepo.txt`, `bulgarian.txt`, `bulgarian_bds.txt`, `bulgarian_bekl.txt`, `central_kurdish.txt`, `chuvash.txt`, `dagbani.txt`, `dargwa_urakhi.txt`, `esperanto.txt`, `ewe.txt`, `farsi.txt`, `ga.txt`, `halmak.txt`, `hausa.txt`, `hungarian_extended_qwertz.txt`, `igbo.txt`, `kaitag.txt`, `kannada.txt`, `kannada_extended.txt`, `kikuyu.txt`, `lingala.txt`, `luganda.txt`, `macedonian.txt`, `malayalam.txt`, `mansi_north.txt`, `mari.txt`, `mongolian.txt`, `qwerty.txt`, `qwertz.txt`, `russian.txt`, `russian_extended.txt`, `russian_student.txt`, `serbian.txt`, `sesotho.txt`, `tamil.txt`, `telugu.txt`, `turkish.txt`, `ukrainian.txt`, `ukrainian_extended.txt`, `workman.txt`, `yoruba.txt`

**JSON (.json) - 25 files (excluding 3 skips):**
- `azerty.json`, `bengali_akkhor.json`, `bengali_baishakhi.json`, `bengali_inscript.json`, `bengali_probhat.json`, `bengali_unijoy.json`, `colemak.json`, `colemak_dh.json`, `dvorak.json`, `georgian.json`, `greek.json`, `gujarati.json`, `hebrew.json`, `hebrew_1452_2.json`, `hindi.json`, `hindi_compact.json`, `hindi_phonetic.json`, `kabyle.json`, `khmer.json`, `marathi.json`, `nepali_romanized.json`, `nepali_traditional.json`, `sinhala.json`, `urdu.json`, `uzbek.json`

### assets/layouts/symbols/ (2 files)
- `symbols.txt`, `symbols_arabic.txt`

### assets/layouts/more_symbols/ (1 file)
- `symbols_shifted.txt`
