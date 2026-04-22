# res/drawable

Drawables for keys, toolbar actions, emoji categories, clipboard, d-pad navigation, and theme-specific icons.

## Naming patterns
- `btn_keyboard_*` - key backgrounds and pressed-state assets.
- `sym_keyboard_*` - keyboard-specific symbols/icons.
- `ic_*` - toolbar, emoji, fullapp, clipboard, and miscellaneous icons.
- Suffixes like `_lxx`, `_rounded`, and `_holo` - theme-specific variants of the same visual concept.

## Nearby related folders
- `drawable-v24/` and `drawable-v26/` - API-level drawable overrides.
- `drawable-<density>/` - bitmap density variants where needed.
- `mipmap-*` - launcher icons rather than in-keyboard artwork.

## Non-obvious notes
- This folder is intentionally pattern-heavy; keep new asset names consistent with the existing prefix/suffix scheme.
- When you change a themed icon or background, check whether the other theme variants need equivalent updates.
- Visual tweaks here often pair with dimension/fraction changes in `res/values/` and drawing logic in `keyboard/`.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
