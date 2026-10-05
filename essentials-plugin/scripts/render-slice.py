#!/usr/bin/env python3
"""render-slice — deterministic rendering of the slice templates.

Why this exists
---------------
Rendering a slice is pure substitution (`references/slice/slice-authoring.md` §4): pick the
template family from language, kind and lane, replace `__Name__` path segments and `{{name}}`
content, put each file where its `package` declaration says it belongs, and make the two
wiring edits at the anchor comments the BC scaffold carries. None of that is judgement, so a
language model doing it by hand is the only way it can go wrong. The kind skills call this
script; the model then fills the TODOs, prunes what the slice does not need and reports.

It also carries the check for the templates: `check` re-renders the compositions in
`tests/slice-golden/compositions.json` and byte-diffs them against the committed goldens.

Usage
-----
    render-slice.py render   --lang L --kind K --lane N --main-root DIR --test-root DIR
                             [--project-root DIR] [--new-bc] [--with-state] [--wire]
                             [--inputs FILE.json] [--set name=value ...] [--dry-run] [--json]
    render-slice.py requires --lang L --kind K --lane N [--new-bc] --build FILE [--json]
    render-slice.py check         [--golden DIR]
    render-slice.py update-golden [--golden DIR]
    render-slice.py families      [--json]

    --templates DIR (before the subcommand) renders from another template tree.

    L  java | kotlin        K  command | view | automation | translation
    N  decider | aggregate | service-entity

Inputs are the placeholder names of slice-authoring.md §4. Only the elicited ones are needed;
the rest are derived: `Bc` from `bc`, `Slice`/`sliceCamel` from `slice`, `View`/`viewCamel`
from `view`, `ExternalSystem` from `externalSystem`, `aggregate` from `Aggregate`,
`entity`/`Entity` from the aggregate, `apiPath` = /api/<bc>, `owner` = <bc>-team, and `tier`
from `lane` (never the same value except on service-entity). `AggregateType` and `Aggregates`
are plurals and are never derived. A derived value may be overridden with --set.

Exit codes
----------
    0   rendered / check clean / every precondition present
    1   check found a difference / requires found a missing module
    2   could not render: bad or missing input, unknown or unfilled placeholder, a leftover
        `{{` or `__` after substitution, a target that already exists, a refused combination

Wiring (`--wire`) is reported per edit as `applied`, `present` (already there) or `manual`
(the anchor is missing, so the edit is left to the caller — it is never guessed). On the decider
lane it also counts the application's decider configurators: `--new-bc` writes the one
application-level `<packagePath>.DeciderWiring` only when the project has none, and any count other
than one is reported `manual`, because a second configurator registers every decider twice.

Standard library only; Python 3.11+.
"""

from __future__ import annotations

import argparse
import difflib
import json
import os
import re
import shutil
import sys
import tempfile
from pathlib import Path

PLUGIN_ROOT = Path(__file__).resolve().parent.parent
TEMPLATES = PLUGIN_ROOT / "references" / "slice" / "templates"
GOLDEN = PLUGIN_ROOT / "tests" / "slice-golden"

LANGS = ("java", "kotlin")
KINDS = ("command", "view", "automation", "translation")
LANES = ("decider", "aggregate", "service-entity")
EXT = {"java": "java", "kotlin": "kt"}
TIER = {"decider": "cqrs-es", "aggregate": "cqrs-es", "service-entity": "service-entity"}

CONTENT_PH = re.compile(r"\{\{([A-Za-z]+)\}\}")
PATH_PH = re.compile(r"__([A-Za-z]+)__")
PACKAGE_DECL = re.compile(r"^package\s+([\w.]+)\s*;?\s*$", re.M)

SNAKE = re.compile(r"^[a-z][a-z0-9]*(_[a-z0-9]+)*$")
PASCAL = re.compile(r"^[A-Z][A-Za-z0-9]*$")
CAMEL = re.compile(r"^[a-z][A-Za-z0-9]*$")
PACKAGE = re.compile(r"^[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)*$")
API_PATH = re.compile(r"^/[A-Za-z0-9_./{}-]*$")
OWNER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]*$")

