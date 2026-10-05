#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3"]
# ///
"""Golden and rule tests for scripts/slice-source.py.

    uv run --script tests/scripts/test_slice_source.py                   # from essentials-plugin/
    uv run --script tests/scripts/test_slice_source.py --update-golden   # rewrite tests/slice-source/golden/

`python3 -m unittest tests/scripts/test_slice_source.py` works too where pyyaml is installed.

Every case under tests/slice-source/cases/ and every fixture in FIXTURES is run in both modes and
byte-compared with its golden JSON; a changed golden is the review. The rule tests below pin the
behaviour each case exists for, so a golden regenerated without reading the diff still cannot hide
a regression in them.
"""

from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parents[2]
SCRIPT = PLUGIN / "scripts" / "slice-source.py"
CASES = PLUGIN / "tests" / "slice-source" / "cases"
GOLDEN = PLUGIN / "tests" / "slice-source" / "golden"
FIXTURES = ["service-entity", "aggregate-lane", "worked-example", "multi-lane", "brownfield-layered"]
UPDATE = os.environ.get("SLICE_SOURCE_UPDATE_GOLDEN") == "1"

try:
    import yaml  # noqa: F401
except ImportError:  # the goldens are written with manifests read; without pyyaml every one would differ
    raise ImportError("test_slice_source needs pyyaml — run it with `uv run --script tests/scripts/test_slice_source.py`")


def _load():
    spec = importlib.util.spec_from_file_location("slice_source", SCRIPT)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {SCRIPT}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


ss = _load()


def run(target, *args, env=None):
    """Run the script from the plugin directory, so `root` in the JSON is the stable relative path."""
    proc = subprocess.run([sys.executable, str(SCRIPT), target, *args], cwd=PLUGIN, capture_output=True,
                          text=True, env=env)
    return proc.returncode, proc.stdout, proc.stderr


def run_json(target, *args):
    code, out, err = run(target, "--json", *args)
    if code == 2:
        raise AssertionError(f"slice-source could not run on {target}: {err}")
    return code, json.loads(out)


def rel_case(name):
    return f"tests/slice-source/cases/{name}"


def rel_fixture(name):
    return f"tests/fixtures/{name}"


def check_golden(test, name, out):
    path = GOLDEN / name
    text = json.dumps(out, indent=2) + "\n"
    if UPDATE:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
        return
    test.assertTrue(path.exists(), f"missing golden {path.name} — run with --update-golden and review it")
    test.assertEqual(path.read_text(encoding="utf-8"), text,
                     f"{path.name} differs from the script's output — if the change is intended, "
                     f"run with --update-golden and review the diff")


def gates(check):
    return sorted((f["gate"], f["slice"]) for f in check["findings"])


class Goldens(unittest.TestCase):
    """Byte-compare facts and check output for every case and fixture."""

    def test_cases(self):
        names = sorted(p.name for p in CASES.iterdir() if p.is_dir())
        self.assertGreaterEqual(len(names), 7)
        for name in names:
            with self.subTest(case=name):
                check_golden(self, f"{name}.facts.json", run_json(rel_case(name))[1])
                check_golden(self, f"{name}.check.json", run_json(rel_case(name), "--check")[1])

    def test_fixtures(self):
        for name in FIXTURES:
            with self.subTest(fixture=name):
                check_golden(self, f"fixture-{name}.facts.json", run_json(rel_fixture(name))[1])
                check_golden(self, f"fixture-{name}.check.json", run_json(rel_fixture(name), "--check")[1])

    def test_no_stale_goldens(self):
        expected = {f"{p.name}.{m}.json" for p in CASES.iterdir() if p.is_dir() for m in ("facts", "check")}
        expected |= {f"fixture-{n}.{m}.json" for n in FIXTURES for m in ("facts", "check")}
        if UPDATE:
            for p in GOLDEN.glob("*.json"):
                if p.name not in expected:
                    p.unlink()
        self.assertEqual(sorted(p.name for p in GOLDEN.glob("*.json")), sorted(expected))


