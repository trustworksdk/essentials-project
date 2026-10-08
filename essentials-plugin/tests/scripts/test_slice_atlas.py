#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3"]
# ///
"""Golden and behaviour tests for the Slice Atlas data: scripts/slice_atlas.py and the `atlas`
subcommand (and the `map` additions) of scripts/slice-index.py.

    uv run --script tests/scripts/test_slice_atlas.py                  # run
    uv run --script tests/scripts/test_slice_atlas.py --update-golden  # rewrite the goldens, then review the diff

Input: `tests/fixtures/atlas/` — three contexts (orders, billing, shipping); the journey `checkout` crossing
all three and `returns` inside billing, with `billing.payment_list` in both; slices that declare no flow
(a derived process per entry slice, an unattached view, a scheduled automation whose command belongs to
`checkout`); scenarios ending in events, an error, `nothing` and a read model; one `test:` that resolves
by method name, one by @DisplayName and one that does not; a column no inbound event carries
(`OrderListView.discount`); a `with` key that is no payload field; a `given` event nothing publishes; and
`orders/use_cases/amend_order`, whose manifest does not parse (an unquoted braced path).

Goldens: `tests/slice-index/golden/atlas.map.json` (`map` with slice-source facts — so it also pins the
seam with slice-source's `tests` index) and `atlas.diff.json` (the diff of a scripted change, built from a
throwaway git repository by --before-ref). The page template here is a minimal one written per test:
the real `references/slice/atlas/` page is checked by `tests/fixtures/slice-map/render-check.py`.
"""

from __future__ import annotations

import importlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PLUGIN = Path(__file__).resolve().parent.parent.parent
SCRIPTS = PLUGIN / "scripts"
INDEX = SCRIPTS / "slice-index.py"
SOURCE = SCRIPTS / "slice-source.py"
FIXTURE = PLUGIN / "tests" / "fixtures" / "atlas"
GOLDEN = PLUGIN / "tests" / "slice-index" / "golden"
UPDATE = "--update-golden" in sys.argv
if UPDATE:
    sys.argv.remove("--update-golden")

sys.path.insert(0, str(SCRIPTS))
slice_atlas = importlib.import_module("slice_atlas")

try:
    import yaml  # noqa: F401
except ImportError:  # pragma: no cover
    raise unittest.SkipTest("pyyaml missing — run: uv run --script tests/scripts/test_slice_atlas.py")

TEMPLATE = """<!doctype html><title>atlas</title>
<script>
const ATLAS = /* __ATLAS_DATA__ */ null;
</script>
<script>
/* __ATLAS_LENSES__ */
</script>
"""
MAP_ARGS = ["--sha", "fixture", "--project", "atlas", "--root-label", "tests/fixtures/atlas"]
# A fixed identity and clock, so a commit in a throwaway repository has the same sha on every run.
GIT_ENV = {**os.environ, "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@example.com", "GIT_COMMITTER_NAME": "t",
           "GIT_COMMITTER_EMAIL": "t@example.com", "GIT_AUTHOR_DATE": "2026-01-01T00:00:00Z",
           "GIT_COMMITTER_DATE": "2026-01-01T00:00:00Z", "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1"}


def run(*args, cwd=PLUGIN):
    return subprocess.run([sys.executable, str(INDEX), *map(str, args)], cwd=cwd, capture_output=True, text=True,
                          timeout=120)


def source_facts(root: Path, out: Path):
    """slice-source --json and --check --json for root, as the two map options that take them."""
    args = []
    for extra, opt in (([], "--source-facts"), (["--check"], "--source-check")):
        r = subprocess.run([sys.executable, str(SOURCE), str(root), "--json", *extra], cwd=PLUGIN,
                           capture_output=True, text=True, timeout=120)
        assert r.returncode in (0, 1, 3) and r.stdout.lstrip().startswith("{"), r.stderr
        path = out / f"{opt[2:]}.json"
        path.write_text(r.stdout, encoding="utf-8")
        args += [opt, path]
    return args


