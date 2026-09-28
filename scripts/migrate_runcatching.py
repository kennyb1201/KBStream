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
Of those, the ones this tool can prove SAFE are rewritten without being asked.
The proof always has the same meaning - nothing runs after the expression - and
there are three ways to see it:

    - the whole expression, call and any trailing `.onFailure { }` chain, is the
      LAST thing in its enclosing block; or
    - it is the entire body of a `when` branch or a brace-less lambda
      (`A -> runCatching { ... }.getOrElse { ... }`), which is the same thing
      written without braces: the construct ends with the expression; or
    - it is the expression of a `return`, which ends the path by definition.

Everything else is reported as REVIEW and left alone, because converting it
means the statements after it are skipped when the coroutine is cancelled. That
is right for a fetch feeding UI state and wrong for bookkeeping that has to
happen either way - the classic example is a bare
`runCatching { client.signOut() }` with the sign-out prefs clearing and state
update on the lines below it. That judgement is a human's, so a REVIEW site is
converted only when a reviewer names it in the file passed to --approve. The
remaining sites in this tree were swept that way in one pass, entry by entry,
and the two that had to stay are marked in the code (see KEEP_MARKERS).

A clean report is `0 safe, 0 need review`. What may still be printed:

    NAME-ONLY  the block calls a name that is ALSO a plain `fun` declared
               somewhere in this tree (`build`, `apply`, `load`, `resolve`,
               `collect`...). Name matching cannot say which declaration a call
               resolves to, and the collisions are dominated by libraries -
               OkHttp's `Request.Builder.build()`, SharedPreferences'
               `Editor.apply()`, Coil's `ImageView.load()`, `File.resolve()` -
               so these are printed to be read, never converted automatically.
               A site here whose call IS a suspend function (there are a few,
               e.g. `MdbListClient.addToList`) can be approved like any other,
               but only with a `# reason` naming that call.
    kept       the site carries the marker comment above it. Nothing to do.

Sites the tool does not report at all: blocks that name nothing suspending, and
blocks that sit where a suspend call cannot be written - directly in a
non-suspend `fun` body, in a class/object body or in an `init` block (see
nonsuspending_enclosing_block).

Usage:
    python3 scripts/migrate_runcatching.py                # report only
    python3 scripts/migrate_runcatching.py --apply        # rewrite the SAFE set
    python3 scripts/migrate_runcatching.py --apply --approve reviews.txt
    python3 scripts/migrate_runcatching.py --only data/sync  # scope the report