# Every placeholder the templates may use, with the shape its value must have. A template
# using a name that is not here is drift (exit 2), exactly as slice-authoring.md §4 requires.
PLACEHOLDERS = {
    "packagePath": PACKAGE, "bc": SNAKE, "Bc": PASCAL,
    "slice": SNAKE, "Slice": PASCAL, "sliceCamel": CAMEL,
    "aggregate": CAMEL, "Aggregate": PASCAL, "Aggregates": PASCAL, "AggregateType": PASCAL,
    "entity": CAMEL, "Entity": PASCAL,
    "Command": PASCAL, "Event": PASCAL,
    "view": SNAKE, "View": PASCAL, "viewCamel": CAMEL,
    "externalSystem": SNAKE, "ExternalSystem": PASCAL, "ExternalEvent": PASCAL,
    "apiPath": API_PATH, "owner": OWNER,
    "lane": re.compile(r"^(decider|aggregate|service-entity)$"),
    "tier": re.compile(r"^(cqrs-es|service-entity)$"),
}

JAVA_KEYWORDS = set("""abstract assert boolean break byte case catch char class const continue default do
double else enum extends final finally float for goto if implements import instanceof int interface long
native new package private protected public return short static strictfp super switch synchronized this
throw throws transient try void volatile while true false null var record yield sealed permits
as fun in is object typealias typeof val when""".split())

# Module preconditions per family: every group must be satisfied by one of its artifactIds
# being declared in the build file. The starters do not bring these (eventsourced-aggregates
# is <optional> in spring-boot-starter-postgresql-event-store, and no starter depends on
# postgresql-document-db), so a project that lacks them gets a first slice that does not compile.
EVENT_STORE = ("spring-boot-starter-postgresql-event-store",)
DOCUMENT_DB = ("postgresql-document-db",)
KOTLIN_FOR_JAVA = (("kotlin-stdlib", "kotlin-stdlib-jdk8"), ("kotlin-reflect",))
SPRING_DATA = ("spring-boot-starter-data-mongodb", "spring-boot-starter-data-jpa", "spring-data-commons")
REQUIRES = {
    ("java", "app-wiring"): (EVENT_STORE, ("eventsourced-aggregates",)),
    ("kotlin", "app-wiring"): (EVENT_STORE, ("kotlin-eventsourcing",)),
    ("java", "bc-scaffold"): (EVENT_STORE, ("eventsourced-aggregates",)),
    ("kotlin", "bc-scaffold"): (EVENT_STORE, ("kotlin-eventsourcing",)),
    ("java", "bc-scaffold-aggregate"): (EVENT_STORE, ("eventsourced-aggregates",)),
    ("java", "bc-scaffold-service-entity"): (SPRING_DATA,),
    ("kotlin", "bc-scaffold-service-entity"): (SPRING_DATA,),
    ("java", "command"): (EVENT_STORE, ("eventsourced-aggregates",)),
    ("kotlin", "command"): (EVENT_STORE, ("kotlin-eventsourcing",)),
    ("java", "command_aggregate"): (EVENT_STORE, ("eventsourced-aggregates",)),
    ("java", "command_service_entity"): (SPRING_DATA,),
    ("kotlin", "command_service_entity"): (SPRING_DATA,),
    ("java", "view"): (EVENT_STORE, DOCUMENT_DB) + KOTLIN_FOR_JAVA,
    ("kotlin", "view"): (EVENT_STORE, DOCUMENT_DB),
    ("java", "view_service_entity"): (SPRING_DATA,),
    ("kotlin", "view_service_entity"): (SPRING_DATA,),
    ("java", "automation"): (EVENT_STORE, DOCUMENT_DB) + KOTLIN_FOR_JAVA,
    ("kotlin", "automation"): (EVENT_STORE, DOCUMENT_DB),
    ("java", "translation"): (EVENT_STORE,),
    ("kotlin", "translation"): (EVENT_STORE,),
}

SLICE_DIR = {"command": ("use_cases", "slice"), "view": ("views", "view"),
             "automation": ("automations", "slice"), "translation": ("external_systems", "externalSystem")}
STATE_TEMPLATES = ("__Aggregate__State", "__Aggregate__StateEvolver")

# Wiring anchors. They are comments the BC scaffold templates carry; a user who deleted one
# gets a `manual` wiring entry instead of a guessed edit.
BEAN_ANCHOR = "/essentials:add-slice appends a "
# The decider lane's command routing: ONE configurator per application, in the application-level
# `app-wiring` family (<packagePath>.DeciderWiring). A second one registers every decider twice and the
# first send throws MultipleCommandHandlersFoundException, so the renderer writes it only when the
# project has none and reports a project that has several.
APP_WIRING = "app-wiring"
CONFIGURATOR_CALL = re.compile(r"(?<![\w.])(?:new\s+)?(?:EventStream)?DeciderAndAggregateTypeConfigurator\s*\(")
PERMITS_LINE = re.compile(r"^(\s*permits\s+)([\w\s,]+?)(\s*\{\s*//\s*/essentials:add-slice appends each new variant.*)$", re.M)


