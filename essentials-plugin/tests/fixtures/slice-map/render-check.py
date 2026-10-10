#!/usr/bin/env python3
"""render-check — the slice-map and Slice Atlas pages, rendered by a real browser and checked from their DOM.

Why this exists
---------------
The graph is laid out by the template's own JavaScript (`buildGraph`, `floorRank`, `layoutGraph` in
`references/slice/slice-map-template.html`), so no amount of reading the data proves the page. This
renders each data file into the template exactly as `slice-index.py html` does, lets headless
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

The Slice Atlas pages
---------------------
The page `/essentials:slice-map-html` writes (`references/slice/atlas/`), built two ways: from
`tests/fixtures/atlas/` exactly as the command builds it (`slice-source.py` twice, then
`slice-index.py atlas`, so this needs `uv`, or a `python3` with `pyyaml`), and from the hand-written
`tests/fixtures/slice-atlas/sample.json` through the same inliner (`scripts/slice_atlas.py`
`assemble`). The Atlas page computes no layout, so its checks are about the page working at all. A probe
script (prepended to the harness's copy of the page, never to the page itself) records every
uncaught error, unhandled rejection and `console.error`, can click one issue in the dock, and writes
what it saw into the DOM for the dump. Each page is loaded several times, each with a URL hash:

    lens          `#lens=<id>` for every lens-<n>-<id>.js file: the lens mounts, its tab is selected,
                  it draws something and no "failed to draw" message; no console error, ever
    banner        an incomplete map (meta.incomplete) shows the "did not parse" banner
    broken        `#issue=<map-parse-error id>`: the unparsed manifest is drawn as a red error card
                  (`g.st.r-error`) in its context, and the dock and card follow
    issue click   the probe clicks a dock row: the row is selected, the hash carries issue= and the
                  target's focus=, the card opens at the issue, the target sticky is selected, and a
                  marker is drawn
    deep link     `#lens=atlas&focus=<slice>&q=<name>&theme=dark`: the same view comes back — lens,
                  focus, card, search text, theme
    stale focus   `#focus=<an id the map lacks>`: the "Not in this map" toast, not a failure
    diff issues   `#lens=diff&diff.list=1` on a page with new issues: the summary's count is the list's, and
                  a per-slice id folded into a context issue (`members`) is named by its slice

plus the unrendered-template trap for `atlas.html`. Pan, zoom, levels of detail and hover stay by eye.

Usage
-----
    render-check.py [--chrome PATH] [--template FILE] [--atlas-template FILE] [--keep DIR] [--only map|atlas]
                    [DATA.json ...]

    With no DATA, checks every file named in expected.json next to this script, then the two Atlas
    pages. DATA names slice-map JSON files for the slice-map page, and naming any skips the Atlas pages
    unless --only atlas. Chrome is found via --chrome,
    then $CHROME_BIN, then chrome-headless-shell, google-chrome, google-chrome-stable, chromium,
    chromium-browser, chrome on PATH, then the macOS app bundle.

Exit codes
----------
    0   every check passed
    1   at least one check failed
    2   could not run — unreadable data or template, a placeholder line drifted, the Atlas fixture
        page could not be built (no uv and no python3 with pyyaml, or slice-index.py exit 2)
    3   SKIPPED — no headless Chrome found. Never read this as a pass
"""

from __future__ import annotations

import argparse
import importlib
import importlib.util
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
from urllib.parse import quote, unquote

HERE = Path(__file__).resolve().parent
PLUGIN = HERE.parent.parent.parent
TEMPLATE = PLUGIN / "references" / "slice" / "slice-map-template.html"
PLACEHOLDER = "const SLICE_MAP = /* __SLICE_MAP_DATA__ */ null;"
SCRIPTS = PLUGIN / "scripts"
ATLAS_TEMPLATE = PLUGIN / "references" / "slice" / "atlas" / "atlas.html"
ATLAS_FIXTURE = PLUGIN / "tests" / "fixtures" / "atlas"
ATLAS_SAMPLE = PLUGIN / "tests" / "fixtures" / "slice-atlas" / "sample.json"
ATLAS_DATA_PREFIX = "const ATLAS = "
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


# ----------------------------------------------------------------- the check's own derivation


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


