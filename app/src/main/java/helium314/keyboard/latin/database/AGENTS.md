# latin/database

Persistence layer for keyboard-managed structured data.

## Direct files
- `ClipboardDao.kt` - DAO for clipboard history rows.
- `Database.kt` - database definition and migrations.

## Non-obvious notes
- Clipboard history is user data; schema changes should be treated as migration-sensitive work.
- Keep DB contracts in sync with `ClipboardHistoryManager.kt` and the clipboard UI package.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
