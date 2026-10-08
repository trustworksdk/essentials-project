#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3", "jsonschema==4.26.0"]
# ///
"""slice-enrich — evidence for the human-owned manifest fields an older slice.yaml lacks, and the
writer that records the ones a person accepted.

Why this exists
---------------
`--fix-manifests` never writes a human-owned field, so a project whose manifests predate schema 1.4
never gains `flow:` or `scenarios:` and the Slice Atlas falls back to one derived journey per command.
`/essentials:slice-enrich` lets a model draft those fields — but only from evidence, and a person
writes each one by accepting it (docs/slice-enrich-design.md, references/slice/manifest-reconciliation.md
§1). This script is the mechanical half: it finds every candidate and its evidence, and it writes what
was accepted. The model writes prose; it never edits a slice.yaml.

It reuses slice-source.py's lexer and declaration reader (comments and strings handled, imports
resolved) and slice-index.py's manifest loader and normaliser by importing them; the facts those
compute are taken from `slice-source.py --json`, never re-derived. The facts only this command needs —
guard clauses, GivenWhenThenScenario chains, Javadoc, @ConditionalOnProperty, a translation's
constructor calls — are read here, so slice-source.py's goldens do not move.

Usage
-----
    slice-enrich.py propose ROOT --source-facts FILE [--bc BC] [--fields f,…] [--json]
    slice-enrich.py apply   ROOT ACCEPTED.json [--dry-run]

    propose   every absent field a manifest could gain, with the evidence for it (contract P1,
              docs/slice-enrich-design.md §5.2). FILE is `slice-source.py ROOT --json` on the same ROOT.
              --bc keeps that context's slices (journeys are computed over the whole project);
              --fields limits the fields considered. A text summary unless --json.
    apply     writes the accepted values (contract P2, §5.5): the proposal JSON with each slice's
              `proposals` replaced by `accept: {field: value}`. Only fields still absent are written,
              as a text insertion that keeps every existing byte. The result must parse and must not
              fail references/slice/slice-yaml.schema.json in any way the file did not already.
              --dry-run prints a unified diff per file and writes nothing.

`accept` values are in manifest shape: `flow` a name or a list of names, `scenarios`, `invariants` and
`maps` lists of their schema items, `summary` and `tier` strings, `featureFlag` an object, and
`consistency` a mapping from projection name to `strong` | `eventual`. A field present and equal to the
accepted value is left alone, so a second run of the same file writes nothing.

Exit codes
----------
    propose   0 proposal written (possibly empty) · 2 could not run · 3 no slice.yaml under ROOT
    apply     0 written (or, with --dry-run, would be) · 1 some entries refused (reported; the rest
              written) · 2 could not run (nothing written)
"""

from __future__ import annotations

import argparse
import difflib
import importlib.util
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PLUGIN = HERE.parent
SCHEMA = PLUGIN / "references" / "slice" / "slice-yaml.schema.json"
TEMPLATES = PLUGIN / "references" / "slice" / "templates"

FIELDS = ["flow", "scenarios", "summary", "invariants", "tier", "consistency", "featureFlag", "maps"]
# Never drafted: the code holds no evidence for them (design §2). Reported once per project.
NOT_INFERABLE = ["characterization", "forbidden", "notes", "observability", "owner", "profile", "sla",
                 "stability", "status", "statutory", "supersedes"]
FLOW_NAME = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
TIERS = {"cqrs-es", "service-entity", "cqrs", "layered-hexagonal", "custom"}
LANE_TIER = {"aggregate": "cqrs-es", "service-entity": "service-entity"}  # manifest-guide.md §3
MAX_INVARIANTS = 10
MAX_SUMMARY = 160
AGGREGATE_BASES = {"AggregateRoot", "AggregateRootWithState", "FlexAggregate", "Aggregate"}
MAIN_TYPE_SUFFIXES = ("Decider", "Handler", "Projection", "Projector", "Processor", "Translator")
# The chain of dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.test.GivenWhenThenScenario
GWT = "GivenWhenThenScenario"
GWT_CALLS = ("given", "when", "then", "thenExpectNoEvent", "thenFailsWithException", "thenFailsWithExceptionType",
             "thenAssert")


class UsageError(Exception):
    pass


def _load(name: str, file: str):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    if spec is None or spec.loader is None:
        raise ImportError(f"cannot load {file} beside this script")
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


ss = _load("slice_source", "slice-source.py")  # the lexer, SrcFile and the declaration constants
si = _load("slice_index", "slice-index.py")  # the manifest loader and row normaliser
sa = sys.modules["slice_atlas"]  # imported by slice-index: causal_order(), normalise_flow()


# ---------------------------------------------------------------- small helpers


def ev(path, line, text):
    return {"path": path, "line": line, "text": text}


def pascal(snake: str) -> str:
    return "".join(p[:1].upper() + p[1:] for p in re.split(r"[_-]+", snake) if p)


def kebab(name: str) -> str:
    return re.sub(r"[_\s]+", "-", name).lower()


def humanise_method(name: str) -> str:
    if " " in name:
        return name  # a Kotlin backtick name is already a sentence
    words = re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ", name.replace("_", " ")).split()
    return " ".join(w if w.isupper() and len(w) > 1 else w.lower() for w in words)


def first_sentence(text: str) -> str:
    text = re.sub(r"\{@(?:code|link|linkplain|literal)\s+([^}]*)\}", lambda m: m.group(1).split("#")[-1], text)
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"\[([^\]]+)\]", r"\1", text)  # KDoc links
    text = " ".join(text.split())
    m = re.search(r"(?<=[.!?])\s", text)
    return (text[:m.start()] if m else text).strip()


def clip(sentence: str) -> str:
    if len(sentence) <= MAX_SUMMARY:
        return sentence
    return sentence[:MAX_SUMMARY - 1].rsplit(" ", 1)[0].rstrip(",;:") + "…"


def doc_body(raw: str) -> str:
    lines = []
    for line in raw.splitlines():
        line = re.sub(r"^\s*\*?", "", line).strip()
        if line.startswith("@"):
            break  # block tags end the description
        lines.append(line)
    return "\n".join(lines)


def first_paragraph(text: str):
    """(paragraph, first line number) of the first prose paragraph of a Markdown file, or None."""
    para, at, in_comment = [], None, False
    for n, line in enumerate(text.splitlines(), 1):
        s = line.strip()
        if in_comment:
            in_comment = "-->" not in s
            continue
        if s.startswith("<!--"):
            in_comment = "-->" not in s
            continue
        if not s:
            if para:
                break
            continue
        if not para and s.startswith(("#", "|", "```", ">", "- ", "* ")):
            continue
        para.append(s)
        at = at or n
    return (" ".join(para), at) if para else None


