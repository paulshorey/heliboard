# HeliBoard agent tooling

`.codex` owns this repository's six Android/product skills and project agent
configuration. The root `AGENTS.md` contains the mandatory PR/APK policy.

- `skills/` contains `android-build-apk`, `android-workspace-setup`, `development`,
  `full-app-mode`, `key-hint-sizing`, and `voice-transcription`.
- `../.agents/skills -> ../.codex/skills` provides skill discovery. Keep one
  physical copy; `.cursor -> .codex` is a compatibility link.
- `config.toml` and `mcp.json` contain matching empty MCP server maps. MAI API
  work uses the official Microsoft Speech SDK guide linked by the voice skill.
- Historical layout plans live in `docs/archive/layout-plans`. The old cloud
  `environment.json` has been removed; portable Linux setup uses
  `tools/agent-environment-startup.sh`.

The voice skill describes MAI recording, bounded recovery, final insertion,
Azure configuration, and validation. Provider API changes must be checked against
[Microsoft's Speech SDK guide](https://learn.microsoft.com/en-us/azure/ai-services/speech-service/mai-transcribe-2-streaming-speech-sdk).

Run `python3 tools/check-agent-infrastructure.py` after changes to this folder.
Local environment actions and the manual acceptance matrix are in
`docs/agent-development.md`. Keep this map current when files or ownership change.