class RenderError(Exception):
    """A render that must not write anything. Exit 2."""


# ---------------------------------------------------------------- inputs

def pascal(snake: str) -> str:
    return "".join(p[:1].upper() + p[1:] for p in snake.split("_"))


def lower_first(s: str) -> str:
    return s[:1].lower() + s[1:]


def derive(raw: dict[str, str], lane: str) -> dict[str, str]:
    """Fill every derivable placeholder the caller did not supply. Explicit values win."""
    v = dict(raw)
    v["lane"] = lane
    if v.get("tier", TIER[lane]) != TIER[lane]:
        raise RenderError(f"tier={v['tier']} contradicts lane={lane}: tier is derived from the lane "
                          f"({lane} -> {TIER[lane]}, slice-authoring.md §4) and never supplied")
    v["tier"] = TIER[lane]
    if "bc" in v:
        v.setdefault("Bc", pascal(v["bc"]))
        v.setdefault("apiPath", "/api/" + v["bc"])
        v.setdefault("owner", v["bc"] + "-team")
    if "slice" in v:
        v.setdefault("Slice", pascal(v["slice"]))
        v.setdefault("sliceCamel", lower_first(v["Slice"]))
    if "view" in v:
        v.setdefault("View", pascal(v["view"]))
        v.setdefault("viewCamel", lower_first(v["View"]))
    if "externalSystem" in v:
        v.setdefault("ExternalSystem", pascal(v["externalSystem"]))
    if "Aggregate" in v:
        v.setdefault("aggregate", lower_first(v["Aggregate"]))
    elif "aggregate" in v:
        v["Aggregate"] = v["aggregate"][:1].upper() + v["aggregate"][1:]
    if lane == "service-entity":
        if "Entity" in v:
            v.setdefault("entity", lower_first(v["Entity"]))
        elif "Aggregate" in v:
            v.setdefault("Entity", v["Aggregate"])
            v.setdefault("entity", v["aggregate"])
    return v


def validate(values: dict[str, str]) -> None:
    errors = []
    for name, value in values.items():
        shape = PLACEHOLDERS.get(name)
        if shape is None:
            errors.append(f"unknown input '{name}' (not a slice placeholder; see slice-authoring.md §4)")
            continue
        if not isinstance(value, str) or not shape.match(value):
            errors.append(f"{name}={value!r} does not have the required shape ({shape.pattern})")
            continue
        if name in ("bc", "slice", "view", "externalSystem") and value in JAVA_KEYWORDS:
            errors.append(f"{name}={value!r} is a Java/Kotlin keyword and cannot be a package segment")
        if name == "packagePath" and any(seg in JAVA_KEYWORDS for seg in value.split(".")):
            errors.append(f"packagePath={value!r} has a keyword segment")
    if errors:
        raise RenderError("invalid inputs:\n  " + "\n  ".join(errors))


# ---------------------------------------------------------------- families

def families_for(lang: str, kind: str, lane: str, new_bc: bool) -> list[str]:
    if lane == "aggregate" and lang == "kotlin":
        raise RenderError("the aggregate lane is scaffolded in Java only: AggregateRoot / "
                          "StatefulAggregateRepository are a Java-native family (slice-authoring.md §1b). "
                          "Emit nothing; a Kotlin BC belongs on the decider lane.")
    if lane == "service-entity" and kind in ("automation", "translation"):
        raise RenderError(f"no {kind} template exists for the service-entity lane: the {kind} templates are "
                          "EventProcessor subscribers over an event-store AggregateType, and a service-entity BC "
                          "has no event store — its events are published in-process on the EventBus. A slice "
                          "rendered there would not compile on a Mongo or pg-crud project, or would never receive "
                          "an event. Write this slice by hand as an EventBus subscriber.")
    if new_bc and kind != "command":
        raise RenderError(f"a new bounded context starts with its first command slice: the {kind} templates import "
                          "<bc>/events/<Event>, and only a command slice supplies an event variant (and, in Java, "
                          "the first entry of the sealed parent's permits clause). Add a command slice first.")
    suffix = {"decider": "", "aggregate": "_aggregate", "service-entity": "_service_entity"}[lane]
    out = []
    if new_bc and lane == "decider":
        out.append(APP_WIRING)
    if new_bc:
        out.append("bc-scaffold" + suffix.replace("_", "-"))
    if kind == "command":
        out.append("command" + suffix)
    elif kind == "view":
        out.append("view_service_entity" if lane == "service-entity" else "view")
    else:
        out.append(kind)
    for fam in out:
        if not (TEMPLATES / lang / fam).is_dir():
            raise RenderError(f"no template family {lang}/{fam}")
    return out


