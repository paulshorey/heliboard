# latin package

This is the main IME engine. It owns the InputMethodService lifecycle, the current-word model, dictionary orchestration, suggestion flow, clipboard history, and voice insertion hooks.

## Direct files
- `App.kt` - application-level initialization hooks.
- `AppsManager.kt` - installed-app name discovery for the apps dictionary.
- `AppUpgrade.kt` - one-time migrations and upgrade tasks.
- `AudioAndHapticFeedbackManager.java` - keypress sound and vibration control.
- `ClipboardHistoryEntry.kt` - clipboard history row model.
- `ClipboardHistoryManager.kt` - clipboard history persistence/orchestration.
- `ContactsContentObserver.java` - watches contact changes so contact suggestions stay fresh.
- `ContactsDictionaryConstants.java` - contacts-dictionary constants.
- `ContactsDictionaryUtils.java` - helper logic for contact dictionary data.
- `ContactsManager.java` - contacts access for suggestions.
- `DictionaryDumpBroadcastReceiver.java` - debug/dump receiver for dictionary state.
- `DictionaryFacilitator.java` - abstraction over prepared dictionary stacks.
- `DictionaryFacilitatorImpl.kt` - default dictionary facilitator implementation.
- `DictionaryFacilitatorLruCache.java` - cache of facilitators by context.
- `DictionaryFacilitatorProvider.java` - factory/provider for facilitators.
- `DictionaryPackInstallBroadcastReceiver.java` - handles external dictionary pack installs.
- `EmojiAltPhysicalKeyDetector.java` - physical keyboard emoji/alt key detection.
- `FullappTextSnapshotUtils.java` - captures before/selected/after text for fullapp flows.
- `InputAttributes.java` - interprets the current `EditorInfo`.
- `InputView.java` - root IME content view.
- `KeyboardWrapperView.kt` - wrapper around keyboard content layout.
- `LastComposedWord.java` - remembers the last committed/composed word for correction flows.
- `LatinIME.java` - main `InputMethodService` and orchestrator.
- `NgramContext.java` - context model for suggestion generation.
- `PunctuationSuggestions.java` - punctuation-specific suggestion list support.
- `RichInputConnection.java` - cached `InputConnection` wrapper and editor helper.
- `RichInputMethodManager.kt` - higher-level input-method manager helper.
- `RichInputMethodSubtype.kt` - richer subtype metadata wrapper.
- `SingleDictionaryFacilitator.kt` - facilitator for one backing dictionary source.
- `Suggest.kt` - suggestion request pipeline entry point; suggestion strip UI lives under `suggestions/`.
- `SuggestedWords.java` - suggestion list/value model.
- `SystemBroadcastReceiver.java` - boot/package/locale receiver.
- `WordComposer.java` - keyboard-owned current-word source of truth.

## Subfolders
- `common/` - small shared types and string/locale helpers.
- `database/` - clipboard database layer.
- `define/` - debug and decoder flags.
- `dictionary/` - concrete binary dictionary implementations.
- `edithistory/` - bounded read-only edit history and editor-target fingerprinting.
- `inputlogic/` - central text-editing state machine.
- `makedict/` - dictionary file-format metadata classes.
- `personalization/` - user-history learning helpers.
- `permissions/` - runtime permission utilities.
- `settings/` - preference keys, defaults, and runtime snapshots.
- `spellcheck/` - Android spell checker service/session code.
- `suggestions/` - suggestion strip and more-suggestions UI.
- `touchinputconsumer/` - gesture typing consumer hook.
- `utils/` - cross-cutting helpers used across the IME.
- `voice/` - Gemini Live recording, streaming, and transcript post-processing.

## Non-obvious notes
- The simplified input model keeps the current word in `WordComposer`, not in the host editor.
- `EditorWordMirror` in `inputlogic/` mirrors that current word into the host app using committed-text operations; bypassing it tends to break deletion, suggestions, and revert logic.
- Suggestion lookup is driven by `WordComposer` plus `needsToLookupSuggestions()`. Host `TYPE_TEXT_FLAG_NO_SUGGESTIONS` no longer hides the strip or skips current-word tracking; password and non-text fields still do. Autocorrect and user-history learning still honor that host flag unless the field also set `TYPE_TEXT_FLAG_AUTO_CORRECT`. An empty 3-slot strip almost always means lookup was skipped, not that the host hid the current word.
- Voice insertion reports local commit/cleanup success; Android remote `InputConnection` booleans report dispatch, not host acceptance. Rejection, exception, or unverifiable cleanup selection terminates dictation. Interruption markers bypass paragraph cleanup and preserve highlighted text by collapsing the selection to its end only after verifying the host caret. `RichInputConnection` verifies actual positions and selected text before cleanup/punctuation replacement, and refreshes caches/restores the caret best-effort on failure without retrying text.
- Voice insertion is a deliberate bypass: `LatinIME.commitVoiceTranscriptionText()` calls `finishInput()` and then direct `commitText()` in a wrapper batch before paragraph post-processing. Verified replacements temporarily close/reopen the host batch so buffered editors apply pending edits before readback. Delayed selection callbacks are ignored only when the current host caret matches the expected voice caret. Cleared-field detection reads actual host text rather than the previous cached text. Markers verify the caret even when the cached selection was collapsed.
- `TranscriptPostProcessor` owns the `!`, `?`, `,`, `:`, `;` period-correction set. `LatinIME` normalizes each complete incoming segment (including line breaks), verifies replacement of a preceding editor period for segments starting with a correction mark, then runs paragraph cleanup. Interruption markers bypass normalization and cleanup.
- Voice host-clear detection uses a dedicated extracted-text monitor unless the framework's fullscreen extract view is visible, plus host reads on selection updates, restarts, and before insertion. A nonempty-to-empty transition cancels active/draining dictation even without cursor movement. Null text queries are unavailable, not empty; highlighted content is preserved. Clears alone do not prove submission, so they do not automatically hide the keyboard. Interactive keyboard hiding and finished input/view callbacks cancel immediately; screen-off hiding alone preserves recording.
- The voice manager's state listener owns pause/recording controls, including IDLE during drain and failed resume. A no-op pause tap must not force them back to recording. Intentional silence auto-stop accepts late finals and closes normally without an interruption marker; actual failures retain the marker policy.
- Fullapp is a standalone draft view. It seeds from `InputConnection`, persists live sync-eligible drafts through `FullappEditorResult`, archives finished drafts into `latin/edithistory/EditHistoryStore`, and replays with raw `InputConnection` replacement on IME reconnect; the system extract view is not the fullapp source of truth.
- Regular-keyboard typing is captured into the same read-only `EditHistoryStore` (debounced in `LatinIME`, gated by `PREF_EDIT_HISTORY_ENABLED` and the same privacy exclusions as email capture).
- Toolbar button definitions and defaults live in `utils/ToolbarUtils.kt`, while strip rendering/voice overlay state lives in `suggestions/SuggestionStripView.kt`.
- When the **Secondary Toolbar** (pinned keys) is visible, `LatinIME` includes its height in visible-strip / inset calculations so the primary strip stays tappable and more-suggestions positioning stays correct.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
