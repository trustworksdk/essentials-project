#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3", "jsonschema==4.26.0"]
# ///
"""check-patches — the deterministic half of the /essentials:review judgement oracle.

Each `<case>.expected.yaml` here names a fixture, a patch and what /essentials:review must report for
it. The model-judged rows are graded by the eval suite; this script checks everything that needs no
model, so a fixture edit or a script change that silently invalidates a case fails here first:

1. the patch applies (`git apply --check`) to a copy of its fixture, without TEST-GUIDE.md/expected.yaml;
2. every `anchor` in the case sits on its `line` in the patched tree (must_find, dismissed, must_not_find);
3. `review-scan.py --diff <patch> --json` reports exactly `deterministic.review-scan` (exit code too);
4. `stack-lint.py`, `slice-lint.py` and `slice-source.py --check`, run on the patched tree and on the
   fixture, report exactly the `new` findings the case lists (head minus base, compared without line
   numbers, as commands/review.md Step 3d does), and stack-lint the listed `pre_existing` count;
5. the `fix.offers_in_order` rows are exactly the eligible rows of commands/review.md Step 6, in order.

Usage: check-patches.py [CASE …]      (default: every *.expected.yaml beside this script)
Exit: 0 every case holds · 1 a mismatch · 2 could not run: git missing, an unreadable case, or a checked
script that exits outside its contract or dies (reported as `could not run: <script>: <first error line>`,
never as a findings mismatch).
"""

from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
PLUGIN = HERE.parents[2]
SCRIPTS = PLUGIN / "scripts"
SEVERITY_ORDER = {"Blocking": 0, "Should-fix": 1, "Advisory": 2}


def run(cmd: list[str], cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)


def git(cwd: Path, *args: str) -> None:
    r = run(["git", "-c", "user.email=check@example.invalid", "-c", "user.name=check", *args], cwd)
    if r.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {r.stderr.strip()}")


class CouldNotRun(Exception):
    """A checked script crashed or exited outside its contract: no verdict on the case is possible."""


def first_error_line(stderr: str) -> str:
    """The line that names the failure: for a Python traceback or compile error, the exception line plus
    the innermost `File …, line N`; otherwise the first line of stderr."""
    lines = [ln.strip() for ln in stderr.splitlines() if ln.strip()]
    errors = [ln for ln in lines if re.match(r"[A-Za-z_][\w.]*(Error|Exception|Interrupt)\b", ln)]
    if not errors:
        return lines[0] if lines else ""
    where = [m for m in (re.match(r'File "([^"]+)", line (\d+)', ln) for ln in lines) if m]
    return errors[-1] + (f" ({Path(where[-1].group(1)).name}:{where[-1].group(2)})" if where else "")


def script_json(name: str, *args: str, ok: tuple[int, ...] = (0, 1)) -> tuple[int, dict]:
    """Runs a plugin script with --json. `ok` are the exit codes that mean it ran; exit 2 in `ok` means
    "could not run" is the expected outcome, so no JSON is read. Anything else raises CouldNotRun."""
    r = run([sys.executable, str(SCRIPTS / name), *args])
    if r.returncode not in ok:
        raise CouldNotRun(f"{name}: {first_error_line(r.stderr) or f'exit {r.returncode}'}")
    if r.returncode == 2:
        return 2, {}
    try:
        out = json.loads(r.stdout)
    except ValueError:
        out = None
    if not isinstance(out, dict):
        detail = first_error_line(r.stderr) or f"exit {r.returncode} with no JSON on stdout"
        raise CouldNotRun(f"{name}: {detail}")
    return r.returncode, out


def build(case: dict, work: Path) -> tuple[Path, Path, Path]:
    """Returns (base tree, head tree, patch)."""
    setup = case["setup"]
    fixture = PLUGIN / setup["fixture"]
    patch = PLUGIN / setup["patch"]
    base = work / "base"
    shutil.copytree(fixture, base, ignore=shutil.ignore_patterns(*setup.get("exclude", [])))
    head = work / "head"
    shutil.copytree(base, head)
    git(head, "init", "-q")
    git(head, "add", "-A")
    git(head, "commit", "-qm", "fixture")
    git(head, "apply", "--check", str(patch))
    git(head, "apply", str(patch))
    return base, head, patch