def template_patterns():
    """Template boilerplate is not evidence: the `summary:` values of the slice templates (the
    placeholders a rendered manifest starts with) and the first sentence of every template's doc
    comments and CLAUDE.md, each with `{{var}}` as a wildcard."""
    summaries, prose = set(), set()
    if not TEMPLATES.is_dir():
        return [], []
    for p in sorted(TEMPLATES.rglob("*")):
        if not p.is_file():
            continue
        text = p.read_text(encoding="utf-8", errors="replace")
        if p.name == "slice.yaml":
            summaries.update(m.group(1) for m in re.finditer(r"(?m)^summary:[ \t]*(.*?)[ \t]*$", text))
        elif p.suffix in (".java", ".kt"):
            prose.update(first_sentence(doc_body(m.group(1))) for m in re.finditer(r"/\*\*(.*?)\*/", text, re.S))
        elif p.name.startswith("CLAUDE.md"):
            found = first_paragraph(text)
            if found:
                prose.add(first_sentence(found[0]))

    def rx(s):
        return re.compile(".+?".join(re.escape(x) for x in re.split(r"\{\{\w+\}\}", s)) + r"\Z", re.S)

    return [rx(s) for s in sorted(summaries) if s], [rx(s) for s in sorted(prose) if s]


# ---------------------------------------------------------------- source reading


class Sources:
    """Lexed files, each read once, through slice-source.py's SrcFile."""

    def __init__(self, root: Path):
        self.root = root
        self.cache = {}

    def get(self, rel: str):
        if rel not in self.cache:
            p = self.root / rel
            try:
                text = p.read_text(encoding="utf-8", errors="replace")
            except OSError:
                self.cache[rel] = (None, None)
            else:
                lang = ss.SOURCE_EXT.get(p.suffix)
                f = ss.SrcFile(p, rel, lang, text) if lang else None
                self.cache[rel] = (None if f is None or f.error is not None else f, text)
        return self.cache[rel]


def args_of(f, lp):
    """Top-level argument token ranges [(a, b)) of the call whose '(' is at lp."""
    rp = f.match[lp]
    out, a, k = [], lp + 1, lp + 1
    while k <= rp:
        if k == rp or f.is_op(k, ","):
            if a < k:
                out.append((a, k))
            a = k = k + 1
            continue
        t = f.toks[k]
        if t.k == "op" and t.t in "([{":
            k = f.match[k] + 1
            continue
        k += 1
    return out


def string_arg(f, rng):
    a, b = rng
    t = f.tok(a)
    if b - a == 1 and t is not None and t.k == "str" and t.v is not None:
        return t.v
    return None


def literal(f, rng):
    """(True, value) when the argument is one scalar literal, else (False, None)."""
    a, b = rng
    toks = f.toks[a:b]
    neg = False
    if len(toks) == 2 and toks[0].k == "op" and toks[0].t == "-" and toks[1].k == "num":
        neg, toks = True, toks[1:]
    if len(toks) != 1:
        return False, None
    t = toks[0]
    if t.k == "str":
        return t.v is not None, t.v
    if t.k == "id" and t.t in ("true", "false"):
        return True, t.t == "true"
    if t.k == "id" and t.t == "null":
        return True, None
    if t.k == "num":
        text = t.t.replace("_", "")
        if text.lower().startswith(("0x", "0b")):
            return False, None
        text = re.sub(r"[lLfFdD]$", "", text)
        try:
            value = int(text) if re.fullmatch(r"\d+", text) else float(text)
        except ValueError:
            return False, None
        return True, -value if neg else value
    return False, None


def construction(f, a, b):
    """(TypeName, '(' index) when tokens [a, b) are exactly `new X(…)` (Java) or `X(…)` (Kotlin)."""
    j = a
    if f.is_id(j, "new"):
        j += 1
    elif not f.kotlin:
        return None
    name, j = f.qname(j)
    if not name:
        return None
    if f.is_op(j, "<"):
        j = f.skip_angles(j)
    if not f.is_op(j, "(") or f.match[j] + 1 != b:
        return None
    simple = name.rsplit(".", 1)[-1]
    return (simple, j) if simple[:1].isupper() else None


def declaration_lines(f, td):
    """(first line of the declaration with its annotations and modifiers, line of the token before it)."""
    k = td.kw - 1
    while k >= 0:
        if k in f.ann_last:
            k = f.ann_last[k].start - 1
            continue
        t = f.toks[k]
        if t.k == "id" and t.t in ss.MODIFIERS:
            k -= 1
            continue
        break
    return f.toks[k + 1].line, (f.toks[k].line if k >= 0 else 0)


def javadoc(f, text, td):
    """(first sentence, line) of the doc comment directly above a type declaration, or None."""
    start, prev = declaration_lines(f, td)
    lines = text.splitlines()
    best = None
    for m in re.finditer(r"/\*\*(?!/)(.*?)\*/", text, re.S):
        a = text.count("\n", 0, m.start()) + 1
        z = text.count("\n", 0, m.end()) + 1
        if a < prev or z > start or any(x.strip() for x in lines[z:start - 1]):
            continue
        skip = 0  # the evidence line is the comment's first line of text, not its `/**`
        for row in m.group(1).split("\n"):
            if row.strip().lstrip("*").strip():
                break
            skip += 1
        best = (m.group(1), a + skip)
    if best is None:
        return None
    sentence = first_sentence(doc_body(best[0]))
    return (sentence, best[1]) if sentence else None


# ---------------------------------------------------------------- the project


class Project:
    def __init__(self, root: Path, facts: dict):
        self.root, self.facts = root, facts
        self.sources = Sources(root)
        self.summary_rx, self.prose_rx = template_patterns()
        self.facts_by_manifest = {s["manifest"]: s for s in facts.get("slices") or [] if s.get("manifest")}
        self.messages = {}
        for m in facts.get("messages") or []:
            if m.get("found"):
                self.messages.setdefault((m["name"], m["type"]), [x.split(":", 1)[0].strip() for x in m["fields"]])
        self.lanes = {b["name"]: b.get("lane") or {} for b in facts.get("bcs") or []}
        self.rows, self.docs, self.skipped = [], {}, []
        for rel, doc, error, _ in si.load(root, False):
            path = (rel + "/slice.yaml") if rel != "." else "slice.yaml"
            if doc is None:
                self.skipped.append({"path": path, "why": f"{error} — /essentials:slice-check --fix-manifests first"})
                continue
            gen = doc.get("generator")
            if gen is not None and gen != "essentials":
                self.skipped.append({"path": path, "why": f"generator: {gen} — its generator owns flow and scenarios"})
                continue
            row = si.normalise(rel, doc)
            row["_manifest"] = path
            self.rows.append(row)
            self.docs[path] = doc
        self.rows.sort(key=lambda r: (r["id"], r["_manifest"]))
        self.manifest_count = len(self.rows) + len(self.skipped)

    def facts_of(self, row):
        return self.facts_by_manifest.get(row["_manifest"]) or {}

    def slice_files(self, row):
        fx = self.facts_of(row)
        out = []
        for name in fx.get("files") or []:
            if Path(name).suffix in ss.SOURCE_EXT:
                f, text = self.sources.get(f"{fx['dir']}/{name}")
                if f is not None:
                    out.append((f, text))
        return sorted(out, key=lambda x: x[0].rel)


