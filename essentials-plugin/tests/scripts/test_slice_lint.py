#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3", "jsonschema==4.26.0"]
# ///
"""Behaviour tests for scripts/slice-lint.py.

    uv run --script tests/scripts/test_slice_lint.py

Every manifest tree this plugin ships must lint clean with the schema, except the findings a fixture
carries on purpose, which are pinned here by gate and file. The script runs as a subprocess, exactly
as slice-check Step 1.5 and a project's pre-commit hook run it.
"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parent.parent.parent
SCRIPT = PLUGIN / "scripts" / "slice-lint.py"
SCHEMA = PLUGIN / "references" / "slice" / "slice-yaml.schema.json"

try:
    import jsonschema  # noqa: F401
    import yaml  # noqa: F401
except ImportError:  # pragma: no cover
    raise unittest.SkipTest("pyyaml/jsonschema missing — run: uv run --script tests/scripts/test_slice_lint.py")


def lint(root, *args, script=SCRIPT, cwd=PLUGIN):
    r = subprocess.run([sys.executable, str(script), str(root), "--require-schema", "--json", *args],
                       cwd=cwd, capture_output=True, text=True, timeout=60)
    # --json always prints one document when the script ran; no output is a failed run, never a result.
    if not r.stdout.strip():
        raise AssertionError(f"slice-lint printed no JSON (exit {r.returncode}): {r.stderr.strip()}")
    doc: dict = json.loads(r.stdout)
    return r.returncode, doc


# Runs the script with `jsonschema` unimportable, as on a machine with pyyaml alone (the hook's
# python3 branch). A meta_path finder refuses the import before any installed copy is consulted.
BLOCK_JSONSCHEMA = """
import runpy, sys
class Block:
    def find_spec(self, name, path=None, target=None):
        if name == "jsonschema" or name.startswith("jsonschema."):
            raise ImportError("blocked for the test: " + name)
        return None
