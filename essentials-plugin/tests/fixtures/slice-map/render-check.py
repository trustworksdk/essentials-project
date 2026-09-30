#!/usr/bin/env python3
"""render-check — the slice-map page, rendered by a real browser and checked from its DOM.

Why this exists
---------------
The graph is laid out by the template's own JavaScript (`buildGraph`, `floorRank`, `layoutGraph` in
`references/slice/slice-map-template.html`), so no amount of reading the data proves the page. This
renders each data file into the template exactly as `/essentials:slice-map --html` does, lets headless
Chrome run the script (`--dump-dom`), and parses the resulting DOM with the stdlib `html.parser`.
No Node, no npm, no browser driver — one Chrome binary and Python.

What it asserts, for every data file
------------------------------------
    rendered      the title is set and the "not been rendered" guard did not fire
    nodes         every node the data declares is drawn exactly once, and nothing else is
                  (the node/edge derivation is re-implemented here from the data contract)
    edges         every declared edge is drawn, with the right ends
    placed        every node has a translate(x,y) on the column grid
    no overlap    no two node boxes intersect — the scale check
    floor         no node sits left of its role column (commands 0, command slices 1,
                  events/external systems 2, reactor slices 3)
    forward       every edge points right, except an edge that closes a cycle (its target can
                  reach its source); a data file marked `cycle: true` must contain one — G10
    views         Flow rows, Endpoint rows, flagged write targets, cross-reads without `via`,
                  slice buttons and every slice flag text are all on the page

plus the per-file expectations in `expected.json` (exact ranks, isolated nodes, first rows), and the
unrendered-template trap. Interaction (pan, zoom, click-to-focus, hover, the pointer-capture trap)
is NOT covered — a DOM dump cannot see it. Check those by eye per TEST-GUIDE.md.

Usage
-----
    render-check.py [--chrome PATH] [--template FILE] [--keep DIR] [DATA.json ...]

    With no DATA, checks every file named in expected.json next to this script. Chrome is found
    via --chrome, then $CHROME_BIN, then chrome-headless-shell, google-chrome,
    google-chrome-stable, chromium, chromium-browser, chrome on PATH, then the macOS app bundle.

Exit codes
----------
    0   every check passed
    1   at least one check failed
    2   could not run — unreadable data or template, the placeholder line drifted
    3   SKIPPED — no headless Chrome found. Never read this as a pass
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
from html.parser import HTMLParser
from pathlib import Path

HERE = Path(__file__).resolve().parent
PLUGIN = HERE.parent.parent.parent
TEMPLATE = PLUGIN / "references" / "slice" / "slice-map-template.html"
PLACEHOLDER = "const SLICE_MAP = /* __SLICE_MAP_DATA__ */ null;"
# chrome-headless-shell first: it is the build made for exactly this (--dump-dom, no UI stack).
CHROME_NAMES = ["chrome-headless-shell", "google-chrome", "google-chrome-stable", "chromium",
                "chromium-browser", "chrome"]
MAC_CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
VOID = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "source", "track", "wbr"}


class HarnessError(Exception):
    pass


# ---------------------------------------------------------------- the DOM


class Node:
    __slots__ = ("tag", "attrs", "children", "parent", "text")

    def __init__(self, tag: str, attrs: dict, parent: "Node | None"):
        self.tag, self.attrs, self.parent = tag, attrs, parent
        self.children: list[Node] = []
        self.text: list[str] = []

    @property
    def classes(self):
        return set((self.attrs.get("class") or "").split())

    def all_text(self):
        return "".join(self.text) + "".join(c.all_text() for c in self.children)

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()

    def find(self, pred):
        return [n for n in self.walk() if pred(n)]


class Tree(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.root = Node("#root", {}, None)
        self.cur: Node = self.root

    def handle_starttag(self, tag, attrs):
        node = Node(tag, {k: (v if v is not None else "") for k, v in attrs}, self.cur)
        self.cur.children.append(node)
        if tag not in VOID:
            self.cur = node

    def handle_startendtag(self, tag, attrs):
        self.cur.children.append(Node(tag, {k: (v if v is not None else "") for k, v in attrs}, self.cur))

    def handle_endtag(self, tag):
        # Close the nearest open element with this tag; an end tag matching nothing open is ignored.
        n = self.cur
        while n is not self.root and n.tag != tag:
            if n.parent is None:
                return
            n = n.parent
        if n is not self.root and n.parent is not None:
            self.cur = n.parent

    def handle_data(self, data):
        # The page's own script source carries every UI string, the guard text included; only
        # rendered text counts.
        if self.cur.tag not in ("script", "style"):
            self.cur.text.append(data)


def parse(html):
    t = Tree()
    t.feed(html)
    t.close()
    return t.root


# ---------------------------------------------------------------- the oracle's own derivation


def arr(v):
    return v if isinstance(v, list) else ([] if v is None else [v])


def name_of(v):
    return v.get("name", str(v)) if isinstance(v, dict) else str(v)


def expected_graph(data):
    """The nodes and edges the data contract declares — slice-map.md §3 "Message graph",
    written independently of the template's buildGraph()."""
    nodes, edges = {}, []
    for s in arr(data.get("slices")):
        sid = "s:" + s["id"]
        nodes.setdefault(sid, {"type": "slice", "kind": s.get("kind")})
        for c in arr(s.get("handles")):
            nodes.setdefault("c:" + name_of(c), {"type": "command"})
            edges.append(("c:" + name_of(c), sid))
        inbound = [name_of(e) for e in arr(s.get("consumes"))]
        inbound += [name_of(e) for p in arr(s.get("projections")) for e in arr((p or {}).get("from"))]
        for e in dict.fromkeys(inbound):
            nodes.setdefault("e:" + e, {"type": "event"})
            edges.append(("e:" + e, sid))
        for e in arr(s.get("publishes")):
            nodes.setdefault("e:" + name_of(e), {"type": "event"})
            edges.append((sid, "e:" + name_of(e)))
        for c in arr(s.get("dispatches")):
            nodes.setdefault("c:" + name_of(c), {"type": "command"})
            edges.append((sid, "c:" + name_of(c)))
        if s.get("externalSystem"):
            x = "x:" + s["externalSystem"]
            nodes.setdefault(x, {"type": "external"})
            d = s.get("direction") or "both"
            if d in ("inbound", "both"):
                edges.append((x, sid))
            if d in ("outbound", "both"):
                edges.append((sid, x))
    return nodes, edges