def template_files(lang: str, family: str, with_state: bool) -> list[Path]:
    root = TEMPLATES / lang / family
    files = sorted(p for p in root.rglob("*") if p.is_file())
    if family == "command" and not with_state:
        files = [p for p in files if p.stem not in STATE_TEMPLATES]
    return files


# ---------------------------------------------------------------- rendering

def substitute(text: str, values: dict[str, str], where: str, pattern: re.Pattern) -> str:
    names = set(pattern.findall(text))
    unknown = sorted(n for n in names if n not in PLACEHOLDERS)
    if unknown:
        raise RenderError(f"{where}: unknown placeholder(s) {', '.join(unknown)} — the template has drifted "
                          "from slice-authoring.md §4")
    unfilled = sorted(n for n in names if n not in values)
    if unfilled:
        raise RenderError(f"{where}: no value for {', '.join(unfilled)} (pass --set name=value)")
    return pattern.sub(lambda m: values[m.group(1)], text)


def destination(lang: str, family: str, tpl: Path, text: str, values: dict[str, str],
                main_root: Path, test_root: Path) -> Path:
    rel = tpl.relative_to(TEMPLATES / lang / family)
    name = substitute(rel.name, values, str(rel), PATH_PH)
    if name.endswith(".template"):
        name = name[: -len(".template")]
    pkg_dir = Path(*values["packagePath"].split("."))
    bc_dir = pkg_dir / values["bc"]
    if tpl.suffix in (".java", ".kt"):
        m = PACKAGE_DECL.search(text)
        if not m:
            raise RenderError(f"{lang}/{family}/{rel}: no package declaration")
        package = m.group(1)
        if family == APP_WIRING:
            if package != values["packagePath"]:
                raise RenderError(f"{lang}/{family}/{rel}: package {package} is not the application package")
        elif not (package + ".").startswith(f"{values['packagePath']}.{values['bc']}."):
            raise RenderError(f"{lang}/{family}/{rel}: package {package} is outside the bounded context")
        root = test_root if rel.parts[0] == "test" else main_root
        return root / Path(*package.split(".")) / name
    if family.startswith("bc-scaffold"):
        return main_root / bc_dir / Path(*rel.parts[:-1]) / name
    top, key = SLICE_DIR[family.split("_")[0]]
    return main_root / bc_dir / top / values[key] / name


def check_leftovers(path: str, text: str) -> None:
    for i, line in enumerate(text.splitlines(), 1):
        if "{{" in line or "__" in line:
            raise RenderError(f"{path}:{i}: unsubstituted text after rendering: {line.strip()[:120]}")


def plan_render(lang, kind, lane, values, main_root, test_root, new_bc, with_state):
    """Return [(dest, text)] without touching the disk (beyond reading templates)."""
    fams = families_for(lang, kind, lane, new_bc)
    planned = []
    for fam in fams:
        for tpl in template_files(lang, fam, with_state):
            where = f"{lang}/{fam}/{tpl.relative_to(TEMPLATES / lang / fam)}"
            text = substitute(tpl.read_text(encoding="utf-8"), values, where, CONTENT_PH)
            dest = destination(lang, fam, tpl, text, values, main_root, test_root)
            check_leftovers(where, text)
            planned.append((dest, text))
    dests = [d for d, _ in planned]
    dupes = sorted({str(d) for d in dests if dests.count(d) > 1})
    if dupes:
        raise RenderError("two templates render to the same file: " + ", ".join(dupes))
    return fams, planned