def dump_dom(chrome, page: Path, workdir: Path, timeout=60, fragment="", extra=()):
    """The DOM after the page's script ran. A timeout is retried once with a fresh profile: a
    browser that stalls at startup is not a layout hang, and a real hang fails both attempts."""
    err = None
    url = page.as_uri() + ("#" + fragment if fragment else "")
    for attempt in (1, 2):
        profile = Path(tempfile.mkdtemp(prefix="profile-", dir=workdir))
        cmd = [chrome, "--headless", "--disable-gpu", "--no-sandbox", "--no-first-run",
               "--disable-extensions", "--disable-dev-shm-usage", "--allow-file-access-from-files",
               f"--user-data-dir={profile}", "--enable-logging=stderr", "--v=0", *extra,
               "--virtual-time-budget=10000", "--dump-dom", url]
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


# ---------------------------------------------------------------- the Slice Atlas pages

# Chrome flags for an Atlas page: a desktop-sized window (below 760 px the dock and card become
# bottom sheets), and reduced motion, which makes the shell's camera flights instant.
ATLAS_CHROME = ("--window-size=1400,900", "--force-prefers-reduced-motion")

# Prepended to the harness's copy of an Atlas page, ahead of the page's own scripts, so it sees every
# error they raise. It writes what it saw into two hidden <pre> elements, which --dump-dom serialises.
# __RC_CLICK__ is the id of a dock issue to click once the page has booted, or null.
PROBE = """<script>
(function () {
  'use strict';
  var errors = [];
  function out(id, value) {
    var el = document.querySelector('#' + id);
    if (!el) {
      el = document.createElement('pre');
      el.setAttribute('id', id);
      el.hidden = true;
      (document.body || document.documentElement).appendChild(el);
    }
    el.textContent = JSON.stringify(value);
  }
  function put(m) { errors.push(String(m)); out('rc-errors', errors); }
  window.addEventListener('error', function (e) { put('uncaught: ' + ((e && e.message) || e)); });
  window.addEventListener('unhandledrejection', function (e) { put('unhandled rejection: ' + (e && e.reason)); });
  var original = console.error;
  console.error = function () {
    put('console.error: ' + Array.prototype.map.call(arguments, function (a) {
      return (a && a.stack) || String(a);
    }).join(' '));
    return original.apply(console, arguments);
  };
  // The pages draw in requestAnimationFrame, and under --virtual-time-budget headless Chrome runs
  // next to none: virtual time jumps from timer to timer with no frame in between, so whether the
  // frame that draws a click's result came before the dump was luck. That was the old issue-click
  // flake: the camera had landed (a transform set synchronously), and the board had not been
  // redrawn at its new level. A frame here is a 16 ms virtual-time timer, so frames advance with
  // virtual time like everything else, deterministically.
  var frames = {}, lastFrame = 0;
  window.requestAnimationFrame = function (cb) {
    var id = ++lastFrame;
    frames[id] = setTimeout(function () { delete frames[id]; cb(performance.now()); }, 16);
    return id;
  };
  window.cancelAnimationFrame = function (id) { clearTimeout(frames[id]); delete frames[id]; };
  var click = __RC_CLICK__;
  window.addEventListener('load', function () {
    setTimeout(function () {
      var clicked = false;
      if (click !== null) {
        document.querySelectorAll('#issues .iss').forEach(function (b) {
          if (!clicked && b.getAttribute('data-id') === click) { b.click(); clicked = true; }
        });
      }
      setTimeout(function () {
        var q = document.querySelector('#q');
        // A board shape painted with SVG's default black fill is one whose lens CSS never arrived.
        var black = 0;
        document.querySelectorAll('#lens-host rect, #lens-host path').forEach(function (r) {
          var cs = getComputedStyle(r);
          if (cs.fill === 'rgb(0, 0, 0)' && cs.fillOpacity !== '0' && cs.display !== 'none') black++;
        });
        out('rc-state', { hash: location.hash, q: q ? q.value : null, clicked: clicked, black: black });
        out('rc-errors', errors);
      }, 1500);
    }, 500);
  });
})();
</script>
"""

STALE_ID = "render-check.not_in_this_map"


def issue_to_click(data):
    """The first issue (data order: errors first) on a slice that parsed — the one the probe clicks."""
    parsed = {s["id"] for s in arr(data.get("slices")) if not s.get("error")}
    return next((i for i in arr(data.get("issues"))
                 if i["target"]["kind"] == "slice" and i["target"]["id"] in parsed), None)


def atlas_runner():
    """How to run the PEP 723 scripts: `uv run --script`, else this python3 when it has pyyaml."""
    if shutil.which("uv"):
        return ["uv", "run", "--quiet", "--script"]
    if importlib.util.find_spec("yaml") is not None:
        return [sys.executable]
    return None


