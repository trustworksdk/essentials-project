#!/usr/bin/env python3
"""slice_atlas_layout — every coordinate the Slice Atlas page draws, computed once, in Python.

Why this exists
---------------
The Atlas page (`references/slice/atlas/`) only draws, filters and navigates; it derives nothing.
Layout lives here so it is golden-tested, identical on every run, and laid out once over the union
of a before and an after map when the Diff lens is on (design `docs/slice-atlas-design.md` §4, §9.3).
`slice_atlas.py` calls `compute()` and stores the result under the data's `layout` key.

    import slice_atlas_layout
    layout = slice_atlas_layout.compute(data)   # data: the Atlas data JSON without `layout`

`compute` is a pure function of its argument: no randomness, no clock, no dependence on dict or set
iteration order (every sort has a total key ending in an id). It reads `slices`, `processes`,
`writers`, `flows` and `diff`; any of them may be missing or empty. It never mutates its input.

What it returns (one coordinate system per lens, absolute user-space px, integers)
-------------------------------------------------------------------------------
    { "version": 1, "atlas": {...}, "metro": {...}, "matrix": {...} }

atlas — the board (design §8, §10.1)
    Contexts are cells, shelf-packed in id order. Each holds one **segment** per process that has
    slices in it (`<processId>@<bc>`), stacked in process order: declared first, then by id. A
    segment is an Event Modeling board: one column per slice in causal order; lanes top to bottom
    `trigger`, `action` (command · view), `stream:<Target>` (sorted), `events`, `external`; and a
    scenario band under the lanes. Cards per kind:

      command      trigger card (first endpoint) · a command card per `handles` · an event card per
                   `publishes` in `stream:<first writes>` (else `events`)
      view         trigger card (first GET endpoint, else first endpoint) · view card named after the
                   first `owns` (else the slice name) in `action`
      automation   automation card in `trigger`, named by its schedule (`cron`, `every <fixedDelay>`)
                   when the row carries one, else the slice name · a command card per `dispatches`
                   that no other slice handles
      translation  external card — `trigger` lane when inbound or both, `external` lane when outbound ·
                   command cards for `handles`, and for each `dispatches` no other slice handles ·
                   event cards for `publishes`
      broken row   one `error` card in `action`

    The trigger card of an endpoint is named after the first endpoint (`POST /api/orders`); the
    page lists the rest from the slice row.

    Edges: trigger → command `handles` (→ the view card for a view's endpoint); command → event
    `publishes`; event → each consumer's main card `feeds` (consumers = `consumes` ∪
    `projections[].from`); a command another slice handles is drawn once, in the handler's column:
    the dispatcher — an automation card, an inbound external card, a command slice's first command
    card — gets a `dispatches` edge to the handler's command card, never a copy of the command in its
    own column. A dispatched command no slice handles gets a card in the dispatcher's column, with an
    edge (`dispatches`, `external` from an inbound external card); an inbound external card → what it dispatches (and
    what it publishes, when it handles nothing), and an outbound translation's handled command →
    its external card, `external`. A cross-slice edge targets the column in the same segment when
    the target slice has one there, else the target's full column; `cross` is true when the two
    ends sit in different segments. Main card: view, automation, external, first command, error.

    A slice listed in several processes gets its full column in the first process (declared first,
    then by id) and a one-card `portal` column (its main card, `portal: true`) in every other. A
    slice listed in no process gets a segment of process `~unattached:<bc>`, the id the data side
    uses for that group. A row with no `bc` (a broken manifest) takes the one context of the
    process listing it, else the directory above its kind directory, else `?`. When `data.diff` is
    set, each removed slice (`diff.slices.removed`, its full before-row) is laid out too,
    `ghost: true`, so before and after share coordinates: in a declared process named in its
    before-row `flows` when that process exists; else in the first process with a slice in the same
    context that shares a message with it; else in `~unattached:<bc>`.

    Causal order inside a segment is the longest-path rank with role floors of `slice-index.py`
    (`ranks`, `role_floor`; `commands/slice-map.md` Step 3) — a loop is cut at the edge that closes
    it, see ranks() — computed over the segment's own slices and the messages they name — per segment, so a slice added in one context never reorders a
    board in another. Columns sort by (rank, slice id).

    Context cells are snapped up to a size step (GRID x 1..7, then 8, 10, 12, 14, 16, 20, … — at
    most 25 % apart), so a slice added to a context moves nothing outside it unless that cell
    crosses a step; then only cells after it in packing order translate, rigidly, and no board
    outside the context changes shape. `tests/scripts/test_slice_atlas_layout.py` measures how
    often that happens on the 60-context estate. A slice in a context that did not exist is a new
    cell, placed in id order.

    `links` aggregate the cross-segment `feeds` edges per (from segment, to segment) with the event
    names; `bridges` aggregate `flows[]` per (publishing context, consuming context).
    `scenarioBoxes` reserve the space under each full column for its scenario stacks: height from
    the scenario count; NO_SCENARIO_H when there are none, the box in which the page draws
    "no scenarios declared", the invariants and the test kinds (design §8). Every full column gets
    a box of positive height, so whatever the page draws under a column is inside its box and
    inside the segment.

metro (design §10.5)
    One line per process, one station per slice, x by the global causal rank (the same rank over
    every live slice), one horizontal track per line in process order. A slice in several
    processes is an interchange: it sits on its first line's track and the other lines bend to it
    in 45° segments (straight where the gap is too short for 45°). Stations of one rank take slots
    left to right, a slot never shared by two stations of the same line.

matrix (design §10.4)
    Rows: `writers[]` targets plus any read target no writer names, by (context, aggregate before
    read model, name). Columns: live slices by (context, kind, id). Cells `W` writes, `O` owns,
    `R` reads, `R·via` a read through a reader interface, `R!` a cross-context read without one;
    `cross` when the column's context is not among the row's. `bcMatrix` counts the events each
    context publishes that a context consumes (diagonal included), from `flows[]`.

Constants
---------
Card, lane and gap sizes are the module constants below. The page reads none of them: it draws the
boxes it is given.
"""

