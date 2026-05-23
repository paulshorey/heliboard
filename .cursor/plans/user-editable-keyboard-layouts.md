# HeliBoard user-editable keyboard layouts — implementation plan

> **Status:** v2 — rewritten to be agent-executable. Use **§10
> Progress tracking** as the source of truth for "where we are."
> Every step has a checkbox; tick it with `StrReplace` (`[ ]` →
> `[x]`) as you complete it, and commit the plan along with the
> code change so resumability survives across sessions.

---

## 0. The user goal in one paragraph

A HeliBoard user should be able to **create a new keyboard
layout of their own** by starting from an existing locale's
layout (e.g. English/QWERTY, Russian, Bengali, …), then **edit
the key characters, the long-press popups, the small grey hint
label above each key, *and* the number row** as one coherent
unit. The edited layout appears in *Languages & Layouts →
[language]* alongside the built-ins, can be selected per
subtype, and the user can keep multiple custom variants per
locale (the globe key cycles through them as today).

What "edit" means concretely after this plan ships:

- Every layout slot the user can pick (`MAIN`, `SYMBOLS`,
  `MORE_SYMBOLS`, `FUNCTIONAL`, `NUMBER`, `NUMBER_ROW`,
  `NUMPAD`, `NUMPAD_LANDSCAPE`, `PHONE`, `PHONE_SYMBOLS`,
  `EMOJI_BOTTOM`, `CLIPBOARD_BOTTOM`) has the same **Add /
  Edit / Delete / Load-from-file / Edit-a-copy** affordances.
  Today only `MAIN` has the full set.
- **Edit-a-copy is one tap.** Pick any built-in (e.g. `qwerty`
  inside a German subtype), hit a fork icon, and the editor
  opens pre-filled with that built-in's exact rendered content.
  Save → a new `custom.<scope>.<base36>.<name>.` file is
  written and the active subtype switches to it.
- **The number row is part of that pre-filled content.** Today
  the user edits 3 rows and the keyboard renders 4 because the
  parser silently prepends a number-row asset at render time.
  This plan bakes the number row into every built-in layout
  file so what the user sees in the editor is what the keyboard
  renders. A 4-row file renders 4 rows; deleting the first row
  in the editor gives a clean 3-row layout with digit hints
  inferred above the alphabet — no hidden plumbing.
- **Hints aren't a separate field.** The first popup of each
  key is already what renders as the small grey hint label, so
  editing the hint is the same gesture as editing the
  long-press popup — one source of truth.

---

## Table of contents

0. The user goal in one paragraph (above)
1. Plan conventions — build, test, branching, commits
2. Architecture on `main` today
3. The change in one diagram
4. Implementation — four PRs
5. Edge cases and rationale
6. Test coverage
7. Out of scope and follow-ups
8. Open questions
9. File reference
10. Progress tracking (resumable checklist)
- Appendix A — `tools/bake_number_row.py` specification
- Appendix B — Exhaustive PR-1 file list (74 files)
- Appendix C — PR-2 deletion-sweep order
- Appendix D — Manual verification recipe

---

## 1. Plan conventions

### 1.1 Branching

Each PR lives on its own branch off `main`:

| PR  | Branch name                                                |
| --- | ---------------------------------------------------------- |
| 1   | `cursor/custom-layouts-pr1-bake-number-row`                |
| 2   | `cursor/custom-layouts-pr2-parser-cleanup`                 |
| 3   | `cursor/custom-layouts-pr3-ui-parity`                      |
| 4   | `cursor/custom-layouts-pr4-rebuild-apk`                    |

