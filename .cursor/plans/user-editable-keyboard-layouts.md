# HeliBoard user-editable keyboard layouts — implementation plan

## 0. The user goal in one paragraph

A HeliBoard user should be able to **create a new keyboard layout
of their own** by starting from an existing locale's layout (e.g.
English/QWERTY, Russian, Bengali, …), then **edit the key
characters, the long-press popups, the small grey hint label
above each key, *and* the number row** as one coherent unit. The
edited layout should appear in the *Languages & Layouts → [language]*
screen alongside the built-ins, can be selected per subtype, and
the user can keep multiple custom variants per locale (the globe
key cycles through them as today).

What "edit" means concretely, after this plan ships:

- Every layout slot the user can pick (`MAIN`, `SYMBOLS`,
  `MORE_SYMBOLS`, `FUNCTIONAL`, `NUMBER`, `NUMBER_ROW`,
  `NUMPAD`, `NUMPAD_LANDSCAPE`, `PHONE`, `PHONE_SYMBOLS`,
  `EMOJI_BOTTOM`, `CLIPBOARD_BOTTOM`) has the same **Add /
  Edit / Delete / Load-from-file / Edit-a-copy** affordances.
  Today only `MAIN` has the full set.
- "Edit a copy" is one tap: pick any built-in (e.g. `qwerty`),
  hit a fork icon, and the editor opens pre-filled with that
  built-in's exact rendered content. Save → a new
  `custom.<scope>.<base36>.<name>.` file is written and the
  active subtype switches to it.
- **The number row is part of that pre-filled content.** Today
  the user edits 3 rows and the keyboard renders 4 because the
  parser silently prepends a number-row asset at render time.
  This plan bakes the number row into every built-in layout
  file so what the user sees in the editor is what the keyboard
  renders. A 4-row file renders 4 rows; deleting the first row
  in the editor gives a clean 3-row layout with digit hints
  inferred above the alphabet — no hidden plumbing.
- Hints are not a separate field; the *first popup* of each key
  is already what renders as the small grey hint label. So
  editing the hint is the same gesture as editing the
  long-press popup — one source of truth.

This file describes a four-PR sequence that delivers that goal,
verified against `main` at the time of writing (every line number
and code snippet below was checked in the current tree).

---

## Table of contents

1. The user goal in one paragraph (above)
2. Architecture on `main` today
3. The change in one diagram
4. Implementation: four PRs
5. Edge cases and rationale
6. Test coverage
7. Out of scope and follow-ups
8. Open questions
9. File reference

---

## 2. Architecture on `main` today

### 2.1 Layout assets (built-in)

Every layout the app can render lives in
`app/src/main/assets/layouts/<slot>/`. Verified counts on `main`:

