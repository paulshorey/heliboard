---
name: key-hint-sizing
description: How HeliBoard sizes key hint (secondary) characters — long-press hints, number row hints, Holo vs LXX themes, and resource qualifiers. Use when adjusting hint label sizes, modifying config.xml fraction values, or debugging key hint display issues.
---

# Key Hint (Secondary Character) Sizing

How HeliBoard sizes the small secondary characters on keys: long-press (popup) alternative hints, number row hints, and the "..." popup hint.

## What the user sees

- On many keys, a **small character** near the top-right hints at long-press alternatives. That text comes from `Key.getHintLabel()`, derived from popup/alternate labels (`PopupKeysUtils.kt`).
- **Number row hints** (`mShowNumberRowHints`) use the same sizing pipeline as other hint labels.
- The **"..." popup hint** uses `params.mHintLetterSize` for its glyph — see `KeyboardView.drawKeyPopupHint()`.

## Size computation (code path)

1. Each key row has a height; `KeyDrawParams.updateParams()` runs per draw pass with that height.
2. Fractions from XML → pixel sizes:
   ```
   hintLetterSize ≈ keyHeight × config_key_hint_letter_ratio_*
   hintLabelSize  ≈ keyHeight × config_key_hint_label_ratio_*
   ```
3. `Key.selectHintTextSize()` chooses which size applies:

| Key capability | Size used | Typical use |
|----------------|-----------|-------------|
| `hasHintLabel()` | `mHintLabelSize` | Hint next to or bottom-aligned with main label |
| `hasShiftedLetterHint()` | `mShiftedLetterHintSize` | Shift alternate in corner (tablet) |
| Else (hint letter path) | `mHintLetterSize` | Top-right corner hint on phone letter keys |

4. `KeyboardView` draws with `paint.setTextSize(key.selectHintTextSize(params) * mFontSizeMultiplier)`.
5. For `hasHintLabel()`, if the hint would extend past the key edge, the code **auto-shrinks** text horizontally.

### Key source files

- `KeyDrawParams.java` — `updateParams()`, `selectTextSize()`
- `Key.java` — `selectHintTextSize`, `hasHintLabel`, `hasHintLetter`, `hasShiftedLetterHint`
- `KeyboardView.java` — hint drawing branches

## Holo vs LXX (two fraction sets)

Themes point at named fractions in `config.xml`:

- `themes-holo_base.xml` → `config_key_hint_*_ratio_holo`
- `themes-lxx.xml` → `config_key_hint_*_ratio_lxx`

One qualifier folder defines four hint-related sizes: hint letter + hint label, each for Holo and LXX.

## Resource qualifiers

Android picks the best-matching `values-*` folder. Keyboard height and key aspect ratio change between phone/tablet and portrait/landscape, so the same hint fraction can look too small or too large without per-qualifier tuning.

| Qualifier | Role |
|-----------|------|
| `values/` | Default — small phone, portrait |
| `values-land/` | Landscape |
| `values-sw600dp/` | Small tablet portrait |
| `values-sw600dp-land/` | Small tablet landscape |
| `values-sw768dp/` | Large tablet portrait |
| `values-sw768dp-land/` | Large tablet landscape |

## Read current values from resources

Use `rg 'config_key_hint_(letter|label)_ratio' app/src/main/res/values*/config.xml`
when tuning sizes. The resource files are authoritative; do not copy a static
value table into this skill where it can drift from the rendered keyboard.

## Tuning guidelines

1. Keep hint letter < main letter ratio for the same qualifier.
2. Hint label can be larger than hint letter (inline display + auto-shrink).
3. When adjusting one qualifier, check sibling qualifiers so behavior doesn't jump on rotation/resize.
4. Verify: letter keys (corner hint), number row (if hints enabled), LXX vs Holo, landscape.

## Parser / settings touchpoints

- `KeyboardParser.kt`: `LABEL_FLAGS_DISABLE_HINT_LABEL` when `mShowNumberRowHints` is false
- Symbol layouts: may disable hint labels via `defaultLabelFlags`

## Keep this skill up to date

If sizing logic, fraction values, or qualifier structure changes, update this skill file immediately.
