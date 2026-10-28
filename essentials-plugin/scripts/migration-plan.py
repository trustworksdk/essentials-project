#!/usr/bin/env python3
"""migration-plan — the ordered plan for moving a project to the Essentials release this plugin targets.

Why this exists
---------------
Moving an application from one Essentials release to the next is a fixed order of steps: clear the
deprecations on the old release, move a handful of version pins together, apply what only holds on the
new release, fix what the scripts can find, compile and fix what only the compiler can find, then decide
the behaviour changes no compiler sees. The order, the pins the release requires and which catalogue
ids belong to which step are data, kept in `references/stack/migrations/<from>-to-<to>.json`. That
file holds only order, pin NAMES (stack-pins.md is the one place a version is written), stack-lint check
slugs, `ESS-NNN` ids and `path:line` + token citations into the release's own notes. Detection stays in
the scripts that own it — review-scan.py's signatures and stack-lint.py's rules — and every fix stays
with the catalogue line or the fix descriptor those scripts emit. This script only joins them: it
matches no pattern of its own, and it reports and never writes.

Modes
-----
--check     validates every notes file: it parses and has the shape below; `from` is below `to`; the
            newest file's `to` is the `essentials.version` pin; every `ESS-NNN` is an active line of
            references/llm/LLM-traps.md (a retired one is named as such); every ESS id of a review-scan
            step has a review-scan signature; every stack-lint slug is in stack-lint.py's RULES and not
            in RETIRED_CHECKS; every pin is a row of stack-pins.md; every checklist phase has a
            checklist step and every checklist step has an item; every citation's line, in the
            repository beside the plugin, contains its token; and the commands/review.md Step 3e pair
            table names a review-scan check and a stack-lint check in every row.
PROJECT     prints the plan for the project at PROJECT: the chain of notes from the project's Essentials
            release (its `essentials.version` property, else an Essentials dependency's version, else
            --from) to the target; each required pin with the project's value and where it is set,
            against the stack-pins.md value (`move`, `ok`, `tolerated`, `not set` — a pin the project does
            not set is not moved — or `unknown`), with the fix op that moves it where the version is a
            literal at that site (`set-property`, `set-parent-version`, `set-dependency-version`, as
            commands/review.md § Applying a fix descriptor defines them); every step in order; each
            checklist item with its catalogue symptom and link; and the fixedIn ids, whose project
            workarounds the target makes removable.
            Unless --no-scan, it also runs `review-scan.py --tree PROJECT --json` and
            `stack-lint.py PROJECT --json` and files each finding, verbatim, under the step that lists
            its ESS id or check slug; a stack-lint finding marked `appliesWithUpgrade` that no step lists
            goes under the stack-lint step. The pins step's stack-lint findings are what its pin rows'
            moves fix. A stack-lint finding that is one defect with a listed review-scan finding (the pair
            table of commands/review.md Step 3e, read from there) is named on it as `also`, not listed
            twice. Findings no step lists are not part of the release move: they are counted, never
            listed (`/essentials:upgrade` Group C and `/essentials:review` own them).
            Running the scanners here keeps the plan one command; --no-scan prints the plan alone.

Notes file shape
----------------
    from, to                 release versions, `from` < `to`
    guide                    citation of the release's migration guide
    requiredPins[]           {pin, tolerated: [versions], reason, cite}
    steps[]                  {id, kind, title, cites[], ess?, stackLint?, release?, phase?}
                             kind: pins | stack-lint | review-scan | compile-and-fix | checklist;
                             compile-and-fix has release `from` or `to`; checklist has a phase
    checklist[]              {id: ESS-NNN, phase, cite}
    fixedIn[]                {id: ESS-NNN, reason, cite}
    a citation               {path (from the repository root), line, token}

Usage
-----
    migration-plan.py --check [--migrations DIR]
    migration-plan.py PROJECT [--from VERSION] [--no-scan] [--json] [--migrations DIR]
    migration-plan.py --self-test [--update-golden]

Exit codes: 0 plan printed (also when the project is already on the target) · 1 --check found a problem,
or --self-test failed · 2 could not run (no notes chain from the project's release, its release unknown
without --from, an unreadable POM, a scanner that could not run, an unreadable catalogue or pins file).

--self-test runs --check, then plans every directory under tests/migration-plan/ that holds a pom.xml
(options from its optional args.json: {"from": …, "noScan": true}) and compares each with its
`<case>.golden.json`; catalogue entries are reduced to their ids and findings to source, id, check and
place, so neither the catalogue's nor a scanner's wording is part of a golden.

Standard library only; Python 3.11+.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
import subprocess
import sys
from pathlib import Path
from types import ModuleType

PLUGIN = Path(__file__).resolve().parent.parent
REPO = PLUGIN.parent
MIGRATIONS = PLUGIN / "references" / "stack" / "migrations"
PINS = PLUGIN / "references" / "stack" / "stack-pins.md"
TRAPS = PLUGIN / "references" / "llm" / "LLM-traps.md"
REVIEW_MD = PLUGIN / "commands" / "review.md"
REVIEW_SCAN = PLUGIN / "scripts" / "review-scan.py"
STACK_LINT = PLUGIN / "scripts" / "stack-lint.py"
CASES = PLUGIN / "tests" / "migration-plan"

KINDS = ("pins", "stack-lint", "review-scan", "compile-and-fix", "checklist")
ESS_ID = re.compile(r"^ESS-\d{3}$")


class UsageError(Exception):
    pass


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise UsageError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod     # dataclasses resolve annotations through sys.modules
    spec.loader.exec_module(mod)
    return mod


_MODS: dict[str, ModuleType] = {}


def scripts() -> tuple[ModuleType, ModuleType]:
    """(review_scan, stack_lint), loaded once: their tables are the catalogue this script checks against."""
    if not _MODS:
        _MODS["rs"] = load_module("review_scan", REVIEW_SCAN)
        _MODS["sl"] = load_module("stack_lint", STACK_LINT)
    return _MODS["rs"], _MODS["sl"]


def version_tuple(value) -> tuple | None:
    m = re.match(r"^(\d+(?:\.\d+)*)", str(value or "").strip())
    return tuple(int(x) for x in m.group(1).split(".")) if m else None


def tolerates(entry, value) -> bool:
    """A `tolerated` entry covers a value it is a version prefix of: `4.0` covers 4.0.8."""
    x, v = version_tuple(entry), version_tuple(value)
    return x is not None and v is not None and v[:len(x)] == x


def below(a, b) -> bool | None:
    x, y = version_tuple(a), version_tuple(b)
    if x is None or y is None:
        return None
    n = max(len(x), len(y))
    return x + (0,) * (n - len(x)) < y + (0,) * (n - len(y))


# ---------------------------------------------------------------------------------------------
# The catalogue: traps (active and retired), signatures, stack-lint slugs, pins


class Catalogue:
    def __init__(self):
        rs, sl = scripts()
        try:
            text = TRAPS.read_text(encoding="utf-8")
        except OSError as exc:
            raise UsageError(f"cannot read {TRAPS}: {exc}")
        self.traps, self.retired, retired = {}, set(), False
        for no, line in enumerate(text.splitlines(), 1):
            if rs.RETIRED_HEADING.match(line):
                retired = True
            m = rs.TRAP_LINE.match(line)
            if m and m.group(1) == m.group(2):
                tid = f"ESS-{m.group(1)}"
                if retired:
                    self.retired.add(tid)
                else:
                    self.traps[tid] = {"line": no, "symptom": m.group("symptom"), "href": m.group("href")}
        self.signature_ids = {s.id for s in rs.SIGNATURES}
        self.signature_checks = {s.check for s in rs.SIGNATURES}
        self.rules, self.retired_checks = set(sl.RULES), set(sl.RETIRED_CHECKS)
        try:
            self.pins = sl.read_pins(PINS)
        except sl.UsageError as exc:
            raise UsageError(str(exc))
        blob = next((t["href"] for t in self.traps.values() if t["href"].startswith("https://github.com/")
                     and "/blob/" in t["href"]), None)
        self.blob = blob.split("/blob/")[0] + "/blob/" if blob else None

    def trap(self, tid: str) -> dict:
        t = self.traps[tid]
        link = t["href"] if re.match(r"^[a-z]+://", t["href"]) else f"references/llm/{t['href']}"
        return {"id": tid, "symptom": t["symptom"], "link": link,
                "cite": f"references/llm/LLM-traps.md:{t['line']}"}


# ---------------------------------------------------------------------------------------------
# Notes files


def load_notes(directory: Path) -> list[tuple[Path, dict]]:
    files = sorted(directory.glob("*.json"))
    if not files:
        raise UsageError(f"no migration notes under {directory}")
    out = []
    for f in files:
        try:
            out.append((f, json.loads(f.read_text(encoding="utf-8"))))
        except (OSError, ValueError) as exc:
            raise UsageError(f"{f}: not readable JSON: {exc}")
    out.sort(key=lambda fn: version_tuple(fn[1].get("from")) or ())
    return out


def check_cite(c, where: str, problems: list[str]):
    if not (isinstance(c, dict) and isinstance(c.get("path"), str) and isinstance(c.get("line"), int)
            and isinstance(c.get("token"), str) and c["token"]):
        problems.append(f"{where}: a citation is {{path, line, token}}: {c!r}")
        return
    p = REPO / c["path"]
    try:
        lines = p.read_text(encoding="utf-8").splitlines()
    except OSError:
        problems.append(f"{where}: {c['path']} does not exist in the repository")
        return
    if not 0 < c["line"] <= len(lines):
        problems.append(f"{where}: {c['path']}:{c['line']} is past the end of the file")
    elif c["token"] not in lines[c["line"] - 1]:
        problems.append(f"{where}: {c['path']}:{c['line']} no longer contains {c['token']!r}")


def check_notes(cat: Catalogue, notes: list[tuple[Path, dict]]) -> list[str]:
    problems: list[str] = []

    def ess(tid, where):
        if not (isinstance(tid, str) and ESS_ID.match(tid)):
            problems.append(f"{where}: {tid!r} is not an ESS-NNN id")
        elif tid in cat.retired:
            problems.append(f"{where}: {tid} is retired in references/llm/LLM-traps.md")
        elif tid not in cat.traps:
            problems.append(f"{where}: {tid} is not a line of references/llm/LLM-traps.md")

    for f, n in notes:
        rel = f.relative_to(PLUGIN).as_posix() if f.is_relative_to(PLUGIN) else str(f)
        for key in ("from", "to", "guide", "requiredPins", "steps", "checklist", "fixedIn"):
            if key not in n:
                problems.append(f"{rel}: no `{key}`")
        if any(k not in n for k in ("from", "to", "requiredPins", "steps", "checklist", "fixedIn")):
            continue
        if below(n["from"], n["to"]) is not True:
            problems.append(f"{rel}: `from` {n['from']!r} is not below `to` {n['to']!r}")
        if f.name != f"{'.'.join(str(n['from']).split('.')[:2])}-to-{'.'.join(str(n['to']).split('.')[:2])}.json":
            problems.append(f"{rel}: the file name does not say {n['from']} to {n['to']}")
        check_cite(n.get("guide"), f"{rel} guide", problems)
        for i, p in enumerate(n["requiredPins"]):
            where = f"{rel} requiredPins[{i}]"
            if p.get("pin") not in cat.pins:
                problems.append(f"{where}: {p.get('pin')!r} is not a row of references/stack/stack-pins.md")
            if not isinstance(p.get("tolerated"), list):
                problems.append(f"{where}: `tolerated` is a list of versions ([] when none)")
            if not p.get("reason"):
                problems.append(f"{where}: no `reason`")
            check_cite(p.get("cite"), where, problems)
        phases_used, ids = set(), set()
        for i, s in enumerate(n["steps"]):
            where = f"{rel} steps[{i}] {s.get('id')}"
            if s.get("id") in ids or not s.get("id"):
                problems.append(f"{where}: step ids are unique and non-empty")
            ids.add(s.get("id"))
            kind = s.get("kind")
            if kind not in KINDS:
                problems.append(f"{where}: kind {kind!r} is not one of {', '.join(KINDS)}")
            if not s.get("title"):
                problems.append(f"{where}: no `title`")
            if kind == "compile-and-fix" and s.get("release") not in ("from", "to"):
                problems.append(f"{where}: a compile-and-fix step names its release, `from` or `to`")
            if kind == "checklist":
                if not s.get("phase"):
                    problems.append(f"{where}: a checklist step names its phase")
                phases_used.add(s.get("phase"))
            if not s.get("cites"):
                problems.append(f"{where}: no `cites`")
            for c in s.get("cites", []):
                check_cite(c, where, problems)
            for tid in s.get("ess", []):
                ess(tid, where)
                if kind == "review-scan" and tid not in cat.signature_ids:
                    problems.append(f"{where}: {tid} has no review-scan signature")
            for slug in s.get("stackLint", []):
                if slug in cat.retired_checks:
                    problems.append(f"{where}: stack-lint check {slug} is retired")
                elif slug not in cat.rules:
                    problems.append(f"{where}: {slug} is not a stack-lint check")
        seen = set()
        for i, c in enumerate(n["checklist"]):
            where = f"{rel} checklist[{i}]"
            ess(c.get("id"), where)
            if c.get("id") in seen:
                problems.append(f"{where}: {c.get('id')} is listed twice")
            seen.add(c.get("id"))
            if c.get("phase") not in phases_used:
                problems.append(f"{where}: phase {c.get('phase')!r} has no checklist step")
            check_cite(c.get("cite"), where, problems)
        for ph in phases_used - {c.get("phase") for c in n["checklist"]}:
            problems.append(f"{rel}: checklist step phase {ph!r} has no item")
        for i, c in enumerate(n["fixedIn"]):
            where = f"{rel} fixedIn[{i}]"
            ess(c.get("id"), where)
            if not c.get("reason"):
                problems.append(f"{where}: no `reason`")
            check_cite(c.get("cite"), where, problems)
    try:
        pairs = read_pairs()
    except UsageError as exc:
        pairs = set()
        problems.append(str(exc))
    if not pairs:
        problems.append("commands/review.md Step 3e: no `review-scan check` | `stack-lint check` pair rows")
    for a, b in sorted(pairs):
        if a not in {s for s in cat.signature_checks}:
            problems.append(f"commands/review.md Step 3e: {a} is not a review-scan check")
        if b not in cat.rules:
            problems.append(f"commands/review.md Step 3e: {b} is not a stack-lint check")
    froms = [n.get("from") for _, n in notes]
    if len(set(froms)) != len(froms):
        problems.append("two notes files start from the same release")
    newest = max((n for _, n in notes), key=lambda n: version_tuple(n.get("to")) or ())
    if newest.get("to") != cat.pins.get("essentials.version"):
        problems.append(f"the newest notes end at {newest.get('to')!r}, not at the essentials.version pin "
                        f"{cat.pins.get('essentials.version')!r}")
    return problems


def chain_for(notes: list[tuple[Path, dict]], start: str, target: str) -> list[tuple[Path, dict]]:
    """The notes that take `start` to `target`, in order; [] when `start` is already at or past it."""
    if below(start, target) is not True:
        return []
    out, cur = [], start
    while below(cur, target):
        step = next(((f, n) for f, n in notes if below(cur, n["from"]) is False and below(cur, n["to"])), None)
        if step is None:
            raise UsageError(f"no migration notes from Essentials {cur} (notes start at {notes[0][1]['from']})")
        out.append(step)
        cur = step[1]["to"]
    return out


# ---------------------------------------------------------------------------------------------
# The project: pin values, read with stack-lint's POM model


def read_project(path: Path):
    _, sl = scripts()
    try:
        proj = sl.Project(path)
    except sl.UsageError as exc:
        raise UsageError(str(exc))
    if not proj.poms:
        raise UsageError(f"{path}: no pom.xml")
    proj.poms.sort(key=lambda p: (p.rel.count("/"), p.rel))
    return proj


# Where a project states a pin's version under another name: Spring Boot's version when the project imports
# the Boot BOM instead of inheriting the parent (stack-pins.md names the parent).
PIN_ALIASES = {"spring-boot-starter-parent": ("spring-boot-dependencies", "spring-boot.version")}


class PinSite:
    """Where the project sets a pin: the resolved value, file:line, and the fix op that moves it — written
    only where the version is a literal at that site (a `${…}` is moved where it is defined, by its own row)."""

    def __init__(self, value, pom, line, op):
        self.value, self.at, self._op = value, f"{pom.rel}:{line}", op

    def ops(self, target: str) -> list[dict]:
        return [{**self._op, "to": target}] if self._op else []


def pin_site(proj, name: str) -> PinSite | None:
    """A property of that name, else the parent, dependency or plugin with that artifactId, else the same
    for its PIN_ALIASES. None when the project does not set the pin."""
    for n in (name, *PIN_ALIASES.get(name, ())):
        site = _pin_site(proj, n)
        if site is not None:
            return site
    return None


def _literal(v) -> bool:
    return bool(v) and "${" not in v


def _pin_site(proj, name: str) -> PinSite | None:
    for pom in proj.poms:
        if name in pom.props:
            v, line = pom.props[name]
            return PinSite(proj.resolve(pom, v), pom, line,
                           {"op": "set-property", "pom": pom.rel, "name": name, "from": v} if _literal(v) else None)
    for pom in proj.poms:
        if pom.parent and pom.parent[1] == name and pom.parent[2]:
            g, a, v, line = pom.parent
            return PinSite(proj.resolve(pom, v), pom, line,
                           {"op": "set-parent-version", "pom": pom.rel, "line": line, "groupId": g, "artifactId": a,
                            "from": v} if _literal(v) else None)
    for pom in proj.poms:
        for d in pom.deps + pom.managed:
            if d.a == name and d.v:
                return PinSite(proj.resolve(pom, d.v), pom, d.line,
                               {"op": "set-dependency-version", "pom": pom.rel, "line": d.line, "groupId": d.g,
                                "artifactId": d.a, "from": d.v} if _literal(d.v) else None)
        for p in pom.plugins + pom.managed_plugins:
            if p.val("artifactId") == name and p.val("version"):
                return PinSite(proj.resolve(pom, p.val("version")), pom, p.line, None)
    return None


def essentials_version(proj) -> tuple[str | None, str | None]:
    site = pin_site(proj, "essentials.version")
    if site is not None:
        return site.value, site.at
    for pom in proj.poms:
        for d in pom.deps + pom.managed:
            if d.g.startswith("dk.trustworks.essentials") and d.v:
                return proj.resolve(pom, d.v), f"{pom.rel}:{d.line}"
    return None, None


# ---------------------------------------------------------------------------------------------
# Scanners

PAIR_ROW = re.compile(r"^\|\s*`(ess-[\w.-]+)`\s*\|\s*`(s[\w.-]+)`\s*\|")


def read_pairs() -> set[tuple[str, str]]:
    """(review-scan check, stack-lint check) pairs that report one defect: the table in commands/review.md
    Step 3e, read rather than restated. The plan applies that step's rule: keep the review-scan row, name the
    stack-lint one on it."""
    try:
        text = REVIEW_MD.read_text(encoding="utf-8")
    except OSError as exc:
        raise UsageError(f"cannot read {REVIEW_MD}: {exc}")
    section = text.split("### 3e", 1)[1].split("\n## ", 1)[0] if "### 3e" in text else ""
    return {(m.group(1), m.group(2)) for m in map(PAIR_ROW.match, section.splitlines()) if m}


def run_json(argv: list[str], name: str) -> dict:
    try:
        r = subprocess.run([sys.executable, *argv], capture_output=True, text=True, check=False)
    except OSError as exc:
        raise UsageError(f"cannot run {name}: {exc}")
    if r.returncode not in (0, 1):
        raise UsageError(f"{name} could not run: {r.stderr.strip() or r.stdout.strip()}")
    try:
        return json.loads(r.stdout)
    except ValueError as exc:
        raise UsageError(f"{name} printed no JSON: {exc}")


def fold(steps: list[dict], scan: dict, lint: dict, pairs: set[tuple[str, str]]) -> dict:
    """Files each finding under the step that lists it, the step's `findings`. A stack-lint finding that
    reports the same defect as a review-scan finding (a Step 3e pair: same file, and the same line or the
    line the review-scan fix op names) is not listed again: it is named on that finding as `also`. Returns
    the counts of findings no step lists."""
    by_id, by_slug, upgrade_step = {}, {}, None
    for s in steps:
        s["findings"] = []
        if s["kind"] == "review-scan":
            for tid in s.get("ess", []):
                by_id.setdefault(tid, s)
        for slug in s.get("stackLint", []):
            by_slug.setdefault(slug, s)
        if s["kind"] == "stack-lint" and upgrade_step is None:
            upgrade_step = s
    outside = {"review-scan": 0, "stack-lint": 0}
    listed = []
    for f in scan.get("findings", []):
        s = by_id.get(f["id"])
        if s is None:
            outside["review-scan"] += 1
        else:
            item = {"source": "review-scan", **f}
            s["findings"].append(item)
            listed.append(item)
    for f in lint.get("findings", []):
        twin = next((r for r in listed if (r["check"], f["check"]) in pairs and r["file"] == f["file"]
                     and (r["line"] == f["line"] or any(op.get("line") == f["line"] for op in r["fix"]["ops"]))), None)
        if twin is not None:
            twin.setdefault("also", []).append({"id": f["id"], "check": f["check"], "line": f["line"]})
            continue
        s = by_slug.get(f["check"]) or (upgrade_step if f.get("appliesWithUpgrade") else None)
        if s is None:
            outside["stack-lint"] += 1
        else:
            s["findings"].append({"source": "stack-lint", **f})
    return outside


# ---------------------------------------------------------------------------------------------
# The plan


def cite_out(cat: Catalogue, c: dict, tag: str) -> dict:
    out = {"path": c["path"], "line": c["line"], "token": c["token"]}
    if cat.blob:
        out["url"] = f"{cat.blob}{tag}/{c['path']}#L{c['line']}"
    return out


def plan(project: Path, from_version: str | None, scan: bool, migrations: Path) -> dict:
    cat = Catalogue()
    notes = load_notes(migrations)
    target = cat.pins["essentials.version"]
    proj = read_project(project)
    current, at = (from_version, "--from") if from_version else essentials_version(proj)
    if current is None or version_tuple(current) is None:
        raise UsageError(f"cannot tell which Essentials release {project} is on "
                         f"({'essentials.version is ' + repr(current) if current else 'no essentials.version'}); "
                         "pass --from <version>")
    chain = chain_for(notes, current, target)
    doc: dict = {"tool": "migration-plan", "project": str(project), "from": current, "fromSource": at,
                 "to": target, "notes": [f.relative_to(PLUGIN).as_posix() if f.is_relative_to(PLUGIN) else str(f)
                                         for f, _ in chain],
                 "upToDate": not chain, "pins": [], "steps": [], "fixedIn": [], "scanned": False, "outside": None}
    if not chain:
        return doc
    pins: dict[str, dict] = {}
    for _, n in chain:
        for p in n["requiredPins"]:
            pins[p["pin"]] = {**p, "tag": n["to"]}
    for name, p in pins.items():
        site = pin_site(proj, name)
        value, want = (site.value if site else None), cat.pins[name]
        if name == "essentials.version" and from_version and below(value, want) is None:
            value = from_version     # the release --from says the unresolvable property stands for
        status = ("not set" if value is None else "unknown" if below(value, want) is None
                  else "ok" if not below(value, want)
                  else "tolerated" if any(tolerates(x, value) for x in p["tolerated"])
                  else "move")
        ops = site.ops(want) if site is not None and status == "move" else []
        doc["pins"].append({"pin": name, "current": value, "at": site.at if site else None, "target": want,
                            "status": status, "fix": {"mechanical": bool(ops), "ops": ops},
                            "tolerated": p["tolerated"], "reason": p["reason"], "cite": cite_out(cat, p["cite"], p["tag"])})
    for _, n in chain:
        items = {}
        for c in n["checklist"]:
            items.setdefault(c["phase"], []).append({**cat.trap(c["id"]), "because": cite_out(cat, c["cite"], n["to"])})
        for s in n["steps"]:
            step = {"id": s["id"], "kind": s["kind"], "title": s["title"], "release": n.get(s.get("release", ""), None)
                    if s["kind"] == "compile-and-fix" else None,
                    "phase": s.get("phase"), "ess": [cat.trap(t) for t in s.get("ess", [])],
                    "stackLint": list(s.get("stackLint", [])), "cites": [cite_out(cat, c, n["to"]) for c in s["cites"]]}
            if s["kind"] == "checklist":
                step["items"] = items.get(s["phase"], [])
            doc["steps"].append(step)
        doc["fixedIn"] += [{**cat.trap(c["id"]), "reason": c["reason"], "cite": cite_out(cat, c["cite"], n["to"])}
                           for c in n["fixedIn"]]
    if scan:
        rs_json = run_json([str(REVIEW_SCAN), "--tree", str(project), "--json"], "review-scan")
        sl_argv = [str(STACK_LINT), str(project), "--json"] + (["--essentials-version", current] if from_version else [])
        sl_json = run_json(sl_argv, "stack-lint")
        steps_in = [{"kind": s["kind"], "ess": [e["id"] for e in s["ess"]], "stackLint": s["stackLint"]}
                    for s in doc["steps"]]
        doc["outside"] = fold(steps_in, rs_json, sl_json, read_pairs())
        for s, f in zip(doc["steps"], steps_in):
            s["findings"] = f["findings"]
        doc["scanned"] = True
    return doc


def print_plan(doc: dict, out):
    if doc["upToDate"]:
        out.write(f"migration-plan: {doc['project']} is on Essentials {doc['from']} ({doc['fromSource']}); "
                  f"the target is {doc['to']} — nothing to move\n")
        return
    out.write(f"migration-plan: {doc['project']} — Essentials {doc['from']} ({doc['fromSource']}) → {doc['to']}\n"
              f"notes: {', '.join(doc['notes'])}\n\nPins (stack-pins.md is the source of each target):\n")
    for p in doc["pins"]:
        cur = p["current"] if p["current"] is not None else "—"
        out.write(f"  {p['pin']:<28} {cur} → {p['target']:<10} {p['status']:<8} {p['at'] or ''}\n"
                  f"      {p['reason']} ({p['cite']['path']}:{p['cite']['line']})"
                  + (f"; tolerated: {', '.join(p['tolerated'])}" if p["tolerated"] else "") + "\n")
    for i, s in enumerate(doc["steps"], 1):
        tag = s["kind"] + (f" on {s['release']}" if s["release"] else "") + (f" {s['phase']}" if s["phase"] else "")
        out.write(f"\n{i}. [{tag}] {s['title']}\n")
        if s["ess"]:
            out.write(f"   ids: {', '.join(e['id'] for e in s['ess'])}\n")
        if s["stackLint"]:
            out.write(f"   stack-lint: {', '.join(s['stackLint'])}\n")
        out.write(f"   see: {', '.join(c['path'] + ':' + str(c['line']) for c in s['cites'])}\n")
        for it in s.get("items", []):
            out.write(f"   - {it['id']} {it['symptom']}\n     → {it['link']}\n")
        for f in s.get("findings", []):
            name = f["id"] + (" " + f["check"] if f["source"] == "stack-lint" else "")
            mech = "  [mechanical]" if f["fix"]["mechanical"] else ""
            also = "".join(f" (also {a['id']} {a['check']})" for a in f.get("also", []))
            out.write(f"   * {f['source']} {name} {f['severity']} {f['file']}:{f['line']}{also}: {f['message']}\n"
                      f"     fix: {f['fix']['text']}{mech}\n")
    if doc["fixedIn"]:
        out.write("\nWorkarounds the target makes removable:\n")
        for c in doc["fixedIn"]:
            out.write(f"  - {c['id']} {c['reason']} ({c['cite']['path']}:{c['cite']['line']})\n")
    if doc["scanned"]:
        o = doc["outside"]
        out.write(f"\nOutside the release move (not listed): {o['review-scan']} review-scan and "
                  f"{o['stack-lint']} stack-lint finding(s)\n")
    else:
        out.write("\nNot scanned (--no-scan): run without it to file review-scan and stack-lint findings under their steps\n")


# ---------------------------------------------------------------------------------------------
# Self-test


def golden_view(doc: dict) -> dict:
    """The plan with its project path relative, catalogue entries reduced to their ids and each finding to
    source, id, check and place — what the notes and the fold decide, not the catalogue's or a scanner's wording."""
    view = json.loads(json.dumps(doc))
    view["project"] = Path(view["project"]).name
    view["fixedIn"] = [c["id"] for c in view["fixedIn"]]
    for s in view["steps"]:
        s["ess"] = [e["id"] for e in s["ess"]]
        if "items" in s:
            s["items"] = [i["id"] for i in s["items"]]
        if "findings" in s:
            s["findings"] = [f"{f['source']} {f['id']} {f.get('check', '')} {f['file']}:{f['line']}"
                             + "".join(f" also {a['check']}:{a['line']}" for a in f.get("also", []))
                             for f in s["findings"]]
    return view


def self_test(update: bool, out) -> int:
    failures = 0
    problems = check_notes(Catalogue(), load_notes(MIGRATIONS))
    for p in problems:
        out.write(f"FAIL --check: {p}\n")
    failures += len(problems)
    cases = sorted(d for d in CASES.iterdir() if (d / "pom.xml").is_file()) if CASES.is_dir() else []
    if not cases:
        out.write(f"FAIL no cases under {CASES}\n")
        return 1
    for case in cases:
        golden = case.parent / f"{case.name}.golden.json"
        args = json.loads((case / "args.json").read_text(encoding="utf-8")) if (case / "args.json").is_file() else {}
        try:
            got = golden_view(plan(case, args.get("from"), not args.get("noScan", False), MIGRATIONS))
        except UsageError as exc:
            out.write(f"FAIL {case.name}: {exc}\n")
            failures += 1
            continue
        text = json.dumps(got, indent=2, ensure_ascii=False) + "\n"
        if update:
            golden.write_text(text, encoding="utf-8")
            out.write(f"updated {golden.relative_to(PLUGIN)}\n")
        elif not golden.is_file() or golden.read_text(encoding="utf-8") != text:
            out.write(f"FAIL {case.name}: differs from {golden.relative_to(PLUGIN)} "
                      "(--update-golden only for an intended change, then read the diff)\n")
            failures += 1
        else:
            out.write(f"ok   {case.name}\n")
    out.write(f"self-test: {len(cases)} case(s), {'FAILED' if failures else 'ok'}\n")
    return 1 if failures else 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(prog="migration-plan",
                                 description="The ordered plan for moving a project to the Essentials release "
                                             "stack-pins.md targets. Reports, never writes.")
    ap.add_argument("project", nargs="?", metavar="PROJECT")
    ap.add_argument("--from", dest="from_version", metavar="VERSION")
    ap.add_argument("--no-scan", action="store_true")
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--migrations", metavar="DIR", default=str(MIGRATIONS))
    ap.add_argument("--self-test", action="store_true")
    ap.add_argument("--update-golden", action="store_true")
    args = ap.parse_args(argv)
    out = sys.stdout
    try:
        if args.self_test:
            return self_test(args.update_golden, out)
        if args.check:
            problems = check_notes(Catalogue(), load_notes(Path(args.migrations)))
            for p in problems:
                out.write(f"{p}\n")
            out.write(f"migration-plan --check: {'FAILED, ' + str(len(problems)) + ' problem(s)' if problems else 'ok'}\n")
            return 1 if problems else 0
        if not args.project:
            raise UsageError("give PROJECT, --check or --self-test")
        project = Path(args.project)
        if not project.is_dir():
            raise UsageError(f"{project} is not a directory")
        doc = plan(project, args.from_version, not args.no_scan, Path(args.migrations))
        if args.json:
            json.dump(doc, out, indent=2, ensure_ascii=False)
            out.write("\n")
        else:
            print_plan(doc, out)
        return 0
    except UsageError as exc:
        print(f"migration-plan: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
