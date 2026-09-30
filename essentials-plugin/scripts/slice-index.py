#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3"]
# ///
"""slice-index — the manifest algebra behind /essentials:slice-map, done in code.

Why this exists
---------------
Everything `/essentials:slice-map` derives in its Step 3 is pure manifest algebra: which slices
publish and react to an event, who writes an aggregate or owns a read model, which reads cross a
bounded-context boundary, which slices are a `supersedes` migration twin, and the message chains
of the terminal graph. None of it needs judgement, and all of it has a confidently-wrong failure
mode when done by eye — reading `consumes` alone reports every view as reacting to nothing. This
script computes it, so the command keeps only the prose and the checks that need source.

It reads `slice.yaml` files only. Facts that come from source (`package`, `files`, message
payloads, read-model columns, handled-event and endpoint divergence) are merged in from the JSON
`slice-source.py` writes, never re-derived here — run both scripts on the same ROOT:

    slice-source.py ROOT --json > facts.json ; slice-source.py ROOT --check --json > check.json
    slice-index.py map ROOT --source-facts facts.json --source-check check.json

slice-source exits 0, 1 or 3 with complete JSON on stdout; 2 means no JSON.

Usage
-----
    slice-index.py map   [ROOT] [--bc BC] [--source-facts FILE] [--source-check FILE] [--sha SHA] [--project NAME]
                         [--root-label LABEL] [--skipped TEXT]... [--repair-braced-paths]
    slice-index.py html  [ROOT] --out FILE [--template FILE]  (+ every `map` option)
    slice-index.py graph [ROOT] [--bc BC] [--json] [--repair-braced-paths]
    slice-index.py query [ROOT] WHAT NAME [--method M] [--json]

    map     the slice-map data contract (commands/slice-map.md §6) as JSON on stdout
    html    the same data substituted into the slice-map page, written to --out
    graph   the terminal --view graph: message chains from each entry point, longest first,
            each node expanded once, a repeat on the current path marked as a cycle
    query   locate the owning slice from manifests (references/slice/change-procedure.md §3).
            WHAT is one of:
              who-handles CMD      handles
              who-dispatches CMD   dispatches
              who-serves QUERY     serves
              who-publishes EVT    publishes
              who-reacts EVT       consumes AND projections[].from — a view declares its events
                                   on the projection, so a consumes-only search misses every view
              who-writes NAME      writes (aggregate) and owns (read model)
              who-reads NAME       reads
              who-owns-endpoint P  endpoints[].path — exact route, then a concrete path against
                                   `{var}` segments, then prefix; the `?query` part is ignored
              slice ID             the normalised manifest of one slice
            Names match case-insensitively.

Exit codes
----------
    0   done
    1   done, but at least one manifest did not parse. `map` and `graph` still emit output, with the
        broken manifest carried as an `error` row; `html` writes NOTHING, because a page that
        silently omits a slice misleads. `query` exits 1 when nothing matched
    2   could not run — bad arguments, unreadable input, no YAML parser
    3   no slice.yaml under ROOT: nothing is declared, so there is nothing to map
        (/essentials:slice-discover is the command for that case)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

SKIP_DIRS = {".git", "target", "build", "out", "node_modules", ".idea", ".gradle", "dist"}
PLACEHOLDER = "const SLICE_MAP = /* __SLICE_MAP_DATA__ */ null;"

# The unquoted `path:` value slice-lint detects: inside a flow mapping its '{' opens a nested mapping
# and the whole file stops being YAML (manifest-guide.md §3).
UNQUOTED_PATH = re.compile(r"""\bpath:[ \t]*+(?!["'])""")

# The directory a slice of each kind lives in (§R3). A mismatch is a Step 4 divergence flag.
KIND_DIR = {
    "command": "use_cases",
    "view": "views",
    "automation": "automations",
    "translation": "external_systems",
}

# The column a node belongs in on message-flow grounds alone — the template's floorRank().
ROLE_FLOOR = {"command": 0, "event": 2, "external": 2}


class UsageError(Exception):
    pass


# ---------------------------------------------------------------- loading


def find_manifests(root: Path):
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        if "slice.yaml" in filenames:
            found.append(Path(dirpath) / "slice.yaml")
    return sorted(found)


def repair_braced_paths(text: str):
    """Quote every unquoted `path:` value containing '{'. Returns (text, [line numbers changed]).
    The value runs to the next ',' or ' #', or the end of the line, less the '}' closing the flow
    mapping it sits in."""
    lines, changed = [], []
    for i, line in enumerate(text.splitlines(keepends=True), 1):
        m = UNQUOTED_PATH.search(line)
        if m:
            start = m.end()
            end = len(line.rstrip("\r\n"))
            for stop in (line.find(",", start), line.find(" #", start)):
                if stop != -1:
                    end = min(end, stop)
            value = line[start:end].rstrip()
            while value.endswith("}") and value.count("}") > value.count("{"):
                value = value[:-1].rstrip()
            if "{" in value:
                line = line[:start] + '"' + value + '"' + line[start + len(value):]
                changed.append(i)
        lines.append(line)
    return "".join(lines), changed


def load(root: Path, repair: bool):
    """Every manifest under root as (rel_dir, doc or None, error or None, repaired_lines)."""
    import yaml

    out = []
    for path in find_manifests(root):
        rel = path.parent.relative_to(root).as_posix()
        rel = "." if rel == "" else rel
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            out.append((rel, None, f"unreadable: {exc}", []))
            continue
        try:
            doc = yaml.safe_load(text)
        except yaml.YAMLError as exc:
            mark = getattr(exc, "problem_mark", None)
            where = f" (line {mark.line + 1})" if mark is not None else ""
            error = f"slice.yaml is not valid YAML{where}"
            if repair:
                fixed, changed = repair_braced_paths(text)
                if changed:
                    try:
                        doc = yaml.safe_load(fixed)
                    except yaml.YAMLError:
                        doc = None
                    if isinstance(doc, dict):
                        out.append((rel, doc, None, changed))
                        continue
            out.append((rel, None, error, []))
            continue
        if not isinstance(doc, dict):
            out.append((rel, None, "slice.yaml parses, but is not a mapping at the root", []))
            continue
        out.append((rel, doc, None, []))
    return out


# ---------------------------------------------------------------- normalising


def _list(value):
    if value is None:
        return []
    return value if isinstance(value, list) else [value]


def names(value):
    """`handles`, `serves`, `publishes`, `consumes`, `dispatches`, `writes`, `owns` take a bare
    string or an object with `name`. Both forms are the same thing."""
    out = []
    for item in _list(value):
        if isinstance(item, str):
            out.append(item)
        elif isinstance(item, dict) and isinstance(item.get("name"), str):
            out.append(item["name"])
    return out


def _str(value):
    return value if isinstance(value, str) else None


def normalise(rel, doc):
    """One manifest as a slice-map `slices[]` row (source-derived keys left empty)."""
    reads = []
    for item in _list(doc.get("reads")):
        if isinstance(item, str):
            reads.append({"name": item, "via": None, "external": False})
        elif isinstance(item, dict) and isinstance(item.get("name"), str):
            reads.append({"name": item["name"], "via": _str(item.get("via")), "external": False})
    projections = []
    for p in _list(doc.get("projections")):
        if isinstance(p, dict):
            row = {"name": _str(p.get("name")), "from": [n for n in _list(p.get("from")) if isinstance(n, str)]}
            for key in ("aggregateTypes", "consistency", "store"):
                if key in p:
                    row[key] = p[key]
            projections.append(row)
    endpoints = []
    for e in _list(doc.get("endpoints")):
        if isinstance(e, dict):
            endpoints.append({"method": _str(e.get("method")), "path": str(e.get("path", "")),
                              "auth": _str(e.get("auth"))})
    invariants = []
    for inv in _list(doc.get("invariants")):
        if isinstance(inv, dict):
            invariants.append({"id": _str(inv.get("id")), "text": str(inv.get("text", "")),
                               "enforcedBy": _str(inv.get("enforcedBy"))})
    # `tests` lists the kinds that are present: a key whose entry says `present: false` is absent.
    tests = []
    raw_tests = doc.get("tests")
    if isinstance(raw_tests, dict):
        for key, entry in raw_tests.items():
            if not (isinstance(entry, dict) and entry.get("present") is False):
                tests.append(str(key))

    sid = _str(doc.get("slice"))
    bc = _str(doc.get("bc"))
    name = sid.split(".", 1)[1] if sid and "." in sid else (sid or Path(rel).name)
    return {
        "id": sid or rel,
        "bc": bc,
        "name": name,
        "kind": _str(doc.get("kind")),
        "status": _str(doc.get("status")),
        "owner": _str(doc.get("owner")),
        "summary": _str(doc.get("summary")),
        "language": _str(doc.get("language")),
        "tier": _str(doc.get("tier")),
        "lane": _str(doc.get("lane")),
        "path": rel,
        "package": None,
        "files": [],
        "handles": names(doc.get("handles")),
        "serves": names(doc.get("serves")),
        "publishes": names(doc.get("publishes")),
        "consumes": names(doc.get("consumes")),
        "dispatches": names(doc.get("dispatches")),
        "writes": names(doc.get("writes")),
        "owns": names(doc.get("owns")),
        "projections": projections,
        "reads": reads,
        "endpoints": endpoints,
        "invariants": invariants,
        "externalSystem": _str(doc.get("externalSystem")),
        "direction": _str(doc.get("direction")),
        "supersedes": _str(doc.get("supersedes")),
        "dependsOn": [d for d in _list(doc.get("dependsOn")) if isinstance(d, str)],
        "tests": tests,
        "flags": [],
        # object forms carry what messages() needs; dropped from the output
        "_raw_handles": _list(doc.get("handles")),
        "_raw_publishes": _list(doc.get("publishes")),
    }


def broken_row(rel, error):
    """A manifest that did not parse — carried into the map, never dropped."""
    return {
        "id": rel, "bc": None, "name": Path(rel).name, "kind": None, "status": None, "owner": None,
        "summary": None, "language": None, "tier": None, "lane": None, "path": rel, "package": None,
        "files": [], "handles": [], "serves": [], "publishes": [], "consumes": [], "dispatches": [],
        "writes": [], "owns": [], "projections": [], "reads": [], "endpoints": [], "invariants": [],
        "externalSystem": None, "direction": None, "supersedes": None, "dependsOn": [], "tests": [],
        "flags": [{"level": "error", "text": error + " — this slice is missing from every edge of the map. "
                   "Repair: /essentials:slice-check --fix-manifests (manifest-guide.md §3)"}],
        "error": True,
    }


def inbound(s):
    """A slice's inbound events: `consumes` ∪ `projections[].from`, in declaration order."""
    seen, out = set(), []
    for name in s["consumes"] + [n for p in s["projections"] for n in p["from"]]:
        if name not in seen:
            seen.add(name)
            out.append(name)
    return out


def flag(s, level, text):
    s["flags"].append({"level": level, "text": text})


# ---------------------------------------------------------------- the index


class Index:
    def __init__(self, loaded):
        self.slices = []
        self.broken = []
        self.repaired = []
        for rel, doc, error, repaired in loaded:
            if doc is None:
                self.slices.append(broken_row(rel, error))
                self.broken.append(rel)
            else:
                s = normalise(rel, doc)
                if repaired:
                    self.repaired.append(rel)
                    flag(s, "warn", "slice.yaml is not valid YAML on disk (unquoted path containing '{' on line "
                         + ", ".join(map(str, repaired)) + "); mapped from an in-memory corrected copy. "
                         "Repair: /essentials:slice-check --fix-manifests")
                self.slices.append(s)
        self.good = [s for s in self.slices if not s.get("error")]
        self.by_id = {}
        for s in self.good:
            self.by_id.setdefault(s["id"], s)

    # -- Step 4 divergence checks that need no source
    def divergence(self):
        counts = {}
        for s in self.good:
            counts[s["id"]] = counts.get(s["id"], 0) + 1
        published = {e for s in self.good for e in s["publishes"]}
        for s in self.good:
            if counts[s["id"]] > 1:
                others = [o["path"] for o in self.good if o["id"] == s["id"] and o is not s]
                flag(s, "error", f"slice id '{s['id']}' is also declared by: " + ", ".join(others))
            want = KIND_DIR.get(s["kind"])
            if want and Path(s["path"]).parent.name != want:
                flag(s, "warn", f"{'an' if s['kind'][0] in 'aeiou' else 'a'} {s['kind']} slice outside {want}/ (it is under "
                     f"{Path(s['path']).parent.as_posix()}/)")
            for event in inbound(s):
                if event not in published:
                    flag(s, "warn", f"consumes {event}, which no slice in scope publishes (dangling edge)")
        self.twins()

    def twins(self):
        """`supersedes` pairing: a missing target, a superseded slice still live, a retirement
        outstanding. The pair itself is recorded so writers() does not call it a shared read model."""
        self.twin_pairs = set()
        for s in self.good:
            target = s["supersedes"]
            if not target:
                continue
            old = self.by_id.get(target)
            if old is None:
                flag(s, "warn", f"supersedes '{target}', which is not a slice in scope")
                continue
            self.twin_pairs.add(frozenset((s["id"], old["id"])))
            if old["status"] != "deprecated":
                flag(old, "warn", f"superseded by {s['id']} but status is {old['status']} — a migration "
                     "twin moves the old slice to status: deprecated")
            else:
                flag(old, "info", f"superseded by {s['id']} — retirement outstanding: delete this slice "
                     "once consumers have swapped over")

    def home_bcs(self):
        """Target name → the BCs whose slices write (aggregate) or own (read model) it."""
        home = {}
        for s in self.good:
            for target in s["writes"] + s["owns"]:
                if s["bc"]:
                    home.setdefault(target, set()).add(s["bc"])
        return home

    def flows(self):
        events = {}
        for s in self.good:
            for e in s["publishes"]:
                events.setdefault(e, (set(), set()))[0].add(s["id"])
            for e in inbound(s):
                events.setdefault(e, (set(), set()))[1].add(s["id"])
        out = []
        for event, (pubs, subs) in events.items():
            pub_bcs = sorted({self.by_id[i]["bc"] for i in pubs if self.by_id[i]["bc"]})
            sub_bcs = sorted({self.by_id[i]["bc"] for i in subs if self.by_id[i]["bc"]})
            # An event belongs to the context that publishes it; a dangling one to its first consumer.
            bc = pub_bcs[0] if pub_bcs else (sub_bcs[0] if sub_bcs else None)
            out.append({
                "event": event,
                "bc": bc,
                "from": sorted(pubs),
                "to": sorted(subs),
                "crossContext": len(set(pub_bcs) | set(sub_bcs)) > 1,
                "dangling": not pubs and bool(subs),
            })
        out.sort(key=lambda f: (not f["crossContext"], f["event"]))
        return out

    def writers(self):
        """Aggregates (`writes`) and read models (`owns`) kept apart: several command slices on one
        aggregate inside one BC is the design, two owners of one read model is §R4 unless the pair
        is a declared `supersedes` twin. Ownership crossing a BC boundary is the finding for both."""
        groups = {}
        for s in self.good:
            for kind, targets in (("aggregate", s["writes"]), ("read-model", s["owns"])):
                for t in targets:
                    groups.setdefault((t, kind), []).append(s)
        out = []
        for (target, kind), ss in groups.items():
            bcs = sorted({s["bc"] for s in ss if s["bc"]})
            ids = sorted({s["id"] for s in ss})
            flag_ = None
            if len(bcs) > 1:
                flag_ = "crossBc"
            elif kind == "read-model" and len(ids) > 1 and not self._one_twin_family(ids):
                flag_ = "sharedReadModel"
            out.append({"target": target, "kind": kind, "bcs": bcs, "slices": ids, "flag": flag_})
        out.sort(key=lambda w: (w["flag"] is None, w["target"], w["kind"]))
        return out

    def _one_twin_family(self, ids):
        """True when every owner is linked to the others through `supersedes`."""
        ids = set(ids)
        start = next(iter(sorted(ids)))
        seen, todo = {start}, [start]
        while todo:
            cur = todo.pop()
            for pair in self.twin_pairs:
                if cur in pair:
                    for other in pair:
                        if other in ids and other not in seen:
                            seen.add(other)
                            todo.append(other)
        return seen == ids

    def cross_reads(self):
        home = self.home_bcs()
        out = []
        for s in self.good:
            for r in s["reads"]:
                bcs = home.get(r["name"], set())
                if not bcs or not s["bc"] or s["bc"] in bcs:
                    continue
                r["external"] = True
                for to_bc in sorted(bcs):
                    out.append({"from": s["id"], "toBc": to_bc, "model": r["name"], "via": r["via"],
                                "declared": r["via"] is not None})
        out.sort(key=lambda c: (c["from"], c["toBc"], c["model"]))
        return out

    def contexts(self, notes):
        by_bc = {}
        for s in self.good:
            if s["bc"]:
                by_bc.setdefault(s["bc"], []).append(s)
        out = []
        for bc in sorted(by_bc):
            ss = by_bc[bc]
            row = {"id": bc}
            for key in ("lane", "language", "tier"):
                values = sorted({s[key] for s in ss if s[key]})
                row[key] = values[0] if len(values) == 1 else None
                if len(values) > 1:
                    notes.append(f"{bc}: manifests declare more than one {key} — " + ", ".join(values))
            row["owners"] = sorted({s["owner"] for s in ss if s["owner"]})
            row["sliceIds"] = sorted(s["id"] for s in ss)
            out.append(row)
        return out

    def messages(self):
        """One entry per command and event named in a manifest. Only what a manifest can say is
        filled in; the payload comes from source facts, and stays empty rather than guessed."""
        out = {}

        def entry(name, type_):
            return out.setdefault((name, type_), {
                "name": name, "type": type_, "identity": None, "fields": [], "declaredIn": None,
                "version": None, "schema": None, "idempotencyKey": None, "summary": None})

        for s in self.good:
            for c in s["handles"] + s["dispatches"]:
                entry(c, "command")
            for e in s["publishes"] + inbound(s):
                entry(e, "event")
            for obj in s["_raw_handles"]:
                if isinstance(obj, dict) and isinstance(obj.get("name"), str) and obj.get("idempotencyKey"):
                    entry(obj["name"], "command")["idempotencyKey"] = obj["idempotencyKey"]
            for obj in s["_raw_publishes"]:
                if isinstance(obj, dict) and isinstance(obj.get("name"), str):
                    m = entry(obj["name"], "event")
                    if isinstance(obj.get("version"), int):
                        m["version"] = obj["version"]
                    if isinstance(obj.get("schema"), str):
                        m["schema"] = obj["schema"]
        return [out[k] for k in sorted(out, key=lambda k: (k[1], k[0]))]

    # -- the message graph, exactly as the template's buildGraph() derives it
    def graph(self):
        nodes, edges = {}, []

        def add(nid, **attrs):
            if nid not in nodes:
                nodes[nid] = dict(id=nid, **attrs)
            return nodes[nid]

        for s in self.good:
            add("s:" + s["id"], type="slice", label=s["id"], bc=s["bc"], kind=s["kind"])
            for c in s["handles"]:
                add("c:" + c, type="command", label=c, bc=s["bc"])
                edges.append(("c:" + c, "s:" + s["id"], "command"))
            for e in inbound(s):
                add("e:" + e, type="event", label=e, bc=s["bc"])
                edges.append(("e:" + e, "s:" + s["id"], "event"))
            for e in s["publishes"]:
                add("e:" + e, type="event", label=e, bc=s["bc"])["bc"] = s["bc"]
                edges.append(("s:" + s["id"], "e:" + e, "event"))
            for c in s["dispatches"]:
                add("c:" + c, type="command", label=c, bc=s["bc"])
                edges.append(("s:" + s["id"], "c:" + c, "command"))
            if s["externalSystem"]:
                x = "x:" + s["externalSystem"]
                add(x, type="external", label=s["externalSystem"], bc=s["bc"])
                d = s["direction"] or "both"
                if d in ("inbound", "both"):
                    edges.append((x, "s:" + s["id"], "external"))
                if d in ("outbound", "both"):
                    edges.append(("s:" + s["id"], x, "external"))
        return nodes, edges


def role_floor(node):
    if node["type"] == "slice":
        return 1 if node["kind"] == "command" else 3
    return ROLE_FLOOR[node["type"]]


def ranks(nodes, edges):
    """Longest-path rank over a role floor, cycle-guarded — the template's layoutGraph() rank."""
    inc = {n: [] for n in nodes}
    for a, b, _ in edges:
        if a in inc and b in inc:
            inc[b].append(a)
    done, path = {}, set()

    def floor(n):
        return role_floor(nodes[n])

    def depth(n):
        if n in done:
            return done[n]
        if n in path:
            return 0
        path.add(n)
        d = floor(n)
        for p in inc[n]:
            d = max(d, depth(p) + 1)
        path.discard(n)
        done[n] = d
        return d

    for n in nodes:  # insertion order, as the template iterates g.nodes
        depth(n)
    return done


# ---------------------------------------------------------------- terminal graph


def node_label(node):
    if node["type"] == "slice":
        return f"{node['label']}  [{node['kind'] or '?'}]"
    return f"{node['label']}  ({'external system' if node['type'] == 'external' else node['type']})"


def chains(nodes, edges):
    """Indented chains from every entry point. An entry point has no inbound edge and at least one
    outbound one (a command type nobody dispatches, an inbound external system, a dangling event, a
    slice triggered from outside the manifests). A node is expanded once; a later occurrence says
    where it was expanded, and a node repeated on its own path is a cycle and stops the walk."""
    out_adj = {n: [] for n in nodes}
    indeg = {n: 0 for n in nodes}
    for a, b, _ in edges:
        if b not in out_adj[a]:
            out_adj[a].append(b)
            indeg[b] += 1

    # Heights over the graph with its back edges removed, so a loop neither recurses forever nor
    # makes the ordering depend on where the walk happened to enter it. Back edges are found by one
    # DFS from the nodes with no inbound edge, then from whatever is left, both in label order.
    def by_label(ids):
        return sorted(ids, key=lambda n: (nodes[n]["label"], n))

    back, state = set(), {}
    for root in by_label([n for n in nodes if indeg[n] == 0]) + by_label(nodes):
        if root in state:
            continue
        state[root] = 1
        stack = [(root, iter(by_label(out_adj[root])))]
        while stack:
            n, it = stack[-1]
            c = next(it, None)
            if c is None:
                state[n] = 2
                stack.pop()
            elif state.get(c) == 1:
                back.add((n, c))
            elif c not in state:
                state[c] = 1
                stack.append((c, iter(by_label(out_adj[c]))))

    memo = {}

    def height(n):
        if n not in memo:
            memo[n] = 1  # placeholder; the graph below is acyclic, so it is never read
            memo[n] = 1 + max((1 if (n, c) in back else height(c) for c in out_adj[n]), default=0)
        return memo[n]

    for n in by_label(nodes):
        height(n)

    def order(ids):
        return sorted(ids, key=lambda n: (-memo[n], nodes[n]["label"], n))

    entries = order([n for n in nodes if indeg[n] == 0 and out_adj[n]])
    lines, expanded, reached = [], {}, set()
    cycles = []

    def walk(n, prefix, connector, path, last_bc, entry_label):
        node = nodes[n]
        text = node_label(node)
        bc = last_bc
        if node["type"] == "slice":
            if last_bc is not None and node["bc"] and node["bc"] != last_bc:
                text += f"  ⇢ crosses {last_bc} → {node['bc']}"
            bc = node["bc"] or last_bc
        if n in path:
            lines.append(prefix + connector + text + "  ↺ cycle")
            cycles.append([nodes[p]["label"] for p in path[path.index(n):]] + [node["label"]])
            return
        if n in expanded:
            if out_adj[n]:
                text += f"  ↑ continued under {expanded[n]}"
            lines.append(prefix + connector + text)
            return
        lines.append(prefix + connector + text)
        expanded[n] = entry_label
        reached.add(n)
        kids = order(out_adj[n])
        child_prefix = prefix + ("   " if connector == "└─ " else "│  " if connector else "")
        for i, c in enumerate(kids):
            walk(c, child_prefix, "└─ " if i == len(kids) - 1 else "├─ ", path + [n], bc, entry_label)

    def start(n):
        walk(n, "", "", [], None, nodes[n]["label"])
        lines.append("")

    for e in entries:
        start(e)
    # A component that is all loop has no entry point. Start it where a chain naturally starts —
    # the earliest role (command type, command slice, message, reactor), then by label.
    while True:
        rest = [n for n in nodes if n not in reached and out_adj[n]]
        if not rest:
            break
        start(min(rest, key=lambda n: (role_floor(nodes[n]), nodes[n]["label"], n)))

    isolated = sorted(nodes[n]["label"] for n in nodes if indeg[n] == 0 and not out_adj[n])
    return {
        "entries": [nodes[n]["label"] for n in entries],
        "lines": lines,
        "cycles": cycles,
        "unconnected": isolated,
    }


def graph_text(index, g, ch):
    nodes, edges = g
    out = [f"graph — {len(nodes)} nodes · {len(edges)} edges · {len(ch['entries'])} entry points"
           + (f" · {len(ch['cycles'])} cycle(s)" if ch["cycles"] else ""), ""]
    out.extend(ch["lines"])
    if ch["unconnected"]:
        out.append("In no chain — a view served only over HTTP, or a slice nothing reaches:")
        for label in ch["unconnected"]:
            s = index.by_id.get(label)
            out.append(f"  {label}  [{s['kind'] if s else '?'}]")
        out.append("")
    if index.broken:
        out.append("NOT MAPPED — these manifests did not parse, so their edges are missing:")
        out.extend(f"  {rel}/slice.yaml" for rel in index.broken)
        out.append("")
    return "\n".join(out).rstrip("\n") + "\n"


# ---------------------------------------------------------------- source facts


# slice-source.py --check gates that are slice-map Step 4 divergence checks ("handled events declared",
# "endpoint appears in source"). Every other gate is the audit's, and a map that shows them starts grading.
MAP_GATES = ("11(b)", "6 endpoint route", "6 discriminator")
LANES = {"decider", "aggregate", "service-entity"}


def merge_source_facts(index, facts, messages, contexts):
    """Fold slice-source.py's JSON — the facts document, the --check document, or both — into the
    map. Every key is optional; nothing is re-derived. Paths join on the slice directory relative to
    ROOT, so both scripts must be given the same ROOT; the slice id is the fallback join."""
    rows = [r for r in facts.get("slices", []) if isinstance(r, dict)]
    by_dir = {r["dir"]: r for r in rows if isinstance(r.get("dir"), str)}
    by_id = {r["id"]: r for r in rows if isinstance(r.get("id"), str)}
    by_id.update({r["slice"]: r for r in rows if isinstance(r.get("slice"), str)})
    for s in index.good:
        row = by_dir.get(s["path"]) or by_id.get(s["id"])
        if not row:
            continue
        if isinstance(row.get("package"), str):
            s["package"] = row["package"]
        if isinstance(row.get("files"), list):
            s["files"] = [f for f in row["files"] if isinstance(f, str)]
        if s["kind"] == "view" and isinstance(row.get("readModels"), list):
            s["readModels"] = [{"name": rm.get("name"), "store": rm.get("store"), "columns": rm.get("columns") or []}
                               for rm in row["readModels"] if isinstance(rm, dict)]

    by_key = {(m["name"], m["type"]): m for m in messages}
    for m in facts.get("messages", []):
        if not isinstance(m, dict) or not m.get("name") or m.get("type") not in ("command", "event"):
            continue
        target = by_key.get((m["name"], m["type"]))
        if target is None:
            continue  # the map shows what manifests declare; a type no manifest names is not a node
        for key in ("identity", "fields", "declaredIn", "summary"):
            if m.get(key) is not None:
                target[key] = m[key]
        for key in ("version", "schema", "idempotencyKey"):
            if target.get(key) is None and m.get(key) is not None:
                target[key] = m[key]
        if m.get("found") is False and not target["summary"]:
            target["summary"] = "No declaration in scope — its payload is not known here."

    detected = {b.get("name"): (b.get("lane") or {}).get("detected") for b in facts.get("bcs", [])
                if isinstance(b, dict)}
    for c in contexts:
        if c["lane"] is None and detected.get(c["id"]) in LANES:
            c["lane"] = detected[c["id"]]

    for f in facts.get("findings", []):
        if not isinstance(f, dict) or not str(f.get("gate", "")).startswith(MAP_GATES):
            continue
        s = next((x for x in index.good if f.get("dir") and x["path"] == f["dir"]), None)
        if s is None and f.get("slice"):
            s = index.by_id.get(f["slice"])
        if s is None:
            continue
        where = f" ({f['file']}:{f['line']})" if f.get("file") and f.get("line") else ""
        gate = f"{f['gate']}: " if f.get("gate") else ""
        flag(s, "warn", f"{gate}{f.get('message', '')}{where}")

    # A check that had no source to run against must not read as a pass.
    for u in facts.get("unverified", []):
        if not isinstance(u, dict) or not str(u.get("gate", "")).startswith(MAP_GATES):
            continue
        s = index.by_id.get(u.get("slice"))
        if s is not None:
            flag(s, "info", f"not checked — {u['gate']}: {u.get('reason', '')}")


# ---------------------------------------------------------------- commands


def git_sha(root: Path):
    try:
        r = subprocess.run(["git", "-C", str(root), "rev-parse", "--short", "HEAD"],
                           capture_output=True, text=True, timeout=10)
    except (OSError, subprocess.SubprocessError):
        return "not-versioned"
    return r.stdout.strip() if r.returncode == 0 and r.stdout.strip() else "not-versioned"


def build_index(args):
    root = Path(args.root)
    if not root.is_dir():
        raise UsageError(f"not a directory: {root}")
    try:
        import yaml  # noqa: F401
    except ImportError:
        raise UsageError("`pyyaml` is not installed. Run through uv: uv run --script " + sys.argv[0])
    loaded = load(root.resolve(), getattr(args, "repair_braced_paths", False))
    if not loaded:
        return None
    index = Index(loaded)
    index.divergence()
    return index


def build_map(index, args):
    root = Path(args.root)
    notes = []
    messages = index.messages()
    contexts = index.contexts(notes)
    if args.source_facts or args.source_check:
        args.source_facts = args.source_facts or []
        for source in args.source_facts + (args.source_check or []):
            try:
                text = sys.stdin.read() if source == "-" else Path(source).read_text(encoding="utf-8")
                facts = json.loads(text)
            except (OSError, ValueError) as exc:
                raise UsageError(f"cannot read --source-facts {source}: {exc}")
            if not isinstance(facts, dict):
                raise UsageError(f"--source-facts {source} must hold a JSON object")
            merge_source_facts(index, facts, messages, contexts)
    else:
        notes.append("package, files, message payloads and read-model columns come from source and were "
                     "not read (no --source-facts)")
    if index.repaired:
        notes.append("mapped from an in-memory corrected copy of " + ", ".join(
            f"{r}/slice.yaml" for r in index.repaired) + " — the files on disk do not parse")
    if index.broken:
        notes.append("NOT MAPPED — did not parse: " + ", ".join(f"{r}/slice.yaml" for r in index.broken))

    flows = index.flows()
    writers = index.writers()
    cross = index.cross_reads()

    slices = []
    for s in index.slices:
        row = {k: v for k, v in s.items() if not k.startswith("_")}
        if row["kind"] != "view":
            row.pop("readModels", None)
        elif "readModels" not in row:
            row["readModels"] = []
        slices.append(row)

    if args.bc:
        keep = {s["id"] for s in index.good if s["bc"] == args.bc}
        if not keep:
            raise UsageError(f"no slice declares bc: {args.bc}")
        slices = [s for s in slices if s["id"] in keep]
        contexts = [c for c in contexts if c["id"] == args.bc]
        flows = [f for f in flows if f["bc"] == args.bc or keep & set(f["from"] + f["to"])]
        writers = [w for w in writers if args.bc in w["bcs"]]
        cross = [c for c in cross if c["from"] in keep or c["toBc"] == args.bc]
        names_in_scope = {n for s in slices for n in s["handles"] + s["dispatches"] + s["publishes"] + inbound(s)}
        messages = [m for m in messages if m["name"] in names_in_scope]

    if args.root_label is not None:
        label = args.root_label
    else:
        try:
            label = Path(os.path.relpath(root.resolve(), Path.cwd())).as_posix()
        except ValueError:
            label = root.resolve().as_posix()
        if label.startswith(".."):
            label = root.resolve().as_posix()
    return {
        "meta": {
            "root": label,
            "sha": args.sha or git_sha(root),
            "project": args.project or root.resolve().name,
            "scope": f"bc:{args.bc}" if args.bc else "full",
            "sliceCount": len(slices),
            "bcCount": len(contexts),
            "skipped": list(args.skipped or []),
        },
        "contexts": contexts,
        "slices": slices,
        "messages": messages,
        "flows": flows,
        "writers": writers,
        "crossReads": cross,
        "notes": notes,
    }


def cmd_map(args):
    index = build_index(args)
    if index is None:
        return no_manifests(args)
    json.dump(build_map(index, args), sys.stdout, indent=2, ensure_ascii=False)
    sys.stdout.write("\n")
    return 1 if index.broken else 0


def cmd_html(args):
    index = build_index(args)
    if index is None:
        return no_manifests(args)
    if index.broken:
        print("slice-index: refusing to render an incomplete map — these manifests did not parse:",
              file=sys.stderr)
        for rel in index.broken:
            print(f"  {rel}/slice.yaml", file=sys.stderr)
        return 1
    template = Path(args.template) if args.template else (
        Path(__file__).resolve().parent.parent / "references" / "slice" / "slice-map-template.html")
    try:
        page = template.read_text(encoding="utf-8")
    except OSError as exc:
        raise UsageError(f"cannot read template {template}: {exc}")
    if page.count(PLACEHOLDER) != 1:
        raise UsageError(f"template {template} does not carry the placeholder line exactly once: {PLACEHOLDER}")
    data = json.dumps(build_map(index, args), ensure_ascii=False).replace("</", "<\\/")
    Path(args.out).write_text(page.replace(PLACEHOLDER, "const SLICE_MAP = " + data + ";"), encoding="utf-8")
    print(f"slice-index: wrote {args.out}", file=sys.stderr)
    return 0


def cmd_graph(args):
    index = build_index(args)
    if index is None:
        return no_manifests(args)
    subset = index
    if args.bc:
        keep = [s for s in index.good if s["bc"] == args.bc]
        if not keep:
            raise UsageError(f"no slice declares bc: {args.bc}")
        subset = _Sub(index, keep)
    nodes, edges = subset.graph()
    ch = chains(nodes, edges)
    if args.json:
        rk = ranks(nodes, edges)
        json.dump({
            "nodes": [{"id": n, "type": v["type"], "label": v["label"], "bc": v["bc"],
                       "kind": v.get("kind"), "rank": rk[n]} for n, v in nodes.items()],
            "edges": [{"from": a, "to": b, "kind": k} for a, b, k in edges],
            "entries": ch["entries"],
            "cycles": ch["cycles"],
            "unconnected": ch["unconnected"],
            "notMapped": index.broken,
        }, sys.stdout, indent=2, ensure_ascii=False)
        sys.stdout.write("\n")
    else:
        sys.stdout.write(graph_text(index, (nodes, edges), ch))
    return 1 if index.broken else 0


class _Sub:
    """An Index restricted to some slices — for graph --bc."""

    def __init__(self, index, keep):
        self.good = keep
        self.by_id = {s["id"]: s for s in keep}
        self.broken = index.broken

    graph = Index.graph


def route_matches(declared, asked):
    d = declared.split("?", 1)[0].rstrip("/") or "/"
    a = asked.split("?", 1)[0].rstrip("/") or "/"
    if d == a:
        return "exact"
    ds, as_ = d.split("/"), a.split("/")
    if len(ds) == len(as_) and all(x == y or (x.startswith("{") and x.endswith("}")) for x, y in zip(ds, as_)):
        return "template"
    if d.startswith(a):
        return "prefix"
    return None


def cmd_query(args):
    index = build_index(args)
    if index is None:
        return no_manifests(args)
    want = args.name.casefold()
    hits = []

    def hit(s, field, match=None, extra=None):
        row = {"slice": s["id"], "bc": s["bc"], "kind": s["kind"], "status": s["status"],
               "path": s["path"], "field": field}
        if match:
            row["match"] = match
        if extra:
            row.update(extra)
        hits.append(row)

    fields = {
        "who-handles": lambda s: [("handles", s["handles"])],
        "who-dispatches": lambda s: [("dispatches", s["dispatches"])],
        "who-serves": lambda s: [("serves", s["serves"])],
        "who-publishes": lambda s: [("publishes", s["publishes"])],
        "who-reacts": lambda s: [("consumes", s["consumes"]),
                                 ("projections[].from", [n for p in s["projections"] for n in p["from"]])],
        "who-writes": lambda s: [("writes", s["writes"]), ("owns", s["owns"])],
        "who-reads": lambda s: [("reads", [r["name"] for r in s["reads"]])],
    }
    if args.what in fields:
        for s in index.good:
            for field, values in fields[args.what](s):
                if any(v.casefold() == want for v in values):
                    extra = None
                    if field == "reads":
                        via = next(r["via"] for r in s["reads"] if r["name"].casefold() == want)
                        extra = {"via": via}
                    hit(s, field, extra=extra)
    elif args.what == "who-owns-endpoint":
        found = []
        for s in index.good:
            for e in s["endpoints"]:
                if args.method and (e["method"] or "").upper() != args.method.upper():
                    continue
                m = route_matches(e["path"], args.name)
                if m:
                    found.append((m, s, e))
        best = min((("exact", "template", "prefix").index(m) for m, _, _ in found), default=None)
        for m, s, e in found:
            if ("exact", "template", "prefix").index(m) == best:
                hit(s, "endpoints", m, {"method": e["method"], "endpoint": e["path"], "auth": e["auth"]})
    elif args.what == "slice":
        for s in index.good:
            if s["id"].casefold() == want:
                hits.append({k: v for k, v in s.items() if not k.startswith("_")})
    else:
        raise UsageError(f"unknown query: {args.what}")

    hits.sort(key=lambda h: (h.get("slice", h.get("id")), h.get("field", ""), h.get("endpoint", "")))
    if args.json:
        json.dump({"query": args.what, "name": args.name, "hits": hits, "notMapped": index.broken},
                  sys.stdout, indent=2, ensure_ascii=False)
        sys.stdout.write("\n")
    else:
        for h in hits:
            if args.what == "slice":
                print(json.dumps(h, indent=2, ensure_ascii=False))
                continue
            tail = ""
            if "endpoint" in h:
                tail = f"  {h['method']} {h['endpoint']}  ({h['match']})"
            elif "via" in h:
                tail = f"  via {h['via']}" if h["via"] else "  (no via)"
            print(f"{h['slice']}  {h['kind']}  {h['field']}{tail}  {h['path']}")
        if not hits:
            print(f"no slice matches {args.what} {args.name}", file=sys.stderr)
        if index.broken:
            print("NOT SEARCHED — did not parse: " + ", ".join(f"{r}/slice.yaml" for r in index.broken),
                  file=sys.stderr)
    if index.broken:
        return 1
    return 0 if hits else 1


def no_manifests(args):
    print(f"slice-index: no slice.yaml under {args.root} — nothing is declared, so there is nothing to "
          "map. /essentials:slice-discover infers structure for that case.", file=sys.stderr)
    return 3


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="slice-index",
        description="slice-map's manifest algebra: the map JSON, the page, the terminal graph and the "
                    "locate queries. Reads slice.yaml only; writes nothing but --out.")
    sub = parser.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("root", nargs="?", default=".", help="directory to scan (default: .)")
        p.add_argument("--bc", help="restrict the output to one bounded context")
        p.add_argument("--repair-braced-paths", action="store_true",
                       help="parse an in-memory copy with unquoted braced paths quoted; always announced")

    def map_opts(p):
        common(p)
        p.add_argument("--source-facts", metavar="FILE", action="append",
                       help="slice-source.py --json output to merge ('-' = stdin)")
        p.add_argument("--source-check", metavar="FILE", action="append",
                       help="slice-source.py --check --json output to merge; its 11(b) and 6 endpoint route / "
                            "discriminator findings become flags, the rest is the audit's")
        p.add_argument("--sha", help="provenance sha (default: git rev-parse --short HEAD)")
        p.add_argument("--project", help="project name (default: ROOT's directory name)")
        p.add_argument("--root-label", help="meta.root as shown (default: ROOT relative to the cwd)")
        p.add_argument("--skipped", action="append", metavar="TEXT", help="a scope exclusion to list")

    p = sub.add_parser("map", help="the slice-map data contract as JSON")
    map_opts(p)
    p.set_defaults(fn=cmd_map)

    p = sub.add_parser("html", help="the slice-map page, written to --out")
    map_opts(p)
    p.add_argument("--out", required=True, help="page to write — a path the user chose")
    p.add_argument("--template", help="slice-map-template.html (default: the plugin's)")
    p.set_defaults(fn=cmd_html)

    p = sub.add_parser("graph", help="terminal --view graph")
    common(p)
    p.add_argument("--json", action="store_true", help="nodes, edges, ranks, entries and cycles as JSON")
    p.set_defaults(fn=cmd_graph)

    p = sub.add_parser("query", help="locate slices from manifests")
    p.add_argument("root", nargs="?", default=".")
    p.add_argument("what", choices=["who-handles", "who-dispatches", "who-serves", "who-publishes",
                                    "who-reacts", "who-writes", "who-reads", "who-owns-endpoint", "slice"])
    p.add_argument("name")
    p.add_argument("--method", help="who-owns-endpoint: restrict to one HTTP method")
    p.add_argument("--json", action="store_true")
    p.set_defaults(fn=cmd_query, repair_braced_paths=False)

    args = parser.parse_args(argv)
    sys.setrecursionlimit(100_000)  # the graph walks recurse once per node on the longest chain
    try:
        return args.fn(args)
    except UsageError as exc:
        print(f"slice-index: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
