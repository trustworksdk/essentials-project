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
                             [--tests stub|none] [--manifest FILE]
                             [--inputs FILE.json] [--set name=value ...] [--dry-run | --emit [--only PATH]]
                             [--json]
    render-slice.py verify   <the options of render, without --dry-run and --emit>
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
are plurals and are never derived. A derived value may be overridden with --set. Rendering into
an existing BC reads `AggregateType`, `Aggregates`, `Aggregate` and `Entity` from its scaffold when
they are not supplied and the BC names exactly one; a new BC has none to read, so they are passed.

`--tests none` writes nothing under the test root and stamps every `tests:` entry of the manifest
`present: false`; production code and wiring are what `--tests stub` (the default) writes.

`--manifest FILE` renders from a `slice.yaml` that already exists at the slice's own path, written
by a person or another toolchain before any code: its identity (`slice`, `kind`, `language`, `lane`)
must agree with the flags, and its contracts supply inputs a `--set` did not (MANIFEST_INPUTS). The
slice directory may hold that manifest and a `CLAUDE.md`, and with `--new-bc` the BC directory may
hold only such orientation files; every one of them is kept. The manifest is updated in place: the
derived keys (DERIVED_KEYS) are rewritten, an input-bearing key is kept unless it no longer names the
value the render used, a key the template has and the manifest lacks is added, and every other key
(`flow`, `scenarios`, `requirements`, `summary`, `x-*`, …) is kept byte for byte; `schemaVersion` is
never lowered.

`--emit` is for a filesystem this process may not write (a sandbox that lets the agent's file tool
write but not its shell): it validates and renders exactly as `render` would, wiring included, writes
nothing, and prints the `--json` report with every file to write in `files` — `path` (relative to
--project-root), `role` (`wiring` for an existing file only a wiring edit changes), `action` (`create` or
`replace`) and `content`, the exact text; `--only PATH` prints that one file's content, raw, instead. Run
again part-way through the writes, `--emit` accepts a target that already exists only when it holds
exactly what it would emit. The caller writes each file verbatim, then runs `verify` with the
same options: it renders again in memory and compares every target byte for byte, read-only, and reports
`verify.missing` and `verify.differs`. Only exit 0 there means the slice was rendered. Because verify
runs after the files exist, it takes them as expected rather than refusing them; a wiring edit counts as
made when re-applying it changes nothing, and an application-level DeciderWiring identical to the render's
counts as written, any other as the application's own (as render treats it).

The `render` interface is versioned (INTERFACE): within one interface version, options and `--json`
report fields are only ever added. `references/sdd-contract.md` is the contract another plugin's
agent drives it by.

Exit codes
----------
    0   rendered / emitted / verify identical / check clean / every precondition present
    1   check or verify found a difference / requires found a missing module
    2   could not render: bad or missing input, unknown or unfilled placeholder, a leftover
        `{{` or `__` after substitution, a target that already exists, a refused combination.
        Nothing is written — a failure part-way through the writes or the wiring edits restores
        every file it touched before exiting. One `render-slice: …` line on stderr says why.
    4   `render` cannot write a target (EROFS, EACCES): every target is checked before the first write,
        so nothing is written. One `render-slice: cannot write …` line on stderr names the path and the
        cause and says to re-run with --emit.
    any other code from `render` is a crash; it too restores what it touched before it propagates.

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
import errno
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
PLUGIN_JSON = PLUGIN_ROOT / ".claude-plugin" / "plugin.json"
STACK_PINS = PLUGIN_ROOT / "references" / "stack" / "stack-pins.md"

# The `render` interface and its --json report, as capabilities.json advertises them. Within one
# INTERFACE an option or a report field is only ever added; a removal, a rename or a changed exit
# meaning is the next one.
INTERFACE = 1
REPORT_VERSION = 1
BEHAVIOURS = "behaviours.json"   # beside the language dirs under TEMPLATES, so it is never rendered

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


class Planned:
    """One file a render will write: where, what, and which template it came from."""

    __slots__ = ("dest", "text", "family", "template", "role")

    def __init__(self, dest: Path, text: str, family: str, template: str):
        self.dest, self.text, self.family, self.template = dest, text, family, template
        self.role = ("test" if template.startswith("test/") else "manifest" if dest.name == "slice.yaml"
                     else "doc" if dest.name == "CLAUDE.md" else "main")


def plan_render(lang, kind, lane, values, main_root, test_root, new_bc, with_state):
    """Return the families and the [Planned] files without touching the disk (beyond reading templates)."""
    fams = families_for(lang, kind, lane, new_bc)
    planned = []
    for fam in fams:
        for tpl in template_files(lang, fam, with_state):
            rel = tpl.relative_to(TEMPLATES / lang / fam).as_posix()
            where = f"{lang}/{fam}/{rel}"
            text = substitute(tpl.read_text(encoding="utf-8"), values, where, CONTENT_PH)
            dest = destination(lang, fam, tpl, text, values, main_root, test_root)
            check_leftovers(where, text)
            planned.append(Planned(dest, text, fam, rel))
    dests = [f.dest for f in planned]
    dupes = sorted({str(d) for d in dests if dests.count(d) > 1})
    if dupes:
        raise RenderError("two templates render to the same file: " + ", ".join(dupes))
    return fams, planned


SLICE_TOPS = {top for top, _ in SLICE_DIR.values()}
ORIENTATION = ("slice.yaml", "CLAUDE.md")


