# Key hint (secondary character) sizing

This document describes how HeliBoard sizes the **small secondary characters** on keys: the glyphs that hint at long-press (popup) alternatives, including optional **number row** hints. It explains the two ratio types, the **Holo** vs **LXX** themes, and why values are split across **resource qualifiers**.

## What the user sees

- On many keys, a **small character** appears near the **top-right** (or, for some layouts, beside the main label). That text comes from the key’s **hint label** in data (`Key.getHintLabel()`), which is derived from popup / alternate labels (see `PopupKeysUtils.kt`).
- If the user enables **number row hints** (`mShowNumberRowHints`), the dedicated number row uses the **same sizing pipeline** as other keys that expose a hint label.
- A separate visual is the **“…” popup hint** (when enabled in settings); it uses `params.mHintLetterSize` for its glyph, not the full hint-label path—see `KeyboardView.drawKeyPopupHint()`.

## How size is computed (code path)

1. Each key row has a height; `KeyDrawParams.updateParams()` runs per draw pass with that height.
2. Fractions from XML are turned into pixel sizes:

   ```text
   hintLetterSize  ≈ keyHeight × config_key_hint_letter_ratio_*
   hintLabelSize   ≈ keyHeight × config_key_hint_label_ratio_*
   ```

   (`KeyDrawParams.selectTextSize()` — ratios are fractions of key height.)

3. `Key.selectHintTextSize()` chooses which of those sizes applies:

   | Key capability | Size used | Typical use |
   | -------------- | --------- | ----------- |
   | `hasHintLabel()` | `mHintLabelSize` | Hint drawn **next to** main label, or **bottom-aligned** with it (LXX `alignHintLabelToBottom`). Can also apply to keys where the parser keeps hint labels enabled (e.g. number row when hints are on). |
   | `hasShiftedLetterHint()` | `mShiftedLetterHintSize` | **Shift alternate** in the corner (often tablet-oriented). Uses `config_key_shifted_letter_hint_ratio_*` — **not** the hint letter/label fractions. |
   | Else (hint letter path) | `mHintLetterSize` | **Top-right** corner hint on typical phone letter keys (`KeyboardView`: “Used mainly on phone”). |

4. `KeyboardView` draws the hint with `paint.setTextSize(key.selectHintTextSize(params) * mFontSizeMultiplier)`. Any global **font size** preference therefore scales hints together with key labels.

5. For `hasHintLabel()`, if the hint would extend past the key edge, the code **shrinks** text horizontally (`paint.setTextSize(autoSize)` with a width ratio). Larger base `hintLabel` fractions are more likely to trigger this auto-shrink on narrow keys.

Relevant sources:

- `app/src/main/java/helium314/keyboard/keyboard/internal/KeyDrawParams.java`
- `app/src/main/java/helium314/keyboard/keyboard/Key.java` (`selectHintTextSize`, `hasHintLabel`, `hasHintLetter`, `hasShiftedLetterHint`)
- `app/src/main/java/helium314/keyboard/keyboard/KeyboardView.java` (hint drawing branches)

## Holo vs LXX (two sets of fractions)

Themes do not hard-code percentages; they point at **named fractions** in `config.xml`:

- `app/src/main/res/values/themes-holo_base.xml` → `config_key_hint_*_ratio_holo`
- `app/src/main/res/values/themes-lxx.xml` → `config_key_hint_*_ratio_lxx`

So **one qualifier folder** defines **four** hint-related sizes: hint letter + hint label, each for Holo and LXX. The active theme picks which pair is used at runtime.

**Why two themes differ:** historical AOSP/OpenBoard styling. Holo often uses a **larger** hint-label ratio than LXX because label and hint layout/colors differ; the values are tuned so corner and inline hints stay readable without overpowering the main glyph.

## Resource qualifiers: why not a single value?

Android picks the **best-matching** `values-*` folder for the device configuration. HeliBoard follows the same pattern as the upstream keyboard: **keyboard height**, **key aspect ratio**, and **main letter/label ratios** change between phone/tablet and portrait/landscape, so the same hint fraction of key height can look **too small** or **too large** if we used only the default bucket.

Qualifiers used for hint ratios:

| Qualifier | Role (in this project’s `config.xml` headers) |
| --------- | ----------------------------------------------- |
| `values/` | Default — **small phone, portrait**. Fallback when no more specific bucket matches. |
| `values-land/` | **Landscape** (any width unless a larger `sw` bucket overrides). Keys are often **shorter and wider**; hint letter/label ratios are adjusted accordingly. |
| `values-sw600dp/` | **Small tablet portrait** (`sw600dp` = smallest width ≥ 600dp). |
| `values-sw600dp-land/` | **Small tablet landscape**. |
| `values-sw768dp/` | **Large tablet portrait**. |
| `values-sw768dp-land/` | **Large tablet landscape**. |

**Important:** `sw600dp` does not mean “only tablets”—very large phones in multi-window mode can match tablet buckets. The splits exist so **physical key proportions** stay reasonable across configurations.

## Current fraction values (reference)

These are the **hint letter** and **hint label** ratios (`*_holo` / `*_lxx`) per qualifier. They are defined in the listed `config.xml` files; grep for `config_key_hint_letter_ratio` or `config_key_hint_label_ratio` to see the exact lines after any future edit.

| Qualifier file | Hint letter (holo / lxx) | Hint label (holo / lxx) |
| -------------- | ------------------------ | ----------------------- |
| `values/config.xml` | 31% / 31% | 52% / 37% |
| `values-land/config.xml` | 37% / 37% | 60% / 37% |
| `values-sw600dp/config.xml` | 29% / 29% | 35% / 25% |
| `values-sw600dp-land/config.xml` | 29% / 29% | 42% / 25% |
| `values-sw768dp/config.xml` | 31% / 31% | 35% / 25% |
| `values-sw768dp-land/config.xml` | 37% / 37% | 35% / 25% |

**Shifted-letter hints** use separate resources `config_key_shifted_letter_hint_ratio_*` in the same files. Change those only if you intend to resize the **shift alternate** corner glyph, not the generic long-press hint.

## Related dimensions (not ratios)

- `config_key_hint_letter_padding` — horizontal inset for corner hints (`KeyboardView` / `KeyboardView` style `keyHintLetterPadding`). Some qualifiers bump padding (e.g. tablets) so the glyph does not hug the edge.
- `keyHintLabelVerticalAdjustment` (theme) — vertical nudge for hint placement relative to the main label.

## Tuning guidelines

1. **Keep hint letter < main letter ratio** for the same bucket (e.g. `config_key_letter_ratio_lxx` in the same file) so the primary label stays dominant.
2. **Hint label** can be larger than hint letter because it is often **inline** with the main label or gets **auto-shrunk** when too wide.
3. When adjusting one qualifier, consider **matching intent** in sibling qualifiers (phone land vs tablet port) so behavior does not jump oddly when rotating or resizing the window.
4. After changes, verify: letter keys (corner hint), **number row** (if hints enabled), **LXX** vs **Holo** theme, and **landscape**.

## Parser / settings touchpoints

- Number row hint visibility: `KeyboardParser.kt` applies `LABEL_FLAGS_DISABLE_HINT_LABEL` when `mShowNumberRowHints` is false so the row does not show secondary characters.
- Alphabet vs symbol layouts: symbol layouts may disable hint labels via default label flags (see `KeyboardParser` `defaultLabelFlags`).

This doc is descriptive; for behavior changes, prefer editing the `config.xml` fractions and re-reading the table above for consistency across qualifiers.
