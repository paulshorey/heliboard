# Unifying Custom keyboards with the existing per-locale layout system

This document answers two questions:

1. How does HeliBoard already store and pick the per-locale keyboard
   key arrangement when the user adds a language in
   *Settings → Languages & Layouts*?
2. Could the new **Custom keyboards** feature (PR
   [#113](https://github.com/paulshorey/heliboard/pull/113)) reuse that
   same machinery so the two systems become one?

The short answer to both is "yes". HeliBoard already has a working
"add-your-own layout, per locale" system. The new Custom keyboards
feature partially re-implements it from scratch through a separate
prefs blob. The right move is to fold the new feature **into** the
existing system rather than alongside it.

The rest of this doc explains exactly what's there today, what the new
feature added on top, and a concrete plan for merging them.

---

## 1. How per-locale layouts work today (before this PR)

### 1.1 The two storage tiers

Built-in layouts and user-defined layouts already share the same
parser and the same on-disk shape — they just live in different
places.

**Built-in layouts (read-only)**

- `app/src/main/assets/layouts/<slot>/<name>.{txt,json}` — one file per
  layout, per slot (`main/`, `symbols/`, `more_symbols/`,
  `functional/`, `number/`, `number_row/`, `numpad/`, `phone/`,
  `emoji_bottom/`, `clipboard_bottom/`).
- `assets/layouts/main/` has 76 files: `qwerty.txt`, `azerty.json`,
  `colemak.json`, `dvorak.json`, `bepo.txt`, `bengali_unijoy.json`,
  `hindi.json`, `arabic.txt`, `russian.txt`, etc.
- Format A — **rich Floris JSON** (e.g. `azerty.json`,
  `colemak.json`). One JSON array per row, with `{ "label": "a" }`
  objects that can carry `popup` sets, shift-state selectors, code
  overrides, width hints, etc. See
  `keyboard/internal/keyboard_parser/floris/`.
- Format B — **simple text** (e.g. `qwerty.txt`, `azerty.txt`,
  `bepo.txt`, `symbols/symbols.txt`). Rows separated by blank lines,
  one key per line, first whitespace-separated token is the primary
  label and the remaining tokens are popup keys.

```
≠ ≈ ≡
≤ ≥
± ∓
∞ ∝
…
                  <- blank line == row break
$$$
←
↑
…
```

This format is what `KeyboardParser`/`LayoutParser.parseSimpleString`
already understands. The new Custom keyboards feature's row syntax
(`primary|hint primary|hint …`) is a **third** dialect that gets
translated back into Format B at render time
(`CustomKeyboards.toSimpleLayoutText`).

**User-defined custom layouts (read/write)**

- Stored as files inside the app's device-protected files dir:
  `<filesDir>/layouts/<slot>/custom.<scope>.<base36-name>.`.
- `<scope>` is either a script (`Latn`, `Cyrl`, …) or a BCP-47 locale
  tag (`fr-FR`), depending on the locale's script. See
  `LayoutUtilsCustom.getLayoutName`.
- The file body is the **same** text/JSON the built-in layouts use,
  so the same parser handles both. `LayoutParser.getLayoutFileContent`
  checks `LayoutUtilsCustom.isCustomLayout(name)` and reads from the
  files dir, otherwise falls through to `assets/`.

### 1.2 How a subtype picks a layout

The user-visible "language list" is the set of *enabled subtypes*, not
the set of files. Each subtype is identified by

- a `Locale` (e.g. `en_US`, `fr_FR`, `bn_IN`), and
- an `extraValues` string. Inside `extraValues`,
  `KeyboardLayoutSet=MAIN§<layoutName>§SYMBOLS§<layoutName>§…` maps
  each `LayoutType` slot to a specific layout name (built-in *or*
  custom). See `latin/utils/LayoutType.kt`, `SettingsSubtype.kt`.

The list of all available layouts a subtype can pick from for a given
slot is the union of:

- everything under `assets/layouts/<slot>/` (built-in), and
- everything under `<filesDir>/layouts/<slot>/` filtered to the
  subtype's locale/script (custom).

Three prefs hold this state:

- `PREF_ENABLED_SUBTYPES` — the user's enabled subtypes.
- `PREF_ADDITIONAL_SUBTYPES` — user-created subtypes that differ from
  the resource ones (e.g. "English (US) with Colemak", "English (US)
  with my custom layout 'PS-mod'"). Loaded by
  `SubtypeUtilsAdditional.createAdditionalSubtypes`.
- `PREF_SELECTED_SUBTYPE` — currently active subtype (cycled by the
  globe key).

### 1.3 The settings UI that already exists

- `settings/screens/LanguageScreen.kt` lists every available subtype
  with an on/off `Switch` (this is "Languages & Layouts"). Tapping a
  row goes to `SettingsDestination.Subtype + subtype.toPref()`.
- `settings/screens/SubtypeScreen.kt` is the per-subtype detail
  screen. It has:
  - A **Main layout** dropdown that lists both built-in layouts for
    that locale and custom layouts. Buttons next to each entry let
    the user **add** a new custom layout (with the option to start
    from the current selection's content via
    `LayoutUtils.getContentWithPlus`), **edit** an existing custom
    layout in `LayoutEditDialog`, or **delete** it.
  - Per-slot dropdowns for every other `LayoutType` (Symbols,
    Symbols-shifted, Functional, Number, Number row, Numpad, Phone,
    Emoji bottom, Clipboard bottom) with the same add/edit/delete
    flow. So the existing system already supports overriding any
    slot, per subtype.
  - Secondary locales, popup-order, hint-source, more-popups,
    localized number-row toggle, etc.

- `settings/dialogs/LayoutEditDialog.kt` is a multi-line text editor
  that accepts the same simple-text or JSON format the parser
  consumes. It validates by re-parsing
  (`LayoutUtilsCustom.checkLayout`), writes the result to the files
  dir, calls `SubtypeSettings.onRenameLayout` to keep prefs
  consistent, and triggers `KeyboardSwitcher.setThemeNeedsReload`.

### 1.4 How rendering picks a layout (without the new feature)

`KeyboardParser.parseLayout(layoutType, params, context)` →
`LayoutParser.parseLayout` →

1. If the layout name starts with `custom.` → read the user file.
2. Else → read the asset under `assets/layouts/<slot>/`.
3. Parse as JSON if it starts with `[`, otherwise as simple text.
4. Optionally append the number row, locale extra keys, etc.

So the **same parser** already produces the same keyboard whether
the layout is built-in or user-edited. Per-locale uniqueness is
handled at the subtype level, not at the layout-file level.

---

## 2. What this PR added on top

PR #113 adds:

- A separate prefs blob `PREF_CUSTOM_KEYBOARDS_JSON` that stores a
  `Document` with `{ active, presets: [Preset] }`. Each `Preset` has
  a `locales` list and three slots: `alphabet`, `symbols`,
  `more_symbols`. Each slot is 3 or 4 rows of `primary|hint
  primary|hint …` tokens.
- `latin/utils/CustomKeyboards.kt` — parser, validator, locale
  matcher (`Preset.matchScore(locale)` → exact tag → language → `*`),
  and a `toSimpleLayoutText` step that translates a preset slot back
  into the existing simple-text format the parser already
  understands.
- An override in `LayoutParser.parseLayout`: when
  `PREF_USE_CUSTOM_KEYBOARDS` is on **and** a preset's `locales`
  match the current subtype's locale, the asset/file lookup is
  bypassed and the preset's rows are rendered instead.
- `KeyboardParser` tweaks so the preset's hints render and the
  built-in number row is not prepended.
- `settings/screens/CustomKeyboardsScreen.kt` — a single
  free-text JSON editor for the whole document.

### 2.1 What this duplicates

| Capability | Existing system | New feature |
| --- | --- | --- |
| Per-locale layout selection | Subtype `extraValues` `KEYBOARD_LAYOUT_SET` + `LayoutParser` per-slot lookup | `Preset.locales` + `presetForLocale` override |
| Storage of user-edited layouts | One file per (slot, locale-or-script, layout name) under `<filesDir>/layouts/` | One JSON blob in `SharedPreferences` |
| Editor UI | `LayoutEditDialog` + per-slot dropdown in `SubtypeScreen` | One giant `OutlinedTextField` for the whole document |
| Multiple variants per locale | "Additional subtypes" — same locale, different layouts in the dropdown, each becomes its own enabled entry | One preset per slot per locale (no UI yet to add/remove individual presets — must edit JSON) |
| Number-row inclusion | Per-subtype `LOCALIZED_NUMBER_ROW` extra value + global `PREF_LOCALIZED_NUMBER_ROW` | Implicit from row count (4 = number row, 3 = none) |
| Per-key hints / popups | Full popup machinery (`PopupSet`, popup sources, more-popups setting) | Single literal hint character per key, no popup sources |
| Symbol/More-symbol slots | Each subtype can override `SYMBOLS` / `MORE_SYMBOLS` independently | Preset must declare all three slots together |

### 2.2 What the new feature has that the old system doesn't

- A friendlier syntax for the common "I want a primary character with
  exactly one hint above it" case. Today the simple-text rows let you
  write `a 1` (primary `a`, popup `1`) but the *hint* (the small
  grey label) is currently sourced from auto-generated number-row
  hints or locale popup-key data, not from the file.
- A wildcard preset (`"locales": ["*"]`) that applies to any locale
  that has no more-specific preset.
- One global Enable/Disable switch.

Both of those are nice. But they don't justify a second storage tier
in `SharedPreferences` running parallel to the file-based one.

---

## 3. Proposed merged design

### 3.1 Mental model

- **Built-in layouts** under `assets/layouts/<slot>/*` are
  **immutable templates**. They appear in the dropdown as
  "QWERTY", "AZERTY", "Colemak", "Bengali (Unijoy)", etc. The user
  cannot edit them, only copy from them.
- **User layouts** under `<filesDir>/layouts/<slot>/custom.<scope>.…`
  are **editable**. The user creates one by starting from any
  built-in layout (or from an empty/seed template), edits it, and
  optionally deletes it. The existing system already supports this
  for *every* slot.
- **Multiple variants per locale** are expressed as multiple
  *additional subtypes* sharing the same locale but pointing at
  different `mainLayoutName` (and/or different per-slot layouts).
  Today the *Languages & Layouts* screen already lists those
  variants side by side and the globe key already cycles through
  them.
- **Per-locale matching** falls out for free: every subtype carries
  its own `Locale` and its own per-slot layout name. The renderer
  already picks the right thing.

So the user-visible plan is:

1. Open *Languages & Layouts*.
2. Tap a language to open the subtype detail screen.
3. Use the existing **Main layout** dropdown to pick a layout.
4. Tap the `+` button to add a new layout for this locale. A dialog
   asks which existing layout to base it on (built-in or another
   custom one), gives it a name, opens `LayoutEditDialog` pre-filled
   with the chosen base.
5. Tap the pencil button next to a custom layout to edit it.
6. Tap the trash button to delete a custom layout (with a warning if
   another subtype references it).
7. If the user wants the new layout to be the active main layout for
   that language, the dropdown selects it. If they want it as a
   second alphabet that the globe key can cycle to, they save it,
   then on *Languages & Layouts* they enable the additional subtype
   that uses it.

That flow already exists today for the MAIN slot. The new feature
exposes nothing that this flow can't do — except the convenience of
hint labels and the wildcard preset.

### 3.2 What still moves over from the new feature

Two things from PR #113 are worth keeping after the merge:

1. **Per-key hint column in the simple-text format.** Today the
   simple-text format is `<primary> <popup1> <popup2> …` with no
   separate hint label. The user-facing convenience of PR #113 is
   "I can write `a|@` and `@` shows as a tiny gray label above `a`
   and is the only popup." This is genuinely useful and can live in
   the existing parser by adding a row-token convention:

   - Rows are still simple-text format.
   - A token may contain a single unescaped `|`. Everything before
     `|` is the primary; everything after is the **hint**. The
     popup of that key is also set to the hint character.
   - `\|` and `\\` escapes carry over from
     `CustomKeyboards.splitKeyToken`.
   - `KeyboardParser` already has the logic that drops
     `LABEL_FLAGS_DISABLE_HINT_LABEL` on symbol layouts when custom
     keyboards is on. After the merge, this becomes a per-layout
     hint when the layout file (asset or custom) contains any
     `primary|hint` tokens.

   With this change, *any* layout file — built-in or custom —
   can declare hints directly, and there is no separate "preset"
   concept needed.

2. **A "Programming (any language)" baseline.** Today every layout is
   tied to a script via the `custom.<script>.…` naming. To preserve
   the "this applies to any language" wildcard, allow
   `custom.any.<name>.` as a recognized scope:

   - `LayoutUtilsCustom.getLayoutName` learns to accept an explicit
     "any" sentinel as an alternative to a locale/script.
   - `getLayoutFiles(LayoutType.MAIN, ctx, locale)` returns
     `custom.any.*` layouts in addition to script/locale matches.

   This makes the "Programming" preset from PR #113 expressible as
   a regular custom MAIN layout that any subtype can pick.

Everything else (`PREF_USE_CUSTOM_KEYBOARDS`, the JSON document, the
parallel preset cache, `LayoutParser`'s short-circuit) goes away.

### 3.3 Migration

Users currently on PR #113 have a `PREF_CUSTOM_KEYBOARDS_JSON` blob.
A one-time migration in `AppUpgrade.kt` should:

1. Parse the existing document.
2. For each preset, write three files under
   `<filesDir>/layouts/<slot>/`:
   - `custom.<scope>.<base36 of preset name>.` containing the
     simple-text rows produced by
     `CustomKeyboards.toSimpleLayoutText(preset, slot)` *plus* the
     new `primary|hint` token convention so hints survive.
   - `<scope>` is the preset's `locales` first entry if it's a
     locale/script tag, or `any` for `["*"]`.
3. For each enabled subtype that the preset's locale matches, write
   an additional subtype that points all three slots at the new
   custom layouts (or, if the user wants a soft default, only do
   this if PR #113's `PREF_USE_CUSTOM_KEYBOARDS` was on).
4. Delete `PREF_USE_CUSTOM_KEYBOARDS` and
   `PREF_CUSTOM_KEYBOARDS_JSON`.

Users on stock HeliBoard see no change.

### 3.4 What disappears

- `latin/utils/CustomKeyboards.kt` deleted entirely.
- The override branch in `LayoutParser.parseLayout` deleted; the
  parser becomes the single rendering path again.
- `KeyboardParser` symbol-layout / number-row injection logic that
  was conditionalized on the active preset becomes unconditional
  again (the hints come from the layout file directly).
- `settings/screens/CustomKeyboardsScreen.kt` and its navigation
  entry deleted; the *Languages & Layouts* tree is the single
  surface for picking and editing layouts.
- `PREF_USE_CUSTOM_KEYBOARDS` / `PREF_CUSTOM_KEYBOARDS_JSON` removed
  from `Settings.java` / `Defaults.kt` (with the migration above).
- Default JSON seed in `Defaults.kt` deleted.

### 3.5 What gets added or extended

- `KeyboardParser` / parser-side: support the `primary|hint` token
  inside simple-text rows. Implementation re-uses
  `CustomKeyboards.splitKeyToken` logic almost verbatim, just moved
  into `LayoutParser.parseKey` or a `toTextKey` helper.
- `LayoutUtilsCustom`: accept `any` as a scope for MAIN, so a custom
  MAIN layout can be declared "applies to all Latin / all locales".
- `SubtypeScreen` Main-layout row: small UX additions on top of what
  already exists.
  - In the *Add custom layout* dialog, default the starting content
    to the layout currently selected for this slot, not just empty.
  - When the user picks a built-in layout, also show a "Make
    editable copy" button that creates a custom layout pre-seeded
    with the built-in's content. (This is the
    "edit the built-in layout" workflow the task description asks
    for: built-ins stay read-only, but copying them is one click.)
  - Surface the per-slot dropdowns for SYMBOLS and MORE_SYMBOLS
    with the same Add/Edit/Delete affordances the MAIN row has
    today; the existing code already iterates `LayoutType.entries`
    and shows dropdowns, but only the MAIN row has the file-edit
    UI. The other slots need the same `+`/edit/delete buttons.
- `LanguageScreen`: add an explicit **+ Add custom keyboard layout**
  action at the top of the list (and in the search-empty state).
  Tapping it opens a small wizard:

  1. Pick a base — a built-in layout from the dropdown (filtered to
     the locale/script the user picks), or "Any language
     (Latin-only)" for a `custom.any.*` layout.
  2. Name it.
  3. Open `LayoutEditDialog` pre-filled with the base's content.
  4. On save, optionally also create an additional subtype that uses
     the new layout for the chosen locale, and enable it.

  This wizard is just a thin wrapper around the existing
  `LayoutUtilsCustom.getLayoutName` /
  `LayoutEditDialog` /
  `SubtypeUtilsAdditional.createDummyAdditionalSubtype` /
  `SubtypeSettings.addEnabledSubtype` calls. No new storage layer,
  no new parser, no new preference.

### 3.6 Where things end up in the codebase

| Concern | Owning file(s) |
| --- | --- |
| Locale + layout binding | `latin/settings/SettingsSubtype.kt`, `latin/utils/SubtypeSettings.kt`, `latin/utils/SubtypeUtilsAdditional.kt` (no changes needed) |
| Built-in layout files | `app/src/main/assets/layouts/<slot>/` (no changes) |
| User layout files | `<filesDir>/layouts/<slot>/custom.<scope>.<base36>.` (`LayoutUtilsCustom` learns the `any` scope) |
| Layout parsing | `keyboard/internal/keyboard_parser/LayoutParser.kt`, `KeyboardParser.kt` (learn `primary|hint` token, drop preset short-circuit) |
| Per-subtype editor UI | `settings/screens/SubtypeScreen.kt` (extend Add/Edit/Delete to non-MAIN slots, add "Make editable copy" button) |
| Language list entry point | `settings/screens/LanguageScreen.kt` (add "+ Add custom layout" wizard entry) |
| Layout content editor | `settings/dialogs/LayoutEditDialog.kt` (no changes) |
| Migration | `latin/AppUpgrade.kt` (read old JSON, write files, remove prefs) |

### 3.7 What this gives us vs PR #113

- **One source of truth** for per-locale layouts: a subtype + its
  per-slot layout files. Both built-in and custom flow through the
  same parser, the same files-dir layout, the same dropdowns.
- **No global on/off switch.** Custom layouts are just layouts; if
  the user picks them, they get them.
- **Edit-as-copy on built-ins** without making the built-ins
  themselves editable: tap "Make editable copy" on any built-in to
  fork it into a custom layout under the same locale/script.
- **More than one custom layout per locale** is already trivially
  expressible — the user adds more additional subtypes, each
  pointing at a different custom layout. The globe key cycles
  through them.
- **The wildcard preset** survives as `custom.any.<name>.`.
- **The friendly hint syntax** survives because the parser learns
  `primary|hint`, applicable everywhere — including the user's
  edits of *built-in* layout templates after they fork them.
- **No second JSON blob in prefs**, so there's no "the user edited
  the JSON wrong and the whole keyboard broke" failure mode. Each
  layout file is validated independently by `LayoutUtilsCustom.checkLayout`,
  just like today.

---

## 4. Concrete merge steps

This is the order I'd implement the merge in.

1. **Parser: add `primary|hint` token to simple-text rows.**
   - Move `splitKeyToken` / `unescape` out of `CustomKeyboards.kt`
     into a small helper next to `LayoutParser.parseKey`.
   - Teach `LayoutParser.parseKey` to detect an unescaped `|` in a
     row token and emit a `KeyData` whose primary label is the
     left side, whose popup set contains the right side, and whose
     drawn hint label is the right side.
   - Make sure `KeyboardParser` does *not* clear
     `LABEL_FLAGS_DISABLE_HINT_LABEL` on those keys unconditionally
     — only when the row token actually had a `|`. (Today it
     clears them globally on symbol layouts when the preset is
     active.)
   - No call sites change in `assets/layouts/`. Builtin layouts
     keep working as before. The `|` form is purely additive.

2. **`LayoutUtilsCustom`: support `any` scope.**
   - Treat the literal string `any` as a valid MAIN scope. The
     name shape becomes `custom.any.<base36>.`.
   - `getLayoutFiles(MAIN, ctx, locale)` includes `custom.any.*` in
     its result for *any* locale whose script is Latin
     (matching the current behavior of "Latin script gets all
     Latin-scoped custom layouts").
   - For non-Latin scripts, decide whether `any` is also exposed.
     A reasonable default: yes, but show a warning in the editor
     dialog because the layout's labels may not be appropriate
     for that script.

3. **SubtypeScreen: bring non-MAIN slots up to parity.**
   - Extract the MAIN-row Add/Edit/Delete UI into a reusable
     `LayoutSlotEditor` composable.
   - Render it for every `LayoutType` slot (not just MAIN),
     replacing the read-only-ish dropdown that's there today.
   - "Add" for non-MAIN slots needs a scope choice (or just always
     uses `any` since SYMBOLS/MORE_SYMBOLS don't depend on locale
     today).

4. **SubtypeScreen + LayoutEditDialog: "Make editable copy" button.**
   - When the currently selected layout is **built-in**, show a
     button labelled "Edit a copy". Clicking it opens
     `LayoutEditDialog` with `initialLayoutName = currentSelection`,
     `startContent = LayoutUtils.getContentWithPlus(...)`, and
     `isNameValid` configured so the user has to choose a new name.
     After save the dropdown selection switches to the new custom
     layout.

5. **LanguageScreen: top-of-list "Add custom layout" wizard.**
   - 3-step dialog: locale → base layout → name + editor.
   - Optionally tick a "Enable for this language" box that, on save,
     calls `SubtypeUtilsAdditional.createDummyAdditionalSubtype` +
     `SubtypeSettings.addEnabledSubtype`.

6. **Migration in `AppUpgrade.kt`.**
   - Detect `PREF_CUSTOM_KEYBOARDS_JSON` and migrate each preset to
     three layout files using `CustomKeyboards.toSimpleLayoutText`
     (or its successor in step 1).
   - For locales that had a matching preset, optionally add an
     additional subtype + enable it (only if the user had
     `PREF_USE_CUSTOM_KEYBOARDS = true`).
   - Remove the old prefs.

7. **Delete the parallel system.**
   - Remove `latin/utils/CustomKeyboards.kt`,
     `settings/screens/CustomKeyboardsScreen.kt`, the override
     branch in `LayoutParser.parseLayout`, the conditional
     symbol-hint / number-row logic in `KeyboardParser`, the
     `PREF_USE_CUSTOM_KEYBOARDS` / `PREF_CUSTOM_KEYBOARDS_JSON`
     constants and defaults, the MainSettingsScreen entry, and the
     SettingsNavHost destination.
   - Update the AGENTS.md files in `latin/utils`,
     `latin/settings`, `keyboard/internal/keyboard_parser`,
     `settings/screens` to reflect the merged architecture.

8. **Rebuild the canonical APK** with
   `./tools/build-dist-apk.sh`.

This sequence keeps each step shippable. Step 1 alone makes the
existing custom-layout editor strictly more powerful (any layout can
now declare visible hints). Steps 2–5 add UI polish without changing
storage. Step 6 is the one user-visible behavior change. Step 7
removes the dead parallel system.

---

## 5. Open questions

- Should the legacy number-row logic come back to its pre-PR-#113
  state, or is the "preset's row count decides number row" rule
  worth generalizing? The cleanest answer: keep the
  per-locale-subtype `LOCALIZED_NUMBER_ROW` toggle (which already
  exists) and let a custom MAIN layout that has 4 rows simply
  *include* the number row in its file. The runtime check then
  becomes "if this layout file is 4 rows tall, treat row 1 as the
  number row and skip the global number-row injection." This is a
  local change to `KeyboardParser` and applies to built-in and
  custom layouts uniformly.
- Do we want the "Edit a copy" affordance to also cover the
  *FUNCTIONAL* slot (space bar, shift, etc.)? Today the existing
  system already supports it; the new feature does not. Probably
  yes, but it's separate from the merge.
- Is there appetite for a future "export/import all of a user's
  custom layouts as a zip"? The file-based system makes that
  trivial; the JSON blob made it trivial in a different way. After
  the merge, a single export command can just zip
  `<filesDir>/layouts/`.

---

## 6. TL;DR

HeliBoard already has a per-locale, per-slot, editable-layout system
sitting in `LayoutUtilsCustom` + `SubtypeScreen` +
`LayoutEditDialog` + `SubtypeSettings`. PR #113 built a parallel
system in `SharedPreferences` for the same purpose, with a worse UX
(monolithic JSON editor, can't add/remove a single preset, no
per-slot editing) but two genuinely nice features (`primary|hint`
tokens and `*` wildcard).

The merge is:

- **Keep** the existing per-locale subtype system as the only
  storage and rendering path.
- **Lift** the `primary|hint` token convention into the shared
  parser so every layout file (built-in or custom) can declare
  hints.
- **Add** an `any` scope to `LayoutUtilsCustom` so a layout can be
  declared locale-agnostic.
- **Polish** `SubtypeScreen` / `LanguageScreen` so creating a
  custom layout from a built-in baseline is one click and applies
  to every slot, not just MAIN.
- **Migrate** existing PR #113 documents into custom-layout files
  and **delete** the JSON-prefs path.

Built-in layouts stay read-only (you can copy from them but not
overwrite them). Users get full control over their own layouts, per
locale, with as many variants as they want, all surfaced in the
existing *Languages & Layouts* tree.

---

# Appendix A — Review notes and revisions

After writing sections 1-6 above I re-checked the codebase end-to-end
looking for edge cases. Several findings change recommendations in
the body of this document; this appendix is the *authoritative*
version where it disagrees with the body, and the body should be
treated as the "Why" while this appendix is the "How".

The findings are grouped by severity.

## A.1 Resolved — the existing format already encodes hints, just implicitly

**This subsection is revised after maintainer feedback. The earlier
JSON-`hint`-field proposal is dropped — it is not needed.**

### How hints actually work in the existing system

The existing simple-text and rich-JSON layout formats **already
encode hints**, they just do it without a dedicated `hint` field. The
hint above a key is derived from the key's popup set by
`latin/utils/PopupKeysUtils.kt → getHintLabel(...)`:

```kotlin
fun getHintLabel(popupSet, params, label): String? {
    for (type in params.mPopupKeyLabelSources) {
        when (type) {
            POPUP_KEYS_NUMBER -> popupSet?.numberLabel?.let { hintLabel = it }
            POPUP_KEYS_LAYOUT -> popupSet?.getPopupKeyLabels(params)?.let { hintLabel = it.firstOrNull() }
            POPUP_KEYS_SYMBOLS -> popupSet?.symbol?.let { hintLabel = it }
            POPUP_KEYS_LANGUAGE -> ...
            POPUP_KEYS_LANGUAGE_PRIORITY -> ...
        }
        if (hintLabel != null) break
    }
    ...
}
```

The default priority order (`POPUP_KEYS_LABEL_DEFAULT` in
`PopupKeysUtils.kt`) makes `POPUP_KEYS_LAYOUT` — *"the first popup
declared by the layout file itself"* — the primary hint source for
alphabet keyboards once the auto-generated number-row hint is taken
out. So:

| Format | How the user writes "primary `a`, hint `@`" |
| --- | --- |
| Simple-text | `a @` (popup `@` → hint `@`) |
| Rich JSON | `{ "label": "a", "popup": { "main": { "label": "@" } } }` |
| PR #113 row | `a\|@` |

These are all **the same key** at render time. `qwerty.txt` already
uses this convention everywhere — `i - –` means *primary `i`, hint
`-`, additional popup `–`*. `colemak.json` does the same with
`{ "label": "o", "popup": { "main": { "label": "…" } } }`.

### What this means for the merge

1. **No parser changes for hint support.** No `TextKeyData.hint`
   field, no simple-text grammar extension, no `|` overload, no
   `// format:` magic header. The existing
   *first-popup-becomes-the-hint* rule is already what users want.

2. **The new feature's `primary|hint` syntax is a pure user-input
   sugar that we can drop on the floor.** PR #113's
   `CustomKeyboards.splitKeyToken` turns `a|@` into `"a"` +
   `"@"`, and `CustomKeyboards.toSimpleLayoutText` then emits
   simple-text rows where the popup is `@`. That output is already
   parseable by the existing pipeline — the only reason this whole
   detour exists is that the user typed `|` for ergonomic reasons.

3. **Migration is trivial.** `AppUpgrade.kt` calls
   `CustomKeyboards.toSimpleLayoutText(preset, slot)` (the same
   function PR #113 already ships) for each slot and writes the
   result to
   `<filesDir>/layouts/<slot>/custom.<scope>.<base36>.` as plain
   simple-text. No JSON construction, no new schema. The resulting
   file is indistinguishable from a hand-written custom layout the
   user could have created via *SubtypeScreen → Main layout → +*.

   Escape handling: `splitKeyToken` already unescapes `\|` to `|`
   and `\\` to `\`. Free literal `|` keys end up as a single-
   character token in the simple-text output, which
   `KeySpecParser.indexOfLabelEnd` already special-cases as "sole
   vertical bar as a special case of key label" (line 87). Free
   `\\` keys end up as `\` in the simple-text output, which is the
   `KeySpecParser` escape char — so the migrator must write `\\`
   (a literal backslash-as-key-label needs to be `\\` in the
   simple-text format because `KeySpecParser.parseEscape` strips
   one level). Add a regression test for both.

4. **One small parser tweak (replaces the `customKeyboardsActive`
   branch in `KeyboardParser.kt`).** Today, alphabet keyboards
   show hints by default and symbol/more-symbols keyboards apply
   `Key.LABEL_FLAGS_DISABLE_HINT_LABEL` (line 52). PR #113 clears
   that flag on the symbol layouts when a preset is active so the
   user's authored popups become visible hints. After the merge,
   replace the preset-driven conditional with a check on the
   *layout source*:

   ```kotlin
   params.mId.isAlphabetKeyboard -> params.mLocaleKeyboardInfos.labelFlags
   params.mId.isAlphaOrSymbolKeyboard
     && LayoutUtilsCustom.isCustomLayout(layoutNameFor(params.mId)) -> 0
   params.mId.isAlphaOrSymbolKeyboard -> Key.LABEL_FLAGS_DISABLE_HINT_LABEL
   else -> 0
   ```

   In other words: built-in symbol layouts keep their no-hint
   default (because files like `symbols.txt` use the
   `≠ ≈ ≡` row format where the secondary chars are alternates,
   not hints). User-edited custom symbol layouts get hints
   enabled, because the user is authoring popups that they
   probably want to see.

5. **No need for the optional `// format: hint-rows-v1` editor
   sugar from the previous appendix revision.** The user is going
   to type `a @` directly in `LayoutEditDialog` — same five
   characters as `a|@`, two of them keys.

### Net effect

- One asset-pipeline conversion in the migrator: `CustomKeyboards.toSimpleLayoutText`
  → write to file. The function already exists.
- One conditional in `KeyboardParser`: switch the
  `customKeyboardsActive` branch to `isCustomLayout(layoutName)`.
- Everything else (`TextKeyData`, `LayoutParser`, every built-in
  asset, `KeySpecParser`, the rich-JSON schema, all hint-source
  configuration) is untouched.

This subsection supersedes Section 3.2 item 1, Section 3.5 "Editor
authoring", Section 4 step 1, *and* the earlier (now superseded)
"add `hint` field to JSON" plan.

## A.2 Resolved — what the "any" scope is, and why we don't need it

**Maintainer asked: explain what the "any" scope is. Here's the
explanation and the recommendation.**

### What the "any" scope was supposed to be

PR #113 lets one preset apply to *every* language at once via
`"locales": ["*"]`. The default seeded JSON ships a *"Programming
(any language)"* preset that puts symbols like `@ # $ _` on the
alphabet row regardless of which subtype the user is on. The
hypothetical *"any" scope* in the merged design was the file-system
equivalent of `"*"` — a sentinel like `custom.any.<name>.` that
would make a single custom layout file appear in *every* subtype's
*Main layout* dropdown.

### How custom layouts are scoped today (existing system)

In `LayoutUtilsCustom.kt`:

| Slot | Filename shape | Visible to which subtypes? |
| --- | --- | --- |
| MAIN, Latin locale | `custom.Latn.<base36>.` | Every subtype whose locale uses Latin script (en, fr, de, es, …) |
| MAIN, non-Latin locale | `custom.<bcp47>.<base36>.` | Only the subtype with that exact BCP-47 tag (e.g. `custom.fr-FR.…` is only visible to `fr-FR`) |
| Non-MAIN (SYMBOLS, MORE_SYMBOLS, FUNCTIONAL, NUMBER, NUMBER_ROW, NUMPAD, PHONE, EMOJI_BOTTOM, CLIPBOARD_BOTTOM) | `custom.<base36>.` | Every subtype, regardless of locale (no filtering) |

`getLayoutFiles(LayoutType.MAIN, ctx, locale)` (lines 104-113) is
the function doing this filtering. The key insight is that the
*Latin* scope **already is the wildcard** for the majority of users
— anything you write under `custom.Latn.…` shows up in every
en/fr/de/es/it/pt/etc. *Main layout* dropdown. The seeded
*Programming* preset uses Latin labels (`q w e r t y …`), so it
naturally belongs under `custom.Latn.…`.

### What the "any" sentinel would actually add

The only thing a `custom.any.…` sentinel would do that `custom.Latn.…`
doesn't already do is *also* show the layout under non-Latin
subtypes (Cyrillic, Bengali, Khmer, Hebrew, Arabic, …). For a
Latin-character programming keyboard, surfacing it under, say,
Bengali (which has its own writing system) is rarely what the user
wants — they would have to manually pick it from a dropdown and
lose access to their native script.

For non-MAIN slots (symbols, more-symbols, etc.), there's nothing
to add. They are already universal.

### Recommendation

**Don't introduce an `any` scope. Drop Section 4 step 2 entirely.**

- The *Programming-style "applies to any language"* preset survives
  as a regular `custom.Latn.<base36>.` MAIN layout. Every Latin
  subtype already sees it in the dropdown with zero code changes.
- For SYMBOLS / MORE_SYMBOLS / etc., the user-created custom file
  is already universal because the existing
  `getLayoutFiles(non-MAIN, ctx, locale)` ignores locale.
- The *Add custom layout* dialog should say something like:
  *"Latin-script custom layouts are available to every Latin
  language."* That single line is the entire UX for the wildcard
  case.
- Migration of PR #113's `["*"]` preset writes one file:
  `custom.Latn.<base36>.<programming-name>.`. That's it — no
  fan-out, no per-locale subtype creation. The user picks it from
  the dropdown on whichever Latin subtype they want it active in.

If, *later*, someone needs a truly script-agnostic universal layout
(e.g. a "math keyboard" with Greek/math symbols that someone wants
to use under any language), that's a narrow follow-up. The
implementation would add a new `LayoutUtilsCustom.SCRIPT_ANY` token
and one extra union in `getLayoutFiles`. Not worth doing
preemptively.

## A.3 Resolved — number row becomes part of every layout file; **3-row layouts are first-class**

**Maintainer feedback (round 1): bake the number row into every
locale's MAIN layout file so users can edit those keys when they
fork it. Don't restore the global toggle.**

**Maintainer feedback (round 2): a user must still be able to opt
out of the number row by saving a 3-row custom layout, just like
PR #113 supported 3-row presets. In that case the digits should
become hints on the top alphabet row.**

Both are satisfied by the same plan: the *layout file's row count*
becomes the source of truth, and the parser already handles both
3 and 4 rows correctly today.

### Why this is the right call

The whole point of the merge is *one storage tier, one editor*.
A runtime "prepend a number row" step in `KeyboardParser` is
exactly the kind of hidden-state behaviour the merge is trying to
eliminate. If the user forks `qwerty` to make their own *PS-mod*
layout, they expect the number row to be in the file they're
editing. Right now it isn't — it lives in
`assets/layouts/number_row/number_row.json` and gets stitched in
at render time. PR #113 already moved this responsibility into the
preset itself ("4 rows = top row is the number row, 3 rows = no
number row"). After the merge, that rule extends to every layout,
built-in and custom.

### The user-facing model

A MAIN layout file decides its own row count. Rendering is:

| Row count in the file | Renders as | Hints on top row |
| --- | --- | --- |
| 4 (number row baked in as row 1) | 4 rows; top row is the number row | Same hints as any other row — sourced from each key's first popup in the file (`POPUP_KEYS_LAYOUT`) |
| 3 (no number row) | 3 rows; alphabet only | Digit hints auto-appear above the top alphabet row, sourced from the locale's number-row asset (`POPUP_KEYS_NUMBER`). If the user authored their own popups on top-row keys, those take precedence over the auto-digit (per `params.mPopupKeyLabelSources` priority) |
| Any other count | Renders that many rows with proportional heights (existing `heightRescale` behaviour) | No special handling |

This is exactly what PR #113 was simulating with its `4 rows` /
`3 rows` preset rule, plus an automatic digit-as-hint behaviour for
the 3-row case that the new feature did not support.

The mechanism for the 3-row case already exists at
`KeyboardParser.kt:289-294`:

```kotlin
private fun addNumberRowOrPopupKeys(baseKeys, numberRow) {
    if (customKeyboardsActive) return
    if (!params.mId.mNumberRowEnabled && params.mId.isAlphabetKeyboard && !hasBuiltInNumbers()) {
        baseKeys.first().forEachIndexed { i, keyData ->
            keyData.popup.numberLabel = numberRow.getOrNull(i)?.label
        }
    }
}
```

`numberLabel` is read by `PopupKeysUtils.getHintLabel` (the
`POPUP_KEYS_NUMBER` branch) as a hint source, so the digits show
up small and grey above each top-row key with no other work
needed. The only change is the *trigger*: instead of "global
`mNumberRowEnabled` toggle is off", it becomes "the parsed layout
has 3 rows".

### What this requires (replaces the previous A.3 plan)

1. **Bake the number row into every built-in MAIN layout file under
   `app/src/main/assets/layouts/main/*.{txt,json}`.** 76 files.

   - All `.txt` files get a new first row consisting of
     `1 2 3 4 5 6 7 8 9 0`, followed by a blank line, followed by
     the existing content.
   - All `.json` files get a new first array element:
     `[{ "label": "1" }, { "label": "2" }, ..., { "label": "0" }]`.
   - **Source the digits per locale where appropriate.** Match
     today's behaviour of localized digits:

     | Existing localized digit set (from `number_row/`) | Locales |
     | --- | --- |
     | Eastern Arabic-Indic `٠ ١ ٢ ٣ ٤ ٥ ٦ ٧ ٨ ٩` | `ar`, `arabic_hijai`, `arabic_pc`, `farsi`, `central_kurdish`, etc. |
     | Bengali numerals `০ ১ ২ ৩ ৪ ৫ ৬ ৭ ৮ ৯` | `bengali_*` |
     | Devanagari `० १ २ ३ ४ ५ ६ ७ ८ ९` | `hindi*` |
     | Thai `๐ ๑ ๒ ๓ ๔ ๕ ๖ ๗ ๘ ๙` | `thai*` |
     | Khmer `០ ១ ២ ៣ ៤ ៥ ៦ ៧ ៨ ៩` | `khmer*` |
     | Lao `໐ ໑ ໒ ໓ ໔ ໕ ໖ ໗ ໘ ໙` | `lao*` |
     | Burmese `၀ ၁ ၂ ၃ ၄ ၅ ၆ ၇ ၈ ၉` | `burmese*` (if present) |
     | Western `1 2 3 4 5 6 7 8 9 0` | everything else (Latin, Cyrillic, Greek, Hebrew, etc.) |

     `LocaleKeyboardInfos.kt` already encodes which scripts use
     which digit set; reuse that mapping when generating the
     baked-in rows.

   - **Hints on the number row.** Some current renderings put
     locale-specific symbols above number keys (`! @ #` style).
     Today this comes from `LocaleKeyboardInfos.getPopupKeys`. To
     keep that behaviour, write each number-row key as
     `1 !` (primary `1`, hint `!`) in the simple-text format or
     `{ "label": "1", "popup": { "main": { "label": "!" } } }` in
     JSON, mirroring whatever `LocaleKeyboardInfos` currently emits
     for the active locale. If a layout author later wants to wipe
     those hints they just remove the second token per line in
     their custom copy.

2. **Symbols / more-symbols layouts also get number rows baked
   in.** PR #113's seeded *English* preset already does this for
   `symbols` and `more_symbols` (`"1 2 3 4 5 6 7 8 9 0"` is the
   first row of each). Extend the same change to
   `assets/layouts/symbols/symbols.txt`,
   `assets/layouts/more_symbols/symbols_shifted.txt`, and any
   localized variants (`symbols_arabic.txt`). Numbers should
   appear as the first row of every alphabet/symbol layer of every
   locale.

3. **Delete the runtime number-row prepend; keep the
   digit-hint fallback.** In `KeyboardParser.kt`:
   - Remove the `customKeyboardsActive` check and its branches at
     lines 43-54, 124, 290.
   - Remove the `baseKeys.add(0, numberRow.mapTo(...))` injection
     in the `mNumberRowEnabled` branch — the number row is now in
     the file when it's there at all, never prepended.
   - **Keep `addNumberRowOrPopupKeys` (the function that sets
     `popup.numberLabel`).** Flip its guard from
     `!params.mId.mNumberRowEnabled` to *"the parsed alphabet
     layout has 3 rows"* (i.e. `baseKeys.size == 3` for
     `params.mId.isAlphabetKeyboard`). The locale number-row
     asset under `assets/layouts/number_row/` stays in the
     codebase exactly so this fallback can read it. Drop the
     `customKeyboardsActive` early-return.
   - The `getNumberRow()` helper that loads
     `assets/layouts/number_row/` and optionally swaps in
     localised digits stays as-is. It's now only called from the
     `numberLabel`-fallback path, not from the prepend path.
4. **Drop `PREF_SHOW_NUMBER_ROW`, `mShowsNumberRow`,
   `setNumberRowEnabled`, `mNumberRowEnabled`.** Since every layout
   file always has a number row, the parameter is redundant.
   Touched files:
   - `latin/settings/SettingsValues.java` — delete the field.
   - `keyboard/KeyboardLayoutSet.java` — delete the builder method.
   - `keyboard/KeyboardSwitcher.java` — drop the
     `setNumberRowEnabled(...)` calls.
   - `keyboard/KeyboardId.java` — delete the field, recompute its
     equality/hash code.
   - `keyboard/emoji/EmojiLayoutParams.kt`,
     `keyboard/clipboard/ClipboardLayoutParams.kt`,
     `keyboard/internal/KeyboardBuilder.kt` — these all currently
     test `mShowsNumberRow` to decide how much vertical space to
     reserve. Since the answer is now *always reserved for 4
     rows*, drop the conditional.
5. **`PREF_LOCALIZED_NUMBER_ROW` survives, with a narrower scope.**
   After step 1 the digit characters for 4-row layouts live in
   the file directly, so the toggle has no effect there. But the
   3-row → digit-hint mechanism in `addNumberRowOrPopupKeys`
   calls `getNumberRow()` (lines 306-320 of `KeyboardParser.kt`),
   which respects `PREF_LOCALIZED_NUMBER_ROW`/the per-subtype
   `LOCALIZED_NUMBER_ROW` extra value to decide whether to swap
   in localised digits.

   - **Keep the pref.** It still affects 3-row layouts.
   - **Keep the per-subtype `LOCALIZED_NUMBER_ROW` extra value
     and the UI in `SubtypeScreen.kt:212-235`.** Update the
     label/description so users understand it controls the
     *3-row hint digits*, not the *4-row number-row keys*.
     Example string: *"Show localised digits as number hints
     when the keyboard has no number row"*.

   If the maintainer prefers absolute simplicity and is willing
   to drop bilingual digit-hint customisation, this whole bullet
   can collapse to "remove the pref and the extra value, let
   `getNumberRow()` always return the Latin digits". But the
   3-row use case is real (Persian/Arabic users who want their
   3-row alphabet keyboard to show `٠١٢٣...` hints rather than
   `1234...`), so the recommendation is **keep**.

6. **The `hasLocalizedNumberRow(...)` UI in `SubtypeScreen.kt`
   stays.** Same fate as step 5.

7. **The `+` layout system (`qwerty+`, `azerty+`, etc.) is
   unaffected.** `+` appends locale extra keys to specific rows
   (`getExtraKeys(i+1)`). After step 1 the number row is row 1
   and the *row indexing* already starts at 1 in
   `LocaleKeyboardInfos.getExtraKeys` for the *alphabet* rows —
   the helper takes the row *position relative to the alphabet*,
   so we need to either:
   - Adjust the `+` row indexing to account for the new top row
     (`getExtraKeys(i)` instead of `getExtraKeys(i+1)` when
     iterating from row 1), **or**
   - Leave the indexing alone and let the number row be row 0,
     which the `if (params.mId.isAlphabetKeyboard && layoutName.endsWith("+"))` branch in `LayoutParser.kt:128` already
     skips because it currently iterates from `i=0` and asks for
     extras at `i+1`. That makes the number row "row 0" with
     extras at index 1, which is the alphabet top row's extras.
     Re-check the math when implementing.

### How the user opts out of the number row

Two paths, both already supported by the parser after the changes
above:

1. **Per-subtype, by switching the layout.** In *Languages &
   Layouts → [language] → Main layout*, pick a 3-row variant from
   the dropdown (built-in or custom). The selected subtype renders
   3 rows with digit hints. Other subtypes for the same locale
   that picked a 4-row variant are unaffected.

2. **Author a 3-row custom layout.** From a 4-row built-in, tap
   *Edit a copy*, delete the first row in `LayoutEditDialog`,
   save. The parser will render that custom layout as 3 rows and
   add digit hints automatically.

There is no global "show/hide number row" toggle. The decision is
encoded in whichever MAIN layout file the subtype is pointing at.
This matches PR #113's row-count-as-source-of-truth design but
applies it uniformly across built-in and custom layouts.

### Trade-offs the maintainer has accepted

- **Default user experience is 4 rows.** All shipped MAIN
  layouts include a number row. A user who prefers 3 rows
  authors (or picks) a custom layout. We can later ship a few
  3-row built-ins (e.g. `qwerty_compact`) if there's demand.
- **76 built-in layout files have to be edited.** Mechanical and
  scriptable. A small generator can read the locale → digit-set
  mapping and emit the new top rows. CI check: every
  `main/*.{txt,json}` has either 3 or 4 rows (not 1, 2, or 5+).
- **Localised digit support persists in two places now.** For
  4-row layouts, the digits are baked into the file directly per
  locale. For 3-row layouts, the digit-hint fallback honours
  `PREF_LOCALIZED_NUMBER_ROW` / the per-subtype extra value.
  Slightly more surface area than I'd like but each piece does
  one clear thing.
- **`KeyboardId` no longer carries `mNumberRowEnabled`.** Cache
  keys collapse — fewer distinct keyboard instances cached.
  Probably a tiny perf win.

### What the merged step list looks like for A.3

Replaces step 3-4 in A.15. See A.15 below for the final wording.

## A.4 Significant — Migration is more delicate than Section 4 step 6 implies

**Body claim being revised:** Section 3.3 / Section 4 step 6.

**Issues with the original sketch:**

- **`["*"]` wildcards × all enabled subtypes** would generate one
  additional subtype per enabled locale. For a user with 8
  languages enabled, that's 8 new subtypes. Wrong.
- **Multiple presets matching the same locale** (the seed has
  `English` *and* `Compact English (no number row)` both with
  `["en"]`) would each become an additional subtype. The user
  probably wants both available but not both enabled by default.
- **`PREF_USE_CUSTOM_KEYBOARDS = false`** users still have a JSON
  document sitting in prefs; do we migrate it? They never used it.
- **Validation failures** in the user's JSON shouldn't lose data.
- **Direct-boot:** `<filesDir>` is in device-protected storage
  (`DeviceProtectedUtils.getFilesDir`), the prefs are in
  device-protected storage too, so timing should be fine — but
  migration must run *before* `SubtypeSettings.removeMissingLayouts`
  on the first launch after upgrade.

**Replacement plan:**

1. **One-shot, idempotent migration in `AppUpgrade.kt`** keyed on a
   new pref `PREF_CUSTOM_KEYBOARDS_MIGRATED` (default `false`):
   - Read `PREF_CUSTOM_KEYBOARDS_JSON`. If it's empty / unparsable,
     just delete the prefs and set the marker.
   - Translate each preset to **rich JSON files** (one per slot)
     under `<filesDir>/layouts/<slot>/custom.<scope>.<base36>.`
     where `<scope>` is:
     - `Latn` for `["*"]` and for any preset whose `locales` are all
       Latin-script (matches existing `custom.Latn.*` convention).
     - The locale's BCP-47 tag for any preset whose first locale is
       a non-Latin tag (matches existing
       `custom.<lang-Region>.*` convention).
   - Display name: the preset's `name`, or `Imported preset #i` if
     blank. If the resulting layout filename already exists, append
     ` (imported)` to the display name to avoid clobbering existing
     custom layouts.
   - Do **not** auto-enable subtypes for `["*"]` presets.
   - For presets with explicit locales: if `PREF_USE_CUSTOM_KEYBOARDS`
     was `true`, create one additional subtype per (preset, locale)
     pair, but enable only the first one per locale. Show a one-shot
     in-app notification (toast or banner on next IME visibility)
     pointing the user to *Languages & Layouts* with the message
     "Your custom keyboards have been migrated; see Main layout
     dropdowns on each language."
   - If `PREF_USE_CUSTOM_KEYBOARDS` was `false`, just write the
     layout files and skip subtype creation entirely. The user can
     pick them later.
   - On success, set `PREF_CUSTOM_KEYBOARDS_MIGRATED = true`, delete
     `PREF_CUSTOM_KEYBOARDS_JSON` and `PREF_USE_CUSTOM_KEYBOARDS`.
   - On failure (`SerializationException`, file write
     `IOException`), keep the prefs, leave the marker `false`, log,
     and retry next launch.

2. **Test fixtures:** add JVM tests under `app/src/test/` covering:
   - default seeded JSON (4 presets) → expected file set and
     subtype-extra-values strings;
   - empty/blank JSON → no-op, marker set;
   - malformed JSON → no-op, marker `false`, retry;
   - JSON with `\|` escapes → file labels contain literal `|`;
   - filename collision → suffixed display name;
   - re-running migration after marker = true → no-op.

3. **Direct-boot:** schedule migration from `AppUpgrade` *after*
   `Settings.init` and *before*
   `SubtypeSettings.removeMissingLayouts`, so the latter doesn't
   prune subtypes that point at not-yet-written custom layouts.

## A.5 Significant — Non-MAIN slot UI parity (Section 4 step 3) needs more spec

**Body claim:** Section 4 step 3 extracts a `LayoutSlotEditor`
composable and reuses it for every `LayoutType` slot.

**What the existing code actually does:** In `SubtypeScreen.kt` lines
241-289, every non-MAIN slot already has a `DropDownField` showing
built-in + custom layouts and an inline pencil button on each custom
entry that opens `LayoutEditDialog`. What's missing:

- **No "+ add new" button** for non-MAIN slots.
- **No bin/delete button** for non-MAIN slots.
- **No "Edit a copy" entry** for non-MAIN slots (the user can't
  fork the built-in symbols layout into an editable copy).
- The MAIN row's add-flow (`MainLayoutRow`'s `showAddLayoutDialog`)
  also offers **load from file** (`layoutFilePicker`), which the
  non-MAIN slots also lack.

**Replacement plan (definitive):**

Extract `MainLayoutRow` (`SubtypeScreen.kt:401-504`) into a generic
`LayoutSlotEditor(slotType, currentSubtype, customLayouts, ...)`
composable. Differences per `LayoutType`:

| Slot | Filter custom files by locale? | "Add from built-in copy" base list |
| --- | --- | --- |
| `MAIN` | yes, by script/locale (existing) | per-locale layouts |
| All others | no | all built-in layouts of that slot |

Use the same composable for MAIN and non-MAIN. The slot-type
distinction lives in two small lookups: `LayoutUtilsCustom.getLayoutFiles`
already takes an optional `locale` parameter, and
`LayoutUtils.getAvailableLayouts(type, ctx, locale = null)` already
returns the global pool for non-MAIN.

Drop unused per-slot toggle UI we currently render around the
dropdowns (e.g. the *number row* localized toggle should stay
gated by `hasLocalizedNumberRow(locale)`).

## A.6 Significant — The "Add custom layout" wizard from Section 4 step 5 should be on `SubtypeScreen`, not `LanguageScreen`

**Body claim:** Section 4 step 5 adds the wizard at the top of
`LanguageScreen`.

**Why move it:** A "custom layout" is always bound to a *subtype*'s
locale + slot. Adding a discovery affordance on the *language list*
forces the user to answer "which language?" up front, which is a
mode question, not a creation question. Worse, if the user then
realizes they want a different language they have to start over.

The natural flow is:

1. *Languages & Layouts* → tap the language (existing behavior).
2. *Subtype detail screen* → tap **Main layout +** (already
   exists for MAIN; A.5 makes it exist for every slot too).
3. The dialog already lets them seed from any base layout
   (handled by `MainLayoutRow.showLayoutEditDialog` → `startContent`
   path which uses `LayoutUtils.getContentWithPlus`).

**Replacement plan:**

- Drop step 5 from Section 4.
- The `LanguageScreen` change is reduced to a small affordance: if
  the user is searching and there's no result, offer a "Configure
  custom layouts → tap a language above and use the **+** in the
  Main layout dropdown" hint string. No new wizard.
- The "Edit a copy" / "Make editable copy" button proposed in
  Section 4 step 4 becomes the *only* discovery affordance for
  built-in layouts. It surfaces as a small icon next to a built-in
  entry in the dropdown — a pencil with a `+` overlay — and clicks
  go straight into `LayoutEditDialog` with `startContent` from the
  current selection. This is already 80% of step 4 today; the new
  piece is the icon and the auto-naming default ("`<base name> (copy)`").

## A.7 Minor — Custom-subtype name override

`SubtypeUtilsAdditional.createAdditionalSubtype` already calls
`builder.setSubtypeNameOverride(LayoutUtilsCustom.getDisplayName(mainLayoutName))`
on Android 14+ when the MAIN layout is custom. After the merge,
imported PR #113 presets carry their human-readable name through to
the subtype label automatically. No additional code needed — but
worth listing in tests so the migration produces names like *French
(My custom)* and not *French (custom.fr-FR.bWlnXyMxMTM.)*.

## A.8 Minor — Spell-check / dictionary availability for new custom subtypes

If migration creates additional subtypes for locales that have no
bundled dictionary, the existing `dictsAvailable` check in
`LanguageScreen.kt` will show `MissingDictionaryDialog`. The
migration should not pre-warn the user — the existing dialog is
enough — but it should *not* try to create subtypes for locales the
user hasn't enabled. Migration should only operate on locales that
already appear in `PREF_ENABLED_SUBTYPES`.

## A.9 Minor — Layouts ending in `+` (locale extra keys)

`LayoutUtils.getContentWithPlus(layoutName, locale, ctx)` is the
canonical "expand `+` extras into the file content" helper. When the
"Edit a copy" affordance forks a built-in `qwerty+`, the new custom
file gets the *materialised* extras for the current locale. That's
correct, but means the custom layout will not auto-update if the
user later switches that subtype to a different locale and copies
the same custom layout there. The wizard should note this in a
single-line caption: *"Locale-specific extra keys are frozen into
the copy."*

## A.10 Minor — `KeyboardLayoutSet` cache invalidation

PR #113 added invalidation hooks on `PREF_USE_CUSTOM_KEYBOARDS` and
`PREF_CUSTOM_KEYBOARDS_JSON` in `Settings.onSharedPreferenceChanged`
(per the existing AGENTS note in `latin/settings/`). After deletion,
those hooks need to be removed and the existing per-layout cache
invalidation in `LayoutUtilsCustom.onLayoutFileChanged()` /
`KeyboardSwitcher.setThemeNeedsReload()` is sufficient — it's already
called by `LayoutEditDialog` and `LayoutUtilsCustom.deleteLayout`.

## A.11 Minor — `LayoutParser` synthetic cache key

The synthetic cache entry
`"__custom_keyboards__:${preset.hashCode()}:${locale.toLanguageTag()}:${customSlot.name}"`
goes away with the preset path. Confirm no other code reads that
shape (none does — it's purely internal to `LayoutParser`). The
existing per-layout cache key `layoutType.name + layoutName` already
covers the custom-file case because filenames embed the locale tag.

## A.12 Minor — Toolbar "cycle preset" button (referenced in PR #113 scope notes)

PR #113's scope notes mentioned a future toolbar action calling
`CustomKeyboards.cycleActive`. After the merge there is no `active`
index. Equivalent behaviour for the user is "globe key cycles
enabled subtypes", which already exists. If we want a dedicated
"next alphabet variant for *this* language" button, that is a
follow-up — implement it by ordering additional subtypes for the
same locale and selecting the next one via
`SubtypeSettings.setSelectedSubtype`. Out of scope for the merge.

## A.13 Minor — Validation surface area

`LayoutUtilsCustom.checkLayout` already validates row counts (>=1,
<=8), key count per row (<=20), label/popup string length, etc.
After the merge:

- **MAIN / SYMBOLS / MORE_SYMBOLS must have 3 or 4 rows.** Either
  the file declares the number row (4 rows) or it omits it and
  the parser supplies digit hints (3 rows). Other row counts are
  parser-supported but UX-confusing for these slots; treat as a
  soft warning rather than a hard error so power users aren't
  blocked.
- **Reject extreme cases.** 0 rows, > 6 rows, > 15 keys per row
  for MAIN should remain hard errors.
- **Per-key popup-as-hint length.** When `KeyboardView` draws the
  hint label, the string is single-line and clipped to roughly 5
  visual characters (see `.cursor/skills/key-hint-sizing/SKILL.md`).
  Add a soft warning in `LayoutEditDialog` when any top-row
  popup-as-hint is longer than 5 chars. Don't reject — non-Latin
  scripts can have wider glyphs that look fine at 1-2 chars but
  trigger Latin-length intuitions.

Tests to add:

- 4-row MAIN custom layout renders 4 rows, top row keys carry the
  digit labels themselves.
- 3-row MAIN custom layout renders 3 rows with `popup.numberLabel`
  set on the top row via `addNumberRowOrPopupKeys`.
- 5-row MAIN custom layout is accepted with a soft warning and
  renders with `heightRescale = 4f/5`.
- Round-trip a custom JSON layout through `LayoutEditDialog`.

## A.14 Minor — Test coverage expectations

After the merge, JVM tests under `app/src/test/` should cover:

- Migration produces deterministic filenames for the four seeded
  presets.
- A subtype's `mainLayoutName()` pointing at a deleted custom
  layout still falls back via `SubtypeSettings.onRenameLayout`.
- A `Latn`-scoped custom layout shows up in every Latin subtype's
  MAIN dropdown and is hidden from non-Latin subtypes.
- A `hint`-bearing custom JSON renders an off-center hint label
  (assert via `KeyParams.mHintLabel`, not Compose rendering).

## A.15 Revised step list (replaces Section 4)

This is the merged, corrected step list after the maintainer
review of A.1, A.2, A.3. Each step is independently shippable.

1. **(assets) Bake the number row into every built-in MAIN,
   SYMBOLS, and MORE_SYMBOLS layout file** under
   `app/src/main/assets/layouts/`. Use the locale-appropriate
   digit set (table in A.3 step 1). Carry the locale's current
   number-row hints (first popup per key) into the file as the
   first popup of each digit. Built-ins default to 4 rows; 3-row
   layouts remain supported and may be added later as compact
   variants. JVM test: every `main/*.{txt,json}` and
   `{symbols,more_symbols}/*` has **3 or 4** rows (default 4).

2. **(parser) Delete the runtime number-row prepend; flip the
   3-row fallback trigger; flip the symbol hint flag.** In
   `KeyboardParser.kt`:
   - Remove the `customKeyboardsActive` branches and the
     `baseKeys.add(0, numberRow.mapTo(...))` injection in the
     `mNumberRowEnabled` branch.
   - Flip `addNumberRowOrPopupKeys`'s guard from
     `!params.mId.mNumberRowEnabled` to `baseKeys.size == 3`
     (for alphabet boards). Drop its `customKeyboardsActive`
     early-return. The function continues to set
     `popup.numberLabel` so 3-row layouts get digit hints.
   - Replace the `customKeyboardsActive` hint-flag branch (line
     49) with `LayoutUtilsCustom.isCustomLayout(layoutName)` so
     user-edited symbol layouts show their first popup as a hint.
   - Drop `mShowsNumberRow`, `mNumberRowEnabled`,
     `setNumberRowEnabled`, and their callers in
     `KeyboardLayoutSet`, `KeyboardSwitcher`, `KeyboardId`,
     `KeyboardBuilder`, `EmojiLayoutParams`,
     `ClipboardLayoutParams`, `SettingsValues`. Touching all
     these is mostly delete-and-let-the-compiler-find-the-callers.
   - Keep `getNumberRow()` and the
     `assets/layouts/number_row/` assets. They are still the
     content source for the 3-row digit-hint fallback.

3. **(prefs) `PREF_LOCALIZED_NUMBER_ROW` stays, scope narrowed.**
   The toggle continues to swap Western → localised digits, but
   only on the 3-row digit-hint fallback (since 4-row layouts
   bake their digits into the file). Update the description
   string in `SubtypeScreen.kt:212-235` to match the new
   narrower behaviour, e.g. *"Show localised digits as number
   hints when the keyboard has no number row"*. The per-subtype
   `LOCALIZED_NUMBER_ROW` extra value and the
   `hasLocalizedNumberRow(...)` helper survive unchanged.

4. **(UI) Extract `MainLayoutRow` into `LayoutSlotEditor` and
   reuse for every slot in `SubtypeScreen`.** Adds Add / Edit /
   Delete / Load-from-file affordances for SYMBOLS, MORE_SYMBOLS,
   FUNCTIONAL, etc. Includes "Edit a copy" (pencil-with-plus) icon
   on built-in entries that pre-fills `LayoutEditDialog` with the
   built-in file's contents (now including the number row).

5. **(UI) `LanguageScreen` hint string.** When search yields no
   results, render a single-line hint pointing the user at the
   subtype detail screen for layout customisation. No standalone
   wizard.

6. **(migration) One-shot migration in `AppUpgrade.kt`.** Guarded
   by `PREF_CUSTOM_KEYBOARDS_MIGRATED`. For each preset:
   - Call `CustomKeyboards.toSimpleLayoutText(preset, slot)` to
     produce a plain simple-text body. **Preserve the preset's
     original row count** — a 3-row preset becomes a 3-row
     custom layout file (parser will supply digit hints), a
     4-row preset becomes a 4-row layout file. Do **not** pad
     up or trim.
   - Write the file to
     `<filesDir>/layouts/<slot>/custom.<scope>.<base36>.`
     using `Latn` as the scope for `["*"]` presets, `Latn` for
     any preset whose locales are all Latin, and the
     BCP-47 locale tag for non-Latin presets.
   - Resolve filename collisions by appending `(imported)` to the
     display name.
   - If `PREF_USE_CUSTOM_KEYBOARDS` was `true`, also create
     additional subtypes pointing at the migrated layout for each
     enabled locale that the preset's `locales` list explicitly
     names. Enable them. Skip subtype creation for `["*"]`
     wildcard presets.
   - On success: set marker, delete `PREF_CUSTOM_KEYBOARDS_JSON`
     and `PREF_USE_CUSTOM_KEYBOARDS`.

7. **(cleanup) Delete the parallel system.** Remove
   `latin/utils/CustomKeyboards.kt` (except the
   `toSimpleLayoutText`/`splitKeyToken` helpers, which the
   migrator briefly needs — move them inline into `AppUpgrade.kt`
   or a tiny `CustomKeyboardsMigration.kt` file, then delete
   the rest); `settings/screens/CustomKeyboardsScreen.kt`; the
   override branch in
   `keyboard/internal/keyboard_parser/LayoutParser.kt`; the
   `customKeyboardsActive` paths in `KeyboardParser.kt`; the
   `Settings.onSharedPreferenceChanged` hooks for the deleted
   prefs; the MainSettingsScreen entry; the `SettingsNavHost`
   destination; the seed JSON in `Defaults.kt`; the
   `PREF_USE_CUSTOM_KEYBOARDS` / `PREF_CUSTOM_KEYBOARDS_JSON`
   constants. Update AGENTS.md notes in `latin/utils`,
   `latin/settings`, `keyboard/internal/keyboard_parser`,
   `settings/screens`, `assets/layouts`.

8. **(tests) JVM tests** covering:
   - Every built-in alphabet/symbol layout has 3 or 4 rows.
   - A 4-row MAIN custom layout renders 4 rows where the top row
     keys carry the digit labels themselves.
   - A 3-row MAIN custom layout renders 3 rows where the parser
     sets `popup.numberLabel` on the top row via the new
     `baseKeys.size == 3` trigger in `addNumberRowOrPopupKeys`.
   - The compact `Compact English (no number row)` preset from
     PR #113 round-trips through migration as a 3-row custom
     layout and still renders 3 rows with digit hints.
   - Migration of the seeded 4-preset JSON produces deterministic
     filenames and the expected file contents.
   - Migration handles `\|` and `\\` escapes correctly.
   - Migration handles `["*"]` wildcards (writes one `Latn` file,
     no subtypes).
   - Migration handles missing/blank/malformed JSON without
     setting the marker.
   - Re-running migration with the marker set is a no-op.
   - A `Latn`-scoped custom layout appears in every Latin
     subtype's MAIN dropdown and is hidden from non-Latin
     subtypes.
   - A custom symbol layout shows its first popup as a hint
     (because `isCustomLayout` is true and the
     `LABEL_FLAGS_DISABLE_HINT_LABEL` branch is suppressed).

9. **(release) Rebuild the canonical APK** with
   `./tools/build-dist-apk.sh`.

## A.16 Updated TL;DR

The body's TL;DR stands. The three "critical" issues from the
review are resolved as follows:

- **A.1 (hint storage):** the existing format already encodes hints
  implicitly as "first popup of the key". Migration translates PR
  #113 rows into ordinary simple-text using the existing
  `toSimpleLayoutText` helper; no new schema, no `|` grammar
  change, no `hint` JSON field. The only parser tweak is replacing
  the `customKeyboardsActive` hint-flag conditional with
  `isCustomLayout(layoutName)` so user-edited symbol layouts show
  authored popups as hints.
- **A.2 (any scope):** dropped. The existing `custom.Latn.*`
  convention already serves as the wildcard for every Latin
  subtype, and non-MAIN custom layouts are already universal by
  construction.
- **A.3 (number row):** the layout file is the source of truth.
  Built-ins are baked to 4 rows so a fork has a number row to
  edit. The parser supports **both** 3-row and 4-row MAIN files:
  4 rows = top row is the number row; 3 rows = top alphabet row
  gets auto-generated digit hints via the existing
  `addNumberRowOrPopupKeys` mechanism (whose trigger flips from
  `!mNumberRowEnabled` to `baseKeys.size == 3`). The runtime
  prepend logic and the global `mShowsNumberRow`/`mNumberRowEnabled`
  plumbing are deleted. `PREF_LOCALIZED_NUMBER_ROW` survives with
  a narrowed scope — it still applies to the 3-row digit-hint
  fallback. Users who want no number row fork to 3 rows; users
  who want different digits fork the file.

Section 5's open questions and A.4-A.14 minor items still apply.
Recommendations for those follow in A.17.

## A.17 Recommendations on the remaining (A.4-A.14) items

The maintainer asked for advice on each. Short form:

- **A.4 Migration** — keep the spec as written. Idempotency marker
  pref + atomic-with-retry is the only safe pattern given that
  layout-file writes can fail on full storage / direct-boot timing.
  *Recommend: implement exactly as A.4 describes, with the
  A.15 step 6 simplifications (no JSON, no `hint` field needed
  since A.1 collapsed).*

- **A.5 Non-MAIN slot UI parity** — *recommend: do it.* This is the
  highest-value UX change of the whole merge. Users authoring a
  *Programming* custom MAIN layout almost always want a matching
  custom SYMBOLS too; without parity they hit a wall the moment
  they tap `?123`. Cost is low (extract one composable, reuse for
  every slot in `SubtypeScreen.kt`).

- **A.6 Wizard location** — *recommend: drop the standalone
  wizard from `LanguageScreen` entirely.* By the time the user
  knows what locale they want a custom layout for, they're already
  one tap deep into `SubtypeScreen`. Surfacing creation there
  (via the Add button on the dropdown that already exists for MAIN
  and gets extended to non-MAIN by A.5) is enough. The only
  addition on `LanguageScreen` should be the empty-search hint
  string.

- **A.7 Subtype name override** — *recommend: don't add code.* The
  existing `setSubtypeNameOverride` on Android 14+ already does
  the right thing once the migrated layout is named via
  `LayoutUtilsCustom.getDisplayName`. Just add a test to confirm
  the migration produces friendly names.

- **A.8 Dictionary availability** — *recommend: leave as-is.* The
  existing `MissingDictionaryDialog` flow is correct. Migration
  should not pre-warn (would be noisy) and should not auto-create
  subtypes for locales the user hasn't enabled (already in the
  A.4 spec).

- **A.9 Frozen `+` extras** — *recommend: add a one-line caption
  to the Edit dialog* when the source layout name ends with `+`:
  *"Locale-specific extra keys are frozen into the copy."*. No
  code logic change, just a UI string. Low cost, prevents
  confused-user reports.

- **A.10 Cache invalidation** — *recommend: just remove the
  PR-#113-added hooks in `Settings.onSharedPreferenceChanged`.*
  The existing `LayoutUtilsCustom.onLayoutFileChanged` /
  `KeyboardSwitcher.setThemeNeedsReload` covers all relevant
  changes after the merge. Verify by writing a custom layout via
  `LayoutEditDialog` and confirming the next IME show reflects it.

- **A.11 Synthetic cache key** — *recommend: nothing to do.* The
  preset cache key disappears with `CustomKeyboards.kt`. The
  existing per-`layoutName` cache key in `LayoutParser.layoutCache`
  already keys on the custom filename (which embeds the locale),
  so it's correct as-is.

- **A.12 Toolbar cycle button** — *recommend: punt.* The merge
  already gives users a way to switch alphabets per locale by
  enabling multiple additional subtypes for the same locale; the
  globe key cycles them. A dedicated "next alphabet variant"
  toolbar button is a small standalone follow-up issue. Don't
  block on it.

- **A.13 Validation** — *recommend: extend
  `LayoutUtilsCustom.checkLayout` with two checks:* (i) MAIN /
  SYMBOLS / MORE_SYMBOLS files must have exactly 4 rows after
  A.3; (ii) any popup-as-hint string must be ≤5 characters
  visually (matches the existing key-hint-sizing skill in
  `.cursor/skills/key-hint-sizing/SKILL.md`). Surface both as
  inline errors in `LayoutEditDialog`.

- **A.14 Test coverage** — *recommend: add the tests listed in
  A.15 step 8.* They are cheap to write (JVM, no Robolectric
  needed for most) and catch every regression a future refactor
  is likely to introduce.

### Order recommendation

If implementing incrementally over several PRs, this is a
sensible order (each PR shippable on its own):

1. **PR #X:** A.15 steps 1, 8 (assets + tests). This is the
   biggest patch by line count but the lowest-risk: it adds
   number rows to every built-in layout and adds the validation
   test. The runtime still injects a number row on top, so
   you'd see *two* number rows during this PR — temporarily fine
   if the PR also flips the injection off behind a flag, but
   simplest is to land 1 and 2 together.

2. **PR #X+1:** A.15 steps 2, 3 (parser cleanup). Delete the
   runtime number-row prepend and `mShowsNumberRow` /
   `mNumberRowEnabled` plumbing; flip
   `addNumberRowOrPopupKeys`'s trigger to `baseKeys.size == 3`;
   flip the symbol hint flag to `isCustomLayout(layoutName)`.
   Narrow `PREF_LOCALIZED_NUMBER_ROW`'s scope and description.
   This is the "remove machinery" PR.

3. **PR #X+2:** A.15 step 4, 5 (UI). Extract `LayoutSlotEditor`,
   wire it into every slot, add empty-search hint on
   `LanguageScreen`.

4. **PR #X+3:** A.15 steps 6, 7 (migration + cleanup). Delete
   `CustomKeyboards.kt`, the `CustomKeyboardsScreen`, prefs,
   override branches, AGENTS notes.

5. **PR #X+4:** A.15 step 9 (APK rebuild).

Each PR is bounded, reviewable, and rolls back independently.
PR #X+3 is the only one with user-visible state migration; the
others are mechanical refactors.

