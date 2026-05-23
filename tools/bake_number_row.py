#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path

NUMBER_ROW_TEXT = """1 ! ¹ ½ ⅓ ¼ ⅛
2 @ ² ⅔
3 # ³ ¾ ⅜
4 $ ⁴
5 % ⁵ ⅝
6 ^ ⁶
7 & ⁷ ⅞
8 * ⁸
9 ( ⁹
0 ) ⁰ ⁿ ∅
"""

NUMBER_ROW_JSON = [
  {"$": "shift_state_selector", "manualOrLocked": {"label": "!"}, "default": {"label": "1", "popup": {"main": {"label": "!"}, "relevant": [{"label": "¹"}, {"label": "½"}, {"label": "⅓"}, {"label": "¼"}, {"label": "⅛"}]}}},
  {"$": "shift_state_selector", "manualOrLocked": {"label": "@"}, "default": {"label": "2", "popup": {"main": {"label": "@"}, "relevant": [{"label": "²"}, {"label": "⅔"}]}}},
  {"$": "shift_state_selector", "manualOrLocked": {"label": "#"}, "default": {"label": "3", "popup": {"main": {"label": "#"}, "relevant": [{"label": "³"}, {"label": "¾"}, {"label": "⅜"}]}}},
  {"$": "shift_state_selector", "manualOrLocked": {"label": "$"}, "default": {"label": "4", "popup": {"main": {"label": "$"}, "relevant": [{"label": "⁴"}]}}},
  {"$": "shift_state_selector", "manualOrLocked": {"label": "%"}, "default": {"label": "5", "popup": {"main": {"label": "%"}, "relevant": [{"label": "⁵"}, {"label": "⅝"}]}}},
  {"$": "shift_state_selector", "manualOrLocked": {"label": "^"}, "default": {"label": "6", "popup": {"main": {"label": "^"}, "relevant": [{"label": "⁶"}]}}},
  {"$": "shift_state_selector", "manualOrLocked": {"label": "&"}, "default": {"label": "7", "popup": {"main": {"label": "&"}, "relevant": [{"label": "⁷"}, {"label": "⅞"}]}}},
  {"$": "shift_state_selector", "manualOrLocked": {"label": "*"}, "default": {"label": "8", "popup": {"main": {"label": "*"}, "relevant": [{"label": "⁸"}]}}},
  {"$": "shift_state_selector", "manualOrLocked": {"label": "("}, "default": {"label": "9", "popup": {"main": {"label": "("}, "relevant": [{"label": "⁹"}]}}},
  {"$": "shift_state_selector", "manualOrLocked": {"label": ")"}, "default": {"label": "0", "popup": {"main": {"label": ")"}, "relevant": [{"label": "⁰"}, {"label": "ⁿ"}, {"label": "∅"}]}}},
]

SKIPS = {
    "app/src/main/assets/layouts/main/pcqwerty.json",
    "app/src/main/assets/layouts/main/lao.json",
    "app/src/main/assets/layouts/main/thai.json",
}
DIGIT_RE = re.compile(r"[0-9١-٩०-९]")


def split_simple_rows(text: str):
    lines = text.splitlines()
    rows = []
    row = []
    for line in lines:
        stripped = line.strip()
        if stripped:
            row.append(stripped)
        elif row:
            rows.append(row)
            row = []
    if row:
        rows.append(row)
    return rows


def bake_simple(path: Path, dry_run: bool):
    original = path.read_text(encoding="utf-8")
    rows = split_simple_rows(original)
    if not rows:
        return "skip-empty"
    if any(DIGIT_RE.search(cell.split()[0]) for cell in rows[0]):
        return "skip-existing"
    if len(rows) + 1 >= 7:
        return "skip-too-many-rows"
    newline = "\r\n" if "\r\n" in original else "\n"
    trailing = original.endswith(("\n", "\r\n"))
    baked = NUMBER_ROW_TEXT.replace("\n", newline) + newline + original.lstrip("\n\r")
    if not trailing and baked.endswith(("\n", "\r\n")):
        baked = baked[:-len(newline)]
    if dry_run:
        return "would-modify"
    path.write_text(baked, encoding="utf-8")
    return "modified"


def bake_json(path: Path, dry_run: bool):
    original = path.read_text(encoding="utf-8")
    data = json.loads(original)
    if not isinstance(data, list) or not data:
        return "skip-empty"
    first_row = data[0]
    labels = []
    for item in first_row if isinstance(first_row, list) else []:
        if isinstance(item, dict):
            labels.extend([item.get("label"), item.get("default", {}).get("label")])
    if any(isinstance(lbl, str) and DIGIT_RE.search(lbl) for lbl in labels):
        return "skip-existing"
    if len(data) + 1 >= 7:
        return "skip-too-many-rows"
    data.insert(0, NUMBER_ROW_JSON)
    dumped = json.dumps(data, indent=2, ensure_ascii=False)
    if original.endswith("\n"):
        dumped += "\n"
    if dry_run:
        return "would-modify"
    path.write_text(dumped, encoding="utf-8")
    return "modified"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--root", default=".")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    layout_roots = [
        root / "app/src/main/assets/layouts/main",
        root / "app/src/main/assets/layouts/symbols",
        root / "app/src/main/assets/layouts/more_symbols",
    ]
    for base in layout_roots:
        for path in sorted(base.glob("*.txt")) + sorted(base.glob("*.json")):
            rel = path.relative_to(root).as_posix()
            if rel in SKIPS:
                print(f"SKIP configured: {rel}")
                continue
            status = bake_json(path, args.dry_run) if path.suffix == ".json" else bake_simple(path, args.dry_run)
            print(f"{status.upper()}: {rel}")

if __name__ == "__main__":
    main()
