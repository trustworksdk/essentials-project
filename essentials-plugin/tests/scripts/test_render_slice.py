"""Tests for scripts/render-slice.py. Standard library only.

Run from the plugin root:  python3 -m unittest discover -s tests/scripts
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import re
import shutil
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
SCRIPT = PLUGIN / "scripts" / "render-slice.py"

spec = importlib.util.spec_from_file_location("render_slice", SCRIPT)
if spec is None or spec.loader is None:
    raise ImportError(f"cannot load {SCRIPT}")
rs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rs)

BASE = {"packagePath": "com.example.shop", "bc": "orders", "Aggregate": "Order", "AggregateType": "Orders"}
PLACE = {"slice": "place_order", "Command": "PlaceOrder", "Event": "OrderPlaced"}


def run(*argv: str) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = rs.main(list(argv))
    return code, out.getvalue(), err.getvalue()


def sets(values: dict[str, str]) -> list[str]:
    return [a for k, v in values.items() for a in ("--set", f"{k}={v}")]


class Project:
    """A throwaway project tree with main/test roots."""

    def __init__(self, lang: str = "java"):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.lang = lang
        self.main = self.root / "src" / "main" / lang
        self.test = self.root / "src" / "test" / lang
        self.bc = self.main / "com" / "example" / "shop" / "orders"

    def render(self, kind: str, lane: str, values: dict[str, str], *flags: str) -> tuple[int, str, str]:
        return run("render", "--lang", self.lang, "--kind", kind, "--lane", lane,
                   "--main-root", str(self.main), "--test-root", str(self.test),
                   "--project-root", str(self.root), "--json", *flags, *sets(values))

    def files(self) -> list[str]:
        return sorted(p.relative_to(self.root).as_posix() for p in self.root.rglob("*") if p.is_file())

    def close(self):
        self.tmp.cleanup()


class GoldenTest(unittest.TestCase):
    def test_committed_goldens_match_a_fresh_render(self):
        code, out, _ = run("check")
        self.assertEqual(code, 0, out)

    def test_check_reports_a_drifted_golden(self):
        with tempfile.TemporaryDirectory() as tmp:
            golden = Path(tmp) / "golden"
            shutil.copytree(rs.GOLDEN, golden)
            victim = next(golden.glob("java-decider/src/main/java/**/PlaceOrderDecider.java"))
            victim.write_text(victim.read_text() + "// drift\n")
            code, out, _ = run("check", "--golden", str(golden))
            self.assertEqual(code, 1)
            self.assertIn("DIFF    java-decider/", out)
            (golden / "stray").mkdir()
            code, out, _ = run("check", "--golden", str(golden))
            self.assertIn("STALE   stray", out)

    def test_every_language_and_lane_renders_every_supported_kind(self):
        comps = rs.load_compositions(rs.GOLDEN)
        cells = {}
        for comp in comps.values():
            cells.setdefault((comp["language"], comp["lane"]), []).append(comp)
        self.assertEqual(set(cells), {(l, n) for l in rs.LANGS for n in rs.LANES} - {("kotlin", "aggregate")})
        for (lang, lane), group in cells.items():
            kinds = {s["kind"] for c in group for s in c["steps"] if "kind" in s}
            want = {"command", "view"} if lane == "service-entity" else set(rs.KINDS)
            self.assertEqual(kinds, want, (lang, lane))
            self.assertTrue(any(sum(s.get("kind") == "command" and not s.get("newBc") for s in c["steps"]) >= 1
                                for c in group), f"{lang}/{lane}: a second command slice exercises the second append")

    def test_one_decider_configurator_per_application(self):
        comps = rs.load_compositions(rs.GOLDEN)
        for name, comp in comps.items():
            found = rs.configurators(rs.GOLDEN / name / "src" / "main" / comp["language"])
            if comp["lane"] == "decider":
                self.assertEqual([p.name for p in found], [f"DeciderWiring.{rs.EXT[comp['language']]}"], name)
            else:
                self.assertEqual(found, [], name)
        two = [n for n, c in comps.items() if c["lane"] == "decider" and
               len({s["inputs"].get("bc", c["inputs"].get("bc")) for s in c["steps"] if s.get("newBc")}) >= 2]
        self.assertEqual(len(two), 2, "a two-BC decider composition per language")

    def test_a_seed_or_orientation_step_refuses_to_overwrite_a_file(self):
        for key in ("seed", "orientation"):
            with self.subTest(key=key), tempfile.TemporaryDirectory() as tmp:
                golden, out = Path(tmp) / "golden", Path(tmp) / "out"
                (golden / "_seeds" / "files" / "docs").mkdir(parents=True)
                (golden / "_seeds" / "files" / "docs" / "plan.md").write_text("seeded\n")
                (out / "docs").mkdir(parents=True)
                (out / "docs" / "plan.md").write_text("already here\n")
                comp = {"language": "kotlin", "lane": "decider", "steps": [{key: "_seeds/files"}]}
                with self.assertRaises(rs.RenderError) as caught:
                    rs.render_composition(comp, out, golden)
                self.assertEqual(str(caught.exception), f"{key} _seeds/files would overwrite docs/plan.md")
                self.assertEqual((out / "docs" / "plan.md").read_text(), "already here\n")


class PlaceholderContractTest(unittest.TestCase):
    def test_every_template_placeholder_is_known(self):
        for f in rs.TEMPLATES.rglob("*"):
            if f.is_file():
                names = set(rs.CONTENT_PH.findall(f.read_text(encoding="utf-8"))) | set(rs.PATH_PH.findall(f.name))
                self.assertLessEqual(names, set(rs.PLACEHOLDERS), f)

    def test_the_script_and_slice_authoring_table_agree(self):
        doc = (PLUGIN / "references" / "slice" / "slice-authoring.md").read_text(encoding="utf-8")
        section = doc.split("## 4. Placeholders", 1)[1].split("\n## ", 1)[0]
        documented = set(re.findall(r"`\{\{([A-Za-z]+)\}\}`", section)) - {"name"}  # the Form table's example
        self.assertEqual(documented, set(rs.PLACEHOLDERS))

    def test_every_family_is_reachable_and_has_module_preconditions(self):
        reached = set()
        for lang in rs.LANGS:
            for kind in rs.KINDS:
                for lane in rs.LANES:
                    for new_bc in (False, True):
                        try:
                            reached |= {(lang, f) for f in rs.families_for(lang, kind, lane, new_bc)}
                        except rs.RenderError:
                            pass
        on_disk = {(lang, d.name) for lang in rs.LANGS for d in (rs.TEMPLATES / lang).iterdir() if d.is_dir()}
        self.assertEqual(reached, on_disk)
        self.assertEqual(set(rs.REQUIRES), on_disk)


class RefusalTest(unittest.TestCase):
    def setUp(self):
        self.p = Project()

    def tearDown(self):
        self.p.close()

    def assertRefused(self, result, fragment):
        code, _, err = result
        self.assertEqual(code, 2, err)
        self.assertIn(fragment, err)
        self.assertEqual(self.p.files(), [], "a refused render must write nothing")

    def test_kotlin_aggregate_lane(self):
        self.p.lang = "kotlin"
        self.assertRefused(self.p.render("command", "aggregate", {**BASE, **PLACE, "Aggregates": "Orders"}, "--new-bc"),
                           "Java only")

    def test_service_entity_automation_and_translation(self):
        for kind in ("automation", "translation"):
            self.assertRefused(self.p.render(kind, "service-entity", {**BASE, "slice": "x", "Event": "OrderPlaced"}),
                               "service-entity lane")

    def test_new_bc_from_a_non_command_slice(self):
        self.assertRefused(self.p.render("view", "decider", {**BASE, "view": "order_list", "Event": "OrderPlaced"},
                                         "--new-bc"), "starts with its first command slice")

    def test_unfilled_placeholder(self):
        self.assertRefused(self.p.render("command", "decider", {**BASE, "slice": "place_order", "Command": "PlaceOrder"},
                                         "--new-bc"), "no value for Event")

    def test_double_underscore_in_a_value(self):
        self.assertRefused(self.p.render("command", "decider", {**BASE, **PLACE, "slice": "place__order"}, "--new-bc"),
                           "slice='place__order'")

    def test_tier_is_never_supplied_differently(self):
        self.assertRefused(self.p.render("command", "decider", {**BASE, **PLACE, "tier": "service-entity"}, "--new-bc"),
                           "tier is derived from the lane")

    def test_unknown_input(self):
        self.assertRefused(self.p.render("command", "decider", {**BASE, **PLACE, "Evnt": "X"}, "--new-bc"),
                           "unknown input 'Evnt'")

    def test_missing_bc_without_new_bc(self):
        self.assertRefused(self.p.render("command", "decider", {**BASE, **PLACE}), "pass --new-bc")

    def test_unknown_placeholder_in_a_template(self):
        with tempfile.TemporaryDirectory() as tmp:
            tpl = Path(tmp) / "templates"
            shutil.copytree(rs.TEMPLATES, tpl)
            f = tpl / "java" / "command" / "__Slice__.java"
            f.write_text(f.read_text() + "// {{bogus}}\n")
            original = rs.TEMPLATES
            try:
                result = run("--templates", str(tpl), "render", "--lang", "java", "--kind", "command",
                             "--lane", "decider", "--new-bc", "--main-root", str(self.p.main),
                             "--test-root", str(self.p.test), *sets({**BASE, **PLACE}))
            finally:
                setattr(rs, "TEMPLATES", original)  # main() rebinds the module global for --templates
            self.assertRefused(result, "unknown placeholder(s) bogus")

    def test_leftover_marker_after_substitution(self):
        with tempfile.TemporaryDirectory() as tmp:
            tpl = Path(tmp) / "templates"
            shutil.copytree(rs.TEMPLATES, tpl)
            f = tpl / "java" / "command" / "__Slice__.java"
            f.write_text(f.read_text() + "// {{ Event }}\n")
            original = rs.TEMPLATES
            try:
                result = run("--templates", str(tpl), "render", "--lang", "java", "--kind", "command",
                             "--lane", "decider", "--new-bc", "--main-root", str(self.p.main),
                             "--test-root", str(self.p.test), *sets({**BASE, **PLACE}))
            finally:
                setattr(rs, "TEMPLATES", original)  # main() rebinds the module global for --templates
            self.assertRefused(result, "unsubstituted text after rendering")


class RenderAndWireTest(unittest.TestCase):
    def setUp(self):
        self.p = Project()

    def tearDown(self):
        self.p.close()

    def test_second_slice_into_the_same_directory_is_refused(self):
        self.assertEqual(self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire")[0], 0)
        before = {f: (self.p.root / f).read_bytes() for f in self.p.files()}
        code, _, err = self.p.render("command", "decider", {**BASE, **PLACE}, "--wire")
        self.assertEqual(code, 2)
        self.assertIn("never merge", err)
        self.assertEqual({f: (self.p.root / f).read_bytes() for f in self.p.files()}, before)

    def test_new_bc_on_an_existing_bc_is_refused(self):
        self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc")
        code, _, err = self.p.render("command", "decider", {**BASE, **PLACE, "slice": "cancel_order",
                                                            "Command": "CancelOrder", "Event": "OrderCancelled"},
                                     "--new-bc")
        self.assertEqual(code, 2)
        self.assertIn("drop --new-bc", err)

    def test_lane_mismatch_is_refused(self):
        self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc")
        (self.p.bc / "aggregates").mkdir()
        code, _, err = self.p.render("view", "decider", {**BASE, "view": "order_list", "Event": "OrderPlaced"})
        self.assertEqual(code, 2)
        self.assertIn("looks like the aggregate lane", err)

    def test_service_entity_view_needs_the_entity(self):
        self.p.render("command", "service-entity", {**BASE, **PLACE}, "--new-bc")
        code, _, err = self.p.render("view", "service-entity", {**BASE, "view": "order_list"})
        self.assertEqual(code, 2)
        self.assertIn("entity must be written first", err)

    def test_files_land_where_their_package_says(self):
        code, out, err = self.p.render("command", "service-entity", {**BASE, **PLACE}, "--new-bc")
        self.assertEqual(code, 0, err)
        written = json.loads(out)["written"]
        self.assertIn("src/test/java/com/example/shop/orders/entities/PlaceOrderTest.java", written)
        self.assertIn("src/main/java/com/example/shop/orders/entities/CLAUDE.md", written)
        self.assertNotIn("src/main/java/com/example/shop/orders/routing/OrderCommand.java", written)

    def test_state_templates_only_on_request(self):
        code, out, _ = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--dry-run")
        self.assertFalse(any("OrderState" in f for f in json.loads(out)["written"]))
        code, out, _ = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--dry-run", "--with-state")
        self.assertEqual(sum("OrderState" in f for f in json.loads(out)["written"]), 2)
        self.assertEqual(self.p.files(), [], "--dry-run writes nothing")

    def test_derived_inputs(self):
        _, out, _ = self.p.render("command", "service-entity", {**BASE, **PLACE}, "--new-bc", "--dry-run")
        inputs = json.loads(out)["inputs"]
        self.assertEqual((inputs["Bc"], inputs["Slice"], inputs["sliceCamel"], inputs["aggregate"]),
                         ("Orders", "PlaceOrder", "placeOrder", "order"))
        self.assertEqual((inputs["Entity"], inputs["entity"], inputs["tier"]), ("Order", "order", "service-entity"))
        self.assertEqual((inputs["apiPath"], inputs["owner"]), ("/api/orders", "orders-team"))

    def test_wiring_is_idempotent_and_never_guessed(self):
        _, out, _ = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire")
        self.assertEqual([w["status"] for w in json.loads(out)["wiring"]], ["applied", "applied", "present"])
        config = self.p.bc / "config" / "OrdersConfiguration.java"
        self.assertIn("import com.example.shop.orders.use_cases.place_order.PlaceOrderDecider;", config.read_text())
        # Re-wiring the same slice changes nothing.
        values = rs.derive({**BASE, **PLACE}, "decider")
        self.assertEqual([w["status"] for w in rs.wire("java", "command", "decider", values, self.p.main, rs.Txn())],
                         ["present", "present", "present"])
        # A user who removed the anchors gets `manual`, and the files are left alone.
        config.write_text(config.read_text().replace(rs.BEAN_ANCHOR, "hand-edited "))
        parent = self.p.bc / "events" / "OrderEvent.java"
        parent.write_text(parent.read_text().replace("// /essentials:add-slice appends", "// appends"))
        snapshot = (config.read_text(), parent.read_text())
        _, out, _ = self.p.render("command", "decider", {**BASE, "slice": "cancel_order", "Command": "CancelOrder",
                                                         "Event": "OrderCancelled"}, "--wire")
        self.assertEqual([w["status"] for w in json.loads(out)["wiring"]], ["present", "manual", "manual"])
        self.assertEqual((config.read_text(), parent.read_text()), snapshot)

    def test_app_wiring_is_created_once_and_never_overwritten(self):
        _, out, _ = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire")
        app = self.p.main / "com" / "example" / "shop" / "DeciderWiring.java"
        self.assertIn("src/main/java/com/example/shop/DeciderWiring.java", json.loads(out)["written"])
        app.write_text(app.read_text() + "// user edit\n")
        payments = {"packagePath": "com.example.shop", "bc": "payments", "Aggregate": "Payment",
                    "AggregateType": "Payments", "slice": "request_payment", "Command": "RequestPayment",
                    "Event": "PaymentRequested"}
        code, out, err = self.p.render("command", "decider", payments, "--new-bc", "--wire")
        self.assertEqual(code, 0, err)
        rep = json.loads(out)
        self.assertNotIn("src/main/java/com/example/shop/DeciderWiring.java", rep["written"])
        self.assertEqual(rep["wiring"][0]["status"], "present")
        self.assertTrue(app.read_text().endswith("// user edit\n"))

    def test_a_second_configurator_is_reported(self):
        # A project scaffolded with a configurator per BC: nothing new is written, and the count is flagged.
        self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc")
        legacy = self.p.main / "com" / "example" / "shop" / "billing" / "config" / "BillingConfiguration.java"
        legacy.parent.mkdir(parents=True)
        legacy.write_text("class BillingConfiguration { Object c() { return new "
                          "EventStreamDeciderAndAggregateTypeConfigurator(null, null, null, null); } }\n")
        _, out, _ = self.p.render("command", "decider", {**BASE, "slice": "cancel_order", "Command": "CancelOrder",
                                                         "Event": "OrderCancelled"}, "--wire")
        check = json.loads(out)["wiring"][0]
        self.assertEqual(check["status"], "manual")
        self.assertIn("2 configurators found", check["reason"])

    def test_todos_are_reported_with_locations(self):
        _, out, _ = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc")
        todos = json.loads(out)["todos"]
        self.assertTrue(todos)
        self.assertTrue(all(re.match(r"^src/.+:\d+: ", t) for t in todos), todos[:3])


class RequiresTest(unittest.TestCase):
    POM = """<project><dependencies>
      <dependency><groupId>dk.trustworks.essentials.components</groupId>
        <artifactId>spring-boot-starter-postgresql-event-store</artifactId></dependency>
      {extra}
    </dependencies></project>"""

    def check(self, extra: str, lang: str, kind: str, lane: str, name="pom.xml") -> tuple[int, dict]:
        with tempfile.TemporaryDirectory() as tmp:
            pom = Path(tmp) / name
            pom.write_text(self.POM.format(extra=extra) if name == "pom.xml" else extra)
            code, out, _ = run("requires", "--lang", lang, "--kind", kind, "--lane", lane, "--build", str(pom), "--json")
            return code, json.loads(out)

    def test_optional_eventsourced_aggregates_is_reported(self):
        code, rep = self.check("", "java", "command", "decider")
        self.assertEqual(code, 1)
        self.assertEqual(rep["missing"], [["eventsourced-aggregates"]])

    def test_java_view_needs_document_db_and_kotlin_runtime(self):
        code, rep = self.check("<dependency><artifactId>postgresql-document-db</artifactId></dependency>",
                               "java", "view", "decider")
        self.assertEqual(rep["missing"], [["kotlin-stdlib", "kotlin-stdlib-jdk8"], ["kotlin-reflect"]])
        code, rep = self.check("<dependency><artifactId>postgresql-document-db</artifactId></dependency>"
                               "<dependency><artifactId>kotlin-stdlib-jdk8</artifactId></dependency>"
                               "<dependency><artifactId>kotlin-reflect</artifactId></dependency>",
                               "java", "view", "decider")
        self.assertEqual((code, rep["missing"]), (0, []))

    def test_gradle_build(self):
        code, rep = self.check('implementation("dk.trustworks.essentials.components:spring-boot-starter-postgresql-'
                               'event-store:$essentialsVersion")\nimplementation("dk.trustworks.essentials.components:'
                               'kotlin-eventsourcing:$essentialsVersion")', "kotlin", "command", "decider", "build.gradle.kts")
        self.assertEqual((code, rep["missing"]), (0, []))


SDD_MANIFEST = """\
# planned by sdd
schemaVersion: "1.4"
slice: orders.place_order
kind: command
bc: orders
owner: checkout-team
status: planned
summary: Places an order.   # kept as written
generator: sdd
language: java
tier: cqrs
flow: checkout
requirements: [REQ-1]
handles: [PlaceOrder]
publishes: [OrderPlaced]
endpoints:
  - { method: POST, path: "/api/orders/place", auth: user }
