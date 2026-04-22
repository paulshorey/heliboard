# latin/personalization

User-history learning and personalization glue.

## Direct files
- `PersonalizationHelper.java` - scheduling and helper logic for personalization updates.
- `UserHistoryDictionary.java` - dictionary backed by user typing history.

## Non-obvious notes
- This area affects privacy expectations and data-clearing behavior; avoid hidden persistence changes.
- Keep behavior aligned with user dictionary and clipboard-history settings when adding learning features.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