def stray_files(bc_dir: Path) -> list[Path]:
    """Files under a BC directory other than the orientation files a planner writes before any code:
    the BC's CLAUDE.md, and a slice.yaml / CLAUDE.md per planned slice directory."""
    out = []
    for p in sorted(bc_dir.rglob("*")):
        if not p.is_file():
            continue
        parts = p.relative_to(bc_dir).parts
        if parts == ("CLAUDE.md",) or (len(parts) == 3 and parts[0] in SLICE_TOPS and parts[2] in ORIENTATION):
            continue
        out.append(p)
    return out


def bc_dir_of(main_root: Path, values: dict[str, str]) -> Path:
    return main_root / Path(*values["packagePath"].split(".")) / values["bc"]


def preflight(lang, kind, lane, values, main_root, new_bc, planned, manifest: Path | None, verify=False):
    """Refuse what must not be rendered. Returns (planned, kept): the files to write, and the
    orientation files a --manifest render leaves exactly as they are. `verify` compares with a render
    already on disk, so the targets existing is expected there, not refused."""
    bc_dir = main_root / Path(*values["packagePath"].split(".")) / values["bc"]
    top, key = SLICE_DIR[kind]
    slice_dir = bc_dir / top / values[key]
    if manifest is not None and manifest.resolve() != (slice_dir / "slice.yaml").resolve():
        raise RenderError(f"--manifest {manifest} is not this slice's manifest: rendering "
                          f"{values['bc']}.{values[key]} writes {slice_dir / 'slice.yaml'}")
    if new_bc and bc_dir.exists() and not verify:
        if manifest is None:
            raise RenderError(f"{bc_dir} already exists — it is not a new bounded context; drop --new-bc")
        strays = stray_files(bc_dir)
        if strays:
            more = " …" if len(strays) > 3 else ""
            raise RenderError(f"{bc_dir} already exists and holds more than orientation files "
                              f"({', '.join(str(p) for p in strays[:3])}{more}) — it is not a new bounded "
                              "context; drop --new-bc")
    if not new_bc:
        if not bc_dir.is_dir():
            raise RenderError(f"{bc_dir} does not exist — pass --new-bc to scaffold it with this command slice")
        has_agg, has_ent = (bc_dir / "aggregates").is_dir(), (bc_dir / "entities").is_dir()
        found = "aggregate" if has_agg and not has_ent else "service-entity" if has_ent and not has_agg else \
            "decider" if not (has_agg or has_ent) else "two lanes (aggregates/ and entities/)"
        if found != lane:
            raise RenderError(f"{bc_dir} looks like the {found} lane, not {lane} (slice-authoring.md §1b); "
                              "a BC showing two lanes is Blocking — stop and report it")
    if slice_dir.exists() and not verify:
        if manifest is None:
            raise RenderError(f"{slice_dir} already exists — never merge into an existing slice")
        extra = sorted(p.name for p in slice_dir.iterdir() if p.name not in ORIENTATION or not p.is_file())
        if extra:
            raise RenderError(f"{slice_dir} already exists and holds {', '.join(extra)} — a --manifest render "
                              "accepts a slice directory holding only slice.yaml and CLAUDE.md")
    if kind == "view" and lane == "service-entity":
        entity = bc_dir / "entities" / f"{values['Entity']}.{EXT[lang]}"
        if not entity.is_file():
            raise RenderError(f"{entity} does not exist — a service-entity view reads the entity's own table, "
                              "so the entity must be written first (entities/CLAUDE.md holds its contract)")
    kept: list[Path] = []
    if manifest is not None:
        # The orientation files a planner wrote stay as written; the manifest itself is merged, not kept.
        keep = {slice_dir / "CLAUDE.md"} | ({bc_dir / "CLAUDE.md"} if new_bc else set())
        kept = [f.dest for f in planned if f.dest in keep and f.dest.exists()]
        planned = [f for f in planned if f.dest not in kept]
    clashes = [str(f.dest) for f in planned if f.dest.exists() and not verify
               and not (manifest is not None and f.dest == slice_dir / "slice.yaml")]
    if clashes:
        raise RenderError("refusing to overwrite existing file(s): " + ", ".join(clashes))
    return planned, kept


# ---------------------------------------------------------------- manifests (--manifest)
#
# A manifest another toolchain wrote is read for a handful of keys and merged line for line, so
# every key the render does not own keeps its exact bytes — comments, quoting and order included.
# That rules out a YAML library round-trip (which would also make this script need one): the file
# is cut into top-level blocks, and only the blocks named below are parsed, by a reader for the
# subset of YAML manifests use. A block it cannot read is reported, never guessed.

# Keys the render owns: rewritten whenever the manifest's value differs from the rendered one.
DERIVED_KEYS = ("schemaVersion", "slice", "kind", "bc", "generator", "language", "tier", "lane", "tests")
# Inputs a manifest supplies when --set does not, per kind: (placeholder, path into the manifest).
# The top-level key of each path is input-bearing: kept as written while it still names the value
# the render used, else replaced by the rendered block.
MANIFEST_INPUTS = {
    "command": (("Command", ("handles", 0)), ("Event", ("publishes", 0)), ("Aggregate", ("writes", 0)),
                ("apiPath", ("endpoints", 0, "path")), ("owner", ("owner",))),
    "view": (("AggregateType", ("projections", 0, "aggregateTypes", 0)), ("Event", ("projections", 0, "from", 0)),
             ("apiPath", ("endpoints", 0, "path")), ("owner", ("owner",))),
    "automation": (("Event", ("consumes", 0)), ("owner", ("owner",))),
    "translation": (("externalSystem", ("externalSystem",)), ("owner", ("owner",))),
}
IDENTITY = ("slice", "kind", "language", "lane")

