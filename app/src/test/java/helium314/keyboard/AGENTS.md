# app/src/test/java/helium314/keyboard

Top-level test package for keyboard, parser, subtype, and IME behavior.

## Direct files
- `KeyboardParserTest.kt` - parser/layout integration tests.
- `KeySpecParserTest.kt` - key-spec parsing tests.
- `LayoutTest.kt` - layout-type helper tests.
- `Shadows.kt` - shared Robolectric shadows used across tests.
- `SubtypeTest.kt` - subtype and keyboard-layout-set behavior tests.
- `XLinkTest.kt` - external-link/dictionary repository checks.

## Subfolders
- `latin/` - core IME pipeline tests.
- `settings/` - fullapp/settings result tests.

## Non-obvious notes
- `XLinkTest.kt` intentionally carries an `X` prefix because test ordering/class-loading has mattered here before.
- The `latin/` subtree contains the highest-value regression coverage for typing behavior.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
