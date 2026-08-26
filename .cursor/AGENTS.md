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

Installed from [google-gemini/gemini-skills](https://github.com/google-gemini/gemini-skills) into `.cursor/skills/` so they sit next to the existing HeliBoard skills. The install lockfile is `skills-lock.json` at the repo root.

| Skill | Use when |
| :--- | :--- |
| `gemini-api-dev` | General Gemini/Gemma development: current models, SDKs, multimodal, function calling, structured output. Includes batch `gemini-3.5-transcribe`. |
| `gemini-live-api-dev` | Real-time WebSocket streaming (audio/video/text), VAD, Live API session management. Includes **`gemini-3.5-transcribe-live`** streaming speech-to-text. |
| `gemini-interactions-api` | Interactions API: chat, streaming, tools, structured output, image generation, Deep Research, `generateContent` migration. |

Read the matching skill **before** writing Gemini client code. Do not use deprecated SDKs (`google-generativeai`, `@google/generative-ai`) or retired model IDs (`gemini-1.5-*`, `gemini-2.0-*`).

Refresh skills with:

```bash
npx skills add google-gemini/gemini-skills --skill gemini-api-dev --skill gemini-live-api-dev --skill gemini-interactions-api --agent cursor -y --copy
```

Then copy the installed folders from `.agents/skills/` into `.cursor/skills/` if the CLI writes the vendor-neutral `.agents` path instead.

## HeliBoard product skills

These are local to this keyboard fork and are not part of the Gemini install:

- `android-build-apk`, `android-workspace-setup`, `development`
- `full-app-mode`, `key-hint-sizing`, `voice-transcription` (current shipping Soniox pipeline)

Gemini Live Transcribe (`gemini-3.5-transcribe-live`) is the Gemini counterpart to the Soniox realtime path. Use `gemini-live-api-dev` plus the Docs MCP when changing or evaluating that API; do not treat the Soniox skill as Gemini documentation.

## Keep this file current
- Update this AGENTS.md when files are added, removed, renamed, or repurposed in this folder.
- If a change here affects neighboring folders or a cross-folder contract, update those AGENTS.md files in the same PR.
- Treat stale agent documentation as a bug.
