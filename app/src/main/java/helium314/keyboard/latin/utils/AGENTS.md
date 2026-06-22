# latin/utils

Cross-cutting helpers used across the IME. Search here before adding another generic utility.

## Direct files
- `AsyncResultHolder.java` - tiny async result container.
- `AutoCorrectionUtils.java` - auto-correction scoring/helpers.
- `CapsModeUtils.java` - capitalization mode helpers.
- `ChecksumCalculator.kt` - file checksum helpers.
- `ColorUtil.kt` - theme color utilities.
- `CombinedFormatUtils.java` - formatting helpers for combined values.
- `DebugLogUtils.java` - debug logging helpers.
- `DeviceProtectedUtils.java` - device-protected storage helpers.
- `DialogUtils.kt` - dialog convenience helpers.
- `DictionaryInfoUtils.kt` - dictionary metadata helpers.
- `DictionaryUtils.kt` - dictionary file/path helpers.
- `ExecutorUtils.java` - shared executor/thread helpers.
- `InlineAutofillUtils.java` - inline autofill helpers.
- `InputMethodPicker.kt` - IME picker helpers.
- `InputTypeUtils.java` - `EditorInfo.inputType` interpretation.
- `IntentUtils.kt` - intent helpers.
- `JniUtils.java` - JNI loading and native helper bridge.
- `JsonUtils.java` - JSON helpers.
- `Ktx.kt` - miscellaneous Kotlin extensions.
- `LanguageOnSpacebarUtils.java` - language label logic for the spacebar.
- `LayoutType.kt` - keyboard layout type enum/model.
- `LayoutUtilsCustom.kt` - custom layout helpers.
- `LayoutUtils.kt` - general layout helpers.
- `LeakGuardHandlerWrapper.java` - leak-avoiding `Handler` wrapper.
- `Log.kt` - app logging facade with in-memory ring buffer and voice-diagnostics filtering helpers.
- `NgramContextUtils.java` - helpers for building `NgramContext`.
- `PopupKeysUtils.kt` - popup-key utility logic.
- `RecapitalizeMode.java` - recapitalization mode enum/model.
- `RecapitalizeStatus.java` - recapitalization state holder.
- `ReorderSwitchPreferenceUtils.kt` - reorderable preference normalization helpers.
- `ResourceUtils.java` - resource lookup helpers (keyboard width/height; note `getDefaultKeyboardHeight` clamps `config_default_keyboard_height` against `config_min/max_keyboard_height`, where a negative min is interpreted as a fraction of **display width**). `getKeyboardLayoutHeightForPanel` adds the pinned secondary toolbar reserve when strips are visible so emoji/clipboard bottom rows match the main keyboard frame.
- `RunInLocale.kt` - temporarily-run-in-locale helper.
- `ScriptUtils.kt` - script/language family helpers.
- `SmartAutoCapsUtils.java` - smart auto-capitalization logic helpers.
- `SpacedTokens.kt` - tokenization helpers that keep spacing context.
- `SpannableStringUtils.java` - spannable/string span helpers.
- `StatsUtils.java` - usage/statistics helper methods.
- `StatsUtilsManager.java` - stats manager wrapper.
- `SubtypeLocaleUtils.kt` - subtype locale helpers.
- `SubtypeSettings.kt` - subtype settings helpers.
- `SubtypeUtilsAdditional.kt` - extra subtype utility logic.
- `SubtypeUtils.kt` - main subtype helper set.
- `SuggestionResults.java` - suggestion result container/model.
- `TextPlacement.java` - text placement/caret positioning helpers.
- `TextRange.java` - text range value type.
- `Timestamp.kt` - timestamp/time helpers.
- `ToolbarUtils.kt` - toolbar/action-strip helpers.
- `TypefaceUtils.java` - typeface loading/helpers.
- `UncachedInputMethodManagerUtils.java` - direct IME manager helpers when cached state is stale.
- `ViewLayoutUtils.java` - view measurement/layout helpers.

## Non-obvious notes
- `JniUtils.java` is part of the dictionary/native contract; changes here are higher risk than a normal helper edit.
- `DeviceProtectedUtils.java` matters because the app supports direct boot.
- This folder is intentionally broad, but new utilities should still be named for a concrete domain rather than as generic catch-alls.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
