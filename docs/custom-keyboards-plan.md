# HeliBoard custom keyboard layouts — implementation plan

A plan to make per-locale keyboard layouts user-editable from the
existing **Languages & Layouts** settings tree, with full
add / edit / delete support for every layout slot and a one-tap
"Edit a copy" affordance for forking built-in layouts.

This plan is written to be executed against `main`. The reader does
not need to know about any prior branches.

## Table of contents

1. Goal
2. Architecture on `main` today
3. The change in one diagram
4. Implementation: four PRs
5. Edge cases and rationale
6. Test coverage
7. Out of scope and follow-ups
8. Open questions
9. File reference

---

## 1. Goal

HeliBoard already has a per-locale custom-layout system: each
*Languages & Layouts* entry opens a *SubtypeScreen* with a
*Main layout* dropdown that lists built-in layouts plus
user-defined ones (filed under `<filesDir>/layouts/main/`). The
user can add, edit, and delete custom MAIN layouts there. The
plan does three things:

1. **Bake the number row into every built-in MAIN, SYMBOLS, and
   MORE_SYMBOLS layout file.** Today the number row is added at
   *runtime* from `assets/layouts/number_row/` and is invisible
   when a user forks a layout file — they edit three rows and
   the rendered keyboard mysteriously has four. After this
   change the file is WYSIWYG: a 4-row file renders 4 rows, a
   3-row file renders 3 rows.
2. **Bring SYMBOLS, MORE_SYMBOLS, FUNCTIONAL, and the other
   non-MAIN slots to MAIN parity in `SubtypeScreen`.** Today
   non-MAIN slots show a dropdown of layouts but no Add / Delete /
   Edit-a-copy controls. Extract the MAIN slot's editor row into
   a reusable composable and wire it into every slot.
3. **Add an "Edit a copy" affordance** so the user can fork a
   built-in layout into a custom one with one tap, pre-filled
   with the built-in's content (number row included, per #1).

After these changes the user can manage and customize all
keyboard key-character layouts per locale, add multiple variants
per locale (each variant is an additional subtype that the globe
key cycles through), and edit every slot independently — all from
*Languages & Layouts → [language]*.

Two pieces of dead runtime machinery (`PREF_SHOW_NUMBER_ROW`,
`mNumberRowInSymbols`) get deleted along the way because the
layout file is now the only source of truth for what rows a
keyboard has.

---

## 2. Architecture on `main` today

### 2.1 Layout assets (built-in)

Every layout the app can render lives in
`app/src/main/assets/layouts/<slot>/`:

```
assets/layouts/
├── main/              76 files: qwerty.txt, azerty.json, colemak.json, bepo.txt, bengali_unijoy.json, …
├── symbols/           symbols.txt, symbols_arabic.txt
├── more_symbols/      symbols_shifted.txt
├── functional/        functional_keys.json, _tablet.json, _khipro.json
├── number/, number_row/, numpad/, numpad_landscape/
├── phone/, phone_symbols/
├── emoji_bottom/, clipboard_bottom/
└── …
```

Two file formats coexist intentionally:

- **Simple text** (e.g. `qwerty.txt`, `bepo.txt`, `symbols.txt`):
  rows separated by blank lines, one key per line. First
  whitespace-separated token is the primary label; later tokens
  are popup keys. The first popup is *also* the visible hint
  label (small grey letter above the primary), via the
  `POPUP_KEYS_LAYOUT` source in `PopupKeysUtils.getHintLabel`.
- **Rich Floris JSON** (e.g. `azerty.json`, `colemak.json`):
  one JSON array per row, each key is
  `{ "label": "a", "popup": { … }, … }` with optional shift-state
  selectors, code overrides, width hints, etc.

Both formats are parsed by `LayoutParser` and reach
`KeyboardParser`/`KeyboardBuilder` as the same `KeyData` model.
Existing layouts mix the two formats freely; we don't need to
convert one to the other.

### 2.2 Custom layouts (user-edited)

The existing custom-layout system stores user-edited layouts as
plain files under the app's device-protected files dir:

```
<filesDir>/layouts/
├── main/             custom.Latn.<base36>.,   custom.fr-FR.<base36>.,   …
├── symbols/          custom.<base36>.,                                  …
├── more_symbols/     custom.<base36>.,                                  …
└── functional/, number/, etc.
```

`LayoutUtilsCustom.kt` owns the naming convention:

- **MAIN, Latin locale**: `custom.Latn.<base36>.<…>` — the file is
  visible to **every subtype whose locale uses Latin script**.
- **MAIN, non-Latin locale**: `custom.<bcp47>.<base36>.<…>` — the
  file is visible only to that exact BCP-47 tag (e.g.
  `custom.fr-FR.…` for French).
- **Non-MAIN slot**: `custom.<base36>.<…>` — the file is visible to
  **every subtype, regardless of locale**.

The file body uses the same simple-text or Floris-JSON format the
built-in layouts use. `LayoutParser.getLayoutFileContent` checks
`LayoutUtilsCustom.isCustomLayout(name)` and reads from the files
dir; otherwise it falls through to `context.assets`.

The `custom.Latn.*` convention already serves as the implicit
"applies to any Latin language" wildcard — anything authored under
that scope appears in every Latin subtype's *Main layout*
dropdown. Non-MAIN custom layouts are already universal because
their lookup ignores locale.

### 2.3 Subtype binding