def golden(test, path: Path, actual: str):
    if UPDATE:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(actual, encoding="utf-8")
        return
    test.assertTrue(path.is_file(), f"{path} missing — run with --update-golden")
    test.assertEqual(path.read_text(encoding="utf-8"), actual,
                     f"{path.relative_to(PLUGIN)} differs — if intended, --update-golden and review the diff")


def page_data(page: str):
    m = re.search(r"^const ATLAS = (.*);$", page, re.M)
    assert m, "no data line in the page"
    return json.loads(m.group(1))


def git(repo: Path, *args):
    r = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True, env=GIT_ENV, timeout=60)
    assert r.returncode == 0, r.stderr
    return r.stdout


class Fixture(unittest.TestCase):
    """The fixture's map, through the real slice-source output."""

    @classmethod
    def setUpClass(cls):
        cls._tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls._tmp.name)
        cls.facts = source_facts(FIXTURE, tmp)
        r = run("map", "tests/fixtures/atlas", *MAP_ARGS, *cls.facts)
        cls.map_out, cls.map_code = r.stdout, r.returncode
        cls.data = json.loads(r.stdout)
        cls.issues = {i["id"]: i for i in cls.data["issues"]}

    @classmethod
    def tearDownClass(cls):
        cls._tmp.cleanup()

    def slice(self, sid):
        return next(s for s in self.data["slices"] if s["id"] == sid)

    def test_golden(self):
        self.assertEqual(self.map_code, 1)  # amend_order does not parse
        golden(self, GOLDEN / "atlas.map.json", self.map_out)

    def test_processes(self):
        procs = {p["id"]: p for p in self.data["processes"]}
        self.assertEqual(list(procs), ["checkout", "returns", "~orders.cancel_order", "~shipping.carrier_webhook",
                                       "~shipping.nightly_reship", "~unattached:orders"])
        checkout = procs["checkout"]
        self.assertTrue(checkout["declared"])
        self.assertEqual(checkout["bcs"], ["billing", "orders", "shipping"])
        self.assertEqual(checkout["sliceIds"][0], "orders.place_order")
        self.assertEqual(checkout["sliceIds"][-1], "shipping.ship_order")
        self.assertEqual(checkout["entries"], ["orders.place_order"])
        self.assertIn("billing.payment_list", procs["returns"]["sliceIds"])  # a slice serving two journeys
        derived = procs["~orders.cancel_order"]
        self.assertEqual((derived["name"], derived["declared"]), ("cancel order", False))
        self.assertEqual(derived["sliceIds"], ["orders.cancel_order", "orders.cancellation_mailer"])
        # the derivation stops at a slice that declares a flow: that is a link, not a member
        self.assertEqual(derived["links"], [{"to": "checkout", "via": ["OrderCancelled"]}])
        self.assertEqual(procs["~shipping.nightly_reship"]["links"], [{"to": "checkout", "via": ["ShipOrder"]}])
        # the broken manifest sits in its context's unattached process
        self.assertEqual(procs["~unattached:orders"]["sliceIds"],
                         ["orders.order_history", "src/main/java/com/example/orders/use_cases/amend_order"])
        # every slice, broken ones included, is in at least one process
        self.assertEqual({s["id"] for s in self.data["slices"]},
                         {i for p in procs.values() for i in p["sliceIds"]})
        for p in procs.values():
            self.assertIn(p["color"], range(12))
            self.assertEqual(p["color"], slice_atlas.colour(p["id"]))

    def test_issue_ids_levels_and_order(self):
        ids = list(self.issues)
        self.assertEqual(ids, [i["id"] for i in sorted(self.data["issues"], key=lambda i: (
            slice_atlas.LEVEL_RANK[i["level"]], i["id"]))])
        for i in self.data["issues"]:
            t = i["target"]
            self.assertEqual(i["id"], i["code"] + ":" + t["id"] + (":" + t["part"] if t["part"] else ""))
        want = {
            "map-parse-error:src/main/java/com/example/orders/use_cases/amend_order": "error",
            "completeness-unsourced-column:orders.order_list:readModel:OrderListView.discount": "info",
            "scenario-test-missing:orders.cancel_order:scenario:cancelling twice is a no-op": "warn",
            "scenario-given-unpublished:billing.refund_payment:scenario:refunds a returned order:OrderReturned": "warn",
            "scenario-unknown-field:orders.cancel_order:scenario:rejects an unknown order:CancelOrder.priority": "info",
            "map-dangling-consume:shipping.carrier_webhook:event:DeliveryNotice": "warn",
            "map-not-checked:orders.cancellation_mailer:11(b) handled events": "info",
        }
        for iid, level in want.items():
            with self.subTest(iid):
                self.assertEqual(self.issues[iid]["level"], level)
        self.assertEqual(self.issues["map-not-checked:orders.cancellation_mailer:11(b) handled events"]["source"],
                         "source")
        self.assertEqual(self.issues["map-dangling-consume:shipping.carrier_webhook:event:DeliveryNotice"]["source"],
                         "map")
        # only one unsourced column: `version` is technical, `reason` comes from OrderCancelled
        self.assertEqual([i for i in self.issues if i.startswith("completeness-")],
                         ["completeness-unsourced-column:orders.order_list:readModel:OrderListView.discount"])

    def test_aggregation(self):
        """orders: 3 of 5 slices declare no flow — one context issue; shipping: 2 of 4 — per slice."""
        agg = self.issues["flow-undeclared:orders"]
        self.assertEqual(agg["target"], {"kind": "context", "id": "orders", "part": None})
        self.assertIn("3 of 5 slices in `orders`", agg["text"])
        self.assertFalse(any(i.startswith("flow-undeclared:orders.") for i in self.issues))
        self.assertIn("flow-undeclared:shipping.carrier_webhook", self.issues)
        self.assertIn("flow-undeclared:shipping.nightly_reship", self.issues)

    def test_scenarios_normalised_and_tests_resolved(self):
        place = self.slice("orders.place_order")
        self.assertEqual(place["flows"], ["checkout"])
        by_name = {sc["name"]: sc for sc in place["scenarios"]}
        sc = by_name["places an order"]
        self.assertEqual(sc["when"], {"type": "command", "name": "PlaceOrder",
                                      "with": {"orderId": "o-1", "customerId": "c-7", "quantity": 2}})
        self.assertEqual(sc["then"]["kind"], "steps")
        self.assertEqual(sc["testResolved"]["status"], "found")
        self.assertTrue(sc["testResolved"]["path"].endswith("PlaceOrderTest.java"))
        err = by_name["rejects a zero quantity"]
        self.assertEqual(err["then"], {"kind": "error", "error": "IllegalArgumentException",
                                       "message": "quantity must be positive"})
        self.assertEqual(err["testResolved"]["status"], "found")  # by @DisplayName
        cancel = {sc["name"]: sc for sc in self.slice("orders.cancel_order")["scenarios"]}
        self.assertEqual(cancel["cancelling twice is a no-op"]["then"], {"kind": "nothing"})
        self.assertEqual(cancel["cancelling twice is a no-op"]["testResolved"]["status"], "missing")
        self.assertEqual(cancel["rejects an unknown order"]["testResolved"]["status"], "not-checked")
        rm = self.slice("orders.order_list")["scenarios"][0]["then"]
        self.assertEqual(rm["kind"], "readModel")
        self.assertEqual(rm["rows"], [{"orderId": "o-1", "customerId": "c-7", "quantity": 2, "reason": None}])
        sched = self.slice("shipping.nightly_reship")
        self.assertEqual(sched["schedule"], {"cron": "0 0 2 * * *", "note": "after the carrier's last collection"})
        self.assertEqual(sched["scenarios"][0]["when"], {"type": "schedule", "name": None, "with": {}})
        self.assertEqual(self.slice("billing.payment_list")["flows"], ["checkout", "returns"])

    def test_doc_is_the_safe_subset(self):
        doc = self.slice("orders.place_order")["doc"]
        self.assertIn("<h2>Invariants</h2>", doc)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", doc)
        self.assertNotIn("<script", doc)
        self.assertNotIn("<b>", doc)
        self.assertIn("see the API", doc)  # a link is its text
        self.assertIn("<table><thead><tr><th>Field</th>", doc)
        self.assertIn("<pre><code>new PlaceOrder(&quot;o-1&quot;", doc)
        self.assertIsNone(self.slice("orders.order_history")["doc"])

    def test_lineage(self):
        fields = self.data["lineage"]["fields"]
        self.assertEqual(fields["orderid"][:2], ["msg:command:CancelOrder", "msg:command:ChargePayment"])
        self.assertIn("rm:OrderListView", fields["discount"])
        self.assertEqual(self.data["lineage"]["technicalColumns"], list(slice_atlas.TECHNICAL_COLUMNS))

    def test_bc_filter_cuts_the_additions(self):
        data = json.loads(run("map", "tests/fixtures/atlas", *MAP_ARGS, *self.facts, "--bc", "shipping").stdout)
        self.assertEqual({p["id"] for p in data["processes"]},
                         {"checkout", "~shipping.carrier_webhook", "~shipping.nightly_reship"})
        self.assertTrue(all(i["target"]["id"].startswith("shipping") for i in data["issues"]), data["issues"])


