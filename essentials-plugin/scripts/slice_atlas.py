"""slice_atlas — the Slice Atlas data computed from the slice-map: processes, issues, lineage, the diff,
the slice CLAUDE.md conversion and page assembly. Imported by `slice-index.py` (its `map` and `atlas`
subcommands); it has no command line of its own.

Everything here is a pure function of the map rows `slice-index.py` builds and the JSON `slice-source.py`
writes. Nothing is random, nothing reads the clock, and every sort has a total key ending in an id, so the
same manifests give the same bytes. The contract it produces is the map JSON plus the keys below; the
page (`references/slice/atlas/`) derives nothing from the graph on its own.

    slices[].flows        the declared `flow:` as a list, [] when absent
    slices[].scenarios    the `scenarios:` normalised (Step / Then shapes below)
    slices[].doc          the slice's CLAUDE.md as a fixed safe HTML subset, null when absent
    slices[].schedule     the manifest's `schedule` object (cron, fixedDelay, initialDelay, timeZone,
                          note — string values only), null when absent
    processes             declared flows, derived processes (`~<entry slice id>`), unattached slices
                          (`~unattached:<bc>`), each in causal order
    issues                the map's divergence flags with codes, plus the Atlas checks
    lineage               normalised field name -> the commands, events and read models that carry it

Issue codes and levels (an issue annotates; the map grades nothing):

    map-parse-error map-duplicate-id map-kind-location map-twin-broken map-dangling-consume
    map-handled-undeclared map-endpoint-missing map-not-checked     the map's eight checks, as flagged
    flow-undeclared                  info  a slice with no `flow:` (aggregated per context)
    scenario-none                    info  a slice with no scenarios (aggregated per context)
    scenario-given-unpublished       warn  a `given` event no slice in scope publishes
    scenario-unknown-field           info  a `with` / row key that is no payload field / column
    scenario-test-missing            warn  `test:` resolves to no test in slice-source's test index
    scenario-malformed               warn  a scenario that does not have the schema's shape
    completeness-unsourced-column    info  a read-model column no inbound event carries a field for

Each issue carries `fix: {route, bc}` — which command owns its repair (FIX_ROUTES), and the context to
run it on. The page turns the routes into a prompt (the dock's Copy); the map itself repairs nothing.
"""

from __future__ import annotations

import datetime
import html
import io
import json
import re
import subprocess
import tarfile
import zlib
from pathlib import Path

# Columns that are bookkeeping rather than information, skipped by the completeness rule. Compared
# normalised; shown in the lineage lens's legend (lineage.technicalColumns).
TECHNICAL_COLUMNS = ("id", "version", "createdAt", "updatedAt", "lastModified", "etag")

# An adoption issue (no flow, no scenarios) that fires on at least AGGREGATE_MIN slices and on more than
# half of a context's slices fires once for the context, with the count. Below that it fires per slice.
AGGREGATED_CODES = ("flow-undeclared", "scenario-none")
AGGREGATE_MIN = 3
# Each aggregated code's per-slice text, and the slice-row field whose absence raises it.
SLICE_TEXT = {
    "flow-undeclared": "declares no `flow:` — grouped into a process by cause and effect; "
                       "/essentials:slice-enrich drafts its journey",
    "scenario-none": "declares no scenarios — its board shows invariants and test kinds instead; "
                     "/essentials:slice-enrich drafts them from its tests",
}
AGGREGATED_FIELD = {"flow-undeclared": "flows", "scenario-none": "scenarios"}

PROCESS_COLOURS = 12
LEVEL_RANK = {"error": 0, "warn": 1, "info": 2}
STEP_TYPES = ("event", "command", "query", "state", "external", "schedule")
FLOW_NAME = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
KIND_DIRS = ("use_cases", "views", "automations", "external_systems")
SOURCE_CODES = ("map-handled-undeclared", "map-endpoint-missing", "map-not-checked")

# Who owns the repair of each issue code. `repair`: a machine-derived manifest field or the YAML itself,
# which `/essentials:slice-check <bc> --fix-manifests` regenerates. `audit`: a divergence that needs a
# judgement — stale manifest or wrong code, which slice to move, which twin is live — so
# `/essentials:slice-check <bc>`, whose gates decide and offer the fix. `author`: the human-owned
# `flow:` and `scenarios:` (manifest-guide.md §3), which `--fix-manifests` never writes:
# `/essentials:slice-enrich --bc <bc>` drafts an absent one from evidence in the code and tests and writes
# only what a person accepts; a scenario that is present but wrong is the person's to edit. A code
# missing here routes to `audit`.
FIX_ROUTES = {
    "map-parse-error": "repair",
    "map-handled-undeclared": "repair",
    "map-duplicate-id": "audit",
    "map-kind-location": "audit",
    "map-twin-broken": "audit",
    "map-dangling-consume": "audit",
    "map-endpoint-missing": "audit",
    "map-not-checked": "audit",
    "completeness-unsourced-column": "audit",
    "flow-undeclared": "author",
    "scenario-none": "author",
    "scenario-given-unpublished": "author",
    "scenario-unknown-field": "author",
    "scenario-test-missing": "author",
    "scenario-malformed": "author",
}

