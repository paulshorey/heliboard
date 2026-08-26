#!/usr/bin/env python3
"""Local corrections applied on top of the vendored google-gemini/gemini-skills files.

The skills under `.cursor/skills/gemini-*` are vendored verbatim from upstream. A few
upstream code samples do not run as written, and agents copy those samples into real
code, so we patch them locally. Because `tools/sync-gemini-skills.sh` re-downloads the
upstream files, every correction lives here instead of being hand-edited, and each one
is re-applied (or reported) after a refresh.

Usage:
    tools/gemini-skills-fixups.py --check   # report state, non-zero exit if action needed
    tools/gemini-skills-fixups.py --apply   # apply anything still missing

Report each fixup upstream. When upstream fixes one, its `old` text disappears and this
script reports it as OBSOLETE so the entry can be deleted.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class Fixup:
    path: str
    why: str
    old: str
    new: str


FIXUPS: tuple[Fixup, ...] = (
    Fixup(
        path=".cursor/skills/gemini-interactions-api/references/migration.md",
        why="steps[-1] is Python-only negative indexing and returns undefined in JS; the "
            "skill documents output_text as the language-neutral helper.",
        old="| **Response text** | `response.text` | `interaction.steps[-1].content[0].text` |",
        new="| **Response text** | `response.text` | `interaction.output_text` |",
    ),
    Fixup(
        path=".cursor/skills/gemini-interactions-api/references/migration.md",
        why="Same negative-index problem in the migration checklist item.",
        old="- [ ] Replaced `response.text` → `interaction.steps[-1].content[0].text`",
        new="- [ ] Replaced `response.text` → `interaction.output_text`",
    ),
    Fixup(
        path=".cursor/skills/gemini-interactions-api/SKILL.md",
        why="'cancelled' is a terminal status (see the skill's own Status values list), so "
            "the Python poll loop never exits for a cancelled background interaction. The "
            "JS sample in the same section already handles it.",
        old='    elif interaction.status == "failed":\n'
            '        print(f"Failed: {interaction.error}")\n'
            "        break",
        new='    elif interaction.status in ("failed", "cancelled"):\n'
            '        print(f"Failed: {interaction.status}")\n'
            "        break",
    ),
    Fixup(
        path=".cursor/skills/gemini-live-api-dev/SKILL.md",
        why="Section is titled 'Receiving Audio and Text' and tells the reader to process "
            "all parts, but the Python loop only reads inline_data, dropping model text "
            "when the session uses the TEXT response modality.",
        old="        if content.model_turn:\n"
            "            for part in content.model_turn.parts:\n"
            "                if part.inline_data:\n"
            "                    audio_data = part.inline_data.data",
        new="        if content.model_turn:\n"
            "            for part in content.model_turn.parts:\n"
            "                if part.inline_data:\n"
            "                    audio_data = part.inline_data.data\n"
            "                if part.text:\n"
            '                    print(f"Gemini: {part.text}")',
    ),
    Fixup(
        path=".cursor/skills/gemini-live-api-dev/SKILL.md",
        why="Same dropped text parts in the JavaScript receive loop.",
        old="if (content?.modelTurn?.parts) {\n"
            "  for (const part of content.modelTurn.parts) {\n"
            "    if (part.inlineData) {\n"
            "      const audioData = part.inlineData.data; // Base64 encoded\n"
            "    }\n"
            "  }\n"
            "}",
        new="if (content?.modelTurn?.parts) {\n"
            "  for (const part of content.modelTurn.parts) {\n"
            "    if (part.inlineData) {\n"
            "      const audioData = part.inlineData.data; // Base64 encoded\n"
            "    }\n"
            "    if (part.text) console.log('Gemini:', part.text);\n"
            "  }\n"
            "}",
    ),
    Fixup(
        path=".cursor/skills/gemini-interactions-api/SKILL.md",
        why="Template literal is missing the ${...} interpolation marker, so it prints the "
            "literal placeholder instead of the environment id.",
        old="console.log(`Environment ID: {interaction.environment_id}`);",
        new="console.log(`Environment ID: ${interaction.environment_id}`);",
    ),
    Fixup(
        path=".cursor/skills/gemini-interactions-api/SKILL.md",
        why="'base_agent=' is not valid object-literal syntax, so the TypeScript custom-agent "
            "sample fails to parse before any API call.",
        old="const agent = await client.agents.create({\n"
            '    id: "code-reviewer",\n'
            '    base_agent="antigravity-preview-05-2026",',
        new="const agent = await client.agents.create({\n"
            '    id: "code-reviewer",\n'
            '    base_agent: "antigravity-preview-05-2026",',
    ),
)

APPLIED, PENDING, OBSOLETE, AMBIGUOUS = "APPLIED", "PENDING", "OBSOLETE", "AMBIGUOUS"


def classify(text: str, fixup: Fixup) -> str:
    # `new` is checked first because several fixups only append lines, which leaves `old`
    # as a substring of `new`.
    if fixup.new in text:
        return APPLIED
    count = text.count(fixup.old)
    if count == 1:
        return PENDING
    if count == 0:
        return OBSOLETE
    return AMBIGUOUS


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--check", action="store_true", help="report without writing")
    group.add_argument("--apply", action="store_true", help="apply missing fixups")
    args = parser.parse_args()

    edits: dict[Path, str] = {}
    counts = {APPLIED: 0, PENDING: 0, OBSOLETE: 0, AMBIGUOUS: 0}
    problems: list[str] = []

    for index, fixup in enumerate(FIXUPS, start=1):
        target = ROOT / fixup.path
        if not target.is_file():
            print(f"[{index}] MISSING   {fixup.path}")
            problems.append(
                f"[{index}] missing file {fixup.path} — run tools/sync-gemini-skills.sh"
            )
            continue

        text = edits.get(target, target.read_text())
        state = classify(text, fixup)
        counts[state] += 1
        print(f"[{index}] {state:9} {fixup.path}")

        if state == PENDING:
            if args.apply:
                edits[target] = text.replace(fixup.old, fixup.new, 1)
                print(f"           applied: {fixup.why}")
            else:
                problems.append(f"[{index}] not applied in {fixup.path}: {fixup.why}")
        elif state == OBSOLETE:
            problems.append(
                f"[{index}] neither form found in {fixup.path} — upstream changed this "
                "sample. Re-check it and drop or rewrite this fixup."
            )
        elif state == AMBIGUOUS:
            problems.append(
                f"[{index}] matched {text.count(fixup.old)} times in {fixup.path} — needs "
                "more surrounding context to stay unique."
            )

    if args.apply:
        for path, text in edits.items():
            path.write_text(text)

    print(
        f"\n{counts[APPLIED]} applied, {counts[PENDING]} pending, "
        f"{counts[OBSOLETE]} obsolete, {counts[AMBIGUOUS]} ambiguous"
    )

    if problems and not args.apply:
        print("\nAction needed:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    if any(p for p in problems if "upstream changed" in p or "needs" in p or "missing file" in p):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