sys.meta_path.insert(0, Block())
script = sys.argv[1]
sys.argv = sys.argv[1:]
runpy.run_path(script, run_name="__main__")
"""


def lint_without_jsonschema(root, *args):
    r = subprocess.run([sys.executable, "-c", BLOCK_JSONSCHEMA, str(SCRIPT), str(root), *args],
                       cwd=PLUGIN, capture_output=True, text=True, timeout=60)
    return r.returncode, r.stdout, r.stderr


def found(doc, root):
    """(gate, id, path relative to root) per finding."""
    return sorted((f["gate"], f["id"], str(Path(f["file"]).relative_to(Path(root).resolve())))
                  for f in doc["findings"])


class SliceLint(unittest.TestCase):
    def test_fixtures_clean_except_deliberate_breakage(self):
        for fixture in sorted((PLUGIN / "tests" / "fixtures").iterdir()):
            if not fixture.is_dir():
                continue
            with self.subTest(fixture=fixture.name):
                code, doc = lint(fixture)
                self.assertTrue(doc["schemaValidated"] or doc["manifests"] == 0)
                if fixture.name == "multi-lane":
                    # ML-5: `tier: aggregate` is a write style in the tier field.
                    self.assertEqual(code, 1)
                    self.assertEqual(found(doc, fixture), [(
                        "14 tier", "ESS-G14",
                        "src/main/java/com/example/multi/ledger/use_cases/post_entry/slice.yaml")])
                    self.assertEqual(doc["findings"][0]["line"], 10)
                    self.assertEqual(doc["findings"][0]["severity"], "Should-fix")
                elif fixture.name == "atlas":
                    # The Atlas fixture carries a broken manifest (and may carry a malformed scenario)
                    # on purpose; its scenarios must otherwise name the slices' own messages.
                    self.assertEqual(code, 1)
                    self.assertTrue(doc["findings"])
                    self.assertEqual({f["gate"] for f in doc["findings"]} - {"1(a) parse", "1(b) schema"},
                                     set(), [f["message"] for f in doc["findings"]])
                elif fixture.name == "atlas-multi":
                    # Two applications reusing slice ids: one folder is not one slice-id namespace, so the
                    # folder fails uniqueness and sole-handler while each application root is clean.
                    self.assertEqual(code, 1)
                    self.assertEqual({f["gate"] for f in doc["findings"]}, {"3 uniqueness", "4(a) sole handler"})
                    for app in ("alpha", "beta"):
                        app_code, app_doc = lint(fixture / app)
                        self.assertEqual((app_code, app_doc["findings"]), (0, []))
                elif fixture.name == "enrich":
                    # Manifests from before schema 1.4 on purpose: one does not parse, two lack a required
                    # field slice-enrich backfills (tests/scripts/test_slice_enrich.py).
                    self.assertEqual(code, 1)
                    self.assertEqual(sorted((f["gate"], Path(f["file"]).parent.name) for f in doc["findings"]),
                                     [("1(a) parse", "waitlist"), ("1(b) schema", "catalogue_feed"),
                                      ("1(b) schema", "loans_on_shelf")])
                else:
                    self.assertEqual((code, doc["findings"]), (0, []))

    def test_slice_golden_compositions_clean(self):
        golden = PLUGIN / "tests" / "slice-golden"
        names = json.loads((golden / "compositions.json").read_text())["compositions"]
        for name in names:
            with self.subTest(composition=name):
                code, doc = lint(golden / name)
                self.assertEqual((code, doc["findings"]), (0, []))

    def test_script_cases_clean(self):
        for case in [*sorted((PLUGIN / "tests" / "slice-source" / "cases").iterdir()),
                     PLUGIN / "tests" / "slice-index" / "cycle"]:
            with self.subTest(case=case.name):
                code, doc = lint(case)
                self.assertEqual((code, doc["findings"]), (0, []))

    def test_no_manifest_is_exit_0_and_still_json(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, doc = lint(tmp)
        self.assertEqual((code, doc["manifests"], doc["findings"]), (0, 0, []))

    def test_supersedes_twin_is_not_a_sole_owner_finding(self):
        # order_list/_v2 and customer_summary/_v2 are declared twins (gate 13's business);
        # order_stats + order_totals share a read model with no link (4(b)); Order is written
        # from two BCs (4(c)).
        root = PLUGIN / "tests" / "slice-index" / "twin"
        code, doc = lint(root)
        self.assertEqual(code, 1)
        self.assertEqual(found(doc, root), [
            ("4(b) sole owner", "ESS-G4b", "orders/views/order_stats/slice.yaml"),
            ("4(b) sole owner", "ESS-G4b", "orders/views/order_totals/slice.yaml"),
            ("4(c) one BC per aggregate", "ESS-G4c", "billing/use_cases/refund_order/slice.yaml"),
            ("4(c) one BC per aggregate", "ESS-G4c", "orders/use_cases/cancel_order/slice.yaml"),
            ("4(c) one BC per aggregate", "ESS-G4c", "orders/use_cases/place_order/slice.yaml"),
        ])

    def test_duplicate_handler_names_the_other_manifest_even_with_a_shared_id(self):
        # Two compositions carry the same slice ids (gate 3), so the old id comparison listed nobody.
        golden = PLUGIN / "tests" / "slice-golden"
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("java-decider", "kotlin-decider"):
                shutil.copytree(golden / name / "src", Path(tmp) / name)
            code, doc = lint(tmp)
        self.assertEqual(code, 1)
        sole = [f["message"] for f in doc["findings"] if f["gate"] == "4(a) sole handler"]
        self.assertTrue(sole)
        for message in sole:
            self.assertNotIn("also handled by: .", message)
            self.assertRegex(message, r"also handled by: orders\.\w+\. ")

    def test_decider_in_tier_is_reported_too(self):
        with tempfile.TemporaryDirectory() as tmp:
            src = PLUGIN / "tests" / "slice-golden" / "java-decider" / "src"
            shutil.copytree(src, Path(tmp) / "src")
            manifest = next(Path(tmp).rglob("place_order/slice.yaml"))
            manifest.write_text(manifest.read_text().replace("tier: cqrs-es", "tier: decider"))
            code, doc = lint(tmp)
        self.assertEqual(code, 1)
        self.assertEqual([(f["gate"], f["hint"]) for f in doc["findings"]],
                         [("14 tier", "lane: decider; tier: cqrs-es if the BC is event-sourced, else "
                                      "remove tier (absent ⇒ project default)")])

    def test_skipped_schema_is_exit_2_unless_allowed(self):
        clean = PLUGIN / "tests" / "fixtures" / "service-entity"
        code, out, err = lint_without_jsonschema(clean)
        self.assertEqual(code, 2, err)
        self.assertIn("SCHEMA VALIDATION SKIPPED", out)
        code, out, _ = lint_without_jsonschema(clean, "--allow-skip-schema")
        self.assertEqual(code, 0)
        self.assertIn("SCHEMA VALIDATION SKIPPED", out)
        # An installed hook or CI line written for an older copy still parses, and still fails.
        code, _, _ = lint_without_jsonschema(clean, "--require-schema")
        self.assertEqual(code, 2)
        code, out, err = lint_without_jsonschema(clean, "--json", "--quiet")
        self.assertEqual(code, 2)
        self.assertFalse(json.loads(out)["schemaValidated"])
        self.assertIn("SCHEMA VALIDATION SKIPPED", err)
        code, _, _ = lint_without_jsonschema(clean, "--allow-skip-schema", "--require-schema")
        self.assertEqual(code, 2)  # contradictory flags are a usage error

    def test_findings_win_over_a_skipped_schema(self):
        code, out, _ = lint_without_jsonschema(PLUGIN / "tests" / "fixtures" / "multi-lane", "--json")
        doc = json.loads(out)
        self.assertEqual((code, doc["schemaValidated"]), (1, False))
        self.assertEqual([f["gate"] for f in doc["findings"]], ["14 tier"])

    def test_baseline_reports_only_new_findings(self):
        fixture = PLUGIN / "tests" / "fixtures" / "multi-lane"
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp) / "base.json"
            _, doc = lint(fixture)
            base.write_text(json.dumps(doc))
            code, doc = lint(fixture, "--baseline", str(base))
            self.assertEqual((code, doc["findings"], doc["baseline"]["suppressed"]), (0, [], 1))

            project = Path(tmp) / "project"
            shutil.copytree(fixture, project)
            manifest = next(p for p in project.rglob("slice.yaml")
                            if "tier: aggregate" not in p.read_text() and "tier:" in p.read_text())
            text = manifest.read_text()
            manifest.write_text(text[:text.index("tier:")] + "tier: decider\n"
                                + text[text.index("\n", text.index("tier:")) + 1:])
            code, doc = lint(project, "--baseline", str(base))
            self.assertEqual(code, 1)
            self.assertEqual(found(doc, project),
                             [("14 tier", "ESS-G14", str(manifest.relative_to(project)))])
            self.assertEqual(doc["baseline"]["suppressed"], 1)

    def test_baseline_matches_across_checkouts_and_older_copies(self):
        # Gate 3's message names manifests by absolute path; the root is cut out on both sides.
        golden = PLUGIN / "tests" / "slice-golden"
        with tempfile.TemporaryDirectory() as tmp:
            for checkout in ("a", "b"):
                for name in ("java-decider", "kotlin-decider"):
                    shutil.copytree(golden / name / "src", Path(tmp) / checkout / name)
            code, doc = lint(Path(tmp) / "a")
            self.assertEqual(code, 1)
            self.assertTrue(any(f["gate"] == "3 uniqueness" for f in doc["findings"]))
            for f in doc["findings"]:
                del f["id"]  # a copy predating finding ids
            base = Path(tmp) / "base.json"
            base.write_text(json.dumps(doc))
            code, doc = lint(Path(tmp) / "b", "--baseline", str(base))
        self.assertEqual((code, doc["findings"]), (0, []))
        self.assertGreater(doc["baseline"]["suppressed"], 0)

    def test_unreadable_baseline_is_exit_2(self):
        with tempfile.TemporaryDirectory() as tmp:
            bad = Path(tmp) / "bad.json"
            bad.write_text("{}")
            r = subprocess.run([sys.executable, str(SCRIPT), str(PLUGIN / "tests" / "fixtures" / "multi-lane"),
                                "--baseline", str(bad)], capture_output=True, text=True, timeout=60)
        self.assertEqual(r.returncode, 2)
        self.assertIn("cannot read baseline", r.stderr)

    def test_project_installed_copy_finds_its_schema_beside_it(self):
        # /essentials:init copies the script and the schema into <project>/scripts/.
        with tempfile.TemporaryDirectory() as tmp:
            project = Path(tmp)
            (project / "scripts").mkdir()
            shutil.copy(SCRIPT, project / "scripts" / "slice-lint.py")
            shutil.copy(SCHEMA, project / "scripts" / "slice-yaml.schema.json")
            shutil.copytree(PLUGIN / "tests" / "fixtures" / "service-entity", project / "app")
            code, doc = lint(".", script=project / "scripts" / "slice-lint.py", cwd=project)
        self.assertEqual(code, 0)
        self.assertTrue(doc["schemaValidated"])
        self.assertGreater(doc["manifests"], 0)


# --- flow and scenarios (schema 1.4) ------------------------------------------------------------
#
# One manifest per case, all linted in one run. Cases share command and read-model names, so the
# cross-manifest gates (3, 4) fire between them; only the gates under test are read.

BASES = {
    "command": {
        "kind": "command", "handles": ["CancelOrder"], "publishes": ["OrderCancelled"],
        "writes": ["Order"], "endpoints": [{"method": "POST", "path": "/api/orders/cancel", "auth": "user"}],
    },
    "view": {
        "kind": "view", "serves": ["ListOrders"], "reads": [{"name": "OrderListView"}],
        "projections": [{"name": "OrderListProjection", "aggregateTypes": ["Orders"],
                         "from": ["OrderPlaced", "OrderCancelled"], "consistency": "eventual"}],
    },
    "automation": {"kind": "automation", "consumes": ["OrderPlaced"], "dispatches": ["InitiatePayment"]},
    "automation-scheduled": {"kind": "automation", "schedule": {"cron": "0 0 3 * * *"},
                             "dispatches": ["ExpireQuotes"]},
    "inbound": {
        "kind": "translation", "externalSystem": "billing", "direction": "inbound",
        "consumes": ["InvoiceIssued"], "maps": [{"from": "InvoiceIssued", "to": "RecordPayment"}],
        "dispatches": ["RecordPayment"], "publishes": ["PaymentRecorded"],
    },
    "outbound": {"kind": "translation", "externalSystem": "billing", "direction": "outbound",
                 "consumes": ["OrderPlaced"]},
    "both": {
        "kind": "translation", "externalSystem": "billing", "direction": "both",
        "consumes": ["InvoiceIssued", "OrderPlaced"], "dispatches": ["RecordPayment"],
        "maps": [{"from": "InvoiceIssued", "to": "RecordPayment"}],
    },
}

E, C, Q, S, X = ("event", "command", "query", "state", "external")


def step(key, name, **values):
    out: dict = {key: name}
    if values:
        out["with"] = values
    return out


def sc(name="s", given=None, when=None, then=None, **extra):
    out: dict = {"name": name}
    if given is not None:
        out["given"] = given
    if when is not None:
        out["when"] = when
    if then is not None:
        out["then"] = then
    out.update(extra)
    return out


NOTHING = {"nothing": True}
ERROR = {"error": "OrderNotFound", "message": "Order o-404 not found"}

# (case, base, extra root fields) — every row of the manifest-guide §3 kind table, valid.
VALID = [
    ("command-events", "command", {"scenarios": [sc(
        given=[step(E, "OrderPlaced", orderId="o-1"), step(S, "Order")],
        when=step(C, "CancelOrder", orderId="o-1"),
        then=[step(E, "OrderCancelled", orderId="o-1")], test="CancelOrderTest#cancelsPlacedOrder")]}),
    ("command-error", "command", {"scenarios": [sc(when=step(C, "CancelOrder"), then=ERROR)]}),
    ("command-nothing", "command", {"scenarios": [sc(
        given=[step(E, "OrderPlaced"), step(E, "OrderCancelled")], when=step(C, "CancelOrder"),
        then=NOTHING, note="idempotent retry")]}),
    ("view-rows", "view", {"scenarios": [sc(
        given=[step(E, "OrderPlaced", orderId="o-1", total=12.5)], when=step(Q, "ListOrders"),
        then={"readModel": "OrderListView", "rows": [{"orderId": "o-1", "total": 12.5, "shipped": False,
                                                      "note": None}]})]}),
    ("view-empty-no-when", "view", {"scenarios": [sc(then={"readModel": "OrderListView", "rows": []})]}),
    ("view-owns-projection-name", "view", {"owns": ["OrderBoard"], "scenarios": [
        sc("a", then={"readModel": "OrderBoard", "rows": []}),
        sc("b", then={"readModel": "OrderListProjection", "rows": []})]}),
    ("view-error", "view", {"scenarios": [sc(when=step(Q, "ListOrders", page=-1),
                                             then={"error": "IllegalArgumentException"})]}),
    ("automation-dispatches", "automation", {"scenarios": [sc(
        given=[step(E, "PaymentFailed"), step(S, "PaymentAttempts")], when=step(E, "OrderPlaced"),
        then=[step(C, "InitiatePayment", orderId="o-1")])]}),
    ("automation-nothing-error", "automation", {"scenarios": [
        sc("ignored", when=step(E, "OrderPlaced"), then=NOTHING),
        sc("fails", when=step(E, "OrderPlaced"), then={"error": "PaymentGatewayDown"})]}),
    ("automation-schedule", "automation-scheduled", {"scenarios": [sc(
        when={"schedule": True}, then=[step(C, "ExpireQuotes")])]}),
    ("inbound", "inbound", {"scenarios": [
        sc("records", given=[step(E, "OrderPlaced")], when=step(X, "InvoiceIssued", invoiceId="i-1"),
           then=[step(C, "RecordPayment"), step(E, "PaymentRecorded")]),
        sc("dup", when=step(X, "InvoiceIssued"), then=NOTHING),
        sc("bad", when=step(X, "InvoiceIssued"), then={"error": "UnknownInvoice"})]}),
    ("outbound", "outbound", {"scenarios": [
        sc("sends", given=[step(S, "SentInvoices")], when=step(E, "OrderPlaced"),
           then=[step(X, "CreateInvoice", amount=10)]),
        sc("skip", when=step(E, "OrderPlaced"), then=NOTHING),
        sc("down", when=step(E, "OrderPlaced"), then={"error": "BillingUnavailable"})]}),
    ("both-directions", "both", {"scenarios": [
        sc("in", when=step(X, "InvoiceIssued"), then=[step(C, "RecordPayment")]),
        sc("out", when=step(E, "OrderPlaced"), then=[step(X, "CreateInvoice")])]}),
    ("flow-one", "command", {"flow": "checkout"}),
    ("flow-several", "view", {"flow": ["checkout", "returns_2"]}),
    # any id grammar: the requirement source owns it, not this schema
    ("requirements", "command", {"requirements": ["REQ-ORDERS-12", "PROJ-481", "nfr.latency.p99"]}),
    # a view on the service-entity lane reads entity state: it has no events to be given
    ("view-given-state-entity-lane", "view", {"lane": "service-entity",
                                              "scenarios": [sc(given=[step(S, "ShippingOrder")],
                                                               then={"readModel": "OrderListView", "rows": []})]}),
    ("view-given-state-entity-tier", "view", {"tier": "service-entity",
                                              "scenarios": [sc(given=[step(S, "ShippingOrder")],
                                                               then={"readModel": "OrderListView", "rows": []})]}),
]

# (case, base, extra root fields, expected (gate, message fragment) findings) — each one invalid.
INVALID = [
    # command
    ("cmd-when-not-handled", "command", {"scenarios": [sc(when=step(C, "CancelOrdr"), then=NOTHING)]},
     [("1(d)", "`when: { command: CancelOrdr }` is not in `handles` [CancelOrder]")]),
    ("cmd-when-event", "command", {"scenarios": [sc(when=step(E, "OrderPlaced"), then=NOTHING)]},
     [("1(d)", "a command slice is triggered by a `command`")]),
    ("cmd-no-when", "command", {"scenarios": [sc(then=NOTHING)]},
     [("1(d)", "no `when` — a command slice's scenario needs its trigger")]),
    ("cmd-then-unpublished", "command", {"scenarios": [sc(when=step(C, "CancelOrder"),
                                                          then=[step(E, "OrderShipped")])]},
     [("1(d)", "`then: { event: OrderShipped }` is not in `publishes` [OrderCancelled]")]),
    ("cmd-then-command", "command", {"scenarios": [sc(when=step(C, "CancelOrder"),
                                                      then=[step(C, "RefundOrder")])]},
     [("1(d)", "a command slice emits `event` steps")]),
    ("cmd-then-read-model", "command", {"scenarios": [sc(when=step(C, "CancelOrder"),
                                                         then={"readModel": "Order", "rows": []})]},
     [("1(d)", "is a view's outcome")]),
    ("cmd-given-query", "command", {"scenarios": [sc(given=[step(Q, "ListOrders")],
                                                     when=step(C, "CancelOrder"), then=NOTHING)]},
     [("1(d)", "`given` holds a `query` step")]),
    ("cmd-given-state-unwritten", "command", {"scenarios": [sc(given=[step(S, "Invoice")],
                                                               when=step(C, "CancelOrder"), then=NOTHING)]},
     [("1(d)", "`given: { state: Invoice }` is not in `writes` [Order]")]),
    # view
    ("view-when-not-served", "view", {"scenarios": [sc(when=step(Q, "GetOrder"),
                                                       then={"readModel": "OrderListView", "rows": []})]},
     [("1(d)", "`when: { query: GetOrder }` is not in `serves` [ListOrders]")]),
    ("view-when-command", "view", {"scenarios": [sc(when=step(C, "CancelOrder"),
                                                    then={"readModel": "OrderListView", "rows": []})]},
     [("1(d)", "a view's `when` is a `query` it serves")]),
    ("view-given-unprojected", "view", {"scenarios": [sc(given=[step(E, "OrderShipped")],
                                                         then={"readModel": "OrderListView", "rows": []})]},
     [("1(d)", "`given: { event: OrderShipped }` is not an event this view projects")]),
    ("view-given-state", "view", {"scenarios": [sc(given=[step(S, "Order")],
                                                   then={"readModel": "OrderListView", "rows": []})]},
     [("1(d)", "on a view slice `given` takes `event` steps only")]),
    ("view-then-steps", "view", {"scenarios": [sc(then=[step(E, "OrderPlaced")])]},
     [("1(d)", "a view's `then` is `{ readModel, rows }`")]),
    ("view-then-nothing", "view", {"scenarios": [sc(when=step(Q, "ListOrders"), then=NOTHING)]},
     [("1(d)", "an empty read model is `{ readModel: <Name>, rows: [] }`")]),
    ("view-then-unknown-model", "view", {"scenarios": [sc(then={"readModel": "Orders", "rows": []})]},
     [("1(d)", "`then: { readModel: Orders }` is not this view's read model")]),
    ("view-error-no-when", "view", {"scenarios": [sc(then=ERROR)]},
     [("1(d)", "needs the `when` query that fails")]),
    # automation
    ("auto-when-unconsumed", "automation", {"scenarios": [sc(when=step(E, "OrderShipped"), then=NOTHING)]},
     [("1(d)", "`when: { event: OrderShipped }` is not in `consumes` [OrderPlaced]")]),
    ("auto-schedule-undeclared", "automation", {"scenarios": [sc(when={"schedule": True}, then=NOTHING)]},
     [("1(d)", "the slice declares no `schedule`")]),
    ("auto-when-command", "automation", {"scenarios": [sc(when=step(C, "InitiatePayment"), then=NOTHING)]},
     [("1(d)", "an automation is triggered by an `event`")]),
    ("auto-no-when", "automation", {"scenarios": [sc(then=NOTHING)]},
     [("1(d)", "no `when` — an automation slice's scenario")]),
    ("auto-then-undispatched", "automation", {"scenarios": [sc(when=step(E, "OrderPlaced"),
                                                               then=[step(C, "CancelOrder")])]},
     [("1(d)", "`then: { command: CancelOrder }` is not in `dispatches` [InitiatePayment]")]),
    ("auto-then-event", "automation", {"scenarios": [sc(when=step(E, "OrderPlaced"),
                                                        then=[step(E, "PaymentInitiated")])]},
     [("1(d)", "an automation slice emits `command` steps")]),
    # translation, inbound
    ("in-when-event", "inbound", {"scenarios": [sc(when=step(E, "InvoiceIssued"), then=NOTHING)]},
     [("1(d)", "this translation is triggered by an `external` message (`direction: inbound`)")]),
    ("in-no-when", "inbound", {"scenarios": [sc(then=NOTHING)]},
     [("1(d)", "no `when` — a translation slice's scenario")]),
    ("in-then-external", "inbound", {"scenarios": [sc(when=step(X, "InvoiceIssued"),
                                                      then=[step(X, "Ack")])]},
     [("1(d)", "an inbound translation emits `command` and `event` steps")]),
    ("in-then-undispatched", "inbound", {"scenarios": [sc(when=step(X, "InvoiceIssued"),
                                                          then=[step(C, "CancelOrder"),
                                                                step(E, "OrderCancelled")])]},
     [("1(d)", "`then: { command: CancelOrder }` is not in `dispatches` [RecordPayment]"),
      ("1(d)", "`then: { event: OrderCancelled }` is not in `publishes` [PaymentRecorded]")]),
    # translation, outbound
    ("out-when-external", "outbound", {"scenarios": [sc(when=step(X, "InvoiceIssued"), then=NOTHING)]},
     [("1(d)", "an `event` it consumes (`direction: outbound`)")]),
    ("out-when-unconsumed", "outbound", {"scenarios": [sc(when=step(E, "OrderShipped"), then=NOTHING)]},
     [("1(d)", "`when: { event: OrderShipped }` is not in `consumes` [OrderPlaced]")]),
    ("out-then-command", "outbound", {"scenarios": [sc(when=step(E, "OrderPlaced"),
                                                       then=[step(C, "RecordPayment")])]},
     [("1(d)", "an outbound translation emits `external` steps")]),
    ("both-in-emits-external", "both", {"scenarios": [sc(when=step(X, "InvoiceIssued"),
                                                         then=[step(X, "CreateInvoice")])]},
     [("1(d)", "an inbound translation emits `command` and `event` steps")]),
    # uniqueness, the one shape rule the schema cannot state
    ("duplicate-name", "command", {"scenarios": [sc("twice", when=step(C, "CancelOrder"), then=NOTHING),
                                                 sc("twice", when=step(C, "CancelOrder"), then=ERROR)]},
     [("1(d)", "2 scenarios of this slice share the name")] * 2),
    # shape — the schema
    ("then-empty", "command", {"scenarios": [sc(when=step(C, "CancelOrder"), then=[])]},
     [("1(b)", "scenarios/0/then")]),
    ("then-missing", "command", {"scenarios": [sc(when=step(C, "CancelOrder"))]},
     [("1(b)", "'then' is a required property")]),
    ("name-missing", "command", {"scenarios": [{"when": step(C, "CancelOrder"), "then": NOTHING}]},
     [("1(b)", "'name' is a required property")]),
    ("step-two-keys", "command", {"scenarios": [sc(when={"command": "CancelOrder", "event": "OrderPlaced"},
                                                   then=NOTHING)]},
     [("1(b)", "scenarios/0/when")]),
    ("step-no-key", "command", {"scenarios": [sc(when={"with": {"orderId": "o-1"}}, then=NOTHING)]},
     [("1(b)", "scenarios/0/when")]),
    ("then-two-forms", "command", {"scenarios": [sc(when=step(C, "CancelOrder"),
                                                    then={"error": "X", "nothing": True})]},
     [("1(b)", "scenarios/0/then")]),
    ("nothing-false", "command", {"scenarios": [sc(when=step(C, "CancelOrder"), then={"nothing": False})]},
     [("1(b)", "scenarios/0/then")]),
    ("schedule-false", "automation-scheduled", {"scenarios": [sc(when={"schedule": False}, then=NOTHING)]},
     [("1(b)", "scenarios/0/when")]),
    ("with-nested", "command", {"scenarios": [sc(when=step(C, "CancelOrder", lines=[1, 2]), then=NOTHING)]},
     [("1(b)", "scenarios/0/when")]),
    ("unknown-scenario-key", "command", {"scenarios": [sc(when=step(C, "CancelOrder"), then=NOTHING,
                                                          expect="x")]},
     [("1(b)", "scenarios/0: unknown key 'expect'")]),
    ("flow-capitalised", "command", {"flow": "Checkout"}, [("1(b)", "flow")]),
    ("flow-empty-list", "command", {"flow": []}, [("1(b)", "flow")]),
    ("flow-duplicate", "command", {"flow": ["checkout", "checkout"]}, [("1(b)", "flow")]),
    ("flow-number", "command", {"flow": 3}, [("1(b)", "flow")]),
    ("requirements-empty-list", "command", {"requirements": []}, [("1(b)", "requirements")]),
    ("requirements-duplicate", "command", {"requirements": ["REQ-A-1", "REQ-A-1"]}, [("1(b)", "requirements")]),
    ("requirements-empty-id", "command", {"requirements": [""]}, [("1(b)", "requirements/0")]),
    ("requirements-scalar", "command", {"requirements": "REQ-A-1"}, [("1(b)", "requirements")]),
]


class FlowAndScenarios(unittest.TestCase):
    """Schema 1.4: `flow` and `scenarios` — the shape in the schema, the names in gate 1(d)."""

    by_case: dict = {}
    hints: dict = {}

    @classmethod
    def setUpClass(cls):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for case, base, extra, *_ in VALID + INVALID:
                manifest = {"schemaVersion": "1.4", "slice": f"orders.{case.replace('-', '_')}",
                            "bc": "orders", "owner": "orders-team", "status": "live", "summary": case,
                            "tests": {"unit": {"present": True}}, **BASES[base], **extra}
                (root / case).mkdir()
                (root / case / "slice.yaml").write_text(yaml.safe_dump(manifest, sort_keys=False))
            _, doc = lint(root)
            for f in doc["findings"]:
                case = Path(f["file"]).parent.name
                if f["gate"] in ("1(b) schema", "1(d) scenario"):
                    cls.by_case.setdefault(case, []).append(f)
        cls.doc = doc

    def test_every_valid_case_is_clean(self):
        for case, *_ in VALID:
            with self.subTest(case=case):
                self.assertEqual(self.by_case.get(case, []), [])

    def test_every_invalid_case_reports_exactly_its_findings(self):
        for case, _, _, expected in INVALID:
            with self.subTest(case=case):
                got = self.by_case.get(case, [])
                self.assertEqual(len(got), len(expected), [f["message"] for f in got])
                for (gate, fragment), finding in zip(expected, sorted(got, key=lambda f: f["message"])):
                    self.assertTrue(finding["gate"].startswith(gate), finding)
                    self.assertIn(fragment, finding["message"])
                    self.assertEqual(finding["severity"], "Should-fix")
                    self.assertEqual(finding["id"], "ESS-G1d" if gate == "1(d)" else "ESS-G1b")

    def test_hints_name_the_forms(self):
        hint = self.by_case["then-empty"][0]["hint"]
        self.assertIn("write { nothing: true }", hint)
        self.assertIn("exactly one of event, command", self.by_case["step-two-keys"][0]["hint"])
        self.assertIn("lower-case", self.by_case["flow-capitalised"][0]["hint"])
        self.assertIn("manifest-guide.md §3", self.by_case["cmd-when-not-handled"][0]["hint"])

    def test_unquoted_date_gets_the_quoting_hint(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "orders" / "slice.yaml"
            manifest.parent.mkdir()
            manifest.write_text(yaml.safe_dump({
                "schemaVersion": "1.4", "slice": "orders.place_order", "kind": "command", "bc": "orders",
                "owner": "o", "status": "live", "summary": "s", "tests": {}, **BASES["command"]})
                + "scenarios:\n  - name: dated\n    when: { command: CancelOrder, with: { on: 2026-10-08 } }\n"
                  "    then: [{ event: OrderCancelled, with: { at: 2026-10-08 } }]\n")
            code, doc = lint(tmp)
        self.assertEqual(code, 1)
        self.assertEqual([f["gate"] for f in doc["findings"]], ["1(b) schema", "1(b) schema"])
        for f in doc["findings"]:
            self.assertIn("Quote a date", f["hint"])

    def test_finding_points_at_the_scenario_line_and_exits_1(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "orders" / "slice.yaml"
            manifest.parent.mkdir()
            manifest.write_text(
                'schemaVersion: "1.4"\nslice: orders.cancel_order\nkind: command\nbc: orders\n'
                "owner: orders-team\nstatus: live\nsummary: Cancel.\nhandles: [CancelOrder]\n"
                "publishes: [OrderCancelled]\nwrites: [Order]\nflow: checkout\n"
                "scenarios:\n"
                "  - name: cancels a placed order\n"
                "    when: { command: CancelOrder }\n"
                "    then: [{ event: OrderCancelled }]\n"
                "  - { name: \"rejects an unknown order\", when: { command: CancelOrdr }, then: { error: X } }\n"
                "tests:\n  unit: { present: true }\n")
            code, doc = lint(tmp)
            r = subprocess.run([sys.executable, str(SCRIPT), tmp], capture_output=True, text=True, timeout=60)
        self.assertEqual(code, 1)
        self.assertEqual([(f["gate"], f["line"]) for f in doc["findings"]], [("1(d) scenario", 16)])
        self.assertIn("scenarios/1 'rejects an unknown order'", doc["findings"][0]["message"])
        self.assertEqual(r.returncode, 1)
        self.assertIn("1(d) scenario", r.stdout)

    def test_rule_runs_without_jsonschema(self):
        # Manifest-only, no schema needed: a python3 hook without jsonschema still reports it (exit 1).
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / "slice.yaml"
            manifest.write_text(yaml.safe_dump({
                "schemaVersion": "1.4", "slice": "orders.cancel_order", "kind": "command", "bc": "orders",
                "owner": "o", "status": "live", "summary": "s", "tests": {}, **BASES["command"],
                "scenarios": [sc(when=step(C, "CancelOrdr"), then=NOTHING)]}))
            code, out, _ = lint_without_jsonschema(tmp, "--json")
        doc = json.loads(out)
        self.assertEqual((code, doc["schemaValidated"]), (1, False))
        self.assertEqual([f["gate"] for f in doc["findings"]], ["1(d) scenario"])

    def test_pre_1_4_schema_copy_rejects_the_new_fields(self):
        # manifest-guide.md §3 compatibility rule: a project copy older than 1.4 fails these manifests,
        # which is why a generator checks the copy for a `scenarios` property before writing either.
        with tempfile.TemporaryDirectory() as tmp:
            old = json.loads(SCHEMA.read_text())
            del old["properties"]["flow"], old["properties"]["scenarios"]
            # a copy that old predates the version gate too: strict at the root, no x-manifestVersion
            old.pop("x-manifestVersion")
            old["allOf"] = [c for c in old["allOf"] if (c.get("else") or {}).get("$ref") != "#/$defs/knownKeys"]
            old["additionalProperties"] = False
            old_path = Path(tmp) / "old.json"
            old_path.write_text(json.dumps(old))
            manifest = Path(tmp) / "m" / "slice.yaml"
            manifest.parent.mkdir()
            manifest.write_text(yaml.safe_dump({
                "schemaVersion": "1.4", "slice": "orders.cancel_order", "kind": "command", "bc": "orders",
                "owner": "o", "status": "live", "summary": "s", "tests": {}, **BASES["command"],
                "flow": "checkout"}))
            code, doc = lint(manifest.parent, "--schema", str(old_path))
        self.assertEqual(code, 1)
        self.assertIn("'flow' does not match any of the regexes", doc["findings"][0]["message"])
        self.assertIn("scenarios", json.loads(SCHEMA.read_text())["properties"])

    def test_manifest_guide_worked_examples_lint_clean(self):
        # manifest-guide.md §5 is what an author copies; it must pass the gate it documents.
        import re
        guide = (PLUGIN / "references" / "slice" / "manifest-guide.md").read_text()
        section = guide[guide.index("## 5. Worked examples"):guide.index("## 6. The companion")]
        blocks = re.findall(r"```yaml\n(.*?)```", section, re.S)
        self.assertEqual(len(blocks), 4)
        with tempfile.TemporaryDirectory() as tmp:
            for i, block in enumerate(blocks):
                (Path(tmp) / str(i)).mkdir()
                (Path(tmp) / str(i) / "slice.yaml").write_text(block)
            code, doc = lint(tmp)
        self.assertEqual((code, doc["parsed"], doc["findings"]), (0, 4, []))
        kinds = "".join(blocks)
        for form in ("error:", "nothing: true", "readModel:", "external:", "flow:"):
            self.assertIn(form, kinds)


def manifest(tmp, stamp, **extra):
    path = Path(tmp) / "m" / "slice.yaml"
    path.parent.mkdir(exist_ok=True)
    path.write_text(yaml.safe_dump({
        "schemaVersion": stamp, "slice": "orders.cancel_order", "kind": "command", "bc": "orders",
        "owner": "o", "status": "live", "summary": "s", "tests": {}, **BASES["command"], **extra}))
    return path.parent


class UnknownKeys(unittest.TestCase):
    """The version gate: strict at or below the copy's x-manifestVersion, tolerant above it, and an
    x- extension key valid on every object at any stamp (manifest-guide.md §3)."""

    EVERYWHERE = {
        "x-team": "payments",
        "endpoints": [{"method": "POST", "path": "/x", "x-rate": 3}],
        "projections": [{"name": "P", "from": ["A"], "x-why": "y"}],
        "schedule": {"cron": "0 * * * *", "x-owner": "ops"},
        "provides": [{"name": "Seam", "x-since": "2026"}],
        "scenarios": [sc(when={"command": "CancelOrder", "x-n": 1}, then={"nothing": True, "x-why": "idem"},
                         **{"x-ticket": "T-1"})],
    }

    def test_extension_keys_pass_everywhere_at_the_current_stamp(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, doc = lint(manifest(tmp, "1.4", **self.EVERYWHERE))
        self.assertEqual((code, doc["findings"], doc["notes"]), (0, [], []))

    def test_unknown_key_at_or_below_the_copy_is_a_finding(self):
        for stamp in ("1.4", "1.0", "2.0", "1.4.1"):
            with self.subTest(stamp=stamp), tempfile.TemporaryDirectory() as tmp:
                code, doc = lint(manifest(tmp, stamp, futureField=1,
                                          endpoints=[{"method": "POST", "path": "/x", "rateLimit": 3}]))
                self.assertEqual(code, 1)
                self.assertEqual(sorted(f["message"] for f in doc["findings"]),
                                 ["(root): unknown key 'futureField'", "endpoints/0: unknown key 'rateLimit'"])

    def test_unknown_key_on_a_newer_stamp_is_a_note(self):
        for stamp in ("1.5", "1.12"):
            with self.subTest(stamp=stamp), tempfile.TemporaryDirectory() as tmp:
                code, doc = lint(manifest(tmp, stamp, futureField=1,
                                          scenarios=[sc(when=step(C, "CancelOrder"), then={"nothing": True, "why": "x"})]))
                self.assertEqual((code, doc["findings"]), (0, []))
                self.assertEqual(len(doc["notes"]), 2)
                self.assertTrue(all(f"stamped {stamp}" in n for n in doc["notes"]))
                self.assertTrue(any("(root): unknown key 'futureField' — not defined by schema 1.4" in n
                                    for n in doc["notes"]))
                self.assertTrue(any("scenarios/0/then: unknown key 'why'" in n for n in doc["notes"]))

    def test_projection_near_synonyms_are_findings_at_every_stamp(self):
        # the projection's $comment: these keys make a view look maintained while it reacts to nothing,
        # so a stamp ahead of the copy must not turn them into notes
        for stamp in ("1.4", "1.5", "1.12"):
            for key in ("subscribesTo", "handles", "table", "kind"):
                with self.subTest(stamp=stamp, key=key), tempfile.TemporaryDirectory() as tmp:
                    code, doc = lint(manifest(tmp, stamp, projections=[{"name": "P", "from": ["A"],
                                                                         key: ["Orders"]}]))
                    self.assertEqual(code, 1)
                    self.assertEqual([f["message"].split(" — ")[0] for f in doc["findings"]],
                                     [f"projections/0: unknown key '{key}'"])
                    self.assertEqual(doc["findings"][0]["gate"], "1(b) schema")
                    self.assertEqual(doc["notes"], [])
                    hint = doc["findings"][0]["hint"]
                    self.assertIn("`aggregateTypes`" if key == "subscribesTo" else "instead of `from`", hint)

    def test_other_unknown_projection_key_on_a_newer_stamp_is_still_a_note(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, doc = lint(manifest(tmp, "1.5", projections=[{"name": "P", "from": ["A"], "retention": "P30D"}]))
        self.assertEqual((code, doc["findings"]), (0, []))
        self.assertEqual(len(doc["notes"]), 1)
        self.assertIn("projections/0: unknown key 'retention' — not defined by schema 1.4", doc["notes"][0])

    def test_copy_without_the_near_synonym_clause_keeps_them_as_notes(self):
        # an older project copy: the gate has no `then`, so a newer stamp tolerates every unknown key
        with tempfile.TemporaryDirectory() as tmp:
            copy = json.loads(SCHEMA.read_text())
            gate = [c for c in copy["allOf"] if (c.get("else") or {}).get("$ref") == "#/$defs/knownKeys"][0]
            gate.pop("then", None)
            copy_path = Path(tmp) / "copy.json"
            copy_path.write_text(json.dumps(copy))
            code, doc = lint(manifest(tmp, "1.5", projections=[{"name": "P", "from": ["A"], "subscribesTo": ["O"]}]),
                             "--schema", str(copy_path))
        self.assertEqual((code, doc["findings"]), (0, []))
        self.assertIn("projections/0: unknown key 'subscribesTo'", doc["notes"][0])

    def test_note_names_the_copy_when_it_carries_no_version(self):
        # a hand-edited copy that kept the gate but lost x-manifestVersion
        with tempfile.TemporaryDirectory() as tmp:
            copy = json.loads(SCHEMA.read_text())
            copy.pop("x-manifestVersion")
            copy_path = Path(tmp) / "copy.json"
            copy_path.write_text(json.dumps(copy))
            code, doc = lint(manifest(tmp, "1.5", futureField=1), "--schema", str(copy_path))
        self.assertEqual(code, 0)
        self.assertEqual(len(doc["notes"]), 1)
        self.assertIn("not defined by the project's schema copy; the manifest is stamped 1.5", doc["notes"][0])

    def test_newer_stamp_still_checks_every_known_key(self):
        # tolerance covers keys the copy does not know, never a known key with a wrong value
        with tempfile.TemporaryDirectory() as tmp:
            code, doc = lint(manifest(tmp, "1.5", flow="Checkout", futureField=1))
        self.assertEqual(code, 1)
        self.assertEqual([f["gate"] for f in doc["findings"]], ["1(b) schema"])

    def test_then_form_reports_the_key_outside_its_form_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            code, doc = lint(manifest(tmp, "1.4", scenarios=[
                sc(when=step(C, "CancelOrder"), then={"error": "X", "rows": []})]))
        self.assertEqual([f["message"] for f in doc["findings"]], ["scenarios/0/then: unknown key 'rows'"])

    def test_known_keys_mirror_the_properties_they_guard(self):
        # knownKeys restates each object's key list for the strict branch; a field added to one place only
        # is either rejected at the current stamp or never checked
        s = json.loads(SCHEMA.read_text())
        d, kk = s["$defs"], s["$defs"]["knownKeys"]
        enum = lambda frag: set(frag["propertyNames"]["anyOf"][1]["enum"])  # noqa: E731
        sc_kk = kk["properties"]["scenarios"]["items"]
        pairs = {
            "root": (enum(kk), s["properties"]),
            "endpoint": (enum(kk["properties"]["endpoints"]["items"]), d["endpoint"]["properties"]),
            "providedSeam": (enum(kk["properties"]["provides"]["items"]), d["providedSeam"]["properties"]),
            "schedule": (enum(kk["properties"]["schedule"]), d["schedule"]["properties"]),
            "projection": (enum(kk["properties"]["projections"]["items"]), d["projection"]["properties"]),
            "scenario": (enum(sc_kk), d["scenario"]["properties"]),
            "scenarioStep": (enum(sc_kk["properties"]["when"]), d["scenarioStep"]["properties"]),
        }
        for form, guard in zip(d["scenario"]["properties"]["then"]["oneOf"][1:],
                               sc_kk["properties"]["then"]["else"]["anyOf"]):
            pairs["then " + form["required"][0]] = (enum(guard), form["properties"])
        for name, (listed, props) in pairs.items():
            with self.subTest(object=name):
                self.assertEqual(listed, set(props))

    def test_gate_pattern_matches_x_manifest_version(self):
        s = json.loads(SCHEMA.read_text())
        import re
        major, minor = map(int, s["x-manifestVersion"].split("."))
        newer = re.compile([c for c in s["allOf"] if (c.get("else") or {}).get("$ref") == "#/$defs/knownKeys"][0]
                           ["if"]["properties"]["schemaVersion"]["pattern"])
        self.assertEqual([bool(newer.match(f"{major}.{m}")) for m in range(minor + 12)],
                         [m > minor for m in range(minor + 12)])


if __name__ == "__main__":
    unittest.main()
