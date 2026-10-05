#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3"]
# ///
"""Golden and behaviour tests for scripts/slice-index.py.

    uv run --script tests/scripts/test_slice_index.py                  # run
    uv run --script tests/scripts/test_slice_index.py --update-golden  # rewrite the goldens, then review the diff

Goldens: `tests/slice-index/golden/` (terminal graph text, graph JSON with ranks, locate queries) and
the three generated slice-map data files `tests/fixtures/slice-map/sample-data-{cycle,twin,scale}.json`,
which render-check.py then renders in Chrome — so the map this script emits is the map the page check
proves. Ranks are also cross-checked against `tests/fixtures/slice-map/expected.json`, the values the
page was seen to produce.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parent.parent.parent
SCRIPT = PLUGIN / "scripts" / "slice-index.py"
FIXTURES = PLUGIN / "tests" / "slice-index"
GOLDEN = FIXTURES / "golden"
SLICE_MAP = PLUGIN / "tests" / "fixtures" / "slice-map"
UPDATE = "--update-golden" in sys.argv
if UPDATE:
    sys.argv.remove("--update-golden")

try:
    import yaml  # noqa: F401
except ImportError:  # pragma: no cover
    raise unittest.SkipTest("pyyaml missing — run: uv run --script tests/scripts/test_slice_index.py")


def run(*args, cwd=PLUGIN, stdin=None):
    return subprocess.run([sys.executable, str(SCRIPT), *map(str, args)], cwd=cwd, input=stdin,
                          capture_output=True, text=True, timeout=60)


def gen_scale(out, bcs=12):
    r = subprocess.run([sys.executable, str(FIXTURES / "gen-scale.py"), str(out), "--bcs", str(bcs)],
                       capture_output=True, text=True, timeout=60)
    assert r.returncode == 0, r.stderr


MAP_ARGS = {
    "cycle": ["tests/slice-index/cycle", "--sha", "fixture", "--project", "cycle",
              "--root-label", "tests/slice-index/cycle"],
    "twin": ["tests/slice-index/twin", "--sha", "fixture", "--project", "twin",
             "--root-label", "tests/slice-index/twin"],
}
SCALE_ARGS = ["--sha", "fixture", "--project", "scale", "--root-label", "gen-scale.py --bcs 12"]


def golden(test, path: Path, actual: str):
    if UPDATE:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(actual, encoding="utf-8")
        return
    test.assertTrue(path.is_file(), f"{path} missing — run with --update-golden")
    test.assertEqual(path.read_text(encoding="utf-8"), actual,
                     f"{path.relative_to(PLUGIN)} differs — if intended, --update-golden and review the diff")


def write(root: Path, rel: str, text: str):
    p = root / rel / "slice.yaml"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")


def manifest(sid, kind, extra=""):
    bc = sid.split(".")[0]
    return (f'schemaVersion: "1.3"\nslice: {sid}\nkind: {kind}\nbc: {bc}\nowner: t\nstatus: live\n'
            f"summary: s\ntests:\n  unit: {{ present: true }}\n{extra}")


class Goldens(unittest.TestCase):
    def test_map_is_the_committed_sample_data(self):
        for name, args in MAP_ARGS.items():
            with self.subTest(name):
                r = run("map", *args)
                self.assertEqual(r.returncode, 0, r.stderr)
                golden(self, SLICE_MAP / f"sample-data-{name}.json", r.stdout)

    def test_graph_text_and_json(self):
        for name in MAP_ARGS:
            with self.subTest(name):
                r = run("graph", f"tests/slice-index/{name}")
                self.assertEqual(r.returncode, 0, r.stderr)
                golden(self, GOLDEN / f"{name}.graph.txt", r.stdout)
                r = run("graph", f"tests/slice-index/{name}", "--json")
                self.assertEqual(r.returncode, 0, r.stderr)
                golden(self, GOLDEN / f"{name}.graph.json", r.stdout)

    def test_graph_text_of_the_real_fixtures(self):
        """The terminal --view graph over the fixtures that carry real manifests — worked-example is
        the realistic chain (fan-out, an outbound translation, a saga loop back into the command)."""
        for name in ("worked-example", "aggregate-lane", "service-entity"):
            with self.subTest(name):
                r = run("graph", f"tests/fixtures/{name}")
                self.assertEqual(r.returncode, 0, r.stderr)
                golden(self, GOLDEN / f"{name}.graph.txt", r.stdout)

    def test_scale(self):
        with tempfile.TemporaryDirectory() as tmp:
            gen_scale(Path(tmp) / "scale")
            r = run("map", Path(tmp) / "scale", *SCALE_ARGS)
            self.assertEqual(r.returncode, 0, r.stderr)
            golden(self, SLICE_MAP / "sample-data-scale.json", r.stdout)
            data = json.loads(r.stdout)
            self.assertEqual(data["meta"]["sliceCount"], 53)
            r = run("graph", Path(tmp) / "scale")
            self.assertEqual(r.returncode, 0, r.stderr)
            golden(self, GOLDEN / "scale.graph.txt", r.stdout)

    def test_scale_runtime(self):
        """A 60-context estate (265 manifests, ~520 nodes) maps and chains in well under two seconds."""
        with tempfile.TemporaryDirectory() as tmp:
            gen_scale(Path(tmp) / "big", bcs=60)
            for cmd in ("map", "graph"):
                start = time.monotonic()
                r = run(cmd, Path(tmp) / "big")
                took = time.monotonic() - start
                self.assertEqual(r.returncode, 0, r.stderr)
                self.assertLess(took, 2.0, f"{cmd} took {took:.2f}s")

    def test_queries(self):
        spec = json.loads((GOLDEN / "queries.json").read_text(encoding="utf-8")) if not UPDATE or \
            (GOLDEN / "queries.json").is_file() else {"queries": []}
        out = []
        for q in spec["queries"]:
            args = ["query", q["root"], q["what"], q["name"], "--json"]
            if q.get("method"):
                args += ["--method", q["method"]]
            r = run(*args)
            self.assertIn(r.returncode, (0, 1), r.stderr)
            got = json.loads(r.stdout)["hits"]
            if UPDATE:
                out.append({**{k: v for k, v in q.items() if k != "hits"}, "hits": got})
            else:
                with self.subTest(f"{q['what']} {q['name']} @ {q['root']}"):
                    self.assertEqual(got, q["hits"])
        if UPDATE:
            spec["queries"] = out
            (GOLDEN / "queries.json").write_text(json.dumps(spec, indent=2, ensure_ascii=False) + "\n",
                                                 encoding="utf-8")

    def test_ranks_match_what_the_page_drew(self):
        """expected.json holds the columns Chrome produced; the script's rank is the same algorithm."""
        expected = json.loads((SLICE_MAP / "expected.json").read_text(encoding="utf-8"))["expect"]
        for name in MAP_ARGS:
            want = expected[f"sample-data-{name}.json"].get("ranks", {})
            got = {n["id"]: n["rank"] for n in json.loads(run("graph", f"tests/slice-index/{name}", "--json").stdout)["nodes"]}
            for node, rank in want.items():
                self.assertEqual(got.get(node), rank, f"{name}: {node}")


