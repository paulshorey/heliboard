## Simplified text input architecture

This document describes the current text input model after the simplification rewrite.

### Goal

The architecture is designed around one rule:

- **Keyboard owns current word state and text assistance**
- **Host app owns only committed text and selection**

That means the keyboard should not depend on the host editor's composing spans or rich text
editing semantics for ordinary typing.

## High-level model

### Keyboard-owned state

The keyboard is responsible for:

- current word text
- current word cursor position
- suggestions
- autocorrect decision
- when a word starts
- when a word is finalized

This state lives primarily in `WordComposer` and `InputLogic`.

### Host-owned state

The host app/editor is responsible only for:

- the actual text buffer
- caret position
- selection range

The host is treated as a **committed-text sink**, not as the source of truth for the active word.

## Main files

### `app/src/main/java/helium314/keyboard/latin/inputlogic/InputLogic.java`

Main text input state machine.

Responsibilities:

- process key events
- decide when a word starts or ends
- request/update suggestions
- apply autocorrect
- handle separators, backspace, and cursor-triggered suggestion lookup

This is the main place to read when debugging ordinary text entry behavior.

### `app/src/main/java/helium314/keyboard/latin/inputlogic/EditorWordMirror.java`

Bridges keyboard-owned current-word state into the host editor.

Responsibilities:

- mirror the in-progress word into the host editor
- replace the mirrored word when it changes
- finalize or clear the mirrored word

It uses only committed text operations (`commitText`, `deleteTextBeforeCursor`) and does not try
to make the host own the active word.

### `app/src/main/java/helium314/keyboard/latin/RichInputConnection.java`

Thin wrapper around Android `InputConnection`.

Responsibilities:

- cached text/selection bookkeeping
- helper methods for text before/after cursor
- selection changes
- committed text insertion and deletion

This class still contains some legacy composition-related helpers because a few non-mainline
paths still depend on them, but the ordinary typing path should prefer the simplified model.

### `app/src/main/java/helium314/keyboard/latin/WordComposer.java`

Keyboard-owned current-word model.

Responsibilities:

- track typed word contents
- track cursor position within the word
- track capitalization / batch input metadata
- hold current autocorrect candidate

This is the source of truth for the active word.

### `app/src/main/java/helium314/keyboard/keyboard/KeyboardActionListenerImpl.kt`

Entry point from keyboard UI events into the input logic.

Responsibilities:

- forward software key presses
- forward hardware key presses
- cursor movement gestures
- language and mode switching gestures

## Main flow

### Ordinary single-character typing

1. UI event enters through `KeyboardActionListenerImpl`
2. `InputLogic.onCodeInput(...)` processes it
3. `WordComposer` updates the current word
4. `EditorWordMirror` mirrors that updated word into the host editor
5. suggestions are refreshed from keyboard-owned state

Important: the host editor does **not** need to expose a live composing span for this to work.
`TYPE_TEXT_FLAG_NO_SUGGESTIONS` also does not block this path: the keyboard still tracks the
current word and fills the suggestion strip. Password and non-text fields remain excluded.
Autocorrect and personalized learning still honor that host flag.

### Divergent insertion paths

Some paths intentionally bypass `EditorWordMirror`:

- voice transcription: `LatinIME.commitVoiceTranscriptionText()` calls `finishInput()`, commits the finalized segment directly, and runs transcript post-processing in the same batch edit
- fullapp replay: `LatinIME.replaceEntireFieldText()` uses the raw `InputConnection` after the IME reconnects to the original field
- paste and multi-character text keys: commit the current word first, then insert direct committed text
- separators and non-word keys: finalize or clear the current word before direct insertion

These paths still write through `InputConnection`; they are not allowed to mutate extract/fullapp UI widgets directly. The important invariant is that any direct commit must avoid leaving stale `WordComposer` or mirror state behind.

### Separator typing

When the user types punctuation or space:

1. `InputLogic.handleSeparatorEvent(...)` decides whether the current word must be committed
2. autocorrect may replace the current mirrored word
3. the separator is then inserted as committed text
4. space-state / autospace logic updates

### Backspace inside current word

1. `InputLogic.handleBackspaceEvent(...)` updates `WordComposer`
2. `EditorWordMirror` replaces or clears the mirrored word in the host editor
3. suggestions refresh from keyboard-owned state

### Suggestion pick

1. `InputLogic.onPickSuggestionManually(...)` commits the chosen word
2. the host text is updated through normal committed replacement
3. autospace/phantom-space behavior may be armed for the next word

### Autocorrect revert

When backspace immediately follows an autocorrected commit:

1. the committed corrected word is deleted
2. the originally typed word is restored
3. this is handled as committed text replacement, not host composition recovery

## Recorrection behavior

The simplified model no longer treats the host editor as the owner of the active word when the
user moves the caret into existing text.

Current behavior:

- the keyboard may inspect the touched word
- the keyboard may use that word to show suggestions
- when editing that touched word, `EditorWordMirror.setMirroredWord(...)` must preserve the `charsAfterCursor` tail so replacement deletes after-cursor text before before-cursor text
- the keyboard should avoid rebuilding a host-managed composing region as the normal mechanism

This keeps the host editor simpler and avoids contenteditable / browser composition glitches.

## Why this architecture exists

Many Android browser and web-app editors behave poorly with IME composition:

- invisible undeletable boundaries
- cursor jumps
- broken backspace behavior
- selection oddities

By making the keyboard own current-word state and using committed text mirroring, the main text
input path becomes easier to reason about and more robust across host editors.

## What to prefer when editing this code

Prefer:

- updating `WordComposer`
- mirroring via `EditorWordMirror`
- committed replace/delete operations
- tests that assert committed text and selection behavior

Avoid introducing new dependencies on:

- host-side composing spans as the source of truth
- editor-managed recorrection regions
- editor-visible underline/composition display for ordinary typing

## Remaining legacy areas

There are still some legacy composition-oriented helper methods in `RichInputConnection`, mainly
for cache bookkeeping, belated update detection, and older or less common paths. `InputLogic` also
still has private `setLegacyComposingText*` helpers, but they are not the ordinary typing path.
When touching these areas:

- check whether the path is still part of ordinary Latin/Cyrillic typing
- if not, keep it isolated
- if yes, consider migrating it toward the same mirror-based model

## Tests

Primary unit coverage for this architecture lives in:

- `app/src/test/java/helium314/keyboard/latin/InputLogicTest.kt`

Those tests should now treat host-visible `composingText` as a legacy/secondary detail, not the
main source of truth for current-word behavior.