def role_floor(node):
    if node["type"] == "slice":
        return 1 if node["kind"] == "command" else 3
    return 0 if node["type"] == "command" else 2


def reaches(edges, a, b):
    adj = {}
    for x, y in edges:
        adj.setdefault(x, set()).add(y)
    seen, todo = {a}, [a]
    while todo:
        cur = todo.pop()
        if cur == b:
            return True
        for n in adj.get(cur, ()):
            if n not in seen:
                seen.add(n)
                todo.append(n)
    return False


# ---------------------------------------------------------------- rendering


def find_chrome(explicit):
    for cand in [explicit, os.environ.get("CHROME_BIN")]:
        if cand and Path(cand).is_file():
            return cand
    for name in CHROME_NAMES:
        found = shutil.which(name)
        if found:
            return found
    return MAC_CHROME if Path(MAC_CHROME).is_file() else None


def substitute(template_text, data_text):
    if template_text.count(PLACEHOLDER) != 1:
        raise HarnessError("placeholder line drifted — commands/slice-map.md §6 and the template must "
                           "agree on: " + PLACEHOLDER)
    return template_text.replace(PLACEHOLDER, "const SLICE_MAP = " + data_text.strip() + ";")


def dump_dom(chrome, page: Path, workdir: Path, timeout=60):
    """The DOM after the page's script ran. A timeout is retried once with a fresh profile: a
    browser that stalls at startup is not a layout hang, and a real hang fails both attempts."""
    err = None
    for attempt in (1, 2):
        profile = Path(tempfile.mkdtemp(prefix="profile-", dir=workdir))
        cmd = [chrome, "--headless", "--disable-gpu", "--no-sandbox", "--no-first-run",
               "--disable-extensions", "--disable-dev-shm-usage", "--allow-file-access-from-files",
               f"--user-data-dir={profile}", "--enable-logging=stderr", "--v=0",
               "--virtual-time-budget=10000", "--dump-dom", page.as_uri()]
        # Own process group, so a timeout takes Chrome's helper processes down with it.
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
                                start_new_session=True)
        try:
            out, stderr = proc.communicate(timeout=timeout)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except OSError:
                pass
            proc.communicate()
            err = (f"Chrome did not return within {timeout} s, twice — the page hangs (a layout "
                   "loop is the G10 failure)")
            continue
        if proc.returncode != 0 or "<html" not in out:
            return None, f"Chrome exited {proc.returncode}: {stderr.strip()[-400:]}"
        # An uncaught exception blanks part of the page without failing the dump; surface it by name.
        errors = [line.split("] ", 1)[-1] for line in stderr.splitlines() if "Uncaught" in line]
        if errors:
            return out, "JavaScript error: " + " | ".join(errors[:3])
        return out, None
    return None, err