TOP_KEY = re.compile(r"""^(?:"([^"]+)"|'([^']+)'|([^\s#'"\-?:\[\]{},&*!|>%@`][^:#]*?))\s*:(?:\s|$)""")


def manifest_inputs_for(kind: str, lane: str):
    inputs = list(MANIFEST_INPUTS[kind])
    if kind == "command" and lane == "service-entity":
        inputs.insert(0, ("Entity", ("writes", 0)))
    return inputs


class Block:
    __slots__ = ("key", "lead", "body")

    def __init__(self, key: str, lead: list[str], body: list[str]):
        self.key, self.lead, self.body = key, lead, body


def split_blocks(text: str, where: str) -> tuple[list[str], list[Block], list[str]]:
    """(header, blocks, trailer): every top-level key, with the comment and blank lines above it."""
    header: list[str] = []
    blocks: list[Block] = []
    pending: list[str] = []
    for line in text.splitlines(keepends=True):
        bare = line.rstrip("\r\n")
        if not bare.strip() or bare.startswith("#"):
            pending.append(line)
            continue
        if bare[0] in " \t" or (bare[0] == "-" and not bare.startswith("---")) or (not blocks and bare.startswith("---")):
            if blocks:
                blocks[-1].body += pending + [line]
            else:
                header += pending + [line]
            pending = []
            continue
        m = TOP_KEY.match(bare)
        if not m:
            raise RenderError(f"{where}: cannot read top-level line {bare.strip()[:80]!r}")
        key = next(g for g in m.groups() if g is not None).strip()
        if any(b.key == key for b in blocks):
            raise RenderError(f"{where}: top-level key '{key}' appears twice")
        blocks.append(Block(key, pending, [line]))
        pending = []
    if not blocks:
        header, pending = header + pending, []
    return header, blocks, pending


def join_blocks(header: list[str], blocks: list[Block], trailer: list[str]) -> str:
    text = "".join(header + [line for b in blocks for line in b.lead + b.body] + trailer)
    return text if text.endswith("\n") else text + "\n"


class Unreadable(Exception):
    """A construct outside the subset the block reader handles."""


def strip_comment(line: str) -> str:
    quote, i = None, 0
    while i < len(line):
        ch = line[i]
        if quote:
            if ch == "\\" and quote == '"':
                i += 1
            elif ch == quote:
                quote = None
        elif ch in "'\"" and line[:i].rstrip()[-1:] in ("", ":", "[", "{", ",", "-"):
            quote = ch
        elif ch == "#" and (i == 0 or line[i - 1] in " \t"):
            return line[:i].rstrip()
        i += 1
    if quote:
        raise Unreadable("a quoted scalar spans lines")
    return line.rstrip()


class Flow:
    """A one-line YAML value: a flow sequence or mapping, a quoted or a plain scalar."""

    def __init__(self, s: str):
        self.s, self.i = s, 0

    def parse(self):
        v = self.value(top=True)
        self.ws()
        if self.i != len(self.s):
            raise Unreadable(f"unexpected {self.s[self.i:][:20]!r}")
        return v

    def peek(self) -> str:
        return self.s[self.i:self.i + 1]

    def ws(self):
        while self.peek() in (" ", "\t"):
            self.i += 1

    def value(self, top: bool = False):
        self.ws()
        c = self.peek()
        if c == "":
            return None
        if c in "[{":
            close = "]" if c == "[" else "}"
            self.i += 1
            seq: list = []
            mapping: dict = {}
            while True:
                self.ws()
                if self.peek() == close:
                    self.i += 1
                    return seq if c == "[" else mapping
                if c == "[":
                    seq.append(self.value())
                else:
                    k = self.scalar(stop=",:}")
                    self.ws()
                    if self.peek() != ":":
                        raise Unreadable("flow mapping entry without ':'")
                    self.i += 1
                    mapping[str(k)] = self.value()
                self.ws()
                if self.peek() == ",":
                    self.i += 1
                elif self.peek() != close:
                    raise Unreadable(f"unterminated flow {'sequence' if c == '[' else 'mapping'}")
        if c in "&*!|>":
            raise Unreadable(f"'{c}' (an anchor, alias, tag or block scalar)")
        return self.scalar(stop="" if top else ",]}")

    def scalar(self, stop: str):
        self.ws()
        c = self.peek()
        if c in ("'", '"'):
            j, out = self.i + 1, []
            while j < len(self.s):
                ch = self.s[j]
                if c == "'" and ch == "'":
                    if self.s[j + 1:j + 2] == "'":
                        out.append("'")
                        j += 2
                        continue
                    break
                if c == '"' and ch == "\\":
                    nxt = self.s[j + 1:j + 2]
                    out.append({"n": "\n", "t": "\t"}.get(nxt, nxt))
                    j += 2
                    continue
                if c == '"' and ch == '"':
                    break
                out.append(ch)
                j += 1
            else:
                raise Unreadable("unterminated quoted scalar")
            self.i = j + 1
            return "".join(out)
        j = self.i
        while j < len(self.s) and self.s[j] not in stop and not (
                ":" in stop and self.s[j] == ":" and self.s[j + 1:j + 2] in (" ", "")):
            j += 1
        raw, self.i = self.s[self.i:j].strip(), j
        return None if raw in ("", "~", "null") else raw


