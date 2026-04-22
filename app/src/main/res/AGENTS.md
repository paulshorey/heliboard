# app/src/main/res

Android resources: strings, themes, layouts, drawables, IME metadata, and density/locale overrides.

## Major subfolders
- `values/` - canonical strings, dimensions, config, themes, and many locale/form-factor overrides.
- `layout/` - IME, emoji, popup, suggestion, and clipboard layout XML.
- `xml/` - IME metadata (`method.xml`), spell checker config, and keyboard templates.
- `drawable/` - vector/layered drawables; naming conventions matter more than any one icon.
- `anim/` and `animator/` - small animation resources.
- `mipmap-*` - launcher icon assets.
- density/API/locale qualifiers (for example `drawable-v24`, `values-sw600dp`, `values-night`) - targeted overrides of the canonical base resources.

## Non-obvious notes
- `method.xml` is effectively part of keyboard layout configuration, not just Android boilerplate.
- Many behavior changes touch both code and resources; for example, key hint sizing spans `values/`, drawables, and keyboard rendering code.
- Default English/source strings belong in `values/`; translations should follow the same keys unless a locale genuinely needs a behavioral override.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