class Algebra(unittest.TestCase):
    """The rules slice-map.md Step 3 and Step 4 state, one at a time."""

    @classmethod
    def setUpClass(cls):
        cls.twin = json.loads(run("map", *MAP_ARGS["twin"]).stdout)
        cls.cycle_graph = json.loads(run("graph", "tests/slice-index/cycle", "--json").stdout)

    def slice(self, sid):
        return next(s for s in self.twin["slices"] if s["id"] == sid)

    def test_inbound_is_consumes_union_projections_from(self):
        flow = next(f for f in self.twin["flows"] if f["event"] == "OrderPlaced")
        self.assertIn("orders.order_list_v2", flow["to"])
        self.assertEqual(len(flow["to"]), 7)

    def test_several_command_slices_on_one_aggregate_in_one_bc_is_not_flagged(self):
        order = next(w for w in self.twin["writers"] if w["target"] == "Order")
        self.assertEqual(order["flag"], "crossBc")  # only because billing writes it too
        self.assertEqual(order["bcs"], ["billing", "orders"])

    def test_twin_suppresses_shared_read_model_and_plain_pair_does_not(self):
        flags = {w["target"]: w["flag"] for w in self.twin["writers"]}
        self.assertIsNone(flags["order_list_view"])
        self.assertIsNone(flags["customer_summary_view"])
        self.assertEqual(flags["order_stats_view"], "sharedReadModel")

    def test_twin_pairing_flags(self):
        texts = lambda sid: " ".join(f["text"] for f in self.slice(sid)["flags"])  # noqa: E731
        self.assertIn("retirement outstanding", texts("orders.order_list"))
        self.assertIn("status is live", texts("orders.customer_summary"))
        self.assertIn("not a slice in scope", texts("orders.shipment_board_v2"))
        self.assertEqual(self.slice("orders.order_list_v2")["flags"], [])

    def test_cross_reads(self):
        rows = {(c["from"], c["model"]): c for c in self.twin["crossReads"]}
        self.assertTrue(rows[("billing.refund_order", "order_list_view")]["declared"])
        self.assertFalse(rows[("billing.settle_refund", "customer_summary_view")]["declared"])
        self.assertTrue(self.slice("billing.refund_order")["reads"][0]["external"])

    def test_dangling_is_consumed_but_never_published(self):
        flows = {f["event"]: f for f in self.twin["flows"]}
        self.assertTrue(flows["PaymentSettled"]["dangling"])
        self.assertFalse(flows["OrderRefunded"]["dangling"])  # published, no consumer: ordinary

    def test_cycles_terminate_and_are_marked(self):
        self.assertEqual(len(self.cycle_graph["cycles"]), 2)
        self.assertEqual(self.cycle_graph["entries"], ["PlaceOrder"])
        text = (GOLDEN / "cycle.graph.txt").read_text(encoding="utf-8")
        self.assertEqual(text.count("↺ cycle"), 2)
        self.assertIn("SendReminder  (command)\n", text)  # the loop with no entry point still prints


