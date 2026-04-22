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
- Avoid reintroducing host-composition dependence for ordinary typing unless a path truly requires it.
- `InputLogicTest.kt` is the main regression suite for this folder.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