def build_fixture_page(template: Path, work: Path) -> str:
    """tests/fixtures/atlas, built as /essentials:slice-map-html builds a project's page."""
    runner = atlas_runner()
    if runner is None:
        raise HarnessError("cannot build the Atlas fixture page: no `uv` on PATH, and this python3 has no pyyaml")
    facts, check, out = work / "atlas-facts.json", work / "atlas-check.json", work / "atlas-fixture.built.html"
    for target, extra in ((facts, []), (check, ["--check"])):
        r = subprocess.run([*runner, str(SCRIPTS / "slice-source.py"), str(ATLAS_FIXTURE), *extra, "--json"],
                           capture_output=True, text=True, timeout=300)
        if r.returncode not in (0, 1, 3):  # 1 and 3 still write the JSON (findings, gaps)
            raise HarnessError(f"slice-source.py {' '.join(extra)} exited {r.returncode}: {r.stderr.strip()[-400:]}")
        target.write_text(r.stdout, encoding="utf-8")
    r = subprocess.run([*runner, str(SCRIPTS / "slice-index.py"), "atlas", str(ATLAS_FIXTURE), "--out", str(out),
                        "--template", str(template), "--source-facts", str(facts), "--source-check", str(check)],
                       capture_output=True, text=True, timeout=300)
    if r.returncode not in (0, 1):  # 1: written, and the map is incomplete (design A7) — the fixture's broken manifest
        raise HarnessError(f"slice-index.py atlas exited {r.returncode}: {r.stderr.strip()[-400:]}")
    return out.read_text(encoding="utf-8")


def build_sample_page(template: Path) -> str:
    """tests/fixtures/slice-atlas/sample.json through the inliner slice-index.py atlas uses."""
    sys.path.insert(0, str(SCRIPTS))
    try:
        assemble = getattr(importlib.import_module("slice_atlas"), "assemble")
    finally:
        sys.path.remove(str(SCRIPTS))
    return assemble(template, json.loads(ATLAS_SAMPLE.read_text(encoding="utf-8")))


def atlas_data(page_text):
    for line in page_text.splitlines():
        if line.startswith(ATLAS_DATA_PREFIX) and line.endswith(";"):
            return json.loads(line[len(ATLAS_DATA_PREFIX):-1])
    raise HarnessError("no `const ATLAS = …;` line in the built page — the data line of atlas.html moved")


def with_probe(page_text, click=None):
    at = page_text.find("<script")
    if at < 0:
        raise HarnessError("the Atlas page has no <script> element")
    return page_text[:at] + PROBE.replace("__RC_CLICK__", json.dumps(click)) + page_text[at:]


def lens_ids(template: Path):
    """The lenses the page carries, by file name (lens-<n>-<id>.js): the id each file registers."""
    ids = []
    for f in sorted(template.parent.glob("lens-*.js")):
        m = re.fullmatch(r"lens-\d+-([a-z]+)\.js", f.name)
        if m:
            ids.append(m.group(1))
    return ids


def hash_state(h):
    out = {}
    for part in (h or "").lstrip("#").split("&"):
        if "=" in part:
            k, v = part.split("=", 1)
            out[unquote(k)] = unquote(v.replace("+", " "))
    return out


def by_id(root, ident):
    return next((n for n in root.walk() if n.attrs.get("id") == ident), None)


def probe_value(root, ident):
    n = by_id(root, ident)
    if n is None:
        return None
    try:
        return json.loads(n.all_text())
    except ValueError:
        return None


def shown(n):
    return n is not None and "hidden" not in n.attrs


def stickies(root, slice_id, *classes):
    want = {"st", *classes}
    return [g for g in root.find(lambda n: n.tag == "g" and want <= n.classes)
            if f"|{slice_id}|" in g.attrs.get("data-card", "")]


