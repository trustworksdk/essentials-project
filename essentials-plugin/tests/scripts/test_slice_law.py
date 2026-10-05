#!/usr/bin/env python3
"""Behaviour tests for scripts/slice-law.py.

    python3 tests/scripts/test_slice_law.py                  # run
    python3 tests/scripts/test_slice_law.py --update-golden  # rewrite tests/slice-law/views.golden

The golden pins which sections each lane x kind x store view prints, by name, not their bytes: a prose edit
inside a section does not move it, a scope line added, removed or changed does, and its diff is the
review. The script's own --check holds the byte budgets. The script runs as a subprocess, exactly as
the slice skills run it.
"""

from __future__ import annotations

import itertools
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parent.parent.parent
SCRIPT = PLUGIN / "scripts" / "slice-law.py"
LAW = PLUGIN / "rules" / "slice-design.md"
GOLDEN = PLUGIN / "tests" / "slice-law" / "views.golden"
LANES = ("decider", "aggregate", "service-entity")
KINDS = ("command", "view", "automation", "translation")
STORES = ("spring-data", "other")
SCOPE_LINE = re.compile(r"^<!--\s*slice-law:.*-->\s*$")


def run(*args, law=None):
    extra = ["--law", str(law)] if law else []
    r = subprocess.run([sys.executable, str(SCRIPT), *extra, *args],
                       capture_output=True, text=True, timeout=60)
    return r.returncode, r.stdout, r.stderr


def views() -> str:
    out = []
    for lane, kind, store in itertools.product(LANES, KINDS, STORES):
        code, text, err = run("--outline", "--lane", lane, "--kind", kind, "--store", store)
        assert code == 0, err
        names = [re.sub(r"^\+\s+\d+\s+", "", line).split("  [")[0]
                 for line in text.splitlines() if line.startswith("+")]
        out.append(f"## lane={lane} kind={kind} store={store}")
        out.extend(f"  {name}" for name in names)
    return "\n".join(out) + "\n"


class SliceLaw(unittest.TestCase):
    def test_law_scope_lines_valid_and_within_budget(self):
        code, out, err = run("--check")
        self.assertEqual(code, 0, out + err)

    def test_views_match_golden(self):
        self.assertEqual(views(), GOLDEN.read_text(encoding="utf-8"),
                         "a scope line changed what a view prints: review, then --update-golden")

    def test_unfiltered_view_is_the_file_without_its_scope_lines(self):
        code, out, _ = run()
        self.assertEqual(code, 0)
        header, body = out.split("\n", 1)
        self.assertTrue(header.startswith("<!-- rules/slice-design.md for every lane and kind:"))
        expected = "".join(line for line in LAW.read_text(encoding="utf-8").splitlines(keepends=True)
                           if not SCOPE_LINE.match(line))
        self.assertEqual(body, expected)

    def test_filtered_view_names_what_it_left_out(self):
        code, out, _ = run("--lane", "decider", "--kind", "command", "--store", "other")
        self.assertEqual(code, 0)
        self.assertNotIn("\n### Service-entity style", out)
        self.assertIn("§ Service-entity style — the decision lives on a state-stored entity "
                      "(lane=service-entity)", out)
        # A left-out parent stands for its subsections.
        self.assertIn("§ Spring Data repository surface (store=spring-data)", out)
        self.assertNotIn("§ Repositories extend the bare Repository marker (", out)
        self.assertFalse([line for line in out.splitlines() if SCOPE_LINE.match(line)])

    def test_project_decides_the_store(self):
        fixtures = PLUGIN / "tests" / "fixtures"
        for fixture, store in (("service-entity", "spring-data"), ("worked-example", "other"),
                               ("aggregate-lane", "other")):
            with self.subTest(fixture=fixture):
                code, out, err = run("--lane", "decider", "--project", str(fixtures / fixture))
                self.assertEqual(code, 0, err)
                self.assertIn(f"store={store}:", out.split("\n", 1)[0])
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "pom.xml").write_text(
                "<artifactId>spring-boot-starter-data-mongodb</artifactId>", encoding="utf-8")
            code, out, _ = run("--project", tmp)
            self.assertIn("store=spring-data:", out.split("\n", 1)[0])
            code, _, err = run("--project", tmp, "--store", "other")
            self.assertEqual(code, 2, err)
        code, _, err = run("--project", str(PLUGIN / "no-such-dir"))
        self.assertEqual(code, 2, err)

    def test_section_by_short_name_brings_its_subsections(self):
        code, out, _ = run("--section", "Spring Data repository surface")
        self.assertEqual(code, 0)
        self.assertTrue(out.startswith("## Spring Data repository surface\n"))
        self.assertIn("### Never name a query method after a CRUD base method\n", out)
        self.assertNotIn("## Sanctioned sharing", out)
        code, out, _ = run("--section", "R5")
        self.assertEqual(code, 0)
        self.assertTrue(out.startswith("## R5 — use a standard Essentials design, per language\n"))

    def test_unknown_section_is_a_usage_error(self):
        code, _, err = run("--section", "No such section")
        self.assertEqual(code, 2)
        self.assertIn("no section named", err)

    def test_bad_scope_lines_fail_check(self):
        cases = {
            "widens": "## A\n<!-- slice-law: lane=decider -->\n### B\n<!-- slice-law: lane=aggregate -->\n",
            "detached": "## A\n\n<!-- slice-law: lane=decider -->\n",
            "unknown value": "## A\n<!-- slice-law: lane=layered -->\n",
            "unknown dimension": "## A\n<!-- slice-law: language=kotlin -->\n",
            "twice": "## A\n<!-- slice-law: lane=decider -->\n<!-- slice-law: kind=view -->\n",
        }
        for name, text in cases.items():
            with self.subTest(case=name), tempfile.TemporaryDirectory() as tmp:
                law = Path(tmp) / "law.md"
                law.write_text("# Law\n\n" + text + "\nbody\n", encoding="utf-8")
                code, out, _ = run("--check", law=law)
                self.assertEqual(code, 1, out)
                code, _, err = run(law=law)
                self.assertEqual(code, 2, err)

    def test_scope_lines_inside_a_fence_are_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            law = Path(tmp) / "law.md"
            law.write_text("# Law\n\n## A\n\n```\n<!-- slice-law: lane=nope -->\n## not a heading\n```\n",
                           encoding="utf-8")
            code, out, _ = run("--check", law=law)
            self.assertEqual(code, 0, out)
            code, out, _ = run("--lane", "decider", law=law)
            self.assertIn("<!-- slice-law: lane=nope -->\n## not a heading\n", out)


if __name__ == "__main__":
    if "--update-golden" in sys.argv:
        GOLDEN.write_text(views(), encoding="utf-8")
        print(f"wrote {GOLDEN.relative_to(PLUGIN)}")
        sys.exit(0)
    unittest.main()