A *subtype* is the unit the user picks via the globe key. It
carries a `Locale` and an `extraValues` string containing
`KeyboardLayoutSet=MAIN§<layoutName>§SYMBOLS§<layoutName>§…` that
maps each `LayoutType` slot to a specific layout name (built-in
or custom). See `latin/utils/LayoutType.kt` and
`latin/settings/SettingsSubtype.kt`.

Three prefs hold the state:

- `PREF_ENABLED_SUBTYPES` — the user's enabled subtypes.
- `PREF_ADDITIONAL_SUBTYPES` — user-created subtypes that differ
  from the resource ones. Loaded by
  `SubtypeUtilsAdditional.createAdditionalSubtypes`.
- `PREF_SELECTED_SUBTYPE` — currently active subtype (cycled by
  the globe key).

The globe key already cycles every enabled subtype. To get
"multiple alphabet variants per locale" today, the user creates
two additional subtypes for the same locale, each pointing at a
different MAIN layout. The system supports this end-to-end.

### 2.4 Settings UI today

`settings/screens/LanguageScreen.kt` (the *Languages & Layouts*
list) shows every available subtype with a toggle. Tapping a row
navigates to:

`settings/screens/SubtypeScreen.kt` (the *Subtype detail* page).
This page currently has:

- A **Main layout** dropdown (`MainLayoutRow` composable, lines
  401-504). For MAIN this dropdown has:
  - A `+` button to **add** a custom layout (from blank or by
    file-picker import).
  - An inline pencil to **edit** the selected custom layout.
  - A bin to **delete** the selected custom layout, with a
    confirmation when other subtypes still use it.
  - When the selected entry is a built-in, the pencil opens the
    edit dialog with the built-in's content pre-filled; saving
    creates a new custom layout (this is the implicit
    "edit a copy" path, currently quite hidden in the UI).
- Per-slot **secondary** dropdowns for `SYMBOLS`, `MORE_SYMBOLS`,
  `FUNCTIONAL`, `NUMBER`, `NUMBER_ROW`, `NUMPAD`,
  `NUMPAD_LANDSCAPE`, `PHONE`, `PHONE_SYMBOLS`, `EMOJI_BOTTOM`,
  `CLIPBOARD_BOTTOM` (lines 241-289). These have:
  - The dropdown of built-in + custom files for that slot.
  - An inline pencil that opens `LayoutEditDialog` for **existing
    custom** entries.
  - **No `+` button** to create a new custom layout for that slot.
  - **No bin** for delete.
  - No "edit a copy" affordance for built-in entries.

So MAIN is fully managed and non-MAIN slots are stuck halfway:
you can pick a layout, and edit an existing custom one, but you
can't create a new one or delete one from this UI.

The edit dialog (`settings/dialogs/LayoutEditDialog.kt`) is a
multi-line text editor that accepts both layout formats. It
validates by re-parsing via `LayoutUtilsCustom.checkLayout`,
writes the result, calls `SubtypeSettings.onRenameLayout` to keep
prefs consistent, and calls `KeyboardSwitcher.setThemeNeedsReload`.

### 2.5 Hint labels

The grey hint character drawn above each key is **not** declared
in a dedicated field. It's derived from the key's popup set by
`latin/utils/PopupKeysUtils.kt → getHintLabel(...)`:

```kotlin
for (type in params.mPopupKeyLabelSources) {
    when (type) {
        POPUP_KEYS_NUMBER  -> popupSet?.numberLabel?.let { hintLabel = it }
        POPUP_KEYS_LAYOUT  -> popupSet?.getPopupKeyLabels(params)?.let { hintLabel = it.firstOrNull() }
        POPUP_KEYS_SYMBOLS -> popupSet?.symbol?.let { hintLabel = it }
        POPUP_KEYS_LANGUAGE          -> …
        POPUP_KEYS_LANGUAGE_PRIORITY -> …
    }
    if (hintLabel != null) break
}
```

The default priority (`POPUP_KEYS_LABEL_DEFAULT`) makes
`POPUP_KEYS_NUMBER` first when present and `POPUP_KEYS_LAYOUT` —
"the first popup declared by the layout file itself" — second.
This means:

- A simple-text line `a @` (primary `a`, popup `@`) renders as
  *primary `a`, hint `@`*. The popup IS the hint.
- A JSON `{ "label": "a", "popup": { "main": { "label": "@" } } }`
  renders identically.
- A simple-text line `i - –` (primary `i`, popups `-` and `–`)
  renders as *primary `i`, hint `-`, additional popup `–`*. The
  first popup wins.

A layout author writes a hint by writing a popup. No separate
`hint` field needs to exist.

### 2.6 The number row, today

Today the number row is **prepended at runtime** to alphabet
keyboards from `assets/layouts/number_row/number_row.json` (or
`number_row_basic.txt`):

```kotlin
// KeyboardParser.kt:104-112
val numberRow = getNumberRow()
addNumberRowOrPopupKeys(baseKeys, numberRow)
…
if (params.mId.isAlphabetKeyboard && params.mId.mNumberRowEnabled) {
    val newLabelFlags = defaultLabelFlags or
        if (mShowNumberRowHints) 0 else Key.LABEL_FLAGS_DISABLE_HINT_LABEL
    baseKeys.add(0, numberRow.mapTo(mutableListOf()) { it.copy(newLabelFlags = newLabelFlags) })
}
```