```
assets/layouts/
├── main/              76 files (48 .txt + 28 .json)
├── symbols/           symbols.txt, symbols_arabic.txt
├── more_symbols/      symbols_shifted.txt
├── number_row/        number_row.json, number_row_basic.txt
├── functional/        functional_keys.json, *_tablet.json, *_khipro.json
├── number/, numpad/, numpad_landscape/
├── phone/, phone_symbols/
└── emoji_bottom/, clipboard_bottom/
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
`KeyboardParser` / `KeyboardBuilder` as the same `KeyData` model.
Existing layouts mix the two formats freely; we don't need to
convert one to the other.

**Important: not every main layout is 3 rows today.** A spot
audit found 12 of 76 main layouts already have 4 or 5 rows
(number row or extra rows baked in):

| File | Rows |
|------|------|
| `armenian_phonetic.txt` | 4 |
| `chuvash.txt` | 4 |
| `dvorak.json` | 4 |
| `halmak.txt` | 4 |
| `hungarian_extended_qwertz.txt` | 4 |
| `kannada_extended.txt` | **5** |
| `khmer.json` | 4 |
| `lao.json` | 4 |
| `mansi_north.txt` | 4 |
| `mari.txt` | 4 |
| `pcqwerty.json` | 4 |
| `thai.json` | 4 |

`KeyboardParser.hasBuiltInNumbers()` (lines 318–322) already
special-cases some of these so the parser does not double-prepend
a number row. PR 1 must therefore *skip* files that already have
4+ rows (otherwise we'd end up with 5-row qwerty for any layout
that already includes its own number row).

### 2.2 Custom layouts (user-edited)

The existing custom-layout system stores user-edited layouts as
plain files under the app's device-protected files dir:

```
<filesDir>/layouts/
├── main/             custom.Latn.<base36>.<name>.,   custom.fr-FR.<base36>.<name>.,   …
├── symbols/          custom.<base36>.<name>.                                          …
├── more_symbols/     custom.<base36>.<name>.                                          …
└── functional/, number/, etc.
```

`LayoutUtilsCustom.kt` owns the naming convention
(`LayoutUtilsCustom.kt:137–142`):

- **MAIN, Latin locale**: `custom.Latn.<base36>.<name>.` — the file
  is visible to **every subtype whose locale uses Latin script**.
- **MAIN, non-Latin locale**: `custom.<bcp47>.<base36>.<name>.` —
  the file is visible only to that exact BCP-47 tag (e.g.
  `custom.fr-FR.…` for French — note: a non-Latin example would
  use the locale's actual script tag like `custom.ru-RU.…`).
- **Non-MAIN slot**: `custom.<base36>.<name>.` — the file is
  visible to **every subtype, regardless of locale**.

The file body uses the same simple-text or Floris-JSON format the
built-in layouts use. `LayoutParser.getLayoutFileContent`
(`LayoutParser.kt:101–106`) checks `LayoutUtilsCustom.isCustomLayout(name)`
and reads from the files dir; otherwise it falls through to
`context.assets`.

The `custom.Latn.*` convention already serves as the implicit
"applies to any Latin language" wildcard — anything authored
under that scope appears in every Latin subtype's *Main layout*
dropdown. Non-MAIN custom layouts are already universal because
their lookup ignores locale.

### 2.3 Subtype binding

A *subtype* is the unit the user picks via the globe key. It
carries a `Locale` and an `extraValues` string containing
`KeyboardLayoutSet=MAIN§<layoutName>§SYMBOLS§<layoutName>§…` that
maps each `LayoutType` slot to a specific layout name (built-in
or custom). See `latin/utils/LayoutType.kt:9–11` and
`latin/settings/SettingsSubtype.kt:37, 58–61` (note: the
in-source separators are `Separators.KV` / `Separators.ENTRY`
constants, not the literal `§` glyph — the on-disk format is the
same shape).

Three prefs hold the state (`Settings.java`):

- `PREF_ENABLED_SUBTYPES` (line 161) — the user's enabled subtypes.
- `PREF_ADDITIONAL_SUBTYPES` (line 94) — user-created subtypes
  that differ from the resource ones. Loaded by
  `SubtypeUtilsAdditional.createAdditionalSubtypes`
  (`SubtypeUtilsAdditional.kt:113–117`).
- `PREF_SELECTED_SUBTYPE` (line 162) — currently active subtype
  (cycled by the globe key).

The globe key already cycles every enabled subtype. To get
"multiple alphabet variants per locale" today, the user creates
two additional subtypes for the same locale, each pointing at a
different MAIN layout. The system supports this end-to-end —
this plan does not change subtype plumbing.

### 2.4 Settings UI today

`settings/screens/LanguageScreen.kt` (the *Languages & Layouts*
list) shows every available subtype with a toggle. Tapping a row
navigates to `settings/screens/SubtypeScreen.kt` (the *Subtype
detail* page). This page currently has:

- A **Main layout** dropdown (`MainLayoutRow`, lines **401–504**).
  For `MAIN` this dropdown has:
  - A `+` button to **add** a custom layout (from blank or by
    file-picker import, lines 424–426, 484–502).
  - An inline pencil to **edit** any selected layout (custom or
    built-in). When the selected entry is a built-in, the dialog
    opens pre-filled with `LayoutUtils.getContentWithPlus`
    (lines 437, 464–482) — this is the implicit "edit a copy"
    path, currently quite hidden in the UI.
  - A bin to **delete** the selected custom layout, with a
    confirmation when other subtypes still use it (lines 438–461).
- Per-slot **secondary** dropdowns for every other `LayoutType`
  (`SubtypeScreen.kt:241–289`). These have:
  - The dropdown of built-in + custom files for that slot
    (lines 248–249).
  - A *DefaultButton* "reset to subtype default" that clears the
    per-subtype layout override (lines 254–258).
  - An inline pencil that opens `LayoutEditDialog` for
    **existing custom** entries only (lines 270–286).
  - **No `+` button** to create a new custom layout for that slot.
  - **No bin** for delete.
  - **No "edit a copy"** for built-in entries.
- A **Hint source** order/priority setting (lines **171–192**).
  Per-subtype reorder/toggle of which popup source supplies the
  hint label (matches `params.mPopupKeyLabelSources` priority).
- A **Localised number row** Switch (lines **212–235**) bound to
  the per-subtype `LOCALIZED_NUMBER_ROW` extra value (only
  visible for locales whose `[number_row]` differs from Western
  `1234567890`).

So MAIN is fully managed and non-MAIN slots are stuck halfway:
you can pick a layout and edit an existing custom one, but you
can't create a new one or delete one from this UI, and there is
no one-tap fork of a built-in.

The edit dialog (`settings/dialogs/LayoutEditDialog.kt:45–54`) is
a multi-line text editor that accepts both layout formats. Its
signature is:

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

It validates by re-parsing via `LayoutUtilsCustom.checkLayout`
(line 104), writes the result via `LayoutUtilsCustom.getLayoutFile(...)`
(line 81), calls `SubtypeSettings.onRenameLayout` to keep prefs
consistent (line 79), calls `LayoutUtilsCustom.onLayoutFileChanged()`
(line 82) and `KeyboardSwitcher.getInstance().setThemeNeedsReload()`
(line 85).

### 2.5 Hint labels

The grey hint character drawn above each key is **not** declared
in a dedicated field. It's derived from the key's popup set by
`latin/utils/PopupKeysUtils.kt:63–82 → getHintLabel(...)`, which
iterates `params.mPopupKeyLabelSources` and stops at the first
hit. The default priority order is `POPUP_KEYS_LABEL_DEFAULT`
(`PopupKeysUtils.kt:16–18`):

```
number=true, language_priority=false, layout=true, symbols=true, language=false
```

So in priority order: `POPUP_KEYS_NUMBER` first (when present
and enabled), then `POPUP_KEYS_LAYOUT` ("the first popup
declared by the layout file itself"), then `POPUP_KEYS_SYMBOLS`,
then language sources. (The plan's earlier draft listed only
two sources — the actual default has five.)

In practice this means:

- A simple-text line `a @` (primary `a`, popup `@`) renders as
  *primary `a`, hint `@`*. The popup IS the hint.
- A JSON `{ "label": "a", "popup": { "main": { "label": "@" } } }`
  renders identically.
- A simple-text line `i - –` (primary `i`, popups `-` and `–`)
  renders as *primary `i`, hint `-`, additional popup `–`*. The
  first popup wins.

**A layout author writes a hint by writing a popup.** No separate
`hint` field needs to exist.

### 2.6 The number row, today

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
`main`, `SettingsValues.mShowsNumberRow` is **hardcoded** to
`true` (`SettingsValues.java:191`):

```java
mShowsNumberRow = true;
```

(There is no comment on that line; the field is simply assigned
`true` and the pref `PREF_SHOW_NUMBER_ROW` declared at
`Settings.java:138` with its default at `Defaults.kt:129` is
**never read** anywhere.)

`PREF_SHOW_NUMBER_ROW_IN_SYMBOLS` *is* read into
`SettingsValues.mShowsNumberRowInSymbols` (`SettingsValues.java:192`),
passed through `KeyboardSwitcher.java:157–158, 174–175` and
`KeyboardLayoutSet.Builder` into `KeyboardId.mNumberRowInSymbols`
(`KeyboardId.java:98–99`) — but **nothing downstream reads
`mNumberRowInSymbols`** to make a rendering decision. Also dead.

The 3-row branch already exists too, at
`KeyboardParser.kt:274–278`:

```kotlin
private fun addNumberRowOrPopupKeys(baseKeys, numberRow) {
    if (!params.mId.mNumberRowEnabled && params.mId.isAlphabetKeyboard && !hasBuiltInNumbers()) {
        baseKeys.first().forEachIndexed { index, keyData ->
            keyData.popup.numberLabel = numberRow.getOrNull(index)?.label
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
**The per-locale digit set is not a static Kotlin map.** It is
parsed from `assets/locale_key_texts/<lang>.txt` under the
`[number_row]` section by `LocaleKeyboardInfos.kt`. Example
(`assets/locale_key_texts/fa.txt`):

```
[number_row]
۱ ۲ ۳ ۴ ۵ ۶ ۷ ۸ ۹ ۰
```

These files (81 of them) are the source of truth that PR 1's
generator script must read to bake the correct digit row per
locale.

`LocaleKeyboardInfos` also owns `getExtraKeys(rowIdx)` (lines
109–111), used for the `+` extras mechanism (see 2.7).

### 2.7 The `+` extras mechanism (relevant to PR 2)

Layouts named with a trailing `+` in `method.xml` (e.g.
`qwerty+` for Catalan) get locale-specific extra keys appended
at runtime. Verbatim from `LayoutParser.kt:91–97`:

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

Today (3-row files) the indices are `i = 0,1,2` → extras for
alphabet rows 1, 2, 3. After PR 1's baked number row a 4-row
file gives `i = 0,1,2,3` → asks `getExtraKeys` at row 1 for the
*number* row (wrong) and at rows 2–4 for the alphabet (also
wrong). PR 2 fixes this off-by-one.

Same pattern lives in `LayoutUtils.getContentWithPlus`
(`LayoutUtils.kt:48–49`) for the "edit a copy" pre-fill — PR 2
must fix that too.

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
| **3** (no number row) | 3 rows; alphabet only | Digit hints auto-appear above the top alphabet row, sourced from the locale's `[number_row]` in `assets/locale_key_texts/`. If the user authored their own popups on top-row keys, those take precedence per `POPUP_KEYS_LABEL_DEFAULT` source priority (`POPUP_KEYS_LAYOUT` is later in the default order — see 5.1). |

To opt out of the number row, the user either picks a 3-row
variant from the *Main layout* dropdown, or authors a 3-row
custom layout (fork a 4-row built-in, delete the first row in
the editor, save).

---

## 4. Implementation: four PRs

Each PR is independently shippable and rolls back independently.

### 4.1 PR 1 — Bake the number row into every built-in layout

**Scope:**

- Edit files under `app/src/main/assets/layouts/main/*.{txt,json}`
  to add a number row as the new first row, **only for files
  that currently have 3 rows** (64 of 76 — the 12 listed in 2.1
  are skipped because they already include a number row or
  extras).
- Edit `app/src/main/assets/layouts/symbols/symbols.txt`,
  `symbols/symbols_arabic.txt`, and
  `more_symbols/symbols_shifted.txt` to add a number row as the
  new first row of each.
- Add a JVM test that asserts every alphabet/symbol layout file
  has **3, 4, or 5** rows (so future changes can't accidentally
  ship a 1-row or 6-row file).
- Update `app/src/main/assets/layouts/AGENTS.md` to document the
  new "row 1 is the number row (unless explicitly omitted)"
  convention.

**Digit set per locale.** The shipped per-locale digit choice
must match what users see today (which is `getNumberRow()` with
`PREF_LOCALIZED_NUMBER_ROW = true` by default). The mapping is
**not** a static Kotlin map — it is sourced from per-locale
`[number_row]` sections in `app/src/main/assets/locale_key_texts/<lang>.txt`.
For each layout file under `main/`, the generator script:

1. Reads the layout filename → derives the candidate locale
   (cross-referenced against `app/src/main/res/xml/method.xml`
   subtype entries that point at this layout).
2. Looks up `[number_row]` in that locale's
   `locale_key_texts/<lang>.txt`.
3. Falls back to Western `1 2 3 4 5 6 7 8 9 0` if the locale
   has no `[number_row]` entry.

So Latin/Cyrillic/Greek/Hebrew/Armenian → Western digits;
Arabic, Persian, Bengali, Devanagari, Thai, Khmer, Lao, Myanmar,
… → their native digits per their locale text file.

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
makes the 64+3 edits mechanical and reviewable. The script reads
`number_row.json` and the per-locale `locale_key_texts/<lang>.txt`,
then prepends the row to each existing file (with the appropriate
blank-line separator for simple-text format, or as a new top-level
array element for JSON).

**Symbols / More-symbols.** Today `symbols.txt` and
`symbols_shifted.txt` have 3 rows. The runtime prepend adds the
same number row. After this PR, both files have 4 rows starting
with `1 2 3 4 5 6 7 8 9 0`. `symbols_arabic.txt` similarly gets
its baked number row using Eastern Arabic-Indic digits.

**Acceptance:**

- Diff: ~70 asset files touched.
- Build and run: every alphabet keyboard still shows the number
  row at the top, with the same digits and hints as before.
- JVM test passes: every `main/*.{txt,json}` and
  `{symbols,more_symbols}/*.{txt,json}` has 3, 4, or 5 rows.
- The 12 already-4-or-5-row layouts from 2.1 are unchanged.

This PR is the *only* one where the asset-level change is large
but the code change is zero (except for the new test). Land it
first.

### 4.2 PR 2 — Parser cleanup: layout file is the source of truth

**Scope:**

This PR makes `KeyboardParser` stop prepending the number row,
flip the 3-row digit-hint trigger to the layout's actual row
count, fix the `+` extras-index for 4-row files, and deletes the
dead toggle plumbing.

**Files touched (verified paths and line numbers):**

- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt`
  (lines 37–43, 67, 104–113, 274–278, etc.)
- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/LayoutParser.kt`
  (lines 91–97)
- `app/src/main/java/helium314/keyboard/latin/utils/LayoutUtils.kt`
  (lines 40–58 — `getContentWithPlus`)
- `app/src/main/java/helium314/keyboard/keyboard/KeyboardLayoutSet.java`
  (lines 258–264)
- `app/src/main/java/helium314/keyboard/keyboard/KeyboardSwitcher.java`
  (lines 157–158, 174–175, 824–837)
- `app/src/main/java/helium314/keyboard/keyboard/KeyboardId.java`
  (lines 78–79, 98–99)
- `app/src/main/java/helium314/keyboard/keyboard/internal/KeyboardBuilder.kt`
  (line 116)
- `app/src/main/java/helium314/keyboard/keyboard/emoji/EmojiLayoutParams.kt`
  (line 39)
- `app/src/main/java/helium314/keyboard/keyboard/clipboard/ClipboardLayoutParams.kt`
  (line 43)
- `app/src/main/java/helium314/keyboard/latin/settings/SettingsValues.java`
  (lines 191–192)
- `app/src/main/java/helium314/keyboard/latin/settings/Settings.java`
  (line 138 — `PREF_SHOW_NUMBER_ROW` constant)
- `app/src/main/java/helium314/keyboard/latin/settings/Defaults.kt`
  (line 129)
- `app/src/main/java/helium314/keyboard/latin/utils/ResourceUtils.java`
  (line 84)
- `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/AGENTS.md`
- `app/src/main/java/helium314/keyboard/latin/settings/AGENTS.md`
- `app/src/main/res/values/strings.xml` (localised-number-row toggle copy)
- `app/src/main/java/helium314/keyboard/settings/screens/SubtypeScreen.kt`
  (lines 212–235 — Switch description)

**Concrete edits:**

1. **`KeyboardParser.kt`** — replace the `mNumberRowEnabled`
   guards.
   - Remove the `baseKeys.add(0, numberRow.mapTo(...))`
     injection at lines 109–112. The number row now lives in
     the parsed file.
   - In `addNumberRowOrPopupKeys` (lines 274–278), flip the
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
   - In `defaultLabelFlags` (lines 37–43), change the
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
   layouts. The current code at lines 91–97:
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
   asks `LocaleKeyboardInfos.getExtraKeys(i+1)` for each row.
   Today a 3-row file gives `i = 0,1,2` → extras for alphabet
   rows 1, 2, 3. After PR 1 a 4-row file gives `i = 0,1,2,3` →
   asks for extras at row 1 for the number row (wrong) and at
   rows 2–4 for the alphabet (also wrong).

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
   Apply the same fix to `LayoutUtils.getContentWithPlus`
   (`LayoutUtils.kt:48–49`) so the "edit a copy" pre-fill is
   correct too. Both row counts work correctly: 3-row → extras
   at 1,2,3; 4-row → extras at 1,2,3 (for the alphabet rows,
   skipping the number row).

3. **Delete dead toggle plumbing.** None of the following are
   actually read by any rendering path on `main`. Compiler will
   guide every deletion.
   - `Settings.java:138`: remove `PREF_SHOW_NUMBER_ROW` constant.
   - `Settings.java`: remove `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS`
     constant.
   - `Defaults.kt:129`: remove `PREF_SHOW_NUMBER_ROW` default.
   - `Defaults.kt`: remove `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS`
     default.
   - `SettingsValues.java:191–192`: remove `mShowsNumberRow`
     and `mShowsNumberRowInSymbols`. Replace remaining call
     sites (`KeyboardParser.kt:67`, `EmojiLayoutParams.kt:39`,
     `ClipboardLayoutParams.kt:43`, `ResourceUtils.java:84`,
     `KeyboardBuilder.kt:116`) with a constant `true` literal,
     or drop the conditional entirely — every alphabet/symbol
     keyboard now always has at least one row of number/symbol
     keys at the top.
   - `KeyboardLayoutSet.Builder` (lines 258–264): remove
     `setNumberRowEnabled`, `setNumberRowInSymbolsEnabled`,
     and the corresponding `mNumberRowEnabled`,
     `mNumberRowInSymbols` fields.
   - `KeyboardSwitcher.java:157–158, 174–175`: remove the
     four `.setNumberRow…` calls.
   - `KeyboardId.java:78–79, 98–99`: remove `mNumberRowEnabled`
     and `mNumberRowInSymbols` fields, remove them from
     constructor, `equals`, `hashCode`, `toString`. (`KeyboardId`
     instances will collide more readily — a small perf win.)
   - `KeyboardBuilder.kt:116`: drop the conditional top-padding
     branch on `mShowsNumberRow`; reserve top padding
     unconditionally.
   - `EmojiLayoutParams.kt:39` /
     `ClipboardLayoutParams.kt:43`: row-count math that
     branches on `mShowsNumberRow` becomes unconditional. The
     existing `heightRescale = if (keysInRows.size != 4) 4f /
     keysInRows.size else 1f` (`KeyboardParser.kt:77–78`)
     already handles 3- and 5-row variants gracefully.
   - `ResourceUtils.java:84`: same — drop any branches on
     `mShowsNumberRow`.

4. **Narrow `PREF_LOCALIZED_NUMBER_ROW`'s description.** The
   pref stays — it's still consulted inside `getNumberRow()` for
   the 3-row digit-hint fallback. But its scope narrows: 4-row
   layouts now bake their digits in directly, so the toggle has
   no effect on those. Update:
   - The summary string for the Switch UI in
     `SubtypeScreen.kt:212–235` to *"Show localised digits as
     number hints when the keyboard has no number row"*.
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
- Saving a 4-row custom layout (which is what "Edit a copy" of
  a built-in will produce in PR 3) renders 4 rows where the
  top row keys carry their digit labels directly.
- A built-in `qwerty+` subtype still appends the locale's extra
  keys to alphabet rows 1, 2, 3 (not to the number row).

### 4.3 PR 3 — UI parity for non-MAIN slots and a discoverable "Edit a copy"

**This is the PR that directly delivers the user goal.** PR 1
and PR 2 make the layout files honest; PR 3 puts the editing
affordances in front of the user.

**Scope:**

Extract the existing `MainLayoutRow` from `SubtypeScreen.kt`
(lines 401–504) into a reusable `LayoutSlotEditor` composable
and wire it for every `LayoutType` slot, then add a
"pencil-with-plus" icon next to built-in entries for the
copy-and-edit flow.

**Files touched:**

- `app/src/main/java/helium314/keyboard/settings/screens/SubtypeScreen.kt`
- `app/src/main/java/helium314/keyboard/settings/dialogs/LayoutEditDialog.kt`
  (small caption addition)
- `app/src/main/java/helium314/keyboard/settings/screens/LanguageScreen.kt`
  (one hint string for empty-search state)
- `app/src/main/res/values/strings.xml`

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
   The composable is the existing `MainLayoutRow` body minus
   the MAIN-specific bits. Per-slot differences live in two
   lookups:
   - `MAIN`: filter custom files by locale/script
     (`LayoutUtilsCustom.getLayoutFiles(MAIN, ctx, locale)`)
     and built-in files by locale
     (`LayoutUtils.getAvailableLayouts(MAIN, ctx, locale)`).
   - Every other slot: no locale filter
     (`LayoutUtilsCustom.getLayoutFiles(type, ctx)` and
     `LayoutUtils.getAvailableLayouts(type, ctx)` — matches
     what the existing secondary-slot dropdown already does at
     `SubtypeScreen.kt:246–247`).

2. **Wire it for every slot in `SubtypeScreen`.** Replace the
   current MAIN-row composable call (line 157) with the new
   `LayoutSlotEditor` call. Replace the inline per-slot
   `DropDownField` blocks at lines 241–289 with
   `LayoutSlotEditor` calls. The result: every slot gets
   Add / Edit / Delete / Load-from-file affordances, with no
   duplicated logic.

3. **Add "Edit a copy" to the dropdown row.** When the currently
   highlighted entry is a built-in (i.e.
   `!LayoutUtilsCustom.isCustomLayout(name)`), render a
   *pencil-with-plus* icon button that:
   - Reads the built-in file's content via
     `LayoutUtils.getContentWithPlus(name, locale, ctx)` (for
     MAIN, currently `LayoutUtils.kt:40–58`) or
     `LayoutUtils.getContent(slotType, name, ctx)` (for
     non-MAIN, `LayoutUtils.kt:32–38`).
   - Opens `LayoutEditDialog` with
     `initialLayoutName = "<name> (copy)"`,
     `startContent = <that content>`,
     `isNameValid = { it !in customLayouts }`.
   - On save, the existing `LayoutEditDialog` save flow writes
     the new file via
     `LayoutUtilsCustom.getLayoutFile(...).writeText(...)`
     (`LayoutEditDialog.kt:81`) and the `onEdited` callback
     calls `setCurrentSubtype(currentSubtype.withLayout(slotType, newName))`.

   The "edit a copy" affordance is what the user thinks of as
   "start a new keyboard layout from English/Russian/etc." —
   tapping the icon on a `qwerty` row inside a German subtype
   yields a new German-specific (or Latin-scope) custom layout
   pre-loaded with the qwerty content, ready to edit.

4. **`LayoutEditDialog` caption for `+` forks.** When the
   built-in layout name ends with `+`, add a one-line
   `supportingText` to the dialog:
   *"Locale-specific extra keys are frozen into this copy. They
   won't change if you later use this layout in a different
   language."* This addresses the implicit foot-gun where
   `LayoutUtils.getContentWithPlus` materialises the locale
   extras into the file body.

5. **`LanguageScreen` empty-search hint.** When the search box
   (`LanguageScreen.kt:63–82` via `SearchScreen`) yields no
   results, render a single muted line beneath the search
   field:
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
  load-from-file, and (for built-ins) a fork-and-edit icon.
- Tapping the "Edit a copy" icon on any built-in opens
  `LayoutEditDialog` pre-filled with the built-in's content (4
  rows for the post-PR-1 layouts).
- Saving the copy creates a new custom layout, selects it in
  the dropdown, and the keyboard renders the new layout
  immediately.
- For a `qwerty+` subtype, the copy dialog shows the caption
  about frozen extras.
- Searching for a non-existent language on `LanguageScreen`
  shows the discoverability hint.
- A power user can fork English qwerty, delete the first row,
  reorder a few hint popups, and save — and the result renders
  exactly as authored, with no surprise number row.

### 4.4 PR 4 — Rebuild the canonical APK

Run `./tools/build-dist-apk.sh` and commit the resulting
`dist/HeliBoard.apk`. Per the existing repo convention (root
`AGENTS.md`: *"Build the canonical installable artifact with
./tools/build-dist-apk.sh, which writes dist/HeliBoard.apk."*).

---

## 5. Edge cases and rationale

### 5.1 3-row layouts remain first-class

The parser supports both 3-row and 4-row MAIN files after PR 2.
A user who wants a 3-row keyboard:

- Picks an existing 3-row variant from the *Main layout*
  dropdown (none ship today; we can add `qwerty_compact` etc.
  in a follow-up if there's demand), **or**
- Forks a 4-row built-in with "Edit a copy", deletes the first
  row in the editor, saves. The parser renders 3 rows with
  digit hints automatically.

The 3-row case has a useful property: explicit user popups on
top-row keys win over the auto-digit hint, **subject to the
`POPUP_KEYS_LABEL_DEFAULT` source order**. The default order
(`PopupKeysUtils.kt:16–18`) is:

```
number=true, language_priority=false, layout=true, symbols=true, language=false
```

In words: when both a `popup.numberLabel` *and* an in-file popup
exist, the number hint wins by default. This is the inverse of
what was stated in earlier drafts. **For the 3-row case, the
plan should explicitly disable `POPUP_KEYS_NUMBER` on the top
alphabet row when the file declares an in-file popup for that
key**, so that authored hints don't get silently overridden.
Easiest implementation: only set `keyData.popup.numberLabel`
when the existing `popup.getPopupKeyLabels(params).firstOrNull()`
is null. One extra null-check in `addNumberRowOrPopupKeys`. The
user can still rely on the per-subtype *Hint source* setting
(`SubtypeScreen.kt:171–192`) for fine-grained control.

### 5.2 Localised digits

For 4-row layouts, the digits are baked into the file directly
per locale (PR 1). For 3-row layouts, digit hints come from
`getNumberRow()`, which honours `PREF_LOCALIZED_NUMBER_ROW` and
the per-subtype `LOCALIZED_NUMBER_ROW` extra value
(`SubtypeScreen.kt:212–235`).

This means localised-digit logic ends up in two places after PR
2 — the per-locale layout file and the runtime swap for hints —
which is slightly more surface area than I'd like. The simpler
alternative is to drop `PREF_LOCALIZED_NUMBER_ROW` entirely and
let 3-row users fork a custom layout if they want digit hints
in non-Western scripts. The recommendation is **keep the pref**,
because the bilingual-user case (e.g. Farsi keyboard with
`1234…` hints, or English keyboard with `٠١٢٣…` hints) is real
and the runtime swap is one line in `getNumberRow()`.

### 5.3 The `+` (extra keys) mechanism

Layouts named with a trailing `+` in `method.xml` (e.g.
`qwerty+` for Catalan) get locale-specific extra keys appended
at runtime via `LocaleKeyboardInfos.getExtraKeys`
(`LocaleKeyboardInfos.kt:109–111`; populated from `[extra_keys]`
in `locale_key_texts/<lang>.txt` at lines 137–144). After PR 1
the layout file has 4 rows (number + alphabet × 3), but the
extras only apply to alphabet rows. The `+` extras-index fix in
PR 2 (see 4.2 item 2) shifts the index by 1 when the parsed
layout has 4 rows so extras still land on the right rows.

A consequence: the `+` mechanism continues to share one
`qwerty.txt` across many locales, with locale-specific extras
appended dynamically. This stays exactly the same — we just fix
the off-by-one introduced by the number-row baked into row 0.

### 5.4 Forking from a `+` layout

When the user taps "Edit a copy" on a `qwerty+` subtype, the new
custom file gets *materialised* extras for the **current locale**
(via `LayoutUtils.getContentWithPlus`,
`LayoutUtils.kt:40–58`). The fork is no longer locale-
parameterised. If the user later switches to a different locale
and picks the same custom layout, the extras are wrong (they're
for the original locale). The `LayoutEditDialog` caption added
in 4.3 item 4 warns the user about this. Documented behaviour,
not a bug.

### 5.5 Subtype name override

`SubtypeUtilsAdditional.createAdditionalSubtype:46–47` already
does:

```java
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && LayoutUtilsCustom.isCustomLayout(mainLayoutName))
    builder.setSubtypeNameOverride(LayoutUtilsCustom.getDisplayName(mainLayoutName))
```

So on Android 14+ a *Languages & Layouts* row using a custom
*PS-mod* layout under `en` shows up as *English (PS-mod)*
automatically. No new code needed.

### 5.6 Dictionary availability for new subtypes

The existing `dictsAvailable(locale, ctx)` check in
`LanguageScreen.kt:126–129` shows `MissingDictionaryDialog`
(lines 115–116, 121–122) when a user enables a subtype for a
locale that has no bundled dictionary. This continues to work;
nothing in this plan changes the dictionary system.

### 5.7 Cache invalidation

Existing `LayoutUtilsCustom.onLayoutFileChanged()`
(`LayoutUtilsCustom.kt:115–117`) +
`KeyboardSwitcher.setThemeNeedsReload()`
(`KeyboardSwitcher.java:824–837`) are already called by
`LayoutEditDialog`'s save flow (`LayoutEditDialog.kt:82, 85`)
and by `LayoutUtilsCustom.deleteLayout`
(`LayoutUtilsCustom.kt:119–123`). Every code path that creates,
renames, or deletes a custom layout already triggers
invalidation. No new hooks needed.

### 5.8 Validation

`LayoutUtilsCustom.checkLayout` (`LayoutUtilsCustom.kt:64–76`)
already validates row counts (≥1, ≤8) and keys per row (≤20).
After PR 1, add two soft checks (warning, not rejection — power
users may have legitimate reasons):

- **MAIN / SYMBOLS / MORE_SYMBOLS prefer 3 or 4 rows.** A 5-row
  file renders with `heightRescale = 4f/5` and looks fine, just
  smaller (and `kannada_extended.txt` ships at 5 rows today, so
  the validator must accept it). Show a yellow note: *"This
  layout has 5 rows. Most keyboards have 3 or 4. Continue
  anyway?"*.
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
  successfully and has 3, 4, or 5 rows.
- Every file under
  `assets/layouts/{symbols,more_symbols}/*.{txt,json}` parses
  successfully and has 3 or 4 rows.
- For each locale tag in `method.xml` that uses a non-Western
  digit set, the baked number-row digits in the locale's MAIN
  file match the `[number_row]` section in the locale's
  `assets/locale_key_texts/<lang>.txt`.

**Parser (PR 2):**

- A 4-row MAIN custom layout renders 4 rows where the top row's
  `KeyParams.mLabel` carries digits literally (no
  `popup.numberLabel` injection).
- A 3-row MAIN custom layout renders 3 rows where the parser
  sets `popup.numberLabel` on each top-row key via the new
  `baseKeys.size == 3` trigger in `addNumberRowOrPopupKeys`.
- A 3-row MAIN custom layout where the top row keys have
  explicit popups (`q !`, `w @`) preserves the user's popups as
  hints — verified by the null-check from 5.1 keeping
  `popup.numberLabel` unset for keys that already declare an
  in-file popup.
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
- Saving a copy of `qwerty` from an English subtype produces a
  new `custom.Latn.<base36>.<chosen-name>.` file in
  `<filesDir>/layouts/main/`.
- Saving a copy of `qwerty` from a Russian subtype produces a
  `custom.ru-RU.<base36>.<chosen-name>.` file scoped to that
  locale.
- A `qwerty+` fork shows the locale-extras-frozen caption.

---

## 7. Out of scope and follow-ups

The following are *not* part of this plan but are reasonable
follow-ups once the core merge ships:

- **Compact 3-row built-in variants.** After PR 1 most built-ins
  are 4 rows. Adding e.g. `qwerty_compact` (3 rows, no number
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
  per-subtype *Hint source* setting in
  `SubtypeScreen.kt:171–192` is the only fine-grained control.

---

## 8. Open questions

- **Should we keep `PREF_LOCALIZED_NUMBER_ROW`?** Section 5.2
  recommends keep with a narrowed scope. Alternative: drop and
  have 3-row users fork a custom layout when they want
  non-Western digit hints. Drop is simpler; keep is friendlier
  to bilingual users. Default: keep.
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
  user might wonder why their *custom* symbol layout shows
  hints and the built-in doesn't. Document this in
  `keyboard/internal/keyboard_parser/AGENTS.md`.
- **Should the 12 already-4+-row built-in main layouts get
  their popup hints normalised in PR 1?** Today they each have
  their own digit row with whatever popups the original author
  chose, which differ from `number_row.json`'s
  `! @ # $ % ^ & * ( )` set. Normalising would unify the UX;
  leaving them alone preserves any locale-specific intent. The
  conservative default is **leave them alone** and document the
  divergence in `assets/layouts/AGENTS.md`.

---

## 9. File reference

Files this plan touches, grouped by PR:

**PR 1 (assets):**

- `app/src/main/assets/layouts/main/*.{txt,json}` — ~64 files
  (the 12 already-4-or-5-row layouts from §2.1 are skipped)
- `app/src/main/assets/layouts/symbols/*.txt` — 2 files
- `app/src/main/assets/layouts/more_symbols/*.txt` — 1 file
- `app/src/main/assets/layouts/AGENTS.md`
- `tools/<new>.{py,sh,kt}` — optional generator script that
  reads `assets/locale_key_texts/<lang>.txt` [number_row]
- `app/src/test/.../LayoutAssetsTest.kt` — new test

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
- `app/src/main/res/values/strings.xml` — description text for
  the localised-number-row toggle
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

1. `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/KeyboardParser.kt`
   — parser entry point, the file PR 2 mostly edits.
2. `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/LayoutParser.kt`
   — asset loader, parses simple-text and JSON.
3. `app/src/main/java/helium314/keyboard/latin/utils/LayoutUtilsCustom.kt`
   — custom-layout file conventions.
4. `app/src/main/java/helium314/keyboard/settings/screens/SubtypeScreen.kt`
   — the subtype detail UI, contains `MainLayoutRow` and the
   per-slot dropdowns.
5. `app/src/main/java/helium314/keyboard/settings/dialogs/LayoutEditDialog.kt`
   — the editor.
6. `app/src/main/java/helium314/keyboard/latin/utils/SubtypeSettings.kt`
   — subtype state and `onRenameLayout`.
7. `app/src/main/java/helium314/keyboard/latin/utils/PopupKeysUtils.kt`
   — hint-label derivation.
8. `app/src/main/assets/layouts/AGENTS.md` and
   `app/src/main/java/helium314/keyboard/keyboard/internal/keyboard_parser/AGENTS.md`
   — pre-existing conventions.
9. `app/src/main/assets/locale_key_texts/<lang>.txt` and
   `LocaleKeyboardInfos.kt` — per-locale `[number_row]` and
   `[extra_keys]` data PR 1's generator script must consume.
