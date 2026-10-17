# docs

Longer-form architecture notes that complement the shorter folder-local AGENTS files.

## Direct files
- `agent-development.md` - current agent tooling audit, APK/PR contract, local validation, manual acceptance and prioritized follow-up implementation plan.
- `input-simplified.md` - explains the mirror-based current-word architecture and why the host editor is treated as a committed-text sink.
- `recent-number-row-changes.md` - number-row bake migration notes and layout/parser troubleshooting.
- `muse-transcription.md` - current Muse protocol, first-speaker filter, pacing, pause silence, settings/privacy, integrity safeguards and live/device acceptance.
- `archive/gemini-transcription.md` - historical provider architecture; not the current branch behavior.
- `general-edit-history-plan.md` - implementation plan for generalizing the fullapp edit history into a bounded, general-purpose edit history that also captures regular-keyboard typing, without breaking fullapp draft sync.

## Subfolders
- `archive/layout-plans/` - historical design notes, moved out of active agent configuration; check current code before treating their status as current.

## Non-obvious notes
- These docs overlap intentionally with root guidance and skills; use them when you need design rationale rather than just file lookup.
- If you change a core architectural rule, update both the relevant folder AGENTS file and the matching long-form doc here.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
