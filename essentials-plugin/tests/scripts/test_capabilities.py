"""Tests for capabilities.json — every value it advertises to another plugin's agent, checked against
the code that implements it (references/sdd-contract.md). Standard library only.

Run from the plugin root:  python3 -m unittest discover -s tests/scripts -p 'test_capabilities.py'
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import re
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
CAPS = json.loads((PLUGIN / "capabilities.json").read_text(encoding="utf-8"))
PLUGIN_VERSION = json.loads((PLUGIN / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8"))["version"]
SCHEMA = json.loads((PLUGIN / "references" / "slice" / "slice-yaml.schema.json").read_text(encoding="utf-8"))
CONTRACT = (PLUGIN / "references" / "sdd-contract.md").read_text(encoding="utf-8")

KNOWN = {"init.preset", "slice.render.agent", "slice.render.tests-none", "slice.render.manifest",
         "slice.render.emit", "slice.render.report", "slice.lane.cqrs", "schema.sdd-keys"}


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def version_key(v: str) -> tuple[int, ...]:
    """X.Y.Z-N orders after X.Y.Z and before X.Y.(Z+1): -N is a later plugin-only build."""
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)(?:-(\d+))?", v)
    if not m:
        raise ValueError(f"not a plugin version: {v!r}")
    return tuple(int(g or 0) for g in m.groups())


class CapabilitiesTest(unittest.TestCase):
    def test_shape(self):
        self.assertIs(type(CAPS["contractVersion"]), int, "a JSON number, never a string")
        self.assertEqual(CAPS["contractVersion"], 1)
        self.assertEqual((CAPS["contract"], CAPS["plugin"]), ("sdd-essentials", "essentials"))
        self.assertNotIn("pluginVersion", CAPS, "plugin.json is the one version")
        self.assertRegex(CAPS["python"], r"^3\.\d+$")
        self.assertLessEqual(set(CAPS["capabilities"]) | set(CAPS["declined"]), KNOWN)
        self.assertFalse(set(CAPS["capabilities"]) & set(CAPS["declined"]))
        for cid, reason in CAPS["declined"].items():
            self.assertTrue(isinstance(reason, str) and reason and "\n" not in reason and len(reason) <= 300, cid)

    def test_since_is_a_released_or_current_version(self):
        for cid, cap in CAPS["capabilities"].items():
            with self.subTest(cid):
                self.assertLessEqual(version_key(cap["since"]), version_key(PLUGIN_VERSION))

    def test_every_capability_has_its_section_in_the_contract(self):
        for cid in CAPS["capabilities"]:
            self.assertIn(f"`{cid}`", CONTRACT, cid)

    def test_python_floor_matches_the_entries(self):
        for path in (PLUGIN / "scripts" / "render-slice.py", PLUGIN / "scripts" / "init-report.py"):
            self.assertIn(f"Python {CAPS['python']}+", path.read_text(encoding="utf-8"), path.name)

    def test_render_agent(self):
        cap = CAPS["capabilities"]["slice.render.agent"]
        entry = cap["entry"]
        self.assertFalse(entry.startswith("/") or ".." in Path(entry).parts, entry)
        self.assertTrue((PLUGIN / entry).is_file(), entry)
        rs = load("render_slice", PLUGIN / entry)
        self.assertEqual(cap["interface"], rs.INTERFACE)
        self.assertEqual(CAPS["capabilities"]["slice.render.report"]["reportVersion"], rs.REPORT_VERSION)
        out = io.StringIO()
        with contextlib.redirect_stdout(out), self.assertRaises(SystemExit):
            rs.main(["render", "--help"])
        for option in ("--main-root", "--test-root", "--set", "--json", "--wire", "--new-bc", "--project-root",
                       "--tests", "--manifest"):
            self.assertIn(option, out.getvalue(), "the --help probe another plugin runs")
        if "slice.render.emit" in CAPS["capabilities"]:
            self.assertIn("--emit", out.getvalue())
            self.assertIn("--only", out.getvalue())
            verify = io.StringIO()
            with contextlib.redirect_stdout(verify), self.assertRaises(SystemExit):
                rs.main(["verify", "--help"])
            self.assertIn("--main-root", verify.getvalue(), "verify takes render's options")
            self.assertIn("\n    4 ", rs.__doc__ or "", "exit 4 is documented")
        cqrs = CAPS["capabilities"].get("slice.lane.cqrs")
        if cqrs is not None:
            self.assertRegex(cqrs["lane"], r"^[a-z][a-z0-9-]{0,63}$")
            self.assertIn(cqrs["lane"], rs.LANES)
            self.assertEqual(rs.TIER[cqrs["lane"]], "cqrs")

    def test_behaviour_catalog_ships_with_the_report(self):
        rs = load("render_slice", PLUGIN / "scripts" / "render-slice.py")
        self.assertTrue((rs.TEMPLATES / rs.BEHAVIOURS).is_file())

    def test_init_preset_keys(self):
        cap = CAPS["capabilities"]["init.preset"]
        ir = load("init_report", PLUGIN / "scripts" / "init-report.py")
        self.assertEqual(cap["presetKeys"], list(ir.PRESET_KEYS))
        self.assertEqual(cap["command"], "essentials:init")
        init = (PLUGIN / "commands" / "init.md").read_text(encoding="utf-8")
        front = init.split("---", 2)[1]
        self.assertNotIn("disable-model-invocation", front, "another plugin runs init through the Skill tool")
        self.assertIn("--preset", front, "argument-hint")

    def test_schema_keys(self):
        cap = CAPS["capabilities"]["schema.sdd-keys"]
        self.assertEqual(cap["schemaVersion"], SCHEMA["x-manifestVersion"])
        self.assertLessEqual({"flow", "scenarios", "requirements"}, set(SCHEMA["properties"]))


if __name__ == "__main__":
    unittest.main()