MAP_ENTRY = re.compile(r"""^("[^"]*"|'[^']*'|[^\s:'"\[{][^:]*?)\s*:(?:\s+(.*))?$""")


def read_block(block: Block):
    """The value of one top-level block, through the subset reader (block style, one-line flow values)."""
    lines = []
    for raw in block.body:
        bare = strip_comment(raw.rstrip("\r\n"))
        if bare.strip():
            lines.append((len(bare) - len(bare.lstrip(" ")), bare.strip()))
    m = MAP_ENTRY.match(lines[0][1])
    if not m:
        raise Unreadable(f"cannot read {lines[0][1][:40]!r}")
    rest = (m.group(2) or "").strip()
    if rest:
        if len(lines) > 1:
            raise Unreadable("a value continued over several lines")
        return Flow(rest).parse()
    if len(lines) == 1:
        return None
    value, i = node(lines, 1, lines[1][0])
    if i != len(lines):
        raise Unreadable("inconsistent indentation")
    return value


def node(lines: list[tuple[int, str]], i: int, indent: int):
    if lines[i][1][:1] == "-" and lines[i][1][1:2] in (" ", ""):
        seq = []
        while i < len(lines) and lines[i][0] == indent and lines[i][1][:1] == "-" and lines[i][1][1:2] in (" ", ""):
            rest = lines[i][1][1:].lstrip()
            if not rest:
                if i + 1 < len(lines) and lines[i + 1][0] > indent:
                    v, i = node(lines, i + 1, lines[i + 1][0])
                else:
                    v, i = None, i + 1
            elif rest[0] not in "[{'\"" and MAP_ENTRY.match(rest):
                inner = indent + len(lines[i][1]) - len(rest)
                lines = lines[:i] + [(inner, rest)] + lines[i + 1:]
                v, i = node(lines, i, inner)
            else:
                v, i = Flow(rest).parse(), i + 1
            seq.append(v)
        return seq, i
    mapping = {}
    while i < len(lines) and lines[i][0] == indent:
        m = MAP_ENTRY.match(lines[i][1])
        if not m:
            raise Unreadable(f"cannot read {lines[i][1][:40]!r}")
        k, rest = m.group(1).strip("'\""), (m.group(2) or "").strip()
        if rest:
            mapping[k], i = Flow(rest).parse(), i + 1
        elif i + 1 < len(lines) and (lines[i + 1][0] > indent or
                                     (lines[i + 1][0] == indent and lines[i + 1][1][:2] == "- ")):
            mapping[k], i = node(lines, i + 1, lines[i + 1][0])
        else:
            mapping[k], i = None, i + 1
    if i < len(lines) and lines[i][0] > indent:
        raise Unreadable("inconsistent indentation")
    return mapping, i


def manifest_value(blocks: dict[str, Block], key: str, where: str):
    if key not in blocks:
        return None
    try:
        return read_block(blocks[key])
    except (Unreadable, ValueError, IndexError) as e:
        raise RenderError(f"{where}: cannot read '{key}' ({e}) — the renderer reads the block style and the "
                          "one-line flow values slice.yaml uses; pass the inputs it holds with --set")


def dig(value, path) -> str | None:
    for step in path:
        if isinstance(step, int):
            value = value[step] if isinstance(value, list) and len(value) > step else None
        else:
            value = value.get(step) if isinstance(value, dict) else None
        if value is None:
            return None
    return value if isinstance(value, str) else None


def leaves(value) -> list[str]:
    if isinstance(value, dict):
        return [x for v in value.values() for x in leaves(v)]
    if isinstance(value, list):
        return [x for v in value for x in leaves(v)]
    return [] if value is None else [str(value)]


def version_tuple(v) -> tuple[int, ...]:
    try:
        return tuple(int(x) for x in str(v).split("."))
    except ValueError:
        return ()


