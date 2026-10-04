#!/usr/bin/env python3
"""Check repository Markdown local links and discourage stale README run IDs."""
from __future__ import annotations

import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parent.parent
LINK_RE = re.compile(r"!?\[[^\]]*\]\(([^)]+)\)")
STATIC_ACTION_RUN_RE = re.compile(r"https?://github\.com/[^\s)]+/actions/runs/\d+", re.IGNORECASE)
EXCLUDED_DIRS = {".git", ".gradle", "build", "node_modules"}


def markdown_files() -> list[Path]:
    return sorted(
        path for path in ROOT.rglob("*.md")
        if not any(part in EXCLUDED_DIRS for part in path.relative_to(ROOT).parts)
    )


def main() -> int:
    errors: list[str] = []
    checked_links = 0
    for path in markdown_files():
        text = path.read_text(encoding="utf-8")
        rel_source = path.relative_to(ROOT).as_posix()
        if rel_source == "README.md" and STATIC_ACTION_RUN_RE.search(text):
            errors.append(f"{rel_source}: use the live Actions workflow/badge, not a hard-coded run ID")
        for raw_target in LINK_RE.findall(text):
            target = raw_target.strip().split()[0] if raw_target.strip() else ""
            if not target:
                continue
            parsed = urlsplit(target)
            if parsed.scheme or target.startswith("#"):
                continue
            relative_target = unquote(parsed.path)
            if not relative_target:
                continue
            checked_links += 1
            destination = (path.parent / relative_target).resolve()
            try:
                destination.relative_to(ROOT)
            except ValueError:
                errors.append(f"{rel_source}: local link escapes the repository: {target}")
                continue
            if not destination.exists():
                errors.append(f"{rel_source}: missing local link target: {target}")

    if errors:
        for error in errors:
            print(f"[FAIL] {error}", file=sys.stderr)
        return 1
    print(f"[OK] Documentation links: {checked_links} local targets resolve across {len(markdown_files())} Markdown files.")
    print("[OK] README does not pin a changing Actions status to a single run ID.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
