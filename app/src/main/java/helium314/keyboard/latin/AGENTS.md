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
- `Suggest.kt` - suggestion request pipeline entry point.
- `SuggestedWords.java` - suggestion list/value model.
- `SystemBroadcastReceiver.java` - boot/package/locale receiver.
- `WordComposer.java` - keyboard-owned current-word source of truth.

## Subfolders
- `common/` - small shared types and string/locale helpers.
- `database/` - clipboard database layer.
- `define/` - debug and decoder flags.
- `dictionary/` - concrete binary dictionary implementations.
- `inputlogic/` - central text-editing state machine.
- `makedict/` - dictionary file-format metadata classes.
- `personalization/` - user-history learning helpers.
- `permissions/` - runtime permission utilities.
- `settings/` - preference keys, defaults, and runtime snapshots.
- `spellcheck/` - Android spell checker service/session code.
- `suggestions/` - suggestion strip and more-suggestions UI.
- `touchinputconsumer/` - gesture typing consumer hook.
- `utils/` - cross-cutting helpers used across the IME.
- `voice/` - Speechmatics recording, streaming, and transcript post-processing.

## Non-obvious notes
- The simplified input model keeps the current word in `WordComposer`, not in the host editor.
- `EditorWordMirror` in `inputlogic/` mirrors that current word into the host app using committed-text operations; bypassing it tends to break deletion, suggestions, and revert logic.
- Fullapp/extract UI should be treated as a view of host text, not the source of truth.
- Voice insertion ultimately still has to honor the same `InputConnection` contract as typed text.
- When the **Secondary Toolbar** (pinned keys) is visible, `LatinIME` includes its height in visible-strip / inset calculations so the primary strip stays tappable and more-suggestions positioning stays correct.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
