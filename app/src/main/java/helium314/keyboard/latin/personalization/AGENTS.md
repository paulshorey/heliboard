# latin/personalization

User-history learning and personalization glue.

## Direct files
- `EmailLearner.kt` - debounced scanner that scrapes email addresses out of the host editor's surrounding text and feeds them to `EmailsDictionary`. Triggered from `LatinIME.onUpdateSelection` and flushed at `onFinishInput`.
- `EmailsDictionary.kt` - locale-agnostic, file-backed dictionary of user-typed email addresses with usage counts. Read by `Suggest.prependMatchingEmails` to prioritize email suggestions.
- `PersonalizationHelper.java` - scheduling and helper logic for personalization updates.
- `UserHistoryDictionary.java` - dictionary backed by user typing history.

## Non-obvious notes
- This area affects privacy expectations and data-clearing behavior; avoid hidden persistence changes.
- Keep behavior aligned with user dictionary and clipboard-history settings when adding learning features.
- `EmailLearner` and `EmailsDictionary` deliberately bypass `UserHistoryDictionary` and the native dictionary stack. Emails contain `@`, `.`, and `-` which the binary dictionary pipeline treats as separators or connectors, so they cannot reliably be stored there. They live in a plain TSV file so they are also easy to expose through the standalone settings UI.
- The email learner must be gated by `SettingsValues.mIncognitoModeEnabled`, which already covers password fields, no-learning fields, and the always-incognito toggle.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
