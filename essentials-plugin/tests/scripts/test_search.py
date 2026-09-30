"""Tests for skills/essentials-docs/search.sh. Standard library only.

Run from the plugin root:  python3 -m unittest discover -s tests/scripts -p 'test_search.py'

Each case runs twice: once with ripgrep, and once on a PATH that holds only the tools the grep fallback needs, so the
fallback is exercised on a machine that has rg installed. The rg half is skipped where rg is absent.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
SCRIPT = PLUGIN / "skills" / "essentials-docs" / "search.sh"
BASH = shutil.which("bash")
FALLBACK_TOOLS = ("grep", "sed", "dirname", "ls")


class SearchTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        if BASH is None:
            raise unittest.SkipTest("bash not on PATH")
        cls._bin = tempfile.TemporaryDirectory()
        for tool in FALLBACK_TOOLS:
            os.symlink(shutil.which(tool), Path(cls._bin.name) / tool)

    @classmethod
    def tearDownClass(cls) -> None:
        cls._bin.cleanup()

    def search(self, *argv: str, fallback: bool) -> subprocess.CompletedProcess[str]:
        env = dict(os.environ)
        if fallback:
            env["PATH"] = self._bin.name
        return subprocess.run([BASH, str(SCRIPT), *argv], env=env, capture_output=True, text=True)

    def modes(self) -> list[bool]:
        return [False, True] if shutil.which("rg") else [True]

    def test_fallback_path_has_no_rg(self) -> None:
        env = {"PATH": self._bin.name}
        probe = subprocess.run([BASH, "-c", "command -v rg"], env=env, capture_output=True, text=True)
        self.assertNotEqual(probe.returncode, 0, "the fallback PATH must not find rg")

    def test_query_starting_with_a_dash_is_a_pattern_not_a_flag(self) -> None:
        for fallback in self.modes():
            with self.subTest(fallback=fallback):
                listed = self.search("-l", "--", "-parameters", fallback=fallback)
                self.assertEqual(listed.returncode, 0, listed.stderr)
                self.assertIn("LLM-foundation.md", listed.stdout)

                shown = self.search("--", "-parameters", fallback=fallback)
                self.assertEqual(shown.returncode, 0, shown.stderr)
                self.assertIn("`-parameters`", shown.stdout)

    def test_no_match_exits_one(self) -> None:
        for fallback in self.modes():
            with self.subTest(fallback=fallback):
                result = self.search("--", "-no-such-token-zq9", fallback=fallback)
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main()