# ---------------------------------------------------------------- absent fields


def is_placeholder_summary(summary_rx, value):
    if value is None or (isinstance(value, str) and not value.strip()):
        return True
    return isinstance(value, str) and any(rx.match(value) for rx in summary_rx)


def absent_fields(project, row, doc):
    out = []
    for field in FIELDS:
        if field == "summary":
            if is_placeholder_summary(project.summary_rx, doc.get("summary")):
                out.append(field)
        elif field == "consistency":
            projections = doc.get("projections")
            if isinstance(projections, list) and any(isinstance(p, dict) and "consistency" not in p
                                                     for p in projections):
                out.append(field)
        elif field == "maps":
            if row["kind"] == "translation" and "maps" not in doc:
                out.append(field)
        elif field not in doc:
            out.append(field)
    return out


# ---------------------------------------------------------------- journeys (flow)


def is_entry(row):
    """Design §5.3: a slice with an endpoint, a schedule, or an inbound translation."""
    if row["endpoints"] or row["_raw_schedule"] is not None:
        return True
    return row["kind"] == "translation" and row["direction"] in (None, "inbound", "both")


def journeys(rows):
    """Connected components of the non-view slices under 'publishes an event the other consumes'
    (consumes ∪ projections[].from) and 'dispatches a command the other handles'; each view joins
    every component that publishes an event it projects (design §5.3). A component with no edge at
    all is no evidence of a journey and is not proposed."""
    nonview = [r for r in rows if r["kind"] != "view"]
    views = [r for r in rows if r["kind"] == "view"]
    edges = []
    for a in nonview:
        for b in nonview:
            if a is b:
                continue
            edges += [{"from": a["id"], "to": b["id"], "via": e, "kind": "feeds"}
                      for e in sorted(set(a["publishes"]) & set(si.inbound(b)))]
            edges += [{"from": a["id"], "to": b["id"], "via": c, "kind": "dispatches"}
                      for c in sorted(set(a["dispatches"]) & set(b["handles"]))]
    parent = {r["id"]: r["id"] for r in nonview}

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for e in edges:
        ra, rb = find(e["from"]), find(e["to"])
        if ra != rb:
            parent[max(ra, rb)] = min(ra, rb)
    comps = {}
    for r in nonview:
        comps.setdefault(find(r["id"]), []).append(r)
    by_id = {r["id"]: r for r in rows}
    out = []
    for key in sorted(comps):
        ids = {r["id"] for r in comps[key]}
        evidence = [e for e in edges if e["from"] in ids]
        for v in views:
            for e in sorted(set(si.inbound(v))):
                evidence += [{"from": r["id"], "to": v["id"], "via": e, "kind": "projects"}
                             for r in comps[key] if e in r["publishes"]]
        if not evidence:
            continue
        members = [by_id[i] for i in sorted(ids | {e["to"] for e in evidence})]
        order, ranked = sa.causal_order(members)
        inside = {e["to"] for e in evidence}
        entries = [i for i in order if i not in inside and is_entry(by_id[i])] or ranked
        declared = sorted({n for r in members for n in sa.normalise_flow(r["_raw_flow"])})
        name = declared[0] if declared else kebab(by_id[sorted(entries)[0]]["name"])
        if not FLOW_NAME.match(name):
            name = re.sub(r"[^a-z0-9_-]+", "-", name.lower()).strip("-_") or "journey"
        j = {"suggestedName": name, "declared": declared[0] if declared else None, "slices": order,
             "entries": entries, "bcs": sorted({r["bc"] for r in members if r["bc"]}),
             "evidence": sorted(evidence, key=lambda e: (e["from"], e["to"], e["via"], e["kind"]))}
        if len(declared) > 1:
            j["alsoDeclared"] = declared[1:]
        out.append(j)
    out.sort(key=lambda j: (j["entries"][:1], j["slices"]))
    for n, j in enumerate(out, 1):
        j["id"] = f"j{n}"
    return [{"id": j.pop("id"), **j} for j in out]


# ---------------------------------------------------------------- tests → slices (scenarios)


def map_tests(project, rows):
    """{slice id: [(test class, lexed file, ambiguous simple name)]} and the ambiguous classes.
    Design §5.4: (1) the test's package is the slice's package or under it; else (2) its name starts
    with a type declared in the slice's directory. Several candidates: the longest package match
    wins, a tie maps to none."""
    classes = (project.facts.get("tests") or {}).get("classes") or []
    pkg_of = {r["id"]: project.facts_of(r).get("package") for r in rows}
    types_of = {r["id"]: {td.name for f, _ in project.slice_files(r) for td in f.types if td.outer is None}
                for r in rows}
    count = {}
    for c in classes:
        count[c["name"]] = count.get(c["name"], 0) + 1
    mapped, ambiguous = {}, []
    for c in classes:
        if not c.get("methods"):
            continue
        f, _ = project.sources.get(c["path"])
        if f is None or not f.package:
            continue
        pkg = f.package
        cands = [r for r in rows if pkg_of[r["id"]] and (pkg == pkg_of[r["id"]] or pkg.startswith(pkg_of[r["id"]] + "."))]
        if not cands:
            outer = c["name"].split(".", 1)[0]
            cands = [r for r in rows if any(outer.startswith(t) for t in types_of[r["id"]])]
        if not cands:
            continue

        def depth(r):
            p, q, n = (pkg_of[r["id"]] or "").split("."), pkg.split("."), 0
            while n < min(len(p), len(q)) and p[n] == q[n]:
                n += 1
            return n

        best = max(depth(r) for r in cands)
        top = [r for r in cands if depth(r) == best]
        if len(top) > 1:
            ambiguous.append({"class": c["name"], "path": c["path"], "slices": sorted(r["id"] for r in top)})
            continue
        mapped.setdefault(top[0]["id"], []).append((c, f, count[c["name"]] > 1))
    return mapped, sorted(ambiguous, key=lambda a: (a["path"], a["class"]))