class RenderedTemplates(unittest.TestCase):
    """Every slice-template composition the renderer's goldens hold reads clean: no finding, nothing unparsed."""

    def test_rendered_compositions_check_clean(self):
        root = PLUGIN / "tests" / "slice-golden"
        compositions = sorted(p for p in root.iterdir() if p.is_dir() and any(p.rglob("slice.yaml")))
        self.assertTrue(compositions, f"no rendered compositions under {root}")
        for comp in compositions:
            with self.subTest(composition=comp.name):
                code, check = run_json(f"tests/slice-golden/{comp.name}", "--check")
                self.assertEqual((code, check["findings"], check["unverified"], check["unparsed"]), (0, [], [], []))


class ExitCodes(unittest.TestCase):
    """0 clean · 1 findings · 2 could not run · 3 incomplete (unparsed / unverified), never a pass."""

    EXPECTED = {  # case: (facts exit, check exit)
        "java-clean": (0, 0),
        "kotlin-clean": (0, 0),
        "java-drift": (3, 1),  # facts: one mapping path is unparsed; check: findings win over incompleteness
        "kotlin-drift": (0, 1),
        "java-lanes": (0, 1),
        "lexer": (0, 0),
        "raw-ids": (0, 1),
        "unparsed": (3, 3),
    }

    def test_case_exit_codes(self):
        for name, (facts, check) in self.EXPECTED.items():
            with self.subTest(case=name):
                self.assertEqual(run(rel_case(name))[0], facts)
                self.assertEqual(run(rel_case(name), "--check")[0], check)

    def test_not_a_directory(self):
        self.assertEqual(run("does/not/exist")[0], 2)

    def test_check_without_pyyaml_cannot_run(self):
        with tempfile.TemporaryDirectory() as blocker:
            Path(blocker, "yaml.py").write_text("raise ImportError('blocked for the test')\n", encoding="utf-8")
            env = dict(os.environ, PYTHONPATH=blocker)
            code, _, err = run(rel_case("java-clean"), "--check", env=env)
            self.assertEqual(code, 2)
            self.assertIn("pyyaml", err)
            code, out, _ = run(rel_case("java-clean"), "--json", env=env)
            self.assertEqual(code, 0, "the facts mode is stdlib-only")
            data = json.loads(out)
            self.assertFalse(data["manifestsRead"])
            self.assertEqual({s["idSource"] for s in data["slices"]}, {"directory"})


