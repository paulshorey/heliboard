#!/usr/bin/env bash
# Download in isolation: never let the upstream installer touch discovery links.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGING_DIR="$(mktemp -d)"
trap 'rm -rf "$STAGING_DIR"' EXIT
SKILLS=(gemini-api-dev gemini-live-api-dev)
args=()
for skill in "${SKILLS[@]}"; do args+=(--skill "$skill"); done
cd "$STAGING_DIR"
npx --yes skills add google-gemini/gemini-skills "${args[@]}" --agent codex -y --copy
for skill in "${SKILLS[@]}"; do
  [[ -f "$STAGING_DIR/.agents/skills/$skill/SKILL.md" ]] || { echo "Missing downloaded skill: $skill" >&2; exit 1; }
done
[[ -f "$STAGING_DIR/skills-lock.json" ]] || { echo "Missing download lockfile" >&2; exit 1; }
for skill in "${SKILLS[@]}"; do
  rm -rf "$ROOT_DIR/.codex/skills/$skill"
  cp -R "$STAGING_DIR/.agents/skills/$skill" "$ROOT_DIR/.codex/skills/$skill"
done
cp "$STAGING_DIR/skills-lock.json" "$ROOT_DIR/skills-lock.json"
"$ROOT_DIR/tools/gemini-skills-fixups.py" --apply
echo "Review git diff, including the lockfile and any changed fixups."
