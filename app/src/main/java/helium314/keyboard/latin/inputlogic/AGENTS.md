# latin/inputlogic

Central text-entry state machine and the mirror-based current-word architecture.

## Direct files
- `EditorWordMirror.java` - mirrors the current word into the host editor via committed-text operations.
- `InputLogic.java` - main typing, deletion, correction, and separator handling state machine.
- `InputLogicHandler.java` - bridge from input logic to UI/update callbacks.
- `PrivateCommandPerformer.java` - app-specific private command handling.
- `SpaceState.java` - tracks recent spacing state for autospace/double-space logic.

## Non-obvious notes
- `WordComposer` is the active word source of truth; `EditorWordMirror` exists so host editors still see the evolving word.
- Current-word tracking and suggestion lookup start only when `needsToLookupSuggestions()` is true. That is now true for non-password text fields even if the host set `TYPE_TEXT_FLAG_NO_SUGGESTIONS`.
- Avoid reintroducing host-composition dependence for ordinary typing unless a path truly requires it.
- Ordinary letter typing, recorrection, and gesture words on lift go through the mirror path; direct `commitText()` paths such as separators, paste/multi-character keys, voice, and fullapp replay must first clear or avoid current-word mirror state.
- Recorrection in the middle of an existing word arms the mirror with `setMirroredWord(word, charsAfterCursor, ...)`; delete-after-cursor happens before delete-before-cursor so the word tail is not duplicated or eaten.
- Voice insertion is intentionally outside this package: `LatinIME.commitVoiceTranscriptionText()` calls `finishInput()` and direct `commitText()` inside one batch edit to keep `onUpdateSelection` from cancelling the voice session.
- `InputLogic.setLegacyComposingText*` helpers are not the ordinary typing path. `RichInputConnection` still keeps composing-related cache helpers for belated-update detection and edge cases, but host composing spans are not the active-word source of truth.
- Toolbar keys can arrive through `onCodeInput`, but `KeyCode.FULLAPP` and the toolbar `VOICE_INPUT` handoff are handled by `LatinIME.onEvent`, not by the main text state machine.
- `InputLogicTest.kt` is the main regression suite for this folder.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
