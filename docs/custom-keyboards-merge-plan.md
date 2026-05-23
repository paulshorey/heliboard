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
