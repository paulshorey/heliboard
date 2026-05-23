# HeliBoard User-Editable Keyboard Layouts — Implementation Plan

> This document serves as the high-level Overview and Index. Follow the sub-plans sequentially.

---

## 1. The User Goal

A HeliBoard user should be able to **create a new keyboard layout of their own** by starting from an existing locale's layout (e.g. English/QWERTY, Russian, Bengali, …), then **edit the key characters, the long-press popups, the small grey hint label above each key, and the number row** as one coherent, visual unit.

Concretely, this delivers:

1. **Universal Customization Parity:** Every single keyboard layout slot (`MAIN`, `SYMBOLS`, `MORE_SYMBOLS`, `FUNCTIONAL`, `NUMBER`, `NUMBER_ROW`, `NUMPAD`, etc.) has identical **Add / Edit / Delete / Import / Fork** controls.
2. **One-Tap Forking ("Edit a copy"):** Tapping a fork button next to any built-in layout (such as `qwerty` or `russian`) creates a custom layout pre-filled with that layout's exact character structure, names it `<original>-copy`, saves it, and immediately selects it.
3. **WYSIWYG Layout Editing:** The number row is baked into built-in layout files instead of being prepended at runtime. Editing a 4-row layout shows 4 rows; deleting the top row gives a clean 3-row layout with automatic top-row digit hints inferred from the locale.
4. **Single-Source Hint Editing:** Key hints are derived directly from the first popup of each key inside the file, providing one visual source of truth for hints and long-press menus.

---

## 2. Multi-Part Plan Map

The implementation is split into **three independent, sequentially-delivered plans**:

```
 ┌─────────────────────────────────────────────────────────────┐
 │                    1. BAKE NUMBER ROW                       │
 │  Prepend number rows directly into built-in layout assets  │
 │  File: .cursor/plans/part1-bake-number-row.md               │
 └──────────────────────────────┬──────────────────────────────┘
                                │
                                ▼
 ┌─────────────────────────────────────────────────────────────┐
 │                    2. PARSER CLEANUP                        │
 │  Stop dynamic prepending, support localized digits pass,    │
 │  fix off-by-one '+' offsets, and sweep away dead preferences │
 │  File: .cursor/plans/part2-parser-cleanup.md                │
 └──────────────────────────────┬──────────────────────────────┘
                                │
                                ▼
 ┌─────────────────────────────────────────────────────────────┐
 │                    3. UI PARITY & FORKING                   │
 │  Extract LayoutSlotEditor, implement Fork icon, wire all    │
 │  slots, add helper warnings and search-empty tips          │
 │  File: .cursor/plans/part3-ui-parity.md                     │
 └─────────────────────────────────────────────────────────────┘
```

1. **[Part 1: Bake Number Row](part1-bake-number-row.md)** — Asset-level changes. We prepend number rows into layout files so they reflect what actually renders, and verify correctness with JUnit tests.
2. **[Part 2: Parser Cleanup](part2-parser-cleanup.md)** — Engine-level changes. We transition the parser to treat the layout file as the absolute source of truth for row count, handle localization on baked top rows, fix offset bugs, and clean up legacy settings.
3. **[Part 3: UI Parity & Forking](part3-ui-parity.md)** — UI-level changes. We deliver the full user-facing controls and a discoverable "Fork Layout" action on all subtype slots.

_(Note: The old PR 4 "Rebuild APK" step has been removed. Local builds, testing, and sideloading happen continuously during each phase.)_

---

## 3. High-Level Architecture & Technical Concept

```
                BEFORE (Legacy)                             AFTER (V3)

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
```

### Layout Properties after completion of this plan:

- **4-row files (baked):** Renders 4 rows; Row 1 contains the numbers, and the first popup of each key determines its hint label.
- **3-row files:** Renders 3 rows (alphabet only); digit hints automatically appear on the top alphabet row from the locale's `[number_row]`, unless overridden by an explicit key-level popup.

---

## 4. Key Considerations & Guardrails

- **Localized Digits:** After baking Western digits in Part 1, the localized digits pass (`convertToLocalizedNumbers`) must be updated in Part 2 to apply to the first row of parsed 4+ row files.
- **"+" Layout Extras Offset:** Because layout files gain a row, dynamic appenders (like Catalan's `qwerty+` appending `ç` to row 3) must be adjusted by an offset of 1 row to prevent appending keys to the number row.
- **Custom Symbols Exception:** Built-in symbols layout files suppress hints. For custom symbol layouts, we bypass this suppression so users can see their customized hint glyphs.
- **Resumability protocol:** Each of the sub-plans contains a sequential, actionable progress tracking checklist. When starting work, open the active sub-plan, find the first unchecked box, and execute.

---

## 5. Progress Tracking Index

Mark these phases as complete once the corresponding sub-plan is fully merged.

- [ ] **Phase 1:** Bake Number Row (Branch: `cursor/custom-layouts-pr1-bake-number-row`)
  - _Details:_ See [Part 1 Plan](part1-bake-number-row.md)
- [ ] **Phase 2:** Parser Cleanup (Branch: `cursor/custom-layouts-pr2-parser-cleanup`)
  - _Details:_ See [Part 2 Plan](part2-parser-cleanup.md)
- [ ] **Phase 3:** UI Parity & Forking (Branch: `cursor/custom-layouts-pr3-ui-parity`)
  - _Details:_ See [Part 3 Plan](part3-ui-parity.md)