--approve takes a file of `path:line` entries (one reviewer decision each, with
an optional `# why`), and refuses the run if an entry does not name a site it
reported, so a stale list cannot silently approve nothing.

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
PLAIN_DECL = re.compile(
    r"(?<!suspend )\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z0-9_.<>?]+\.)?([A-Za-z0-9_]+)\s*\("
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
# What can follow a `when` branch body: the next branch's arrow, or `else ->`.
# A branch label that is an identifier (`Foo ->`) is left as REVIEW - it cannot
# be told apart from a lambda parameter list without real parsing.
NEXT_BRANCH = re.compile(r"(?:->|else\s*->)")


def consume_chain(masked, start):
    """End of the expression starting at `start`, following `.foo(...)`/`.foo {}`.

    The whitespace skip before `(` / `{` is load-bearing, not tidiness: this
    tree formats trailing lambdas as `.onFailure { error -> ... }` with a space,
    and a chain reader that only accepted the brace when it touched the
    identifier read every one of those chains as ending at `.onFailure` - so
    the caller saw an unterminated expression followed by more code and
    reported the site as REVIEW. That is how a rule this conservative ends up
    with 316 untouched sites, most of them the last statement in their block.
    """
    pos = start
    while True:
        m = CHAIN_LINK.match(masked, pos)
        if not m:
            return pos
        after = m.end()
        probe = after
        while probe < len(masked) and masked[probe] in " \t\r\n":
            probe += 1
        if probe < len(masked) and masked[probe] in "({":
            try:
                after = match_parens(masked, probe) if masked[probe] == "(" else match_block(masked, probe)
            except ValueError:
                return pos
            pos = after
        elif after < len(masked) and masked[after] == "<":
            return pos  # a generic call: not worth parsing, leave it as REVIEW
        else:
            # a property access continues the chain but there is nothing to
            # consume; a bare `.something` with nothing after it is the end.
            pos = probe
            if pos < len(masked) and masked[pos] != ".":
                return pos


def next_significant(masked, pos):
    while pos < len(masked) and masked[pos] in " \t\r\n":
        pos += 1
    return pos, masked[pos] if pos < len(masked) else ""


def enclosing_brace(masked, pos):
    """Index of the `{` that opens the block around `pos`, or None at top level."""
    depth = 0
    for i in range(pos, -1, -1):
        if masked[i] == "}":
            depth += 1
        elif masked[i] == "{":
            if depth == 0:
                return i
            depth -= 1
    return None


FUN_HEAD = re.compile(r"\bfun\b[^=]*\([^()]*\)\s*(?::\s*[^={]+)?\s*$")
CLASS_HEAD = re.compile(r"\b(?:class|object|interface)\b[^{}]*$")
INIT_HEAD = re.compile(r"\binit\s*$")


def nonsuspending_enclosing_block(masked, call_start):
    """True when the block around the call cannot hold a suspending call.

    Kotlin reaches a suspend call only from a suspend function body or from a
    lambda the callee takes as a suspend lambda (launch, async, withContext,
    flow...). A call sitting DIRECTLY in the body of a non-suspend `fun`, in a
    class/object body (a property initializer) or in an `init` block therefore
    cannot suspend, whatever its block happens to name - so a name match there
    is noise a reviewer has to clear by hand. Note the directness: this looks at
    the NEAREST enclosing block, so a call inside `launch { }` inside a
    non-suspend function is untouched.

    Blocks that INHERIT the surrounding context (if/when/try/for/synchronized
    bodies, and lambdas generally) are left alone: this says nothing about them.
    """
    open_at = enclosing_brace(masked, call_start)
    if open_at is None:
        return False
    seg = masked[max(0, open_at - 300):open_at]
    seg = seg[seg.rfind("}") + 1:]
    if FUN_HEAD.search(seg):
        return not re.search(r"\bsuspend\b", seg)
    if CLASS_HEAD.search(seg) or INIT_HEAD.search(seg):
        return True
    return False


def classify(masked, call_start):
    """('SAFE'|'REVIEW'|'AMBIGUOUS', end of expression, note) or None.

    None means "not a suspending site" - the block calls nothing that looks like
    a suspend, or it sits somewhere a suspend call cannot be written at all, so
    there is no cancellation for it to swallow.
    """
    block_end = match_block(masked, call_start)
    body = masked[call_start:block_end]
    # A block that reaches its suspend calls through runBlocking is a BLOCKING
    # site on a deliberately non-suspend path - typically a defensive one (see
    # SupabaseSync.persistSessionBeforeProcessExit, on the APK-install exit
    # path, where nothing may throw). It has no cancellation of its own to
    # swallow, so it is not this rule's business.
    if "runBlocking" in body:
        return None
    if nonsuspending_enclosing_block(masked, call_start):
        return None
    names = block_suspend_names(body)
    if not names:
        return None
    expr_end = consume_chain(masked, block_end)
    pos, ch = next_significant(masked, expr_end)
    # Every name that matched is ALSO declared as a plain `fun` somewhere in the
    # tree, so the block may not suspend at all: `build()` on a request builder,
    # `prefs.edit().apply()`, an mpv `load()`. Name matching cannot say which
    # declaration a call resolves to, and a runCatchingCancellable around work
    # that cannot be cancelled buys nothing (it renames the call and claims a
    # guarantee the code does not need - see RunCatchingCancellable.kt). Report
    # it so a human can look, but never rewrite on this evidence.
    if names <= AMBIGUOUS_NAMES:
        return ("AMBIGUOUS", pos, ", ".join(sorted(names)))
    if ch in ("", "}") or preceded_by_return(masked, call_start):
        return ("SAFE", pos, "")
    # A `when` branch body and a lambda body can be a bare expression with no
    # braces: `A -> runCatching { discover(...) }.getOrElse { emptyList() }`.
    # Brace matching sees the NEXT branch (`->`) after it and calls that "code
    # after the expression", but there is no code after the expression: the
    # construct ended. So when the call is the first thing after a `->` and what
    # follows the chain is the next `->` or the closing brace, this is the same
    # case as above - nothing else in this execution path can run.
    if NEXT_BRANCH.match(masked, pos) and preceded_by_arrow(masked, call_start):
        return ("SAFE", pos, "")
    return ("REVIEW", pos, "")


def block_suspend_names(body):
    return set(IDENT_CALL.findall(body)) & SUSPEND_NAMES


# `return runCatching { ... }` (and its `return@label` form): the statement ends
# the enclosing function or lambda, so there is nothing after it to skip.
RETURN_BEFORE = re.compile(r"(?<![A-Za-z0-9_])(?:return|return@[A-Za-z0-9_]+)\s*$")


def preceded_by_return(masked, call_start):
    return bool(RETURN_BEFORE.search(masked[max(0, call_start - 40):call_start]))


def preceded_by_arrow(masked, call_start):
    """True when only whitespace sits between a `->` and `call_start`.

    That is what makes the call the WHOLE body of the construct it sits in: a
    `when` branch or a lambda written without braces. A lambda body can hold
    several statements (`{ x -> a(); b() }`), which is why the caller still has
    to check that nothing but the next branch or the closing brace follows.
    """
    i = call_start - 1
    while i >= 0 and masked[i] in " \t\r\n":
        i -= 1
    return i >= 1 and masked[i] == ">" and masked[i - 1] == "-"


SUSPEND_NAMES = set()
# Names declared BOTH as `suspend fun name(` and as a plain `fun name(` in this
# tree. 17 of the 493 suspend names are in here (build, apply, load, resolve,
# search, run, count, download, scrobble, ...), which is exactly the set of
# names a call site cannot be resolved by spelling alone.
AMBIGUOUS_NAMES = set()


def collect_suspend_names(files):
    plain = set()
    for path in files:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
        for m in SUSPEND_DECL.finditer(text):
            SUSPEND_NAMES.add(m.group(1))
        for m in PLAIN_DECL.finditer(text):
            plain.add(m.group(1))
    SUSPEND_NAMES.update(STDLIB_SUSPEND)
    AMBIGUOUS_NAMES.clear()
    AMBIGUOUS_NAMES.update(SUSPEND_NAMES & plain)


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


# A site a human has looked at and decided must keep plain `runCatching` says so
# in a comment above it. The marker is prose on purpose: it is read far more
# often than the tool, and a reader who sees `plain runCatching` above a call
# should be able to tell why without opening the tool.
KEEP_MARKERS = ("runcatching, deliberately", "plain runcatching")


def keep_reason(text, call_start):
    """The comment above the site that says it is meant to stay, or None.

    The window is a few lines, not one: the reason takes more than a line to
    write and the marker is normally the first line of that paragraph. Matching
    is case-insensitive so the comment can start a sentence.
    """
    # Back to the previous blank line, which is where the comment paragraph
    # above the site starts (caps at 900 chars so an unbroken block cannot pull
    # in an unrelated comment far above it).
    raw = text[max(0, call_start - 900):call_start]
    cut = raw.rfind("\n\n")
    if cut >= 0:
        raw = raw[cut:]
    lower = raw.lower()
    for marker in KEEP_MARKERS:
        at = lower.rfind(marker)
        if at >= 0:
            start = raw.rfind("\n", 0, at) + 1
            end = raw.find("\n", at)
            line = raw[start:end if end >= 0 else len(raw)]
            return " ".join(line.replace("//", " ").split())
    return None


def sites_in(path):
    """(line, start, kind, after, note, text, masked) for every runCatching."""
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
        kind, after, note = verdict
        reason = keep_reason(text, m.start())
        if reason:
            kind, note = "KEPT", reason
        out.append((text.count("\n", 0, m.start()) + 1, m.start(), kind, after, note, text, masked))
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


def load_approvals(path):
    """`path:line` per line, `#` comments ignored: sites a human reviewed.

    A REVIEW site is a call that suspends mid-block, so converting it means the
    statements after it are skipped when the coroutine is cancelled. That is the
    right answer for the great majority of them (a fetch feeding UI state or a
    cache) and the wrong one for bookkeeping that has to happen either way, and
    nothing mechanical can tell the two apart. So the tool never converts one on
    its own, and this is how the review is fed back in: the reviewer names the
    sites that are safe to convert, and everything not named stays as it is and
    is reported again on the next run.
    """
    approved = {}
    with open(path, encoding="utf-8") as fh:
        for raw in fh:
            entry, _, reason = raw.partition("#")
            entry = entry.strip()
            if not entry:
                continue
            file, _, line = entry.rpartition(":")
            if not file or not line.isdigit():
                raise SystemExit(f"bad entry in {path}: {raw.strip()!r}")
            approved[(file.strip(), int(line))] = reason.strip()
    return approved


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--apply", action="store_true", help="rewrite the SAFE sites")
    ap.add_argument("--only", default=None, help="substring filter on the path")
    ap.add_argument("--quiet", action="store_true", help="counts only")
    ap.add_argument(
        "--approve",
        default=None,
        metavar="FILE",
        help="file of reviewed `path:line` sites to convert despite being REVIEW",
    )
    args = ap.parse_args()

    files = kt_files(args.only)
    collect_suspend_names(kt_files())
    approved = load_approvals(args.approve) if args.approve else {}
    unused = set(approved)

    safe = review = 0
    kept = 0
    renamed_labels = 0
    touched = []
    name_only = []
    for path in files:
        sites = sites_in(path)
        if not sites:
            continue
        edits = []
        for line, start, kind, after, note, text, masked in sites:
            ok = (path, line) in approved
            if ok:
                unused.discard((path, line))
                # A name-only site was approved by spelling alone, which the tool
                # has just said it cannot resolve. Approving one is allowed -
                # the reviewer can read the call and the tool cannot - but it
                # has to be said WHY, so the entry carries the evidence.
                if kind == "AMBIGUOUS" and not approved[(path, line)]:
                    raise SystemExit(
                        f"{path}:{line} matches a suspend name only ambiguously "
                        "- approve it with a `# reason` naming the suspending "
                        "call in the block"
                    )
            if kind == "KEPT":
                kept += 1
                if not args.quiet:
                    print(f"  kept   {path}:{line}  ({note})")
            elif kind == "SAFE" or ok:
                safe += 1
                edits.append(start)
            else:
                tail = " ".join(masked[after:after + 55].split())
                if kind == "AMBIGUOUS":
                    name_only.append(f"  NAME-ONLY {path}:{line}  [{note}] ~ ...{tail}")
                else:
                    review += 1
                    if not args.quiet:
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

    # The name-only sites go last and separately: every one of them matched a
    # name that is ALSO a plain fun somewhere in this tree (`build`,
    # `apply`, `load`, `resolve`, `collect`), and that list is dominated by the
    # stdlib and by libraries - OkHttp's Request.Builder.build(),
    # SharedPreferences.Editor.apply(), Coil's ImageView.load(), File.resolve().
    # They are printed so the next reader can confirm there is no suspending call
    # in the block, not because any of them is a rewrite candidate.
    if not args.quiet:
        for entry in name_only:
            print(entry)

    print(f"\nsuspend call sites: {safe + review + kept + len(name_only)}"
          f"  ({safe} safe, {review} need review, {len(name_only)} name-only"
          f", {kept} reviewed to stay)")
    if unused:
        for entry in sorted(unused):
            print(f"  APPROVED BUT NOT FOUND: {entry[0]}:{entry[1]}")
        return 1 if args.apply else 0
    if args.apply:
        print(f"rewritten: {safe} sites in {len(touched)} files"
              f" ({renamed_labels} @runCatching labels renamed)")
    else:
        print("report only: pass --apply to rewrite the safe set")
    return 0


if __name__ == "__main__":
    sys.exit(main())