def test_ref(project, c, method, ambiguous_name):
    """`Class#method`, or `path/from/test/root/File.java#method` when the class name is not unique."""
    if not ambiguous_name:
        return f"{c['name']}#{method}"
    path = c["path"]
    for root in (project.facts.get("tests") or {}).get("roots") or []:
        if path.startswith(root + "/"):
            path = path[len(root) + 1:]
            break
    return f"{path}#{method}"


def message_step(project, f, rng, type_):
    con = construction(f, *rng)
    if con is None:
        return None
    name, lp = con
    step = {type_: name}  # manifest shape: one message key, plus `with`
    fields = project.messages.get((name, type_))
    args = args_of(f, lp)
    if fields and len(args) == len(fields):
        values = [literal(f, r) for r in args]
        if all(ok for ok, _ in values):
            step["with"] = dict(zip(fields, (v for _, v in values)))
    return step


def unwrap_list(f, rng):
    """The element ranges of `List.of(…)` / `listOf(…)` / `Arrays.asList(…)`, or None."""
    a, b = rng
    for pattern in (("List", ".", "of"), ("listOf",), ("Arrays", ".", "asList"), ("mutableListOf",)):
        n = len(pattern)
        if all(f.tok(a + i) is not None and f.toks[a + i].t == pattern[i] for i in range(n)) \
                and f.is_op(a + n, "(") and f.match[a + n] + 1 == b:
            return args_of(f, a + n)
    return None


def class_literal(f, rng):
    """`X.class` or `X::class` / `X::class.java` → X, else None."""
    a, b = rng
    name, j = f.qname(a)  # `X.class` reads as one dotted name: `class` lexes as an identifier
    if name.endswith(".class") and j == b:
        return name[:-len(".class")].rsplit(".", 1)[-1] or None
    kotlin = f.is_op(j, "::") and f.is_id(j + 1, "class") and (
        j + 2 == b or (f.is_op(j + 2, ".") and f.is_id(j + 3, "java") and j + 4 == b))
    return name.rsplit(".", 1)[-1] if name and kotlin else None


def extract_gwt(project, f, m):
    """What a GivenWhenThenScenario chain in one test method states, part by part, already in the
    manifest's scenario shape (`given` steps, `when` step, `then` steps / {error, message?} /
    {nothing: true}); a part the chain does not show as a literal construction is None. None when the
    method holds no such chain."""
    if m is None or m.body is None or not any(t.k == "id" and t.t == GWT for t in f.toks):
        return None
    a, b = m.body
    calls = {}
    for i in range(a, b):
        if f.is_op(i, ".") and f.is_id(i + 1) and f.is_op(i + 2, "(") and f.toks[i + 1].t in GWT_CALLS:
            calls.setdefault(f.toks[i + 1].t, i + 2)
    if "when" not in calls:
        return None
    out: dict = {"given": None, "when": None, "then": None}
    if "given" in calls:
        args = args_of(f, calls["given"])
        if len(args) == 1 and (inner := unwrap_list(f, args[0])) is not None:
            args = inner
        steps = [message_step(project, f, r, "event") for r in args]
        out["given"] = None if any(s is None for s in steps) else steps
    args = args_of(f, calls["when"])
    if len(args) == 1:
        out["when"] = message_step(project, f, args[0], "command")
    if "then" in calls:
        args = args_of(f, calls["then"])
        if len(args) == 1 and (step := message_step(project, f, args[0], "event")) is not None:
            out["then"] = [step]
    elif "thenExpectNoEvent" in calls:
        out["then"] = {"nothing": True}
    elif "thenFailsWithExceptionType" in calls:
        args = args_of(f, calls["thenFailsWithExceptionType"])
        if args and (name := class_literal(f, args[0])) is not None:
            out["then"] = {"error": name}
            if len(args) > 1 and (msg := string_arg(f, args[1])) is not None:
                out["then"]["message"] = msg
    elif "thenFailsWithException" in calls:
        args = args_of(f, calls["thenFailsWithException"])
        if len(args) == 1 and (con := construction(f, *args[0])) is not None:
            out["then"] = {"error": con[0]}
            inner = args_of(f, con[1])
            if inner and (msg := string_arg(f, inner[0])) is not None:
                out["then"]["message"] = msg
    return out


def scenario_tests(project, row, mapped):
    tests = []
    for c, f, dup in sorted(mapped.get(row["id"], []), key=lambda x: (x[0]["path"], x[0]["name"])):
        methods = {(m.name, m.line): m for m in f.methods}
        for tm in c["methods"]:
            tests.append({
                "class": c["name"], "method": tm["name"], "path": c["path"], "line": tm["line"],
                "displayName": tm.get("displayName"),
                "name": tm.get("displayName") or humanise_method(tm["name"]),
                "test": test_ref(project, c, tm["name"], dup),
                "extracted": extract_gwt(project, f, methods.get((tm["name"], tm["line"]))),
            })
    return tests


# ---------------------------------------------------------------- summary, invariants, the rest


def main_types(row, files):
    """The type named after the slice, then its deciders, handlers and projectors."""
    want = pascal(row["name"])
    named, roles = [], []
    for f, text in files:
        for td in f.types:
            if td.outer is not None:
                continue
            if td.name == want:
                named.append((f, text, td))
            elif set(td.super_bases()) & (ss.DECIDER_BASES | ss.COMMAND_HANDLER_BASES | ss.PROCESSOR_BASES) \
                    or td.name.endswith(MAIN_TYPE_SUFFIXES):
                roles.append((f, text, td))
    return named + roles


def summary_proposal(project, row, files):
    for f, text, td in main_types(row, files):
        found = javadoc(f, text, td)
        if found is None or any(rx.match(found[0]) for rx in project.prose_rx):
            continue
        return {"draft": clip(found[0]), "evidence": [ev(f.rel, found[1], found[0])]}
    fx = project.facts_of(row)
    p = project.root / fx["dir"] / "CLAUDE.md" if fx else None
    if p is not None and p.is_file():
        found = first_paragraph(p.read_text(encoding="utf-8", errors="replace"))
        if found:
            sentence = first_sentence(found[0])
            if sentence and not any(rx.match(sentence) for rx in project.prose_rx):
                return {"draft": clip(sentence), "evidence": [ev(f"{fx['dir']}/CLAUDE.md", found[1], sentence)]}
    return None


