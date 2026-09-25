#!/usr/bin/env python3
"""Split a large Kotlin source file into parts an editor can actually reach.

Why this exists
---------------
The editor tools used on this repo only see the first part of a file: a
`str_replace` anchor past roughly 60 KB is reported as "not found" even though
the text is definitely there. Measured on this tree, so the numbers are not a
guess:

  * `HomeScreen.kt`, anchor at byte 56,460  -> matched
  * `HomeScreen.kt`, anchor at byte 63,644  -> "was not found"
  * `AddonsHomeManagerDialog.kt` (42 KB), anchor at its last declaration -> matched

The cutoff is a byte offset, not a line count, so the only durable fix is to
keep every source file under it. Kotlin has no partial classes and no way to
split one declaration across files, but *separate* top-level declarations can
be moved to a sibling file verbatim -- which is what this tool does. It cuts
only at top-level declaration boundaries and copies bytes, so nothing is
re-typed or re-ordered by accident.

It follows that this tool cannot help a file whose bulk is one huge declaration
(a 367 KB Activity class, a 170 KB composable). Those need declarations
*extracted* out of the giant one, which is a real refactor, not a move.

What it does
------------
  * cuts at top-level declaration boundaries (never inside a function, a
    multiline expression, a string or a comment), keeping each declaration's
    KDoc/annotations/region comments attached to it;
  * balances the parts instead of filling each to the maximum;
  * gives every part the parent's prologue (package, imports, `@file:`
    annotations) so it compiles on its own;
  * promotes a `private` top-level declaration to `internal` when another part
    refers to it -- widening visibility cannot break a caller;
  * checks the result three ways *before* writing anything: the parts' bodies
    must concatenate back to the body that was read, every top-level
    declaration must appear exactly once across the parts, and the byte count
    must add up.

Usage
-----
    python3 scripts/split_kotlin.py <file.kt>                 # dry run
    python3 scripts/split_kotlin.py <file.kt> --apply
    python3 scripts/split_kotlin.py <file.kt> --apply --max-kb 45

Run the project's compile and tests after applying; this tool only guarantees
that the bytes moved.
"""

import argparse
import os
import re
import sys

DECL_KEYWORDS = ("fun", "val", "var", "class", "object", "interface", "typealias")
WORD = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
COMMENT_START = ("//", "/*", "*")


def scan(lines):
    """Per-line flags: True when the line starts outside every comment, string
    and bracketed expression, i.e. it is safe to cut immediately before it."""
    flags = []
    bdepth = pdepth = 0
    in_block_comment = in_raw_string = False
    for line in lines:
        flags.append(
            bdepth == 0
            and pdepth == 0
            and not in_block_comment
            and not in_raw_string
            and line[:1] not in ("", " ", "\t")
        )
        i, n = 0, len(line)
        while i < n:
            ch = line[i]
            two = line[i : i + 2]
            three = line[i : i + 3]
            if in_raw_string:
                if three == '"""':
                    in_raw_string = False
                    i += 3
                else:
                    i += 1
            elif in_block_comment:
                if two == "*/":
                    in_block_comment = False
                    i += 2
                else:
                    i += 1
            elif two == "//":
                break
            elif two == "/*":
                in_block_comment = True
                i += 2
            elif three == '"""':
                in_raw_string = True
                i += 3
            elif ch in "\"'":
                quote = ch
                i += 1
                while i < n:
                    if line[i] == "\\":
                        i += 2
                    elif line[i] == quote:
                        break
                    else:
                        i += 1
                i += 1
            else:
                if ch == "{":
                    bdepth += 1
                elif ch == "}":
                    bdepth -= 1
                elif ch in "([":
                    pdepth += 1
                elif ch in ")]":
                    pdepth -= 1
                i += 1
    return flags


def is_decl(line):
    if line.startswith(COMMENT_START):
        return False
    head = line.split("=")[0].split("(")[0]
    return any(w in DECL_KEYWORDS for w in WORD.findall(head))


def decl_name(line):
    m = re.search(
        r"\b(fun|val|var|class|object|interface|typealias)\s+"
        r"(?:<[^>]*>\s*)?(?:[A-Za-z_][A-Za-z0-9_]*\.)?([A-Za-z_][A-Za-z0-9_]*)",
        line,
    )
    return m.group(2) if m else None


def decl_names(text):
    lines = as_lines(text)
    flags = scan(lines)
    return [decl_name(lines[i]) for i in range(len(lines)) if flags[i] and is_decl(lines[i])]


def as_lines(text):
    lines = text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    return [l + "\n" for l in lines]


def attached_start(lines, idx):
    """Walk back over the KDoc/annotation/region comment run above `idx`.

    A single blank line between two comment runs does not end the run: section
    dividers in this codebase sit above the declaration they introduce, with a
    blank line after them, and leaving a divider at the tail of the previous
    part would read as if it labelled the wrong block.
    """
    start = idx
    blank_seen = False
    while start > 0:
        prev = lines[start - 1].strip()
        if prev.startswith(("//", "/*", "*", "*/", "@")):
            start -= 1
            blank_seen = False
            continue
        if not prev and not blank_seen:
            start -= 1
            blank_seen = True
            continue
        break
    while start < idx and not lines[start].strip():
        start += 1
    return start