def manifest_inputs(path: Path, kind: str, lang: str, lane: str, values: dict[str, str],
                    sources: dict[str, str]) -> str:
    """Check an existing manifest's identity against the flags, take the inputs --set did not
    supply from it, and return its text."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as e:
        raise RenderError(f"--manifest {path}: {e.strerror or e}")
    _, blocks, _ = split_blocks(text, str(path))
    byk = {b.key: b for b in blocks}
    ident = {k: manifest_value(byk, k, str(path)) for k in IDENTITY}
    for k, flag, opt in (("kind", kind, "--kind"), ("language", lang, "--lang"), ("lane", lane, "--lane")):
        if ident[k] is not None and ident[k] != flag:
            raise RenderError(f"{path}: {k}: {ident[k]} contradicts {opt} {flag}")
    key = SLICE_DIR[kind][1]
    sid = ident["slice"]
    if not (isinstance(sid, str) and re.fullmatch(r"[^.]+\.[^.]+", sid)):
        raise RenderError(f"{path}: slice: {sid!r} is not the qualified <bc>.<slice-id>")
    for name, v in zip(("bc", key), sid.split(".")):
        if name not in values:
            values[name], sources[name] = v, "manifest"
        elif values[name] != v:
            raise RenderError(f"{path}: slice: {sid} contradicts --set {name}={values[name]}")
    for name, where in manifest_inputs_for(kind, lane):
        v = dig(manifest_value(byk, where[0], str(path)), where[1:])
        if v is not None and name not in values:
            values[name], sources[name] = v, "manifest"
    if kind == "command" and lane == "service-entity" and "Entity" in values and "Aggregate" not in values:
        values["Aggregate"], sources["Aggregate"] = values["Entity"], sources["Entity"]
    return text


def merge_manifest(existing: str, rendered: str, kind: str, lane: str, values: dict[str, str],
                   where: str) -> tuple[str, list[str], list[str]]:
    """Merge the rendered manifest into the existing one. Returns (text, filled, corrected)."""
    header, blocks, trailer = split_blocks(existing, where)
    rheader, rblocks, _ = split_blocks(rendered, "rendered slice.yaml")
    rby = {b.key: b for b in rblocks}
    owned: dict[str, list[str]] = {}
    for name, path in manifest_inputs_for(kind, lane):
        if name in values:
            owned.setdefault(path[0], []).append(values[name])

    def value(b: Block):
        try:
            return read_block(b)
        except (Unreadable, ValueError, IndexError):
            return Unreadable

    filled, corrected = [], []
    for b in blocks:
        r = rby.get(b.key)
        if r is None:
            continue
        if b.key == "schemaVersion":
            replace = version_tuple(value(r)) > version_tuple(value(b))
        elif b.key in DERIVED_KEYS:
            replace = value(b) != value(r)
        elif b.key in owned:
            have = value(b)
            replace = have is Unreadable or not all(v in leaves(have) for v in owned[b.key])
        else:
            replace = False
        if replace:
            b.body = r.body
            corrected.append(b.key)
    seen = {b.key for b in blocks}
    for r in rblocks:
        if r.key not in seen:
            blocks.append(r)
            filled.append(r.key)
    return join_blocks(header or rheader, blocks, trailer), filled, corrected


def stamp_tests_none(manifest_text: str) -> str:
    """Stamp every entry of a rendered manifest's tests: block `present: false`."""
    header, blocks, trailer = split_blocks(manifest_text, "rendered slice.yaml")
    for b in blocks:
        if b.key == "tests":
            b.body = [re.sub(r"\bpresent:\s*true\b", "present: false", line) for line in b.body]
    return join_blocks(header, blocks, trailer)


# ---------------------------------------------------------------- facts of an existing BC

AGG_TYPE_OF = re.compile(r'AggregateType\.of\(\s*"([A-Za-z][A-Za-z0-9]*)"\s*\)')
SEALED_EVENT = re.compile(r"\bsealed\s+(?:interface|class)\s+([A-Z][A-Za-z0-9]*)Event\b")


def bc_facts(bc_dir: Path, lang: str, lane: str) -> dict[str, str]:
    """The inputs an existing BC's scaffold already names — each only when it names exactly one."""
    def sources(sub: str) -> list[Path]:
        d = bc_dir / sub
        return sorted(p for p in d.glob(f"*.{EXT[lang]}") if p.is_file()) if d.is_dir() else []

    def read(p: Path) -> str:
        return p.read_text(encoding="utf-8", errors="replace")

    facts = {}
    types = {t for p in sources("config") + sources("aggregates") for t in AGG_TYPE_OF.findall(read(p))}
    if len(types) == 1:
        facts["AggregateType"] = types.pop()
    parents = {a for p in sources("events") for a in SEALED_EVENT.findall(read(p))}
    if len(parents) == 1:
        facts["Aggregate"] = parents.pop()
    if lane == "aggregate":
        owners = [p.stem for p in sources("aggregates") if AGG_TYPE_OF.search(read(p))]
        if len(owners) == 1:
            facts["Aggregates"] = owners[0]
    if lane == "service-entity":
        entities = sources("entities")
        if len(entities) == 1:
            facts["Entity"] = entities[0].stem
    return facts


# ---------------------------------------------------------------- writing

class Stage:
    """A render's writes, held in memory: what --emit prints, what verify compares with the disk, and
    what a render checks it can write before it writes the first file. A read sees the staged text
    first, so a wiring edit to a file the same render creates (a new BC's configuration) edits that."""

    def __init__(self):
        self.files: dict[Path, str] = {}

    def write(self, path: Path, text: str) -> None:
        self.files[path] = text

    def read(self, path: Path) -> str:
        return self.files[path] if path in self.files else path.read_text(encoding="utf-8")

    def is_file(self, path: Path) -> bool:
        return path in self.files or path.is_file()


class NotWritable(Exception):
    """A target the render cannot write (EROFS, EACCES): exit 4, nothing written."""


def unwritable(paths) -> tuple[Path, int] | None:
    """The first target that cannot be written, with the errno that says why — checked before the first
    write, so a filesystem this process may not write leaves no partial slice."""
    for path in paths:
        target = path
        while not target.exists():
            target = target.parent
        if os.access(target, os.W_OK | (os.X_OK if target.is_dir() else 0)):
            continue
        try:
            readonly = bool(os.statvfs(target).f_flag & os.ST_RDONLY)
        except (AttributeError, OSError):
            readonly = False
        return target, errno.EROFS if readonly else errno.EACCES
    return None


def not_writable(path, code: int) -> NotWritable:
    return NotWritable(f"cannot write {path}: {os.strerror(code)} ({errno.errorcode[code]}) — nothing written; "
                       "re-run with --emit")