class AtlasPage:
    """One Atlas page, loaded once per URL fragment."""

    def __init__(self, rep, chrome, page: Path, click_page: Path, tmp: Path, keep: Path | None):
        self.rep, self.chrome, self.page, self.click_page, self.tmp, self.keep = rep, chrome, page, click_page, tmp, keep

    def load(self, label, fragment="", click=False):
        page = self.click_page if click else self.page
        dom, err = dump_dom(self.chrome, page, self.tmp, fragment=fragment, extra=ATLAS_CHROME)
        self.rep.check(err is None, f"{label}: render: {err}")
        if dom is None:
            return None, {}
        if self.keep:
            (self.keep / f"{page.stem}.{re.sub(r'[^a-z0-9]+', '-', label)}.dom.html").write_text(dom, encoding="utf-8")
        root = parse(dom)
        errors = probe_value(root, "rc-errors") or []
        state = probe_value(root, "rc-state")
        self.rep.check(state is not None, f"{label}: the probe never reported — the page did not finish loading")
        self.rep.check(not errors, f"{label}: console errors: {errors[:3]}")
        return root, state or {}


def check_issue_selected(rep, root, state, iss, label):
    """The dock row, the hash, the card and the marker all follow one selected issue."""
    rows = root.find(lambda n: "iss" in n.classes and n.attrs.get("data-id") == iss["id"])
    rep.check(len(rows) == 1 and rows[0].attrs.get("aria-selected") == "true",
              f"{label}: the dock row of {iss['id']!r} is missing or not selected")
    h = hash_state(state.get("hash"))
    rep.check(h.get("issue") == iss["id"], f"{label}: hash issue= is {h.get('issue')!r}, expected {iss['id']!r}")
    card = by_id(root, "card")
    rep.check(shown(card), f"{label}: the detail card did not open")
    if card is not None:
        rep.check(bool(card.find(lambda n: n.attrs.get("data-issue") == iss["id"])),
                  f"{label}: the card does not show the issue {iss['id']!r}")
    target = iss["target"]
    if target["kind"] == "slice":
        rep.check(h.get("focus") == target["id"], f"{label}: hash focus= is {h.get('focus')!r}, expected {target['id']!r}")
        title = by_id(root, "card-title")
        got = title.all_text() if title is not None else None
        rep.check(got == target["id"], f"{label}: card title {got!r}, expected {target['id']!r}")


