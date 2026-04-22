# keyboard package

Visual keyboard system: key geometry, rendering, pointer tracking, layout switching, popups, emoji, and clipboard surfaces.

## Direct files
- `Keyboard.java` - key grid/model for one keyboard layout.
- `KeyboardActionListener.java` - callback contract from the view layer into IME logic.
- `KeyboardActionListenerImpl.kt` - concrete bridge from keyboard events into the IME pipeline.
- `KeyboardId.java` - identity of a keyboard layout/state.
- `KeyboardLayoutSet.java` - collection/factory of active keyboard layouts.
- `KeyboardSwitcher.java` - switches between keyboard modes/states.
- `KeyboardTheme.kt` - theme resolution for keyboard visuals.
- `KeyboardView.java` - base keyboard drawing/event view.
- `Key.java` - one key's geometry, labels, and behavior.
- `KeyDetector.java` - hit-testing logic.
- `MainKeyboardView.java` - primary keyboard surface used by the IME.
- `PointerTracker.java` - per-pointer gesture/touch tracking.
- `PopopUtil.kt` - popup utility helpers.
- `PopupKeysDetector.java` - popup-key hit detection.
- `PopupKeysKeyboard.java` - model for popup key layouts.
- `PopupKeysKeyboardView.java` - popup keys view.
- `PopupKeysPanel.java` - popup keys container/panel.
- `PopupTextView.java` - small popup text view element.

## Subfolders
- `internal/` - drawing previews, timers, keyboard state, gesture trails, parser plumbing.
- `emoji/` - emoji palette UI.
- `clipboard/` - clipboard history keyboard UI.

## Non-obvious notes
- The keyboard renderer is performance-sensitive; avoid allocations and broad invalidations in hot paths.
- Layout assets and parser logic must stay aligned with `assets/layouts/` and `res/xml/method.xml`.
- `KeyboardActionListenerImpl.kt` is the main seam from view events into text logic.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
