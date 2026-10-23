"""Structural tests for references/slice/templates/behaviours.json. Standard library only.

The catalog tells scripts/render-slice.py which behaviour a rendered slice already implements and
which stub tests it wrote. These tests hold its shape against the template tree: one entry per
family directory, every `where` naming a file of that family, every stub test listed exactly once.

Whether an `implemented` status is honest is decided by running the skeleton, which this file does
not do: the behaviour checks under tests/slice-compile/behaviours/ do, in the CI slices build
(scripts/plugin-scaffold.sh slices). Here every `implemented` entry must be named by a
`// behaviour: <language>/<family> <id>` line directly above an @Test in one of those integration
tests, in an overlay directory tests/slice-compile/overlay.py puts only on compositions that render
that family; a `placeholder` entry may not be named. The reverse holds too: every composition runs a
marked test of each `implemented` entry of every family it renders, unless overlay.py lists it in
BEHAVIOUR_EXEMPT with the reason.

Run from the plugin root:  python3 -m unittest discover -s tests/scripts
"""

from __future__ import annotations

import importlib.util
import json
import re
import sys
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
TEMPLATES = PLUGIN / "references" / "slice" / "templates"
CATALOG = TEMPLATES / "behaviours.json"
SLICE_COMPILE = PLUGIN / "tests" / "slice-compile"
BEHAVIOUR_TESTS = SLICE_COMPILE / "behaviours"
GOLDEN = PLUGIN / "tests" / "slice-golden"
LANGUAGES = ("java", "kotlin")
SUFFIX_LANGUAGE = {".java": "java", ".kt": "kotlin"}
STATUSES = {"implemented", "placeholder"}
ID = re.compile(r"^(command|view|automation|translation|bc|app)\.[a-z0-9]+(-[a-z0-9]+)*$")
MARKER = re.compile(r"^\s*//\s*behaviour:(.*)$")
MARKER_BODY = re.compile(r"^ (java|kotlin)/([a-z0-9_-]+) (\S+)$")