def check_anchors(case: dict, head: Path, errors: list[str]) -> None:
    for section in ("must_find", "dismissed", "must_not_find"):
        for entry in case.get(section) or []:
            if "anchor" not in entry or "line" not in entry:
                continue
            path = head / entry["file"]
            if not path.is_file():
                errors.append(f"{section} {entry['id']}: {entry['file']} does not exist after the patch")
                continue
            lines = path.read_text(encoding="utf-8").splitlines()
            n = entry["line"]
            if n > len(lines) or entry["anchor"] not in lines[n - 1]:
                errors.append(f"{section} {entry['id']}: anchor not on {entry['file']}:{n}")


def scan_row(f: dict) -> tuple:
    return (f["id"], f["check"], f["kind"], f["severity"], f["file"], f["line"], bool(f["fix"]["mechanical"]))


def expected_scan_row(e: dict) -> tuple:
    return (e["id"], e["check"], e["kind"], e["severity"], e["file"], e["line"], bool(e["mechanical"]))


def check_review_scan(case: dict, head: Path, patch: Path, errors: list[str]) -> list[dict]:
    want = case["deterministic"]["review-scan"]
    code, out = script_json("review-scan.py", "--diff", str(patch), "--root", str(head), "--json")
    if code != want["exit"]:
        errors.append(f"review-scan exit {code}, expected {want['exit']}")
    got = sorted(scan_row(f) for f in out.get("findings", []))
    exp = sorted(expected_scan_row(e) for e in want["findings"])
    if got != exp:
        errors.append(f"review-scan findings differ:\n    got      {got}\n    expected {exp}")
    for e in want["findings"]:
        if "ops" not in e:
            continue
        match = [f for f in out.get("findings", []) if f["id"] == e["id"] and f["line"] == e["line"]]
        if not match or match[0]["fix"]["ops"] != e["ops"]:
            errors.append(f"review-scan {e['id']} at line {e['line']}: fix ops differ")
    if out.get("notRun", []) != want.get("notRun", []):
        errors.append(f"review-scan notRun {out.get('notRun')} != {want.get('notRun')}")
    return out.get("findings", [])


def key(f: dict) -> tuple:
    return (f.get("check") or f.get("gate"), f.get("file"), f.get("message"))


def new_findings(base_out: dict, head_out: dict) -> tuple[list[dict], int]:
    base_keys = {key(f) for f in base_out.get("findings", [])}
    new = [f for f in head_out.get("findings", []) if key(f) not in base_keys]
    return new, len(head_out.get("findings", [])) - len(new)


def gate_id(label: str) -> str:
    num = label.split(" ", 1)[0]
    return "ESS-G" + num.replace("(", "").replace(")", "")


def check_whole_tree(case: dict, base: Path, head: Path, errors: list[str]) -> list[dict]:
    det = case["deterministic"]
    stack_new: list[dict] = []
    # stack-lint
    want = det["stack-lint"]
    hc, hout = script_json("stack-lint.py", str(head), "--json", ok=(0, 1, 2) if "exit" in want else (0, 1))
    if "exit" in want:
        if hc != want["exit"]:
            errors.append(f"stack-lint exit {hc}, expected {want['exit']}")
    else:
        _, bout = script_json("stack-lint.py", str(base), "--json")
        stack_new, pre = new_findings(bout, hout)
        got = sorted((f["id"], f["check"], f["severity"], f["file"], f["line"]) for f in stack_new)
        exp = sorted((e["id"], e["check"], e["severity"], e["file"], e["line"]) for e in want["new"])
        if got != exp:
            errors.append(f"stack-lint new findings differ:\n    got      {got}\n    expected {exp}")
        if pre != want["pre_existing"]:
            errors.append(f"stack-lint pre-existing {pre}, expected {want['pre_existing']}")
    # slice-lint
    _, bout = script_json("slice-lint.py", str(base), "--require-schema", "--json")
    _, hout = script_json("slice-lint.py", str(head), "--require-schema", "--json")
    new, _ = new_findings(bout, hout)
    got = sorted((f.get("id") or gate_id(f["gate"]), f["severity"], f["file"], f["line"]) for f in new)
    exp = sorted((e["id"], e["severity"], e["file"], e["line"]) for e in det["slice-lint"]["new"])
    if got != exp:
        errors.append(f"slice-lint new findings differ:\n    got      {got}\n    expected {exp}")
    # slice-source
    _, bout = script_json("slice-source.py", str(base), "--check", "--json", ok=(0, 1, 3))
    _, hout = script_json("slice-source.py", str(head), "--check", "--json", ok=(0, 1, 3))
    new, _ = new_findings(bout, hout)
    got = sorted((f["id"], f["gate"], f["severity"], f["file"], f["line"]) for f in new)
    exp = sorted((e["id"], e["gate"], e["severity"], e["file"], e["line"]) for e in det["slice-source"]["new"])
    if got != exp:
        errors.append(f"slice-source new findings differ:\n    got      {got}\n    expected {exp}")
    return stack_new