# ---------------------------------------------------------------- checks


class Report:
    def __init__(self, name):
        self.name, self.failures, self.passed = name, [], 0

    def check(self, ok, what):
        if ok:
            self.passed += 1
        else:
            self.failures.append(what)
        return ok


def grid_constants(template_text):
    m = re.search(r"const NW = (\d+), NH = (\d+), COLGAP = (\d+), ROWGAP = (\d+);", template_text)
    if not m:
        raise HarnessError("cannot find `const NW = …, NH = …, COLGAP = …, ROWGAP = …;` in the template — "
                           "the layout constants moved; update render-check.py with them")
    return tuple(int(g) for g in m.groups())


def check_page(rep, dom, data, exp, grid):
    nw, nh, colgap, _ = grid
    root = parse(dom)
    text = root.all_text()

    title = next((n.all_text() for n in root.walk() if n.attrs.get("id") == "title"), "")
    project = (data.get("meta") or {}).get("project")
    rep.check("not been rendered" not in text, "rendered: the unrendered-template guard fired")
    rep.check(title == "Slice map" + (" — " + project if project else ""), f"rendered: title is {title!r}")
    if "title" in exp:
        rep.check(title == exp["title"], f"expected title {exp['title']!r}, got {title!r}")

    want_nodes, want_edges = expected_graph(data)
    drawn = {}
    for g in root.find(lambda n: n.tag == "g" and "data-node" in n.attrs):
        drawn.setdefault(g.attrs["data-node"], []).append(g)
    dup = sorted(k for k, v in drawn.items() if len(v) > 1)
    missing = sorted(set(want_nodes) - set(drawn))
    extra = sorted(set(drawn) - set(want_nodes))
    rep.check(not dup, f"nodes: drawn more than once: {dup}")
    rep.check(not missing, f"nodes: declared but not drawn: {missing}")
    rep.check(not extra, f"nodes: drawn but not declared: {extra}")
    if "nodes" in exp:
        rep.check(len(drawn) == exp["nodes"], f"nodes: {len(drawn)} drawn, expected {exp['nodes']}")

    paths = [(p.attrs.get("data-from"), p.attrs.get("data-to"))
             for p in root.find(lambda n: n.tag == "path" and "ge" in n.classes)]
    rep.check(sorted(paths) == sorted(want_edges),
              f"edges: drawn {len(paths)}, declared {len(want_edges)}; "
              f"missing {sorted(set(want_edges) - set(paths))[:5]}, extra {sorted(set(paths) - set(want_edges))[:5]}")
    if "edges" in exp:
        rep.check(len(paths) == exp["edges"], f"edges: {len(paths)} drawn, expected {exp['edges']}")

    pos, rank = {}, {}
    for nid, gs in drawn.items():
        m = re.fullmatch(r"translate\((-?[\d.]+),\s*(-?[\d.]+)\)", gs[0].attrs.get("transform", ""))
        if m is None:
            rep.check(False, f"placed: {nid} has no translate(x,y)")
            continue
        rep.check(True, "placed")
        x, y = float(m.group(1)), float(m.group(2))
        pos[nid] = (x, y)
        col = x / (nw + colgap)
        if rep.check(col == int(col) and col >= 0 and y >= 0, f"placed: {nid} off the grid at ({x},{y})"):
            rank[nid] = int(col)

    items = sorted(pos.items(), key=lambda kv: kv[1])
    overlaps = []
    for i, (a, (xa, ya)) in enumerate(items):
        for b, (xb, yb) in items[i + 1:]:
            if xb - xa >= nw:
                break
            if abs(ya - yb) < nh:
                overlaps.append((a, b))
    rep.check(not overlaps, f"no overlap: {len(overlaps)} intersecting pairs, e.g. {overlaps[:3]}")

    low = [n for n, r in rank.items() if n in want_nodes and r < role_floor(want_nodes[n])]
    rep.check(not low, f"floor: left of their role column: {low[:5]}")

    backward = [(a, b) for a, b in set(want_edges) if a in rank and b in rank and rank[b] <= rank[a]]
    not_cycle = [(a, b) for a, b in backward if not reaches(want_edges, b, a)]
    rep.check(not not_cycle, f"forward: edges pointing left that close no cycle: {not_cycle[:5]}")
    if exp.get("cycle") is True:
        rep.check(bool(backward), "G10: the data has a cycle but no edge closes it on the page")
    elif exp.get("cycle") is False:
        rep.check(not backward, f"forward: no cycle expected, but edges point left: {backward[:5]}")

    for nid, r in exp.get("ranks", {}).items():
        rep.check(rank.get(nid) == r, f"rank: {nid} in column {rank.get(nid)}, expected {r}")
    if "maxRank" in exp:
        got = max(rank.values(), default=-1)
        rep.check(got == exp["maxRank"], f"rank: widest column {got}, expected {exp['maxRank']}")
    degree = {}
    for a, b in want_edges:
        degree[a] = degree.get(a, 0) + 1
        degree[b] = degree.get(b, 0) + 1
    isolated = sorted(n for n in want_nodes if n not in degree)
    if "isolated" in exp:
        rep.check(isolated == sorted(exp["isolated"]), f"isolated: {isolated}, expected {exp['isolated']}")
        rep.check(all(n in pos for n in isolated), "isolated: an unconnected node was not placed")
    for nid, n in exp.get("inbound", {}).items():
        got = sum(1 for _, b in paths if b == nid)
        rep.check(got == n, f"inbound: {nid} has {got} inbound edges drawn, expected {n}")

    # the four row views
    def section(sid):
        """A view's <section>. A missing one is a failure, and an empty stand-in lets the rest run."""
        node = next((n for n in root.walk() if n.attrs.get("id") == sid), None)
        if node is None:
            rep.check(False, f"views: section #{sid} is not on the page")
            return Node("#missing", {}, None)
        return node

    flows = arr(data.get("flows"))
    flow_rows = section("v-flow").find(lambda n: n.tag == "div" and "flow" in n.classes)
    rep.check(len(flow_rows) == len(flows), f"views: {len(flow_rows)} Flow rows, {len(flows)} flows")
    if "firstFlows" in exp:
        got = [r.find(lambda n: "ev" in n.classes)[0].all_text() for r in flow_rows[:len(exp["firstFlows"])]]
        rep.check(got == exp["firstFlows"], f"views: Flow starts {got}, expected {exp['firstFlows']}")
    endpoints = sum(len(arr(s.get("endpoints"))) for s in arr(data.get("slices")))
    ep_rows = section("v-endpoints").find(lambda n: n.tag == "tr" and n.parent is not None and n.parent.tag == "tbody")
    rep.check(len(ep_rows) == endpoints, f"views: {len(ep_rows)} Endpoint rows, {endpoints} endpoints")
    writers = arr(data.get("writers"))
    data_view = section("v-data")
    flagged = data_view.find(lambda n: n.tag == "tr" and "multi" in n.classes)
    want_flagged = [w["target"] for w in writers if w.get("flag")]
    rep.check(len(flagged) == len(want_flagged), f"views: {len(flagged)} flagged write targets, "
              f"expected {len(want_flagged)}")
    if "flaggedWriters" in exp:
        got = [r.children[0].all_text() for r in flagged]
        rep.check(got == exp["flaggedWriters"], f"views: flagged write targets {got}, expected {exp['flaggedWriters']}")
    no_via = sum(1 for c in arr(data.get("crossReads")) if not c.get("via"))
    got_no_via = data_view.all_text().count("no via: — R4 requires one")
    rep.check(got_no_via == no_via, f"views: {got_no_via} cross-reads marked without via, expected {no_via}")

    ctx = {c["id"] for c in arr(data.get("contexts"))}
    buttons = root.find(lambda n: n.tag == "button" and "slice" in n.classes)
    in_ctx = [s for s in arr(data.get("slices")) if s.get("bc") in ctx]
    rep.check(len(buttons) == len(in_ctx), f"views: {len(buttons)} slice buttons, {len(in_ctx)} slices")
    flags_on_page = {n.all_text() for n in root.find(lambda n: n.tag == "span" and "flag" in n.classes)}
    lost = [f["text"] for s in in_ctx for f in arr(s.get("flags")) if f.get("text") not in flags_on_page]
    rep.check(not lost, f"views: slice flags not shown: {lost[:3]}")
    for needle in exp.get("text", []):
        rep.check(needle in text, f"text: {needle!r} not on the page")