def check_atlas_page(ap: AtlasPage, data, ids):
    rep = ap.rep
    meta = data.get("meta") or {}
    issues = arr(data.get("issues"))
    rep.check(bool(ids) and ids[0] == "atlas", f"lenses: the Atlas lens is not lens 1 ({ids})")

    # Every lens mounts from a deep link, with no console error.
    for lid in ids:
        root, state = ap.load(f"lens {lid}", "lens=" + lid)
        if root is None:
            continue
        hosts = root.find(lambda n: "lens-root" in n.classes)
        rep.check([h.attrs.get("data-lens") for h in hosts] == [lid],
                  f"lens {lid}: mounted {[h.attrs.get('data-lens') for h in hosts]}")
        tabs = root.find(lambda n: n.tag == "button" and n.attrs.get("data-lens") == lid)
        rep.check(len(tabs) == 1 and tabs[0].attrs.get("aria-selected") == "true",
                  f"lens {lid}: its tab is missing or not selected — does lens-*-{lid}.js register id '{lid}'?")
        failed = [n.all_text() for h in hosts for n in h.find(lambda n: "lens-msg" in n.classes)
                  if "failed to draw" in n.all_text()]
        rep.check(not failed, f"lens {lid}: {failed[:1]}")
        rep.check(bool(hosts) and bool(hosts[0].children), f"lens {lid}: drew nothing")
        rep.check(not state.get("black"), f"lens {lid}: {state.get('black')} shapes painted SVG's default black — "
                  "a stylesheet this lens draws with was not injected")
        rep.check(hash_state(state.get("hash")).get("lens") == lid, f"lens {lid}: hash is {state.get('hash')!r}")
        if lid == "atlas":
            banner = by_id(root, "banner")
            if meta.get("incomplete"):
                rep.check(banner is not None and shown(banner) and "did not parse" in banner.all_text(),
                          f"banner: {meta['incomplete']} manifest(s) did not parse, and the banner does not say so")
            else:
                rep.check(not shown(banner), "banner: shown on a complete map")

    # An unparsed manifest is a red error card in its context (design A7), reached from its issue.
    broken = [i for i in issues if i["code"] == "map-parse-error"]
    rep.check(len(broken) == (meta.get("incomplete") or 0),
              f"broken: {len(broken)} map-parse-error issues, meta.incomplete {meta.get('incomplete')}")
    for iss in broken:
        root, state = ap.load("broken", "issue=" + quote(iss["id"], safe=""))
        if root is None:
            continue
        sid = iss["target"]["id"]
        drawn = stickies(root, sid, "r-error")
        rep.check(bool(drawn), f"broken: no error card for {sid!r}; error cards drawn: "
                  f"{[g.attrs.get('data-card') for g in root.find(lambda n: 'r-error' in n.classes)][:3]}")
        check_issue_selected(rep, root, state, iss, "broken")

    # Selecting an issue in the dock focuses its target.
    parsed = {s["id"] for s in arr(data.get("slices")) if not s.get("error")}
    pick = issue_to_click(data)
    if rep.check(pick is not None, "issue click: the data has no issue on a parsed slice to select") and pick:
        root, state = ap.load("issue click", "lens=atlas", click=True)
        if root is not None:
            rep.check(state.get("clicked") is True, f"issue click: no dock row {pick['id']!r} to click")
            check_issue_selected(rep, root, state, pick, "issue click")
            sid = pick["target"]["id"]
            rep.check(bool(stickies(root, sid, "sel")), f"issue click: {sid!r} is not selected on the board")
            rep.check(bool(root.find(lambda n: "mark" in n.classes)), "issue click: no issue marker drawn")

    # A deep link restores the view.
    target = next((s for s in arr(data.get("slices")) if s["id"] in parsed and s.get("kind") == "command"), None)
    if rep.check(target is not None, "deep link: the data has no parsed command slice") and target:
        sid, q = target["id"], target["name"]
        fragment = f"lens=atlas&focus={quote(sid, safe='')}&q={quote(q, safe='')}&theme=dark"
        root, state = ap.load("deep link", fragment)
        if root is not None:
            h = hash_state(state.get("hash"))
            rep.check(h.get("lens") == "atlas" and h.get("focus") == sid and h.get("q") == q,
                      f"deep link: the hash came back as {state.get('hash')!r}")
            rep.check(state.get("q") == q, f"deep link: the search box holds {state.get('q')!r}, expected {q!r}")
            count = by_id(root, "qcount")
            rep.check(count is not None and count.all_text().strip() != "", "deep link: the search counted nothing")
            html_el = next((n for n in root.walk() if n.tag == "html"), None)
            rep.check(html_el is not None and html_el.attrs.get("data-theme") == "dark", "deep link: theme=dark not applied")
            title = by_id(root, "card-title")
            rep.check(shown(by_id(root, "card")) and title is not None and title.all_text() == sid,
                      f"deep link: the card for {sid!r} is not open")
            rep.check(bool(stickies(root, sid, "sel")), f"deep link: {sid!r} is not selected on the board")

    # The Diff lens's new issues: the summary counts what the list shows, and a per-slice id the map
    # folded into a context issue is named by its slice, never by the context's own text.
    d_issues = (data.get("diff") or {}).get("issues") or {}
    new_ids = arr(d_issues.get("new"))
    if "diff" in ids and new_ids and ((data.get("diff") or {}).get("before") or {}).get("issuesKnown") is not False:
        root, _ = ap.load("diff issues", "lens=diff&diff.list=1")
        if root is not None:
            heads = root.find(lambda n: n.tag == "h3" and n.attrs.get("data-anchor") == "new")
            sib = heads[0].parent.children if heads and heads[0].parent else []
            ul = next((n for n in sib[sib.index(heads[0]) + 1:] if n.tag == "ul"), None) if heads else None
            rows = [li for li in ul.children if li.tag == "li"] if ul is not None else []
            rep.check(len(rows) == len(new_ids), f"diff issues: the list shows {len(rows)} new issues, the data "
                      f"names {len(new_ids)}")
            bold = [b.all_text() for btn in root.find(lambda n: n.tag == "button" and {"diff-n", "new"} <= n.classes)
                    for b in btn.children if b.tag == "b"]
            rep.check(bold == [f"+{len(new_ids)}"], f"diff issues: the summary counts {bold}, expected +{len(new_ids)}")
            plain = {i["id"] for i in issues}
            for ctx_issue in issues:
                for sid in arr(ctx_issue.get("members")):
                    fid = f"{ctx_issue['code']}:{sid}"
                    if fid not in new_ids or fid in plain:
                        continue
                    row = next((li for li in rows if any(n.tag == "span" and (n.all_text() + " ").startswith(fid + " ")
                                                         for n in li.children)), None)
                    label = next((n.all_text() for n in row.children if n.tag == "button"), "") if row else ""
                    rep.check(row is not None and label.startswith(sid + " ") and ctx_issue["text"] not in label,
                              f"diff issues: folded {fid!r} reads {label!r} — expected its slice and per-slice "
                              f"wording, not the context issue's text")

    # A stale id is a toast, never a failure.
    root, _ = ap.load("stale focus", "focus=" + STALE_ID)
    if root is not None:
        toast = by_id(root, "toast")
        rep.check(toast is not None and "Not in this map" in toast.all_text(),
                  f"stale focus: no 'Not in this map' toast ({toast.all_text() if toast is not None else None!r})")


