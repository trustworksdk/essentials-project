"""Tests for scripts/review-scan.py. Standard library only.

Run from the plugin root:  python3 -m unittest discover -s tests/scripts -p 'test_review_scan.py'
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
SCRIPT = PLUGIN / "scripts" / "review-scan.py"

spec = importlib.util.spec_from_file_location("review_scan", SCRIPT)
if spec is None or spec.loader is None:
    raise ImportError(f"cannot load {SCRIPT}")
rs = importlib.util.module_from_spec(spec)
sys.modules["review_scan"] = rs   # dataclasses resolve annotations through sys.modules
spec.loader.exec_module(rs)


def run(*argv: str, stdin: str | None = None) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    old_stdin = sys.stdin
    if stdin is not None:
        sys.stdin = io.StringIO(stdin)
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = rs.main(list(argv))
    finally:
        sys.stdin = old_stdin
    return code, out.getvalue(), err.getvalue()


def self_test_with(attr: str, pattern: str) -> tuple[int, str]:
    """Run the self-test with one Scan signature pattern replaced."""
    original = getattr(rs.Scan, attr)
    setattr(rs.Scan, attr, re.compile(pattern))
    try:
        out = io.StringIO()
        code = rs.self_test(rs.load_traps(rs.TRAPS), out)
        return code, out.getvalue()
    finally:
        setattr(rs.Scan, attr, original)


def git(root: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True)


class SelfTest(unittest.TestCase):
    def test_fixtures_pass(self):
        code, out, _ = run("--self-test")
        self.assertEqual(code, 0, out)
        self.assertIn("self-test:", out)

    def test_a_signature_that_never_fires_fails_the_self_test(self):
        code, out = self_test_with("MAPPER", r"(?!x)x")
        self.assertEqual(code, 1)
        self.assertIn("expected ess-052-hand-built-mapper", out)
        self.assertIn("FAIL ess-052-hand-built-mapper: no fixture expects it to fire", out)

    def test_a_signature_that_over_fires_fails_the_self_test(self):
        code, out = self_test_with("LOCAL_BUS", r"LocalCommandBus")
        self.assertEqual(code, 1)
        self.assertIn("unexpected ess-016-local-command-bus", out)

    def test_runs_without_site_packages(self):
        r = subprocess.run([sys.executable, "-I", "-S", str(SCRIPT), "--self-test"], capture_output=True, text=True)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)


class Catalogue(unittest.TestCase):
    def test_every_signature_id_is_an_active_trap(self):
        traps = rs.load_traps(rs.TRAPS)
        for s in rs.SIGNATURES:
            self.assertIn(s.id, traps, s.check)
            self.assertTrue(s.evidence, s.check)

    def test_a_signature_id_missing_from_the_catalogue_cannot_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            text = rs.TRAPS.read_text(encoding="utf-8")
            broken = Path(tmp) / "LLM-traps.md"
            broken.write_text("\n".join(l for l in text.splitlines() if 'id="ess-088"' not in l), encoding="utf-8")
            code, _, err = run("--traps", str(broken), "--diff", "-", stdin="")
        self.assertEqual(code, 2)
        self.assertIn("ESS-088", err)

    def test_signatures_listing(self):
        code, out, _ = run("--signatures", "--json")
        self.assertEqual(code, 0)
        rows = json.loads(out)
        self.assertEqual({r["check"] for r in rows}, set(rs.BY_CHECK))
        self.assertTrue(all(r["symptom"] and r["link"].startswith("references/llm/LLM-traps.md#ess-") for r in rows))


class Input(unittest.TestCase):
    def test_empty_diff_is_clean(self):
        code, out, _ = run("--diff", "-", "--json", stdin="")
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["findings"], [])

    def test_not_a_diff_cannot_run(self):
        code, _, err = run("--diff", "-", stdin="hello\nworld\n")
        self.assertEqual(code, 2)
        self.assertIn("not a unified diff", err)

    def test_removed_line_starting_with_dashes_is_not_a_header(self):
        diff = ("--- a/src/main/resources/application.properties\n"
                "+++ b/src/main/resources/application.properties\n"
                "@@ -1,2 +1,2 @@\n"
                "--- a comment line starting with two dashes\n"
                "+spring.data.mongodb.uri=mongodb://x\n"
                " logging.level.root=INFO\n")
        code, out, _ = run("--diff", "-", "--json", "--root", tempfile.gettempdir(), stdin=diff)
        found = json.loads(out)["findings"]
        self.assertEqual(code, 1)
        self.assertEqual([(f["file"], f["line"], f["id"]) for f in found],
                         [("src/main/resources/application.properties", 1, "ESS-088")])

    def test_fail_on_blocking_ignores_advisory(self):
        diff = ("--- /dev/null\n+++ b/src/main/resources/application.properties\n@@ -0,0 +1 @@\n"
                "+essentials.durable-queues.transactional-mode=single-operation-transaction\n")
        code, out, _ = run("--diff", "-", "--json", "--fail-on", "blocking", "--root", tempfile.gettempdir(), stdin=diff)
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["counts"]["Advisory"], 1)


class GitMode(unittest.TestCase):
    def setUp(self):
        if shutil.which("git") is None:
            self.skipTest("git not installed")
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        git(self.root, "init", "-q")
        git(self.root, "config", "user.email", "t@example.com")
        git(self.root, "config", "user.name", "t")
        self.yml = self.root / "src" / "main" / "resources" / "application.yml"
        self.yml.parent.mkdir(parents=True)
        filler = "\n".join(f"      # note {i}" for i in range(10))
        self.yml.write_text(f"spring:\n  data:\n    mongodb:\n{filler}\n      database: orders\n", encoding="utf-8")
        git(self.root, "add", "-A")
        git(self.root, "commit", "-q", "-m", "base")

    def tearDown(self):
        self.tmp.cleanup()

    def test_working_tree_against_base_uses_the_whole_file(self):
        with self.yml.open("a", encoding="utf-8") as f:
            f.write("      uri: mongodb://localhost/orders\n")
        (self.root / "notes.txt").write_text("untracked\n", encoding="utf-8")
        code, out, _ = run("--base", "HEAD", "--root", str(self.root), "--json")
        doc = json.loads(out)
        self.assertEqual(code, 1, out)
        self.assertEqual(doc["notRun"], [])
        [f] = doc["findings"]
        self.assertEqual((f["id"], f["file"], f["line"], f["kind"]),
                         ("ESS-088", "src/main/resources/application.yml", 15, "confirmed"))
        self.assertEqual(f["fix"]["ops"][0]["to"], "spring.mongodb.uri")
        self.assertEqual(doc["source"]["untracked"], ["notes.txt"])

    def test_bad_ref_cannot_run(self):
        code, _, err = run("--base", "no-such-ref", "--root", str(self.root))
        self.assertEqual(code, 2)
        self.assertIn("git", err)


if __name__ == "__main__":
    unittest.main()
