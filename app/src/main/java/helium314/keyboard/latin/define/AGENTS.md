# latin/define

Build/runtime flags and decoder-specific constants.

## Direct files
- `DebugFlags.kt` - debug-only toggles and flags.
- `DecoderSpecificConstants.kt` - constants shared with the native decoder.
- `ProductionFlags.kt` - production feature flags/defaults.

## Non-obvious notes
- `DecoderSpecificConstants.kt` is part of the Java/JNI contract; changing it may require validating native behavior too.
- Treat this folder as configuration, not as a dumping ground for random constants.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
