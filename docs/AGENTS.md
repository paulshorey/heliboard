# docs

Longer-form architecture notes that complement the shorter folder-local AGENTS files.

## Direct files
- `input-simplified.md` - explains the mirror-based current-word architecture and why the host editor is treated as a committed-text sink.
- `soniox-transcription.md` - end-to-end Soniox voice pipeline and configuration notes.

## Non-obvious notes
- These docs overlap intentionally with root guidance and skills; use them when you need design rationale rather than just file lookup.
- If you change a core architectural rule, update both the relevant folder AGENTS file and the matching long-form doc here.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
