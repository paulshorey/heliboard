#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

# Keep the root editor config link in sync.
ln -sfn .cursor .claude

# Keep CLAUDE.md as a sibling symlink to every AGENTS.md in the repo.
while IFS= read -r -d '' agents_file; do
  agents_dir="$(dirname "$agents_file")"
  ln -sfn AGENTS.md "${agents_dir}/CLAUDE.md"
done < <(rg --files -0 "$ROOT_DIR" -g 'AGENTS.md')