def enforcing(td):
    """A decider, a command handler or an aggregate: where a throw is a business rule."""
    if set(td.super_bases()) & (ss.DECIDER_BASES | ss.COMMAND_HANDLER_BASES | AGGREGATE_BASES):
        return True
    if td.name.endswith(("Decider", "Handler")):
        return True
    return any(a.simple in ss.HANDLER_ANNS for m in td.methods for a in m.anns)


def null_guarded(f, i):
    """The throw at token i is a null guard: `?: throw`, or `if (… null …) [{] throw`."""
    if f.is_op(i - 1, "?:"):
        return True
    k = i - 1
    if f.is_op(k, "{"):
        k -= 1
    if f.is_op(k, ")"):
        lp = f.match[k]
        if f.is_id(lp - 1, "if"):
            return any(t.k == "id" and t.t in ("null", "isNull") for t in f.toks[lp:k])
    return False


def guards(f):
    """(token index, message, source text) of every business-rule guard in a file: FailFast
    requireTrue/requireFalse, Kotlin require/check with a message, and a throw with a message inside
    a decider, handler or aggregate method. requireNonNull and null guards are plumbing, not rules."""
    hits = []
    for i, t in enumerate(f.toks):
        if t.k != "id":
            continue
        prev = f.tok(i - 1)
        dotted = prev is not None and prev.k == "op" and prev.t in (".", "?.")
        if t.t in ("requireTrue", "requireFalse") and f.is_op(i + 1, "("):
            if dotted and not f.is_id(i - 2, "FailFast"):
                continue
            args = args_of(f, i + 1)
            if len(args) == 2 and (msg := string_arg(f, args[1])) is not None:
                hits.append((i, msg, f.text(i - 2 if dotted else i, f.match[i + 1] + 1)))
        elif f.kotlin and t.t in ("require", "check") and not dotted and f.is_op(i + 1, "("):
            rp = f.match[i + 1]
            if f.is_op(rp + 1, "{") and f.match[rp + 1] == rp + 3 and (msg := string_arg(f, (rp + 2, rp + 3))):
                hits.append((i, msg, f.text(i, rp + 4)))
        elif t.t == "throw" and not null_guarded(f, i):
            j = i + 1
            if f.is_id(j, "new"):
                j += 1
            elif not f.kotlin:
                continue
            name, j = f.qname(j)
            if not name or not f.is_op(j, "("):
                continue
            m = f.method_at(i)
            if m is None or m.owner is None or not enforcing(m.owner):
                continue
            args = args_of(f, j)
            msg = next((s for s in (string_arg(f, r) for r in args) if s is not None), None)
            if msg is not None:
                hits.append((i, msg, f.text(i, f.match[j] + 1)))
    return hits


def invariant_proposals(files):
    out, seen = [], set()
    for f, _ in files:
        for i, msg, text in guards(f):
            if msg in seen:
                continue
            seen.add(msg)
            m = f.method_at(i)
            owner = m.owner if m is not None and m.owner is not None else f.enclosing_type(i)
            where = owner.name if owner is not None else Path(f.rel).stem
            if m is not None and m.owner is not None and m.name != m.owner.name:
                where += "#" + m.name
            out.append({"text": msg, "enforcedBy": where, "evidence": ev(f.rel, f.toks[i].line, text)})
    return out[:MAX_INVARIANTS]


def consistency_proposals(project, row, doc):
    """design §5.4: the projector's processor type — InTransactionEventProcessor is strong, the rest eventual."""
    procs = project.facts_of(row).get("processors") or []
    out = []
    for p in doc.get("projections") or []:
        if not isinstance(p, dict) or "consistency" in p or not isinstance(p.get("name"), str):
            continue
        match = [x for x in procs if x["class"] == p["name"]] or (procs if len(procs) == 1 else [])
        if len(match) != 1:
            continue
        x = match[0]
        sep = " : " if x["file"].endswith(".kt") else " extends "
        out.append({"projection": p["name"], "value": "strong" if x["base"] == "InTransactionEventProcessor"
                    else "eventual", "evidence": ev(x["file"], x["line"], f"{x['class']}{sep}{x['base']}")})
    return out


def feature_flag_proposal(files):
    """@ConditionalOnProperty on a type of the slice → {property, havingValue} (manifest-guide.md §3).
    Several annotations that disagree give the evidence and no value."""
    found = []
    for f, _ in files:
        for td in f.types:
            for a in td.anns:
                if a.simple != "ConditionalOnProperty":
                    continue
                args = f.ann_args(a)

                def arg(key):
                    return string_arg(f, args[key]) if key in args else None

                name, prefix, value = arg("name") or arg("value"), arg("prefix"), None
                if name:
                    value = {"property": f"{prefix.rstrip('.')}.{name}" if prefix else name}
                    if (having := arg("havingValue")) is not None:
                        value["havingValue"] = having
                found.append((value, ev(f.rel, a.line, f.text(a.start, a.end))))
    if not found:
        return None
    one = len({json.dumps(v, sort_keys=True) for v, _ in found}) == 1
    return {"value": found[0][0] if one else None, "evidence": [e for _, e in found]}


def maps_proposal(project, row, files):
    """A translation's methods that construct a command or event: the evidence the model drafts `{from, to}` from."""
    known = {n for n, _ in project.messages} | set(row["handles"] + row["dispatches"] + row["publishes"]
                                                   + row["consumes"])
    out = []
    for f, _ in files:
        for m in f.methods:
            if m.body is None or m.owner is None:
                continue
            for i in range(*m.body):
                con = None
                if f.is_id(i, "new"):
                    _, j = f.qname(i + 1)
                    if f.is_op(j, "<"):
                        j = f.skip_angles(j)
                    if f.is_op(j, "("):
                        con = construction(f, i, f.match[j] + 1)
                elif f.kotlin and f.is_id(i) and f.is_op(i + 1, "(") and not f.is_op(i - 1, "."):
                    con = construction(f, i, f.match[i + 1] + 1)
                if con is None or con[0] not in known:
                    continue
                params = ", ".join(p.type.text for p in m.params if p.type is not None)
                out.append(ev(f.rel, f.toks[i].line, f"{m.owner.name}#{m.name}({params}) → {con[0]}"))
    return {"evidence": out} if out else None