def preflight(lang, kind, lane, values, main_root, new_bc, planned) -> None:
    bc_dir = main_root / Path(*values["packagePath"].split(".")) / values["bc"]
    if new_bc and bc_dir.exists():
        raise RenderError(f"{bc_dir} already exists — it is not a new bounded context; drop --new-bc")
    if not new_bc:
        if not bc_dir.is_dir():
            raise RenderError(f"{bc_dir} does not exist — pass --new-bc to scaffold it with this command slice")
        has_agg, has_ent = (bc_dir / "aggregates").is_dir(), (bc_dir / "entities").is_dir()
        found = "aggregate" if has_agg and not has_ent else "service-entity" if has_ent and not has_agg else \
            "decider" if not (has_agg or has_ent) else "two lanes (aggregates/ and entities/)"
        if found != lane:
            raise RenderError(f"{bc_dir} looks like the {found} lane, not {lane} (slice-authoring.md §1b); "
                              "a BC showing two lanes is Blocking — stop and report it")
    top, key = SLICE_DIR[kind]
    slice_dir = bc_dir / top / values[key]
    if slice_dir.exists():
        raise RenderError(f"{slice_dir} already exists — never merge into an existing slice")
    if kind == "view" and lane == "service-entity":
        entity = bc_dir / "entities" / f"{values['Entity']}.{EXT[lang]}"
        if not entity.is_file():
            raise RenderError(f"{entity} does not exist — a service-entity view reads the entity's own table, "
                              "so the entity must be written first (entities/CLAUDE.md holds its contract)")
    clashes = [str(d) for d, _ in planned if d.exists()]
    if clashes:
        raise RenderError("refusing to overwrite existing file(s): " + ", ".join(clashes))


# ---------------------------------------------------------------- wiring

def insert_import(text: str, line: str, own_prefix: str) -> str:
    lines = text.split("\n")
    if line in lines:
        return text
    imports = [i for i, l in enumerate(lines) if l.startswith("import ")]
    own = [i for i in imports if lines[i].startswith("import " + own_prefix)]
    at = (own[-1] + 1) if own else (imports[0] if imports else None)
    if at is None:
        pkg = next(i for i, l in enumerate(lines) if l.startswith("package "))
        lines[pkg + 1:pkg + 1] = ["", line]
    else:
        lines.insert(at, line)
    return "\n".join(lines)


def wire_bean(path: Path, lang: str, values: dict[str, str]) -> dict:
    entry = {"path": path, "edit": f"@Bean {values['sliceCamel']}Decider()"}
    if not path.is_file():
        return {**entry, "status": "manual", "reason": "configuration class not found"}
    text = path.read_text(encoding="utf-8")
    slice_pkg = f"{values['packagePath']}.{values['bc']}.use_cases.{values['slice']}"
    decider = f"{values['Slice']}Decider"
    if lang == "java":
        present = re.search(rf"\b{decider}\s+{values['sliceCamel']}Decider\s*\(", text)
        block = (f"\n    @Bean\n    public {decider} {values['sliceCamel']}Decider() {{\n"
                 f"        return new {decider}();\n    }}\n")
        imp = f"import {slice_pkg}.{decider};"
    else:
        present = re.search(rf"\bfun\s+{values['sliceCamel']}Decider\s*\(", text)
        block = f"\n    @Bean\n    fun {values['sliceCamel']}Decider() = {decider}()\n"
        imp = f"import {slice_pkg}.{decider}"
    if present:
        return {**entry, "status": "present"}
    if BEAN_ANCHOR not in text:
        return {**entry, "status": "manual", "reason": f"anchor comment '// {BEAN_ANCHOR}…' not found"}
    lines = text.split("\n")
    anchor = next(i for i, l in enumerate(lines) if BEAN_ANCHOR in l)
    close = next((i for i in range(len(lines) - 1, anchor, -1) if lines[i] == "}"), None)
    if close is None:
        return {**entry, "status": "manual", "reason": "no class-closing '}' after the anchor"}
    lines[close:close] = block.rstrip("\n").split("\n")
    text = insert_import("\n".join(lines), imp, f"{values['packagePath']}.{values['bc']}.")
    path.write_text(text, encoding="utf-8")
    return {**entry, "status": "applied"}


def wire_permits(path: Path, values: dict[str, str]) -> dict:
    event = values["Event"]
    entry = {"path": path, "edit": f"permits += {event}"}
    if not path.is_file():
        return {**entry, "status": "manual", "reason": "sealed event parent not found"}
    text = path.read_text(encoding="utf-8")
    m = PERMITS_LINE.search(text)
    if not m:
        return {**entry, "status": "manual", "reason": "single-line 'permits … {   // /essentials:add-slice "
                                                        "appends each new variant …' anchor not found"}
    names = [n.strip() for n in m.group(2).split(",")]
    if event in names:
        return {**entry, "status": "present"}
    new = m.group(1) + ", ".join(names + [event]) + m.group(3)
    path.write_text(text[: m.start()] + new + text[m.end():], encoding="utf-8")
    return {**entry, "status": "applied"}


