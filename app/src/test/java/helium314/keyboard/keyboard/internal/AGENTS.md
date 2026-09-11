# app/src/test/java/helium314/keyboard/keyboard/internal

Tests for keyboard state and internal mechanics.

## Direct files
- `KeyboardStateEmojiToggleTest.kt` - `KeyCode.EMOJI` opens the emoji palette, then returns to alphabet on a second press. Also covers physical-shortcut cases where the visible palette and `KeyboardState.mode` disagree, and restoring caps lock when returning from emoji.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