def resolve_tier(project, row, rows):
    """`slice-check --adopt-tier`'s value resolved for the owning BC, decided only where no judgement
    is needed: the BC's lane — the one every manifest that declares a lane agrees on, else the lane
    slice-source detected — through manifest-guide.md §3's lane → tier table (decider is cqrs-es only
    with event-store use in the BC), and the one tier the BC's other manifests declare. The two must
    agree; anything else proposes nothing."""
    bc = row["bc"]
    siblings = [r for r in rows if r["bc"] == bc]
    facts = project.lanes.get(bc) or {}
    declared = sorted({r["lane"] for r in siblings if r["lane"]})
    detected = facts.get("detected")
    lane = declared[0] if len(declared) == 1 else (
        detected if not declared and detected in ("decider", "aggregate", "service-entity") else None)
    from_lane = why = None
    if lane in LANE_TIER:
        from_lane, why = LANE_TIER[lane], f"lane {lane}"
    elif lane == "decider" and (facts.get("signals") or {}).get("eventStore"):
        from_lane, why = "cqrs-es", "lane decider, with event-store use in the context"
    tiers = sorted({r["tier"] for r in siblings if r["tier"]})
    if len(tiers) > 1 or (tiers and tiers[0] not in TIERS):
        return None
    from_bc = tiers[0] if tiers else None
    if from_lane and from_bc and from_lane != from_bc:
        return None
    if from_lane:
        also = f"; the context's other manifests declare {from_bc}" if from_bc else ""
        return {"value": from_lane, "from": f"{why} (manifest-guide.md §3 lane → tier){also}"}
    if from_bc:
        return {"value": from_bc, "from": f"the context's other manifests declare {from_bc}"}
    return None


# ---------------------------------------------------------------- propose


def propose(root: Path, root_arg: str, facts: dict, bc, fields):
    project = Project(root, facts)
    if project.manifest_count == 0:
        return None
    rows = project.rows
    js = journeys(rows) if "flow" in fields else []
    mapped, ambiguous = map_tests(project, rows) if "scenarios" in fields else ({}, [])
    in_journeys = {}
    for j in js:
        for sid in j["slices"]:
            in_journeys.setdefault(sid, []).append(j["id"])
    slices = []
    for r in rows:
        if bc is not None and r["bc"] != bc:
            continue
        doc = project.docs[r["_manifest"]]
        absent = [f for f in absent_fields(project, r, doc) if f in fields]
        files = project.slice_files(r)
        proposals = {}
        for field in absent:
            p = None
            if field == "flow" and r["id"] in in_journeys:
                p = {"journeys": in_journeys[r["id"]]}
            elif field == "scenarios":
                tests = scenario_tests(project, r, mapped)
                p = {"tests": tests, "unmapped": not tests}
            elif field == "summary":
                p = summary_proposal(project, r, files)
            elif field == "invariants":
                p = invariant_proposals(files)
            elif field == "tier":
                p = resolve_tier(project, r, rows)
            elif field == "consistency":
                p = consistency_proposals(project, r, doc)
            elif field == "featureFlag":
                p = feature_flag_proposal(files)
            elif field == "maps":
                p = maps_proposal(project, r, files)
            if p:
                proposals[field] = p
        slices.append({"id": r["id"], "path": r["_manifest"], "kind": r["kind"], "bc": r["bc"],
                       "absent": absent, "proposals": proposals})
    if bc is not None:
        js = [j for j in js if bc in j["bcs"]]
        ambiguous = [a for a in ambiguous if any(s.split(".", 1)[0] == bc for s in a["slices"])]
    return {
        "schema": "slice-enrich/1",
        "root": root_arg,
        "fields": [f for f in FIELDS if f in fields],
        "journeys": js,
        "slices": slices,
        "ambiguousTests": ambiguous,
        "skipped": sorted(project.skipped, key=lambda s: s["path"]),
        "notInferable": sorted({f for r in rows for f in NOT_INFERABLE if f not in project.docs[r["_manifest"]]
                                and (bc is None or r["bc"] == bc)}),
    }


def print_text(data):
    out = [f"slice-enrich propose — {data['root']}", f"  journeys: {len(data['journeys'])}"]
    for j in data["journeys"]:
        tag = f" (extends declared flow {j['declared']})" if j["declared"] else ""
        out.append(f"    {j['id']} {j['suggestedName']}{tag}: " + " → ".join(j["slices"]))
    for s in data["slices"]:
        bare = [x for x in s["absent"] if x not in s["proposals"]]
        shown = [f"{k} (no test maps to it)" if k == "scenarios" and v["unmapped"] else k
                 for k, v in s["proposals"].items()]
        if shown or bare:
            out.append(f"  {s['id']}: proposes " + (", ".join(shown) or "nothing")
                       + (f"; absent without evidence: {', '.join(bare)}" if bare else ""))
    for a in data["ambiguousTests"]:
        out.append(f"  ambiguous test {a['class']} ({a['path']}): " + ", ".join(a["slices"]))
    for s in data["skipped"]:
        out.append(f"  skipped {s['path']}: {s['why']}")
    if data["notInferable"]:
        out.append("  not inferable when absent: " + ", ".join(data["notInferable"]))
    print("\n".join(out))


# ---------------------------------------------------------------- apply: YAML text


def scalar(value, key=None):
    """A YAML scalar: plain when it reads back unchanged, else double-quoted. A `path` is always quoted."""
    import yaml

    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return json.dumps(value)
    s = str(value)
    if key != "path" and s and s == s.strip() and "\n" not in s:
        try:
            if yaml.safe_load(f"k: {s}") == {"k": s} and yaml.safe_load(f"- {s}") == [s]:
                return s
        except yaml.YAMLError:
            pass
    return json.dumps(s, ensure_ascii=False)


KEY_ORDER = ["name", "id", "text", "enforcedBy", "property", "havingValue", "from", "to", "given", "when",
             "then", "event", "command", "query", "state", "external", "schedule", "with", "error", "message",
             "nothing", "readModel", "rows", "test", "note"]


def ordered(d: dict):
    rank = {k: n for n, k in enumerate(KEY_ORDER)}
    keys = list(d)
    return sorted(d.items(), key=lambda kv: (rank.get(kv[0], len(rank)), keys.index(kv[0])))


def emit(key, value, indent):
    """Block-style lines for `key: value` at `indent`; an empty list or mapping is `[]` / `{}`."""
    pad = " " * indent
    if isinstance(value, dict):
        if not value:
            return [f"{pad}{key}: {{}}"]
        return [f"{pad}{key}:"] + [line for k, v in ordered(value) for line in emit(k, v, indent + 2)]
    if isinstance(value, list):
        if not value:
            return [f"{pad}{key}: []"]
        return [f"{pad}{key}:"] + [line for item in value for line in emit_item(item, indent + 2)]
    return [f"{pad}{key}: {scalar(value, key)}"]