# The manifest fields the diff compares, in the order a changed card lists them.
DIFF_SCALARS = ("kind", "bc", "status", "owner", "summary", "tier", "lane", "language", "externalSystem",
                "direction", "supersedes", "schedule", "error")
DIFF_LISTS = ("flows", "handles", "serves", "publishes", "consumes", "dispatches", "writes", "owns",
              "projections", "reads", "endpoints", "invariants", "dependsOn", "tests", "scenarios")


def norm(name: str) -> str:
    """Field-name normalisation for lineage and completeness: `order_id`, `orderId`, `Order-Id` are one."""
    return name.replace("_", "").replace("-", "").lower()


TECHNICAL = {norm(c) for c in TECHNICAL_COLUMNS}


def inbound(s):
    """`consumes` ∪ `projections[].from`, in declaration order."""
    seen, out = set(), []
    for name in s["consumes"] + [n for p in s["projections"] for n in p["from"]]:
        if name not in seen:
            seen.add(name)
            out.append(name)
    return out


def causes(s, o):
    """The messages by which slice s triggers slice o: events o reacts to, commands o handles."""
    return (set(s["publishes"]) & set(inbound(o))) | (set(s["dispatches"]) & set(o["handles"]))


# ---------------------------------------------------------------- manifest fields


def normalise_flow(raw) -> list[str]:
    values = raw if isinstance(raw, list) else [raw] if raw is not None else []
    out = []
    for v in values:
        if isinstance(v, str) and FLOW_NAME.match(v) and v not in out:
            out.append(v)
    return out


SCHEDULE_KEYS = ("cron", "fixedDelay", "initialDelay", "timeZone", "note")


def normalise_schedule(raw):
    """The manifest's `schedule` as given, its string-valued known keys only; None without a trigger."""
    if not isinstance(raw, dict):
        return None
    out = {k: raw[k] for k in SCHEDULE_KEYS if isinstance(raw.get(k), str)}
    return out if "cron" in out or "fixedDelay" in out else None


def _scalar(value):
    """A `with` / row value as JSON, or why it is not a scalar. YAML reads `2026-01-01` as a date."""
    if value is None or isinstance(value, (bool, int, float, str)):
        return value, None
    if isinstance(value, (datetime.date, datetime.datetime)):
        return value.isoformat(), None
    return None, f"is a {type(value).__name__}, not a scalar"


def _scalar_map(raw, where, problems):
    out = {}
    if raw is None:
        return out
    if not isinstance(raw, dict):
        problems.append(f"{where} is not a mapping")
        return out
    for k in raw:
        value, why = _scalar(raw[k])
        if why:
            problems.append(f"{where}.{k} {why}")
            continue
        out[str(k)] = value
    return out


def _step(raw, where, problems):
    if not isinstance(raw, dict):
        problems.append(f"{where} is not a mapping")
        return None
    keys = [k for k in raw if k in STEP_TYPES]
    extra = sorted(str(k) for k in raw if k not in STEP_TYPES and k != "with")
    if extra:
        problems.append(f"{where} has unknown key(s) {', '.join(extra)}")
    if not keys:
        problems.append(f"{where} names no message (one of {', '.join(STEP_TYPES)})")
        return None
    if len(keys) > 1:
        problems.append(f"{where} names more than one message ({', '.join(keys)}); read as `{keys[0]}`")
    t = keys[0]
    if t == "schedule":
        if raw[t] is not True:
            problems.append(f"{where}.schedule is not `true`")
        name = None
    else:
        if not isinstance(raw[t], str) or not raw[t]:
            problems.append(f"{where}.{t} is not a name")
            return None
        name = raw[t]
    return {"type": t, "name": name, "with": _scalar_map(raw.get("with"), f"{where}.with", problems)}


def _then(raw, problems):
    if isinstance(raw, list):
        if not raw:
            problems.append("`then` is an empty list — write { nothing: true }")
        steps = [st for i, r in enumerate(raw) if (st := _step(r, f"then[{i}]", problems)) is not None]
        return {"kind": "steps", "steps": steps}
    if isinstance(raw, dict):
        if "error" in raw:
            extra = sorted(str(k) for k in raw if k not in ("error", "message"))
            if extra:
                problems.append(f"`then` error has unknown key(s) {', '.join(extra)}")
            message = raw.get("message")
            return {"kind": "error", "error": str(raw["error"]),
                    "message": message if isinstance(message, str) else None}
        if "nothing" in raw:
            if raw["nothing"] is not True or len(raw) > 1:
                problems.append("`then` is not exactly { nothing: true }")
            return {"kind": "nothing"}
        if "readModel" in raw:
            rows = raw.get("rows")
            if not isinstance(rows, list):
                problems.append("`then` readModel has no `rows` list")
                rows = []
            return {"kind": "readModel", "readModel": str(raw["readModel"]),
                    "rows": [_scalar_map(r, f"rows[{i}]", problems) for i, r in enumerate(rows)]}
    problems.append("`then` is none of: a list of steps, { error }, { nothing: true }, { readModel, rows }")
    return {"kind": "steps", "steps": []}


