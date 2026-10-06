#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3", "jsonschema==4.26.0"]
# ///
"""Behaviour tests for scripts/slice-lint.py.

    uv run --script tests/scripts/test_slice_lint.py

Every manifest tree this plugin ships must lint clean with the schema, except the findings a fixture
carries on purpose, which are pinned here by gate and file. The script runs as a subprocess, exactly
as slice-check Step 1.5 and a project's pre-commit hook run it.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parent.parent.parent
SCRIPT = PLUGIN / "scripts" / "slice-lint.py"
SCHEMA = PLUGIN / "references" / "slice" / "slice-yaml.schema.json"

try:
    import jsonschema  # noqa: F401
    import yaml  # noqa: F401
except ImportError:  # pragma: no cover
    raise unittest.SkipTest("pyyaml/jsonschema missing — run: uv run --script tests/scripts/test_slice_lint.py")


def lint(root, *args, script=SCRIPT, cwd=PLUGIN):
    r = subprocess.run([sys.executable, str(script), str(root), "--require-schema", "--json", *args],
                       cwd=cwd, capture_output=True, text=True, timeout=60)
    # --json always prints one document when the script ran; no output is a failed run, never a result.
    if not r.stdout.strip():
        raise AssertionError(f"slice-lint printed no JSON (exit {r.returncode}): {r.stderr.strip()}")
    doc: dict = json.loads(r.stdout)
    return r.returncode, doc


def found(doc, root):
    """(gate, id, path relative to root) per finding."""
    return sorted((f["gate"], f["id"], str(Path(f["file"]).relative_to(Path(root).resolve())))
                  for f in doc["findings"])


class SliceLint(unittest.TestCase):
    def test_fixtures_clean_except_multi_lane_tier(self):
        for fixture in sorted((PLUGIN / "tests" / "fixtures").iterdir()):
            if not fixture.is_dir():
                continue
            with self.subTest(fixture=fixture.name):
                code, doc = lint(fixture)
                self.assertTrue(doc["schemaValidated"] or doc["manifests"] == 0)
                if fixture.name == "multi-lane":
                    # ML-5: `tier: aggregate` is a write style in the tier field.
                    self.assertEqual(code, 1)
                    self.assertEqual(found(doc, fixture), [(
                        "14 tier", "ESS-G14",
                        "src/main/java/com/example/multi/ledger/use_cases/post_entry/slice.yaml")])
                    self.assertEqual(doc["findings"][0]["line"], 10)
                    self.assertEqual(doc["findings"][0]["severity"], "Should-fix")
                else:
                    self.assertEqual((code, doc["findings"]), (0, []))

    def test_slice_golden_compositions_clean(self):
        golden = PLUGIN / "tests" / "slice-golden"
        names = json.loads((golden / "compositions.json").read_text())["compositions"]
        for name in names:
            with self.subTest(composition=name):
                code, doc = lint(golden / name)
                self.assertEqual((code, doc["findings"]), (0, []))

    def test_script_cases_clean(self):
        for case in [*sorted((PLUGIN / "tests" / "slice-source" / "cases").iterdir()),
                     PLUGIN / "tests" / "slice-index" / "cycle"]:
            with self.subTest(case=case.name):
                code, doc = lint(case)
                self.assertEqual((code, doc["findings"]), (0, []))

    def test_no_manifest_is_exit_0_and_still_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, doc = lint(tmp)
        self.assertEqual((code, doc["manifests"], doc["findings"]), (0, 0, []))

    def test_supersedes_twin_is_not_a_sole_owner_finding(self):
        # order_list/_v2 and customer_summary/_v2 are declared twins (gate 13's business);
        # order_stats + order_totals share a read model with no link (4(b)); Order is written
        # from two BCs (4(c)).
        root = PLUGIN / "tests" / "slice-index" / "twin"
        code, doc = lint(root)
        self.assertEqual(code, 1)
        self.assertEqual(found(doc, root), [
            ("4(b) sole owner", "ESS-G4b", "orders/views/order_stats/slice.yaml"),
            ("4(b) sole owner", "ESS-G4b", "orders/views/order_totals/slice.yaml"),
            ("4(c) one BC per aggregate", "ESS-G4c", "billing/use_cases/refund_order/slice.yaml"),
            ("4(c) one BC per aggregate", "ESS-G4c", "orders/use_cases/cancel_order/slice.yaml"),
            ("4(c) one BC per aggregate", "ESS-G4c", "orders/use_cases/place_order/slice.yaml"),
        ])

    def test_duplicate_handler_names_the_other_manifest_even_with_a_shared_id(self):
        # Two compositions carry the same slice ids (gate 3), so the old id comparison listed nobody.
        golden = PLUGIN / "tests" / "slice-golden"
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("java-decider", "kotlin-decider"):
                shutil.copytree(golden / name / "src", Path(tmp) / name)
            code, doc = lint(tmp)
        self.assertEqual(code, 1)
        sole = [f["message"] for f in doc["findings"] if f["gate"] == "4(a) sole handler"]
        self.assertTrue(sole)
        for message in sole:
            self.assertNotIn("also handled by: .", message)
            self.assertRegex(message, r"also handled by: orders\.\w+\. ")

    def test_decider_in_tier_is_reported_too(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = PLUGIN / "tests" / "slice-golden" / "java-decider" / "src"
            shutil.copytree(src, Path(tmp) / "src")
            manifest = next(Path(tmp).rglob("place_order/slice.yaml"))
            manifest.write_text(manifest.read_text().replace("tier: cqrs-es", "tier: decider"))
            code, doc = lint(tmp)
        self.assertEqual(code, 1)
        self.assertEqual([(f["gate"], f["hint"]) for f in doc["findings"]],
                         [("14 tier", "tier: cqrs-es + lane: decider")])

    def test_project_installed_copy_finds_its_schema_beside_it(self):
        # /essentials:init copies the script and the schema into <project>/scripts/.
        with tempfile.TemporaryDirectory() as tmp:
            project = Path(tmp)
            (project / "scripts").mkdir()
            shutil.copy(SCRIPT, project / "scripts" / "slice-lint.py")
            shutil.copy(SCHEMA, project / "scripts" / "slice-yaml.schema.json")
            shutil.copytree(PLUGIN / "tests" / "fixtures" / "service-entity", project / "app")
            code, doc = lint(".", script=project / "scripts" / "slice-lint.py", cwd=project)
        self.assertEqual(code, 0)
        self.assertTrue(doc["schemaValidated"])
        self.assertGreater(doc["manifests"], 0)


if __name__ == "__main__":
    unittest.main()