`mNumberRowEnabled` flows from `SettingsValues.mShowsNumberRow`
through `KeyboardLayoutSet.Builder.setNumberRowEnabled` →
`KeyboardParams.mNumberRowEnabled` → `KeyboardId.mNumberRowEnabled`.
On `main`, `SettingsValues.mShowsNumberRow` is **hardcoded** to
`true`:

```java
// SettingsValues.java:191
mShowsNumberRow = true;  // pref is never read
```

The `PREF_SHOW_NUMBER_ROW` constant exists in `Settings.java` and
its default in `Defaults.kt` is `true`, but nothing reads the
value. It's dead.

`PREF_SHOW_NUMBER_ROW_IN_SYMBOLS` *is* read into
`SettingsValues.mShowsNumberRowInSymbols`, but **nothing
downstream consumes the value**. `KeyboardId.mNumberRowInSymbols`
is set, propagated, stored — and never inspected for any
rendering decision. Also dead.

The 3-row branch already exists too, at
`KeyboardParser.kt:274-278`:

```kotlin
private fun addNumberRowOrPopupKeys(baseKeys, numberRow) {
    if (!params.mId.mNumberRowEnabled && params.mId.isAlphabetKeyboard && !hasBuiltInNumbers()) {
        baseKeys.first().forEachIndexed { i, keyData ->
            keyData.popup.numberLabel = numberRow.getOrNull(i)?.label
        }
    }
}
```

When the number row is *not* prepended (`!mNumberRowEnabled`),
this sets `popup.numberLabel` on each top-row key from the
locale's number-row asset. That `numberLabel` is read by
`PopupKeysUtils.getHintLabel` via the `POPUP_KEYS_NUMBER` source
and shows as the grey digit hint above each top-row key.

In other words: **the parser already does the right thing for
both 3-row and 4-row layouts**. The only thing keeping it stuck
in "prepend a number row at runtime" mode is the
`mNumberRowEnabled` guard. Flip that guard to look at the parsed
row count, bake the number row into every layout file, and the
whole runtime-prepend path becomes dead code.

`PREF_LOCALIZED_NUMBER_ROW` (live) controls a digit-swap inside
`getNumberRow()` — when on, Western `1234567890` is replaced by
the locale's localised digits (Persian, Arabic, Devanagari, …).

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
    rendered: 4 rows
              =                               rendered: 4 rows
    file content + 1 row                              =
                                              file content


    Fork qwerty → custom:                     Fork qwerty → custom:
    +--------------------+                    +--------------------+
    │ q w e r t y u i o p│                    │ 1 2 3 4 5 6 7 8 9 0│
    │ a s d f g h j k l  │   ← User edits 3   │ q w e r t y u i o p│  ← User edits 4
    │ z x c v b n m      │     rows, sees 4   │ a s d f g h j k l  │    rows, sees 4
    +--------------------+     rendered.      │ z x c v b n m      │    rendered.
                                              +--------------------+
                                                       WYSIWYG.

    To hide the number row?                   Save a 3-row custom file:
    Need a global toggle that                 +--------------------+
    isn't wired to any UI.                    │ q w e r t y u i o p│
                                              │ a s d f g h j k l  │
                                              │ z x c v b n m      │
                                              +--------------------+
                                              Parser renders 3 rows
                                              and adds digit hints
                                              (popup.numberLabel) on
                                              the top alphabet row
                                              automatically.