def normalise_scenarios(raw):
    """(scenarios, problems): every scenario kept as far as it parses; problems as (scenario name, text)."""
    if raw is None:
        return [], []
    if not isinstance(raw, list):
        return [], [(None, "`scenarios` is not a list")]
    out, problems, names = [], [], set()
    for i, sc in enumerate(raw):
        mine: list[str] = []
        if not isinstance(sc, dict):
            problems.append((f"#{i + 1}", "is not a mapping"))
            continue
        name = sc.get("name")
        if not isinstance(name, str) or not name:
            mine.append("has no `name`")
            name = f"#{i + 1}"
        if name in names:
            mine.append("repeats the name of an earlier scenario")
        names.add(name)
        extra = sorted(str(k) for k in sc if k not in ("name", "given", "when", "then", "test", "note"))
        if extra:
            mine.append(f"has unknown key(s) {', '.join(extra)}")
        given_raw = sc.get("given")
        if given_raw is not None and not isinstance(given_raw, list):
            mine.append("`given` is not a list")
            given_raw = []
        given = [st for j, r in enumerate(given_raw or []) if (st := _step(r, f"given[{j}]", mine)) is not None]
        when = _step(sc["when"], "when", mine) if sc.get("when") is not None else None
        if "then" not in sc:
            mine.append("has no `then`")
            then = {"kind": "steps", "steps": []}
        else:
            then = _then(sc["then"], mine)
        test, note = sc.get("test"), sc.get("note")
        out.append({"name": name, "given": given, "when": when, "then": then,
                    "test": test if isinstance(test, str) and test else None,
                    "testResolved": {"status": "not-checked", "path": None, "line": None},
                    "note": note if isinstance(note, str) else None})
        problems.extend((name, p) for p in mine)
    return out, problems


# ---------------------------------------------------------------- CLAUDE.md -> safe HTML


def _inline(text: str) -> str:
    """Escape first, then emphasis and code spans. A link or an image becomes its text."""
    out = []
    for i, part in enumerate(re.split(r"(`+)(.+?)\1", text)):
        if i % 3 == 1:
            continue
        if i % 3 == 2:
            out.append("<code>" + html.escape(part.strip()) + "</code>")
            continue
        s = html.escape(part)
        s = re.sub(r"!?\[([^\]]*)\]\((?:[^()]|\([^()]*\))*\)", r"\1", s)
        s = re.sub(r"\*\*(?=\S)(.+?)(?<=\S)\*\*", r"<strong>\1</strong>", s)
        s = re.sub(r"(?<![A-Za-z0-9_])__(?=\S)(.+?)(?<=\S)__(?![A-Za-z0-9_])", r"<strong>\1</strong>", s)
        s = re.sub(r"\*(?=\S)(.+?)(?<=\S)\*", r"<em>\1</em>", s)
        s = re.sub(r"(?<![A-Za-z0-9_])_(?=\S)(.+?)(?<=\S)_(?![A-Za-z0-9_])", r"<em>\1</em>", s)
        out.append(s)
    return "".join(out)


def _cells(line: str):
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|") and not s.endswith("\\|"):
        s = s[:-1]
    return [c.strip().replace("\\|", "|") for c in re.split(r"(?<!\\)\|", s)]