class Behaviour(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def test_no_manifests_exits_3(self):
        r = run("map", self.root)
        self.assertEqual(r.returncode, 3)
        self.assertIn("slice-discover", r.stderr)

    def test_bad_root_exits_2(self):
        self.assertEqual(run("map", self.root / "nope").returncode, 2)

    def broken(self):
        write(self.root, "orders/use_cases/place_order", manifest("orders.place_order", "command",
              "handles: [PlaceOrder]\npublishes: [OrderPlaced]\n"))
        write(self.root, "orders/use_cases/cancel_order", manifest("orders.cancel_order", "command",
              "endpoints:\n  - { method: POST, path: /api/orders/{id}/cancel, auth: user }\n"))

    def test_unparseable_manifest_is_a_row_not_an_omission(self):
        self.broken()
        r = run("map", self.root, "--sha", "x")
        self.assertEqual(r.returncode, 1)
        data = json.loads(r.stdout)
        row = next(s for s in data["slices"] if s["path"] == "orders/use_cases/cancel_order")
        self.assertTrue(row["error"])
        self.assertEqual(row["flags"][0]["level"], "error")
        self.assertTrue(any("NOT MAPPED" in n for n in data["notes"]))

    def test_html_refuses_an_incomplete_map(self):
        self.broken()
        out = self.root / "page.html"
        r = run("html", self.root, "--out", out)
        self.assertEqual(r.returncode, 1)
        self.assertFalse(out.exists())

    def test_repair_braced_paths_is_announced(self):
        self.broken()
        r = run("map", self.root, "--sha", "x", "--repair-braced-paths")
        self.assertEqual(r.returncode, 0, r.stderr)
        data = json.loads(r.stdout)
        row = next(s for s in data["slices"] if s["id"] == "orders.cancel_order")
        self.assertEqual(row["endpoints"][0]["path"], "/api/orders/{id}/cancel")
        self.assertIn("in-memory corrected copy", row["flags"][0]["text"])
        self.assertTrue(any("in-memory corrected copy" in n for n in data["notes"]))

    def test_html_substitutes_the_placeholder_once(self):
        write(self.root, "orders/views/list", manifest("orders.list", "view").replace(
            "summary: s", 'summary: "</script><b>x"'))
        out = self.root / "page.html"
        r = run("html", self.root, "--out", out, "--sha", "x")
        self.assertEqual(r.returncode, 0, r.stderr)
        page = out.read_text(encoding="utf-8")
        self.assertNotIn("/* __SLICE_MAP_DATA__ */ null", page)
        self.assertEqual(page.count("const SLICE_MAP = {"), 1)
        self.assertIn("<\\/script><b>x", page)  # a summary cannot close the page's <script>

    def test_divergence_flags(self):
        write(self.root, "a/use_cases/x", manifest("a.x", "view"))                 # view outside views/
        write(self.root, "a/views/y", manifest("a.dup", "view"))
        write(self.root, "a/views/z", manifest("a.dup", "view", "consumes: [Nobody]\n"))
        data = json.loads(run("map", self.root, "--sha", "x").stdout)
        by_path = {s["path"]: " ".join(f["text"] for f in s["flags"]) for s in data["slices"]}
        self.assertIn("outside views/", by_path["a/use_cases/x"])
        self.assertIn("also declared by", by_path["a/views/y"])
        self.assertIn("also declared by", by_path["a/views/z"])
        self.assertIn("dangling", by_path["a/views/z"])

    def test_bc_filter(self):
        data = json.loads(run("map", *MAP_ARGS["twin"], "--bc", "billing").stdout)
        self.assertEqual({s["bc"] for s in data["slices"]}, {"billing"})
        self.assertEqual(data["meta"]["scope"], "bc:billing")
        self.assertEqual([w["target"] for w in data["writers"]], ["Order"])
        self.assertEqual(run("map", *MAP_ARGS["twin"], "--bc", "nope").returncode, 2)

    def test_source_facts_merge(self):
        write(self.root, "orders/use_cases/place_order", manifest("orders.place_order", "command",
              "handles: [{ name: PlaceOrder, idempotencyKey: orderId }]\n"
              "publishes: [{ name: OrderPlaced, version: 2 }]\n"))
        write(self.root, "orders/views/order_list", manifest("orders.order_list", "view",
              "projections:\n  - { name: P, from: [OrderPlaced] }\n"))
        facts = {  # the shape of slice-source.py --json, reduced to what the map reads
            "tool": "slice-source", "mode": "facts",
            "bcs": [{"name": "orders", "lane": {"detected": "decider"}}],
            "slices": [
                {"id": "orders.place_order", "dir": "orders/use_cases/place_order",
                 "package": "com.example.orders.use_cases.place_order", "files": ["PlaceOrder.kt"]},
                {"id": "orders.order_list", "dir": "elsewhere", "package": "p", "files": [],
                 "readModels": [{"name": "order_list", "store": None, "declaredIn": "x.kt", "line": 3,
                                 "columns": [{"name": "id", "type": "OrderId"}]}]},
            ],
            "messages": [
                {"name": "OrderPlaced", "type": "event", "found": True, "identity": "orderId: OrderId",
                 "fields": ["orderId: OrderId"], "declaredIn": "orders/events/OrderPlaced.kt", "line": 4},
                {"name": "PlaceOrder", "type": "command", "found": False, "identity": None, "fields": [],
                 "declaredIn": None},
                {"name": "Unrelated", "type": "event", "found": True, "identity": "x: X"},
            ],
        }
        check = {  # slice-source.py --check --json
            "tool": "slice-source", "mode": "check",
            "findings": [
                {"gate": "11(b) handled events", "slice": "orders.order_list", "file": "a.kt", "line": 3,
                 "message": "handles OrderCancelled, not in projections[].from"},
                {"gate": "14 two write styles", "slice": "orders.order_list", "message": "an audit gate"},
            ],
            "unverified": [
                {"gate": "6 endpoint route", "slice": "orders.order_list", "reason": "WebFlux RouterFunction"},
                {"gate": "14 stale lane", "slice": "orders.order_list", "reason": "an audit gate"},
            ],
        }
        (self.root / "check.json").write_text(json.dumps(check), encoding="utf-8")
        r = run("map", self.root, "--sha", "x", "--source-facts", "-", "--source-check", self.root / "check.json",
                stdin=json.dumps(facts))
        self.assertEqual(r.returncode, 0, r.stderr)
        data = json.loads(r.stdout)
        self.assertEqual(data["contexts"][0]["lane"], "decider")  # no manifest declares one
        place = next(s for s in data["slices"] if s["id"] == "orders.place_order")
        self.assertEqual(place["package"], "com.example.orders.use_cases.place_order")
        self.assertNotIn("readModels", place)
        view = next(s for s in data["slices"] if s["id"] == "orders.order_list")
        self.assertEqual(view["readModels"], [{"name": "order_list", "store": None,
                                               "columns": [{"name": "id", "type": "OrderId"}]}])
        self.assertEqual([f["text"] for f in view["flags"]],
                         ["11(b) handled events: handles OrderCancelled, not in projections[].from (a.kt:3)",
                          "not checked — 6 endpoint route: WebFlux RouterFunction"])
        msgs = {(m["name"], m["type"]): m for m in data["messages"]}
        self.assertEqual(msgs[("OrderPlaced", "event")]["identity"], "orderId: OrderId")
        self.assertEqual(msgs[("OrderPlaced", "event")]["version"], 2)  # the manifest wins on version
        self.assertEqual(msgs[("PlaceOrder", "command")]["idempotencyKey"], "orderId")
        self.assertIn("No declaration in scope", msgs[("PlaceOrder", "command")]["summary"])
        self.assertNotIn(("Unrelated", "event"), msgs)
        self.assertFalse(any("no --source-facts" in n for n in data["notes"]))

    def test_real_slice_source_output_merges(self):
        source = PLUGIN / "scripts" / "slice-source.py"
        if not source.is_file():
            self.skipTest("scripts/slice-source.py not present")
        root = "tests/fixtures/service-entity"
        docs = []
        for extra in ([], ["--check"]):
            r = subprocess.run([sys.executable, str(source), root, "--json", *extra], cwd=PLUGIN,
                               capture_output=True, text=True, timeout=120)
            self.assertIn(r.returncode, (0, 1, 3), r.stderr)
            self.assertTrue(r.stdout.lstrip().startswith("{"),
                            f"slice-source {' '.join(extra)} printed no JSON (exit {r.returncode}): {r.stderr[-400:]}")
            path = self.root / f"doc{len(docs)}.json"
            path.write_text(r.stdout, encoding="utf-8")
            docs += ["--source-check" if extra else "--source-facts", path]
        r = run("map", root, "--sha", "x", *docs)
        self.assertEqual(r.returncode, 0, r.stderr)
        data = json.loads(r.stdout)
        self.assertTrue(all(s["package"] and s["files"] for s in data["slices"]))
        self.assertTrue(all(s["readModels"] for s in data["slices"] if s["kind"] == "view"))
        self.assertTrue(all(m["declaredIn"] for m in data["messages"]))

    def test_without_source_facts_the_gap_is_stated(self):
        write(self.root, "a/views/v", manifest("a.v", "view"))
        data = json.loads(run("map", self.root, "--sha", "x").stdout)
        self.assertTrue(any("no --source-facts" in n for n in data["notes"]))
        self.assertIsNone(data["slices"][0]["package"])
        self.assertEqual(data["slices"][0]["readModels"], [])

    def test_endpoint_query_prefers_exact_then_template_then_prefix(self):
        q = lambda *a: json.loads(run("query", "tests/slice-index/twin", "who-owns-endpoint", *a, "--json").stdout)["hits"]  # noqa: E731
        self.assertEqual([h["slice"] for h in q("/api/orders")], ["orders.order_list", "orders.place_order"])
        self.assertEqual([h["slice"] for h in q("/api/orders", "--method", "GET")], ["orders.order_list"])
        hit = q("/api/orders/42/cancel")
        self.assertEqual((hit[0]["slice"], hit[0]["match"]), ("orders.cancel_order", "template"))
        self.assertEqual({h["match"] for h in q("/api/v2")}, {"prefix"})

    def test_query_exit_code(self):
        self.assertEqual(run("query", "tests/slice-index/twin", "who-handles", "PlaceOrder").returncode, 0)
        self.assertEqual(run("query", "tests/slice-index/twin", "who-handles", "Nothing").returncode, 1)


if __name__ == "__main__":
    os.chdir(PLUGIN)
    unittest.main(verbosity=1)
