# app/src/main/res

Android resources: strings, themes, layouts, drawables, IME metadata, and density/locale overrides.

## Important Android constraint
- Do **not** place `AGENTS.md` files inside resource-type folders such as `res/layout/`, `res/values/`, `res/xml/`, or `res/drawable/`.
- Android treats files in those folders as build inputs, so Markdown there breaks resource packaging.
- Keep the folder-level guidance for those subtrees in this file instead.

## Major subfolders
- `values/` - canonical strings, dimensions, config, themes, and many locale/form-factor overrides.
- `layout/` - IME, emoji, popup, suggestion, and clipboard layout XML.
- `xml/` - IME metadata (`method.xml`), spell checker config, and keyboard templates.
- `drawable/` - vector/layered drawables; naming conventions matter more than any one icon.
- `anim/` and `animator/` - small animation resources.
- `mipmap-*` - launcher icon assets.
- density/API/locale qualifiers (for example `drawable-v24`, `values-sw600dp`, `values-night`) - targeted overrides of the canonical base resources.

## `values/` cheat sheet
### Canonical source files
- `strings.xml` - canonical user-facing source strings.
- `strings-talkback-descriptions.xml` - accessibility/TalkBack strings.
- `config.xml`, `config-common.xml`, `config-per-form-factor.xml`, `config-screen-metrics.xml` - keyboard sizing and behavior defaults.
- `dimens.xml` - shared dimensions.
- `colors.xml` - base colors.
- `themes-*.xml` - theme families (`holo`, `lxx`, `rounded`).
- `touch-position-correction.xml` - touch bias/correction resource data.
- `donottranslate*.xml` - technical strings/config values that should not be localized.

### Qualifier patterns
- `values-<locale>/` - translations and locale-specific overrides.
- `values-sw*` / `values-land/` - form-factor and orientation overrides.
- `values-night*` / `values-v*` - night-mode and API-level overrides.

## `layout/` cheat sheet
- `input_view.xml` - root IME layout.
- `main_keyboard_frame.xml` - main keyboard frame/layout (suggestion strip, optional **Secondary Toolbar** for pinned keys, then `KeyboardWrapperView`).
- `suggestions_strip.xml` + `strip_container.xml` + `suggestion_divider.xml` - suggestion strip layout pieces; `suggestions_strip.xml` also contains `custom_buttons_overlay` for the fixed Soniox mic/cancel/pause controls separate from scrollable toolbar keys.
- `more_suggestions.xml` - expanded suggestions layout.
- `popup_keys_keyboard.xml` and `popup_keys_keyboard_for_action_lxx.xml` - popup key layouts.
- `emoji_*.xml` - emoji category/page/palette layouts.
- `clipboard_*.xml` - clipboard layouts.
- `fake_toast.xml` - lightweight in-app overlay layout.

## `xml/` cheat sheet
- `method.xml` - IME metadata, subtype declarations, and inline layout mapping comments.
- `method_dummy.xml` - dummy/placeholder IME metadata resource used by the manifest.
- `spellchecker.xml` - spell checker service metadata.
- `kbd_emoji.xml`, `kbd_suggestions_pane_template.xml`, `kbd_popup_keys_keyboard_template.xml` - keyboard/popup templates.
- `xml-sw600dp/` and `xml-sw600dp-land/` - tablet popup-key template overrides.

## `drawable/` cheat sheet
- `btn_keyboard_*` - key backgrounds and pressed-state assets.
- `sym_keyboard_*` - keyboard-specific symbols/icons.
- `ic_*` - toolbar, emoji, fullapp, clipboard, and miscellaneous icons.
- suffixes like `_lxx`, `_rounded`, and `_holo` - theme-specific variants of the same visual concept.
- `drawable-v24/`, `drawable-v26/`, and density-specific drawable folders - API/density overrides rather than separate features.

## Non-obvious notes
- `method.xml` is effectively part of keyboard layout configuration, not just Android boilerplate.
- Strip and keyboard compactness tuning lives mainly in `values/config.xml` (and `values-sw*` / `values-land` overrides): `config_suggestions_strip_height`, `config_secondary_toolbar_height`, `config_default_keyboard_height`, `config_key_vertical_gap_*`, and `config_keyboard_*_padding_holo`.
- Many behavior changes touch both code and resources; for example, key hint sizing spans `values/`, drawables, and keyboard rendering code.
- Default English/source strings belong in `values/`; translations should follow the same keys unless a locale genuinely needs a behavioral override.
- When you change a themed icon or background, check whether the sibling theme variants need equivalent updates.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