TABLE_RULE = re.compile(r"^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$")
LIST_ITEM = re.compile(r"^\s*([-*+]|\d+[.)])\s+(.*)$")
FENCE = re.compile(r"^\s*(```|~~~)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
RULE = re.compile(r"^\s*([-*_])(\s*\1){2,}\s*$")
ALLOWED_TAGS = ("h1", "h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "li", "strong", "em", "code", "pre",
                "table", "thead", "tbody", "tr", "th", "td")


def markdown_html(text: str) -> str:
    """A slice CLAUDE.md as a fixed HTML subset (ALLOWED_TAGS, no attributes). Every character of the
    source is escaped before a tag is added, so raw HTML in a CLAUDE.md shows as text. Links render as
    their text; nested lists are flattened."""
    out: list[str] = []
    para: list[str] = []
    items: list[str] = []
    tag = ["ul"]
    lines = text.replace("\r\n", "\n").split("\n")

    def flush():
        if para:
            out.append("<p>" + _inline(" ".join(para)) + "</p>")
            para.clear()
        if items:
            out.append(f"<{tag[0]}>" + "".join(f"<li>{_inline(i)}</li>" for i in items) + f"</{tag[0]}>")
            items.clear()

    i = 0
    while i < len(lines):
        line = lines[i]
        fence = FENCE.match(line)
        if fence:
            flush()
            code = []
            i += 1
            while i < len(lines) and not lines[i].strip().startswith(fence.group(1)):
                code.append(lines[i])
                i += 1
            out.append("<pre><code>" + html.escape("\n".join(code)) + "</code></pre>")
            i += 1
            continue
        if not line.strip() or RULE.match(line):
            flush()
            i += 1
            continue
        heading = HEADING.match(line)
        if heading:
            flush()
            n = len(heading.group(1))
            out.append(f"<h{n}>{_inline(heading.group(2))}</h{n}>")
            i += 1
            continue
        if line.lstrip().startswith("|") and i + 1 < len(lines) and TABLE_RULE.match(lines[i + 1]):
            flush()
            head = _cells(line)
            rows = []
            i += 2
            while i < len(lines) and lines[i].lstrip().startswith("|"):
                rows.append(_cells(lines[i]))
                i += 1
            out.append("<table><thead><tr>" + "".join(f"<th>{_inline(c)}</th>" for c in head)
                       + "</tr></thead><tbody>"
                       + "".join("<tr>" + "".join(f"<td>{_inline(c)}</td>" for c in r) + "</tr>" for r in rows)
                       + "</tbody></table>")
            continue
        item = LIST_ITEM.match(line)
        if item:
            kind = "ol" if item.group(1)[0].isdigit() else "ul"
            if para or (items and tag[0] != kind):
                flush()
            tag[0] = kind
            items.append(item.group(2).strip())
            i += 1
            continue
        stripped = re.sub(r"^\s*>\s?", "", line).strip()
        if items and line[:1].isspace():
            items[-1] += " " + stripped  # a list item's continuation line
        else:
            if items:
                flush()
            para.append(stripped)
        i += 1
    flush()
    return "".join(out)


def read_doc(slice_dir: Path):
    try:
        return markdown_html((slice_dir / "CLAUDE.md").read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError):
        return None


# ---------------------------------------------------------------- processes


def context_of(row):
    """A broken manifest has no `bc`; its context is the directory above its kind directory."""
    if row.get("bc"):
        return row["bc"]
    parts = Path(row["path"]).parts
    for i, part in enumerate(parts):
        if part in KIND_DIRS and i > 0:
            return parts[i - 1]
    return parts[0] if parts and parts[0] != "." else "?"


def humanise(name: str) -> str:
    return name.replace("_", " ").replace("-", " ")


def causal_order(members):
    """(slice ids in causal order, entry ids). The rank is slice-index's longest path over a role floor
    — a command slice 1, any other slice 3, two ranks per message hop — restricted to the process's
    slices, then id. Cycle-guarded; a process that is all loop enters at its first slice."""
    pred = {s["id"]: [] for s in members}
    for s in members:
        for o in members:
            if o is not s and causes(s, o):
                pred[o["id"]].append(s["id"])
    floor = {s["id"]: 1 if s["kind"] == "command" else 3 for s in members}
    done, path = {}, set()

    def depth(n):
        if n in done:
            return done[n]
        if n in path:
            return 0
        path.add(n)
        d = floor[n]
        for p in sorted(pred[n]):
            d = max(d, depth(p) + 2)
        path.discard(n)
        done[n] = d
        return d

    ids = sorted(pred)
    for n in ids:
        depth(n)
    order = sorted(ids, key=lambda n: (done[n], n))
    return order, [n for n in order if not pred[n]] or order[:1]


def colour(pid: str) -> int:
    return zlib.crc32(pid.encode("utf-8")) % PROCESS_COLOURS


def is_entry(s):
    """An entry slice (design §6): a command with an endpoint, an inbound translation, a scheduled automation."""
    if s["kind"] == "command":
        return bool(s["endpoints"])
    if s["kind"] == "translation":
        return s["direction"] in ("inbound", "both")
    if s["kind"] == "automation":
        return s.get("schedule") is not None
    return False


def processes(rows):
    """Declared flows, derived processes and the unattached remainder (design §6), sorted declared
    first, then by id. A broken row joins its context's unattached process."""
    good, ids = [], set()
    for s in rows:  # a duplicate id is flagged by the map; the process graph keeps the first
        if not s.get("error") and s["id"] not in ids:
            ids.add(s["id"])
            good.append(s)
    kept = {id(s) for s in good}
    members: dict[str, list] = {}
    meta: dict[str, tuple] = {}
    for s in good:
        for f in s["flows"]:
            members.setdefault(f, []).append(s)
            meta[f] = (f, True, None)

    reached = set()
    for e in good:
        if e["flows"] or not e["bc"] or not is_entry(e):
            continue
        closure, todo = {e["id"]: e}, [e]
        while todo:  # forward, inside the context, stopping at a slice that declares a flow
            s = todo.pop()
            for o in good:
                if o["bc"] == e["bc"] and not o["flows"] and o["id"] not in closure and causes(s, o):
                    closure[o["id"]] = o
                    todo.append(o)
        pid = "~" + e["id"]
        members[pid] = list(closure.values())
        meta[pid] = (humanise(e["name"]), False, e["id"])
        reached |= set(closure)

    for s in rows:
        if s.get("error") or (id(s) in kept and not s["flows"] and s["id"] not in reached):
            pid = "~unattached:" + context_of(s)
            members.setdefault(pid, []).append(s)
            meta[pid] = ("unattached", False, None)

    where: dict[str, set] = {}
    for pid, ms in members.items():
        for s in ms:
            where.setdefault(s["id"], set()).add(pid)

    out = []
    for pid, ms in members.items():
        ok = [s for s in ms if not s.get("error")]
        order, entries = causal_order(ok)
        order += sorted(s["id"] for s in ms if s.get("error"))
        name, declared, entry = meta[pid]
        mine = {s["id"] for s in ms}
        links: dict[str, set] = {}
        for s in ok:
            for o in good:
                via = causes(s, o) if o["id"] not in mine else set()
                for q in where.get(o["id"], ()) if via else ():
                    links.setdefault(q, set()).update(via)
        out.append({
            "id": pid, "name": name, "declared": declared, "color": colour(pid),
            "bcs": sorted({context_of(s) for s in ms}), "sliceIds": order,
            "entries": [entry] if entry else entries,
            "links": [{"to": q, "via": sorted(links[q])} for q in sorted(links)],
        })
    out.sort(key=lambda p: (not p["declared"], p["id"]))
    return out


# ---------------------------------------------------------------- issues


def issue(code, level, text, kind, target, part=None, source="atlas"):
    return {"id": code + ":" + target + (":" + part if part else ""), "code": code, "level": level,
            "text": text, "target": {"kind": kind, "id": target, "part": part}, "source": source}


def flag_issues(row, codes):
    """One slice's map flags as issues; `codes` holds (code, part) parallel to `row["flags"]`. Ids carry
    no line number, so an edit elsewhere in a file does not turn an issue into a fixed one plus a new
    one; the line stays in the text. Two flags whose (code, part) would collide get a discriminator
    hashed from their text with every `:<line>` removed."""
    seen: dict[tuple, int] = {}
    for key in codes:
        seen[key] = seen.get(key, 0) + 1
    out = []
    for f, (code, part) in zip(row["flags"], codes):
        if seen[(code, part)] > 1:
            stable = re.sub(r":\d+", "", f["text"])
            part = f"{part or ''}#{zlib.crc32(stable.encode('utf-8')):08x}"
        out.append(issue(code, f["level"], f["text"], "slice", row["id"], part,
                         "source" if code in SOURCE_CODES else "map"))
    return out


def sort_issues(issues):
    """First of each id, ordered error < warn < info, then id."""
    by_id = {}
    for i in issues:
        by_id.setdefault(i["id"], i)
    return sorted(by_id.values(), key=lambda i: (LEVEL_RANK[i["level"]], i["id"]))


def with_fixes(issues, rows):
    """Each issue with `fix: {route, bc}` (FIX_ROUTES); bc is the context of its slice or context, or
    None for another target."""
    bc_of = {s["id"]: context_of(s) for s in rows}
    for i in issues:
        t = i["target"]
        bc = t["id"] if t["kind"] == "context" else bc_of.get(t["id"]) if t["kind"] == "slice" else None
        i["fix"] = {"route": FIX_ROUTES.get(i["code"], "audit"), "bc": bc}
    return issues


def aggregate(issues, rows):
    """Fold an adoption issue that fires on most of a context's slices into one for the context. The
    context issue lists the folded slice ids as `members`, so diff() can compare per slice whichever
    side of the threshold each map sits on."""
    total: dict[str, int] = {}
    bc_of = {}
    for s in rows:
        if not s.get("error") and s["bc"]:
            total[s["bc"]] = total.get(s["bc"], 0) + 1
            bc_of.setdefault(s["id"], s["bc"])
    groups: dict[tuple, list] = {}
    for i in issues:
        bc = bc_of.get(i["target"]["id"])
        if i["code"] in AGGREGATED_CODES and i["target"]["kind"] == "slice" and bc:
            groups.setdefault((i["code"], bc), []).append(i)
    folded, out = set(), []
    for (code, bc), group in sorted(groups.items()):
        if len(group) >= AGGREGATE_MIN and 2 * len(group) > total[bc]:
            folded |= {id(i) for i in group}
            text = (f"{len(group)} of {total[bc]} slices in `{bc}` declare no flow — grouped into processes "
                    "by cause and effect; /essentials:slice-enrich drafts their journeys" if code == "flow-undeclared"
                    else f"{len(group)} of {total[bc]} slices in `{bc}` declare no scenarios — their boards show "
                    "invariants and test kinds instead; /essentials:slice-enrich drafts scenarios from their tests")
            out.append({**issue(code, group[0]["level"], text, "context", bc),
                        "members": sorted(i["target"]["id"] for i in group)})
    return [i for i in issues if id(i) not in folded] + out


def unfold(issues, rows):
    """id -> issue with every context issue aggregate() folded expanded back into its per-slice issues,
    so a context crossing the fold threshold between two maps changes no issue id. A map written before
    the fold recorded `members` names none: its folded slices are recovered from its own rows (an
    unbroken slice of that context whose row lacks the field), and when the rows do not carry the field
    either, the context issue is kept as it is — then that context's issues compare by the context id."""
    out = {}
    for i in issues:
        code, t = i["code"], i["target"]
        if code not in AGGREGATED_CODES or t["kind"] != "context":
            out.setdefault(i["id"], i)
            continue
        members = i.get("members")
        if not isinstance(members, list):
            field = AGGREGATED_FIELD[code]
            members = [s["id"] for s in rows if s.get("bc") == t["id"] and not s.get("error") and field in s
                       and not s[field]] or None
        if not members:
            out.setdefault(i["id"], i)
            continue
        for sid in members:
            one = issue(code, i["level"], SLICE_TEXT[code], "slice", sid)
            if "fix" in i:
                one["fix"] = dict(i["fix"])
            out.setdefault(one["id"], one)
    return out


def field_name(field: str) -> str:
    """slice-source writes a payload field as `name: Type`."""
    return field.split(":", 1)[0].strip()


def payloads(messages):
    """(name, type) -> {normalised field: field} for every message whose declaration source facts found."""
    out = {}
    for m in messages:
        if m.get("declaredIn") and isinstance(m.get("fields"), list):
            out[(m["name"], m["type"])] = {norm(field_name(f)): field_name(f) for f in m["fields"]
                                           if isinstance(f, str)}
    return out


def columns(rm):
    return [c["name"] for c in rm.get("columns") or [] if isinstance(c, dict) and isinstance(c.get("name"), str)]


def resolve_test(ref, tests):
    """`Class#method` | `Class` | `path/File.ext#method` against slice-source's test index. The method
    part matches a test method's name or its @DisplayName; `not-checked` when there is no index."""
    if tests is None:
        return {"status": "not-checked", "path": None, "line": None}
    target, _, method = ref.partition("#")
    classes = [c for c in tests.get("classes") or [] if isinstance(c, dict) and isinstance(c.get("path"), str)]
    if "/" in target or target.endswith((".java", ".kt")):
        cands = [c for c in classes if c["path"] == target or c["path"].endswith("/" + target)]
    else:
        cands = [c for c in classes if c.get("name") == target or str(c.get("name")).rsplit(".", 1)[-1] == target]
    for c in sorted(cands, key=lambda c: (c["path"], str(c.get("name")))):
        if not method:
            return {"status": "found", "path": c["path"], "line": None}
        for m in c.get("methods") or []:
            if isinstance(m, dict) and method in (m.get("name"), m.get("displayName")):
                return {"status": "found", "path": c["path"], "line": m.get("line")}
    return {"status": "missing", "path": None, "line": None}


def atlas_issues(rows, scope, messages, tests, facts_given):
    """The Atlas checks (design §6, §7.3, §10.3) over `rows`; `scope` is every row of the map, which a
    `given` event is published in."""
    out = []
    good_scope = [s for s in scope if not s.get("error")]
    published = {e for s in good_scope for e in s["publishes"]}
    known = payloads(messages) if facts_given else {}
    aliases: dict[str, set] = {}
    for s in good_scope:  # a translation's `maps` renames a field on its way in
        for m in s.get("_maps") or []:
            aliases.setdefault(norm(m["from"]), set()).add(norm(m["to"]))
            aliases.setdefault(norm(m["to"]), set()).add(norm(m["from"]))
    roots = ", ".join(tests.get("roots") or []) if tests else ""

    for s in rows:
        if s.get("error"):
            continue
        sid = s["id"]
        if not s["flows"]:
            out.append(issue("flow-undeclared", "info", SLICE_TEXT["flow-undeclared"], "slice", sid))
        if not s["scenarios"]:
            out.append(issue("scenario-none", "info", SLICE_TEXT["scenario-none"], "slice", sid))
        for name, problem in s.get("_scenario_problems") or []:
            out.append(issue("scenario-malformed", "warn",
                             (f"scenario `{name}` " if name else "") + problem + " — kept as far as it parses",
                             "slice", sid, f"scenario:{name}" if name else "scenarios"))
        models = {rm["name"]: rm for rm in s.get("readModels") or [] if isinstance(rm, dict) and rm.get("name")}
        for sc in s["scenarios"]:
            part = "scenario:" + sc["name"]
            for st in sc["given"]:
                if st["type"] == "event" and st["name"] not in published:
                    out.append(issue("scenario-given-unpublished", "warn",
                                     f"scenario `{sc['name']}`: given event {st['name']}, which no slice in scope "
                                     "publishes", "slice", sid, f"{part}:{st['name']}"))
            steps = sc["given"] + ([sc["when"]] if sc["when"] else []) + (
                sc["then"]["steps"] if sc["then"]["kind"] == "steps" else [])
            for st in steps:
                fields = known.get((st["name"], st["type"]))
                if fields is None:
                    continue
                for key in st["with"]:
                    if norm(key) not in fields:
                        out.append(issue("scenario-unknown-field", "info",
                                         f"scenario `{sc['name']}`: `{key}` is not a payload field of {st['name']} "
                                         f"({', '.join(sorted(fields.values())) or 'no fields'})",
                                         "slice", sid, f"{part}:{st['name']}.{key}"))
            then = sc["then"]
            model = read_model_of(s, then["readModel"], models) if then["kind"] == "readModel" else None
            if model is not None:
                cols = {norm(c) for c in columns(model)}
                for key in sorted({k for row in then["rows"] for k in row}):
                    if cols and norm(key) not in cols:
                        out.append(issue("scenario-unknown-field", "info",
                                         f"scenario `{sc['name']}`: row key `{key}` is not a column of "
                                         f"{then['readModel']}", "slice", sid, f"{part}:{then['readModel']}.{key}"))
            if sc["test"] and sc["testResolved"]["status"] == "missing":
                out.append(issue("scenario-test-missing", "warn",
                                 f"scenario `{sc['name']}`: test `{sc['test']}` resolves to no test under "
                                 f"{roots or 'the test roots'}", "slice", sid, part))
        if s["kind"] != "view" or not facts_given:
            continue
        sources = inbound(s)
        carried_by = [known[(e, "event")] for e in sources if (e, "event") in known]
        if not sources or len(carried_by) < len(sources):
            continue  # an inbound event with an unknown payload: not checked rather than guessed
        carried = set().union(*carried_by)
        for rm_name in sorted(models):
            for col in columns(models[rm_name]):
                n = norm(col)
                if n in TECHNICAL or n in carried or aliases.get(n, set()) & carried:
                    continue
                out.append(issue("completeness-unsourced-column", "info",
                                 f"column `{col}` of {rm_name} matches no field of {', '.join(sources)}",
                                 "slice", sid, f"readModel:{rm_name}.{col}"))
    return out


def read_model_of(s, name, models):
    """The source read model a view scenario's `then.readModel` names. It may name the model itself, or —
    as the plugin's view template writes no `owns` — any name in `owns` ∪ `reads[].name` ∪
    `projections[].name`; then the view's only read model is meant. None when that is not decidable."""
    if name in models:
        return models[name]
    declared = set(s["owns"]) | {r["name"] for r in s["reads"]} | {p["name"] for p in s["projections"] if p["name"]}
    if name in declared and len(models) == 1:
        return next(iter(models.values()))
    return None


# ---------------------------------------------------------------- lineage


def lineage(messages, rows):
    """Normalised field name -> `msg:<type>:<Name>` and `rm:<ReadModel>` refs carrying it."""
    fields: dict[str, set] = {}
    for (name, typ), payload in payloads(messages).items():
        for n in payload:
            fields.setdefault(n, set()).add(f"msg:{typ}:{name}")
    for s in rows:
        for rm in s.get("readModels") or []:
            if isinstance(rm, dict) and rm.get("name"):
                for c in columns(rm):
                    fields.setdefault(norm(c), set()).add("rm:" + rm["name"])
    return {"fields": {k: sorted(fields[k]) for k in sorted(fields)}, "technicalColumns": list(TECHNICAL_COLUMNS)}


# ---------------------------------------------------------------- diff


def edges(rows):
    out = set()
    for s in rows:
        if s.get("error"):
            continue
        node = "slice:" + s["id"]
        out |= {("cmd:" + c, node, "handles") for c in s.get("handles") or []}
        out |= {(node, "evt:" + e, "publishes") for e in s.get("publishes") or []}
        out |= {("evt:" + e, node, "consumes") for e in inbound({"consumes": s.get("consumes") or [],
                                                                  "projections": s.get("projections") or []})}
        out |= {(node, "cmd:" + c, "dispatches") for c in s.get("dispatches") or []}
    return out


def _values(row, field):
    v = row.get(field) or []
    if field == "projections":
        return [f"{p.get('name')} <- {', '.join(p.get('from') or [])}" for p in v]
    if field == "reads":
        return [r["name"] + (f" via {r['via']}" if r.get("via") else "") for r in v]
    if field == "endpoints":
        return [f"{e.get('method') or '?'} {e.get('path')}" for e in v]
    if field == "invariants":
        return [i.get("id") or i.get("text") for i in v]
    if field == "scenarios":
        return [sc["name"] for sc in v]
    return list(v)


def diff(before, after):
    """Before and after maps matched by stable ids (design §10.2): slice id, (from, to, kind) for an
    edge, issue id. A removed slice carries its whole before-row. A before map that predates the
    Atlas lacks some keys (`flows`, `scenarios`, `schedule`, `doc`, `issues`): a field the before row
    does not carry is not compared, and `issuesKnown` is false — then no issue is called new or fixed.
    Issues compare unfolded (unfold()): `new` and `fixed` name per-slice issues even where a map folded
    them into one for the context, so the fold threshold never turns into a count of its own."""
    b_rows, a_rows = {}, {}
    for s in before.get("slices") or []:
        b_rows.setdefault(s["id"], s)
    for s in after["slices"]:
        a_rows.setdefault(s["id"], s)
    changed = []
    for sid in sorted(set(a_rows) & set(b_rows)):
        a, b = a_rows[sid], b_rows[sid]
        fields = [{"field": f, "added": [], "removed": [], "from": b.get(f), "to": a.get(f)}
                  for f in DIFF_SCALARS if (f in b or f == "error") and b.get(f) != a.get(f)]
        for f in DIFF_LISTS:
            if f not in b:
                continue
            bv, av = _values(b, f), _values(a, f)
            if bv != av or (f == "scenarios" and b.get(f) != a.get(f)):
                fields.append({"field": f, "added": sorted(set(av) - set(bv)), "removed": sorted(set(bv) - set(av)),
                               "from": len(bv), "to": len(av)})
        if fields:
            changed.append({"id": sid, "fields": fields})
    b_edges, a_edges = edges(before.get("slices") or []), edges(after["slices"])

    def as_json(es):
        return [{"from": f, "to": t, "kind": k} for f, t, k in sorted(es)]

    def names(es, prefix):
        return {n[len(prefix):] for e in es for n in e[:2] if n.startswith(prefix)}

    messages = {}
    for key, prefix in (("events", "evt:"), ("commands", "cmd:")):
        bn, an = names(b_edges, prefix), names(a_edges, prefix)
        messages[key] = {"added": sorted(an - bn), "removed": sorted(bn - an)}

    known = isinstance(before.get("issues"), list)
    b_issues = unfold(before.get("issues") or [], before.get("slices") or [])
    a_ids = set(unfold(after["issues"], after["slices"]))
    return {
        "before": {"sha": (before.get("meta") or {}).get("sha"), "sliceCount": len(before.get("slices") or []),
                   "ref": None, "issuesKnown": known},
        "slices": {"added": sorted(set(a_rows) - set(b_rows)),
                   "removed": [{**b_rows[i], "flows": b_rows[i].get("flows") or []}
                               for i in sorted(set(b_rows) - set(a_rows))],
                   "changed": changed},
        "edges": {"added": as_json(a_edges - b_edges), "removed": as_json(b_edges - a_edges)},
        "messages": messages,
        "issues": {"new": sorted(a_ids - set(b_issues)) if known else [],
                   "fixed": sort_issues([b_issues[i] for i in set(b_issues) - a_ids]) if known else []},
    }


def extract_ref(root: Path, ref: str, dest: Path, missing_ok: bool = False):
    """`git archive REF -- <root>` unpacked under dest. Returns (ROOT's copy inside dest, short sha).
    Touches neither the working tree nor .git. Raises ValueError carrying git's message. With
    `missing_ok`, a ROOT that REF does not hold (an application added since) extracts nothing, and the
    returned path does not exist."""
    def git(*args, cwd=root):
        r = subprocess.run(["git", "-C", str(cwd), *args], capture_output=True, timeout=300)
        if r.returncode != 0:
            raise ValueError(f"git {args[0]} {ref}: {r.stderr.decode('utf-8', 'replace').strip()}")
        return r.stdout

    top = Path(git("rev-parse", "--show-toplevel").decode("utf-8").strip()).resolve()
    sha = git("rev-parse", "--short", ref + "^{commit}").decode("utf-8").strip()
    rel = root.resolve().relative_to(top).as_posix()
    if missing_ok and rel != "." and subprocess.run(["git", "-C", str(top), "cat-file", "-e", f"{sha}:{rel}"],
                                                    capture_output=True, timeout=60).returncode != 0:
        return dest / rel, sha
    data = git("archive", "--format=tar", sha, *(["--", rel] if rel != "." else []), cwd=top)
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        tar.extractall(dest, filter="data")
    return dest / rel, sha


# ---------------------------------------------------------------- page


DATA_LINE = "const ATLAS = /* __ATLAS_DATA__ */ null;"
LENS_LINE = "/* __ATLAS_LENSES__ */"
SCRIPT_ESCAPES = (("<", "\\u003c"), (">", "\\u003e"), ("&", "\\u0026"), ("\u2028", "\\u2028"),
                  ("\u2029", "\\u2029"))


def script_json(data) -> str:
    """The data as a JavaScript literal that cannot end its <script> element or break a JS string."""
    text = json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    for a, b in SCRIPT_ESCAPES:
        text = text.replace(a, b)
    return text


def assemble(template: Path, data) -> str:
    """The page: the data into the template's one data line, every `lens-*.js` beside it into its one
    lens line, in filename order. ValueError when a placeholder is not there exactly once."""
    page = template.read_text(encoding="utf-8")
    for token in (DATA_LINE, LENS_LINE):
        n = page.count(token)
        if n != 1:
            raise ValueError(f"template {template} must carry `{token}` exactly once, not {n} times")
    lenses = []
    for lens in sorted(template.parent.glob("lens-*.js")):
        code = lens.read_text(encoding="utf-8")
        if "</script" in code.lower():
            raise ValueError(f"{lens} contains `</script`, which would end the page's script element")
        lenses.append(f"/* ---- {lens.name} ---- */\n{code.rstrip()}\n")
    page = page.replace(DATA_LINE, "const ATLAS = " + script_json(data) + ";")
    return page.replace(LENS_LINE, "\n".join(lenses))


INDEX_DATA_LINE = "const INDEX = /* __ATLAS_INDEX_DATA__ */ null;"
TOKENS_LINE = "/* __ATLAS_TOKENS__ */"
TOKENS_BEGIN, TOKENS_END = "/* __TOKENS_BEGIN__ */", "/* __TOKENS_END__ */"


def assemble_index(template: Path, atlas: Path, data) -> str:
    """The per-application index page: the data into its one data line, and the brand tokens — the CSS
    between atlas.html's __TOKENS_BEGIN__ and __TOKENS_END__ markers — into its one token line, so the
    palette exists once. ValueError when a placeholder or marker is not there exactly once."""
    page = template.read_text(encoding="utf-8")
    for token in (INDEX_DATA_LINE, TOKENS_LINE):
        n = page.count(token)
        if n != 1:
            raise ValueError(f"template {template} must carry `{token}` exactly once, not {n} times")
    source = atlas.read_text(encoding="utf-8")
    for marker in (TOKENS_BEGIN, TOKENS_END):
        n = source.count(marker)
        if n != 1:
            raise ValueError(f"{atlas} must carry `{marker}` exactly once, not {n} times")
    tokens = source[source.index(TOKENS_BEGIN) + len(TOKENS_BEGIN):source.index(TOKENS_END)].strip("\n")
    if "</style" in tokens.lower():
        raise ValueError(f"the token block of {atlas} contains `</style`")
    page = page.replace(TOKENS_LINE, tokens)  # first: the data cannot then be mistaken for the token line
    return page.replace(INDEX_DATA_LINE, "const INDEX = " + script_json(data) + ";")