def overlay_module():
    """tests/slice-compile/overlay.py, for its BEHAVIOUR_OVERLAYS and BEHAVIOUR_EXEMPT."""
    spec = importlib.util.spec_from_file_location("slice_compile_overlay", SLICE_COMPILE / "overlay.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module            # its dataclasses look their module up there
    spec.loader.exec_module(module)
    return module


def composition_names() -> list[str]:
    return list(json.loads((GOLDEN / "compositions.json").read_text(encoding="utf-8"))["compositions"])


def rendered_families(composition: str) -> set[str]:
    """`<language>/<family>` for every family the composition's render.json rendered."""
    language = json.loads((GOLDEN / "compositions.json").read_text(encoding="utf-8"))["compositions"][composition]["language"]
    steps = json.loads((GOLDEN / composition / "render.json").read_text(encoding="utf-8"))
    return {f"{language}/{family}" for step in steps for family in step.get("families") or ()}


def behaviour_markers() -> list[tuple[str, int, str, str, str]]:
    """(file, line, overlay directory, family key, id) for every marker line; raises on a malformed one."""
    found = []
    for path in sorted(BEHAVIOUR_TESTS.rglob("*")):
        if not path.is_file() or path.suffix not in SUFFIX_LANGUAGE:
            continue
        rel = path.relative_to(BEHAVIOUR_TESTS).as_posix()
        lines = path.read_text(encoding="utf-8").splitlines()
        for number, line in enumerate(lines, start=1):
            marker = MARKER.match(line)
            if not marker:
                continue
            body = MARKER_BODY.match(marker.group(1))
            if not body:
                raise AssertionError(f"{rel}:{number}: malformed marker; write // behaviour: <language>/<family> <id>")
            if body.group(1) != SUFFIX_LANGUAGE[path.suffix]:
                raise AssertionError(f"{rel}:{number}: a {body.group(1)} marker in a {path.suffix} file")
            if not path.stem.endswith("IT"):
                raise AssertionError(f"{rel}:{number}: only *IT classes run in the slices build's verify")
            following = lines[number] if number < len(lines) else ""
            if following.strip() != "@Test":
                raise AssertionError(f"{rel}:{number}: the line after a marker must be @Test, not {following.strip()!r}")
            found.append((rel, number, rel.split("/", 1)[0], f"{body.group(1)}/{body.group(2)}", body.group(3)))
    return found


def family_dirs() -> set[str]:
    return {f"{lang}/{d.name}" for lang in LANGUAGES for d in (TEMPLATES / lang).iterdir() if d.is_dir()}


def stub_test_files(family: str) -> set[str]:
    test_dir = TEMPLATES / family / "test"
    if not test_dir.is_dir():
        return set()
    return {p.relative_to(TEMPLATES / family).as_posix() for p in test_dir.rglob("*") if p.is_file()}


class BehavioursCatalogTest(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        cls.families = cls.catalog["families"]

    def test_version(self):
        self.assertEqual(self.catalog["catalogVersion"], 1)
        self.assertTrue(self.catalog.get("$comment"), "the catalog explains itself in $comment")

    def test_one_entry_per_family_directory(self):
        self.assertEqual(set(self.families), family_dirs())

    def test_behaviours(self):
        for family, entry in self.families.items():
            ids = [b["id"] for b in entry["behaviours"]]
            self.assertEqual(len(ids), len(set(ids)), f"{family}: duplicate behaviour id")
            for b in entry["behaviours"]:
                with self.subTest(family=family, id=b["id"]):
                    self.assertEqual(set(b), {"id", "status", "summary", "where"})
                    self.assertRegex(b["id"], ID)
                    self.assertIn(b["status"], STATUSES)
                    self.assertTrue(b["summary"].strip())
                    path = b["where"].split("#", 1)[0]
                    self.assertTrue((TEMPLATES / family / path).is_file(), f"{family}: no template {path}")

    def test_every_stub_test_listed_once(self):
        for family, entry in self.families.items():
            with self.subTest(family=family):
                listed = [s["template"] for s in entry["stubTests"]]
                self.assertEqual(len(listed), len(set(listed)), f"{family}: stub test listed twice")
                self.assertEqual(set(listed), stub_test_files(family))

    def test_covers_name_known_behaviours(self):
        known = {b["id"] for entry in self.families.values() for b in entry["behaviours"]}
        for family, entry in self.families.items():
            for stub in entry["stubTests"]:
                with self.subTest(family=family, template=stub["template"]):
                    self.assertEqual(set(stub), {"template", "covers"})
                    self.assertEqual(sorted(set(stub["covers"]) - known), [])


class BehaviourChecksTest(unittest.TestCase):
    """Every `implemented` entry is asserted by a behaviour IT the slices build runs."""

    @classmethod
    def setUpClass(cls):
        families = json.loads(CATALOG.read_text(encoding="utf-8"))["families"]
        cls.status = {(family, b["id"]): b["status"] for family, entry in families.items() for b in entry["behaviours"]}
        cls.markers = behaviour_markers()
        overlay = overlay_module()
        cls.overlays = overlay.BEHAVIOUR_OVERLAYS
        cls.exempt = overlay.BEHAVIOUR_EXEMPT

    def test_markers_name_implemented_entries(self):
        for rel, number, _, family, behaviour in self.markers:
            with self.subTest(marker=f"{rel}:{number}"):
                self.assertIn((family, behaviour), self.status, f"{family} {behaviour} is not in the catalog")
                self.assertEqual(self.status[(family, behaviour)], "implemented",
                                 f"{family} {behaviour} is a placeholder; a test of it would fail on the skeleton")

    def test_markers_run_where_their_family_is_rendered(self):
        for rel, number, directory, family, behaviour in self.markers:
            compositions = [name for name, dirs in self.overlays.items() if directory in dirs]
            with self.subTest(marker=f"{rel}:{number}"):
                self.assertTrue(compositions, f"no composition in overlay.py BEHAVIOUR_OVERLAYS runs behaviours/{directory}")
                for composition in compositions:
                    self.assertIn(family, rendered_families(composition),
                                  f"{composition} does not render {family}, yet runs behaviours/{directory}")

    def test_every_implemented_entry_is_asserted(self):
        asserted = {(family, behaviour) for _, _, _, family, behaviour in self.markers}
        unasserted = sorted(f"{family} {behaviour}" for (family, behaviour), status in self.status.items()
                            if status == "implemented" and (family, behaviour) not in asserted)
        self.assertEqual(unasserted, [], "implemented with no behaviour test under tests/slice-compile/behaviours/ "
                                         "(add one, or make the entry a placeholder that says what is missing)")

    def test_every_composition_rendering_an_implemented_family_runs_its_checks(self):
        for composition in composition_names():
            families = rendered_families(composition)
            directories = self.overlays.get(composition, ())
            asserted = {(family, behaviour) for _, _, directory, family, behaviour in self.markers
                        if directory in directories}
            unchecked = sorted(f"{family} {behaviour}" for (family, behaviour), status in self.status.items()
                               if status == "implemented" and family in families
                               and (family, behaviour) not in asserted)
            with self.subTest(composition=composition):
                if composition in self.exempt:
                    self.assertTrue(unchecked, f"{composition} is in overlay.py BEHAVIOUR_EXEMPT, yet its behaviour "
                                               "overlays assert every implemented entry it renders; drop the exemption")
                    continue
                self.assertEqual(unchecked, [], f"{composition} renders these implemented entries, which no directory "
                                                "overlay.py BEHAVIOUR_OVERLAYS gives it asserts; add a behaviour "
                                                "overlay for it, or list it in BEHAVIOUR_EXEMPT with the reason")


if __name__ == "__main__":
    unittest.main()