from __future__ import annotations

import zlib

LAYOUT_VERSION = 1

# -- cards and columns (a sticky is CARD_W x CARD_H; a column pads it by COL_PAD each side)
CARD_W = 168
CARD_H = 88            # caption, name and two detail lines (lens-1-atlas.js drawCard)
CARD_GAP = 12          # vertical gap between stacked cards in one lane of one column
COL_PAD = 12
COL_W = CARD_W + 2 * COL_PAD
COL_GAP = 16

# -- lanes and segments
LANE_PAD = 12          # above the first and below the last card of a lane
LANE_LABEL_W = 112     # left gutter carrying the lane labels
SEG_HEADER_H = 36      # process name strip
SEG_PAD = 16
SEG_GAP = 24           # between stacked segments of one context

# -- scenario band (design §8: given / when / then stacks under each column at L3)
SCENARIO_H = 132       # one scenario stack
SCENARIO_GAP = 8
BAND_GAP = 12          # lanes to band
NO_SCENARIO_H = 64     # box of a column that declares none: "no scenarios declared", then invariants
                       # and test kinds — three 12.5-unit lines at the page's 1.42 line height, plus padding

# -- contexts
CTX_HEADER_H = 48
CTX_PAD = 24
CTX_GAP = 96           # between context cells, both axes
GRID = 256             # size-step unit of a context cell (snap())
SHELF_MIN_W = 4096
SHELF_PER_CTX_W = 2048  # shelf width = max(SHELF_MIN_W, ceil(sqrt(contexts)) x this, widest cell)
MARGIN = 64            # around the whole board

# -- metro
METRO_SLOT = 56        # between stations of one rank on one track
METRO_RANK_GAP = 24    # extra space between ranks
METRO_TRACK_DY = 40
METRO_PAD = 40

LANE_ORDER = ("trigger", "action")  # then stream:* sorted, events, external
KIND_ORDER = {"command": 0, "view": 1, "automation": 2, "translation": 3}
KIND_DIRS = {"use_cases", "views", "automations", "external_systems"}
ROLE_FLOOR = {"command": 0, "event": 2, "external": 2}  # slice-index.py ROLE_FLOOR
UNATTACHED = "~unattached:"


# ---------------------------------------------------------------- small helpers


def _strs(value):
    """The strings in value (a list or a single value); anything else dropped."""
    if value is None:
        return []
    items = value if isinstance(value, list) else [value]
    return [v for v in items if isinstance(v, str)]


def _inbound(s):
    """`consumes` ∪ `projections[].from`, in declaration order — slice-index.py inbound()."""
    froms = []
    for p in s.get("projections") or []:
        if isinstance(p, dict):
            froms += _strs(p.get("from"))
    seen, out = set(), []
    for name in _strs(s.get("consumes")) + froms:
        if name not in seen:
            seen.add(name)
            out.append(name)
    return out


def _lane_label(lane):
    if lane == "trigger":
        return "Trigger"
    if lane == "action":
        return "Command · View"
    if lane.startswith("stream:"):
        return "Events · " + lane[len("stream:"):]
    return "Events" if lane == "events" else "External"


def _lane_key(lane):
    if lane in LANE_ORDER:
        return (LANE_ORDER.index(lane), "")
    if lane.startswith("stream:"):
        return (2, lane)
    return (3, "") if lane == "events" else (4, lane)


def _color(pid):
    return zlib.crc32(pid.encode("utf-8")) % 12


