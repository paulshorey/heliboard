# .cursor

Cursor project configuration: cloud-agent environment, product skills, and planning notes.

## Direct entries
- `environment.json` - Cursor cloud-agent Android SDK setup. Other Linux hosts use `../tools/agent-environment-startup.sh`.
- `skills/` - local HeliBoard product skills.
- `plans/` - implementation notes, separate from runtime configuration.

## Product skills
- `android-build-apk`, `android-workspace-setup`, `development`
- `full-app-mode`, `key-hint-sizing`, `voice-transcription`

The voice-transcription skill describes MAI-Transcribe-2-Streaming recording, explicit commits, final transcript delivery, Azure configuration, and tests. API changes must be verified against [Microsoft's Realtime guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-realtime).

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