class Txn:
    """Every file a render creates or edits, so a failure part-way leaves the project as it was."""

    def __init__(self):
        self.created: list[Path] = []
        self.dirs: list[Path] = []
        self.originals: dict[Path, bytes] = {}

    def read(self, path: Path) -> str:
        return path.read_text(encoding="utf-8")

    def is_file(self, path: Path) -> bool:
        return path.is_file()

    def write(self, path: Path, text: str) -> None:
        if path.exists():
            self.originals.setdefault(path, path.read_bytes())
        else:
            missing, d = [], path.parent
            while not d.exists():
                missing.append(d)
                d = d.parent
            for d in reversed(missing):
                d.mkdir()
                self.dirs.append(d)
            self.created.append(path)
        path.write_text(text, encoding="utf-8", newline="\n")

    def rollback(self) -> None:
        for path, data in self.originals.items():
            if path not in self.created:
                path.write_bytes(data)
        for path in reversed(self.created):
            path.unlink(missing_ok=True)
        for d in reversed(self.dirs):
            try:
                d.rmdir()
            except OSError:
                pass


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


def wire_bean(path: Path, lang: str, values: dict[str, str], txn: "Txn | Stage") -> dict:
    entry = {"path": path, "edit": f"@Bean {values['sliceCamel']}Decider()"}
    if not txn.is_file(path):
        return {**entry, "status": "manual", "reason": "configuration class not found"}
    text = txn.read(path)
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
    txn.write(path, text)
    return {**entry, "status": "applied"}


def wire_permits(path: Path, values: dict[str, str], txn: "Txn | Stage") -> dict:
    event = values["Event"]
    entry = {"path": path, "edit": f"permits += {event}"}
    if not txn.is_file(path):
        return {**entry, "status": "manual", "reason": "sealed event parent not found"}
    text = txn.read(path)
    m = PERMITS_LINE.search(text)
    if not m:
        return {**entry, "status": "manual", "reason": "single-line 'permits … {   // /essentials:add-slice "
                                                        "appends each new variant …' anchor not found"}
    names = [n.strip() for n in m.group(2).split(",")]
    if event in names:
        return {**entry, "status": "present"}
    new = m.group(1) + ", ".join(names + [event]) + m.group(3)
    txn.write(path, text[: m.start()] + new + text[m.end():])
    return {**entry, "status": "applied"}


def configurators(main_root: Path, stage: Stage | None = None) -> list[Path]:
    """Every source file under main_root that constructs a decider configurator, staged files included."""
    staged = stage.files if stage else {}
    paths = {p for p in main_root.rglob("*") if p.suffix in (".java", ".kt") and p.is_file()}
    paths |= {p for p in staged if p.suffix in (".java", ".kt") and p.is_relative_to(main_root)}
    return sorted(p for p in paths
                  if CONFIGURATOR_CALL.search(staged[p] if p in staged else p.read_text(encoding="utf-8")))


def check_configurator(main_root: Path, written: Path | None, txn: "Txn | Stage") -> dict:
    found = configurators(main_root, txn if isinstance(txn, Stage) else None)
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


def wire(lang, kind, lane, values, main_root, txn: "Txn | Stage", app_wiring: Path | None = None) -> list[dict]:
    if kind != "command":
        return []
    bc_dir = main_root / Path(*values["packagePath"].split(".")) / values["bc"]
    out = []
    if lane == "decider":
        out.append(check_configurator(main_root, app_wiring, txn))
        out.append(wire_bean(bc_dir / "config" / f"{values['Bc']}Configuration.{EXT[lang]}", lang, values, txn))
    if lang == "java":
        out.append(wire_permits(bc_dir / "events" / f"{values['Aggregate']}Event.java", values, txn))
    return out


# ---------------------------------------------------------------- render command

def todos(files: list[Planned], rel) -> list[str]:
    out = []
    for f in sorted(files, key=lambda f: f.dest):
        for i, line in enumerate(f.text.splitlines(), 1):
            if "TODO" in line:
                out.append(f"{rel(f.dest)}:{i}: {line.strip()}")
    return out


def plugin_facts() -> dict[str, str]:
    """The Essentials release the templates target (the essentials.version pin)."""
    try:
        m = re.search(r"^\|\s*`essentials\.version`\s*\|\s*\**([0-9][^|*\s]*)", STACK_PINS.read_text(encoding="utf-8"),
                      re.M)
    except OSError:
        m = None
    return {"essentialsVersion": m.group(1)} if m else {}


def load_behaviours() -> dict | None:
    """templates/behaviours.json: what each family's rendered code already does. None for a template
    tree without one (--templates), whose report then lists no behaviours."""
    path = TEMPLATES / BEHAVIOURS
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))["families"]


def behaviour_report(lang: str, fams: list[str], planned: list[Planned], rel) -> tuple[list[dict], list[dict]]:
    catalog = load_behaviours()
    if catalog is None:
        return [], [{"path": rel(f.dest), "covers": []} for f in planned if f.role == "test"]
    rendered = {(f.family, f.template): f.dest for f in planned}
    behaviours, stubs = [], []
    for fam in fams:
        entry = catalog.get(f"{lang}/{fam}", {})
        for b in entry.get("behaviours", []):
            file, _, member = b["where"].partition("#")
            dest = rendered.get((fam, file))
            if dest is not None:
                behaviours.append({"id": b["id"], "status": b["status"], "summary": b["summary"],
                                   "where": rel(dest) + (f"#{member}" if member else "")})
        covers = {t["template"]: t["covers"] for t in entry.get("stubTests", [])}
        for f in planned:
            if f.family == fam and f.role == "test":
                stubs.append({"path": rel(f.dest), "covers": list(covers.get(f.template, []))})
    return behaviours, stubs


