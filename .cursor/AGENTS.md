# .cursor

Cursor project config for this repo: cloud-agent environment, committed MCP servers, and agent skills.

## Direct files
- `environment.json` - Cursor cloud-agent install/env (Android SDK path and setup script).
- `mcp.json` - project-level MCP servers. Currently the public **Gemini Docs MCP** at `https://gemini-api-docs-mcp.dev` (server name `gemini-docs`).
- `skills/` - project skills loaded by Cursor. Includes HeliBoard product skills plus installed Gemini API skills.
- `plans/` - historical implementation notes; not runtime config.

## Gemini Docs MCP

`.cursor/mcp.json` connects agents to the hosted Gemini API docs server (`gemini-docs` 0.17.1 at the time of install). After a reload, use `gemini_search_docs` first, then `gemini_get_doc` when you need surrounding context. Older docs may still call these `search_documentation` / `search_docs`.

Use that MCP as the source of truth for current Gemini models, endpoints, and SDK patterns. Training data is stale.

If the MCP tools are not available in the current session, fall back to `https://ai.google.dev/gemini-api/docs/llms.txt` as the Gemini skills describe.

Reinstall or refresh with:

```bash
npx add-mcp "https://gemini-api-docs-mcp.dev" -a cursor -y -n gemini-docs
```

## Gemini API skills

Vendored from [google-gemini/gemini-skills](https://github.com/google-gemini/gemini-skills) into `.cursor/skills/` so they sit next to the existing HeliBoard skills. The install lockfile is `skills-lock.json` at the repo root.

| Skill | Use when |
| :--- | :--- |
| `gemini-api-dev` | General Gemini/Gemma development: current models, SDKs, multimodal, function calling, structured output. Includes batch `gemini-3.5-transcribe`. |
| `gemini-live-api-dev` | Real-time WebSocket streaming (audio/video/text), VAD, Live API session management. Includes **`gemini-3.5-transcribe-live`** streaming speech-to-text. |
| `gemini-interactions-api` | Interactions API: chat, streaming, tools, structured output, image generation, Deep Research, `generateContent` migration. |

Read the matching skill **before** writing Gemini client code. Do not use deprecated SDKs (`google-generativeai`, `@google/generative-ai`) or retired model IDs (`gemini-1.5-*`, `gemini-2.0-*`).

### Refreshing

```bash
./tools/sync-gemini-skills.sh
```

Do not call `npx skills add` or `npx skills update` directly. The skills CLI installs into `.agents/skills/`, and Cursor loads that path *in addition to* `.cursor/skills/`, so a direct run leaves two copies of every skill under the same names, drifting apart with no warning. `.agents/` is gitignored for the same reason. The sync script does the download, moves the result into `.cursor/skills/`, deletes `.agents/`, and re-applies the local fixups.

### Local fixups

The vendored files are upstream verbatim except for the corrections declared in `tools/gemini-skills-fixups.py`, which fix upstream code samples that do not run as written (a TypeScript object literal that fails to parse, a template literal missing `${...}`, a Deep Research poll loop that never exits on a cancelled interaction, and Live API receive loops that drop text parts).

Never hand-edit `.cursor/skills/gemini-*`; add an entry to that script instead, or the next refresh reverts the change. `tools/gemini-skills-fixups.py --check` reports the state of each one and flags any whose upstream text moved, which is the signal that upstream fixed it and the entry can be dropped.

## HeliBoard product skills

These are local to this keyboard fork and are not part of the Gemini install:

- `android-build-apk`, `android-workspace-setup`, `development`
- `full-app-mode`, `key-hint-sizing`, `voice-transcription` (current shipping Soniox pipeline)

Gemini Live Transcribe (`gemini-3.5-transcribe-live`) is the Gemini counterpart to the Soniox realtime path. Use `gemini-live-api-dev` plus the Docs MCP when changing or evaluating that API; do not treat the Soniox skill as Gemini documentation.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
