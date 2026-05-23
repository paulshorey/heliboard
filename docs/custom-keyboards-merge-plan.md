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

## A.1 Blocker — `|` is already overloaded in the simple-text format

**Body claim being revised:** Section 3.2 item 1 and Section 4 step 1
propose extending the simple-text row format with a `primary|hint`
token convention by lifting `CustomKeyboards.splitKeyToken` into
`LayoutParser.parseKey`.

**Why it's wrong:** The vertical bar is *already* the canonical
delimiter inside a single key spec, parsed by
`keyboard/internal/KeySpecParser.java`:

> Each key specification is one of the following:
> - Label optionally followed by keyOutputText (`keyLabel|keyOutputText`).
> - Label optionally followed by code point (`keyLabel|!code/code_name`).
> - Icon followed by keyOutputText (`!icon/icon_name|keyOutputText`).
> - Icon followed by code point (`!icon/icon_name|!code/code_name`).
> Special character, comma ',' backslash '\\', and bar '|' can be
> escaped by '\\' character.

Stock built-in layouts use this all over the place:

```
app/src/main/assets/layouts/main/qwerty.txt:
  q Tab|!code/key_tab          # primary q, popup labelled Tab that emits a Tab
  t |                          # primary t, popup is a literal '|' character
app/src/main/assets/layouts/main/central_kurdish.txt:
  ھ|ه                          # primary 'ھ' with a code/output spec
app/src/main/assets/layouts/symbols/symbols_arabic.txt:
  |                            # a key whose label is a literal '|'
```

`TextKeyData.getPopupLabel` then *generates* `label|!code/...` strings
when computing popup keys
(`TextKeyData.kt` lines 246-252, `POPUP_KEYS_NAVIGATE_*`,
`createActionPopupKeys`, etc.). Reusing `|` as a primary/hint
separator in the same format would silently re-route every one of
these into the hint path and break every layout that uses a popup
spec with a label.

**Replacement plan (this supersedes Section 4 step 1):**

1. **Do not change the simple-text grammar.** Built-in `.txt` layouts
   and `LayoutParser.parseSimpleString` stay exactly as they are.
   The `KeySpecParser` semantics of `label|output` stay exactly as
   they are.
2. **Add an explicit `hint` field to rich JSON layouts.** In
   `keyboard/internal/keyboard_parser/floris/TextKeyData.kt`, extend
   the `TextKeyData` data class with `val hint: String? = null`
   (`@SerialName("hint")`). Propagate it through `copy(...)`,
   `compute(params)`, and `toKeyParams(params, ...)` so that
   `Key.KeyParams.mHintLabel` is set when `hint != null`. Make sure
   `LABEL_FLAGS_DISABLE_HINT_LABEL` is *not* applied when the layout
   explicitly declared a hint (this replaces the conditional
   `customKeyboardsActive` branch at `KeyboardParser.kt:49` with a
   per-key check).
3. **The migration writes JSON, not extended simple-text.**
   `AppUpgrade.kt` converts each PR-#113 preset slot into one rich
   JSON file under `<filesDir>/layouts/<slot>/custom.<scope>.<base36>.`:

```json
[
  [ { "label": "1" }, { "label": "2" }, ... ],
  [ { "label": "q" }, { "label": "w" }, ... ],
  [ { "label": "a", "hint": "@", "popup": { "main": { "label": "@" } } },
    { "label": "s", "hint": "#", "popup": { "main": { "label": "#" } } }, ... ],
  ...
]
```

   The user-facing "primary|hint" ergonomics survive (they edit the
   compact form, the migration writes the rich form). New custom
   layouts created from the *Add custom layout* wizard get the same
   rich JSON shape on save.
4. **Editor authoring affordance (optional, deferred):** if we still
   want the compact "primary|hint" authoring syntax inside
   `LayoutEditDialog`, expose it as a *third* format **layered
   above** the existing parser — i.e. a "compact hints" parse pass
   that lives next to `parseJsonString`/`parseSimpleString`, has its
   own file-content header (e.g. a magic first line like
   `// format: hint-rows-v1`), and is only chosen when that header
   is present. It is *never* picked by accident on a stock
   layout. This is strictly optional; the JSON form is the source
   of truth.