def emit_item(item, indent):
    pad = " " * indent
    if isinstance(item, dict) and item:
        inner = [line for k, v in ordered(item) for line in emit(k, v, indent + 2)]
        inner[0] = pad + "- " + inner[0][indent + 2:]
        return inner
    if isinstance(item, (dict, list)):
        return [f"{pad}- {json.dumps(item)}"]
    return [f"{pad}- {scalar(item)}"]


TOP_KEY = re.compile(r"^([A-Za-z_][\w-]*)[ \t]*:")


def top_line(lines, key):
    return next((n for n, line in enumerate(lines) if (m := TOP_KEY.match(line)) and m.group(1) == key), None)


def block_end(lines, start):
    """Index after the last content line of the top-level value that starts on line `start`."""
    n = start + 1
    while n < len(lines) and (not lines[n].strip() or lines[n][:1] in (" ", "\t") or lines[n].startswith("#")):
        n += 1
    while n - 1 > start and (not lines[n - 1].strip() or lines[n - 1].lstrip().startswith("#")):
        n -= 1
    return n


def version_tuple(v):
    try:
        return tuple(int(x) for x in str(v).split("."))
    except ValueError:
        return None


class Refused(Exception):
    pass


def check_absent(summary_rx, doc, accept):
    """The accepted fields still to write. A field present since propose with another value refuses
    the manifest; present with the accepted value is already done."""
    todo = {}
    for field, value in accept.items():
        if field not in FIELDS:
            raise Refused(f"`{field}` is not a field slice-enrich writes")
        if field == "consistency":
            if not isinstance(value, dict) or not all(v in ("strong", "eventual") for v in value.values()):
                raise Refused("`consistency` must map projection names to strong | eventual")
            projections = {p.get("name"): p for p in doc.get("projections") or [] if isinstance(p, dict)}
            want = {}
            for name, v in sorted(value.items()):
                p = projections.get(name)
                if p is not None and "consistency" in p:
                    if p["consistency"] != v:
                        raise Refused(f"projection {name} declares consistency: {p['consistency']} since propose")
                else:
                    want[name] = v
            if want:
                todo[field] = want
            continue
        if field == "summary":
            if not is_placeholder_summary(summary_rx, doc.get("summary")):
                if doc.get("summary") != value:
                    raise Refused("`summary` is present since propose")
                continue
            if not isinstance(value, str) or not value.strip():
                raise Refused("`summary` must be a non-empty string")
        elif field in doc:
            if doc[field] != value:
                raise Refused(f"`{field}` is present since propose")
            continue
        if field == "flow":
            names = value if isinstance(value, list) else [value]
            if not names or len(set(map(str, names))) != len(names) or \
                    not all(isinstance(n, str) and FLOW_NAME.match(n) for n in names):
                raise Refused("`flow` must be a name, or a list of distinct names, matching ^[a-z0-9][a-z0-9_-]*$")
        todo[field] = value
    return todo


def insert_consistency(lines, wanted):
    """Insert `consistency:` into each wanted block-mapping item of the root `projections:` sequence.
    Returns (projections written, refusals)."""
    import yaml

    refusals, done = [], []
    n = top_line(lines, "projections")
    if n is None or lines[n].split(":", 1)[1].split("#", 1)[0].strip():
        return [], [f"consistency for {name}: `projections` is not a block sequence — not rewritten"
                    for name in sorted(wanted)]
    end = block_end(lines, n)
    starts = [k for k in range(n + 1, end) if re.match(r"^\s*-(\s|$)", lines[k])]
    dash = len(lines[starts[0]]) - len(lines[starts[0]].lstrip()) if starts else 0
    starts = [k for k in starts if len(lines[k]) - len(lines[k].lstrip()) == dash]
    left = dict(wanted)
    for idx in reversed(range(len(starts))):
        a = starts[idx]
        z = starts[idx + 1] if idx + 1 < len(starts) else end
        try:
            item = yaml.safe_load("\n".join(lines[a:z]))
        except yaml.YAMLError:
            continue
        item = item[0] if isinstance(item, list) and item else None
        if not isinstance(item, dict) or item.get("name") not in left:
            continue
        name = item["name"]
        value = left.pop(name)
        if lines[a].lstrip()[1:].lstrip().startswith("{"):
            refusals.append(f"consistency for {name}: the projection is a flow mapping — not rewritten")
            continue
        last = z
        while last - 1 > a and (not lines[last - 1].strip() or lines[last - 1].lstrip().startswith("#")):
            last -= 1
        lines.insert(last, " " * (dash + 2) + f"consistency: {value}")
        done.append(name)
    refusals += [f"consistency for {name}: no such projection" for name in sorted(left)]
    return done, sorted(refusals)


def edit_manifest(summary_rx, text: str, accept: dict, doc: dict):
    """(new text, fields written, refusals of single entries). Raises Refused to leave the manifest untouched."""
    import yaml

    todo = check_absent(summary_rx, doc, accept)
    nl = "\r\n" if "\r\n" in text else "\n"
    lines = text.split(nl)
    trailing = bool(lines) and lines[-1] == ""
    if trailing:
        lines = lines[:-1]
    written, refusals = [], []

    insert = []
    for field in ("summary", "tier", "featureFlag", "maps", "invariants", "flow", "scenarios"):
        if field not in todo:
            continue
        if field == "summary" and (n := top_line(lines, "summary")) is not None:
            if block_end(lines, n) != n + 1 or re.match(r"^summary:[ \t]*[|>]", lines[n]):
                raise Refused("`summary` spans several lines; edit it by hand")
            lines[n] = f"summary: {scalar(todo[field])}"
        else:
            insert += emit(field, todo[field], 0)
        written.append(field)
    if insert:  # before `tests:` and the comments heading it, else at the end
        at = top_line(lines, "tests")
        if at is None:
            lines += insert
        else:
            while at > 0 and lines[at - 1].startswith("#"):
                at -= 1
            if at > 0 and not lines[at - 1].strip():
                insert = insert + [""]
            lines[at:at] = insert

    if "consistency" in todo:
        done, refusals = insert_consistency(lines, todo["consistency"])
        if done:
            written.append("consistency")

    if "flow" in written or "scenarios" in written:
        n = top_line(lines, "schemaVersion")
        if n is None:
            lines.insert(next((k for k, x in enumerate(lines) if TOP_KEY.match(x)), len(lines)), 'schemaVersion: "1.4"')
        else:
            raw = lines[n].split(":", 1)[1]
            try:
                current = version_tuple(yaml.safe_load(raw))
            except yaml.YAMLError:
                current = None
            if current is None or current < (1, 4):
                comment = re.search(r"\s+#.*$", raw)
                lines[n] = 'schemaVersion: "1.4"' + (comment.group(0) if comment else "")

    return nl.join(lines) + (nl if trailing else ""), written, refusals


