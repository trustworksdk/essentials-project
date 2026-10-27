"""Tests for scripts/migration-plan.py beyond its --self-test goldens. Standard library only.

Run from the plugin root:  python3 -m unittest tests/migration-plan/test_migration_plan.py
"""

from __future__ import annotations

import contextlib
import copy
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
SCRIPT = PLUGIN / "scripts" / "migration-plan.py"
NOTES = PLUGIN / "references" / "stack" / "migrations" / "0.50-to-0.60.json"
CASES = Path(__file__).resolve().parent

spec = importlib.util.spec_from_file_location("migration_plan", SCRIPT)
if spec is None or spec.loader is None:
    raise ImportError(f"cannot load {SCRIPT}")
mp = importlib.util.module_from_spec(spec)
sys.modules["migration_plan"] = mp
spec.loader.exec_module(mp)


def run(*argv: str) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = mp.main(list(argv))
    return code, out.getvalue(), err.getvalue()


def problems_for(edit) -> list[str]:
    notes = json.loads(NOTES.read_text(encoding="utf-8"))
    broken = copy.deepcopy(notes)
    edit(broken)
    return mp.check_notes(mp.Catalogue(), [(NOTES, broken)])


class Check(unittest.TestCase):
    def test_shipped_notes_are_valid(self):
        code, out, _ = run("--check")
        self.assertEqual(code, 0, out)

    def test_an_unknown_or_retired_id_is_named(self):
        def edit(n):
            n["checklist"][0]["id"] = "ESS-999"  # ess-ids: ignore
            n["fixedIn"][0]["id"] = "ESS-114"  # ess-ids: ignore
        found = "\n".join(problems_for(edit))
        self.assertIn("ESS-999 is not a line of references/llm/LLM-traps.md", found)  # ess-ids: ignore
        self.assertIn("ESS-114 is retired", found)  # ess-ids: ignore

    def test_a_review_scan_step_id_needs_a_signature(self):
        def edit(n):
            next(s for s in n["steps"] if s["kind"] == "review-scan")["ess"].append("ESS-051")
        self.assertTrue(any("ESS-051 has no review-scan signature" in p for p in problems_for(edit)))

    def test_stack_lint_slugs_and_pins_resolve(self):
        def edit(n):
            n["steps"][2]["stackLint"] += ["s7-start-stop", "s99-nothing"]
            n["requiredPins"][0]["pin"] = "essentials-version"
        found = "\n".join(problems_for(edit))
        self.assertIn("s7-start-stop is retired", found)
        self.assertIn("s99-nothing is not a stack-lint check", found)
        self.assertIn("'essentials-version' is not a row of references/stack/stack-pins.md", found)

    def test_a_citation_whose_token_moved_is_named(self):
        def edit(n):
            n["requiredPins"][0]["cite"]["line"] += 1
        self.assertTrue(any("no longer contains" in p for p in problems_for(edit)))

    def test_the_newest_notes_end_at_the_pin(self):
        def edit(n):
            n["to"] = "0.70.0"
        found = "\n".join(problems_for(edit))
        self.assertIn("not at the essentials.version pin", found)
        self.assertIn("the file name does not say", found)

    def test_checklist_phases_and_steps_match(self):
        def edit(n):
            n["checklist"][0]["phase"] = "someday"
        self.assertTrue(any("phase 'someday' has no checklist step" in p for p in problems_for(edit)))


class Plan(unittest.TestCase):
    def test_unknown_release_needs_from(self):
        code, _, err = run(str(CASES / "kotlin-from-flag"), "--no-scan")
        self.assertEqual(code, 2)
        self.assertIn("pass --from", err)

    def test_a_release_before_the_first_notes_cannot_be_planned(self):
        code, _, err = run(str(CASES / "kotlin-from-flag"), "--from", "0.40.28", "--no-scan")
        self.assertEqual(code, 2)
        self.assertIn("no migration notes from Essentials 0.40.28", err)

    def test_not_a_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, _, err = run(str(Path(tmp) / "missing"))
        self.assertEqual(code, 2)
        self.assertIn("is not a directory", err)

    def test_text_report_lists_each_step_in_order(self):
        code, out, _ = run(str(CASES / "java-050"))
        self.assertEqual(code, 0, out)
        order = [line.split("]")[0] for line in out.splitlines() if line[:1].isdigit() and ". [" in line]
        self.assertEqual(order, ["1. [compile-and-fix on 0.50.0", "2. [checklist before-move", "3. [pins",
                                 "4. [stack-lint", "5. [review-scan", "6. [compile-and-fix on 0.60.0",
                                 "7. [checklist review", "8. [checklist deploy", "9. [checklist after",
                                 "10. [pins", "11. [compile-and-fix on 0.60.1", "12. [checklist review",
                                 "13. [checklist deploy"])
        self.assertIn("(also ESS-S5 s5-transactional-mode)", out)

    def test_runs_without_site_packages(self):
        r = subprocess.run([sys.executable, "-I", "-S", str(SCRIPT), "--check"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


if __name__ == "__main__":
    unittest.main()