writes: [Order]
scenarios:
  - name: places an order
    when: { command: PlaceOrder, with: { orderId: o-1 } }
    then:
      - event: OrderPlaced
x-sdd-task: "T-1 # not a comment"
"""


class RenderInterfaceTest(unittest.TestCase):
    """The parts of `render` another plugin's agent drives (references/sdd-contract.md)."""

    def setUp(self):
        self.p = Project()

    def tearDown(self):
        self.p.close()

    def snapshot(self) -> dict[str, bytes]:
        return {f: (self.p.root / f).read_bytes() for f in self.p.files()}

    def orientation(self, manifest: str = SDD_MANIFEST, slice_id: str = "place_order") -> Path:
        d = self.p.bc / "use_cases" / slice_id
        d.mkdir(parents=True)
        (self.p.bc / "CLAUDE.md").write_text("# orders, planned\n")
        (d / "CLAUDE.md").write_text("# planned slice\n")
        (d / "slice.yaml").write_text(manifest)
        return d / "slice.yaml"

    def test_report_names_the_interface_and_the_slice(self):
        code, out, err = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--dry-run")
        self.assertEqual(code, 0, err)
        rep = json.loads(out)
        self.assertEqual((rep["interface"], rep["reportVersion"], rep["tier"], rep["slice"], rep["tests"]),
                         (rs.INTERFACE, rs.REPORT_VERSION, "cqrs-es", "orders.place_order", "stub"))
        pin = re.search(r"`essentials\.version` \| \*\*([^*]+)\*\*", rs.STACK_PINS.read_text())
        self.assertIsNotNone(pin)
        self.assertEqual(rep["essentialsVersion"], pin and pin.group(1))
        self.assertEqual(rep["manifest"], {"path": "src/main/java/com/example/shop/orders/use_cases/place_order/slice.yaml",
                                           "source": "rendered"})
        self.assertEqual(sorted(f["path"] for f in rep["files"]), rep["written"])

    def test_every_stub_test_is_listed(self):
        for lane, kinds in (("decider", ("view", "automation", "translation")), ("aggregate", ("view",))):
            with self.subTest(lane=lane):
                p = Project()
                try:
                    _, out, _ = p.render("command", lane, {**BASE, "Aggregates": "Orders", **PLACE}, "--new-bc")
                    reps = [json.loads(out)]
                    for kind in kinds:
                        extra = {"view": {"view": "order_list", "Event": "OrderPlaced"},
                                 "automation": {"slice": "ship_order", "Event": "OrderPlaced"},
                                 "translation": {"externalSystem": "billing", "ExternalEvent": "InvoiceIssued",
                                                 "Event": "OrderPlaced"}}[kind]
                        code, out, err = p.render(kind, lane, {**BASE, "Aggregates": "Orders", **extra})
                        self.assertEqual(code, 0, err)
                        reps.append(json.loads(out))
                    for rep in reps:
                        tests = sorted(f["path"] for f in rep["files"] if f["role"] == "test")
                        self.assertTrue(tests)
                        self.assertEqual(sorted(s["path"] for s in rep["stubTests"]), tests)
                        self.assertTrue(all(t.startswith("src/test/") for t in tests))
                        self.assertTrue(all(b["status"] in ("implemented", "placeholder") for b in rep["behaviours"]))
                finally:
                    p.close()

    def test_tests_none_writes_no_test_and_says_so(self):
        _, stub, _ = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--dry-run")
        code, out, err = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire", "--tests", "none")
        self.assertEqual(code, 0, err)
        rep, stub = json.loads(out), json.loads(stub)
        self.assertEqual(rep["stubTests"], [])
        self.assertFalse(any(f.startswith("src/test/") for f in self.p.files()))
        self.assertEqual(rep["written"], [w for w in stub["written"] if not w.startswith("src/test/")])
        self.assertEqual([w["status"] for w in rep["wiring"]], ["applied", "applied", "present"])
        manifest = (self.p.root / rep["manifest"]["path"]).read_text()
        self.assertNotIn("present: true", manifest)
        self.assertIn("unit: { present: false }", manifest)

    def test_a_failure_part_way_restores_every_file(self):
        self.assertEqual(self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire")[0], 0)
        before = self.snapshot()
        real = rs.wire_permits

        def permits_then_fail(path, values, txn):
            real(path, values, txn)
            raise OSError(28, "No space left on device")

        setattr(rs, "wire_permits", permits_then_fail)
        try:
            code, out, err = self.p.render("command", "decider", {**BASE, "slice": "cancel_order",
                                                                  "Command": "CancelOrder", "Event": "OrderCancelled"},
                                           "--wire")
        finally:
            setattr(rs, "wire_permits", real)
        self.assertEqual((code, out), (2, ""))
        self.assertTrue(err.startswith("render-slice: "), err)
        self.assertEqual(self.snapshot(), before, "the @Bean and permits edits and every new file are undone")
        self.assertFalse((self.p.bc / "use_cases" / "cancel_order").exists())

    def test_an_existing_bc_supplies_its_aggregate_names(self):
        self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc")
        values = {"packagePath": "com.example.shop", "bc": "orders", "view": "order_list", "Event": "OrderPlaced"}
        code, out, err = self.p.render("view", "decider", values)
        self.assertEqual(code, 0, err)
        rep = json.loads(out)
        self.assertEqual((rep["inputs"]["AggregateType"], rep["inputs"]["Aggregate"]), ("Orders", "Order"))
        self.assertEqual(rep["inputSources"]["AggregateType"], "project")

    def test_manifest_render_keeps_what_it_does_not_own(self):
        manifest = self.orientation()
        other = self.p.bc / "use_cases" / "cancel_order"
        other.mkdir()
        (other / "slice.yaml").write_text("schemaVersion: \"1.4\"\n")
        values = {"packagePath": "com.example.shop", "AggregateType": "Orders"}
        code, out, err = self.p.render("command", "decider", values, "--new-bc", "--wire", "--manifest", str(manifest))
        self.assertEqual(code, 0, err)
        rep = json.loads(out)
        for name, want in (("Command", "PlaceOrder"), ("Event", "OrderPlaced"), ("Aggregate", "Order"),
                           ("apiPath", "/api/orders/place"), ("owner", "checkout-team"), ("slice", "place_order")):
            self.assertEqual((rep["inputs"][name], rep["inputSources"][name]), (want, "manifest"))
        self.assertEqual(rep["manifest"]["source"], "merged")
        self.assertEqual(sorted(rep["manifest"]["corrected"]), ["generator", "tier"])
        self.assertIn("lane", rep["manifest"]["filled"])
        self.assertEqual(rep["kept"], ["src/main/java/com/example/shop/orders/CLAUDE.md",
                                       "src/main/java/com/example/shop/orders/use_cases/place_order/CLAUDE.md"])
        self.assertEqual((self.p.bc / "CLAUDE.md").read_text(), "# orders, planned\n")
        self.assertEqual((other / "slice.yaml").read_text(), "schemaVersion: \"1.4\"\n")
        merged = manifest.read_text()
        for kept in ("# planned by sdd\n", 'schemaVersion: "1.4"\n', "summary: Places an order.   # kept as written\n",
                     "flow: checkout\n", "requirements: [REQ-1]\n", SDD_MANIFEST[SDD_MANIFEST.index("scenarios:"):],
                     'path: "/api/orders/place"'):
            self.assertIn(kept, merged)
        self.assertIn("generator: essentials\n", merged)
        self.assertRegex(merged, r"\ntier: cqrs-es\b")
        self.assertRegex(merged, r"\nlane: decider\b")
        self.assertIn("src/main/java/com/example/shop/orders/use_cases/place_order/slice.yaml", rep["written"])

    def test_manifest_render_without_new_bc(self):
        self.p.render("command", "decider", {**BASE, "slice": "cancel_order", "Command": "CancelOrder",
                                             "Event": "OrderCancelled"}, "--new-bc")
        d = self.p.bc / "use_cases" / "place_order"
        d.mkdir()
        (d / "slice.yaml").write_text(SDD_MANIFEST)
        code, out, err = self.p.render("command", "decider", {"packagePath": "com.example.shop"},
                                       "--manifest", str(d / "slice.yaml"))
        self.assertEqual(code, 0, err)
        self.assertTrue((d / "CLAUDE.md").is_file(), "a CLAUDE.md the planner did not write is rendered")
        ship = self.p.bc / "use_cases" / "ship_order"
        ship.mkdir()
        (ship / "Notes.java").write_text("x")
        (ship / "slice.yaml").write_text(SDD_MANIFEST.replace("place_order", "ship_order")
                                         .replace("PlaceOrder", "ShipOrder").replace("OrderPlaced", "OrderShipped"))
        code, _, err = self.p.render("command", "decider", {"packagePath": "com.example.shop"},
                                     "--manifest", str(ship / "slice.yaml"))
        self.assertEqual(code, 2)
        self.assertIn("holds Notes.java", err)

    def test_manifest_refusals_write_nothing(self):
        manifest = self.orientation()
        values = {"packagePath": "com.example.shop", "AggregateType": "Orders"}
        cases = [
            ("kind", lambda: manifest.write_text(SDD_MANIFEST.replace("kind: command", "kind: view")), "contradicts --kind"),
            ("language", lambda: manifest.write_text(SDD_MANIFEST.replace("language: java", "language: kotlin")),
             "contradicts --lang"),
            ("slice", lambda: manifest.write_text(SDD_MANIFEST.replace("orders.place_order", "place_order")),
             "qualified <bc>.<slice-id>"),
            ("stray", lambda: (self.p.bc / "use_cases" / "place_order" / "Notes.kt").write_text("x"),
             "more than orientation files"),
        ]
        for name, setup, message in cases:
            with self.subTest(name):
                manifest.write_text(SDD_MANIFEST)
                setup()
                before = self.snapshot()
                code, out, err = self.p.render("command", "decider", values, "--new-bc", "--manifest", str(manifest))
                self.assertEqual(code, 2, out)
                self.assertIn(message, err)
                self.assertEqual(self.snapshot(), before)
                (self.p.bc / "use_cases" / "place_order" / "Notes.kt").unlink(missing_ok=True)

    def test_manifest_at_another_slices_path_is_refused(self):
        manifest = self.orientation()
        code, _, err = self.p.render("command", "decider", {"packagePath": "com.example.shop", "AggregateType": "Orders",
                                                            "slice": "cancel_order"},
                                     "--new-bc", "--manifest", str(manifest))
        self.assertEqual(code, 2)
        self.assertIn("contradicts --set slice=cancel_order", err)

    def test_new_bc_with_code_in_it_is_still_refused(self):
        manifest = self.orientation()
        (self.p.bc / "types").mkdir()
        (self.p.bc / "types" / "OrderId.java").write_text("x")
        code, _, err = self.p.render("command", "decider", {"packagePath": "com.example.shop", "AggregateType": "Orders"},
                                     "--new-bc", "--manifest", str(manifest))
        self.assertEqual(code, 2)
        self.assertIn("more than orientation files", err)


class EmitAndVerifyTest(unittest.TestCase):
    """--emit, verify and exit 4: the path for a filesystem this process may not write, where the caller writes
    the emitted files with another tool and verify proves the bytes (slice-authoring.md §4b)."""

    CANCEL = {"packagePath": "com.example.shop", "bc": "orders", "slice": "cancel_order", "Command": "CancelOrder",
              "Event": "OrderCancelled"}

    def setUp(self):
        self.p = Project()

    def tearDown(self):
        self.p.close()

    def snapshot(self) -> dict[str, bytes]:
        return {f: (self.p.root / f).read_bytes() for f in self.p.files()}

    def verify(self, values: dict[str, str], *flags: str) -> tuple[int, dict]:
        code, out, err = run("verify", "--lang", "java", "--kind", "command", "--lane", "decider",
                             "--main-root", str(self.p.main), "--test-root", str(self.p.test),
                             "--project-root", str(self.p.root), "--json", *flags, *sets(values))
        self.assertIn(code, (0, 1), err)
        return code, json.loads(out)["verify"]

    def write_emitted(self, out: str) -> dict:
        rep = json.loads(out)
        for f in rep["files"]:
            (self.p.root / f["path"]).parent.mkdir(parents=True, exist_ok=True)
            (self.p.root / f["path"]).write_bytes(f["content"].encode("utf-8"))
        return rep

    def test_emit_writes_nothing_and_its_files_are_what_render_writes(self):
        code, out, err = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire", "--emit")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.p.files(), [], "--emit writes nothing")
        rep = json.loads(out)
        self.assertEqual({f["action"] for f in rep["files"]}, {"create"})
        self.assertTrue(rep["todos"])
        emitted = {f["path"]: f["content"].encode("utf-8") for f in rep["files"]}
        self.assertEqual(self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire")[0], 0)
        self.assertEqual(self.snapshot(), emitted)

    def test_emit_lists_a_wiring_edit_as_a_replace(self):
        self.assertEqual(self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire")[0], 0)
        before = self.snapshot()
        code, out, err = self.p.render("command", "decider", self.CANCEL, "--wire", "--emit")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.snapshot(), before)
        replaced = sorted((f["path"], f["role"]) for f in json.loads(out)["files"] if f["action"] == "replace")
        self.assertEqual(replaced, [("src/main/java/com/example/shop/orders/config/OrdersConfiguration.java", "wiring"),
                                    ("src/main/java/com/example/shop/orders/events/OrderEvent.java", "wiring")])

    def test_emit_runs_again_part_way_through_the_writes(self):
        flags = ("--new-bc", "--wire", "--emit")
        files = json.loads(self.p.render("command", "decider", {**BASE, **PLACE}, *flags)[1])["files"]
        half = [f for f in files if "/orders/" in f["path"]][:6]
        for f in half:
            (self.p.root / f["path"]).parent.mkdir(parents=True, exist_ok=True)
            (self.p.root / f["path"]).write_text(f["content"])
        code, out, err = self.p.render("command", "decider", {**BASE, **PLACE}, *flags)
        self.assertEqual(code, 0, err)
        self.assertEqual({f["path"]: f["content"] for f in json.loads(out)["files"]},
                         {f["path"]: f["content"] for f in files}, "the same files, whichever already exist")
        # A target that holds anything else is still refused, as is a file the render would not write.
        victim = self.p.root / half[0]["path"]
        victim.write_text(half[0]["content"] + "// edited\n")
        code, _, err = self.p.render("command", "decider", {**BASE, **PLACE}, *flags)
        self.assertEqual(code, 2)
        self.assertIn("already exists", err)
        victim.write_text(half[0]["content"])
        (self.p.bc / "Stray.java").write_text("x")
        self.assertEqual(self.p.render("command", "decider", {**BASE, **PLACE}, *flags)[0], 2)

    def test_emit_only_prints_one_file_raw(self):
        files = json.loads(self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire", "--emit")[1])["files"]
        target = next(f for f in files if f["path"].endswith("PlaceOrderDecider.java"))
        code, out, err = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire", "--emit",
                                       "--only", target["path"])
        self.assertEqual((code, out), (0, target["content"]), err)
        code, _, err = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--emit", "--only", "nope.java")
        self.assertEqual(code, 2)
        self.assertIn("not a file this render emits", err)
        code, _, err = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--only", target["path"])
        self.assertEqual((code, self.p.files()), (2, []))
        self.assertIn("--only needs --emit", err)

    def test_verify_passes_on_the_emitted_files_and_names_a_one_byte_change(self):
        self.write_emitted(self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire", "--emit")[1])
        code, rep = self.verify({**BASE, **PLACE}, "--new-bc", "--wire")
        self.assertEqual((code, rep["missing"], rep["differs"]), (0, [], []))
        before = self.snapshot()
        decider = next(self.p.root.rglob("PlaceOrderDecider.java"))
        decider.write_bytes(decider.read_bytes() + b" ")
        code, rep = self.verify({**BASE, **PLACE}, "--new-bc", "--wire")
        self.assertEqual((code, rep["differs"]), (1, [decider.relative_to(self.p.root).as_posix()]))
        decider.unlink()
        code, rep = self.verify({**BASE, **PLACE}, "--new-bc", "--wire")
        self.assertEqual((code, rep["missing"]), (1, [decider.relative_to(self.p.root).as_posix()]))
        before.pop(decider.relative_to(self.p.root).as_posix())
        self.assertEqual(self.snapshot(), before, "verify writes nothing")

    def test_verify_names_a_wiring_edit_that_was_not_made(self):
        self.assertEqual(self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire")[0], 0)
        rep = json.loads(self.p.render("command", "decider", self.CANCEL, "--wire", "--emit")[1])
        for f in rep["files"]:
            if f["role"] != "wiring":
                (self.p.root / f["path"]).parent.mkdir(parents=True, exist_ok=True)
                (self.p.root / f["path"]).write_text(f["content"])
        code, rep = self.verify(self.CANCEL, "--wire")
        self.assertEqual((code, rep["missing"]), (1, []))
        self.assertEqual(rep["differs"], ["src/main/java/com/example/shop/orders/config/OrdersConfiguration.java",
                                          "src/main/java/com/example/shop/orders/events/OrderEvent.java"])

    def test_verify_after_a_manifest_render(self):
        d = self.p.bc / "use_cases" / "place_order"
        d.mkdir(parents=True)
        (d / "slice.yaml").write_text(SDD_MANIFEST)
        values = {"packagePath": "com.example.shop", "AggregateType": "Orders"}
        flags = ("--new-bc", "--wire", "--manifest", str(d / "slice.yaml"))
        rep = self.write_emitted(self.p.render("command", "decider", values, *flags, "--emit")[1])
        self.assertIn(("src/main/java/com/example/shop/orders/use_cases/place_order/slice.yaml", "replace"),
                      [(f["path"], f["action"]) for f in rep["files"]])
        code, rep = self.verify(values, *flags)
        self.assertEqual((code, rep["missing"], rep["differs"]), (0, [], []))

    @unittest.skipIf(hasattr(os, "geteuid") and os.geteuid() == 0, "root writes through 0555")
    def test_render_into_a_read_only_directory_exits_4_and_writes_nothing(self):
        self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire")
        before = self.snapshot()
        # The new slice's own directory is writable; the use_cases directory it goes in, and the wiring, are not.
        modes = {}
        for d in [self.p.bc / "use_cases", *[p for p in self.p.bc.rglob("*") if p.is_file()]]:
            modes[d] = d.stat().st_mode
            d.chmod(0o555 if d.is_dir() else 0o444)
        try:
            code, out, err = self.p.render("command", "decider", self.CANCEL, "--wire")
        finally:
            for d, mode in modes.items():
                d.chmod(mode)
        self.assertEqual((code, out), (4, ""))
        self.assertRegex(err, r"^render-slice: cannot write .+ \((EACCES|EROFS)\) — nothing written; "
                              r"re-run with --emit\n$")
        self.assertEqual(self.snapshot(), before)

    @unittest.skipIf(hasattr(os, "geteuid") and os.geteuid() == 0, "root writes through 0555")
    def test_a_read_only_project_root(self):
        self.p.root.chmod(0o555)
        try:
            code, _, err = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire")
            emit, out, _ = self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc", "--wire", "--emit")
        finally:
            self.p.root.chmod(0o755)
        self.assertEqual(code, 4, err)
        self.assertIn("re-run with --emit", err)
        self.assertEqual((emit, self.p.files()), (0, []))
        self.assertTrue(json.loads(out)["files"])


class ManifestReaderTest(unittest.TestCase):
    def read(self, text: str) -> dict:
        _, blocks, _ = rs.split_blocks(text, "t")
        return {b.key: rs.read_block(b) for b in blocks}

    def test_block_and_flow_styles_read_alike(self):
        flow = self.read('handles: [A, "B"]\nendpoints:\n  - { method: GET, path: "/x/{id}", auth: user }\n')
        block = self.read("handles:\n  - A\n  - 'B'\nendpoints:\n- method: GET\n  path: /x/{id}\n  auth: user\n")
        self.assertEqual(flow, block)
        self.assertEqual(flow["endpoints"][0]["path"], "/x/{id}")

    def test_nested_projection(self):
        v = self.read("projections:\n  - name: P   # c\n    aggregateTypes: [Orders]\n    from:\n      - OrderPlaced\n")
        self.assertEqual(rs.dig(v["projections"], (0, "from", 0)), "OrderPlaced")

    def test_comments_and_quotes(self):
        v = self.read("""owner: "a # b"   # comment\nsummary: it's fine # c\n'x-key': 1\n""")
        self.assertEqual((v["owner"], v["summary"], v["x-key"]), ("a # b", "it's fine", "1"))

    def test_what_it_cannot_read_is_reported(self):
        for text in ("handles: [A,\n  B]\n", "handles: &a [A]\n", "notes: |\n  text\n"):
            with self.subTest(text=text):
                _, blocks, _ = rs.split_blocks(text, "t")
                with self.assertRaises(rs.Unreadable):
                    rs.read_block(blocks[0])

    def test_unreadable_top_level_line_is_refused(self):
        with self.assertRaises(rs.RenderError):
            rs.split_blocks("a: 1\n? complex\n", "t")
        with self.assertRaises(rs.RenderError):
            rs.split_blocks("a: 1\na: 2\n", "t")

    def test_blocks_round_trip_byte_for_byte(self):
        text = SDD_MANIFEST + "\n# trailing comment\n"
        header, blocks, trailer = rs.split_blocks(text, "t")
        self.assertEqual(rs.join_blocks(header, blocks, trailer), text)


if __name__ == "__main__":
    unittest.main()
