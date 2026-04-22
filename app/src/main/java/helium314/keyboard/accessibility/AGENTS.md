# accessibility

Accessibility delegates and virtual node support for keyboard surfaces.

## Direct files
- `AccessibilityLongPressTimer.kt` - timing helper for accessible long-press flows.
- `AccessibilityUtils.kt` - shared accessibility helpers.
- `KeyboardAccessibilityDelegate.kt` - base keyboard accessibility delegate.
- `KeyboardAccessibilityNodeProvider.kt` - virtual accessibility node provider.
- `KeyCodeDescriptionMapper.kt` - spoken descriptions for key codes.
- `MainKeyboardAccessibilityDelegate.kt` - accessibility delegate for the main keyboard.
- `PopupKeysKeyboardAccessibilityDelegate.kt` - accessibility delegate for popup keys.

## Non-obvious notes
- Virtual node geometry must track the currently rendered keyboard exactly or TalkBack navigation becomes misleading.
- Popup keys and long-press behavior need separate accessibility handling from the main keyboard.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
