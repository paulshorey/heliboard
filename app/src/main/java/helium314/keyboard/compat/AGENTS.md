# compat

API-level and app/OEM compatibility helpers.

## Direct files
- `AppWorkarounds.kt` - app-specific workaround registry/helpers.
- `ClipboardManagerCompat.java` - clipboard API compatibility wrapper.
- `ConfigurationCompat.kt` - configuration compatibility helpers.
- `EditorInfoCompatUtils.kt` - `EditorInfo` compatibility helpers.
- `ImeCompat.kt` - IME/platform compatibility helpers.
- `IsLockedCompat.kt` - lock-state compatibility helpers.

## Non-obvious notes
- This folder accumulates real-world platform quirks; avoid cleanup that removes a workaround without reproducing the original bug.
- `EditorInfo` behavior is highly app-dependent, so changes here often need testing against multiple host apps.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
