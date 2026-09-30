"""Tests for scripts/render-slice.py. Standard library only.

Run from the plugin root:  python3 -m unittest discover -s tests/scripts
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
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

BASE = {"packagePath": "com.acme.shop", "bc": "orders", "Aggregate": "Order", "AggregateType": "Orders"}
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
        self.bc = self.main / "com" / "acme" / "shop" / "orders"

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
        self.assertIn("src/test/java/com/acme/shop/orders/entities/PlaceOrderTest.java", written)
        self.assertIn("src/main/java/com/acme/shop/orders/entities/CLAUDE.md", written)
        self.assertNotIn("src/main/java/com/acme/shop/orders/routing/OrderCommand.java", written)

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
        self.assertIn("import com.acme.shop.orders.use_cases.place_order.PlaceOrderDecider;", config.read_text())
        # Re-wiring the same slice changes nothing.
        values = rs.derive({**BASE, **PLACE}, "decider")
        self.assertEqual([w["status"] for w in rs.wire("java", "command", "decider", values, self.p.main)],
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
        app = self.p.main / "com" / "acme" / "shop" / "DeciderWiring.java"
        self.assertIn("src/main/java/com/acme/shop/DeciderWiring.java", json.loads(out)["written"])
        app.write_text(app.read_text() + "// user edit\n")
        payments = {"packagePath": "com.acme.shop", "bc": "payments", "Aggregate": "Payment",
                    "AggregateType": "Payments", "slice": "request_payment", "Command": "RequestPayment",
                    "Event": "PaymentRequested"}
        code, out, err = self.p.render("command", "decider", payments, "--new-bc", "--wire")
        self.assertEqual(code, 0, err)
        rep = json.loads(out)
        self.assertNotIn("src/main/java/com/acme/shop/DeciderWiring.java", rep["written"])
        self.assertEqual(rep["wiring"][0]["status"], "present")
        self.assertTrue(app.read_text().endswith("// user edit\n"))

    def test_a_second_configurator_is_reported(self):
        # A project scaffolded with a configurator per BC: nothing new is written, and the count is flagged.
        self.p.render("command", "decider", {**BASE, **PLACE}, "--new-bc")
        legacy = self.p.main / "com" / "acme" / "shop" / "billing" / "config" / "BillingConfiguration.java"
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


if __name__ == "__main__":
    unittest.main()
