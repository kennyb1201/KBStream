#!/usr/bin/env python3
"""Apply exact, verified string edits to files the editor cannot reach.

Why this exists
---------------
`split_kotlin.py` documents the constraint this tool works around: the
editor's `str_replace` stops matching past roughly 60 KB, and a file whose
bulk is one giant declaration (a 400 KB Activity class) cannot be split at
all, because there is no top-level boundary to cut on. Those files still
need small, surgical edits.

This tool reads an edit spec, checks that every `old` occurs exactly the
expected number of times, and only then writes. A stale or wrong anchor is
a hard error, never a silent no-op.

Usage
-----
    python3 scripts/patch_source.py spec.json          # dry run
    python3 scripts/patch_source.py spec.json --apply

Spec format (a JSON list, applied in order):
    [
      {"file": "app/src/.../Foo.kt",
       "old": "exact text",
       "new": "replacement text",
       "count": 1}
    ]

`count` defaults to 1 and is the number of occurrences that MUST be present.
"""

import argparse
import json
import sys


def load(path):
    with open(path, encoding="utf-8") as fh:
        spec = json.load(fh)
    if not isinstance(spec, list) or not spec:
        sys.exit("spec must be a non-empty JSON list of edits")
    return spec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("spec")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    edits = load(args.spec)
    # Cache file contents so two edits to the same file compose, and nothing
    # is written until every edit has matched.
    contents = {}
    order = []
    for i, edit in enumerate(edits, 1):
        path = edit["file"]
        old = edit["old"]
        new = edit["new"]
        want = int(edit.get("count", 1))
        if path not in contents:
            with open(path, encoding="utf-8") as fh:
                contents[path] = fh.read()
            order.append(path)
        text = contents[path]
        found = text.count(old)
        if found != want:
            sys.exit(
                f"edit {i} ({path}): expected {want} match(es), found {found}\n"
                f"  anchor: {old[:120]!r}"
            )
        contents[path] = text.replace(old, new)
        print(f"edit {i}: {path}  {found} match(es) replaced")

    if not args.apply:
        print("\ndry run: pass --apply to write the files")
        return 0

    for path in order:
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(contents[path])
        print(f"wrote {path} ({len(contents[path])} bytes)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