def configurators(main_root: Path) -> list[Path]:
    """Every source file under main_root that constructs a decider configurator."""
    return sorted(p for p in main_root.rglob("*") if p.suffix in (".java", ".kt") and p.is_file()
                  and CONFIGURATOR_CALL.search(p.read_text(encoding="utf-8")))


def check_configurator(main_root: Path, written: Path | None) -> dict:
    found = configurators(main_root)
    entry = {"path": written or (found[0] if found else main_root), "edit": "one decider configurator per application"}
    if len(found) == 1:
        return {**entry, "status": "applied" if written == found[0] else "present"}
    if not found:
        return {**entry, "status": "manual",
                "reason": "no EventStreamDeciderAndAggregateTypeConfigurator / DeciderAndAggregateTypeConfigurator "
                          "found — no decider is registered on the CommandBus until the application declares one"}
    return {**entry, "status": "manual",
            "reason": f"{len(found)} configurators found ({', '.join(p.as_posix() for p in found)}) — keep exactly one "
                      "per application; each extra one registers every decider again and the first command sent "
                      "fails with MultipleCommandHandlersFoundException"}


def wire(lang, kind, lane, values, main_root, app_wiring: Path | None = None) -> list[dict]:
    if kind != "command":
        return []
    bc_dir = main_root / Path(*values["packagePath"].split(".")) / values["bc"]
    out = []
    if lane == "decider":
        out.append(check_configurator(main_root, app_wiring))
        out.append(wire_bean(bc_dir / "config" / f"{values['Bc']}Configuration.{EXT[lang]}", lang, values))
    if lang == "java":
        out.append(wire_permits(bc_dir / "events" / f"{values['Aggregate']}Event.java", values))
    return out


# ---------------------------------------------------------------- render command

def todos(files: list[tuple[Path, str]], rel) -> list[str]:
    out = []
    for dest, text in files:
        for i, line in enumerate(text.splitlines(), 1):
            if "TODO" in line:
                out.append(f"{rel(dest)}:{i}: {line.strip()}")
    return out


def render(lang, kind, lane, values, project_root: Path, main_root: Path, test_root: Path,
           new_bc=False, with_state=False, do_wire=False, dry_run=False) -> dict:
    values = derive(values, lane)
    validate(values)
    fams, planned = plan_render(lang, kind, lane, values, main_root, test_root, new_bc, with_state)
    app_wiring = None
    if APP_WIRING in fams:
        # Create-if-absent: the application's one configurator is written by the first decider BC only.
        app_file = next(d for d, _ in planned if d.parent == main_root / Path(*values["packagePath"].split(".")))
        if app_file.exists() or configurators(main_root):
            planned = [(d, t) for d, t in planned if d != app_file]
        else:
            app_wiring = app_file
    preflight(lang, kind, lane, values, main_root, new_bc, planned)

    def rel(p: Path) -> str:
        try:
            return Path(os.path.relpath(p, project_root)).as_posix()
        except ValueError:
            return p.as_posix()

    report = {"language": lang, "kind": kind, "lane": lane, "families": fams,
              "inputs": {k: values[k] for k in sorted(values)},
              "written": sorted(rel(d) for d, _ in planned), "wiring": [], "todos": []}
    if dry_run:
        return report
    for dest, text in planned:
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(text, encoding="utf-8", newline="\n")
    if do_wire:
        report["wiring"] = [{**w, "path": rel(w["path"])}
                            for w in wire(lang, kind, lane, values, main_root, app_wiring)]
    report["todos"] = todos(sorted(planned), rel)
    return report


def parse_sets(pairs: list[str]) -> dict[str, str]:
    out = {}
    for p in pairs or []:
        if "=" not in p:
            raise RenderError(f"--set expects name=value, got {p!r}")
        k, v = p.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def load_inputs(args) -> dict[str, str]:
    values = {}
    if args.inputs:
        data = json.loads(Path(args.inputs).read_text(encoding="utf-8"))
        if not isinstance(data, dict) or not all(isinstance(v, str) for v in data.values()):
            raise RenderError(f"{args.inputs}: expected a JSON object of string values")
        values.update(data)
    values.update(parse_sets(args.set))
    if values.get("lane", args.lane) != args.lane:
        raise RenderError(f"input lane={values['lane']} contradicts --lane {args.lane}")
    return values