Each branch is created from latest `main`. PRs land in order
(2 depends on 1's assets, 3 doesn't strictly depend on 1+2 but
it's clearer if they ship sequentially, 4 must be last).

### 1.2 Commit style

Match the existing repo style (verified against recent log):

- Imperative subject ≤72 chars, e.g. `Bake number row into
  built-in alphabet layouts`.
- Optional body paragraph wrapped at ~72 chars explaining
  *why* the change exists and any non-obvious trade-offs.
- One logical change per commit. PR 1 may use several commits
  (per asset family), PR 2 should be one commit per phase from
  Appendix C, PR 3 should be one commit per atomic UI change.
- Use `HEREDOC` for `git commit -m` so the body formats
  correctly:

  ```bash
  git commit -m "$(cat <<'EOF'
  Subject line.

  Body paragraph.
  EOF
  )"
  ```

### 1.3 Build & test commands

| Step | Command |
| --- | --- |
| **JVM unit tests** (default for this plan) | `./gradlew :app:testDebugUnitTest` |
| **All variants' tests** | `./gradlew test` |
| **Fast debug build** | `./gradlew :app:assembleDebugNoMinify` |
| **Canonical install build** | `./gradlew :app:assembleDebug` |
| **Install on connected device** | `./gradlew installDebug` |
| **Logcat tail (keyboard)** | `adb logcat -s LatinIME:V` |
| **Canonical APK (PR 4)** | `./tools/build-dist-apk.sh` → writes `dist/HeliBoard.apk` |

Run `./gradlew :app:testDebugUnitTest` after **every PR** before
opening it; treat failures as blocking.

Reference: `.cursor/skills/development/SKILL.md` for the broader
local-dev guide.

### 1.4 Resumability protocol

This plan has a single source of truth for "where we are":
**§10 Progress tracking**. Whenever you complete a checklist
item:

1. Edit the plan file: change `- [ ]` to `- [x]`.
2. `git add .cursor/plans/user-editable-keyboard-layouts.md`
   in the same commit as the code change it represents (or as
   a standalone tick commit if the work is across many files).

A new agent picking up an interrupted PR starts by reading §10,
finds the first unchecked box, and executes from there. Do not
rely on memory or chat history; rely on the checkboxes.

---

## 2. Architecture on `main` today

### 2.1 Layout assets (built-in) — verified counts

Every layout the app can render lives in
`app/src/main/assets/layouts/<slot>/`. Counts on `main`:

```
assets/layouts/
├── main/              76 files (48 .txt + 28 .json)
├── symbols/           symbols.txt, symbols_arabic.txt          (2)
├── more_symbols/      symbols_shifted.txt                       (1)
├── number_row/        number_row.json, number_row_basic.txt    (2)
├── functional/, number/, numpad/, numpad_landscape/
├── phone/, phone_symbols/
└── emoji_bottom/, clipboard_bottom/
```

Two file formats coexist intentionally and both reach the
parser as the same `KeyData` model via `LayoutParser`:

- **Simple text** (e.g. `qwerty.txt`, `bepo.txt`,
  `symbols.txt`): rows separated by blank lines, one key per
  line. First whitespace-separated token is the primary label;
  later tokens are popup keys. The first popup is also the
  visible hint (`POPUP_KEYS_LAYOUT` source in
  `PopupKeysUtils.getHintLabel`).
- **Rich Floris JSON** (e.g. `azerty.json`, `colemak.json`):
  one JSON array per row; each key is
  `{ "label": "…", "popup": { … }, … }` with optional
  shift-state selectors, code overrides, width hints, etc.

### 2.2 Row counts and the "already taller" subset — verified

Running a row-count on `main` today (verbatim output, sorted):

```
.txt — 48 files:
3 rows ×42 files
4 rows: armenian_phonetic, chuvash, halmak,
        hungarian_extended_qwertz, mansi_north, mari   (6)
5 rows: kannada_extended                               (1)

.json — 28 files:
3 rows ×23 files
4 rows: dvorak, khmer, lao, pcqwerty, thai             (5)
```

Of the **12 already-multi-row files**, only 3 have a number
row baked in (or numbers in popups):
`pcqwerty.json`, `lao.json`, `thai.json`. The remaining 9 have
extra letter rows. Verified via `KeyboardParser.hasBuiltInNumbers()`
at lines 318–322:

```kotlin
private fun hasBuiltInNumbers() = params.mId.mSubtype.mainLayoutName == "pcqwerty"
        || (Settings.getValues().mPopupKeyTypes.contains(POPUP_KEYS_LAYOUT)
            && params.mId.mSubtype.mainLayoutName in listOf("lao", "thai"))
```

So today, with default popup-key settings, a Lao or Thai
keyboard *does* get a prepended Western number row (since the
condition needs `POPUP_KEYS_LAYOUT` in the user's popup-keys
list, which may or may not be on). Verify on a clean install
before relying on it.

**Consequence for PR 1:** skip exactly 3 files (`pcqwerty.json`,
`lao.json`, `thai.json`), bake into the other 73 main + 3
symbol/more_symbols = **74 files** (Appendix B enumerates
them).

### 2.3 Custom layouts (user-edited)

User-edited layouts live under `<filesDir>/layouts/<slot>/`,
with the filename encoding the scope (`LayoutUtilsCustom.kt:137–142`):

| Scope | Naming | Visibility |
| --- | --- | --- |
| MAIN, Latin locale | `custom.Latn.<base36>.<name>.` | every Latin-script subtype |
| MAIN, non-Latin locale | `custom.<bcp47>.<base36>.<name>.` | exact BCP-47 only |
| Non-MAIN slot | `custom.<base36>.<name>.` | universal |

The file body uses the same simple-text or Floris-JSON format
the built-in layouts use.
`LayoutParser.getLayoutFileContent` (`LayoutParser.kt:101–106`)
checks `LayoutUtilsCustom.isCustomLayout(name)` and reads from
the files dir; otherwise it falls through to `context.assets`.

### 2.4 Subtype binding

A subtype carries a `Locale` and an `extraValues` string
containing `KeyboardLayoutSet=MAIN§<layoutName>§SYMBOLS§<layoutName>§…`
that maps each `LayoutType` slot to a specific layout name
(built-in or custom). See `latin/utils/LayoutType.kt:9–11` and
`latin/settings/SettingsSubtype.kt:37, 58–61`. The on-disk
separators are `Separators.KV` / `Separators.ENTRY` constants
(`§`-shaped in effect).

Three relevant prefs:

- `PREF_ENABLED_SUBTYPES` (`Settings.java:161`) — enabled set.
- `PREF_ADDITIONAL_SUBTYPES` (`Settings.java:94`) — user-created
  subtypes, loaded by
  `SubtypeUtilsAdditional.createAdditionalSubtypes`
  (`SubtypeUtilsAdditional.kt:113–117`).
- `PREF_SELECTED_SUBTYPE` (`Settings.java:162`) — current active
  subtype, cycled by the globe key.

The globe key already cycles every enabled subtype. To get
"multiple alphabet variants per locale" today, the user creates
additional subtypes for the same locale, each pointing at a
different MAIN layout. The system supports this end-to-end —
this plan does not change subtype plumbing.

### 2.5 Settings UI today

`settings/screens/LanguageScreen.kt` (the *Languages & Layouts*
list) shows every available subtype with a toggle. Tapping a
row navigates to `settings/screens/SubtypeScreen.kt`. This page
currently has:

- A **Main layout** dropdown (`MainLayoutRow`, lines **401–504**):
  - `+` to **add** a custom layout (blank or via file-picker
    import, lines 424–426, 484–502).
  - Pencil to **edit** any selected layout; built-ins open
    pre-filled via `LayoutUtils.getContentWithPlus` (lines 437,
    464–482). This is today's implicit "edit a copy" — hidden.
  - Bin to **delete** a selected custom layout (lines 438–461).
- Per-slot **secondary** dropdowns (`SubtypeScreen.kt:241–289`):
  - Dropdown + DefaultButton to reset to subtype default.
  - Pencil edits **only existing custom** entries; no `+`, no
    bin, no edit-a-copy on built-ins.
- **Hint source** order/priority dialog (lines **171–192**).
- **Localised number row** Switch (lines **212–235**) bound to
  the per-subtype `LOCALIZED_NUMBER_ROW` extra (only shown for
  locales whose `[number_row]` differs from Western).

The edit dialog (`settings/dialogs/LayoutEditDialog.kt:45–54`)
signature:

```kotlin
fun LayoutEditDialog(
    onDismissRequest: () -> Unit,
    layoutType: LayoutType,
    initialLayoutName: String,
    startContent: String? = null,
    locale: Locale? = null,
    onEdited: (newLayoutName: String) -> Unit = { },
    isNameValid: ((String) -> Boolean)?
)
```

It validates via `LayoutUtilsCustom.checkLayout` (line 104),
writes via `LayoutUtilsCustom.getLayoutFile(...)` (line 81),
calls `SubtypeSettings.onRenameLayout` (line 79),
`LayoutUtilsCustom.onLayoutFileChanged()` (line 82), and
`KeyboardSwitcher.getInstance().setThemeNeedsReload()` (line 85).

### 2.6 Hint labels

Hints are derived from the key's popup set by
`latin/utils/PopupKeysUtils.kt:63–82 → getHintLabel(...)`,
which iterates `params.mPopupKeyLabelSources` and stops at the
first hit. The default priority is `POPUP_KEYS_LABEL_DEFAULT`
(`PopupKeysUtils.kt:16–18`):

```
number=true, language_priority=false, layout=true, symbols=true, language=false
```

So `POPUP_KEYS_NUMBER` wins over `POPUP_KEYS_LAYOUT` by default
— see §5.1 for the implication on 3-row custom layouts.

### 2.7 The number row, today

Today the number row is **prepended at runtime** to alphabet
keyboards from `assets/layouts/number_row/number_row.json`
(default) or `number_row_basic.txt`. Verbatim from
`KeyboardParser.kt:104–113`:

```kotlin
val numberRow = getNumberRow()
addNumberRowOrPopupKeys(baseKeys, numberRow)
// Symbol popups are now defined inline in the layout files
// if (params.mId.isAlphabetKeyboard)
//     addSymbolPopupKeys(baseKeys)
if (params.mId.isAlphabetKeyboard && params.mId.mNumberRowEnabled) {
    val newLabelFlags = defaultLabelFlags or
            if (Settings.getValues().mShowNumberRowHints) 0 else Key.LABEL_FLAGS_DISABLE_HINT_LABEL
    baseKeys.add(0, numberRow.mapTo(mutableListOf()) { it.copy(newLabelFlags = newLabelFlags) })
}
```

`mNumberRowEnabled` flows from `SettingsValues.mShowsNumberRow`
through `KeyboardLayoutSet.Builder.setNumberRowEnabled`
(`KeyboardLayoutSet.java:258–264`) → `KeyboardParams.mNumberRowEnabled`
→ `KeyboardId.mNumberRowEnabled` (`KeyboardId.java:78–79`). On
`main`, `SettingsValues.mShowsNumberRow` is hardcoded to `true`
at `SettingsValues.java:191`:

```java
mShowsNumberRow = true;
```

The pref `PREF_SHOW_NUMBER_ROW` (`Settings.java:138`, default at
`Defaults.kt:129`) is **never read**.

`PREF_SHOW_NUMBER_ROW_IN_SYMBOLS` is read into
`SettingsValues.mShowsNumberRowInSymbols` and passed all the
way through to `KeyboardId.mNumberRowInSymbols` — but **nothing
downstream consumes it** for any rendering decision. Also dead.

The 3-row branch already exists, at `KeyboardParser.kt:274–278`:

```kotlin
private fun addNumberRowOrPopupKeys(baseKeys, numberRow) {
    if (!params.mId.mNumberRowEnabled && params.mId.isAlphabetKeyboard && !hasBuiltInNumbers()) {
        baseKeys.first().forEachIndexed { index, keyData ->
            keyData.popup.numberLabel = numberRow.getOrNull(index)?.label
        }
    }
}
```

When the number row isn't prepended, this sets
`popup.numberLabel` on each top-row key from the locale's
number-row asset. `PopupKeysUtils.getHintLabel` reads that
field via the `POPUP_KEYS_NUMBER` source and shows it as the
grey digit hint.

In other words: **the parser already does the right thing for
both 3-row and 4-row layouts**. The only thing keeping it in
"prepend at runtime" mode is the `mNumberRowEnabled` guard.
Flip that guard to look at parsed row count, bake the number
row into every layout file, and the whole runtime-prepend path
becomes dead code.

### 2.8 Localised digits — where they actually live

The per-locale digit set is **not** a static Kotlin map. It is
parsed from `assets/locale_key_texts/<lang>.txt` under the
`[number_row]` section by `LocaleKeyboardInfos.kt`. Example
(`assets/locale_key_texts/fa.txt`):

```
[number_row]
۱ ۲ ۳ ۴ ۵ ۶ ۷ ۸ ۹ ۰
```

81 such files exist. The runtime swap is implemented by
`convertToLocalizedNumbers` (`KeyboardParser.kt:~290–303`),
which today **runs only on the prepended number row asset**.

**Important consequence for PR 1's "bake Western digits
everywhere" plan**: after baking, `convertToLocalizedNumbers`
no longer runs (because nothing is prepended). PR 2 must extend
the localization pass to the top row of the parsed layout file
when the locale has localized digits. Without that, a Persian
user's farsi.txt would show Western `1234567890` after the
bake. **This is an essential PR-2 work item, not a follow-up.**

### 2.9 The `+` extras mechanism

Layouts named with a trailing `+` in `method.xml` (e.g.
`qwerty+` for Catalan) get locale-specific extra keys appended.
`LayoutParser.kt:91–97`:

```kotlin
return { params ->
    simpleKeyData.mapIndexedTo(mutableListOf()) { i, row ->
        val newRow = row.toMutableList()
        if (params.mId.isAlphabetKeyboard && layoutName.endsWith("+"))
            params.mLocaleKeyboardInfos.getExtraKeys(i+1)?.let { newRow.addAll(it) }
        newRow
    }
}
```

Today (3-row files): `i = 0,1,2` → extras for alphabet rows
1,2,3. After PR 1 bakes a number row, `i = 0,1,2,3` → asks for
extras at row 1 for the *number* row (wrong) and at 2,3,4 for
the alphabet (also wrong). PR 2 fixes this off-by-one. Same
pattern in `LayoutUtils.getContentWithPlus`
(`LayoutUtils.kt:48–49`).

---

## 3. The change in one diagram

```
                BEFORE (main)                                AFTER

    qwerty.txt (3 rows)                       qwerty.txt (4 rows)
    +--------------------+                    +--------------------+
    │ q w e r t y u i o p│                    │ 1 2 3 4 5 6 7 8 9 0│
    │ a s d f g h j k l  │                    │ q w e r t y u i o p│
    │ z x c v b n m      │                    │ a s d f g h j k l  │
    +--------------------+                    │ z x c v b n m      │
              │                               +--------------------+
              ▼                                         │
    KeyboardParser prepends                             ▼
    number_row.json on top                    KeyboardParser
              │                               renders the file as-is
              ▼
    rendered: 4 rows                          rendered: 4 rows
              =                                         =
    file content + 1 row                      file content


    Fork qwerty → custom:                     Fork qwerty → custom:
    +--------------------+                    +--------------------+
    │ q w e r t y u i o p│                    │ 1 2 3 4 5 6 7 8 9 0│
    │ a s d f g h j k l  │   ← User edits 3   │ q w e r t y u i o p│  ← User edits 4
    │ z x c v b n m      │     rows, sees 4   │ a s d f g h j k l  │    rows, sees 4
    +--------------------+     rendered.      │ z x c v b n m      │    rendered.
                                              +--------------------+
                                                       WYSIWYG.
```

User-facing model after the plan:

| File row count | Renders as | Top-row hints |
| --- | --- | --- |
| **4** (number row baked) | 4 rows; row 1 is the number row | First popup of each digit in the file |
| **3** (no number row) | 3 rows; alphabet only | Digit hints auto-appear on the top alphabet row from `[number_row]`. If the user authored an in-file popup on a top-row key, it preserves that — see §5.1 |

To opt out of the number row, the user forks a 4-row built-in,
deletes the first row in the editor, and saves.

---

## 4. Implementation — four PRs

### 4.1 PR 1 — Bake the number row into every built-in layout

**Branch:** `cursor/custom-layouts-pr1-bake-number-row`

**Goal:** make built-in layout files WYSIWYG without changing
any rendering behavior. Pure asset edits + one new test.

**Approach:** A generator script (`tools/bake_number_row.py`,
specced in Appendix A) walks the 74 layout files enumerated in
Appendix B, prepending a number row in the file's existing
format. Western digits 1–0 are baked universally; PR 2 ports
the locale-digit swap to apply to the file's top row.

**Format spec for the baked number row:**

- **Simple-text (`.txt`) files** — prepend exactly these 10
  lines followed by one blank-line separator before the
  original first row:

  ```
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

  These are derived from `number_row.json`'s `default`
  shift-state. Whitespace-separated; the first token (`1`,
  `2`, …) is the primary label, the second token is the hint
  (`POPUP_KEYS_LAYOUT`), the rest are extra long-press popups.

  **Known trade-off:** `number_row.json` declares
  `shift_state_selector` so holding shift swaps `1` → `!` on
  the key itself. The simple-text format can't encode that, so
  baking into `.txt` files **drops the shift-swap behavior** on
  the number row. Long-press for `!` is preserved. Listed as
  Open Question §8.

- **JSON (`.json`) files** — prepend this exact array element
  as the new index-0 row, preserving the existing
  shift-state-selector fidelity:

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

  The element is inserted as the new first array entry; the
  rest of the file is unchanged. Run the result through
  `python3 -m json.tool` to verify syntax.

**Test (new, JVM):** `app/src/test/java/helium314/keyboard/LayoutAssetsTest.kt`.
Assertions:

1. Every file under `assets/layouts/main/*.{txt,json}` parses
   via `LayoutParser` and has **3, 4, 5, or 6 rows**
   (`kannada_extended.txt` will be 6 after baking).
2. Every file under `assets/layouts/{symbols,more_symbols}/*.txt`
   parses and has 4 rows.
3. The 3 skipped files (`pcqwerty.json`, `lao.json`,
   `thai.json`) have unchanged row counts (4).
4. The first row of every non-skipped file contains 10 keys.

Pattern off `app/src/test/java/helium314/keyboard/KeyboardParserTest.kt`
for setup.

**Acceptance:**

- `./gradlew :app:testDebugUnitTest` is green.
- Manual smoke test (`./gradlew installDebug`, see Appendix D):
  English QWERTY still shows the number row at the top with the
  same digits and popups as before. Persian (`farsi.txt`) shows
  the Western number row baked in — **not yet Persian digits**;
  this regression is fixed in PR 2.

**Files touched in this PR:** see Appendix B.

### 4.2 PR 2 — Parser cleanup; layout file is the source of truth

**Branch:** `cursor/custom-layouts-pr2-parser-cleanup`

**Goal:** make the parser stop prepending the number row, port
locale-digit swap to bake top rows, fix the `+` extras
off-by-one, and delete the dead toggle plumbing. After this PR,
the layout file's row count is the *only* source of truth for
whether a number row is present.

**Execution order matters** — see Appendix C for the safe
deletion sweep. Doing this in the wrong order produces large
red builds.

**Concrete edits, in safe order:**

1. **`KeyboardParser.kt`** — replace `mNumberRowEnabled` guards
   and extend the digit-localization pass:

   - **Lines 109–112:** remove the
     `baseKeys.add(0, numberRow.mapTo(...))` injection.
   - **Lines 274–278:** change `addNumberRowOrPopupKeys` to fire
     on `baseKeys.size == 3` instead of `!mNumberRowEnabled`,
     and add the null-check from §5.1 so user-authored popups
     win over auto-digit hints:
     ```kotlin
     private fun addNumberRowOrPopupKeys(baseKeys, numberRow) {
         if (params.mId.isAlphabetKeyboard
                 && baseKeys.size == 3
                 && !hasBuiltInNumbers()) {
             baseKeys.first().forEachIndexed { i, keyData ->
                 if (keyData.popup.getPopupKeyLabels(params).isNullOrEmpty())
                     keyData.popup.numberLabel = numberRow.getOrNull(i)?.label
             }
         }
     }
     ```
   - **New: extend `convertToLocalizedNumbers` to apply to the
     baked top row.** After PR 1 the digits are in the parsed
     layout, not in the `getNumberRow()` asset. Add a call site
     just after `LayoutParser` produces `baseKeys`:
     ```kotlin
     if (params.mId.isAlphabetKeyboard
             && baseKeys.size >= 4
             && Settings.getValues().mLocalizedNumberRow
             && params.mLocaleKeyboardInfos.localizedNumberKeys != null) {
         convertToLocalizedNumbers(baseKeys.first())
     }
     ```
     This restores the Persian-digits-on-farsi-keyboard
     behavior that PR 1 temporarily breaks.
   - **Lines 37–43:** widen the symbol-layer carve-out so
     user-edited symbol layouts show authored popups as hints:
     ```kotlin
     private val defaultLabelFlags = when {
         params.mId.isAlphabetKeyboard -> params.mLocaleKeyboardInfos.labelFlags
         params.mId.isAlphaOrSymbolKeyboard
             && LayoutUtilsCustom.isCustomLayout(layoutNameFor(params.mId)) -> 0
         params.mId.isAlphaOrSymbolKeyboard -> Key.LABEL_FLAGS_DISABLE_HINT_LABEL
         else -> 0
     }
     ```

2. **`LayoutParser.kt:91–97`** — fix the `+` extras index for
   4-row files:
   ```kotlin
   val firstAlphabetRowIndex = if (simpleKeyData.size == 4) 1 else 0
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
   Apply the same fix to `LayoutUtils.getContentWithPlus`
   (`LayoutUtils.kt:48–49`).

3. **Delete dead toggle plumbing** — follow Appendix C's
   ordering. Removed: `PREF_SHOW_NUMBER_ROW`,
   `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS`,
   `SettingsValues.mShowsNumberRow`,
   `SettingsValues.mShowsNumberRowInSymbols`,
   `KeyboardLayoutSet.Builder.setNumberRowEnabled`,
   `KeyboardLayoutSet.Builder.setNumberRowInSymbolsEnabled`,
   `KeyboardId.mNumberRowEnabled`,
   `KeyboardId.mNumberRowInSymbols`, and all their call sites.

4. **Narrow `PREF_LOCALIZED_NUMBER_ROW` scope** — it stays alive
   for the new "convert baked top row" pass plus the 3-row
   `popup.numberLabel` fallback. Update the Switch description
   in `SubtypeScreen.kt:212–235` to:
   *"Show localised digits in the number row when this language
   has its own digits"*. Mirror change in
   `res/values/strings.xml`.

5. **AGENTS.md updates:**
   - `keyboard/internal/keyboard_parser/AGENTS.md` — note that
     the layout file is the single source of truth for row
     count; the digit-localization pass now runs on the baked
     top row.
   - `latin/settings/AGENTS.md` — note that
     `PREF_SHOW_NUMBER_ROW*` are removed.

**Tests:**

- Extend `KeyboardParserTest.kt` with the cases listed in §6.
- Re-run `./gradlew :app:testDebugUnitTest`.

**Acceptance:** see §6 plus Appendix D's full manual recipe.

### 4.3 PR 3 — UI parity for non-MAIN slots and a discoverable "Edit a copy"

**Branch:** `cursor/custom-layouts-pr3-ui-parity`

**Goal:** this is the PR that **directly delivers the user
goal**. After this PR, every layout slot has Add / Edit /
Delete / Load / Fork affordances, and forking is a one-tap
discoverable button rather than the hidden "edit a built-in"
path.

**Files touched:**

- `app/src/main/java/helium314/keyboard/settings/screens/SubtypeScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/dialogs/LayoutEditDialog.kt`
  (caption only)
- `app/src/main/java/helium314/keyboard/settings/screens/LanguageScreen.kt`
  (empty-search hint only)
- `app/src/main/res/values/strings.xml`
- `app/src/main/java/helium314/keyboard/settings/screens/AGENTS.md`

**Concrete edits:**

1. **Extract `LayoutSlotEditor`** from `SubtypeScreen.kt`'s
   `MainLayoutRow` (lines 401–504). Signature:
   ```kotlin
   @Composable
   fun LayoutSlotEditor(
       slotType: LayoutType,
       currentSubtype: SettingsSubtype,
       setCurrentSubtype: (SettingsSubtype) -> Unit,
       builtInLayouts: Collection<String>,
       customLayouts: Collection<String>,
   )
   ```
   Per-slot differences:
   - MAIN: `LayoutUtilsCustom.getLayoutFiles(MAIN, ctx, locale)`
     + `LayoutUtils.getAvailableLayouts(MAIN, ctx, locale)`.
   - Others: `LayoutUtilsCustom.getLayoutFiles(type, ctx)` +
     `LayoutUtils.getAvailableLayouts(type, ctx)` (matches what
     the existing secondary-slot dropdown does at
     `SubtypeScreen.kt:246–247`).

2. **Wire `LayoutSlotEditor` for every slot.** Replace the
   current MAIN-row call (line 157) and the secondary-slot
   blocks (lines 241–289) with a single `forEach` over
   `LayoutType.entries`. Result: every slot has identical
   affordances.

3. **Add the fork icon to the dropdown row.** Within
   `LayoutSlotEditor`, when the row's layout is built-in
   (`!LayoutUtilsCustom.isCustomLayout(name)`), render a
   pencil-with-plus `IconButton` whose `onClick`:
   - For MAIN: reads
     `LayoutUtils.getContentWithPlus(name, currentSubtype.locale, ctx)`.
   - For other slots: reads
     `LayoutUtils.getContent(slotType, name, ctx)`.
   - Opens `LayoutEditDialog` with
     `initialLayoutName = "<name>-copy"`,
     `startContent = <that content>`,
     `isNameValid = { it !in customLayouts }`.
   - In `onEdited`, calls
     `setCurrentSubtype(currentSubtype.withLayout(slotType, newName))`.

   This is the single tap that the user thinks of as "start a
   new keyboard layout from English/Russian/etc."

4. **Caption in `LayoutEditDialog` for `+` forks.** When the
   `initialLayoutName` ends with `+`, render a one-line
   `supportingText`:
   *"Locale-specific extra keys are baked into this copy. They
   won't update if you later use this layout in a different
   language."*
   This warns about the foot-gun in
   `LayoutUtils.getContentWithPlus` (which materializes locale
   extras into the file body — §5.4).

5. **Empty-search hint on `LanguageScreen`.** When the search
   yields no results, render a muted line:
   *"To customise a layout, tap a language above and use the
   **+** or the fork icon on any layout."*

6. **Strings.** All new user-visible text in
   `res/values/strings.xml`. Translators will need an update.

**Acceptance:**

- Every layout slot on the subtype detail screen exposes the
  same five controls: select, add, edit, delete, fork.
- Tapping fork on `qwerty` inside a German subtype creates a
  new `custom.Latn.<base36>.qwerty-copy.` file (4 rows after
  PR 1) and selects it.
- Tapping fork on `russian.txt` inside a Russian subtype creates
  `custom.ru-RU.<base36>.russian-copy.` and selects it.
- `qwerty+` forks show the caption.
- Empty-search shows the hint.
- A user can fork English qwerty, delete the first row in the
  editor, change a couple of hint popups, and save — the result
  renders exactly as authored.

### 4.4 PR 4 — Rebuild the canonical APK

**Branch:** `cursor/custom-layouts-pr4-rebuild-apk`

**Goal:** publish a working artifact for download/install.

**Steps:**

1. Branch from `main` after PRs 1, 2, 3 have all merged.
2. Run `./tools/build-dist-apk.sh`. This writes
   `dist/HeliBoard.apk`.
3. Verify the APK installs cleanly (Appendix D).
4. `git add dist/HeliBoard.apk && git commit -m "Rebuild canonical APK for user-editable layouts"`.

---

## 5. Edge cases and rationale

### 5.1 3-row layouts remain first-class

The parser supports both 3-row and 4-row MAIN files after PR 2.
A user who wants a 3-row keyboard:

- Picks an existing 3-row variant from the dropdown (none ship
  today), **or**
- Forks a 4-row built-in via PR 3, deletes the first row,
  saves. The parser renders 3 rows with digit hints
  automatically.

**Source-order subtlety.** `POPUP_KEYS_LABEL_DEFAULT`
(`PopupKeysUtils.kt:16–18`) is
`number=true, language_priority=false, layout=true, symbols=true, language=false`.
`POPUP_KEYS_NUMBER` therefore wins over `POPUP_KEYS_LAYOUT` by
default. Without intervention, a user who authored `q !` on
their 3-row top row would still see `1` (not `!`) as the hint
because the parser sets `popup.numberLabel = "1"` and that wins.

**Fix:** in PR 2's revised `addNumberRowOrPopupKeys`, only set
`popup.numberLabel` when the key has no in-file popup label
(see PR 2 step 1 above). This makes authored hints win for
top-row keys while preserving the digit-hint default for
unauthored ones.

### 5.2 Localised digits

For 4-row layouts the digits are in the file (PR 1 bakes
Western universally). PR 2 ports `convertToLocalizedNumbers` to
apply to baked top rows, so the user's per-subtype
`LOCALIZED_NUMBER_ROW` extra still produces Persian, Bengali,
Devanagari, … digits on screen. For 3-row layouts the digit
hint comes from `getNumberRow()` as today.

### 5.3 The `+` extras mechanism

After PR 1's bake + PR 2's index fix, `qwerty+` for Catalan
appends `ç` to alphabet row 3 (not the number row). One
`qwerty.txt` continues to serve many locales — locale-specific
extras attached dynamically. No semantic change, just the
off-by-one fix.

### 5.4 Forking from a `+` layout

When the user taps the fork icon on a `qwerty+` subtype, the
new custom file gets the **current locale's extras materialized
into the file body** via `LayoutUtils.getContentWithPlus`
(`LayoutUtils.kt:40–58`). Switching to a different locale
afterwards keeps the original extras. The PR 3 caption warns
the user. Documented behavior, not a bug.

### 5.5 Subtype name override

`SubtypeUtilsAdditional.createAdditionalSubtype:46–47`
already calls `setSubtypeNameOverride(LayoutUtilsCustom.getDisplayName(mainLayoutName))`
on Android 14+ when MAIN is a custom layout. So a row using a
custom *PS-mod* layout under `en` shows up as *English
(PS-mod)*. No new code needed.

### 5.6 Dictionary availability

`dictsAvailable(locale, ctx)` (`LanguageScreen.kt:126–129`)
plus `MissingDictionaryDialog` (lines 115–116, 121–122) continue
to work. This plan does not change the dictionary system.

### 5.7 Cache invalidation

`LayoutUtilsCustom.onLayoutFileChanged()`
(`LayoutUtilsCustom.kt:115–117`) +
`KeyboardSwitcher.setThemeNeedsReload()`
(`KeyboardSwitcher.java:824–837`) are already called by
`LayoutEditDialog`'s save flow and by `deleteLayout`
(`LayoutUtilsCustom.kt:119–123`). PR 3's new fork path goes
through the same `LayoutEditDialog`, so no new hooks needed.

### 5.8 Validation

`LayoutUtilsCustom.checkLayout` (`LayoutUtilsCustom.kt:64–76`)
validates row counts (≥1, ≤8) and keys/row (≤20). PR 1 doesn't
change these; PR 3 adds two soft warnings inline in the dialog:

- *"This layout has 5 rows. Most keyboards have 3 or 4.
  Continue?"* (only for MAIN / SYMBOLS / MORE_SYMBOLS slots;
  `kannada_extended.txt` ships at 6 rows after PR 1, so the
  validator must still accept that — only warn.)
- *"Hint glyph longer than 5 characters may clip."* (Top-row
  popup-as-hint length warning, per
  `.cursor/skills/key-hint-sizing/SKILL.md`.)

---

## 6. Test coverage

JVM tests under `app/src/test/`. No Robolectric needed.

**PR 1 (assets):** see test added in §4.1.

**PR 2 (parser):** extend
`app/src/test/java/helium314/keyboard/KeyboardParserTest.kt`:

- A 4-row MAIN custom layout renders 4 rows where the top
  row's `KeyParams.mLabel` carries digits literally (no
  `popup.numberLabel` injection).
- A 3-row MAIN custom layout renders 3 rows where the parser
  sets `popup.numberLabel` on each top-row key via the new
  `baseKeys.size == 3` trigger.
- A 3-row MAIN custom layout where the top row keys have
  explicit popups (`q !`, `w @`) preserves the user's popups
  as hints (the new null-check from §5.1 keeps
  `popup.numberLabel` unset for keys that already declare an
  in-file popup).
- A built-in `qwerty+` subtype for Catalan still appends `ç`
  to alphabet row 3, not to the number row, on the new 4-row
  base layout.
- The deletion of `PREF_SHOW_NUMBER_ROW`,
  `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS`, and their downstream
  fields doesn't change any observable rendering for a stock
  English subtype on a 4-row built-in layout.
- A Persian subtype on `farsi.txt` with
  `LOCALIZED_NUMBER_ROW` on renders Persian digits in the
  baked top row (proves the localization-pass extension works).
- A built-in `symbols.txt` keeps `LABEL_FLAGS_DISABLE_HINT_LABEL`
  on its keys.
- A user-edited custom symbol layout has the flag cleared via
  the `isCustomLayout` branch — its first popup shows as a
  hint above each key.

**PR 3 (UI):** add
`app/src/test/java/helium314/keyboard/settings/LayoutSlotEditorTest.kt`:

- For a non-MAIN slot, the composable renders Add / Edit /
  Delete / Fork buttons.
- Tapping fork on a built-in opens the edit dialog with the
  expected content.
- Saving a fork from a German subtype produces a Latin-scope
  custom file in `<filesDir>/layouts/main/`.
- Saving a fork from a Russian subtype produces a ru-RU-scope
  custom file.
- A `qwerty+` fork shows the locale-extras caption.

**PR 4:** smoke test only (Appendix D's recipe on a real
device).

---

## 7. Out of scope and follow-ups

- **Compact 3-row built-in variants** as shipped layouts.
- **Functional-row editing UX polish** — parity ships in PR 3
  but the functional row's shift/space behavior deserves
  iteration once we see how users edit it.
- **Export / import all custom layouts** as a single zip.
- **Toolbar button to cycle alphabets within a locale.**
- **A `LABEL_FLAGS_HAS_HINT_LABEL_EXPLICIT` mode** for power
  users who want layout-file-level hint visibility control.

---

## 8. Open questions

- **Should we keep `PREF_LOCALIZED_NUMBER_ROW`?** Recommended:
  keep. Bilingual users want runtime digit swaps without
  forking.
- **Should we drop extras materialization in
  `LayoutUtils.getContentWithPlus`?** Recommended: keep + the
  PR-3 caption. Friendlier for "fork qwerty exactly as I see
  it" users.
- **Should built-in symbols layouts keep
  `LABEL_FLAGS_DISABLE_HINT_LABEL`?** Recommended: yes — their
  popups (`≠ ≈ ≡`) aren't real hints. Document the asymmetry
  with custom symbol layouts in
  `keyboard/internal/keyboard_parser/AGENTS.md`.
- **Should the 9 already-4+-row layouts have their popup hints
  normalized in PR 1?** Recommended: no — leave them alone.
  They have locale-specific intent.
- **Simple-text shift-state regression on the baked number
  row.** PR 1's `.txt` files lose the "shift swaps `1` → `!`
  on the key itself" behavior because simple-text format can't
  encode shift-state selectors. Long-press for `!` is
  preserved. Options:
  - (A) Accept the regression and document. *Default.*
  - (B) Convert all 41 affected `.txt` files to `.json` for
    full fidelity. Much bigger churn.
  - (C) Teach the parser a simple-text shift-state syntax
    (e.g. `1=! ¹ …`). Schema invention; defer.

---

## 9. File reference

**PR 1 (assets):** 74 files — see Appendix B.

**PR 2 (parser cleanup):**

- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt`
- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/LayoutParser.kt`
- `app/src/main/java/helium314/keyboard/latin/utils/LayoutUtils.kt`
- `app/src/main/java/helium314/keyboard/keyboard/KeyboardLayoutSet.java`
- `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java`
- `app/src/main/java/helium314/keyboard/keyboard/KeyboardId.java`
- `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardBuilder.kt`
- `app/src/main/java/helium314/keyboard/keyboard/emoji/EmojiLayoutParams.kt`
- `app/src/main/java/helium314/keyboard/keyboard/clipboard/ClipboardLayoutParams.kt`
- `app/src/main/java/helium314/keyboard/latin/settings/SettingsValues.java`
- `app/src/main/java/helium314/keyboard/latin/settings/Settings.java`
- `app/src/main/java/helium314/keyboard/latin/settings/Defaults.kt`
- `app/src/main/java/helium314/keyboard/latin/utils/ResourceUtils.java`
- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/AGENTS.md`
- `app/src/main/java/helium314/keyboard/latin/settings/AGENTS.md`
- `app/src/main/res/values/strings.xml`
- `app/src/main/java/helium314/keyboard/settings/screens/SubtypeScreen.kt`
  (Switch description string only)
- `app/src/test/java/helium314/keyboard/KeyboardParserTest.kt`

**PR 3 (UI):**

- `app/src/main/java/helium314/keyboard/settings/screens/SubtypeScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/LanguageScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/dialogs/LayoutEditDialog.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/AGENTS.md`
- `app/src/main/res/values/strings.xml`
- `app/src/test/java/helium314/keyboard/settings/LayoutSlotEditorTest.kt`

**PR 4 (release):** `dist/HeliBoard.apk`.

**Read these first if you've never touched this code:**

1. `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt`
2. `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/LayoutParser.kt`
3. `app/src/main/java/helium314/keyboard/latin/utils/LayoutUtilsCustom.kt`
4. `app/src/main/java/helium314/keyboard/settings/screens/SubtypeScreen.kt`
5. `app/src/main/java/helium314/keyboard/settings/dialogs/LayoutEditDialog.kt`
6. `app/src/main/java/helium314/keyboard/latin/utils/SubtypeSettings.kt`
7. `app/src/main/java/helium314/keyboard/latin/utils/PopupKeysUtils.kt`
8. `app/src/main/assets/layouts/AGENTS.md` and
   `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/AGENTS.md`
9. `app/src/main/assets/locale_key_texts/<lang>.txt` +
   `LocaleKeyboardInfos.kt` — `[number_row]` and `[extra_keys]`
   data PR 2 must keep working with the baked top row.

---

## 10. Progress tracking

> **How to use.** Tick a box (`[ ]` → `[x]`) when the work is
> done **and committed**. If a box can't be ticked because of
> a blocker, leave it unchecked and add a `> blocked: <why>`
> note immediately below it. The "currently in progress" PR is
> the first one with at least one unchecked box.

### PR 1 — Bake the number row

- [ ] PR 1.1 Create branch `cursor/custom-layouts-pr1-bake-number-row` off `main`.
- [ ] PR 1.2 Write `tools/bake_number_row.py` per Appendix A; commit standalone.
- [ ] PR 1.3 Dry-run (`python3 tools/bake_number_row.py --dry-run`) and inspect diff for at least: `qwerty.txt`, `azerty.json`, `farsi.txt`, `bengali_unijoy.json`, `symbols.txt`.
- [ ] PR 1.4 Run the script for real on every file in Appendix B; commit "Bake number row into built-in alphabet layouts" (one or more commits, split by directory if convenient).
- [ ] PR 1.5 Add `app/src/test/java/helium314/keyboard/LayoutAssetsTest.kt` with the four assertions from §4.1.
- [ ] PR 1.6 `./gradlew :app:testDebugUnitTest` — all green.
- [ ] PR 1.7 Update `app/src/main/assets/layouts/AGENTS.md` to document "row 1 is the number row in most files".
- [ ] PR 1.8 Manual smoke test per Appendix D's PR-1 section.
- [ ] PR 1.9 Push and open PR.
- [ ] PR 1.10 Merge to `main`.

### PR 2 — Parser cleanup

- [ ] PR 2.1 Create branch `cursor/custom-layouts-pr2-parser-cleanup` off `main`.
- [ ] PR 2.2 Phase A from Appendix C: replace `mNumberRowEnabled` reads with `true` at every consumer. Build green.
- [ ] PR 2.3 Phase B: remove `KeyboardParser.kt:109–112` number-row injection; flip `addNumberRowOrPopupKeys` guard; add the in-file-popup null-check. Build green.
- [ ] PR 2.4 Phase C: extend `convertToLocalizedNumbers` to baked top rows. Build green.
- [ ] PR 2.5 Phase D: fix `+` extras off-by-one in `LayoutParser.kt:91–97` *and* `LayoutUtils.kt:48–49`. Build green.
- [ ] PR 2.6 Phase E: widen `defaultLabelFlags` carve-out for custom symbol layouts. Build green.
- [ ] PR 2.7 Phase F: delete `mShowsNumberRow`, `mShowsNumberRowInSymbols`, `Builder` setters, `KeyboardId` fields and references, prefs + defaults. Build green at each sub-step.
- [ ] PR 2.8 Update `keyboard/internal/keyboard_parser/AGENTS.md` and `latin/settings/AGENTS.md`.
- [ ] PR 2.9 Update `SubtypeScreen.kt` localised-number-row Switch description + matching `strings.xml`.
- [ ] PR 2.10 Extend `KeyboardParserTest.kt` with the 8 cases from §6.
- [ ] PR 2.11 `./gradlew :app:testDebugUnitTest` — all green.
- [ ] PR 2.12 Manual smoke test per Appendix D's PR-2 section (Persian digits, Catalan extras, custom symbol hints).
- [ ] PR 2.13 Push and open PR.
- [ ] PR 2.14 Merge to `main`.

### PR 3 — UI parity for non-MAIN slots and "Edit a copy"

- [ ] PR 3.1 Create branch `cursor/custom-layouts-pr3-ui-parity` off `main`.
- [ ] PR 3.2 Extract `LayoutSlotEditor` composable from `MainLayoutRow`. Wire MAIN through it (still only MAIN). Build + visual smoke. Commit.
- [ ] PR 3.3 Wire every other `LayoutType` through `LayoutSlotEditor`. Build + visual smoke. Commit.
- [ ] PR 3.4 Add the fork (pencil-with-plus) icon and its `LayoutEditDialog` invocation per §4.3 step 3. Commit.
- [ ] PR 3.5 Add the `+`-fork caption to `LayoutEditDialog`. Commit.
- [ ] PR 3.6 Add the empty-search hint to `LanguageScreen`. Commit.
- [ ] PR 3.7 Add new strings to `res/values/strings.xml`.
- [ ] PR 3.8 Update `app/src/main/java/helium314/keyboard/settings/screens/AGENTS.md`.
- [ ] PR 3.9 Add `LayoutSlotEditorTest.kt` (§6 cases).
- [ ] PR 3.10 `./gradlew :app:testDebugUnitTest` — all green.
- [ ] PR 3.11 Manual end-to-end per Appendix D's PR-3 section.
- [ ] PR 3.12 Push and open PR.
- [ ] PR 3.13 Merge to `main`.

### PR 4 — Rebuild canonical APK

- [ ] PR 4.1 Branch `cursor/custom-layouts-pr4-rebuild-apk` off latest `main` (after PRs 1–3 merge).
- [ ] PR 4.2 Run `./tools/build-dist-apk.sh`; confirm `dist/HeliBoard.apk` exists and is non-zero size.
- [ ] PR 4.3 Sideload to a real device and run Appendix D's full recipe.
- [ ] PR 4.4 Commit `dist/HeliBoard.apk`.
- [ ] PR 4.5 Push and open PR.
- [ ] PR 4.6 Merge to `main`.

---

## Appendix A — `tools/bake_number_row.py` specification

**Location:** `tools/bake_number_row.py`. Add a brief mention
in `tools/AGENTS.md` after it lands.

**Invocation:**

```bash
python3 tools/bake_number_row.py [--dry-run] [--root PATH]
```

- No arguments → edits files in place under
  `app/src/main/assets/layouts/`.
- `--dry-run` → prints a unified diff to stdout per file; does
  not write.
- `--root PATH` → use a different repo root (defaults to the
  script's parent's parent).

**Behavior:**

1. Build the target file list from Appendix B (or compute it:
   every file in `main/`, `symbols/`, `more_symbols/` minus the
   3 skip-set entries).
2. For each target file:
   - Detect format by extension (`.txt` vs `.json`).
   - For `.txt`: prepend the 10 lines from §4.1's simple-text
     spec, plus one blank line, plus the original content.
   - For `.json`: parse with `json.loads`, insert the
     §4.1's JSON object as the new `result[0]`, and write with
     `json.dumps(..., indent=2, ensure_ascii=False)`. Match the
     existing trailing-newline convention of the original file.
3. Print a per-file `OK` or `SKIPPED` summary at end.

**Refuses to act on:**

- `pcqwerty.json`, `lao.json`, `thai.json` (numbers already
  in-file via `hasBuiltInNumbers()`).
- Any file whose first non-blank row already starts with a
  primary label that matches `[0-9]|[١-٩]|[०-९]|[০-৯]|[๐-๙]`
  (rough number-row sniffer; if matched, log a warning and
  skip — likely already baked).

**Failure modes:**

- Exit 2 if JSON parse fails (file path printed).
- Exit 3 if the target file already has ≥6 rows (avoid runaway
  baking on `kannada_extended.txt` — it already has 5, baking
  would make 6 which is the agreed cap; the script should
  succeed for exactly that file but reject anything that would
  result in ≥7).

**Idempotency:** running the script twice is a no-op (the
number-row sniffer detects the baked row and skips).

---

## Appendix B — Exhaustive PR-1 file list (74 files)

> Verified against `main` at the time this plan was written.
> Three skip entries (`pcqwerty.json`, `lao.json`,
> `thai.json`) are listed at the bottom for completeness.

### `app/src/main/assets/layouts/main/` — 73 files

`.txt` (48 total, all included):

```
akan.txt              arabic.txt            arabic_hijai.txt
arabic_pc.txt         armenian_phonetic.txt belarusian.txt
bemba.txt             bepo.txt              bulgarian.txt
bulgarian_bds.txt     bulgarian_bekl.txt    central_kurdish.txt
chuvash.txt           dagbani.txt           dargwa_urakhi.txt
esperanto.txt         ewe.txt               farsi.txt
ga.txt                halmak.txt            hausa.txt
hungarian_extended_qwertz.txt               igbo.txt
kaitag.txt            kannada.txt           kannada_extended.txt
kikuyu.txt            lingala.txt           luganda.txt
macedonian.txt        malayalam.txt         mansi_north.txt
mari.txt              mongolian.txt         qwerty.txt
qwertz.txt            russian.txt           russian_extended.txt
russian_student.txt   serbian.txt           sesotho.txt
tamil.txt             telugu.txt            turkish.txt
ukrainian.txt         ukrainian_extended.txt                workman.txt
yoruba.txt
```

`.json` (25 of 28 — minus the 3 skips):

```
azerty.json           bengali_akkhor.json   bengali_baishakhi.json
bengali_inscript.json bengali_probhat.json  bengali_unijoy.json
colemak.json          colemak_dh.json       dvorak.json
georgian.json         greek.json            gujarati.json
hebrew.json           hebrew_1452_2.json    hindi.json
hindi_compact.json    hindi_phonetic.json   kabyle.json
khmer.json            marathi.json          nepali_romanized.json
nepali_traditional.json                     sinhala.json
urdu.json             uzbek.json
```

### `app/src/main/assets/layouts/symbols/` — 2 files

```
symbols.txt           symbols_arabic.txt
```

### `app/src/main/assets/layouts/more_symbols/` — 1 file

```
symbols_shifted.txt
```

### **SKIPPED** — do not touch in PR 1

```
app/src/main/assets/layouts/main/pcqwerty.json   # numbers already in row 1
app/src/main/assets/layouts/main/lao.json        # Lao digits in popups; hasBuiltInNumbers() = true
app/src/main/assets/layouts/main/thai.json       # Thai digits in popups; hasBuiltInNumbers() = true
```

**Sanity check before PR 1 merge:** `find app/src/main/assets/layouts/{main,symbols,more_symbols} -type f \( -name '*.txt' -o -name '*.json' \) | wc -l` should output `77` (74 baked + 3 skipped).

---

## Appendix C — PR-2 deletion-sweep order

The fields and prefs listed below are interdependent. Removing
the field declaration first will produce dozens of unresolved-
reference errors. Follow this phase order; each phase ends with
a green `./gradlew :app:assembleDebugNoMinify`.

### Phase A — pin reads to `true` first

For each consumer below, **replace the read with the constant
`true`**, do not delete the field yet.

| Consumer | Field read |
| --- | --- |
| `KeyboardParser.kt:67` | `Settings.getValues().mShowsNumberRow` |
| `KeyboardParser.kt:109` (inside the to-be-removed block) | `params.mId.mNumberRowEnabled` |
| `KeyboardParser.kt:275` | `params.mId.mNumberRowEnabled` |
| `KeyboardBuilder.kt:116` | `Settings.getValues().mShowsNumberRow` |
| `EmojiLayoutParams.kt:39` | `Settings.getValues().mShowsNumberRow` |
| `ClipboardLayoutParams.kt:43` | `Settings.getValues().mShowsNumberRow` |
| `ResourceUtils.java:84` | `Settings.getValues().mShowsNumberRow` |

After this phase, build green.

### Phase B — remove KeyboardParser.kt prepend + flip guard

Remove lines 109–112 (`baseKeys.add(0, …)`) and update
`addNumberRowOrPopupKeys` per PR 2 step 1.

### Phase C — port `convertToLocalizedNumbers`

Add the call site from PR 2 step 1.

### Phase D — fix `+` extras index

`LayoutParser.kt:91–97` and `LayoutUtils.kt:48–49`.

### Phase E — widen `defaultLabelFlags` carve-out

`KeyboardParser.kt:37–43`.

### Phase F — delete dead fields in safe order

Bottom-up dependency order. Each sub-phase ends with a green
build.

1. **`KeyboardId.java:78–79, 98–99`** — remove
   `mNumberRowEnabled`, `mNumberRowInSymbols` fields,
   constructor parameters, `equals`, `hashCode`, `toString`,
   and any call sites that construct a `KeyboardId`.
2. **`KeyboardLayoutSet.java:258–264`** — remove
   `setNumberRowEnabled`, `setNumberRowInSymbolsEnabled`, and
   the corresponding `mNumberRowEnabled`,
   `mNumberRowInSymbols` builder fields.
3. **`KeyboardSwitcher.java:157–158, 174–175`** — remove the
   four `.setNumberRow…(...)` calls.
4. **`SettingsValues.java:191–192`** — remove the
   `mShowsNumberRow` and `mShowsNumberRowInSymbols` field
   declarations (now no readers remain).
5. **`Settings.java`** — remove `PREF_SHOW_NUMBER_ROW` (line
   138) and `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS` constants.
6. **`Defaults.kt:129`** — remove the matching default entries.

### Phase G — narrow `PREF_LOCALIZED_NUMBER_ROW` description

`SubtypeScreen.kt:212–235` Switch text + `strings.xml`.

### Phase H — AGENTS.md updates

`keyboard/internal/keyboard_parser/AGENTS.md`,
`latin/settings/AGENTS.md`.

### Phase I — tests

Add the 8 cases from §6 to `KeyboardParserTest.kt`. Run
`./gradlew :app:testDebugUnitTest`.

---

## Appendix D — Manual verification recipe

Use a real Android device or emulator (API 30+). Install via
`./gradlew installDebug` (or sideload `dist/HeliBoard.apk` for
PR 4 verification). Enable HeliBoard as the active IME. The
test app can be any text field (e.g. Chrome's URL bar, Notes).

### PR 1 manual smoke

- [ ] English (US) — open keyboard, verify number row at top:
      `1 2 3 4 5 6 7 8 9 0` with hints `! @ # $ % ^ & * ( )`.
- [ ] Long-press `1` shows `! ¹ ½ ⅓ ¼ ⅛` popups. Match against
      the pre-bake screenshot/memory.
- [ ] Russian — number row visible, layout otherwise identical.
- [ ] Bengali (any variant) — keyboard renders without crashes.
      Number row shows Western digits (PR 2 fixes localization).
- [ ] Symbols layer (`?123`) — top row shows `1 2 3 …`.

### PR 2 manual smoke

- [ ] Same checks as PR 1.
- [ ] Persian (Farsi) — number row now shows `۱۲۳۴۵۶۷۸۹۰`
      Persian digits (proves the
      `convertToLocalizedNumbers` extension works).
- [ ] Catalan (`qwerty+`) — long-press `c` shows `ç` as a
      popup; the `ç` is on alphabet row 3, not row 1 (number
      row).
- [ ] Settings → Languages → English → edit `qwerty` (pencil) →
      modify a key → save → open keyboard → change is visible.
- [ ] Settings → Languages → English → SYMBOLS slot → edit an
      existing custom symbol layout (if any; create one first
      if not) → first popup shows as hint label (the new
      `isCustomLayout` carve-out behavior).

### PR 3 manual smoke (the user-goal verification)

- [ ] Settings → Languages → tap "English (US)" → subtype
      detail page loads.
- [ ] On the MAIN row, fork icon (pencil-with-plus) visible
      next to `qwerty`. Tap it → editor opens with 4 rows of
      qwerty content, name pre-filled as `qwerty-copy`.
- [ ] Delete row 1 (the number row) in the editor. Save.
- [ ] Open keyboard → renders 3 rows of alphabet only, with
      digit hints `1 2 3 …` above the top row.
- [ ] Re-enter edit dialog, change hint popup `q !` →
      `q Q!`. Save. Re-open keyboard → hint above `q` shows
      `Q` (the new first popup), per §5.1's null-check.
- [ ] SYMBOLS slot now shows the same five controls (select,
      add, edit, delete, fork). Tap fork on `symbols` →
      editor opens pre-filled with the baked 4-row symbol
      layout. Save without changes → new custom symbol layout
      appears in the dropdown and is selected.
- [ ] Switch active subtype to Russian via globe key →
      Russian layout still renders correctly (custom English
      layouts don't bleed across non-Latin scope).
- [ ] Open Settings → Languages → search "zzz" (no match) →
      empty-search hint appears beneath the search field.
- [ ] Fork `qwerty+` (Catalan or Danish subtype) → caption
      appears warning about frozen extras.

### PR 4 manual smoke

- [ ] `dist/HeliBoard.apk` installs cleanly on a fresh device.
- [ ] Repeat the PR-3 recipe end-to-end on the installed APK.