class Markdown(unittest.TestCase):
    TAG = re.compile(r"</?([a-zA-Z0-9]+)([^>]*)>")

    def tags(self, html):
        return [(m.group(1), m.group(2)) for m in self.TAG.finditer(html)]

    def test_only_the_subset_and_no_attributes(self):
        md = ('# T <img src=x onerror=alert(1)>\n\n<div onclick="x">raw</div>\n\n- **a** and *b* and _c_\n'
              '- `<i>` code\n\n1. one\n2. two\n\n> quoted [l](javascript:alert(1)) ![i](x.png)\n\n'
              '| a | b |\n|:--|--:|\n| 1 \\| 2 | `x` |\n\n```\n<script>\n```\n\n---\n')
        out = slice_atlas.markdown_html(md)
        for tag, attrs in self.tags(out):
            self.assertIn(tag, slice_atlas.ALLOWED_TAGS)
            self.assertEqual(attrs, "")
        self.assertIn("<h1>T &lt;img src=x onerror=alert(1)&gt;</h1>", out)
        self.assertIn("<p>&lt;div onclick=&quot;x&quot;&gt;raw&lt;/div&gt;</p>", out)
        self.assertIn("<ul><li><strong>a</strong> and <em>b</em> and <em>c</em></li><li><code>&lt;i&gt;</code> code</li></ul>", out)
        self.assertIn("<ol><li>one</li><li>two</li></ol>", out)
        self.assertIn("<p>quoted l i</p>", out)
        self.assertIn("<td>1 | 2</td><td><code>x</code></td>", out)
        self.assertIn("<pre><code>&lt;script&gt;</code></pre>", out)

    def test_snake_case_is_not_emphasis(self):
        self.assertEqual(slice_atlas.markdown_html("order_list and place_order_v2"),
                         "<p>order_list and place_order_v2</p>")


