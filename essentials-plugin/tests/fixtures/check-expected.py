#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3"]
# ///
"""check-expected — keep the fixtures' machine-readable expectations honest.

Every fixture under tests/fixtures/ carries its expectations in two files: TEST-GUIDE.md (for a
human) and expected.yaml (for a grader). The sources themselves carry no answer labels, because a
model graded on a fixture reads its sources. This script checks the two halves of that contract:

  1. expected.yaml is true to the tree
     - every `file` it names exists (relative to the fixture directory);
     - every entry with a `line` also has an `anchor`, and the anchor text is on that line;
     - an `anchor` without a `line` is an error (the generator resolves one to the other);
     - `ess` is `ESS-G<gate>` when `gate` is set, and null when `gate` is null;
     - ids are unique within must_find, must_not_find and tolerated;
     - every `severity` is Blocking, Should-fix, Advisory or null.
  2. no fixture source carries an answer hint — FINDING/TRAP/CLEAN labels, "not a finding",
     "must not be reported", gate numbers, "ground truth", "false positive", the words fixture,
     oracle, deliberate(ly), planted, or a slice-check/slice-discover/TEST-GUIDE reference.
     Only the expectation files (TEST-GUIDE.md, expected.yaml, cases.yaml) may say those things.

A fixture directory with a TEST-GUIDE.md and neither expected.yaml nor cases.yaml (the change-router's
case table) is also an error: decision (4) makes every fixture's expectations machine-readable.

Usage
-----
    check-expected.py [--fixtures DIR] [--quiet]
    check-expected.py --self-test

    --fixtures DIR   the fixtures root (default: the directory holding this script)

Exit codes
----------
    0   clean
    1   findings (stale anchors, missing paths, hints in sources, …)
    2   an expected.yaml does not parse, or bad usage
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

import yaml

# Directories that are not model-graded source trees: slice-map is renderer input with its own
# check (render-check.py + expected.json).
EXCLUDED = {"slice-map"}

# The only files in a fixture that may name findings and traps.
EXPECTATION_FILES = {"TEST-GUIDE.md", "expected.yaml", "cases.yaml"}

HINTS: list[tuple[str, re.Pattern[str]]] = [
    ("label", re.compile(r"\b(FINDING|TRAP|CLEAN)\b")),
    ("verdict", re.compile(r"\bnot a (finding|violation|query surface)\b", re.I)),
    ("verdict", re.compile(r"\bmust not (be )?(flag|report)", re.I)),
    ("gate", re.compile(r"\bgates?[- ]?\d+", re.I)),
    ("answer", re.compile(r"\b(ground truth|false positives?|oracle|fixture|planted)\b", re.I)),
    ("answer", re.compile(r"\bdeliberate(ly)?\b", re.I)),
    ("command", re.compile(r"\bslice-(check|discover)\b|TEST-GUIDE|expected\.yaml", re.I)),
]

LIST_KEYS = ("must_find", "must_not_find", "tolerated")
SEVERITIES = {"Blocking", "Should-fix", "Advisory", None}


class Problem:
    def __init__(self, fixture: str, where: str, message: str):
        self.fixture = fixture
        self.where = where
        self.message = message

    def __str__(self) -> str:
        return f"{self.fixture}/{self.where}: {self.message}"


def fixture_dirs(root: Path) -> list[Path]:
    return sorted(p for p in root.iterdir() if p.is_dir() and p.name not in EXCLUDED and not p.name.startswith("."))


def walk_entries(node: object, trail: str):
    """Yield (trail, dict) for every mapping in the document."""
    if isinstance(node, dict):
        yield trail, node
        for k, v in node.items():
            yield from walk_entries(v, f"{trail}.{k}" if trail else str(k))
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from walk_entries(v, f"{trail}[{i}]")


def check_expected(fixture: Path, problems: list[Problem]) -> bool:
    """Return False when expected.yaml cannot be parsed (exit 2)."""
    name = fixture.name
    exp = fixture / "expected.yaml"
    if not exp.exists():
        if (fixture / "TEST-GUIDE.md").exists() and not (fixture / "cases.yaml").exists():
            problems.append(Problem(name, "expected.yaml", "missing (the fixture has a TEST-GUIDE.md and no cases.yaml)"))
        return True
    try:
        doc = yaml.safe_load(exp.read_text(encoding="utf-8"))
    except yaml.YAMLError as e:
        problems.append(Problem(name, "expected.yaml", f"does not parse: {e}"))
        return False
    if not isinstance(doc, dict):
        problems.append(Problem(name, "expected.yaml", "top level is not a mapping"))
        return False

    lines_cache: dict[Path, list[str]] = {}
    for trail, entry in walk_entries(doc, ""):
        if "anchor" in entry and "line" not in entry:
            problems.append(Problem(name, "expected.yaml", f"{trail}: anchor without a line"))
        f = entry.get("file")
        if f is None:
            continue
        if not isinstance(f, str):
            problems.append(Problem(name, "expected.yaml", f"{trail}.file is not a string"))
            continue
        target = fixture / f
        if not target.exists():
            problems.append(Problem(name, "expected.yaml", f"{trail}: {f} does not exist"))
            continue
        line = entry.get("line")
        if line is None:
            continue
        anchor = entry.get("anchor")
        if not isinstance(line, int) or not isinstance(anchor, str):
            problems.append(Problem(name, "expected.yaml", f"{trail}: line must be an int with a string anchor"))
            continue
        if target.is_dir():
            problems.append(Problem(name, "expected.yaml", f"{trail}: {f} is a directory but has a line"))
            continue
        lines = lines_cache.get(target)
        if lines is None:
            lines = target.read_text(encoding="utf-8", errors="replace").splitlines()
            lines_cache[target] = lines
        if not 1 <= line <= len(lines):
            problems.append(Problem(name, "expected.yaml", f"{trail}: {f}:{line} is past the end of the file"))
        elif anchor not in lines[line - 1]:
            hits = [i for i, text in enumerate(lines, 1) if anchor in text]
            now = f"now at line {hits[0]}" if len(hits) == 1 else f"found on {len(hits)} lines"
            problems.append(Problem(name, "expected.yaml", f"{trail}: anchor {anchor!r} not on {f}:{line} ({now})"))

    for trail, entry in walk_entries(doc, ""):
        if "severity" in entry and entry.get("severity") not in SEVERITIES:
            problems.append(Problem(name, "expected.yaml", f"{trail}: severity {entry.get('severity')!r} is not one of "
                                                           "Blocking, Should-fix, Advisory, null"))

    for key in LIST_KEYS:
        seen: set[str] = set()
        items = doc.get(key) or []
        if not isinstance(items, list):
            problems.append(Problem(name, "expected.yaml", f"{key} is not a list"))
            continue
        for i, item in enumerate(items):
            if not isinstance(item, dict):
                continue
            ident = item.get("id")
            if ident is not None:
                if str(ident) in seen:
                    problems.append(Problem(name, "expected.yaml", f"{key}[{i}]: duplicate id {ident}"))
                seen.add(str(ident))
            if "gate" in item:
                gate, ess = item.get("gate"), item.get("ess")
                want = None if gate is None else f"ESS-G{gate}"
                if ess != want:
                    problems.append(Problem(name, "expected.yaml", f"{key}[{i}]: ess is {ess!r}, gate {gate!r} wants {want!r}"))
    return True


def check_hints(fixture: Path, problems: list[Problem]) -> None:
    for path in sorted(fixture.rglob("*")):
        if not path.is_file() or path.name in EXPECTATION_FILES:
            continue
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        rel = path.relative_to(fixture).as_posix()
        for n, line in enumerate(text.splitlines(), 1):
            for kind, pattern in HINTS:
                m = pattern.search(line)
                if m is not None:
                    problems.append(Problem(fixture.name, f"{rel}:{n}", f"answer hint ({kind}): {m.group(0)!r}"))
                    break


def run(root: Path, quiet: bool) -> int:
    problems: list[Problem] = []
    parsed = True
    fixtures = fixture_dirs(root)
    for fixture in fixtures:
        parsed = check_expected(fixture, problems) and parsed
        check_hints(fixture, problems)
    for p in problems:
        print(p)
    if not quiet:
        print(f"{len(fixtures)} fixture(s) checked, {len(problems)} problem(s).")
    if not parsed:
        return 2
    return 1 if problems else 0


def self_test() -> int:
    good_src = "package x;\n\npublic class Order {\n    public void cancel() {}\n}\n"
    good_exp = (
        "fixture: demo\n"
        "must_find:\n"
        "  - id: F1\n    gate: \"8a\"\n    ess: ESS-G8a\n    file: src/Order.java\n    line: 4\n"
        "    anchor: \"public void cancel()\"\n"
        "must_not_find:\n"
        "  - id: T1\n    not_gates: [\"*\"]\n    file: src/\n"
    )
    cases = [
        ("clean", good_src, good_exp, 0, []),
        ("stale line", good_src, good_exp.replace("line: 4", "line: 3"), 1, ["not on src/Order.java:3 (now at line 4)"]),
        ("missing file", good_src, good_exp.replace("src/Order.java", "src/Gone.java"), 1, ["does not exist"]),
        ("ess mismatch", good_src, good_exp.replace("ESS-G8a", "ESS-G8(a)"), 1, ["wants 'ESS-G8a'"]),
        ("duplicate id", good_src, good_exp.replace("id: T1", "id: F1").replace("must_not_find:", "must_find_extra:")
         + "tolerated:\n  - id: X\n  - id: X\n", 1, ["duplicate id X"]),
        ("hint label", good_src.replace("public class", "/** TRAP: fine. */\npublic class"), good_exp.replace("line: 4", "line: 5"),
         1, ["answer hint (label): 'TRAP'"]),
        ("hint gate", good_src.replace("package x;", "package x; // gate 14 ignores this"), good_exp, 1,
         ["answer hint (gate): 'gate 14'"]),
        ("hint verdict", good_src.replace("package x;", "// Not a finding."), good_exp, 1, ["answer hint (verdict)"]),
        ("bad severity", good_src, good_exp.replace("    gate:", "    severity: Should-fi\n    gate:"), 1,
         ["severity 'Should-fi' is not one of"]),
        ("anchor no line", good_src, good_exp.replace("    line: 4\n", ""), 1, ["anchor without a line"]),
        ("unparseable", good_src, "must_find: [\n", 2, ["does not parse"]),
    ]
    failures = 0
    for label, src, exp, want_code, want_msgs in cases:
        with tempfile.TemporaryDirectory() as tmp:
            fx = Path(tmp) / "demo"
            (fx / "src").mkdir(parents=True)
            (fx / "src" / "Order.java").write_text(src, encoding="utf-8")
            (fx / "expected.yaml").write_text(exp, encoding="utf-8")
            (fx / "TEST-GUIDE.md").write_text("TRAP and FINDING are allowed here; gate 14.\n", encoding="utf-8")
            problems: list[Problem] = []
            parsed = check_expected(fx, problems)
            check_hints(fx, problems)
            code = 2 if not parsed else (1 if problems else 0)
            text = "\n".join(str(p) for p in problems)
            ok = code == want_code and all(m in text for m in want_msgs)
            if not ok:
                failures += 1
                print(f"FAIL {label}: exit {code} (want {want_code})\n{text}")
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "bare").mkdir()
        (Path(tmp) / "bare" / "TEST-GUIDE.md").write_text("x\n", encoding="utf-8")
        problems = []
        check_expected(Path(tmp) / "bare", problems)
        if not any("missing" in str(p) for p in problems):
            failures += 1
            print("FAIL missing expected.yaml not reported")
    with tempfile.TemporaryDirectory() as tmp:
        (Path(tmp) / "cases").mkdir()
        (Path(tmp) / "cases" / "TEST-GUIDE.md").write_text("x\n", encoding="utf-8")
        (Path(tmp) / "cases" / "cases.yaml").write_text("cases: []\n", encoding="utf-8")
        problems = []
        check_expected(Path(tmp) / "cases", problems)
        if problems:
            failures += 1
            print("FAIL a cases.yaml fixture must not need an expected.yaml")
    print(f"self-test: {len(cases) + 2 - failures}/{len(cases) + 2} passed")
    return 1 if failures else 0


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Check the fixtures' expected.yaml files and that sources carry no answer hints.")
    ap.add_argument("--fixtures", type=Path, default=Path(__file__).resolve().parent)
    ap.add_argument("--quiet", action="store_true")
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)
    if args.self_test:
        return self_test()
    if not args.fixtures.is_dir():
        print(f"not a directory: {args.fixtures}", file=sys.stderr)
        return 2
    return run(args.fixtures, args.quiet)


if __name__ == "__main__":
    sys.exit(main())