def render(lang, kind, lane, values, project_root: Path, main_root: Path, test_root: Path,
           new_bc=False, with_state=False, do_wire=False, dry_run=False, tests="stub",
           manifest: Path | None = None, sources: dict[str, str] | None = None, mode: str = "write") -> dict:
    """mode `write` renders into the project; `emit` and `verify` stage the same render in memory and
    write nothing — emit adds each file's content to the report, verify compares it with the disk."""
    verify = mode == "verify"
    sources = dict(sources or {k: "set" for k in values})
    existing = manifest_inputs(manifest, kind, lang, lane, values, sources) if manifest is not None else None
    if not new_bc and "packagePath" in values and "bc" in values:
        bc_dir = main_root / Path(*values["packagePath"].split(".")) / values["bc"]
        for k, v in bc_facts(bc_dir, lang, lane).items():
            if k not in values:
                values[k], sources[k] = v, "project"
    values = derive(values, lane)
    validate(values)
    fams, rendered = plan_render(lang, kind, lane, values, main_root, test_root, new_bc, with_state)

    def plan(tolerant: bool):
        planned, app_wiring = rendered, None
        if APP_WIRING in fams:
            # Create-if-absent: the application's one configurator is written by the first decider BC only.
            app_dir = main_root / Path(*values["packagePath"].split("."))
            app = next(f for f in planned if f.dest.parent == app_dir)
            app_file = app.dest
            # verify cannot tell the configurator a render wrote from one the application already had: an
            # identical file counts as written, any other as the application's own, as render would treat it.
            ours = tolerant and app_file.is_file() and app_file.read_bytes() == app.text.encode("utf-8")
            if not ours and (app_file.exists() or configurators(main_root)):
                planned = [f for f in planned if f.dest != app_file]
            else:
                app_wiring = app_file
        return (*preflight(lang, kind, lane, values, main_root, new_bc, planned, manifest, tolerant), app_wiring)

    # An --emit run again part-way through writing its files (the caller fetching them in parts) finds
    # some targets already there; it is refused below unless each one holds exactly what it would emit.
    resumed: RenderError | None = None
    try:
        planned, kept, app_wiring = plan(verify)
    except RenderError as refused:
        if mode != "emit":
            raise
        resumed = refused
        planned, kept, app_wiring = plan(True)
    if tests == "none":
        planned = [f for f in planned if f.role != "test"]
    top, key = SLICE_DIR[kind]
    manifest_dest = main_root / Path(*values["packagePath"].split(".")) / values["bc"] / top / values[key] / "slice.yaml"
    manifest_report: dict = {"source": "rendered"}
    for f in planned:
        if f.dest == manifest_dest:
            if tests == "none":
                f.text = stamp_tests_none(f.text)
            if existing is not None:
                f.text, filled, corrected = merge_manifest(existing, f.text, kind, lane, values, str(manifest))
                manifest_report = {"source": "merged", "filled": filled, "corrected": corrected}

    def rel(p: Path) -> str:
        try:
            return Path(os.path.relpath(p, project_root)).as_posix()
        except ValueError:
            return p.as_posix()

    behaviours, stubs = behaviour_report(lang, fams, planned, rel)
    report = {"interface": INTERFACE, "reportVersion": REPORT_VERSION, **plugin_facts(),
              "language": lang, "kind": kind, "lane": lane, "tier": values["tier"],
              "slice": f"{values['bc']}.{values[key]}", "tests": tests, "families": fams,
              "inputs": {k: values[k] for k in sorted(values)},
              "inputSources": {k: sources.get(k, "derived") for k in sorted(values)},
              "written": sorted(rel(f.dest) for f in planned),
              "files": [{"path": rel(f.dest), "role": f.role} for f in sorted(planned, key=lambda f: f.dest)],
              "kept": sorted(rel(p) for p in kept),
              "manifest": {"path": rel(manifest_dest), **manifest_report},
              "wiring": [], "todos": [], "behaviours": behaviours, "stubTests": stubs}
    if dry_run:
        return report
    stage = Stage()
    for f in planned:
        stage.write(f.dest, f.text)
    if do_wire:
        report["wiring"] = [{**w, "path": rel(w["path"])}
                            for w in wire(lang, kind, lane, values, main_root, stage, app_wiring)]
    report["todos"] = todos(planned, rel)
    if mode == "emit":
        if resumed is not None:
            scope = bc_dir_of(main_root, values) if new_bc else manifest_dest.parent
            foreign = [p for p in scope.rglob("*") if p.is_file() and p not in stage.files and p not in kept] \
                if scope.is_dir() else []
            differ = [f.dest for f in planned if f.dest.is_file() and not (manifest and f.dest == manifest_dest)
                      and f.dest.read_bytes() != stage.files[f.dest].encode("utf-8")]
            if foreign or differ:
                raise resumed
        roles = {f.dest: f.role for f in planned}
        report["files"] = [{"path": rel(p), "role": roles.get(p, "wiring"),
                            "action": "replace" if p.exists() else "create", "content": stage.files[p]}
                           for p in sorted(stage.files)]
        return report
    if verify:
        # A wiring edit staged here is one the disk lacks: re-wiring a wired file stages nothing.
        missing = sorted(rel(p) for p in stage.files if not p.is_file())
        differs = sorted(rel(p) for p in stage.files
                         if p.is_file() and p.read_bytes() != stage.files[p].encode("utf-8"))
        report["verify"] = {"checked": sorted(rel(p) for p in stage.files), "missing": missing, "differs": differs}
        return report
    blocked = unwritable(stage.files)
    if blocked:
        raise not_writable(*blocked)
    txn = Txn()
    try:
        for path, text in stage.files.items():
            txn.write(path, text)
    except OSError as e:
        txn.rollback()
        if e.errno in (errno.EROFS, errno.EACCES, errno.EPERM):
            raise not_writable(e.filename, e.errno) from e
        raise
    except BaseException:
        txn.rollback()
        raise
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
        if "seed" in step or "orientation" in step:
            # Files the user writes by hand between two slices (the service-entity entity), or a
            # planner's orientation files written before any code (--manifest).
            key = "seed" if "seed" in step else "orientation"
            seed = golden / step[key]
            clashes = [p for p in tree(seed) if (out / p).exists()]
            if clashes:
                raise RenderError(f"{key} {step[key]} would overwrite {', '.join(clashes)}")
            shutil.copytree(seed, out, dirs_exist_ok=True)
            reports.append({"seed": step[key], "written": sorted(tree(seed))})
            continue
        values = {**comp.get("inputs", {}), **step.get("inputs", {})}
        rep = render(lang, step["kind"], lane, values, out, main_root, test_root,
                     new_bc=step.get("newBc", False), with_state=step.get("withState", False), do_wire=True,
                     tests=step.get("tests", "stub"),
                     manifest=out / step["manifest"] if "manifest" in step else None)
        # The pin moves with every Essentials release; a golden that held it would churn on each one.
        rep.pop("essentialsVersion", None)
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

    def slice_args(p):
        shape(p)
        p.add_argument("--main-root", required=True, help="e.g. backend/src/main/kotlin")
        p.add_argument("--test-root", required=True, help="e.g. backend/src/test/kotlin")
        p.add_argument("--project-root", default=".", help="paths in the report are relative to this")
        p.add_argument("--with-state", action="store_true", help="decider lane: also emit the per-slice State + Evolver")
        p.add_argument("--wire", action="store_true", help="apply the @Bean and permits edits at their anchors")
        p.add_argument("--inputs", help="JSON object of placeholder values")
        p.add_argument("--set", action="append", metavar="NAME=VALUE", help="a placeholder value (repeatable)")
        p.add_argument("--tests", choices=("stub", "none"), default="stub",
                       help="stub (default): also write the stub tests; none: write nothing under --test-root")
        p.add_argument("--manifest", help="render from this existing slice.yaml at the slice's own path, and update it")
        p.add_argument("--json", action="store_true")

    r = sub.add_parser("render", help="render one slice into a project")
    slice_args(r)
    r.add_argument("--dry-run", action="store_true", help="validate and list the files; write nothing")
    r.add_argument("--emit", action="store_true",
                   help="validate and print every file's content and action as JSON; write nothing")
    r.add_argument("--only", metavar="PATH",
                   help="with --emit: print only this file's content, raw (a files[].path of the report)")

    v = sub.add_parser("verify", help="compare the files a render with the same options writes with the disk")
    slice_args(v)

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
        if args.cmd == "verify":
            rep = render(args.lang, args.kind, args.lane, load_inputs(args), Path(args.project_root),
                         Path(args.main_root), Path(args.test_root), args.new_bc, args.with_state,
                         args.wire, False, args.tests, Path(args.manifest) if args.manifest else None, mode="verify")
            bad = rep["verify"]["missing"] + rep["verify"]["differs"]
            if args.json:
                print(json.dumps(rep, indent=2, ensure_ascii=False))
            else:
                for p in rep["verify"]["missing"]:
                    print(f"missing {p}")
                for p in rep["verify"]["differs"]:
                    print(f"differs {p}")
                if not bad:
                    print(f"verified {len(rep['verify']['checked'])} file(s): identical to the render")
            return 1 if bad else 0
        if args.cmd == "render":
            if args.only and not args.emit:
                raise RenderError("--only needs --emit")
            rep = render(args.lang, args.kind, args.lane, load_inputs(args), Path(args.project_root),
                         Path(args.main_root), Path(args.test_root), args.new_bc, args.with_state,
                         args.wire, args.dry_run and not args.emit, args.tests,
                         Path(args.manifest) if args.manifest else None, mode="emit" if args.emit else "write")
            if args.only:
                content = next((f["content"] for f in rep["files"] if f["path"] == args.only), None)
                if content is None:
                    raise RenderError(f"--only {args.only}: not a file this render emits (files[].path of --emit)")
                sys.stdout.write(content)
                return 0
            if args.json or args.emit:
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
    except NotWritable as e:
        print(f"render-slice: {e}", file=sys.stderr)
        return 4
    except (OSError, json.JSONDecodeError) as e:
        print(f"render-slice: {e}", file=sys.stderr)
        return 2
    return 2


if __name__ == "__main__":
    sys.exit(main())
