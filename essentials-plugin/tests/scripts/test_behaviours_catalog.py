"""Structural tests for references/slice/templates/behaviours.json. Standard library only.

The catalog tells scripts/render-slice.py which behaviour a rendered slice already implements and
which stub tests it wrote. These tests hold its shape against the template tree: one entry per
family directory, every `where` naming a file of that family, every stub test listed exactly once.
Whether a `status` is honest is a reading of the template, which no test here can make.

Run from the plugin root:  python3 -m unittest discover -s tests/scripts
"""

from __future__ import annotations

import json
import re
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
TEMPLATES = PLUGIN / "references" / "slice" / "templates"
CATALOG = TEMPLATES / "behaviours.json"
LANGUAGES = ("java", "kotlin")
STATUSES = {"implemented", "placeholder"}
ID = re.compile(r"^(command|view|automation|translation|bc|app)\.[a-z0-9]+(-[a-z0-9]+)*$")


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


if __name__ == "__main__":
    unittest.main()
