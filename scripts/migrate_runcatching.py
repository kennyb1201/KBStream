#!/usr/bin/env python3
"""Move `runCatching` over SUSPENDING work to `runCatchingCancellable`.

`runCatching` catches Throwable, and coroutine cancellation arrives as a
CancellationException, so `runCatching` around a suspending call turns "this
coroutine was cancelled" into "this operation failed": the coroutine carries on
with the next statement, and the cancellation reaches `.onFailure` /
CrashReporter as if it were a fault (see
app/src/main/java/com/kennyb1201/kbstream/data/RunCatchingCancellable.kt).

Which sites are converted. A site is a `runCatching { ... }` whose block names a
suspending call - a `suspend fun` declared in this project, or one of the
stdlib suspends in STDLIB_SUSPEND (delay, withContext, await, emit, collect...).
Of those, only the ones this tool can prove SAFE are rewritten:

    the whole expression - the call and any trailing `.onFailure { }` style
    chain - is the LAST thing in its enclosing block, so nothing else in that
    lambda runs after it. Rethrowing then only stops what the cancellation
    already stopped.

Everything else is reported as REVIEW and left alone, because converting it
means the statements after it are skipped when the coroutine is cancelled. That
is right for a fetch feeding UI state and wrong for bookkeeping that has to
happen either way - the classic example is a bare
`runCatching { client.signOut() }` with the sign-out prefs clearing and state
update on the lines below it. Those need a human to move the must-run part into
`finally`/`withContext(NonCancellable)` (or to leave it as plain runCatching and
say so).

Usage:
    python3 scripts/migrate_runcatching.py                # report only
    python3 scripts/migrate_runcatching.py --apply        # rewrite the SAFE set
    python3 scripts/migrate_runcatching.py --only data/sync  # scope the report

The edit is text-for-text (the receiver and the block are untouched) plus the
helper import, so the diff is one word per site.
"""

import argparse
import os
import re
import sys

SRC_ROOT = "app/src/main/java"
HELPER_PACKAGE = "com.kennyb1201.kbstream.data"
HELPER_IMPORT = f"import {HELPER_PACKAGE}.runCatchingCancellable"

# Suspends that are not "suspend fun" declarations of this project but that any
# block calling them is suspending for. Deliberately short: every entry here
# decides a rewrite, so the noisy ones (launch, async, first, toList, stateIn,
# shareIn - none of which suspend) are out.
STDLIB_SUSPEND = {
    "delay", "withContext", "withTimeout", "withTimeoutOrNull", "await", "awaitAll",
    "yield", "emit", "collect", "collectLatest", "awaitClose",
    "suspendCancellableCoroutine", "join", "cancelAndJoin",
}

SUSPEND_DECL = re.compile(
    r"\bsuspend\s+fun\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>?]+\.)?([A-Za-z0-9_]+)\s*\("
)
IDENT_CALL = re.compile(r"\b([A-Za-z0-9_]+)\s*\(")