# review.md Step 3e: the pairs whose stack-lint row folds into the review-scan row.
SAME_DEFECT = {
    "ess-088-mongo-key": "s2-mongo-keys",
    "ess-103-transactional-mode": "s5-transactional-mode",
    "ess-094-jackson2-module": "s3.1-jackson2-essentials-module",
}


def check_fix_offers(case: dict, scan: list[dict], stack_new: list[dict], errors: list[str]) -> None:
    fix = case.get("fix")
    if not fix:
        return
    rows = []
    for f in scan:
        if f["kind"] == "confirmed" and f["fix"]["mechanical"] and f["fix"]["ops"]:
            rows.append(f)
    folded = set()
    for f in scan:
        pair = SAME_DEFECT.get(f["check"])
        op_lines = {op.get("line") for op in f["fix"]["ops"]}
        for s in stack_new:
            if s["check"] == pair and s["file"] == f["file"] and (s["line"] == f["line"] or s["line"] in op_lines):
                folded.add(id(s))
    for s in stack_new:
        if id(s) in folded or s["id"] == "ESS-S1":
            continue
        if s["fix"]["mechanical"] and s["fix"]["ops"]:
            rows.append(s)
    rows.sort(key=lambda f: (SEVERITY_ORDER[f["severity"]], f["file"], f["line"] or 0))
    by_ref = {e["id"]: (e["file"], e["line"]) for e in case["must_find"]}
    exp = [by_ref[r] for r in fix["offers_in_order"]]
    got = [(f["file"], f["line"]) for f in rows]
    if got != exp:
        errors.append(f"--fix offers differ:\n    got      {got}\n    expected {exp}")


def check_case(path: Path) -> list[str]:
    """Returns the mismatches; raises CouldNotRun when a checked script could not give an answer."""
    case = yaml.safe_load(path.read_text(encoding="utf-8"))
    errors: list[str] = []
    with tempfile.TemporaryDirectory() as tmp:
        try:
            base, head, patch = build(case, Path(tmp))
        except RuntimeError as exc:
            return [str(exc)]
        check_anchors(case, head, errors)
        scan = check_review_scan(case, head, patch, errors)
        stack_new = check_whole_tree(case, base, head, errors)
        check_fix_offers(case, scan, stack_new, errors)
    return errors


def main(argv: list[str]) -> int:
    if shutil.which("git") is None:
        print("check-patches: git is not on the PATH", file=sys.stderr)
        return 2
    cases = [HERE / f"{a}.expected.yaml" for a in argv] or sorted(HERE.glob("*.expected.yaml"))
    failed = 0
    crashed = 0
    for path in cases:
        if not path.is_file():
            print(f"check-patches: no such case: {path.name}", file=sys.stderr)
            return 2
        name = path.name.removesuffix(".expected.yaml")
        try:
            errors = check_case(path)
        except CouldNotRun as exc:
            crashed += 1
            print(f"ERROR {name}\n  could not run: {exc}")
            continue
        except (OSError, yaml.YAMLError) as exc:
            crashed += 1
            print(f"ERROR {name}\n  could not run: unreadable case: {exc}")
            continue
        if errors:
            failed += 1
            print(f"FAIL {name}")
            for e in errors:
                print(f"  {e}")
        else:
            print(f"ok   {name}")
    return 2 if crashed else 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