```

The user-facing model becomes:

| Layout file row count | Renders as | Top-row hints |
| --- | --- | --- |
| **4** (number row baked in as row 1) | 4 rows; row 1 is the number row | First popup of each digit in the file (just like every other key) |
| **3** (no number row) | 3 rows; alphabet only | Digit hints auto-appear above the top alphabet row, sourced from the locale's `number_row/` asset (`POPUP_KEYS_NUMBER`). If the user authored their own popups on top-row keys, those take precedence per `params.mPopupKeyLabelSources` priority. |

To opt out of the number row, the user either picks a 3-row
variant from the *Main layout* dropdown, or authors a 3-row
custom layout (fork a 4-row built-in, delete the first row in
the editor, save).

---

## 4. Implementation: four PRs

Each PR is independently shippable and rolls back independently.

### 4.1 PR 1 — Bake the number row into every built-in layout

**Scope:**

- Edit every file under `app/src/main/assets/layouts/main/*.{txt,json}`
  (76 files total: 48 `.txt`, 28 `.json`) to add a number row as
  the new first row.
- Edit `app/src/main/assets/layouts/symbols/symbols.txt`,
  `symbols/symbols_arabic.txt`, and
  `more_symbols/symbols_shifted.txt` to add a number row as the
  new first row of each.
- Add a JVM test that asserts every alphabet/symbol layout file
  has **3 or 4** rows (so future changes can't accidentally ship
  a 1-row or 5-row file).
- Update `app/src/main/assets/layouts/AGENTS.md` to document the
  new "row 1 is the number row" convention.

**Digit set per locale.** The shipped per-locale digit choice
must match what users see today (which is `getNumberRow()` with
`PREF_LOCALIZED_NUMBER_ROW = true` by default). The mapping
already lives in
`keyboard/internal/keyboard_parser/LocaleKeyboardInfos.kt` →
`localizedNumberKeys`. For each layout file under `main/`:

- If the file is for a Latin/Cyrillic/Greek/Hebrew/Armenian
  locale → use Western digits `1 2 3 4 5 6 7 8 9 0`.
- If the file is for a script with localised digits — Arabic,
  Persian, Bengali, Devanagari (Hindi), Thai, Khmer, Lao,
  Myanmar, etc. — use the locale-appropriate digit characters
  (`٠١٢٣٤٥٦٧٨٩`, `০১২৩৪৫৬৭৮৯`, `०१२३४५६७८९`, `๐๑๒๓๔๕๖๗๘๙`, etc.).

**Number-row popups (hints).** Today the number-row asset
`assets/layouts/number_row/number_row.json` declares popups for
each digit (e.g. the `1` key has `!` as its hint, `2` has `@`,
etc.). Carry those popups verbatim into each baked-in number row.
For simple-text format that's:

```
1 ! ¹ ½ ⅓ ⅛
2 @ ² ⅔
3 # ³ ¾ ⅜
…
```

The first token after `1` (`!`) becomes the hint; the rest stay
as long-press popup options.

For JSON layouts, generate equivalent JSON. Either:

```json
{ "label": "1", "popup": { "main": { "label": "!" }, "relevant": [...] } }
```

…or fall back to the simple-text format throughout (cheaper and
the parser handles both). A small generator script under `tools/`
makes the 76 edits mechanical and reviewable. The script reads
`number_row.json` and `LocaleKeyboardInfos.localizedNumberKeys`
to compute the per-locale top row, then prepends it to each
existing file (with the appropriate blank-line separator for
simple-text format, or as a new top-level array element for JSON).

**Symbols/More-symbols.** Today `symbols.txt` and
`symbols_shifted.txt` have 3 rows. The runtime prepend adds the
same number row. After this PR, both files have 4 rows starting
with `1 2 3 4 5 6 7 8 9 0`. `symbols_arabic.txt` similarly gets
its baked number row using Eastern Arabic-Indic digits.

**Acceptance:**

- Diff: ~80 asset files touched.
- Build and run: every alphabet keyboard still shows the number
  row at the top, with the same digits and hints as before.
- JVM test passes: every `main/*.{txt,json}` and
  `{symbols,more_symbols}/*.{txt,json}` has 3 or 4 rows.

This PR is the *only* one where the asset-level change is large
but the code change is zero (except for the new test). Land it
first.

### 4.2 PR 2 — Parser cleanup: layout file is the source of truth

**Scope:**

This PR makes `KeyboardParser` stop prepending the number row,
flip the 3-row digit-hint trigger to the layout's actual row
count, and deletes the dead toggle plumbing.

**Files touched:**

- `keyboard/internal/keyboard_parser/KeyboardParser.kt`
- `keyboard/internal/keyboard_parser/LayoutParser.kt` (small `+`
  extras-index fix, see below)
- `keyboard/KeyboardLayoutSet.java`
- `keyboard/KeyboardSwitcher.java`
- `keyboard/KeyboardId.java`
- `keyboard/internal/KeyboardBuilder.kt`
- `keyboard/emoji/EmojiLayoutParams.kt`
- `keyboard/clipboard/ClipboardLayoutParams.kt`
- `latin/settings/SettingsValues.java`
- `latin/settings/Settings.java`
- `latin/settings/Defaults.kt`
- `latin/utils/ResourceUtils.java` (if it consults `mShowsNumberRow`)
- `latin/utils/SubtypeUtils.kt` (if `hasLocalizedNumberRow` needs a description tweak)
- `settings/screens/SubtypeScreen.kt` (description string update)
- `keyboard/internal/keyboard_parser/AGENTS.md`
- `latin/settings/AGENTS.md`

**Concrete edits:**

1. **`KeyboardParser.kt`** — replace the `mNumberRowEnabled`
   guards.
   - Remove the `baseKeys.add(0, numberRow.mapTo(...))`
     injection at lines 109-112. The number row now lives in the
     parsed file.
   - In `addNumberRowOrPopupKeys` (lines 274-278), flip the
     guard from `!params.mId.mNumberRowEnabled` to
     `baseKeys.size == 3`. Concretely:
     ```kotlin
     private fun addNumberRowOrPopupKeys(baseKeys, numberRow) {
         if (params.mId.isAlphabetKeyboard
                 && baseKeys.size == 3
                 && !hasBuiltInNumbers()) {
             baseKeys.first().forEachIndexed { i, keyData ->
                 keyData.popup.numberLabel = numberRow.getOrNull(i)?.label
             }
         }
     }
     ```
     The function continues to use `getNumberRow()` (which
     respects `PREF_LOCALIZED_NUMBER_ROW`) so 3-row layouts get
     locale-appropriate digit hints automatically.
   - In `defaultLabelFlags` (line 41-46), change the
     symbol-layer hint-flag carve-out so user-edited symbol
     layouts show authored popups as hints:
     ```kotlin
     private val defaultLabelFlags = when {
         params.mId.isAlphabetKeyboard -> params.mLocaleKeyboardInfos.labelFlags
         params.mId.isAlphaOrSymbolKeyboard
             && LayoutUtilsCustom.isCustomLayout(layoutNameFor(params.mId)) -> 0
         params.mId.isAlphaOrSymbolKeyboard -> Key.LABEL_FLAGS_DISABLE_HINT_LABEL
         else -> 0
     }
     ```
     `layoutNameFor(params.mId)` reads the active subtype's
     layout name for the current `elementId` (MAIN, SYMBOLS,
     MORE_SYMBOLS). Built-in symbols layouts still hide the
     hints by default — their popups (`≠ ≈ ≡`) aren't meant as
     hints. User-edited symbol layouts show them because the
     user explicitly authored them.

2. **`LayoutParser.kt`** — fix the `+` extras index for 4-row
   layouts. The current code at line 128:
   ```kotlin
   simpleKeyData.mapIndexedTo(...) { i, row ->
       val newRow = row.toMutableList()
       if (params.mId.isAlphabetKeyboard && layoutName.endsWith("+"))
           params.mLocaleKeyboardInfos.getExtraKeys(i + 1)?.let { newRow.addAll(it) }
       newRow
   }
   ```
   asks `LocaleKeyboardInfos.getExtraKeys(i+1)` for each row.
   Today a 3-row file gives `i = 0,1,2` → extras for alphabet
   rows 1, 2, 3. After PR 1 a 4-row file gives `i = 0,1,2,3` →
   asks for extras at row 1 for the number row (wrong) and at
   rows 2-4 for the alphabet (also wrong).
   
   Fix:
   ```kotlin
   val firstAlphabetRowIndex = if (simpleKeyData.size == 4) 1 else 0
   simpleKeyData.mapIndexedTo(...) { i, row ->
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
   Both row counts work correctly: 3-row → extras at 1,2,3;
   4-row → extras at 1,2,3 (for the alphabet rows, skipping the
   number row).

3. **Delete dead toggle plumbing.** None of the following are
   actually read by any rendering path on `main`. Compiler will
   guide every deletion.
   - `Settings.java`: remove
     `PREF_SHOW_NUMBER_ROW`,
     `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS` constants.
   - `Defaults.kt`: remove
     `PREF_SHOW_NUMBER_ROW`,
     `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS` defaults.
   - `SettingsValues.java`: remove
     `mShowsNumberRow`,
     `mShowsNumberRowInSymbols`. (Anything that still references
     `mShowsNumberRow` in `KeyboardSwitcher`, builders, height
     math, etc., switches to a constant `true` literal or drops
     the conditional.)
   - `KeyboardLayoutSet.Builder`: remove
     `setNumberRowEnabled`,
     `setNumberRowInSymbolsEnabled`,
     and the corresponding `mNumberRowEnabled`,
     `mNumberRowInSymbols` fields.
   - `KeyboardSwitcher.java`: remove the four `.setNumberRow…`
     calls.
   - `KeyboardId.java`: remove
     `mNumberRowEnabled` and `mNumberRowInSymbols` fields,
     remove them from constructor, `equals`, `hashCode`,
     `toString`. `KeyboardId` instances will collide more
     readily — a small perf win.
   - `KeyboardBuilder.kt`, `EmojiLayoutParams.kt`,
     `ClipboardLayoutParams.kt`: any height math that branches
     on `mShowsNumberRow` (e.g. *"reserve space for 5 rows if
     emoji bottom + number row, else 4"*) becomes
     unconditional — every alphabet/symbol keyboard now reserves
     space for 4 rows (or 3 if a 3-row custom layout is
     selected, handled by the existing `heightRescale = 4f /
     keysInRows.size` path already in
     `KeyboardParser.parseLayout`).
   - `ResourceUtils.java`: same — drop any branches on
     `mShowsNumberRow`.

4. **Narrow `PREF_LOCALIZED_NUMBER_ROW`'s description.** The
   pref stays — it's still consulted inside `getNumberRow()` for
   the 3-row digit-hint fallback. But its scope narrows: 4-row
   layouts now bake their digits in directly, so the toggle has
   no effect on those. Update:
   - The summary string in `SubtypeScreen.kt:212-235`'s
     `Switch` UI to *"Show localised digits as number hints
     when the keyboard has no number row"*.
   - Any matching string in `res/values/strings.xml`.

5. **AGENTS.md updates.** In
   `keyboard/internal/keyboard_parser/AGENTS.md` and
   `latin/settings/AGENTS.md`, add a note that the layout file's
   row count is the single source of truth for whether a number
   row is present, and that `PREF_LOCALIZED_NUMBER_ROW` only
   affects the 3-row digit-hint fallback now.

**Acceptance:**

- App builds with the compiler having been guided through every
  removed field.
- Every built-in alphabet keyboard renders the same number row
  as before PR 1.
- Saving a 3-row custom layout via the existing edit flow on
  `SubtypeScreen` renders 3 rows with digit hints on the top.
- Saving a 4-row custom layout (which is what "Edit a copy" of a
  built-in will produce in PR 3) renders 4 rows where the top
  row keys carry their digit labels directly.
- A built-in `qwerty+` subtype still appends the locale's extra
  keys to alphabet rows 1, 2, 3 (not to the number row).

### 4.3 PR 3 — UI parity for non-MAIN slots and a discoverable "Edit a copy"

**Scope:**

Extract the existing `MainLayoutRow` from `SubtypeScreen.kt`
(lines 401-504) into a reusable `LayoutSlotEditor` composable
and wire it for every `LayoutType` slot, then add a
"pencil-with-plus" icon next to built-in entries for the
copy-and-edit flow.

**Files touched:**

- `settings/screens/SubtypeScreen.kt`
- `settings/dialogs/LayoutEditDialog.kt` (small caption addition)
- `settings/screens/LanguageScreen.kt` (one hint string for
  empty-search state)
- `res/values/strings.xml`

**Concrete edits:**

1. **Extract `LayoutSlotEditor`** from `SubtypeScreen.kt`'s
   `MainLayoutRow` composable. Signature:
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
   The composable is the existing `MainLayoutRow` body minus the
   MAIN-specific bits. Per-slot differences live in two lookups:
   - `MAIN`: filter custom files by locale/script
     (`LayoutUtilsCustom.getLayoutFiles(MAIN, ctx, locale)`)
     and built-in files by locale
     (`LayoutUtils.getAvailableLayouts(MAIN, ctx, locale)`).
   - Every other slot: no locale filter.

2. **Wire it for every slot in `SubtypeScreen`.** Replace the
   current MAIN-row composable call with the new
   `LayoutSlotEditor` call. Replace the inline per-slot
   `DropDownField` blocks at lines 241-289 with
   `LayoutSlotEditor` calls. The result: every slot gets
   Add / Edit / Delete / Load-from-file affordances, with no
   duplicated logic.

3. **Add "Edit a copy" to the dropdown row.** When the currently
   highlighted entry is a built-in (i.e.
   `!LayoutUtilsCustom.isCustomLayout(name)`), render a
   *pencil-with-plus* icon button that:
   - Reads the built-in file's content via
     `LayoutUtils.getContentWithPlus(name, locale, ctx)` (for
     MAIN) or `LayoutUtils.getContent(slotType, name, ctx)` (for
     non-MAIN).
   - Opens `LayoutEditDialog` with
     `initialLayoutName = "<name> (copy)"`,
     `startContent = <that content>`,
     `isNameValid = { it !in customLayouts }`.
   - On save, the existing `LayoutEditDialog` save flow writes
     the new file via
     `LayoutUtilsCustom.getLayoutFile(...).writeText(...)` and
     calls `setCurrentSubtype(currentSubtype.withLayout(slotType, newName))`.

4. **`LayoutEditDialog` caption for `+` forks.** When the
   built-in layout name ends with `+`, add a one-line
   `supportingText` to the dialog:
   *"Locale-specific extra keys are frozen into this copy. They
   won't change if you later use this layout in a different
   language."* This addresses the implicit foot-gun where
   `LayoutUtils.getContentWithPlus` materialises the locale
   extras into the file body.

5. **`LanguageScreen` empty-search hint.** When the search box
   yields no results, render a single muted line beneath the
   search field:
   *"To customise a layout, tap a language above, then use the
   **+** on any layout dropdown."* No new wizard. Discovery
   lives on the subtype detail page where the locale is already
   chosen.

6. **Strings.** All new user-visible text lives in
   `res/values/strings.xml`. Translators will need an update;
   that's standard.

**Acceptance:**

- Every layout slot on the subtype detail screen has the same
  set of controls: dropdown, `+` add, pencil edit, bin delete,
  load-from-file.
- Tapping the "Edit a copy" icon on any built-in opens
  `LayoutEditDialog` pre-filled with the built-in's content (4
  rows for the post-PR-1 layouts).
- Saving the copy creates a new custom layout, selects it in the
  dropdown, and the keyboard renders the new layout immediately.
- For a `qwerty+` subtype, the copy dialog shows the caption
  about frozen extras.
- Searching for a non-existent language on `LanguageScreen`
  shows the discoverability hint.

### 4.4 PR 4 — Rebuild the canonical APK

Run `./tools/build-dist-apk.sh` and commit the resulting
`dist/HeliBoard.apk`. Per the existing repo convention
(`AGENTS.md` says *"Build the canonical installable artifact with
./tools/build-dist-apk.sh"*).

---

## 5. Edge cases and rationale

### 5.1 3-row layouts remain first-class

The parser supports both 3-row and 4-row MAIN files after PR 2.
A user who wants a 3-row keyboard:

- Picks an existing 3-row variant from the *Main layout*
  dropdown (none ship today; we can add `qwerty_compact` etc. in
  a follow-up if there's demand), **or**
- Forks a 4-row built-in with "Edit a copy", deletes the first
  row in the editor, saves. The parser renders 3 rows with
  digit hints automatically.

The 3-row case has a useful property: explicit user popups on
top-row keys win over the auto-digit hint. So
`q ! w @ e #` in a custom 3-row layout shows `!`, `@`, `#` as
hints instead of `1`, `2`, `3`. Per the
`POPUP_KEYS_LABEL_DEFAULT` source-priority,
`POPUP_KEYS_NUMBER` is the *fallback* (only consulted if no
in-file popups exist for that key).

This makes the system more expressive than a binary "show/hide
number row" toggle: each top-row key can carry the hint the user
actually wants.

### 5.2 Localised digits

For 4-row layouts, the digits are baked into the file directly
per locale (PR 1). For 3-row layouts, digit hints come from
`getNumberRow()`, which honours `PREF_LOCALIZED_NUMBER_ROW` and
the per-subtype `LOCALIZED_NUMBER_ROW` extra value
(`SubtypeScreen.kt:212-235`).

This means localised-digit logic ends up in two places after PR
2 — the per-locale layout file and the runtime swap for hints —
which is slightly more surface area than I'd like. The simpler
alternative is to drop `PREF_LOCALIZED_NUMBER_ROW` entirely and
let 3-row users fork a custom layout if they want digit hints in
non-Western scripts. The recommendation is **keep the pref**,
because the bilingual-user case (e.g. Farsi keyboard with `1234…`
hints, or English keyboard with `٠١٢٣…` hints) is real and the
runtime swap is one line in `getNumberRow()`.

### 5.3 The `+` (extra keys) mechanism

Layouts named with a trailing `+` in `method.xml` (e.g.
`qwerty+` for Catalan) get locale-specific extra keys appended
at runtime via `LocaleKeyboardInfos.getExtraKeys`. After PR 1
the layout file has 4 rows (number + alphabet × 3), but the
extras only apply to alphabet rows. The `+` extras-index fix in
PR 2 (see 4.2 item 2) shifts the index by 1 when the parsed
layout has 4 rows so extras still land on the right rows.

A consequence: the `+` mechanism continues to share one
`qwerty.txt` across many locales, with locale-specific extras
appended dynamically. This stays exactly the same — we just
fix the off-by-one introduced by the number-row baked into
row 0.

### 5.4 Forking from a `+` layout

When the user taps "Edit a copy" on a `qwerty+` subtype, the
new custom file gets *materialised* extras for the **current
locale** (via `LayoutUtils.getContentWithPlus`). The fork is no
longer locale-parameterised. If the user later switches to a
different locale and picks the same custom layout, the extras
are wrong (they're for the original locale). The
`LayoutEditDialog` caption added in 4.3 item 4 warns the user
about this. Documented behaviour, not a bug.

### 5.5 Subtype name override

`SubtypeUtilsAdditional.createAdditionalSubtype` already calls
`builder.setSubtypeNameOverride(LayoutUtilsCustom.getDisplayName(mainLayoutName))`
on Android 14+ when MAIN is a custom layout. So a *Languages &
Layouts* row using a custom *PS-mod* layout under `en` shows
up as *English (PS-mod)* automatically. No new code needed.

### 5.6 Dictionary availability for new subtypes

The existing `dictsAvailable(locale, ctx)` check in
`LanguageScreen.kt` shows `MissingDictionaryDialog` when a
user enables a subtype for a locale that has no bundled
dictionary. This continues to work; nothing in this plan
changes the dictionary system.

### 5.7 Cache invalidation

Existing `LayoutUtilsCustom.onLayoutFileChanged()` +
`KeyboardSwitcher.setThemeNeedsReload()` are already called by
`LayoutEditDialog.onConfirmed` and
`LayoutUtilsCustom.deleteLayout`. Every code path that creates,
renames, or deletes a custom layout already triggers
invalidation. No new hooks needed.

### 5.8 Validation

`LayoutUtilsCustom.checkLayout` already validates row counts
(>=1, <=8), keys per row (<=20), label/popup string lengths.
After PR 1, add two soft checks (warning, not rejection — power
users may have legitimate reasons):

- **MAIN / SYMBOLS / MORE_SYMBOLS prefer 3 or 4 rows.** A
  5-row file renders with `heightRescale = 4f/5` and looks
  fine, just smaller. Show a yellow note: *"This layout has 5
  rows. Most keyboards have 3 or 4. Continue anyway?"*.
- **Top-row popup-as-hint length ≤ 5 visual chars.** Hint
  labels render single-line and clip beyond ~5 chars (see
  `.cursor/skills/key-hint-sizing/SKILL.md`). Show a yellow
  note when a top-row popup exceeds this. Non-Latin scripts
  may have wider glyphs that look fine at 1-2 chars, so don't
  reject — warn.

Both warnings appear inline in `LayoutEditDialog`.

---

## 6. Test coverage

JVM tests under `app/src/test/`. No Robolectric needed for any
of these.

**Asset shape (PR 1):**

- Every file under `assets/layouts/main/*.{txt,json}` parses
  successfully and has exactly 3 or 4 rows.
- Every file under `assets/layouts/{symbols,more_symbols}/*.{txt,json}`
  parses successfully and has exactly 3 or 4 rows.
- For each locale tag in `method.xml` that uses a non-Western
  digit set, the baked number-row digits in the locale's MAIN
  file match `LocaleKeyboardInfos.localizedNumberKeys` for that
  locale.

**Parser (PR 2):**

- A 4-row MAIN custom layout renders 4 rows where the top row's
  `KeyParams.mLabel` carries digits literally (no
  `popup.numberLabel` injection).
- A 3-row MAIN custom layout renders 3 rows where the parser
  sets `popup.numberLabel` on each top-row key via the new
  `baseKeys.size == 3` trigger in `addNumberRowOrPopupKeys`.
- A 3-row MAIN custom layout where the top row keys have
  explicit popups (`q !`, `w @`) preserves the user's popups as
  hints (the `POPUP_KEYS_LAYOUT` source wins over
  `POPUP_KEYS_NUMBER` per `POPUP_KEYS_LABEL_DEFAULT`).
- A built-in `qwerty+` subtype for Catalan still appends `ç` to
  alphabet row 3, not to the number row.
- The deletion of `PREF_SHOW_NUMBER_ROW`,
  `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS`, and their downstream
  fields doesn't change any observable rendering for a stock
  English subtype on a 4-row built-in layout.
- A built-in symbol layout (e.g. `symbols.txt`) keeps
  `LABEL_FLAGS_DISABLE_HINT_LABEL` on its keys.
- A user-edited custom symbol layout has the hint flag cleared
  via the `isCustomLayout` branch — its first popup shows as a
  hint above each key.

**UI (PR 3):**

- A `LayoutSlotEditor` instance for a non-MAIN slot has Add /
  Edit / Delete buttons rendered.
- Tapping "Edit a copy" on a built-in opens the edit dialog
  with the expected content.
- Saving a copy of `qwerty` produces a new
  `custom.Latn.<base36>.qwerty (copy).` file in `<filesDir>/layouts/main/`.
- A `qwerty+` fork shows the locale-extras-frozen caption.

---

## 7. Out of scope and follow-ups

The following are *not* part of this plan but are reasonable
follow-ups once the core merge ships:

- **Compact 3-row built-in variants.** After PR 1 every built-in
  is 4 rows. Adding e.g. `qwerty_compact` (3 rows, no number
  row) as a shipped variant would give users a no-fork path to
  the no-number-row layout. One asset file per script family.
- **Functional-row editing.** The plan extends per-slot Add /
  Edit / Delete to non-MAIN slots, including `FUNCTIONAL`. The
  functional row's interaction with shift, space, etc. is more
  delicate; ship the parity work first and iterate on
  functional-row UX once we see what users do.
- **Export / import all custom layouts.** A single button that
  zips `<filesDir>/layouts/` for backup. Trivial; not in PR 3.
- **Toolbar button to cycle alphabets within a locale.** The
  globe key cycles enabled subtypes; a dedicated "next variant
  for this language" button would be a small follow-up.
- **A separate `LABEL_FLAGS_HAS_HINT_LABEL_EXPLICIT` mode** for
  power users who want hint visibility controlled at the
  layout-file level rather than the subtype level. Today the
  per-subtype `Hint source` setting in
  `SubtypeScreen.kt:171-192` is the only fine-grained control.

---

## 8. Open questions

- **Should we keep `PREF_LOCALIZED_NUMBER_ROW`?** Section 5.2
  recommends keep with a narrowed scope. Alternative: drop and
  have 3-row users fork a custom layout when they want non-Western
  digit hints. Drop is simpler; keep is friendlier to bilingual
  users. Default: keep.
- **Should we delete `LayoutUtils.getContentWithPlus`'s extras-
  materialisation when forking a `+` layout?** Section 5.4
  documents the foot-gun. The alternative is to fork without
  extras (a clean `qwerty.txt` content) and let the user re-add
  what they need. Friendlier for "I want qwerty without the
  Catalan-specific keys" but worse for "I want qwerty exactly
  as I see it, only with the bottom row tweaked". Default:
  keep the current behaviour, add the caption from 4.3 item 4.
- **Should built-in symbols keep `LABEL_FLAGS_DISABLE_HINT_LABEL`?**
  Section 4.2 item 1c says yes (the built-in symbol popups
  `≠ ≈ ≡` aren't really hints). But after the merge, a curious
  user might wonder why their *custom* symbol layout shows hints
  and the built-in doesn't. Document this in
  `keyboard/internal/keyboard_parser/AGENTS.md`.

---

## 9. File reference

Files this plan touches, grouped by PR:

**PR 1 (assets):**

- `app/src/main/assets/layouts/main/*.{txt,json}` — 76 files
- `app/src/main/assets/layouts/symbols/*.txt` — 2 files
- `app/src/main/assets/layouts/more_symbols/*.txt` — 1 file
- `app/src/main/assets/layouts/AGENTS.md`
- `tools/<new>.{py,sh,kt}` — optional generator script
- `app/src/test/.../LayoutAssetsTest.kt` — new test

**PR 2 (parser cleanup):**

- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt`
- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/LayoutParser.kt`
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
- `app/src/main/res/values/strings.xml` — description text for the localised-number-row toggle
- `app/src/test/.../KeyboardParserTest.kt` — new tests

**PR 3 (UI):**

- `app/src/main/java/helium314/keyboard/settings/screens/SubtypeScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/LanguageScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/dialogs/LayoutEditDialog.kt`
- `app/src/main/java/helium314/keyboard/settings/screens/AGENTS.md`
- `app/src/main/res/values/strings.xml` — new strings for
  "Edit a copy", caption, empty-search hint
- `app/src/test/.../LayoutSlotEditorTest.kt` — new tests

**PR 4 (release):**

- `dist/HeliBoard.apk` — rebuilt artifact

**Key existing files the next agent should read first:**

1. `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt` — parser entry point, the file PR 2 mostly edits.
2. `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/LayoutParser.kt` — asset loader, parses simple-text and JSON.
3. `app/src/main/java/helium314/keyboard/latin/utils/LayoutUtilsCustom.kt` — custom-layout file conventions.
4. `app/src/main/java/helium314/keyboard/settings/screens/SubtypeScreen.kt` — the subtype detail UI, contains `MainLayoutRow` and the per-slot dropdowns.
5. `app/src/main/java/helium314/keyboard/settings/dialogs/LayoutEditDialog.kt` — the editor.
6. `app/src/main/java/helium314/keyboard/latin/utils/SubtypeSettings.kt` — subtype state.
7. `app/src/main/java/helium314/keyboard/latin/utils/PopupKeysUtils.kt` — hint-label derivation.
8. `app/src/main/assets/layouts/AGENTS.md` and `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/AGENTS.md` — pre-existing conventions.
