#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3", "jsonschema==4.26.0"]
# ///
"""Golden and behaviour tests for scripts/slice-enrich.py.

    uv run --script tests/scripts/test_slice_enrich.py                  # run
    uv run --script tests/scripts/test_slice_enrich.py --update-golden  # rewrite the goldens, then review the diff

Input: the fixture tree `tests/fixtures/enrich/` and the accepted set `tests/slice-enrich/accepted.json`
(what a person would approve from its proposal). Goldens, `tests/slice-enrich/golden/`: the `propose`
JSON and text, and the unified diff `apply --dry-run` prints for the accepted set. `apply` is checked
for writing only absent fields, keeping every other byte, the schemaVersion stamp, its refusals, a
dry run that writes nothing, and a second run that writes nothing more.
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parent.parent.parent
SCRIPT = PLUGIN / "scripts" / "slice-enrich.py"
SOURCE = PLUGIN / "scripts" / "slice-source.py"
LINT = PLUGIN / "scripts" / "slice-lint.py"
FIXTURE = PLUGIN / "tests" / "fixtures" / "enrich"
HERE = PLUGIN / "tests" / "slice-enrich"
GOLDEN = HERE / "golden"
ACCEPTED = HERE / "accepted.json"
ROOT_ARG = "tests/fixtures/enrich"
UPDATE = "--update-golden" in sys.argv
if UPDATE:
    sys.argv.remove("--update-golden")

try:
    import jsonschema  # noqa: F401
    import yaml
except ImportError:  # pragma: no cover
    raise unittest.SkipTest("pyyaml/jsonschema missing — run: uv run --script tests/scripts/test_slice_enrich.py")


def run(script, *args, cwd=PLUGIN):
    return subprocess.run([sys.executable, str(script), *map(str, args)], cwd=cwd, capture_output=True,
                          text=True, timeout=120)


def golden(test, name: str, actual: str):
    path = GOLDEN / name
    if UPDATE:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(actual, encoding="utf-8")
        return
    test.assertTrue(path.is_file(), f"{path} missing — run with --update-golden")
    test.assertEqual(path.read_text(encoding="utf-8"), actual,
                     f"{path.relative_to(PLUGIN)} differs — if intended, --update-golden and review the diff")


def tree_digest(root: Path):
    h = hashlib.sha256()
    for p in sorted(root.rglob("*")):
        if p.is_file():
            h.update(p.relative_to(root).as_posix().encode())
            h.update(p.read_bytes())
    return h.hexdigest()


def manifests(root: Path):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(root.rglob("slice.yaml"))}


class Base(unittest.TestCase):
    facts: Path
    tmp: tempfile.TemporaryDirectory

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.facts = Path(cls.tmp.name) / "facts.json"
        r = run(SOURCE, ROOT_ARG, "--json")
        assert r.returncode in (0, 3), r.stderr
        cls.facts.write_text(r.stdout, encoding="utf-8")

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def propose(self, *extra, root: object = ROOT_ARG, facts: object = None):
        return run(SCRIPT, "propose", root, "--source-facts", facts or self.facts, *extra)

    def proposal(self, *extra):
        r = self.propose("--json", *extra)
        self.assertEqual(r.returncode, 0, r.stderr)
        return json.loads(r.stdout)

    def copy(self) -> Path:
        dest = Path(tempfile.mkdtemp(dir=self.tmp.name)) / "enrich"
        shutil.copytree(FIXTURE, dest)
        return dest

    def accepted(self, edit=None) -> Path:
        data = json.loads(ACCEPTED.read_text(encoding="utf-8"))
        if edit:
            edit(data)
        path = Path(tempfile.mkdtemp(dir=self.tmp.name)) / "accepted.json"
        path.write_text(json.dumps(data), encoding="utf-8")
        return path

    def slice_of(self, data, sid):
        return next(s for s in data["slices"] if s["id"] == sid)


class Propose(Base):
    def test_golden_json(self):
        r = self.propose("--json")
        self.assertEqual(r.returncode, 0, r.stderr)
        golden(self, "propose.json", r.stdout)

    def test_golden_text(self):
        r = self.propose()
        self.assertEqual(r.returncode, 0, r.stderr)
        golden(self, "propose.txt", r.stdout)

    def test_deterministic(self):
        self.assertEqual(self.propose("--json").stdout, self.propose("--json").stdout)

    def test_journeys(self):
        data = self.proposal()
        by_entry = {j["entries"][0]: j for j in data["journeys"]}
        # a member already declares flow: the journey extends it, and that member is not proposed again
        j = by_entry["lending.borrow_book"]
        self.assertEqual((j["suggestedName"], j["declared"]), ("reminders", "reminders"))
        self.assertIn("lending.schedule_reminder", j["slices"])
        self.assertNotIn("flow", self.slice_of(data, "lending.schedule_reminder")["absent"])
        # a dispatch edge; an inbound translation is the entry
        self.assertEqual(by_entry["lending.catalogue_feed"]["slices"], ["lending.catalogue_feed", "lending.register_copy"])
        # a view joins every journey that publishes an event it projects
        flow = self.slice_of(data, "lending.loans_on_shelf")["proposals"]["flow"]["journeys"]
        self.assertEqual(len(flow), 2)
        # a slice nothing connects gets no journey
        acc = self.slice_of(data, "accounts.open_account")
        self.assertIn("flow", acc["absent"])
        self.assertNotIn("flow", acc["proposals"])

    def test_skipped(self):
        data = self.proposal()
        why = {Path(s["path"]).parent.name: s["why"] for s in data["skipped"]}
        self.assertEqual(sorted(why), ["renew_loan", "waitlist"])
        self.assertIn("generator: sdd", why["renew_loan"])
        self.assertIn("--fix-manifests", why["waitlist"])
        self.assertNotIn("lending.renew_loan", [s["id"] for s in data["slices"]])

    def test_present_fields_are_not_proposed(self):
        data = self.proposal()
        ret = self.slice_of(data, "lending.return_book")
        self.assertNotIn("scenarios", ret["absent"])  # `scenarios: []` is present
        self.assertNotIn("tier", ret["absent"])
        self.assertIn("summary", ret["proposals"])  # the template placeholder counts as absent
        self.assertNotIn("invariants", self.slice_of(data, "lending.register_copy")["absent"])  # `invariants: []`

    def test_tests_map_by_package_before_name(self):
        data = self.proposal()
        tests = self.slice_of(data, "lending.borrow_book")["proposals"]["scenarios"]["tests"]
        self.assertIn("ReturnBookAfterLoanTest", {t["class"] for t in tests})
        sched = self.slice_of(data, "lending.schedule_reminder")["proposals"]["scenarios"]["tests"]
        self.assertEqual({t["class"] for t in sched}, {"ScheduleReminderTest"})  # by name, from the BC package
        view = self.slice_of(data, "lending.loans_on_shelf")["proposals"]["scenarios"]
        self.assertEqual(view, {"tests": [], "unmapped": True})

    def test_extraction(self):
        data = self.proposal()
        tests = {t["method"]: t for t in self.slice_of(data, "lending.borrow_book")["proposals"]["scenarios"]["tests"]}
        first = tests["lendsAnAvailableCopy"]["extracted"]
        self.assertEqual(first["given"], [])
        self.assertEqual(first["when"]["with"], {"copyId": "c-1", "memberId": "m-7", "loanDays": 14})
        self.assertEqual(first["when"]["command"], "BorrowBook")
        self.assertIsInstance(first["then"], list)
        self.assertEqual(tests["refusesASecondLoan"]["name"], "a copy out on loan is not lent twice")
        self.assertEqual(tests["refusesASecondLoan"]["extracted"]["then"], {"error": "IllegalStateException"})
        zero = tests["zeroDayLoan"]["extracted"]
        self.assertIsNone(zero["given"])  # a variable, not a construction
        self.assertIsNone(zero["when"])
        self.assertEqual(zero["then"]["message"], "A loan lasts at least one day")
        sched = {t["method"]: t for t in self.slice_of(data, "lending.schedule_reminder")["proposals"]["scenarios"]["tests"]}
        # a non-literal argument: the step is named, its values are not
        self.assertEqual(sched["plansAReminder"]["extracted"]["then"], [{"event": "ReminderScheduled"}])

    def test_extracted_is_manifest_shaped(self):
        # The command copies `extracted` into a scenario as-is: every part it holds must validate against
        # the manifest schema's scenario definition, or apply refuses the manifest.
        import jsonschema
        schema = json.loads((PLUGIN / "references" / "slice" / "slice-yaml.schema.json").read_text())
        validator = jsonschema.Draft202012Validator({"$ref": "#/$defs/scenario", "$defs": schema["$defs"]})
        seen = 0
        for s in self.proposal()["slices"]:
            for t in ((s.get("proposals") or {}).get("scenarios") or {}).get("tests", []):
                e = t["extracted"] or {}
                if e.get("when") is None or e.get("then") is None:
                    continue
                scenario = {"name": t["name"], "test": t["test"], "when": e["when"], "then": e["then"]}
                if e.get("given") is not None:
                    scenario["given"] = e["given"]
                self.assertEqual([x.message for x in validator.iter_errors(scenario)], [], t["test"])
                seen += 1
        self.assertGreater(seen, 3)

    def test_invariants(self):
        data = self.proposal()
        texts = [i["text"] for i in self.slice_of(data, "lending.borrow_book")["proposals"]["invariants"]]
        self.assertEqual(texts, ["A loan lasts at least one day", "A copy that is out on loan cannot be borrowed again"])
        kt = [i["text"] for i in self.slice_of(data, "accounts.open_account")["proposals"]["invariants"]]
        self.assertNotIn("memberId is required", kt)  # ?: throw is a null guard
        self.assertNotIn("tier is required", kt)  # requireNotNull is plumbing
        self.assertIn("A member is at least 16 years old", kt)

    def test_other_fields(self):
        data = self.proposal()
        view = self.slice_of(data, "lending.loans_on_shelf")["proposals"]
        self.assertEqual({c["projection"]: c["value"] for c in view["consistency"]},
                         {"LoansOnShelfProjection": "eventual", "ShelfCountProjection": "strong"})
        self.assertEqual(view["featureFlag"]["value"], {"property": "library.shelf.enabled", "havingValue": "true",
                                                           "matchIfMissing": True})
        self.assertTrue(view["summary"]["evidence"][0]["path"].endswith("CLAUDE.md"))
        self.assertEqual(self.slice_of(data, "lending.borrow_book")["proposals"]["tier"]["value"], "cqrs-es")
        self.assertNotIn("tier", self.slice_of(data, "accounts.open_account")["proposals"])  # lane undetermined
        self.assertIn("maps", self.slice_of(data, "lending.catalogue_feed")["proposals"])
        self.assertNotIn("summary", self.slice_of(data, "lending.catalogue_feed")["proposals"])  # placeholder, no evidence

    def test_bc_and_fields(self):
        data = self.proposal("--bc", "accounts", "--fields", "summary,tier")
        self.assertEqual([s["id"] for s in data["slices"]], ["accounts.open_account"])
        self.assertEqual(data["journeys"], [])
        self.assertEqual(data["fields"], ["summary", "tier"])
        self.assertEqual(self.slice_of(data, "accounts.open_account")["absent"], ["summary", "tier"])

    def test_exit_codes(self):
        empty = Path(tempfile.mkdtemp(dir=self.tmp.name))
        self.assertEqual(self.propose(root=empty).returncode, 3)
        self.assertEqual(self.propose("--fields", "colour").returncode, 2)
        self.assertEqual(self.propose(root=empty / "missing").returncode, 2)
        bad = empty / "bad.json"
        bad.write_text("{}", encoding="utf-8")
        self.assertEqual(self.propose(facts=bad).returncode, 2)


class Apply(Base):
    def test_golden_dry_run_writes_nothing(self):
        root = self.copy()
        before = tree_digest(root)
        r = run(SCRIPT, "apply", root, self.accepted(), "--dry-run")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertEqual(tree_digest(root), before)
        golden(self, "apply-dry-run.diff", r.stdout)

    def test_writes_absent_fields_keeping_every_byte(self):
        root = self.copy()
        before = manifests(root)
        r = run(SCRIPT, "apply", root, self.accepted())
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        after = manifests(root)
        accepted = {s["path"]: s["accept"] for s in json.loads(ACCEPTED.read_text(encoding="utf-8"))["slices"]}
        for path, old in before.items():
            if path not in accepted:
                self.assertEqual(after[path], old, path)
                continue
            new_lines = after[path].decode().splitlines()
            old_doc, new_doc = yaml.safe_load(old), yaml.safe_load(after[path])
            # every original line survives in order, but a placeholder summary and the version stamp
            it = iter(new_lines)
            for line in old.decode().splitlines():
                if line.startswith(("summary:", "schemaVersion:")):
                    continue
                self.assertIn(line, it, f"{path}: lost or moved {line!r}")
            for field, value in accepted[path].items():
                if field == "consistency":
                    got = {p["name"]: p.get("consistency") for p in new_doc["projections"]}
                    for name, v in value.items():
                        self.assertEqual(got[name], v)
                else:
                    self.assertEqual(new_doc[field], value, f"{path}: {field}")
            for key in old_doc:
                if key == "projections":
                    stripped = [{k: v for k, v in p.items() if k != "consistency" or "consistency" in o}
                                for p, o in zip(new_doc[key], old_doc[key])]
                    self.assertEqual(stripped, old_doc[key], f"{path}: projections changed")
                elif key not in ("summary", "schemaVersion"):
                    self.assertEqual(new_doc[key], old_doc[key], f"{path}: {key} changed")
            if {"flow", "scenarios"} & set(accepted[path]):
                self.assertEqual(new_doc["schemaVersion"], "1.4")
                self.assertIn('schemaVersion: "1.4"', new_lines)
        # the written manifests lint clean of anything new (schema, scenario gate 1(d))
        lint = run(LINT, root, "--json")
        self.assertIn(lint.returncode, (0, 1), lint.stderr)
        findings = [f for f in json.loads(lint.stdout)["findings"] if "waitlist" not in f["file"]]
        self.assertEqual(findings, [])

    def test_stdin_for_the_facts_and_the_accepted_set(self):
        """No file need be written to hand one script's output to the next (a sandbox with no writable
        directory): `-` reads standard input, and what slice-source left unparsed is named on stderr."""
        by_file = self.proposal()
        r = subprocess.run([sys.executable, str(SCRIPT), "propose", ROOT_ARG, "--source-facts", "-", "--json"],
                           input=self.facts.read_text(encoding="utf-8"), cwd=PLUGIN, capture_output=True,
                           text=True, timeout=120)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(json.loads(r.stdout), by_file)
        facts = json.loads(self.facts.read_text(encoding="utf-8"))
        facts["unparsed"] = [{"file": "src/X.java", "line": 3, "what": "source file", "reason": "unbalanced"}]
        r = subprocess.run([sys.executable, str(SCRIPT), "propose", ROOT_ARG, "--source-facts", "-", "--json"],
                           input=json.dumps(facts), cwd=PLUGIN, capture_output=True, text=True, timeout=120)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("src/X.java:3  source file: unbalanced", r.stderr)
        root = self.copy()
        r = subprocess.run([sys.executable, str(SCRIPT), "apply", str(root), "-", "--dry-run"],
                           input=ACCEPTED.read_text(encoding="utf-8"), cwd=PLUGIN, capture_output=True, text=True,
                           timeout=120)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout, run(SCRIPT, "apply", root, self.accepted(), "--dry-run").stdout)

    @unittest.skipIf(os.geteuid() == 0, "root writes through file modes")
    def test_a_manifest_that_cannot_be_written_writes_nothing(self):
        root = self.copy()
        before = tree_digest(root)
        accepted = json.loads(ACCEPTED.read_text(encoding="utf-8"))
        locked = root / sorted(s["path"] for s in accepted["slices"] if s.get("accept"))[-1]
        locked.chmod(0o444)
        self.addCleanup(locked.chmod, 0o644)
        r = run(SCRIPT, "apply", root, self.accepted())
        self.assertEqual(r.returncode, 4, r.stdout + r.stderr)
        self.assertIn("EACCES", r.stderr)
        self.assertIn("nothing written; re-run with --emit", r.stderr)
        self.assertEqual(r.stdout, "")
        self.assertEqual(tree_digest(root), before)

    def test_emit_writes_nothing_and_verify_checks_the_written_bytes(self):
        applied = self.copy()
        self.assertEqual(run(SCRIPT, "apply", applied, self.accepted()).returncode, 0)
        root = self.copy()
        before = tree_digest(root)
        locked = sorted(root.rglob("slice.yaml"))
        for f in locked:
            f.chmod(0o444)
        r = run(SCRIPT, "apply", root, self.accepted(), "--emit")
        for f in locked:
            f.chmod(0o644)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(tree_digest(root), before)
        emitted = json.loads(r.stdout)
        self.assertTrue(emitted["files"])
        self.assertTrue(all(f["action"] == "replace" for f in emitted["files"]))
        self.assertTrue(any(line.startswith("would write ") for line in emitted["report"]))
        r = run(SCRIPT, "verify", root, *emitted["verify"])
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stdout.count("differs "), len(emitted["files"]))
        for f in emitted["files"]:
            Path(f["path"]).write_bytes(f["content"].encode("utf-8"))
        self.assertEqual(tree_digest(root), tree_digest(applied))
        r = run(SCRIPT, "verify", root, *emitted["verify"])
        self.assertEqual((r.returncode, r.stdout.strip()), (0, f"identical {len(emitted['files'])} file(s)"))
        first = Path(emitted["files"][0]["path"])
        first.write_text(first.read_text(encoding="utf-8") + "# by hand\n", encoding="utf-8")
        r = run(SCRIPT, "verify", root, *emitted["verify"])
        self.assertEqual(r.returncode, 1)
        self.assertEqual(r.stdout.strip(), f"differs  {first}")
        r = run(SCRIPT, "verify", root, f"../x/slice.yaml={'0' * 64}")
        self.assertEqual(r.returncode, 2)

    def test_second_run_writes_nothing(self):
        root = self.copy()
        accepted = self.accepted()
        self.assertEqual(run(SCRIPT, "apply", root, accepted).returncode, 0)
        once = tree_digest(root)
        r = run(SCRIPT, "apply", root, accepted)
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assertEqual(tree_digest(root), once)
        self.assertNotIn("written", r.stdout)

    def test_refuses_a_field_that_became_present(self):
        root = self.copy()
        rel = "src/main/java/com/example/library/lending/use_cases/register_copy/slice.yaml"
        p = root / rel
        p.write_text(p.read_text(encoding="utf-8").replace("lane: decider\n", "lane: decider\ntier: cqrs\n"), encoding="utf-8")
        edited = p.read_bytes()
        r = run(SCRIPT, "apply", root, self.accepted())
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn(f"refused  {rel}: `tier` is present since propose", r.stdout)
        self.assertEqual(p.read_bytes(), edited)  # the whole manifest is left untouched
        self.assertIn("flow:", (root / rel.replace("register_copy", "borrow_book")).read_text(encoding="utf-8"))

    def test_refuses_an_invalid_result(self):
        root = self.copy()
        rel = "src/main/java/com/example/library/lending/use_cases/return_book/slice.yaml"

        def edit(data):
            for s in data["slices"]:
                if s["path"] == rel:
                    s["accept"]["invariants"] = [{"enforcedBy": "ReturnBookDecider#handle"}]  # no `text`
        before = (root / rel).read_bytes()
        r = run(SCRIPT, "apply", root, self.accepted(edit))
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn(f"refused  {rel}: the result fails the schema", r.stdout)
        self.assertEqual((root / rel).read_bytes(), before)

    def test_a_kind_error_already_there_does_not_refuse(self):
        # An older automation written with `handles:` fails the schema's per-kind oneOf before and after;
        # that error's message embeds the whole manifest, so it must not read as new once a field is added.
        root = self.copy()
        rel = "src/main/java/com/example/library/lending/automations/loan_reminders/slice.yaml"
        p = root / rel
        p.write_text(p.read_text(encoding="utf-8").replace("consumes: [BookBorrowed]", "handles: [BookBorrowed]"),
                     encoding="utf-8")
        r = run(SCRIPT, "apply", root, self.accepted())
        self.assertEqual(r.returncode, 0, r.stdout)
        self.assertIn(f"written {rel}: ", r.stdout)
        self.assertIn("flow: reminders", p.read_text(encoding="utf-8"))

    def test_refuses_consistency_in_a_flow_mapping(self):
        root = self.copy()
        rel = "src/main/java/com/example/library/lending/views/loans_on_shelf/slice.yaml"

        def edit(data):
            for s in data["slices"]:
                if s["path"] == rel:
                    s["accept"]["consistency"]["ShelfCountProjection"] = "strong"
        r = run(SCRIPT, "apply", root, self.accepted(edit))
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertIn("ShelfCountProjection: the projection is a flow mapping", r.stdout)
        doc = yaml.safe_load((root / rel).read_text(encoding="utf-8"))
        self.assertEqual([p.get("consistency") for p in doc["projections"]], ["eventual", None])
        self.assertIn("flow", doc)  # the rest of that manifest is written

    def test_refuses_another_generator_and_bad_paths(self):
        root = self.copy()

        def edit(data):
            data["slices"] = [
                {"path": "src/main/java/com/example/library/lending/use_cases/renew_loan/slice.yaml", "accept": {"flow": "x"}},
                {"path": "../enrich/../../outside/slice.yaml", "accept": {"flow": "x"}},
                {"path": "src/main/java/com/example/library/lending/use_cases/borrow_book/slice.yaml", "accept": {"flow": "Not A Name"}},
            ]
        before = tree_digest(root)
        r = run(SCRIPT, "apply", root, self.accepted(edit))
        self.assertEqual(r.returncode, 1, r.stdout)
        self.assertEqual(r.stdout.count("refused"), 3, r.stdout)
        self.assertEqual(tree_digest(root), before)

    def test_could_not_run(self):
        root = self.copy()
        bad = Path(tempfile.mkdtemp(dir=self.tmp.name)) / "x.json"
        bad.write_text('{"schema": "other"}', encoding="utf-8")
        self.assertEqual(run(SCRIPT, "apply", root, bad).returncode, 2)
        self.assertEqual(run(SCRIPT, "apply", root, root / "missing.json").returncode, 2)


def load_module():
    spec = importlib.util.spec_from_file_location("slice_enrich", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class Yaml(unittest.TestCase):
    m = load_module()

    def test_scalars_read_back(self):
        for value in ["plain words", "a: colon", "hash # mark", "true", "null", "1.4", "*star", "- dash", "'quote",
                      'say "hi"', "trailing ", "", "Class#method", "Klass#`a backtick name`", "ünïcode"]:
            text = f"k: {self.m.scalar(value)}"
            self.assertEqual(yaml.safe_load(text), {"k": value}, text)

    def test_path_is_always_quoted(self):
        self.assertEqual(self.m.scalar("/api/loans", "path"), '"/api/loans"')
        self.assertEqual(self.m.scalar("/api/{id}", "path"), '"/api/{id}"')

    def test_block_emit_round_trips(self):
        value = [{"name": "x", "given": [], "when": {"command": "C", "with": {"a": 1, "path": "/p/{q}"}},
                  "then": {"nothing": True}, "test": "T#m"}]
        lines = self.m.emit("scenarios", value, 0)
        self.assertEqual(yaml.safe_load("\n".join(lines)), {"scenarios": value})
        self.assertIn('        path: "/p/{q}"', lines)
        self.assertFalse(any("{ " in line for line in lines))  # block style, not flow

    def test_stamp_is_never_lowered(self):
        text = 'schemaVersion: "1.5"\nslice: a.b\ntests: {}\n'
        new, written, _ = self.m.edit_manifest([], text, {"flow": "j"}, yaml.safe_load(text))
        self.assertEqual(written, ["flow"])
        self.assertTrue(new.startswith('schemaVersion: "1.5"\n'))
        self.assertIn("flow: j\ntests: {}\n", new)


if __name__ == "__main__":
    unittest.main(verbosity=1)