def snap(v):
    """v rounded up to a context-cell size step: GRID x n for n in 1..7, then n = m x 2^k with m in
    4..7 (8, 10, 12, 14, 16, 20, …) — at most 25 % between steps, so a cell absorbs most growth."""
    units = max(1, -(-int(v) // GRID))
    if units > 7:
        e = units.bit_length() - 3
        m = -(-units // (1 << e))
        if m > 7:
            m, e = 4, e + 1
        units = m << e
    return units * GRID


def _endpoint_text(e):
    method = e.get("method") if isinstance(e.get("method"), str) else None
    path = e.get("path") if isinstance(e.get("path"), str) else ""
    return f"{method} {path}".strip() if method else (path or "endpoint")


def _trigger_text(s, prefer_get):
    eps = [e for e in s.get("endpoints") or [] if isinstance(e, dict)]
    if not eps:
        return None
    if prefer_get:
        eps = [e for e in eps if str(e.get("method") or "").upper() == "GET"] or eps
    return _endpoint_text(eps[0])


def _schedule_text(s):
    sched = s.get("schedule")
    if isinstance(sched, dict):
        if isinstance(sched.get("cron"), str):
            return sched["cron"]
        if isinstance(sched.get("fixedDelay"), str):
            return "every " + sched["fixedDelay"]
    return None


def _guess_bc(s):
    """The context of a row that declares none (a broken manifest): the directory above its kind
    directory, `<bc>/use_cases/<name>`."""
    parts = str(s.get("path") or "").split("/")
    for i in range(len(parts) - 1, 0, -1):
        if parts[i] in KIND_DIRS:
            return parts[i - 1]
    return None


# ---------------------------------------------------------------- one column's cards


def column_cards(s, handled_elsewhere=lambda name: False):
    """The cards of a slice's full column: (cards, main, edges, dispatched).
    cards: {(role, name): lane} in insertion order; main: the (role, name) that edges from other
    slices target; edges: [(from key, to key, kind)] inside the column; dispatched: [(command name,
    key of the card its cross-slice edge starts at)] for each dispatched command another slice
    handles — `handled_elsewhere(name)` — which gets no card here: one command, one card."""
    cards = {}
    edges = []
    dispatched = []

    def add(role, name, lane):
        cards.setdefault((role, name), lane)
        return (role, name)

    def edge(a, b, kind):
        if a != b and (a, b, kind) not in edges:
            edges.append((a, b, kind))

    name = s["name"] if isinstance(s.get("name"), str) else str(s.get("id"))
    if s.get("error"):
        return cards, add("error", name, "action"), edges, dispatched

    def dispatch(src, c, kind):
        if c in handles or not handled_elsewhere(c):
            edge(src, add("command", c, "action"), kind)
        else:
            dispatched.append((c, src))

    kind = s.get("kind")
    handles = _strs(s.get("handles"))
    dispatches = _strs(s.get("dispatches"))
    publishes = _strs(s.get("publishes"))
    writes = _strs(s.get("writes"))
    owns = _strs(s.get("owns"))
    ev_lane = "stream:" + writes[0] if writes else "events"

    if kind in ("view", "automation"):
        if kind == "view":
            trig = _trigger_text(s, prefer_get=True)
            t = add("trigger", trig, "trigger") if trig else None
            main = add("view", owns[0] if owns else name, "action")
            if t:
                edge(t, main, "handles")
        else:
            main = add("automation", _schedule_text(s) or name, "trigger")
        cmds = [add("command", c, "action") for c in handles]
        for c in dispatches:
            dispatch(main, c, "dispatches")
        for e in publishes:
            ek = add("event", e, ev_lane)
            for c in cmds or [main]:
                edge(c, ek, "publishes")
        return cards, main, edges, dispatched

    if kind == "translation":
        inbound = (s.get("direction") or "both") in ("inbound", "both")
        system = s["externalSystem"] if isinstance(s.get("externalSystem"), str) else name
        main = add("external", system, "trigger" if inbound else "external")
        cmds = [add("command", c, "action") for c in handles]
        for c in dispatches:
            if inbound:
                dispatch(main, c, "external")
            else:
                dispatch(cmds[0] if cmds else main, c, "dispatches")
        for e in publishes:
            ek = add("event", e, ev_lane)
            for c in cmds:
                edge(c, ek, "publishes")
            if not cmds and inbound:
                edge(main, ek, "external")
        if not inbound:
            for c in cmds:
                edge(c, main, "external")
        return cards, main, edges, dispatched

    # command — and a kind this module does not know, drawn as one
    trig = _trigger_text(s, prefer_get=False)
    t = add("trigger", trig, "trigger") if trig else None
    cmds = [add("command", c, "action") for c in handles] or [add("command", name, "action")]
    if t:
        for c in cmds:
            edge(t, c, "handles")
    for c in dispatches:
        dispatch(cmds[0], c, "dispatches")
    for e in publishes:
        ek = add("event", e, ev_lane)
        for c in cmds:
            edge(c, ek, "publishes")
    return cards, cmds[0], edges, dispatched


# ---------------------------------------------------------------- causal rank (slice-index.py)


def message_graph(slices):
    """slice-index.py Index.graph(): nodes in insertion order, edges (from, to)."""
    nodes, edges = {}, []

    def add(nid, type_, kind=None):
        if nid not in nodes:
            nodes[nid] = {"type": type_, "kind": kind}

    for s in slices:
        sid = "s:" + s["id"]
        add(sid, "slice", s.get("kind"))
        for c in _strs(s.get("handles")):
            add("c:" + c, "command")
            edges.append(("c:" + c, sid))
        for e in _inbound(s):
            add("e:" + e, "event")
            edges.append(("e:" + e, sid))
        for e in _strs(s.get("publishes")):
            add("e:" + e, "event")
            edges.append((sid, "e:" + e))
        for c in _strs(s.get("dispatches")):
            add("c:" + c, "command")
            edges.append((sid, "c:" + c))
        system = s.get("externalSystem")
        if isinstance(system, str) and system:
            add("x:" + system, "external")
            d = s.get("direction") or "both"
            if d in ("inbound", "both"):
                edges.append(("x:" + system, sid))
            if d in ("outbound", "both"):
                edges.append((sid, "x:" + system))
    return nodes, edges


def ranks(nodes, edges):
    """Longest-path rank over a role floor (slice-index.py ranks(), `commands/slice-map.md` Step 3),
    with the cycle cut where the loop closes rather than where a walk happened to enter it: back
    edges are found by one forward DFS from the nodes with no inbound edge, then from the rest by
    (floor, id) — so a pure loop starts at its command — and ranks are longest paths over what is
    left. Independent of the order nodes and edges arrive in."""
    succ = {n: set() for n in nodes}
    for a, b in edges:
        if a in succ and b in succ and a != b:
            succ[a].add(b)
    pred = {n: set() for n in nodes}
    for a, bs in succ.items():
        for b in bs:
            pred[b].add(a)

    def floor(n):
        node = nodes[n]
        if node["type"] == "slice":
            return 1 if node["kind"] == "command" else 3
        return ROLE_FLOOR[node["type"]]

    back, state = set(), {}
    for root in sorted(n for n in nodes if not pred[n]) + sorted(nodes, key=lambda n: (floor(n), n)):
        if root in state:
            continue
        state[root] = 1
        stack = [(root, iter(sorted(succ[root])))]
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
                stack.append((c, iter(sorted(succ[c]))))

    indeg = {n: sum(1 for p in pred[n] if (p, n) not in back) for n in nodes}
    rank = {n: floor(n) for n in nodes}
    ready = sorted(n for n in nodes if indeg[n] == 0)
    while ready:
        n = ready.pop()
        for c in sorted(succ[n]):
            if (n, c) in back:
                continue
            rank[c] = max(rank[c], rank[n] + 1)
            indeg[c] -= 1
            if indeg[c] == 0:
                ready.append(c)
    return rank


def slice_ranks(slices):
    """{slice id: causal rank} over the message graph of `slices`."""
    nodes, edges = message_graph(sorted(slices, key=lambda s: s["id"]))
    r = ranks(nodes, edges)
    return {s["id"]: r["s:" + s["id"]] for s in slices}


# ---------------------------------------------------------------- slices, processes, columns


def _process_key(declared, pid):
    return (not declared, pid)


def _shares_message(a, b):
    return bool(set(_strs(a.get("publishes"))) & set(_inbound(b))
                or set(_strs(b.get("publishes"))) & set(_inbound(a))
                or set(_strs(a.get("dispatches"))) & set(_strs(b.get("handles")))
                or set(_strs(b.get("dispatches"))) & set(_strs(a.get("handles"))))


class _Assigned:
    """Rows, ghosts, each row's context, the processes (data's plus synthetic `~unattached:<bc>`),
    and every column (process id, bc, slice id, portal, ghost)."""

    def __init__(self, data):
        self.rows, seen = [], set()
        for s in data.get("slices") or []:
            if isinstance(s, dict) and s.get("id") is not None and str(s["id"]) not in seen:
                seen.add(str(s["id"]))
                self.rows.append(dict(s, id=str(s["id"])))
        self.ghosts = []
        diff = data.get("diff")
        removed = ((diff.get("slices") or {}).get("removed") or []) if isinstance(diff, dict) else []
        for s in removed:
            if isinstance(s, dict) and s.get("id") is not None and str(s["id"]) not in seen:
                seen.add(str(s["id"]))
                self.ghosts.append(dict(s, id=str(s["id"])))
        self.by_id = {s["id"]: s for s in self.rows + self.ghosts}
        self.live = [s for s in self.rows if not s.get("error")]

        procs = {}
        for p in data.get("processes") or []:
            if isinstance(p, dict) and isinstance(p.get("id"), str) and p["id"] not in procs:
                procs[p["id"]] = {"id": p["id"], "declared": bool(p.get("declared")),
                                  "color": p["color"] if isinstance(p.get("color"), int) else _color(p["id"]),
                                  "bcs": _strs(p.get("bcs")),
                                  "sliceIds": [i for i in _strs(p.get("sliceIds")) if i in self.by_id]}
        order = sorted(procs.values(), key=lambda p: _process_key(p["declared"], p["id"]))

        bc = {}
        for s in self.rows + self.ghosts:
            if isinstance(s.get("bc"), str) and s["bc"]:
                bc[s["id"]] = s["bc"]
        for p in order:
            if len(p["bcs"]) == 1:
                for sid in p["sliceIds"]:
                    bc.setdefault(sid, p["bcs"][0])
        for s in self.rows + self.ghosts:
            bc.setdefault(s["id"], _guess_bc(s) or "?")
        self.bc = bc

        def synth(pid, b):
            if pid not in procs:
                procs[pid] = {"id": pid, "declared": False, "color": _color(pid), "bcs": [b], "sliceIds": []}

        live_ids = {s["id"] for s in self.rows}
        member = {}
        for p in order:
            for sid in p["sliceIds"]:
                if sid in live_ids and p["id"] not in member.setdefault(sid, []):
                    member[sid].append(p["id"])
        for s in self.rows:
            if not member.get(s["id"]):
                member[s["id"]] = [UNATTACHED + bc[s["id"]]]
                synth(UNATTACHED + bc[s["id"]], bc[s["id"]])

        ghost_proc = {}
        for g in sorted(self.ghosts, key=lambda s: s["id"]):
            b = bc[g["id"]]
            pick = next((f for f in _strs(g.get("flows")) if f in procs and procs[f]["declared"]), None)
            if pick is None:
                for p in order:
                    if any(p["id"] in member.get(i, []) and bc[i] == b and _shares_message(g, self.by_id[i])
                           for i in sorted(member)):
                        pick = p["id"]
                        break
            if pick is None:
                pick = UNATTACHED + b
                synth(pick, b)
            ghost_proc[g["id"]] = pick

        self.procs = procs
        self.member = member
        self.columns = []
        for s in self.rows:
            ps = sorted(member[s["id"]], key=lambda pid: _process_key(procs[pid]["declared"], pid))
            for i, pid in enumerate(ps):
                self.columns.append((pid, bc[s["id"]], s["id"], i > 0, False))
        for g in self.ghosts:
            self.columns.append((ghost_proc[g["id"]], bc[g["id"]], g["id"], False, True))

    def pkey(self, pid):
        return _process_key(self.procs[pid]["declared"], pid)


# ---------------------------------------------------------------- the atlas board


class _Segment:
    """One segment laid out at origin (0, 0), then shifted into place."""

    def __init__(self, seg_id, pid, b, cols, by_id, handlers):
        flags = {sid: (portal, ghost) for sid, portal, ghost in cols}
        rank = slice_ranks([by_id[sid] for sid in flags])
        order = sorted(flags, key=lambda i: (rank[i], i))

        plan = []
        for sid in order:
            portal, ghost = flags[sid]
            cards, main, edges, dispatched = column_cards(
                by_id[sid], lambda c, sid=sid: any(h != sid for h in handlers.get(c, ())))
            if portal:
                cards, edges, dispatched = {main: cards[main]}, [], []
            plan.append((sid, portal, ghost, cards, edges, main, dispatched))

        used = {"trigger", "action"}
        depth = {}
        for _, _, _, cards, _, _, _ in plan:
            per = {}
            for lane in cards.values():
                per[lane] = per.get(lane, 0) + 1
            used.update(per)
            for lane, n in per.items():
                depth[lane] = max(depth.get(lane, 1), n)
        lanes, y = [], SEG_HEADER_H
        for lane in sorted(used, key=_lane_key):
            n = depth.get(lane, 1)
            h = 2 * LANE_PAD + n * CARD_H + (n - 1) * CARD_GAP
            lanes.append({"id": lane, "label": _lane_label(lane), "y": y, "h": h})
            y += h
        lane_y = {row["id"]: row["y"] for row in lanes}
        lanes_bottom = y

        self.columns, self.cards, self.boxes, self.internal = [], [], [], []
        self.mains, self.cmd_cards, self.event_cards, self.dispatch_out = {}, {}, [], []
        band = 0
        for i, (sid, portal, ghost, cards, edges, main, dispatched) in enumerate(plan):
            cx = LANE_LABEL_W + SEG_PAD + i * (COL_W + COL_GAP)
            self.columns.append({"slice": sid, "x": cx, "w": COL_W, "portal": portal})
            stack, ids = {}, {}
            for (role, name), lane in cards.items():
                k = stack.get(lane, 0)
                stack[lane] = k + 1
                cid = f"{seg_id}|{sid}|{role}|{name}"
                ids[(role, name)] = cid
                self.cards.append({"id": cid, "segment": seg_id, "slice": sid, "role": role, "name": name,
                                   "lane": lane, "x": cx + COL_PAD,
                                   "y": lane_y[lane] + LANE_PAD + k * (CARD_H + CARD_GAP),
                                   "w": CARD_W, "h": CARD_H, "portal": portal, "ghost": ghost})
                if role == "command":
                    self.cmd_cards[(sid, name)] = cid
                if role == "event":
                    self.event_cards.append((sid, name, cid))
            self.mains[sid] = ids[main]
            self.internal += [(ids[a], ids[z], kind) for a, z, kind in edges]
            self.dispatch_out += [(sid, c, ids[src]) for c, src in dispatched]
            if not portal:
                scen = by_id[sid].get("scenarios")
                n = len(scen) if isinstance(scen, list) else 0
                h = n * SCENARIO_H + (n - 1) * SCENARIO_GAP if n else NO_SCENARIO_H
                self.boxes.append({"slice": sid, "segment": seg_id, "x": cx, "y": lanes_bottom + BAND_GAP,
                                   "w": COL_W, "h": h})
                band = max(band, h)

        width = LANE_LABEL_W + 2 * SEG_PAD + len(plan) * COL_W + max(0, len(plan) - 1) * COL_GAP
        height = (lanes_bottom + BAND_GAP + band if band else lanes_bottom) + SEG_PAD
        self.id = seg_id
        self.box = {"id": seg_id, "process": pid, "bc": b, "x": 0, "y": 0, "w": width, "h": height,
                    "lanes": lanes, "columns": self.columns}

    def shift(self, dx, dy):
        self.box["x"] += dx
        self.box["y"] += dy
        for lane in self.box["lanes"]:
            lane["y"] += dy
        for c in self.columns:
            c["x"] += dx
        for item in self.cards + self.boxes:
            item["x"] += dx
            item["y"] += dy


def _pack(cells):
    """Shelf-pack (id, w, h) cells in the given order: {id: (x, y)}, board width, board height."""
    root = 1
    while root * root < len(cells):
        root += 1
    shelf_w = max([SHELF_MIN_W, root * SHELF_PER_CTX_W] + [w for _, w, _ in cells])
    pos, x, y, shelf_h, right = {}, 0, 0, 0, 0
    for cid, w, h in cells:
        if x > 0 and x + w > shelf_w:
            y += shelf_h + CTX_GAP
            x, shelf_h = 0, 0
        pos[cid] = (MARGIN + x, MARGIN + y)
        right = max(right, x + w)
        x += w + CTX_GAP
        shelf_h = max(shelf_h, h)
    return pos, right + 2 * MARGIN, y + shelf_h + 2 * MARGIN


def atlas_layout(data, a):
    by_bc = {}
    for pid, b, sid, portal, ghost in a.columns:
        by_bc.setdefault(b, {}).setdefault(pid, []).append((sid, portal, ghost))

    consumers, handlers = {}, {}
    for s in sorted(a.live + a.ghosts, key=lambda s: s["id"]):
        if s.get("error"):
            continue
        for e in _inbound(s):
            consumers.setdefault(e, []).append(s["id"])
        for c in _strs(s.get("handles")):
            handlers.setdefault(c, []).append(s["id"])

    cells, laid = [], []
    for b in sorted(by_bc):
        segs = [_Segment(f"{pid}@{b}", pid, b, by_bc[b][pid], a.by_id, handlers)
                for pid in sorted(by_bc[b], key=a.pkey)]
        y, w = CTX_HEADER_H, 0
        for seg in segs:
            seg.shift(CTX_PAD, y)
            y += seg.box["h"] + SEG_GAP
            w = max(w, seg.box["w"])
        cells.append((b, snap(w + 2 * CTX_PAD), snap(y - SEG_GAP + CTX_PAD)))
        laid.append(segs)

    pos, bw, bh = _pack(cells)
    contexts = []
    for (b, cw, ch), segs in zip(cells, laid):
        x, y = pos[b]
        contexts.append({"id": b, "x": x, "y": y, "w": cw, "h": ch})
        for seg in segs:
            seg.shift(x, y)
    segments = [seg for segs in laid for seg in segs]

    where, primary = {}, {}
    for seg in segments:
        for col in seg.columns:
            where.setdefault(col["slice"], {})[seg.id] = seg
            if not col["portal"]:
                primary[col["slice"]] = seg

    def target(seg, sid, cmd=None):
        """The card of `sid` an edge from `seg` lands on: its command card `cmd` (else its main
        card), in this segment when it has a column here, else in its full column."""
        here = where.get(sid, {}).get(seg.id)
        cands = [c for c in (here, primary.get(sid)) if c is not None]
        if cmd is not None:
            for cand in cands:
                if (sid, cmd) in cand.cmd_cards:
                    return cand, cand.cmd_cards[(sid, cmd)]
        return (cands[0], cands[0].mains[sid]) if cands else (None, None)

    edges = set()
    for seg in segments:
        for f, t, kind in seg.internal:
            edges.add((f, t, kind, False))
        for _, name, cid in seg.event_cards:
            for c in consumers.get(name, []):
                tseg, tid = target(seg, c)
                if tseg is not None and tid != cid:
                    edges.add((cid, tid, "feeds", tseg.id != seg.id))
        for sid, name, cid in seg.dispatch_out:
            for h in handlers.get(name, []):
                if h != sid:
                    tseg, tid = target(seg, h, name)
                    if tseg is not None and tid != cid:
                        edges.add((cid, tid, "dispatches", tseg.id != seg.id))
    edge_list = [{"from": f, "to": t, "kind": k, "cross": x} for f, t, k, x in sorted(edges)]

    card_seg = {c["id"]: c["segment"] for seg in segments for c in seg.cards}
    seg_bc = {seg.id: seg.box["bc"] for seg in segments}
    links = {}
    for e in edge_list:
        if e["cross"] and e["kind"] == "feeds":
            links.setdefault((card_seg[e["from"]], card_seg[e["to"]]), set()).add(e["from"].rsplit("|", 1)[1])
    link_list = [{"from": f, "to": t, "events": sorted(ev), "cross": seg_bc[f] != seg_bc[t]}
                 for (f, t), ev in sorted(links.items())]

    bridges = {}
    for (p, q), ev in _context_events(data, a).items():
        if p != q:
            bridges[(p, q)] = ev
    bridge_list = [{"from": p, "to": q, "events": sorted(ev)} for (p, q), ev in sorted(bridges.items())]

    return {
        "bounds": {"x": 0, "y": 0, "w": bw, "h": bh},
        "contexts": contexts,
        "segments": [seg.box for seg in segments],
        "cards": [c for seg in segments for c in seg.cards],
        "edges": edge_list,
        "links": link_list,
        "bridges": bridge_list,
        "scenarioBoxes": [b for seg in segments for b in seg.boxes],
    }


def _context_events(data, a):
    """{(publishing bc, consuming bc): {event}} over `flows[]`, live slices only."""
    live_bc = {s["id"]: a.bc[s["id"]] for s in a.live}
    out = {}
    for f in data.get("flows") or []:
        if not isinstance(f, dict) or not isinstance(f.get("event"), str):
            continue
        pubs = {live_bc[i] for i in _strs(f.get("from")) if i in live_bc}
        subs = {live_bc[i] for i in _strs(f.get("to")) if i in live_bc}
        for p in pubs:
            for q in subs:
                out.setdefault((p, q), set()).add(f["event"])
    return out


# ---------------------------------------------------------------- metro


def metro_layout(a):
    rank = slice_ranks(a.live)
    on = {}
    for s in a.live:
        on[s["id"]] = sorted(a.member.get(s["id"], []), key=a.pkey)
    lines = sorted({pid for ps in on.values() for pid in ps}, key=a.pkey)
    track = {pid: i for i, pid in enumerate(lines)}

    # A station takes, within its rank, the first slot free on every line it serves — so two
    # stations of one line never share an x and a line always has room to bend.
    slot, used, width = {}, {}, {}
    for sid in sorted(on, key=lambda i: (rank[i], track[on[i][0]], i)):
        taken = set().union(*(used.get((rank[sid], pid), set()) for pid in on[sid]))
        k = 0
        while k in taken:
            k += 1
        slot[sid] = k
        for pid in on[sid]:
            used.setdefault((rank[sid], pid), set()).add(k)
        width[rank[sid]] = max(width.get(rank[sid], 0), k + 1)
    x_start, x = {}, METRO_PAD
    for r in sorted(width):
        x_start[r] = x
        x += width[r] * METRO_SLOT + METRO_RANK_GAP

    def track_y(pid):
        return METRO_PAD + track[pid] * METRO_TRACK_DY

    at, stations = {}, []
    for sid in sorted(on, key=lambda i: (rank[i], track[on[i][0]], i)):
        at[sid] = (x_start[rank[sid]] + slot[sid] * METRO_SLOT, track_y(on[sid][0]))
        stations.append({"slice": sid, "x": at[sid][0], "y": at[sid][1], "interchange": len(on[sid]) > 1,
                         "lines": sorted(on[sid], key=a.pkey)})

    out = []
    for pid in lines:
        home = track_y(pid)
        pts = []
        for x2, y2, _ in sorted(at[sid] + (sid,) for sid in on if pid in on[sid]):
            if pts:
                x1, y1 = pts[-1]
                dy, dx = abs(y2 - y1), x2 - x1
                if dy and dx > dy:
                    # bend just before an interchange that sits off this track; when returning
                    # home from one, bend just after leaving it
                    pts.append([x2 - dy, y1] if y2 != home else [x1 + dy, y2])
            pts.append([x2, y2])
        p = a.procs[pid]
        out.append({"process": pid, "color": p["color"], "derived": not p["declared"], "points": pts})

    w = max([st["x"] for st in stations], default=0) + METRO_PAD
    h = METRO_PAD * 2 + max(0, len(lines) - 1) * METRO_TRACK_DY
    return {"bounds": {"x": 0, "y": 0, "w": w, "h": h}, "lines": out, "stations": stations}


# ---------------------------------------------------------------- matrix


def _read_name(r):
    name = r.get("name") if isinstance(r, dict) else r
    return name if isinstance(name, str) else None


def matrix_layout(data, a):
    targets = {}
    for w in data.get("writers") or []:
        if isinstance(w, dict) and isinstance(w.get("target"), str) and w.get("kind") in ("aggregate", "read-model"):
            targets.setdefault((w["target"], w["kind"]), set()).update(_strs(w.get("bcs")))
    for s in a.live:
        for t in _strs(s.get("writes")):
            targets.setdefault((t, "aggregate"), set()).add(a.bc[s["id"]])
        for t in _strs(s.get("owns")):
            targets.setdefault((t, "read-model"), set()).add(a.bc[s["id"]])
    named = {t for t, _ in targets}
    for s in a.live:
        for r in s.get("reads") or []:
            name = _read_name(r)
            if name is not None and name not in named:
                targets[(name, "read-model")] = set()
                named.add(name)

    def row_key(k):
        bcs = sorted(targets[k])
        return (bcs[0] if bcs else "", 0 if k[1] == "aggregate" else 1, k[0])

    row_keys = sorted(targets, key=row_key)
    r_index = {k: i for i, k in enumerate(row_keys)}
    rows = [{"id": t, "kind": kind, "bc": min(targets[(t, kind)], default=None)} for t, kind in row_keys]

    cols = sorted(a.live, key=lambda s: (a.bc[s["id"]], KIND_ORDER.get(s.get("kind") or "", 4), s["id"]))
    cells = set()
    for c, s in enumerate(cols):
        mine = a.bc[s["id"]]

        def cell(key, v):
            cells.add((r_index[key], c, v, bool(targets[key]) and mine not in targets[key]))

        for t in _strs(s.get("writes")):
            cell((t, "aggregate"), "W")
        for t in _strs(s.get("owns")):
            cell((t, "read-model"), "O")
        for r in s.get("reads") or []:
            name = _read_name(r)
            if name is None:
                continue
            key = (name, "read-model") if (name, "read-model") in r_index else (name, "aggregate")
            via = r.get("via") if isinstance(r, dict) else None
            external = isinstance(r, dict) and bool(r.get("external"))
            cell(key, "R·via" if via else ("R!" if external else "R"))

    bcs = sorted({a.bc[s["id"]] for s in a.live})
    events = _context_events(data, a)
    return {
        "rows": rows,
        "cols": [{"slice": s["id"], "bc": a.bc[s["id"]], "kind": s.get("kind")} for s in cols],
        "cells": [{"r": r, "c": c, "v": v, "cross": x} for r, c, v, x in sorted(cells)],
        "bcMatrix": {"bcs": bcs,
                     "counts": [[len(events.get((p, q), ())) for q in bcs] for p in bcs],
                     "events": [[sorted(events.get((p, q), ())) for q in bcs] for p in bcs]},
    }


# ---------------------------------------------------------------- entry point


def compute(data: dict) -> dict:
    """The C3 layout of `data` (the Atlas data JSON without `layout`). Pure and deterministic."""
    a = _Assigned(data)
    return {
        "version": LAYOUT_VERSION,
        "atlas": atlas_layout(data, a),
        "metro": metro_layout(a),
        "matrix": matrix_layout(data, a),
    }


if __name__ == "__main__":  # `python3 slice_atlas_layout.py < data.json` prints the layout
    import json
    import sys

    json.dump(compute(json.load(sys.stdin)), sys.stdout, ensure_ascii=False, sort_keys=True, indent=1)
    sys.stdout.write("\n")