def mask(text):
    """A copy of `text` with string and comment CONTENT blanked to spaces.

    Same length as the input, so offsets found in the masked text index straight
    into the original. This is what makes brace matching safe around the
    raw-string SQL/XML blocks and the `//` comments this tree is full of.
    """
    out = list(text)
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            j = text.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif c == "/" and i + 1 < n and text[i + 1] == "*":
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if text[k] != "\n":
                    out[k] = " "
            i = j
        elif text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            for k in range(i, j):
                if text[k] != "\n":
                    out[k] = " "
            i = j
        elif c == '"' or c == "'":
            j = i + 1
            while j < n:
                if text[j] == "\\":
                    j += 2
                    continue
                if text[j] == c:
                    break
                j += 1
            j = min(j + 1, n)
            for k in range(i, j):
                if text[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def match_block(text, start):
    """Index just past the `{ ... }` block that starts at or after `start`."""
    i = text.index("{", start)
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "{":
            depth += 1
        elif text[j] == "}":
            depth -= 1
            if depth == 0:
                return j + 1
    raise ValueError("unbalanced block")


def match_parens(text, start):
    i = text.index("(", start)
    depth = 0
    for j in range(i, len(text)):
        if text[j] == "(":
            depth += 1
        elif text[j] == ")":
            depth -= 1
            if depth == 0:
                return j + 1
    raise ValueError("unbalanced parens")


CHAIN_LINK = re.compile(r"\s*\??\s*\.\s*[A-Za-z_][A-Za-z0-9_]*")


def consume_chain(masked, start):
    """End of the expression starting at `start`, following `.foo(...)`/`.foo {}`."""
    pos = start
    while True:
        m = CHAIN_LINK.match(masked, pos)
        if not m:
            return pos
        after = m.end()
        if after < len(masked) and masked[after] in "({":
            try:
                after = match_parens(masked, after) if masked[after] == "(" else match_block(masked, after)
            except ValueError:
                return pos
            pos = after
        elif after < len(masked) and masked[after] == "<":
            return pos  # a generic call: not worth parsing, leave it as REVIEW
        else:
            # a property access such as `.getOrNull()` is handled above; a bare
            # `.something` continues the chain but there is nothing to consume.
            pos = after
            if pos < len(masked) and masked[pos] != ".":
                return pos


def next_significant(masked, pos):
    while pos < len(masked) and masked[pos] in " \t\r\n":
        pos += 1
    return pos, masked[pos] if pos < len(masked) else ""


def classify(masked, call_start):
    """'SAFE', 'REVIEW' or None (not a suspending site)."""
    block_end = match_block(masked, call_start)
    body = masked[call_start:block_end]
    # A block that reaches its suspend calls through runBlocking is a BLOCKING
    # site on a deliberately non-suspend path - typically a defensive one (see
    # SupabaseSync.persistSessionBeforeProcessExit, on the APK-install exit
    # path, where nothing may throw). It has no cancellation of its own to
    # swallow, so it is not this rule's business.
    if "runBlocking" in body:
        return None
    if not block_calls_suspend(body):
        return None
    expr_end = consume_chain(masked, block_end)
    pos, ch = next_significant(masked, expr_end)
    if ch == "" or ch == "}":
        return ("SAFE", pos)
    return ("REVIEW", pos)


def block_calls_suspend(body):
    names = set(IDENT_CALL.findall(body))
    return bool(names & SUSPEND_NAMES)


SUSPEND_NAMES = set()


def collect_suspend_names(files):
    for path in files:
        with open(path, encoding="utf-8") as fh:
            for m in SUSPEND_DECL.finditer(fh.read()):
                SUSPEND_NAMES.add(m.group(1))
    SUSPEND_NAMES.update(STDLIB_SUSPEND)


def kt_files(only=None):
    found = []
    for dirpath, _, filenames in os.walk(SRC_ROOT):
        for name in filenames:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            if only and only not in path:
                continue
            found.append(path)
    return sorted(found)


CALL_SITE = re.compile(r"(?<![A-Za-z0-9_.])runCatching\s*\{")
CONVERTED_SITE = re.compile(r"(?<![A-Za-z0-9_.])runCatchingCancellable\s*\{")
# `return@runCatching null` uses the lambda's implicit label, which is the
# callee's name: renaming the call without renaming the label is a compile
# error ('return' is prohibited here), so the label follows the call.
STALE_LABEL = re.compile(r"@runCatching(?![A-Za-z0-9_])")


def fix_labels(text):
    """Rename `@runCatching` labels inside already-converted blocks."""
    masked = mask(text)
    edits = []
    for m in CONVERTED_SITE.finditer(masked):
        try:
            end = match_block(masked, m.start())
        except ValueError:
            continue
        for label in STALE_LABEL.finditer(masked, m.start(), end):
            edits.append(label.start())
    for pos in sorted(edits, reverse=True):
        text = text[:pos] + "@runCatchingCancellable" + text[pos + len("@runCatching"):]
    return text, len(edits)


def sites_in(path):
    """(line, kind) for every runCatching in `path`; kind is SAFE/REVIEW."""
    with open(path, encoding="utf-8") as fh:
        text = fh.read()
    masked = mask(text)
    out = []
    for m in CALL_SITE.finditer(masked):
        try:
            verdict = classify(masked, m.start())
        except ValueError:
            continue
        if verdict is None:
            continue
        kind, after = verdict
        out.append((text.count("\n", 0, m.start()) + 1, m.start(), kind, after, text, masked))
    return out


def with_import(text):
    if HELPER_IMPORT in text:
        return text
    lines = text.split("\n")
    pkg = next((l[8:].strip() for l in lines if l.startswith("package ")), "")
    if pkg == HELPER_PACKAGE:
        return text
    imports = [i for i, l in enumerate(lines) if l.startswith("import ")]
    if not imports:
        pkg_line = next(i for i, l in enumerate(lines) if l.startswith("package "))
        lines.insert(pkg_line + 1, "")
        lines.insert(pkg_line + 2, HELPER_IMPORT)
        return "\n".join(lines)
    block = [lines[i] for i in imports]
    if block == sorted(block):
        at = next(
            (i for i in imports if lines[i] > HELPER_IMPORT),
            imports[-1] + 1,
        )
    else:
        at = imports[-1] + 1
    lines.insert(at, HELPER_IMPORT)
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--apply", action="store_true", help="rewrite the SAFE sites")
    ap.add_argument("--only", default=None, help="substring filter on the path")
    ap.add_argument("--quiet", action="store_true", help="counts only")
    args = ap.parse_args()

    files = kt_files(args.only)
    collect_suspend_names(kt_files())

    safe = review = 0
    renamed_labels = 0
    touched = []
    for path in files:
        sites = sites_in(path)
        if not sites:
            continue
        edits = []
        for line, start, kind, after, text, masked in sites:
            if kind == "SAFE":
                safe += 1
                edits.append(start)
            else:
                review += 1
                if not args.quiet:
                    tail = " ".join(masked[after:after + 55].split())
                    print(f"  REVIEW {path}:{line} ~ ...{tail}")
        if not args.apply:
            continue
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        for start in sorted(edits, reverse=True):
            assert text[start:start + len("runCatching")] == "runCatching", path
            text = text[:start] + "runCatchingCancellable" + text[start + len("runCatching"):]
        text, labels = fix_labels(text)
        if edits or labels:
            text = with_import(text)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(text)
            touched.append(path)
            renamed_labels += labels

    print(f"\nsuspend call sites: {safe + review}"
          f"  ({safe} safe, {review} need review)")
    if args.apply:
        print(f"rewritten: {safe} sites in {len(touched)} files"
              f" ({renamed_labels} @runCatching labels renamed)")
    else:
        print("report only: pass --apply to rewrite the safe set")
    return 0


if __name__ == "__main__":
    sys.exit(main())
