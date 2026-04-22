# latin/permissions

Runtime permission helpers for IME-safe flows.

## Direct files
- `PermissionsUtil.java` - permission checks and request helpers.

## Non-obvious notes
- IMEs cannot always launch a normal foreground permission flow from the same contexts as regular apps.
- Voice and contacts work often touches this folder even if the visible feature code lives elsewhere.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