def run_atlas(chrome, template: Path, work: Path, tmp: Path, keep: bool):
    """The unrendered-template trap, then the fixture page and the sample page. Returns failures."""
    failed = 0
    ids = lens_ids(template)

    rep = Report("atlas.html (unrendered)")
    raw = work / "atlas-unrendered.html"
    raw.write_text(with_probe(template.read_text(encoding="utf-8")), encoding="utf-8")
    dom, err = dump_dom(chrome, raw, tmp, extra=ATLAS_CHROME)
    if rep.check(err is None, f"render: {err}") and dom is not None:
        root = parse(dom)
        rep.check("This page has not been rendered" in root.all_text(), "unrendered: the notice is missing")
        rep.check(not root.find(lambda n: "lens-root" in n.classes), "unrendered: a lens mounted")
    failed += report(rep)

    for name, build in (("atlas-fixture", lambda: build_fixture_page(template, tmp)),
                        ("atlas-sample", lambda: build_sample_page(template))):
        text = build()
        data = atlas_data(text)
        page, click_page = work / f"{name}.html", work / f"{name}.click.html"
        page.write_text(with_probe(text), encoding="utf-8")
        pick = issue_to_click(data)
        click_page.write_text(with_probe(text, pick["id"] if pick else None), encoding="utf-8")
        rep = Report(name)
        check_atlas_page(AtlasPage(rep, chrome, page, click_page, tmp, work if keep else None), data, ids)
        failed += report(rep)
    return failed


def main(argv=None):
    parser = argparse.ArgumentParser(prog="render-check", description=(__doc__ or "\n\n").split("\n", 2)[1])
    parser.add_argument("data", nargs="*", help="slice-map JSON files for the slice-map page (default: every file "
                        "in expected.json); naming any checks the slice-map page only, unless --only atlas")
    parser.add_argument("--chrome", help="Chrome / Chromium / chrome-headless-shell binary")
    parser.add_argument("--template", default=str(TEMPLATE), help="slice-map-template.html")
    parser.add_argument("--atlas-template", default=str(ATLAS_TEMPLATE),
                        help="atlas.html; its lens-*.js files are read from beside it")
    parser.add_argument("--only", choices=("map", "atlas"), help="check one page kind only")
    parser.add_argument("--keep", help="also write each rendered page and its DOM dump into this directory")
    args = parser.parse_args(argv)
    do_map = args.only != "atlas"
    do_atlas = args.only == "atlas" or (args.only is None and not args.data)

    try:
        expected = json.loads((HERE / "expected.json").read_text(encoding="utf-8"))
        template_text = Path(args.template).read_text(encoding="utf-8")
        grid = grid_constants(template_text)
        files = [Path(d) for d in args.data] or [HERE / name for name in expected["files"]]
        datasets = [(f, f.read_text(encoding="utf-8")) for f in files] if do_map else []
        for f, t in datasets:
            json.loads(t)
        substitute(template_text, "null")
        atlas_template = Path(args.atlas_template)
        if do_atlas:
            atlas_data(build_sample_page(atlas_template))  # the placeholders and the sample, before any browser
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

        pages = 0
        if do_map:
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
            pages += 1

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
                pages += 1

        if do_atlas:
            try:
                failed += run_atlas(chrome, atlas_template, work, Path(tmp), bool(args.keep))
            except (OSError, ValueError, KeyError, subprocess.TimeoutExpired, HarnessError) as exc:
                print(f"render-check: {exc}", file=sys.stderr)
                return 2
            pages += 3

    print(f"render-check: {'FAIL' if failed else 'ok'} — {pages} page(s), chrome {chrome}")
    return 1 if failed else 0


def report(rep):
    status = "FAIL" if rep.failures else "ok"
    print(f"{status:<4}  {rep.name}  ({rep.passed} checks passed, {len(rep.failures)} failed)")
    for f in rep.failures:
        print(f"      ✗ {f}")
    return 1 if rep.failures else 0


if __name__ == "__main__":
    sys.exit(main())