class Rules(unittest.TestCase):
    """What each case exists to prove, independent of the goldens."""

    @classmethod
    def setUpClass(cls):
        cls.facts = {n: run_json(rel_case(n))[1] for n in ExitCodes.EXPECTED}
        cls.check = {n: run_json(rel_case(n), "--check")[1] for n in ExitCodes.EXPECTED}

    def slice(self, case, sid):
        return next(s for s in self.facts[case]["slices"] if s["id"] == sid)

    def test_clean_cases_have_nothing_to_report(self):
        for case in ("java-clean", "kotlin-clean"):
            with self.subTest(case=case):
                c = self.check[case]
                self.assertEqual((c["findings"], c["unverified"], c["unparsed"]), ([], [], []))
                self.assertTrue(all(e["status"] == "ok" for e in c["endpoints"]))

    def test_kotlin_alias_fqn_and_typealias_resolve_to_the_declared_type(self):
        seen = {}
        for s in self.facts["kotlin-clean"]["slices"]:
            for h in s["handlers"]:
                seen[(s["id"], h["message"]["written"])] = (h["message"]["name"], h["message"]["resolvedBy"])
        self.assertEqual(seen[("orders.order_list", "Placed")], ("OrderPlaced", "alias"))
        self.assertEqual(seen[("orders.order_list", "com.example.shop.orders.events.OrderCancelled")],
                         ("OrderCancelled", "fqn"))
        self.assertEqual(seen[("orders.warehouse", "Placement")], ("OrderPlaced", "typealias"))
        self.assertEqual(seen[("orders.warehouse", "OrderCancelled?")], ("OrderCancelled", "import"))

    def test_java_fqn_parameter_resolves(self):
        h = [h for h in self.slice("java-clean", "orders.order_list")["handlers"]]
        self.assertEqual([x["message"]["name"] for x in h], ["OrderPlaced", "OrderCancelled"])
        self.assertEqual(h[1]["message"]["resolvedBy"], "fqn")

    def test_rename_on_import_is_reported_under_the_declared_name(self):
        msgs = {f["slice"]: f["message"] for f in self.check["kotlin-drift"]["findings"]
                if f["gate"] == "11(b) handled events"}
        self.assertIn("handles OrderShipped (written `Shipped`", msgs["orders.order_board"])
        # the manifest declared the alias `Placed`, which is not a type: OrderPlaced is undeclared
        self.assertIn("handles OrderPlaced (written `Placed`", msgs["orders.notify_customer"])
        self.assertNotIn("OrderCancelled", msgs["orders.notify_customer"])

    def test_discriminators_are_bound_per_handler(self):
        by_path = {e["path"]: e for e in self.check["java-clean"]["endpoints"]}
        self.assertEqual(by_path["/api/orders/list?status="]["boundBy"],
                         ["OrderListAPI.byStatus", "OrderListAPI.shipped"])
        self.assertEqual(by_path["/api/orders/list?status=shipped"]["boundBy"], ["OrderListAPI.shipped"])
        self.assertEqual(by_path["/api/orders/list?from=&to="]["boundBy"], ["OrderListAPI.between"])
        self.assertEqual(by_path["/api/orders/list/search?q="]["boundBy"], ["OrderListAPI.search"])
        self.assertEqual(by_path["/api/orders/list"]["boundBy"], ["OrderListAPI.list"],
                         "an optional filter with a default is not a discriminator")
        drift = {(f["gate"], f["line"]): f for f in self.check["java-drift"]["findings"]
                 if f["slice"] == "billing.invoice_list"}
        sibling = drift[("6 discriminator", 15)]
        self.assertIn("no handler on /api/billing/invoices binds status", sibling["message"])
        self.assertIn("another route: InvoiceListAPI.byStatus", sibling["hint"])
        self.assertIn("does not bind paid=true", drift[("6 discriminator", 16)]["message"])
        self.assertIn("requires params max", drift[("6 discriminator", 17)]["message"])
        self.assertIn(("6 endpoint route", 19), drift)

    def test_drift_findings(self):
        self.assertEqual(gates(self.check["java-drift"]), [
            ("11(b) handled events", "billing.invoice_audit"),
            ("11(b) handled events", "billing.invoice_list"),
            ("6 command mappings", "billing.issue_invoice"),
            ("6 discriminator", "billing.invoice_list"),
            ("6 discriminator", "billing.invoice_list"),
            ("6 discriminator", "billing.invoice_list"),
            ("6 endpoint route", "billing.invoice_list"),
            ("6 undeclared mapping", "billing.invoice_list"),
            ("6 undeclared mapping", "billing.invoice_list"),
            ("6 undeclared mapping", "billing.invoice_list"),
            ("6 undeclared mapping", "billing.issue_invoice"),
        ])
        sealed = next(f for f in self.check["java-drift"]["findings"] if f["slice"] == "billing.invoice_audit")
        self.assertIn("its subtypes InvoiceVoided are not declared", sealed["message"])
        self.assertEqual(gates(self.check["kotlin-drift"]), [
            ("11(b) handled events", "orders.notify_customer"),
            ("11(b) handled events", "orders.order_board"),
            ("6 discriminator", "orders.order_board"),
            ("6 discriminator", "orders.order_board"),
            ("6 undeclared mapping", "orders.order_board"),
        ])
        self.assertEqual([u["slice"] for u in self.check["kotlin-drift"]["unverified"]], ["orders.order_feed"])

    def test_raw_ids_at_the_api_edge(self):
        found = sorted((f["file"].rsplit("/", 1)[-1], f["line"], f["severity"])
                       for f in self.check["raw-ids"]["findings"])
        self.assertEqual(found, [
            ("CancelOrderAPI.java", 15, "Advisory"),     # @PathVariable String orderId
            ("InvoiceLookupAPI.kt", 23, "Advisory"),     # @PathVariable invoiceId: String
            ("InvoiceLookupAPI.kt", 27, "Advisory"),     # @RequestParam customerId: Long?
            ("InvoiceLookupAPI.kt", 27, "Advisory"),     # @RequestParam legacyId: LegacyId? — a typealias of String
            ("OrderLookupAPI.java", 26, "Advisory"),     # @PathVariable("id") long key — the bound name counts
            ("OrderLookupAPI.java", 32, "Advisory"),     # @RequestParam("customerId") Optional<String> customer
            ("OrderLookupAPI.java", 33, "Advisory"),     # @RequestParam UUID batchId
        ])
        self.assertEqual({f["gate"] for f in self.check["raw-ids"]["findings"]}, {"6 raw id"})
        alias = next(f for f in self.check["raw-ids"]["findings"] if "LegacyId" in f["message"])
        self.assertIn("(resolves to String)", alias["message"])
        hints = {f["file"].rsplit(".", 1)[-1]: f["hint"] for f in self.check["raw-ids"]["findings"]}
        self.assertIn("ESS-031", hints["java"])
        self.assertIn("ESS-034", hints["kt"])
        flagged = " ".join(f["message"] for f in self.check["raw-ids"]["findings"])
        for trap in ("sku", "status", "paid", "valid", " q ", "externalId", "OrderId orderId", "InvoiceId"):
            self.assertNotIn(trap, flagged.replace("(e.g. OrderId)", ""), f"{trap} is not a raw id")
        self.assertNotIn("payment_gateway", " ".join(f["file"] for f in self.check["raw-ids"]["findings"]),
                         "a translation webhook carries the external system's ids")

    def test_lane_signal_rows(self):
        c = self.check["java-lanes"]
        self.assertEqual(sorted((f["gate"], f["file"].split("/")[-1])
                                for f in c["findings"]), [
            ("14 declared lanes", "ledger"),
            ("14 declared lanes", "payments"),
            ("14 entities with event store", "catalog"),
            ("14 stale lane", "slice.yaml"),
            ("14 two write styles", "ledger"),
        ])
        detected = {l["bc"]: l["detected"] for l in c["lanes"]}
        self.assertEqual(detected, {"billing": "aggregate", "catalog": "conflict", "drafts": "undetermined",
                                    "ledger": "conflict", "payments": "decider", "shipping": "service-entity?"})
        catalog = next(b for b in self.facts["java-lanes"]["bcs"] if b["name"] == "catalog")
        self.assertEqual([r["line"] for r in catalog["lane"]["signals"]["eventStore"]], [7],
                         "the use site, not the import, and never the javadoc")

    def test_lexer_edges_produce_only_real_handlers_and_mappings(self):
        handlers = sorted((s["id"], h["class"], h["message"]["name"])
                          for s in self.facts["lexer"]["slices"] for h in s["handlers"])
        self.assertEqual(handlers, [("edge.java_edge", "Inner", "Ponged"),
                                    ("edge.kotlin_edge", "Factory", "Ponged"),
                                    ("edge.kotlin_edge", "KotlinEdge", "Pinged")])
        mappings = sorted((s["id"], tuple(m["routes"])) for s in self.facts["lexer"]["slices"] for m in s["mappings"])
        self.assertEqual(mappings, [("edge.java_edge", ("/edge",)), ("edge.kotlin_edge", ("/edge",))])
        anonymous = [na for s in self.facts["lexer"]["slices"] for na in s["notAnalysed"]]
        self.assertEqual(len(anonymous), 2, "a handler in an anonymous class is reported, never dropped")
        self.assertEqual([b["path"] for b in self.facts["lexer"]["bcs"]], ["src/main/*/com/example/edge"],
                         "one BC across the java and kotlin source roots")

    def test_unparsed_is_loud(self):
        f = self.facts["unparsed"]
        self.assertEqual(sorted(u["reason"].split(":")[0] for u in f["unparsed"]),
                         ["handler with no readable first parameter", "not a readable string",
                          "unclosed '{'", "unterminated string literal"])
        self.assertEqual([u["what"] for u in f["unresolved"]], ["dispatch argument"])
        c = self.check["unparsed"]
        self.assertEqual(c["findings"], [])
        self.assertEqual(sorted((u["slice"], u["gate"]) for u in c["unverified"]), [
            ("inbox.broken_view", "11(b) handled events"),
            ("inbox.broken_view", "6 endpoint route"),
            ("inbox.templated", "6 endpoint route"),
        ])

    def test_slice_package_from_sub_packages(self):
        gateway = self.slice("java-clean", "orders.payment_gateway")
        self.assertEqual(gateway["package"], "com.example.shop.orders.external_systems.payment_gateway")
        self.assertEqual(gateway["files"], ["incoming/PaymentWebhook.java"])

    def test_messages_identity_prefers_the_aggregate_id(self):
        placed = next(m for m in self.facts["java-clean"]["messages"] if m["name"] == "OrderPlaced")
        self.assertEqual(placed["identity"], "id: OrderId", "not customerId, the first component ending in Id")
        self.assertNotIn("OrderEvent", [m["name"] for m in self.facts["java-clean"]["messages"]],
                         "a sealed parent handled as a whole is not a message of its own")

    def test_dispatches_schedules_and_subscriptions(self):
        auto = self.slice("java-clean", "orders.cancel_unpaid")
        self.assertEqual([(d["name"], d["own"]) for d in auto["dispatches"]], [("CancelOrder", False)])
        self.assertEqual((auto["schedules"][0]["fixedDelay"], auto["schedules"][0]["initialDelay"]), ("PT15M", "PT1M"))
        streams = {s["id"]: [x["aggregateType"] for x in s["subscriptions"]] for s in self.facts["java-clean"]["slices"]}
        self.assertEqual(streams["orders.order_list"], ["Orders"])      # Class.CONSTANT, itself = OtherClass.CONSTANT
        self.assertEqual(streams["orders.warehouse"], ["Orders"])       # static import
        self.assertEqual(streams["orders.cancel_unpaid"], ["Orders"])   # literal
        place = self.slice("kotlin-clean", "orders.place_order")
        self.assertEqual([(d["name"], d["own"]) for d in place["dispatches"]], [("PlaceOrder", True)])
        cancel = self.slice("kotlin-clean", "orders.auto_cancel")
        self.assertEqual([d["name"] for d in cancel["dispatches"]], ["CancelOrder"], "a local val's type")

    def test_every_finding_carries_a_review_id(self):
        for case, c in self.check.items():
            for f in c["findings"] + c["unverified"]:
                with self.subTest(case=case, gate=f["gate"]):
                    self.assertEqual(f["id"], ss.gate_id(f["gate"]))
                    self.assertRegex(f["id"], r"^ESS-G(6|11b|14)$")


