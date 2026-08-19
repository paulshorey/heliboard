# docs

Longer-form architecture notes that complement the shorter folder-local AGENTS files.

## Direct files
- `input-simplified.md` - explains the mirror-based current-word architecture and why the host editor is treated as a committed-text sink.
- `recent-number-row-changes.md` - number-row bake migration notes and layout/parser troubleshooting.
- `soniox-transcription.md` - Soniox provider/API reference: protocol details, session config fields, and configuration notes.
- `voice-transcription-workflow.md` - end-to-end lifecycle of the custom voice pipeline (mic tap → chunked transcription → insertion → stop/cancel), threading model, guards, and the inventory of where the provider is baked in.
- `pluggable-transcription-providers-plan.md` - implementation plan for making the transcription provider a user-selectable plugin, including the normalized event/capability contracts and phased migration.
- `general-edit-history-plan.md` - implementation plan for generalizing the fullapp edit history into a bounded, general-purpose edit history that also captures regular-keyboard typing, without breaking fullapp draft sync.

## Non-obvious notes
- These docs overlap intentionally with root guidance and skills; use them when you need design rationale rather than just file lookup.
- Voice docs are split on purpose: `voice-transcription-workflow.md` is the provider-agnostic control flow, `soniox-transcription.md` is the Soniox wire protocol. Keep provider specifics out of the workflow doc so it survives the pluggable-provider work.
- The integrated speech-to-text provider is Soniox. Speechify is not used anywhere; `speechmatics_api_key` and `deepgram_api_key` exist only as legacy preference keys that get deleted.
- If you change a core architectural rule, update both the relevant folder AGENTS file and the matching long-form doc here.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