class Scenarios(unittest.TestCase):
    def test_malformed_kept_as_far_as_it_parses(self):
        raw = [
            {"name": "a", "when": {"command": "X", "event": "Y"}, "then": []},
            {"name": "a", "given": [{"with": {"k": 1}}, {"event": "E", "with": {"d": [1]}}], "then": {"oops": 1}},
            {"when": {"command": "X"}, "then": {"nothing": True}, "extra": 1},
            "not a mapping",
        ]
        out, problems = slice_atlas.normalise_scenarios(raw)
        self.assertEqual([sc["name"] for sc in out], ["a", "a", "#3"])
        self.assertEqual(out[0]["when"]["type"], "command")
        self.assertEqual(out[0]["then"], {"kind": "steps", "steps": []})
        self.assertEqual(out[1]["given"], [{"type": "event", "name": "E", "with": {}}])
        texts = [f"{n}: {p}" for n, p in problems]
        for want in ("names more than one message", "`then` is an empty list", "repeats the name",
                     "names no message", "given[1].with.d is a list", "`then` is none of", "has no `name`",
                     "unknown key(s) extra", "#4: is not a mapping"):
            self.assertTrue(any(want in t for t in texts), (want, texts))

    def test_malformed_scenarios_become_warn_issues(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "a" / "views" / "v").mkdir(parents=True)
            (root / "a" / "views" / "v" / "slice.yaml").write_text(
                'schemaVersion: "1.4"\nslice: a.v\nkind: view\nbc: a\nflow: [one, one, Bad]\n'
                "scenarios:\n  - { name: s, then: [] }\n", encoding="utf-8")
            data = json.loads(run("map", root, "--sha", "x").stdout)
            row = data["slices"][0]
            self.assertEqual(row["flows"], ["one"])
            self.assertEqual([i["id"] for i in data["issues"] if i["code"] == "scenario-malformed"],
                             ["scenario-malformed:a.v:scenario:s"])

    def test_view_read_model_named_by_any_declared_name(self):
        view = {"owns": [], "reads": [{"name": "OrderListView"}], "projections": [{"name": "OrderListProjection"}]}
        one = {"OrderListView": {"name": "OrderListView", "columns": [{"name": "orderId"}]}}
        rm = slice_atlas.read_model_of
        self.assertIs(rm(view, "OrderListView", one), one["OrderListView"])
        self.assertIs(rm(view, "OrderListProjection", one), one["OrderListView"])
        self.assertIsNone(rm(view, "Elsewhere", one))
        self.assertIsNone(rm(view, "OrderListProjection", {**one, "B": {"name": "B"}}))  # which one: not decidable

    def test_resolve_test(self):
        tests = {"roots": ["src/test/kotlin"], "classes": [
            {"name": "CancelOrderTest", "path": "src/test/kotlin/a/CancelOrderTest.kt",
             "methods": [{"name": "cancels a placed order", "line": 7, "displayName": None}]},
            {"name": "Outer.Inner", "path": "src/test/kotlin/b/Outer.kt",
             "methods": [{"name": "m", "line": 3, "displayName": "does it"}]}]}
        r = slice_atlas.resolve_test
        self.assertEqual(r("CancelOrderTest#cancels a placed order", tests),
                         {"status": "found", "path": "src/test/kotlin/a/CancelOrderTest.kt", "line": 7})
        self.assertEqual(r("CancelOrderTest", tests)["status"], "found")
        self.assertEqual(r("a/CancelOrderTest.kt#cancels a placed order", tests)["line"], 7)
        self.assertEqual(r("Inner#does it", tests)["line"], 3)
        self.assertEqual(r("Outer.Inner#m", tests)["line"], 3)
        self.assertEqual(r("CancelOrderTest#nope", tests)["status"], "missing")
        self.assertEqual(r("Nope", tests)["status"], "missing")
        self.assertEqual(r("CancelOrderTest#x", None)["status"], "not-checked")