class Units(unittest.TestCase):

    def test_comments_and_strings_hide_their_tokens(self):
        java = ss.lex('/* @A */ x = "@B {"; // @C\n char c = \'{\'; String t = """\n @D } \n""";', False)
        self.assertNotIn("@", [t.t for t in java])
        self.assertEqual([t.t for t in java if t.k == "op" and t.t in "{}"], [])
        kotlin = ss.lex('/* a /* nested */ still */ val s = "${if (x) "}" else "{"}"', True)
        self.assertEqual([t.t for t in kotlin if t.k == "id"], ["val", "s"])
        self.assertIsNone(kotlin[-1].v, "a templated string has no literal value")

    def test_java_block_comments_do_not_nest(self):
        toks = ss.lex("/* a /* b */ c */", False)
        self.assertEqual([t.t for t in toks], ["c", "*", "/"])

    def test_unterminated_input_raises(self):
        for text, kotlin in (('"open', False), ("/* open", True), ('"""open', True)):
            with self.subTest(text=text), self.assertRaises(ss.LexError):
                ss.lex(text, kotlin)
        with self.assertRaises(ss.LexError):
            ss.match_brackets(ss.lex("class A { void f() { }", False))

    def test_routes_and_endpoints(self):
        self.assertEqual(ss.normalise_route("api//orders/{orderId}/"), "/api/orders/{orderId}")
        self.assertEqual(ss.route_key("/api/orders/{orderId}/cancel"), ss.route_key("/api/orders/{id}/cancel"))
        self.assertEqual(ss.parse_endpoint("/api/x?status=&kind=a&flag"),
                         ("/api/x", [("status", None), ("kind", "a"), ("flag", None)]))

    def test_iso_durations(self):
        self.assertEqual(ss.iso_duration("900000"), "PT15M")
        self.assertEqual(ss.iso_duration(3_723_000), "PT1H2M3S")
        self.assertEqual(ss.iso_duration("PT5M"), "PT5M")
        self.assertIsNone(ss.iso_duration("${delay}"))

    def test_gate_ids(self):
        self.assertEqual(ss.gate_id("11(b) handled events"), "ESS-G11b")
        self.assertEqual(ss.gate_id("6 discriminator"), "ESS-G6")
        self.assertEqual(ss.gate_id("14 stale lane"), "ESS-G14")


if __name__ == "__main__":
    if "--update-golden" in sys.argv:
        sys.argv.remove("--update-golden")
        UPDATE = True
    unittest.main()
