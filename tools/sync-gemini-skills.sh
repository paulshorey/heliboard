#!/usr/bin/env bash
# Refresh the vendored google-gemini/gemini-skills files under .cursor/skills.
#
# The skills CLI installs into the vendor-neutral `.agents/skills/` path, but this repo
# keeps its agent skills in `.cursor/skills/`. Cursor loads both paths, so leaving the
# CLI output in place would give every skill a duplicate under two names that drift
# apart. This script does the refresh, moves the result into `.cursor/skills/`, drops
# `.agents/`, and re-applies the local corrections in tools/gemini-skills-fixups.py.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKILLS=(gemini-api-dev gemini-live-api-dev gemini-interactions-api)
AGENTS_SKILLS_DIR="$ROOT_DIR/.agents/skills"
CURSOR_SKILLS_DIR="$ROOT_DIR/.cursor/skills"

cd "$ROOT_DIR"

skill_args=()
for skill in "${SKILLS[@]}"; do
  skill_args+=(--skill "$skill")
done

echo "Fetching skills from google-gemini/gemini-skills..."
npx --yes skills add google-gemini/gemini-skills "${skill_args[@]}" --agent cursor -y --copy

if [[ ! -d "$AGENTS_SKILLS_DIR" ]]; then
  echo "Expected the skills CLI to write $AGENTS_SKILLS_DIR, but it is missing." >&2
  exit 1
fi

for skill in "${SKILLS[@]}"; do
  if [[ ! -f "$AGENTS_SKILLS_DIR/$skill/SKILL.md" ]]; then
    echo "Downloaded skill $skill has no SKILL.md; aborting before touching .cursor." >&2
    exit 1
  fi
done

echo "Moving skills into .cursor/skills..."
for skill in "${SKILLS[@]}"; do
  rm -rf "$CURSOR_SKILLS_DIR/$skill"
  cp -R "$AGENTS_SKILLS_DIR/$skill" "$CURSOR_SKILLS_DIR/$skill"
done

# The lockfile is what `npx skills` reads to know which skills are installed, so keep it
# at the repo root where the CLI expects it.
if [[ -f "$AGENTS_SKILLS_DIR/skills-lock.json" ]]; then
  mv "$AGENTS_SKILLS_DIR/skills-lock.json" "$ROOT_DIR/skills-lock.json"
fi

rm -rf "$ROOT_DIR/.agents"

echo
echo "Re-applying local fixups..."
"$ROOT_DIR/tools/gemini-skills-fixups.py" --apply

echo
echo "Done. Review 'git diff' — upstream wording changes land here verbatim."