# ---------------------------------------------------------------- requires

def build_declares(build_text: str, artifact: str, gradle: bool) -> bool:
    if gradle:
        return re.search(rf"[:\"']{re.escape(artifact)}[:\"']", build_text) is not None
    return re.search(rf"<artifactId>\s*{re.escape(artifact)}\s*</artifactId>", build_text) is not None


def requires(lang, kind, lane, new_bc, build: Path) -> dict:
    fams = families_for(lang, kind, lane, new_bc)
    text = build.read_text(encoding="utf-8")
    gradle = build.name.endswith((".gradle", ".gradle.kts"))
    groups = []
    for fam in fams:
        for g in REQUIRES[(lang, fam)]:
            if g not in groups:
                groups.append(g)
    missing = [list(g) for g in groups if not any(build_declares(text, a, gradle) for a in g)]
    return {"families": fams, "build": build.as_posix(), "requires": [list(g) for g in groups], "missing": missing}


# ---------------------------------------------------------------- goldens

def render_composition(comp: dict, out: Path, golden: Path) -> None:
    """Replay one composition's steps into `out`, as add-slice would run them in order."""
    lang, lane = comp["language"], comp["lane"]
    out.mkdir(parents=True, exist_ok=True)
    main_root, test_root = out / "src" / "main" / lang, out / "src" / "test" / lang
    reports = []
    for step in comp["steps"]:
        if "seed" in step:
            # Files the user writes by hand between two slices (the service-entity entity).
            seed = golden / step["seed"]
            clashes = [p for p in tree(seed) if (out / p).exists()]
            if clashes:
                raise RenderError(f"seed {step['seed']} would overwrite {', '.join(clashes)}")
            shutil.copytree(seed, out, dirs_exist_ok=True)
            reports.append({"seed": step["seed"], "written": sorted(tree(seed))})
            continue
        values = {**comp.get("inputs", {}), **step.get("inputs", {})}
        rep = render(lang, step["kind"], lane, values, out, main_root, test_root,
                     new_bc=step.get("newBc", False), with_state=step.get("withState", False), do_wire=True)
        reports.append(rep)
    (out / "render.json").write_text(json.dumps(reports, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def load_compositions(golden: Path) -> dict:
    data = json.loads((golden / "compositions.json").read_text(encoding="utf-8"))
    return data["compositions"]


def tree(root: Path) -> dict[str, bytes]:
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in sorted(root.rglob("*")) if p.is_file()}


def check(golden: Path) -> int:
    comps = load_compositions(golden)
    failed = 0
    with tempfile.TemporaryDirectory() as tmp:
        for name, comp in comps.items():
            fresh = Path(tmp) / name
            render_composition(comp, fresh, golden)
            want, got = tree(golden / name) if (golden / name).is_dir() else {}, tree(fresh)
            for path in sorted(set(want) | set(got)):
                if want.get(path) == got.get(path):
                    continue
                failed += 1
                if path not in got:
                    print(f"STALE   {name}/{path} (in the golden, not rendered)")
                elif path not in want:
                    print(f"MISSING {name}/{path} (rendered, not in the golden)")
                else:
                    print(f"DIFF    {name}/{path}")
                    sys.stdout.writelines(difflib.unified_diff(
                        want[path].decode("utf-8").splitlines(True), got[path].decode("utf-8").splitlines(True),
                        f"golden/{name}/{path}", f"rendered/{name}/{path}"))
    known = set(comps) | {"compositions.json", "README.md"}
    for extra in sorted(p.name for p in golden.iterdir() if p.name not in known and not p.name.startswith("_")):
        failed += 1
        print(f"STALE   {extra} (not a composition in compositions.json)")
    if failed:
        print(f"\nrender-slice check: {failed} difference(s). If the template change is intended, run "
              f"'render-slice.py update-golden' and review the diff.")
        return 1
    print(f"render-slice check: {len(comps)} composition(s) match the golden")
    return 0


def update_golden(golden: Path) -> int:
    comps = load_compositions(golden)
    for name, comp in comps.items():
        target = golden / name
        if target.exists():
            shutil.rmtree(target)
        render_composition(comp, target, golden)
        print(f"wrote {target}")
    return 0


# ---------------------------------------------------------------- CLI

def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="render-slice.py", description=(__doc__ or "").split("\n\n")[0],
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--templates", help="template tree to render from (default: references/slice/templates)")
    sub = ap.add_subparsers(dest="cmd", required=True)

    def shape(p):
        p.add_argument("--lang", required=True, choices=LANGS)
        p.add_argument("--kind", required=True, choices=KINDS)
        p.add_argument("--lane", required=True, choices=LANES)
        p.add_argument("--new-bc", action="store_true", help="scaffold the bounded context first (command only)")

    r = sub.add_parser("render", help="render one slice into a project")
    shape(r)
    r.add_argument("--main-root", required=True, help="e.g. backend/src/main/kotlin")
    r.add_argument("--test-root", required=True, help="e.g. backend/src/test/kotlin")
    r.add_argument("--project-root", default=".", help="paths in the report are relative to this")
    r.add_argument("--with-state", action="store_true", help="decider lane: also emit the per-slice State + Evolver")
    r.add_argument("--wire", action="store_true", help="apply the @Bean and permits edits at their anchors")
    r.add_argument("--inputs", help="JSON object of placeholder values")
    r.add_argument("--set", action="append", metavar="NAME=VALUE", help="a placeholder value (repeatable)")
    r.add_argument("--dry-run", action="store_true", help="validate and list the files; write nothing")
    r.add_argument("--json", action="store_true")

    q = sub.add_parser("requires", help="list the modules a slice needs that the build file does not declare")
    shape(q)
    q.add_argument("--build", required=True, help="pom.xml / backend/pom.xml / build.gradle.kts")
    q.add_argument("--json", action="store_true")

    for name in ("check", "update-golden"):
        g = sub.add_parser(name)
        g.add_argument("--golden", default=str(GOLDEN))

    f = sub.add_parser("families", help="list template families and their placeholders")
    f.add_argument("--json", action="store_true")

    args = ap.parse_args(argv)
    global TEMPLATES
    if args.templates:
        TEMPLATES = Path(args.templates).resolve()
    try:
        if args.cmd == "render":
            rep = render(args.lang, args.kind, args.lane, load_inputs(args), Path(args.project_root),
                         Path(args.main_root), Path(args.test_root), args.new_bc, args.with_state,
                         args.wire, args.dry_run)
            if args.json:
                print(json.dumps(rep, indent=2, ensure_ascii=False))
            else:
                verb = "would write" if args.dry_run else "wrote"
                for p in rep["written"]:
                    print(f"{verb} {p}")
                for w in rep["wiring"]:
                    extra = f" — {w['reason']}" if "reason" in w else ""
                    print(f"wiring {w['status']}: {w['path']}: {w['edit']}{extra}")
                for t in rep["todos"]:
                    print(f"todo {t}")
            return 0
        if args.cmd == "requires":
            rep = requires(args.lang, args.kind, args.lane, args.new_bc, Path(args.build))
            if args.json:
                print(json.dumps(rep, indent=2))
            else:
                for g in rep["missing"]:
                    print("missing: " + " | ".join(g))
                if not rep["missing"]:
                    print(f"all {len(rep['requires'])} module precondition(s) declared in {rep['build']}")
            return 1 if rep["missing"] else 0
        if args.cmd == "check":
            return check(Path(args.golden))
        if args.cmd == "update-golden":
            return update_golden(Path(args.golden))
        if args.cmd == "families":
            fams = {}
            for lang in LANGS:
                for d in sorted(p for p in (TEMPLATES / lang).iterdir() if p.is_dir()):
                    names = set()
                    for f in d.rglob("*"):
                        if f.is_file():
                            names |= set(CONTENT_PH.findall(f.read_text(encoding="utf-8")))
                            names |= set(PATH_PH.findall(f.name))
                    fams[f"{lang}/{d.name}"] = sorted(names)
            if args.json:
                print(json.dumps(fams, indent=2))
            else:
                for k, v in fams.items():
                    print(f"{k}: {' '.join(v)}")
            return 0
    except RenderError as e:
        print(f"render-slice: {e}", file=sys.stderr)
        return 2
    except (OSError, json.JSONDecodeError) as e:
        print(f"render-slice: {e}", file=sys.stderr)
        return 2
    return 2


if __name__ == "__main__":
    sys.exit(main())