Net effect: no built-in layout's parsing changes, no existing custom
layout breaks on upgrade, and the new feature's hint ergonomics are
preserved through migration to JSON. The body of the doc should be
read as "we want hints to survive"; *this appendix* defines the
mechanism.

## A.2 Major — The "any" scope is mostly redundant

**Body claim being revised:** Section 3.2 item 2 and Section 4 step 2
propose adding an `any` scope to `LayoutUtilsCustom` so a custom
layout can apply to "any language".

**What's already true:**
`LayoutUtilsCustom.getLayoutFiles(LayoutType.MAIN, ctx, locale)`
already returns *all* `custom.Latn.*` layouts when the locale is any
Latin-script locale. So a Latin custom layout already behaves as a
wildcard for every Latin-script subtype the user enables. The new
feature's `"*"` wildcard mainly differs by also being eligible for
non-Latin scripts — but a *Programming* keyboard with Latin labels
doesn't actually make sense to expose under e.g. Bengali or Khmer.

**Replacement plan:**

- Do **not** add a new `any` sentinel. Instead, document the
  existing Latin-wildcard behavior in the *Add custom layout*
  wizard so the user knows that creating a Latin-script custom
  layout makes it available in all their Latin subtypes.
- For SYMBOLS, MORE_SYMBOLS, FUNCTIONAL, NUMBER, NUMBER_ROW,
  PHONE, etc., `getLayoutFiles` already ignores locale — the
  layout file is unfiltered. So a user-created `custom.<base36>.`
  in any of those slots is already "any language" by construction.
  No code change required.
- Migration translates `"locales": ["*"]` presets in PR #113's JSON
  to a Latin-script MAIN file (since the seeded *Programming*
  preset uses Latin characters). Skip migrating the wildcard for
  non-Latin subtypes; if the user wants the Programming layout on
  a non-Latin subtype, they can re-create it.

This removes Section 4 step 2 entirely and removes the `LayoutUtilsCustom`
schema extension.

## A.3 Major — Number-row regression after deletion of PR #113

**Body claim being revised:** Section 5 lists this as an open
question.

**Concrete situation:** PR #113 deleted `PREF_SHOW_NUMBER_ROW` (and
the related `PREF_SHOW_NUMBER_ROW_IN_SYMBOLS`) and hardcoded
`SettingsValues.mShowsNumberRow = true`. Today, the only way for a
user to hide the number row on a given alphabet keyboard is to use a
3-row Custom-keyboards preset, because there is no longer a global
or per-subtype "hide number row" toggle that applies to the stock
path. After the merge deletes the preset path, every alphabet
keyboard will *always* show the number row.

**Replacement plan (definitive):**

1. **Keep `SettingsValues.mShowsNumberRow` field for ABI / call-site
   stability, but** make it source from a restored preference key
   `PREF_SHOW_NUMBER_ROW` (default `true`). Restore the toggle in
   `AppearanceScreen.kt` under *Size and layout*. This is a literal
   revert of the relevant part of PR #115's removal.
2. **Generalize "layout file decides number row" to all MAIN
   layouts**, built-in or custom. In `KeyboardParser.kt`, when the
   parsed MAIN layout already has 4 rows of body keys (i.e. the
   layout file *includes* a top number-row), do not also prepend
   the global number row even if `mNumberRowEnabled` is true.
   Detect this by counting `baseKeys.size` before the
   `if (...mNumberRowEnabled)` branch. This subsumes the
   `customKeyboardsActive` check.
3. **3-row built-in layouts and 3-row custom layouts unchanged:**
   the global number-row toggle (or per-subtype
   `LOCALIZED_NUMBER_ROW` extra value, which already exists) governs
   whether the global number row is prepended. This matches stock
   HeliBoard behavior pre-PR-#113.
4. **A user who wants "no number row" for one language** unchecks
   the per-subtype localized-number-row switch on `SubtypeScreen`
   (already there). A user who wants "no number row anywhere"
   toggles the restored `PREF_SHOW_NUMBER_ROW`.

This makes the merge a strict superset of both pre-PR-#113 and
post-PR-#113 capability.

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

`LayoutUtilsCustom.checkLayout` already validates row counts, key
count, label/popup string length, etc. Tests we should add
alongside the migration:

- A 4-row MAIN layout file with the top row being numbers triggers
  the "no number row injection" branch in A.3 step 2.
- A `hint`-bearing JSON file round-trips through `LayoutEditDialog`.
- A `hint` value that is more than 5 characters long is rejected.

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

This is the merged, corrected step list. Each step is independently
shippable.

1. **(parser) Add `hint: String?` to rich JSON `TextKeyData`.**
   Propagate through `copy`, `compute`, `toKeyParams`. Update
   `KeyboardParser` so a key with a non-null `hint` keeps its hint
   label even on symbol layouts. JSON-only — no simple-text grammar
   change.

2. **(parser, optional/deferred) Authoring helper for hints in
   `LayoutEditDialog`.** If we want users to type
   `a|@ s|# d|$ …` and have the dialog save it as JSON with `hint`
   fields, add a converter that runs only when the input starts
   with the magic header `// format: hint-rows-v1`. Otherwise leave
   the existing simple-text and JSON parsing untouched.

3. **(parser) Generalise number-row injection.** In
   `KeyboardParser.parseCoreLayout` (the
   `mNumberRowEnabled` branch), skip the prepend when the parsed
   MAIN layout already has 4 rows. Remove the
   `customKeyboardsActive` check.

4. **(prefs) Restore `PREF_SHOW_NUMBER_ROW` (boolean, default
   `true`).** Re-add the *Show number row* toggle to
   `AppearanceScreen.kt`. Wire `SettingsValues.mShowsNumberRow` to
   it. Keep the existing per-subtype `LOCALIZED_NUMBER_ROW`
   override.

5. **(UI) Extract `MainLayoutRow` into `LayoutSlotEditor` and reuse
   for every slot in `SubtypeScreen`.** Adds Add / Edit / Delete /
   Load-from-file affordances for SYMBOLS, MORE_SYMBOLS, FUNCTIONAL,
   etc. Includes "Edit a copy" (pencil-with-plus) icon on built-in
   entries.

6. **(UI) `LanguageScreen` hint string.** When search yields no
   results, render a single-line hint pointing the user at the
   subtype detail screen for layout customisation. No wizard.

7. **(migration) One-shot migration in `AppUpgrade.kt`.** Guarded by
   `PREF_CUSTOM_KEYBOARDS_MIGRATED`. Writes one rich-JSON file per
   slot per preset using the `hint` field from step 1. Creates
   additional subtypes only for presets with explicit locales and
   only when `PREF_USE_CUSTOM_KEYBOARDS` was `true`. Deletes
   `PREF_CUSTOM_KEYBOARDS_JSON` and `PREF_USE_CUSTOM_KEYBOARDS` on
   success.

8. **(cleanup) Delete the parallel system.** Remove
   `latin/utils/CustomKeyboards.kt`,
   `settings/screens/CustomKeyboardsScreen.kt`, the
   `customKeyboardsActive` paths in
   `keyboard/internal/keyboard_parser/{KeyboardParser,LayoutParser}.kt`,
   the `Settings.onSharedPreferenceChanged` hooks for the deleted
   prefs, the MainSettingsScreen entry, the `SettingsNavHost`
   destination, the seed JSON in `Defaults.kt`, the
   `PREF_USE_CUSTOM_KEYBOARDS` / `PREF_CUSTOM_KEYBOARDS_JSON`
   constants. Update AGENTS.md notes in `latin/utils`,
   `latin/settings`, `keyboard/internal/keyboard_parser`,
   `settings/screens`.

9. **(tests) Add JVM tests** covering A.4, A.13, A.14.

10. **(release) Rebuild the canonical APK** with
    `./tools/build-dist-apk.sh`.

## A.16 Updated TL;DR

The body's TL;DR stands. The mechanism for "lift the `primary|hint`
ergonomics into the shared parser" is now: **add a JSON `hint`
field**, not "extend simple-text with `|`". Section 5's
"open question" about number-row handling is resolved in A.3 by
restoring `PREF_SHOW_NUMBER_ROW` and generalising the 4-row layout
detection to all MAIN layouts. The wizard from step 5 is dropped in
favor of finishing the per-slot editor on `SubtypeScreen`.

