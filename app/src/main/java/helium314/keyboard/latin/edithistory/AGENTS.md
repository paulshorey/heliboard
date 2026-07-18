# latin/edithistory

Bounded read-only edit history and shared editor-target fingerprinting for regular-keyboard capture and fullapp archival.

## Direct files
- `EditorTargetSnapshot.kt` - identifies a host-app text field (package, fieldId, fieldName, inputType, imeOptions, privateImeOptions) and provides fuzzy `matchScore` for fullapp draft replay.
- `EditHistoryStore.kt` - bounded history store (`FULLAPP` and `REGULAR` sources), per-field latest slots, retention caps, and legacy fullapp-archive migration.

## Non-obvious notes
- **Sync-eligible fullapp drafts** stay in `settings/FullappEditorResult` (`PREF_FULLAPP_DRAFT_*`). This package is read-only history only; it never auto-syncs to a host field.
- Regular-keyboard capture in `LatinIME` writes latest slots via `updateLatest` and promotes them on field exit via `finalizeLatest`.
- Fullapp archival calls `EditHistoryStore.addEntry(source = FULLAPP, …)` when a live draft is archived after sync or supersession.
- Storage uses device-protected `protectedPrefs()` with ordered index + per-entry JSON blobs; retention enforces max entries, total chars, per-entry tail truncation, and a user-configurable age window (`PREF_EDIT_HISTORY_RETENTION_HOURS`, default 24h; “No limit” sentinel skips age eviction).
- The same age window also purges expired pending latest slots and live fullapp drafts in `FullappEditorResult.enforceAgeRetention`.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