def prologue_end(lines):
    """Index of the first line that is not package/import/`@file:`/comment."""
    i = 0
    while i < len(lines):
        s = lines[i].strip()
        if not s or s.startswith(("package ", "import ", "@file:")) or s.startswith(COMMENT_START):
            i += 1
            continue
        break
    while i > 0 and not lines[i - 1].strip():
        i -= 1
    return i


def size_of(chunk):
    return len("".join(chunk).encode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--max-kb", type=float, default=50.0)
    ap.add_argument("--suffix", default="Part")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    path = args.path
    text = open(path, encoding="utf-8").read()
    lines = as_lines(text)

    max_bytes = int(args.max_kb * 1024)
    flags = scan(lines)
    pro_end = prologue_end(lines)
    candidates = sorted(
        {
            attached_start(lines, i)
            for i in range(len(lines))
            if flags[i] and is_decl(lines[i])
        }
    )
    candidates = [c for c in candidates if c > pro_end]

    total = size_of(lines)
    print(f"{path}: {len(text)} bytes, {len(lines)} lines, {len(candidates)} cut points")
    print(f"  prologue: {pro_end} lines / {size_of(lines[:pro_end])} bytes")
    if total <= max_bytes:
        print("  nothing to do: the file already fits in one part")
        return 0

    n_parts = (total + max_bytes - 1) // max_bytes
    cuts = []
    for k in range(1, n_parts):
        target = total * k // n_parts
        best = min(candidates, key=lambda c: abs(size_of(lines[:c]) - target))
        if best not in cuts:
            cuts.append(best)
    cuts.sort()
    bounds = [pro_end] + cuts + [len(lines)]

    for i in range(len(bounds) - 1):
        a, b = bounds[i], bounds[i + 1]
        chunk = lines[a:b]
        first = next((l.strip() for l in chunk if l.strip()), "-")
        last = next((l.strip() for l in reversed(chunk) if l.strip()), "-")
        print(f"  part {i + 1}: lines {a + 1}-{b}  {size_of(chunk):>7} bytes")
        print(f"            {first[:66]}")
        print(f"      ...{last[:66]}")

    if not args.apply:
        print("\ndry run: pass --apply to write the parts")
        return 0

    # `expected` maps an absolute line index to that line's final text, so a
    # visibility change is recorded once and every later comparison is exact.
    expected = {i: lines[i] for i in range(len(lines))}
    privates = {}
    for i in range(pro_end, len(lines)):
        if flags[i] and re.match(r"^\s*private\s+(?:(?:const|inline|suspend|operator|infix)\s+)*", lines[i]):
            name = decl_name(lines[i])
            if name:
                privates[i] = name

    promotions = []
    for idx, name in privates.items():
        owner = next(i for i in range(len(bounds) - 1) if bounds[i] <= idx < bounds[i + 1])
        pattern = r"(?<![A-Za-z0-9_])" + re.escape(name) + r"(?![A-Za-z0-9_])"
        for j in range(len(bounds) - 1):
            if j == owner:
                continue
            chunk = "".join(lines[x] for x in range(bounds[j], bounds[j + 1]))
            if re.search(pattern, chunk):
                expected[idx] = re.sub(r"^(\s*)private\s+", r"\1internal ", lines[idx], count=1)
                promotions.append((name, owner + 1, j + 1))
                break

    base, ext = os.path.splitext(path)
    prologue = "".join(lines[:pro_end])
    outputs = [
        (path, pro_end, prologue + "".join(expected[x] for x in range(pro_end, bounds[1])))
    ]
    for i in range(1, len(bounds) - 1):
        a, b = bounds[i], bounds[i + 1]
        note = (
            f"// Part {i + 1} of the {os.path.basename(path)} split. This file's\n"
            f"// declarations were moved here verbatim by scripts/split_kotlin.py,\n"
            f"// which cuts only at top-level boundaries -- the editor's file view\n"
            f"// stops at ~60 KB, so the original had an unreachable tail. Only the\n"
            f"// declarations another part calls were widened to `internal`.\n"
        )
        body = "".join(expected[x] for x in range(a, b))
        outputs.append(
            # prologue, the blank line before the note, the note, the blank
            # line after it: skip all of them when comparing bodies below.
            (f"{base}{args.suffix}{i + 1}{ext}", pro_end + note.count("\n") + 2, prologue + "\n" + note + "\n" + body)
        )

    # Verify before writing: strip each output's prologue (and this tool's note)
    # and the bodies must concatenate back to the body that was read. Duplicate
    # or missing declarations are caught separately, which is the failure mode
    # a wrong slice actually produces.
    rebuilt = ""
    for _, skip, content in outputs:
        rebuilt += "".join(as_lines(content)[skip:])
    original = "".join(expected[i] for i in range(pro_end, len(lines)))
    names_orig = decl_names("".join(lines[pro_end:]))
    names_parts = []
    for _, _, content in outputs:
        names_parts.extend(decl_names(content))
    ok = rebuilt == original and sorted(names_parts) == sorted(x for x in names_orig if x)
    print(f"  self-check: bodies {len(rebuilt)} vs {len(original)} bytes, "
          f"{len(names_parts)} declarations across parts vs {len([n for n in names_orig if n])} in the original")
    if not ok:
        print("  REFUSING to write: the parts do not reproduce the original", file=sys.stderr)
        return 1

    for p, _, content in outputs:
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(content)
        print(f"  wrote {p} ({len(content)} bytes)")
    for name, owner, user in promotions:
        print(f"  {name}: private in part {owner} -> internal (called from part {user})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
