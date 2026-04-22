# keyboard package root

This package is the seam between the IME engine, keyboard view system, settings UI, and Android compatibility helpers.

## Subfolders
- `latin/` - IME runtime, text pipeline, dictionaries, spell checker, voice.
- `keyboard/` - key model, rendering, pointer tracking, popup keys, emoji and clipboard UI.
- `settings/` - standalone settings and fullapp UI built with Compose.
- `event/` - normalized key events, dead keys, combiners, and input transactions.
- `compat/` - API and OEM compatibility helpers.
- `accessibility/` - TalkBack/explore-by-touch support for keyboard surfaces.
- `dictionarypack/` - constants shared with dictionary pack integration.

## Non-obvious notes
- The package name `helium314.keyboard.settings` is UI code; `helium314.keyboard.latin.settings` is runtime preference/config code.
- The product is intentionally split so keyboard rendering, text logic, and settings can evolve somewhat independently.
- When a change crosses package boundaries, update the local AGENTS files in every touched subtree rather than only this root file.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