def main(argv=None):
    parser = argparse.ArgumentParser(prog="render-check", description=(__doc__ or "\n\n").split("\n", 2)[1])
    parser.add_argument("data", nargs="*", help="slice-map JSON files (default: every file in expected.json)")
    parser.add_argument("--chrome", help="Chrome / Chromium / chrome-headless-shell binary")
    parser.add_argument("--template", default=str(TEMPLATE), help="slice-map-template.html")
    parser.add_argument("--keep", help="also write each rendered page and its DOM dump into this directory")
    args = parser.parse_args(argv)

    try:
        expected = json.loads((HERE / "expected.json").read_text(encoding="utf-8"))
        template_text = Path(args.template).read_text(encoding="utf-8")
        grid = grid_constants(template_text)
        files = [Path(d) for d in args.data] or [HERE / name for name in expected["files"]]
        datasets = [(f, f.read_text(encoding="utf-8")) for f in files]
        for f, t in datasets:
            json.loads(t)
        substitute(template_text, "null")
    except (OSError, ValueError, KeyError, HarnessError) as exc:
        print(f"render-check: {exc}", file=sys.stderr)
        return 2

    chrome = find_chrome(args.chrome)
    if not chrome:
        print("render-check: SKIPPED — no headless Chrome found (--chrome, $CHROME_BIN, or one of "
              + ", ".join(CHROME_NAMES) + " on PATH). Nothing was checked.")
        return 3

    failed = 0
    with tempfile.TemporaryDirectory(prefix="slice-map-render-", ignore_cleanup_errors=True) as tmp:
        work = Path(args.keep) if args.keep else Path(tmp)
        work.mkdir(parents=True, exist_ok=True)

        # Trap: the template opened directly shows the guard and nothing else.
        rep = Report("template (unrendered)")
        raw = work / "unrendered.html"
        raw.write_text(template_text, encoding="utf-8")
        dom, err = dump_dom(chrome, raw, Path(tmp))
        if rep.check(err is None, f"render: {err}"):
            root = parse(dom)
            rep.check("This template has not been rendered." in root.all_text(),
                      "unrendered: the guard text is missing")
            rep.check(not root.find(lambda n: "data-node" in n.attrs), "unrendered: graph nodes were drawn")
        failed += report(rep)

        for path, text in datasets:
            rep = Report(path.name)
            data = json.loads(text)
            page = work / (path.stem + ".html")
            page.write_text(substitute(template_text, text), encoding="utf-8")
            dom, err = dump_dom(chrome, page, Path(tmp))
            rep.check(err is None, f"render: {err}")
            if dom is not None:
                if args.keep:
                    (work / (path.stem + ".dom.html")).write_text(dom, encoding="utf-8")
                check_page(rep, dom, data, expected.get("expect", {}).get(path.name, {}), grid)
            failed += report(rep)

    print(f"render-check: {'FAIL' if failed else 'ok'} — {len(datasets) + 1} page(s), chrome {chrome}")
    return 1 if failed else 0


def report(rep):
    status = "FAIL" if rep.failures else "ok"
    print(f"{status:<4}  {rep.name}  ({rep.passed} checks passed, {len(rep.failures)} failed)")
    for f in rep.failures:
        print(f"      ✗ {f}")
    return 1 if rep.failures else 0


if __name__ == "__main__":
    sys.exit(main())