class SourceIssues(unittest.TestCase):
    """Issue ids from slice-source findings survive an edit that moves lines."""

    def ids(self, root, shift):
        f = "a/views/v/slice.yaml"
        check = {"tool": "slice-source", "mode": "check", "findings": [
            {"gate": "6 discriminator", "slice": "a.v", "file": f, "line": 7 + shift,
             "message": f"GET /x?p= does not bind p (a/views/v/XAPI.java:{20 + shift})"},
            {"gate": "6 discriminator", "slice": "a.v", "file": f, "line": 8 + shift,
             "message": f"GET /x?q= does not bind q (a/views/v/XAPI.java:{24 + shift})"},
            {"gate": "11(b) handled events", "slice": "a.v", "file": "a/views/v/P.java", "line": 3 + shift,
             "message": "handles E, not in projections[].from"}]}
        path = root / f"check{shift}.json"
        path.write_text(json.dumps(check), encoding="utf-8")
        data = json.loads(run("map", root, "--sha", "x", "--source-check", path).stdout)
        return sorted(i["id"] for i in data["issues"] if i["code"].startswith("map-"))

    def test_ids_carry_no_line_and_do_not_collide(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "a" / "views" / "v").mkdir(parents=True)
            (root / "a" / "views" / "v" / "slice.yaml").write_text("slice: a.v\nkind: view\nbc: a\n", encoding="utf-8")
            before, after = self.ids(root, 0), self.ids(root, 5)
            self.assertEqual(before, after)
            self.assertEqual(len(set(before)), 3)
            self.assertIn("map-handled-undeclared:a.v:11(b) handled events@a/views/v/P.java", before)
            disc = [i for i in before if i.startswith("map-endpoint-missing:a.v:6 discriminator@a/views/v/slice.yaml#")]
            self.assertEqual(len(disc), 2)