def error_key(e):
    """An error's identity across two versions of one manifest. A oneOf/anyOf message embeds the whole
    instance, so it changes whenever any field is added; such an error is identified by its branches'
    errors instead, which is also what the report shows."""
    path = "/".join(map(str, e.absolute_path))
    if e.validator in ("oneOf", "anyOf") and e.context:
        branches = frozenset(error_key(c) for c in e.context)
        return (path, tuple(e.schema_path), branches)
    return (path, tuple(e.schema_path), e.message)


def error_text(key):
    path, _, what = key
    if isinstance(what, frozenset):
        what = "matches none of the alternatives (" + "; ".join(sorted(error_text(k) for k in what)) + ")"
    return f"{path or '(root)'}: {what}"


def schema_errors(validator, doc):
    return {error_key(e) for e in validator.iter_errors(doc)}


def apply(root: Path, accepted: dict, dry_run: bool):
    import yaml
    from jsonschema import validators

    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    validator = validators.validator_for(schema)(schema)
    summary_rx, _ = template_patterns()
    base = root.resolve()
    refused, report, diffs, writes = 0, [], [], []
    for s in sorted(accepted.get("slices") or [], key=lambda s: str(s.get("path"))):
        accept = s.get("accept")
        if not accept:
            continue
        rel = str(s.get("path") or "")
        target = (base / rel).resolve()
        if target.name != "slice.yaml" or base not in target.parents or not target.is_file():
            refused += 1
            report.append(f"refused  {rel}: not a slice.yaml under ROOT")
            continue
        text = target.read_bytes().decode("utf-8")
        try:
            doc = yaml.safe_load(text)
        except yaml.YAMLError:
            doc = None
        if not isinstance(doc, dict):
            refused += 1
            report.append(f"refused  {rel}: does not parse to a mapping")
            continue
        if not isinstance(accept, dict):
            refused += 1
            report.append(f"refused  {rel}: `accept` is not an object")
            continue
        gen = doc.get("generator")
        if gen is not None and gen != "essentials":
            refused += 1
            report.append(f"refused  {rel}: generator: {gen} owns this manifest")
            continue
        try:
            new, written, partial = edit_manifest(summary_rx, text, accept, doc)
            try:
                new_doc = yaml.safe_load(new)
            except yaml.YAMLError as exc:
                raise Refused(f"the result would not parse ({str(exc).splitlines()[0]})") from None
            for field in written:
                if field != "consistency" and new_doc.get(field) != accept[field]:
                    raise Refused(f"`{field}` would not read back as accepted")
            added = sorted(error_text(k) for k in schema_errors(validator, new_doc) - schema_errors(validator, doc))
            if added:
                raise Refused("the result fails the schema: " + "; ".join(added))
        except Refused as exc:
            refused += 1
            report.append(f"refused  {rel}: {exc}")
            continue
        refused += len(partial)
        report += [f"refused  {rel}: {p}" for p in partial]
        if new == text:
            report.append(f"unchanged {rel}")
            continue
        report.append(f"{'would write' if dry_run else 'written'} {rel}: " + ", ".join(written))
        if dry_run:
            diffs.append("".join(difflib.unified_diff(text.splitlines(keepends=True), new.splitlines(keepends=True),
                                                      f"a/{rel}", f"b/{rel}")))
        else:
            writes.append((target, new))
    for target, new in writes:
        target.write_bytes(new.encode("utf-8"))
    for d in diffs:
        sys.stdout.write(d if d.endswith("\n") else d + "\n")
    print("\n".join(report) if report else "nothing to write")
    return 1 if refused else 0


# ---------------------------------------------------------------- CLI


def main(argv=None):
    parser = argparse.ArgumentParser(prog="slice-enrich", description="Evidence for the absent human-owned "
                                     "fields of slice.yaml manifests, and the writer for the accepted ones.")
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("propose", help="every absent field and its evidence")
    p.add_argument("root")
    p.add_argument("--source-facts", required=True, help="`slice-source.py ROOT --json` output")
    p.add_argument("--bc", help="this bounded context's slices only")
    p.add_argument("--fields", help="comma-separated subset of: " + ", ".join(FIELDS))
    p.add_argument("--json", action="store_true")
    a = sub.add_parser("apply", help="write the accepted values")
    a.add_argument("root")
    a.add_argument("accepted")
    a.add_argument("--dry-run", action="store_true", help="print the diffs, write nothing")
    try:
        args = parser.parse_args(argv)
    except SystemExit as exc:
        return 2 if exc.code else 0
    try:
        import yaml  # noqa: F401
    except ImportError:
        print("slice-enrich: needs pyyaml — uv run --script slice-enrich.py …", file=sys.stderr)
        return 2
    root = Path(args.root)
    try:
        if not root.is_dir():
            raise UsageError(f"not a directory: {args.root}")
        if args.cmd == "propose":
            fields = FIELDS if not args.fields else [f.strip() for f in args.fields.split(",") if f.strip()]
            unknown = [f for f in fields if f not in FIELDS]
            if unknown:
                raise UsageError("unknown field(s): " + ", ".join(unknown) + " — one of " + ", ".join(FIELDS))
            try:
                facts = json.loads(Path(args.source_facts).read_text(encoding="utf-8"))
            except (OSError, ValueError) as exc:
                raise UsageError(f"cannot read --source-facts: {exc}") from None
            if not isinstance(facts, dict) or facts.get("tool") != "slice-source" or facts.get("mode") != "facts":
                raise UsageError("--source-facts is not the output of `slice-source.py ROOT --json`")
            data = propose(root.resolve(), args.root, facts, args.bc, fields)
            if data is None:
                print(f"slice-enrich: no slice.yaml under {args.root} — /essentials:slice-discover", file=sys.stderr)
                return 3
            if args.json:
                json.dump(data, sys.stdout, indent=2, ensure_ascii=False)
                sys.stdout.write("\n")
            else:
                print_text(data)
            return 0
        try:
            import jsonschema  # noqa: F401
        except ImportError:
            raise UsageError("apply validates against the schema and needs jsonschema — uv run --script") from None
        try:
            accepted = json.loads(Path(args.accepted).read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            raise UsageError(f"cannot read {args.accepted}: {exc}") from None
        if not isinstance(accepted, dict) or accepted.get("schema") != "slice-enrich/1":
            raise UsageError(f"{args.accepted} is not a slice-enrich/1 document")
        return apply(root, accepted, args.dry_run)
    except UsageError as exc:
        print(f"slice-enrich: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
