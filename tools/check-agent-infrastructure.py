#!/usr/bin/env python3
"""Check the portable agent discovery/configuration contract without Android or network."""
import json
from pathlib import Path
import subprocess
import sys
import tomllib

ROOT = Path(__file__).resolve().parent.parent


def main():
    for link, target in {'.cursor': '.codex', '.agents/skills': '.codex/skills'}.items():
        path = ROOT / link
        if not path.is_symlink() or path.resolve() != (ROOT / target).resolve():
            raise ValueError(f'{link} must be a symlink to {target}')
    codex = tomllib.loads((ROOT / '.codex/config.toml').read_text())['mcp_servers']
    compat = json.loads((ROOT / '.codex/mcp.json').read_text())['mcpServers']
    if codex != compat:
        raise ValueError('Codex and compatibility MCP definitions differ')
    skills = ROOT / '.agents/skills'
    names = set()
    for entry in sorted(skills.iterdir()):
        content = (entry / 'SKILL.md').read_text()
        frontmatter = content.split('---', 2)[1]
        name = next(line.removeprefix('name:').strip() for line in frontmatter.splitlines() if line.startswith('name:'))
        if name != entry.name or name in names or not any(line.startswith('description: ') for line in frontmatter.splitlines()):
            raise ValueError(f'Invalid or duplicate skill metadata: {entry}')
        names.add(name)
    locked = json.loads((ROOT / 'skills-lock.json').read_text())['skills']
    if set(locked) != {name for name in names if name.startswith('gemini-')}:
        raise ValueError('Provider skill lockfile differs from installed skills')
    subprocess.run([sys.executable, str(ROOT / 'tools/gemini-skills-fixups.py'), '--check'], check=True)
    print(f'Agent infrastructure verified: {len(names)} skills, discovery symlinks and MCP adapters.')


if __name__ == '__main__':
    main()
