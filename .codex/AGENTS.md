# HeliBoard agent tooling

`.codex` owns this repository's agent skills and Codex configuration. The root
`AGENTS.md` contains mandatory PR/APK policy because skills are loaded on demand.

- `skills/` contains local Android/product skills and two vendored Gemini skills.
- `config.toml` configures the public Gemini Docs MCP for Codex in trusted projects.
- `mcp.json` is the small compatibility adapter exposed through `.cursor -> .codex`.
  Codex reads TOML; it does not load this JSON merely because it was renamed.
- `../.agents/skills -> ../.codex/skills` provides Codex's documented repo discovery
  path. Keep one physical copy; do not replace either symlink with copied skills.
- Historical layout plans now live in `docs/archive/layout-plans`, away from active
  agent instructions. Cursor-only cloud `environment.json` has been removed.

## Skill ownership

| Skills | Ownership / use |
| --- | --- |
| `android-build-apk` | Required PR artifact and verified download workflow, desktop and cloud |
| `android-workspace-setup` | JDK/SDK discovery and fresh-worktree/Linux setup |
| `development` | Local Gradle, Android Studio, device/emulator validation |
| `full-app-mode` | Current standalone editor, drafts and InputConnection sync |
| `key-hint-sizing` | Current keyboard drawing/resource sizing rules |
| `voice-transcription` | Active Grok voice behavior; historical Gemini references retained; API/data-flow references are local |
| `gemini-api-dev`, `gemini-live-api-dev` | Vendored provider documentation, source hashes in `skills-lock.json` |

The Interactions skill was removed because HeliBoard has no Interactions integration.
For Grok API work read current official STT docs linked from `docs/grok-transcription.md`. Read the appropriate retained Gemini skill before changing Gemini API code. Use Gemini
Docs MCP search and document retrieval when available; tool names may vary by
server version. If absent, fetch `https://ai.google.dev/gemini-api/docs/llms.txt`.
A configured URL does not prove that tools are connected in this session.

Refresh provider skills only with `./tools/sync-gemini-skills.sh`. It runs the
upstream installer in a temporary directory, validates the downloads, updates the
canonical copies/lockfile and applies `tools/gemini-skills-fixups.py`. It never
removes the repository's `.agents` discovery folder. Do not hand-edit vendored
skills: put corrections in the fixup script so refreshes preserve them.

Run `python3 tools/check-agent-infrastructure.py` after changes to this folder.
Local environment actions, manual testing and the follow-up tooling plan are in
`docs/agent-development.md`. Keep this map current when files or ownership change.
