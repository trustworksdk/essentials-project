"""Tests for scripts/init-report.py. Standard library only.

Run from the plugin root:  python3 -m unittest discover -s tests/scripts -p 'test_init_report.py'

The value sets are init-render.py's own (ENUMS, METADATA, OPTIONAL); a case that names one reads it from there,
so these tests follow the renderer rather than a copy of it.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
SCRIPT = PLUGIN / "scripts" / "init-report.py"
PINS = PLUGIN / "references" / "stack" / "stack-pins.md"

spec = importlib.util.spec_from_file_location("init_report", SCRIPT)
if spec is None or spec.loader is None:
    raise ImportError(f"cannot load {SCRIPT}")
rep = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = rep
spec.loader.exec_module(rep)
ir = rep.ir

ANSWERS = {"language": "kotlin", "db": "pg-event-sourced", "web": "webflux", "frontend": "none", "compose": True,
           "lintGate": "hook", "projectName": "Orders Service", "groupId": "dk.trustworks.orders",
           "artifactId": "orders-service", "packagePath": "dk.trustworks.orders.ordersservice"}
REPORT_KEYS = ["contractVersion", "essentialsVersion", "pluginVersion", "status", "target", "workspace", "answers",
               "preset", "asked", "ignoredPreset", "stackLint", "smokeBuild"]


def run(*argv: str) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = rep.main(list(argv))
    return code, out.getvalue(), err.getvalue()


class Workspace(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.ws = Path(tmp.name)

    def file(self, name: str, content) -> Path:
        path = self.ws / name
        path.write_text(content if isinstance(content, str) else json.dumps(content), encoding="utf-8")
        return path

    def preset(self, content) -> dict:
        code, out, err = run("preset", "--preset", str(self.file("preset.json", content)), "--workspace", str(self.ws))
        self.assertEqual(code, 0, err)
        return json.loads(out)

    def write(self, *argv: str) -> tuple[int, dict | None, str]:
        report = self.ws / "cache" / "report.json"
        code, _, err = run("write", "--report", str(report), "--workspace", str(self.ws), *argv)
        return code, json.loads(report.read_text(encoding="utf-8")) if report.exists() else None, err


class PresetTest(Workspace):
    def test_value_sets_are_the_renderers(self):
        self.assertEqual(rep.ANSWER_KEYS, (*ir.ENUMS, *ir.METADATA, *ir.OPTIONAL))
        self.assertEqual(len(rep.ANSWER_KEYS), 10)
        self.assertEqual(rep.PRESET_KEYS, (*rep.ANSWER_KEYS, "targetMode", "targetDir"))

    def test_valid_subset_is_usable_and_the_rest_is_asked(self):
        result = self.preset({"language": "java", "db": "pg-event-sourced", "frontend": "none",
                              "projectName": "Orders", "compose": False})
        self.assertEqual(result["preset"], {"language": "java", "db": "pg-event-sourced", "frontend": "none",
                                            "compose": False, "projectName": "Orders"})
        self.assertEqual(result["ignored"], {})
        self.assertEqual(result["ask"], ["targetMode", "web", "lintGate", "groupId", "artifactId", "packagePath"])
        self.assertIsNone(result["target"])

    def test_every_enum_value_is_usable(self):
        for key, values in ir.ENUMS.items():
            for value in values:
                with self.subTest(key=key, value=value):
                    self.assertEqual(self.preset({key: value})["preset"], {key: value})

    def test_bad_value_is_ignored_with_a_reason_and_asked(self):
        result = self.preset({"language": "scala", "compose": "true", "groupId": "com.class.x",
                              "artifactId": "Orders_Service", "projectName": "  ", "packagePath": ""})
        self.assertEqual(result["preset"], {})
        ignored = result["ignored"]
        self.assertEqual(ignored["language"], 'language="scala" is not one of ["kotlin", "java"]')
        self.assertEqual(ignored["compose"], 'compose="true" is not one of [true, false]')
        self.assertIn("'class'", ignored["groupId"])
        self.assertIn("artifactId", ignored["artifactId"])
        self.assertIn("projectName", ignored["projectName"])
        self.assertIn("packagePath", ignored["packagePath"])
        self.assertEqual(result["ask"], ["targetMode", *rep.ANSWER_KEYS])

    def test_artifact_id_that_derives_a_keyword_segment_is_ignored_without_a_package_path(self):
        # Alone each key is valid; with no packagePath the renderer derives `<groupId>.class` and refuses it,
        # so the preset artifactId is asked rather than taken.
        for preset in ({"artifactId": "class"},
                       {"groupId": "dk.trustworks.orders", "artifactId": "class"},
                       {"groupId": "dk.trustworks.orders", "artifactId": "class-"},
                       {"groupId": "dk.trustworks.orders", "artifactId": "class", "packagePath": ""}):
            with self.subTest(preset=preset):
                with self.assertRaises(ir.RenderError):
                    ir.normalize_answers({**ANSWERS, **preset, "packagePath": ""})
                result = self.preset(preset)
                self.assertNotIn("artifactId", result["preset"])
                self.assertIn("'class'", result["ignored"]["artifactId"])
                self.assertIn("packagePath", result["ignored"]["artifactId"])
                self.assertIn("artifactId", result["ask"])
                self.assertEqual(result["preset"], {k: v for k, v in preset.items() if k == "groupId"})

    def test_artifact_id_with_a_keyword_segment_stays_usable_with_a_package_path(self):
        preset = {"groupId": "dk.trustworks.orders", "artifactId": "class", "packagePath": "dk.trustworks.orders.api"}
        ir.normalize_answers({**ANSWERS, **preset})
        result = self.preset(preset)
        self.assertEqual(result["preset"], preset)
        self.assertEqual(result["ignored"], {})

    def test_artifact_id_that_derives_a_valid_package_stays_usable(self):
        for preset in ({"artifactId": "orders-service"},
                       {"groupId": "dk.trustworks.orders", "artifactId": "orders"},
                       {"groupId": "dk.trustworks.orders", "artifactId": "class-api"}):
            with self.subTest(preset=preset):
                result = self.preset(preset)
                self.assertEqual(result["preset"], preset)
                self.assertEqual(result["ignored"], {})

    def test_wrong_json_type_is_ignored(self):
        result = self.preset({"compose": 1, "lintGate": ["hook"], "groupId": 7, "packagePath": 3})
        self.assertEqual(result["preset"], {})
        self.assertEqual(set(result["ignored"]), {"compose", "lintGate", "groupId", "packagePath"})

    def test_unknown_key_is_ignored(self):
        result = self.preset({"language": "kotlin", "tier": "cqrs-es"})
        self.assertEqual(result["preset"], {"language": "kotlin"})
        self.assertEqual(result["ignored"], {"tier": "unknown key"})

    def test_malformed_or_missing_preset_never_aborts(self):
        for content in ("{not json", "[1, 2]", '"kotlin"'):
            with self.subTest(content=content):
                result = self.preset(content)
                self.assertEqual(result["preset"], {})
                self.assertEqual(list(result["ignored"]), ["(preset)"])
                self.assertEqual(result["ask"], ["targetMode", *rep.ANSWER_KEYS])
        code, out, _ = run("preset", "--preset", str(self.ws / "absent.json"), "--workspace", str(self.ws))
        self.assertEqual(code, 0)
        self.assertEqual(list(json.loads(out)["ignored"]), ["(preset)"])

    def test_target_mode(self):
        self.assertEqual(self.preset({"targetMode": "current"})["target"], str(self.ws))
        self.assertIsNone(self.preset({"targetMode": "subdirectory"})["target"])
        self.assertEqual(self.preset({"targetMode": "subdirectory", "artifactId": "orders"})["target"],
                         str(self.ws / "orders"))
        result = self.preset({"targetMode": "here"})
        self.assertIn("targetMode", result["ignored"])
        self.assertEqual(result["ask"][0], "targetMode")

    def test_target_dir_supersedes_target_mode(self):
        result = self.preset({"targetMode": "subdirectory", "targetDir": "modules/backend", "artifactId": "orders"})
        self.assertEqual(result["preset"]["targetDir"], str(self.ws / "modules" / "backend"))
        self.assertNotIn("targetMode", result["preset"])
        self.assertEqual(result["ignored"], {"targetMode": "superseded by targetDir"})
        self.assertEqual(result["target"], str(self.ws / "modules" / "backend"))
        self.assertNotIn("targetMode", result["ask"])

    def test_target_dir_absolute_and_normalised(self):
        elsewhere = self.ws / "a" / ".." / "b"
        self.assertEqual(self.preset({"targetDir": str(elsewhere)})["target"], str(self.ws / "b"))
        self.file("taken", "x")
        result = self.preset({"targetDir": "taken"})
        self.assertIn("not a directory", result["ignored"]["targetDir"])
        self.assertIsNone(result["target"])


class WriteTest(Workspace):
    def test_rendered_report_has_exact_keys_and_types(self):
        preset = self.file("preset.json", {"language": "kotlin", "frontend": "none", "targetDir": str(self.ws),
                                           "web": "jsf", "x-extra": 1})
        answers = self.file("answers.json", {k: v for k, v in ANSWERS.items() if k != "packagePath"})
        code, report, err = self.write("--status", "rendered", "--preset", str(preset), "--answers", str(answers),
                                       "--stack-lint", "passed", "--smoke-build", "compiled-only")
        self.assertEqual(code, 0, err)
        assert report is not None
        self.assertEqual(list(report), REPORT_KEYS)
        self.assertIs(type(report["contractVersion"]), int)
        self.assertEqual(report["contractVersion"], 1)
        self.assertEqual(report["status"], "rendered")
        self.assertNotIn("stoppedAt", report)
        pin = re.search(r"^\|\s*`essentials\.version`\s*\|\s*\**([0-9][^ *|]*)", PINS.read_text(encoding="utf-8"),
                        re.MULTILINE)
        assert pin
        self.assertEqual(report["essentialsVersion"], pin.group(1))
        plugin = json.loads((PLUGIN / ".claude-plugin" / "plugin.json").read_text(encoding="utf-8"))
        self.assertEqual(report["pluginVersion"], plugin["version"])
        self.assertEqual(report["target"], str(self.ws))
        self.assertEqual(report["workspace"], str(self.ws))
        self.assertTrue(Path(report["target"]).is_absolute() and Path(report["workspace"]).is_absolute())
        self.assertEqual(report["answers"], ANSWERS)  # packagePath derived as the renderer derives it
        self.assertTrue(all(isinstance(v, (str, bool)) for v in report["answers"].values()))
        self.assertEqual(report["preset"], ["language", "frontend", "targetDir"])
        self.assertEqual(report["asked"], ["db", "web", "compose", "lintGate", "projectName", "groupId",
                                           "artifactId", "packagePath"])
        self.assertEqual(report["ignoredPreset"], {"web": 'web="jsf" is not one of ["webflux", "webmvc"]',
                                                   "x-extra": "unknown key"})
        self.assertEqual((report["stackLint"], report["smokeBuild"]), ("passed", "compiled-only"))

    def test_asked_overrides_and_removes_from_preset(self):
        preset = self.file("preset.json", {"language": "kotlin", "projectName": "Orders", "targetMode": "current"})
        answers = self.file("answers.json", ANSWERS)
        code, report, err = self.write("--status", "rendered", "--preset", str(preset), "--answers", str(answers),
                                       "--asked", "projectName,groupId,artifactId,packagePath")
        self.assertEqual(code, 0, err)
        assert report is not None
        self.assertEqual(report["preset"], ["language", "targetMode"])
        self.assertEqual(report["asked"], ["projectName", "groupId", "artifactId", "packagePath"])
        self.assertEqual(report["target"], str(self.ws))
        self.assertEqual((report["stackLint"], report["smokeBuild"]), ("not-run", "not-run"))

    def test_without_preset(self):
        code, report, err = self.write("--status", "rendered", "--answers", str(self.file("answers.json", ANSWERS)),
                                       "--target", "orders-service")
        self.assertEqual(code, 0, err)
        assert report is not None
        self.assertEqual((report["preset"], report["ignoredPreset"]), ([], {}))
        self.assertEqual(report["asked"], ["targetMode", *rep.ANSWER_KEYS])
        self.assertEqual(report["target"], str(self.ws / "orders-service"))

    def test_stopped_report(self):
        preset = self.file("preset.json", {"language": "java", "targetDir": "svc", "bogus": True})
        code, report, err = self.write("--status", "stopped", "--stopped-at", "Step 0", "--preset", str(preset))
        self.assertEqual(code, 0, err)
        assert report is not None
        self.assertEqual(list(report), REPORT_KEYS[:4] + ["stoppedAt"] + REPORT_KEYS[4:])
        self.assertEqual((report["status"], report["stoppedAt"]), ("stopped", "Step 0"))
        self.assertEqual(report["target"], str(self.ws / "svc"))
        self.assertEqual(report["answers"], {})
        self.assertEqual((report["preset"], report["asked"]), (["language", "targetDir"], []))
        self.assertEqual(report["ignoredPreset"], {"bogus": "unknown key"})
        self.assertEqual((report["stackLint"], report["smokeBuild"]), ("not-run", "not-run"))

    def test_stopped_keeps_partial_answers(self):
        answers = self.file("answers.json", {"language": "java", "db": "mongo", "notes": "x", "compose": [1]})
        code, report, err = self.write("--status", "stopped", "--stopped-at", "Step 11", "--answers", str(answers),
                                       "--asked", "language,db", "--smoke-build", "not-run")
        self.assertEqual(code, 0, err)
        assert report is not None
        self.assertEqual(report["answers"], {"language": "java", "db": "mongo"})
        self.assertEqual(report["asked"], ["language", "db"])

    def test_refusals_write_nothing(self):
        bad = self.file("answers.json", {**ANSWERS, "db": "oracle"})
        cases = [
            ("--status", "rendered"),                                            # no answers
            ("--status", "rendered", "--answers", str(bad)),                     # the renderer would reject them
            ("--status", "stopped"),                                             # no --stopped-at
            ("--status", "rendered", "--answers", str(self.file("ok.json", ANSWERS)), "--stopped-at", "Step 1"),
            ("--status", "stopped", "--stopped-at", "Step 0", "--asked", "colour"),
        ]
        for argv in cases:
            with self.subTest(argv=argv):
                code, report, err = self.write(*argv)
                self.assertEqual(code, 2)
                self.assertIsNone(report)
                self.assertTrue(err.startswith("init-report: "), err)

    def test_value_sets_for_lint_and_smoke_are_closed(self):
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            rep.main(["write", "--report", str(self.ws / "r.json"), "--workspace", str(self.ws),
                      "--status", "stopped", "--stopped-at", "Step 0", "--smoke-build", "green"])
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            rep.main(["write", "--report", str(self.ws / "r.json"), "--workspace", str(self.ws),
                      "--status", "done"])


if __name__ == "__main__":
    unittest.main()