class Page(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.tpl = self.tmp / "tpl" / "atlas.html"
        self.tpl.parent.mkdir()
        self.tpl.write_text(TEMPLATE, encoding="utf-8")

    def tearDown(self):
        self._tmp.cleanup()

    def atlas(self, root, *extra):
        out = self.tmp / "page.html"
        r = run("atlas", root, "--out", out, "--template", self.tpl, "--sha", "x", *extra)
        return r, out

    def test_a7_writes_the_page_and_exits_1(self):
        r, out = self.atlas("tests/fixtures/atlas")
        self.assertEqual(r.returncode, 1, r.stderr)
        self.assertIn("map incomplete: 1 manifest did not parse", r.stdout)
        data = page_data(out.read_text(encoding="utf-8"))
        self.assertEqual(data["meta"]["incomplete"], 1)
        self.assertEqual((data["meta"]["atlasVersion"], data["meta"]["generatedBy"]), (1, "slice-index.py atlas"))
        self.assertIsNone(data["diff"])
        self.assertIn("layout", data)
        r = run("html", "tests/fixtures/atlas", "--out", self.tmp / "old.html")
        self.assertEqual(r.returncode, 1)  # the old page still refuses
        self.assertFalse((self.tmp / "old.html").exists())

    def test_inliner(self):
        root = self.tmp / "p"
        (root / "a" / "views" / "v").mkdir(parents=True)
        (root / "a" / "views" / "v" / "slice.yaml").write_text(
            'schemaVersion: "1.3"\nslice: a.v\nkind: view\nbc: a\nsummary: "</script><b>&x\u2028y"\n',
            encoding="utf-8")
        (self.tpl.parent / "lens-2-diff.js").write_text("two();\n", encoding="utf-8")
        (self.tpl.parent / "lens-1-atlas.js").write_text("one();\n", encoding="utf-8")
        r, out = self.atlas(root, "--lens", "matrix", "--focus", "a.v", "--bc", "a")
        self.assertEqual(r.returncode, 0, r.stderr)
        page = out.read_text(encoding="utf-8")
        self.assertNotIn("__ATLAS_DATA__", page)
        self.assertNotIn("__ATLAS_LENSES__", page)
        self.assertEqual(page.count("</script>"), 2)
        self.assertIn("\\u003c/script\\u003e\\u003cb\\u003e\\u0026x\\u2028y", page)
        self.assertLess(page.index("/* ---- lens-1-atlas.js ---- */\none();"),
                        page.index("/* ---- lens-2-diff.js ---- */\ntwo();"))
        data = page_data(page)
        self.assertEqual(data["slices"][0]["summary"], "</script><b>&x\u2028y")
        self.assertEqual(data["meta"]["initial"], {"lens": "matrix", "focus": "a.v", "bc": "a"})
        self.assertEqual(data["meta"]["scope"], "full")  # --bc opens the page there; it does not cut the map
        r2, out2 = self.atlas(root, "--lens", "matrix", "--focus", "a.v", "--bc", "a")
        self.assertEqual(out2.read_bytes(), page.encode("utf-8"))  # byte-identical on a rerun
        self.assertEqual(self.atlas(root, "--bc", "nope")[0].returncode, 2)

    def test_placeholders_exactly_once(self):
        root = self.tmp / "p"
        (root / "a" / "views" / "v").mkdir(parents=True)
        (root / "a" / "views" / "v" / "slice.yaml").write_text("slice: a.v\nkind: view\nbc: a\n", encoding="utf-8")
        for broken in (TEMPLATE.replace("/* __ATLAS_LENSES__ */", ""),
                       TEMPLATE + "const ATLAS = /* __ATLAS_DATA__ */ null;\n"):
            self.tpl.write_text(broken, encoding="utf-8")
            r, out = self.atlas(root)
            self.assertEqual(r.returncode, 2, r.stdout)
            self.assertIn("exactly once", r.stderr)
            self.assertFalse(out.exists())

    def test_no_manifests_writes_nothing(self):
        r, out = self.atlas(self.tmp)
        self.assertEqual(r.returncode, 3)
        self.assertFalse(out.exists())


class Diff(unittest.TestCase):
    """--before-ref against a throwaway repository holding the fixture, then a scripted change."""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.repo = self.tmp / "repo"
        self.app = self.repo / "app"
        shutil.copytree(FIXTURE, self.app)
        git(self.repo, "init", "-q", "-b", "main")
        git(self.repo, "add", "-A")
        git(self.repo, "commit", "-q", "-m", "before")
        self.tpl = self.tmp / "atlas.html"
        self.tpl.write_text(TEMPLATE, encoding="utf-8")
        main = self.app / "src/main/java/com/example"
        # remove a slice, add one, change two, repair the broken manifest
        shutil.rmtree(main / "shipping/automations/nightly_reship")
        add = main / "shipping/views/shipment_list"
        add.mkdir(parents=True)
        (add / "slice.yaml").write_text('schemaVersion: "1.4"\nslice: shipping.shipment_list\nkind: view\nbc: shipping\n'
                                        "flow: checkout\nprojections:\n  - { name: P, from: [OrderShipped] }\n",
                                        encoding="utf-8")
        ship = main / "shipping/use_cases/ship_order/slice.yaml"
        ship.write_text(ship.read_text(encoding="utf-8").replace("publishes: [OrderShipped]",
                                                                 "publishes: [OrderShipped, ShipmentLabelled]"),
                        encoding="utf-8")
        cancel = main / "orders/use_cases/cancel_order/slice.yaml"
        cancel.write_text(cancel.read_text(encoding="utf-8").replace(
            "    test: CancelOrderTest#cancellingTwiceIsANoOp\n", ""), encoding="utf-8")
        amend = main / "orders/use_cases/amend_order/slice.yaml"
        amend.write_text(amend.read_text(encoding="utf-8").replace("path: /api/orders/{id},",
                                                                   'path: "/api/orders/{id}",'), encoding="utf-8")

    def tearDown(self):
        self._tmp.cleanup()

    def test_before_ref(self):
        status = git(self.repo, "status", "--porcelain")
        facts = source_facts(self.app, self.tmp)
        out = self.tmp / "page.html"
        r = run("atlas", self.app, "--out", out, "--template", self.tpl, "--sha", "after", "--before-ref", "HEAD",
                *facts)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(git(self.repo, "status", "--porcelain"), status)  # the command is read-only
        self.assertEqual(git(self.repo, "worktree", "list").count("\n"), 1)
        d = page_data(out.read_text(encoding="utf-8"))["diff"]
        self.assertEqual(d["before"]["ref"], "HEAD")
        self.assertEqual(d["before"]["sha"], git(self.repo, "rev-parse", "--short", "HEAD").strip())
        self.assertEqual(d["slices"]["added"], ["orders.amend_order", "shipping.shipment_list"])
        removed = [s["id"] for s in d["slices"]["removed"]]
        self.assertEqual(removed, ["shipping.nightly_reship", "src/main/java/com/example/orders/use_cases/amend_order"])
        self.assertEqual(d["slices"]["removed"][0]["schedule"]["cron"], "0 0 2 * * *")  # the whole before-row
        changed = {c["id"]: c["fields"] for c in d["slices"]["changed"]}
        self.assertEqual(changed["shipping.ship_order"],
                         [{"field": "publishes", "added": ["ShipmentLabelled"], "removed": [], "from": 1, "to": 2}])
        self.assertEqual([f["field"] for f in changed["orders.cancel_order"]], ["scenarios"])
        self.assertIn("scenario-test-missing:orders.cancel_order:scenario:cancelling twice is a no-op",
                      [i["id"] for i in d["issues"]["fixed"]])
        self.assertIn({"from": "slice:shipping.ship_order", "to": "evt:ShipmentLabelled", "kind": "publishes"},
                      d["edges"]["added"])
        self.assertIn({"from": "slice:shipping.nightly_reship", "to": "cmd:ShipOrder", "kind": "dispatches"},
                      d["edges"]["removed"])
        self.assertEqual(d["messages"]["events"], {"added": ["OrderAmended", "ShipmentLabelled"], "removed": []})
        self.assertEqual(d["messages"]["commands"], {"added": ["AmendOrder"], "removed": []})
        self.assertIn("diff vs HEAD", r.stdout)
        d["before"]["sha"] = "<sha>"
        golden(self, GOLDEN / "atlas.diff.json", json.dumps(d, indent=2, ensure_ascii=False, sort_keys=True) + "\n")

    def test_before_map(self):
        old = json.loads(run("map", FIXTURE, "--sha", "old").stdout)
        for key in ("issues", "processes", "lineage"):  # a map written before the Atlas existed
            old.pop(key)
        for row in old["slices"]:
            for key in ("flows", "scenarios", "schedule", "doc"):
                row.pop(key)
        path = self.tmp / "before.json"
        path.write_text(json.dumps(old), encoding="utf-8")
        out = self.tmp / "page.html"
        r = run("atlas", self.app, "--out", out, "--template", self.tpl, "--before-map", path)
        self.assertEqual(r.returncode, 0, r.stderr)
        d = page_data(out.read_text(encoding="utf-8"))["diff"]
        self.assertEqual(d["before"], {"sha": "old", "sliceCount": 14, "ref": None, "issuesKnown": False})
        self.assertEqual(d["issues"], {"new": [], "fixed": []})
        self.assertIn("shipping.shipment_list", d["slices"]["added"])
        self.assertEqual(d["slices"]["removed"][0]["flows"], [])  # a pre-Atlas row still carries `flows`
        # only real changes: a key the pre-Atlas row lacks (flows, scenarios, schedule, doc) is not compared
        self.assertEqual({c["id"]: [f["field"] for f in c["fields"]] for c in d["slices"]["changed"]},
                         {"shipping.ship_order": ["publishes"]})
        self.assertEqual(d["messages"], {"events": {"added": ["OrderAmended", "ShipmentLabelled"], "removed": []},
                                         "commands": {"added": ["AmendOrder"], "removed": []}})

    def test_bad_ref_exits_2(self):
        r = run("atlas", self.app, "--out", self.tmp / "p.html", "--template", self.tpl, "--before-ref", "nope")
        self.assertEqual(r.returncode, 2)
        self.assertIn("--before-ref", r.stderr)


if __name__ == "__main__":
    os.chdir(PLUGIN)
    unittest.main(verbosity=1)
