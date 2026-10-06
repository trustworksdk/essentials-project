#!/usr/bin/env python3
"""init-render — render a new Essentials project from the plugin's template tree.

Why this exists
---------------
`/essentials:init` asks the questions; this script writes the project. Two runs with the same
answers produce the same bytes, so what a user receives is exactly what the committed goldens
under `tests/golden/init/` show and what CI compiles. The model keeps the judgement calls (the
questions, merging into an existing CLAUDE.md/README.md, diagnosing a failed smoke build); nothing
it would otherwise write freehand is left to it.

Inputs: an answers JSON, the template tree `references/init-assets/project/` with its
`manifest.json`, the version pins in `references/stack/stack-pins.md`, and the S2.1 table of
`references/stack/stack-contract.md` (its "what breaks without it" text becomes POM comments).
A template can reach a version only through `{{pin:…}}`, so no version lives in the tree.

Usage
-----
    init-render.py --answers F --out DIR [--workspace-out WDIR] [--preserve PATH]...
    init-render.py --host <lang>-<db> --out DIR      slice-compile host from tests/golden/init/hosts.json
    init-render.py --answers F --tree                 print the serialized tree
    init-render.py --answers F --hooks                the post-render commands to run, as JSON
    init-render.py --self-test                        grammar and serializer cases
    init-render.py --all-combinations                 render every answer combination, check invariants
                                                      and run scripts/stack-lint.py on each (--no-stack-lint skips)
    init-render.py --check                            diff the goldens against a fresh render
    init-render.py --update-golden                    rewrite the goldens (maintainer)
    init-render.py --list-names                       every {{pin:…}} and {{why:S2.1:…}} name available

    --assets DIR / --pins FILE / --contract FILE / --golden DIR override the plugin defaults.

Template grammar
----------------
    <!-- IF var -->  <!-- IF var=a -->  <!-- IF var=a|b -->  <!-- IF var!=a -->  …  <!-- END var -->
    <!-- PATHS --> … <!-- END PATHS -->   paths inside must exist in the rendered tree
    {{var}}  {{pin:<stack-pins name>}}  {{why:S2.1:<artifact>}}

Blocks nest; END names the variable of the innermost open IF. A directive alone on its line
removes the whole line. Placeholders are substituted before the IF pass, so an unknown one fails
even inside a branch no answer set selects. A placeholder has no whitespace inside the braces,
which keeps JSX's `style={{ a: 1 }}` literal.

Exit codes
----------
    0   ok
    1   a check failed (invariant, golden diff, self-test)
    2   could not run (bad answers, unknown placeholder or pin, malformed template or manifest,
        target directory not empty)
"""

from __future__ import annotations

import argparse
import difflib
import itertools
import json
import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path

PLUGIN_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_ASSETS = PLUGIN_ROOT / "references" / "init-assets" / "project"
DEFAULT_PINS = PLUGIN_ROOT / "references" / "stack" / "stack-pins.md"
DEFAULT_CONTRACT = PLUGIN_ROOT / "references" / "stack" / "stack-contract.md"
DEFAULT_GOLDEN = PLUGIN_ROOT / "tests" / "golden" / "init"
STACK_LINT = PLUGIN_ROOT / "scripts" / "stack-lint.py"

ENUMS = {
    "language": ("kotlin", "java"),
    "db": ("pg-event-sourced", "pg-crud", "mongo"),
    "web": ("webflux", "webmvc"),
    "frontend": ("embedded", "standalone", "none"),
    "compose": (True, False),
    "lintGate": ("hook", "script", "none"),
}
METADATA = ("projectName", "groupId", "artifactId")
OPTIONAL = ("packagePath",)
DERIVED_ENUMS = {"stack": ("full-stack", "backend-only")}

# Hard keywords of Java and Kotlin: a package segment may be neither.
KEYWORDS = frozenset("""
abstract assert boolean break byte case catch char class const continue default do double else enum
extends final finally float for goto if implements import instanceof int interface long native new
package private protected public return short static strictfp super switch synchronized this throw
throws transient try void volatile while true false null as fun in is object typealias typeof val var
when
""".split())

# `pin:` names are table cells and may contain spaces; everything else is one token.
PLACEHOLDER = re.compile(r"\{\{(pin:[^{}\n]*[^{}\s]|[A-Za-z][\w.-]*(?::[^{}\s]+)*)\}\}")
DIRECTIVE = re.compile(r"<!--\s*(IF\s+[\w.-]+(?:!?=[\w.|-]+)?|END\s+[\w.-]+|PATHS)\s*-->")
IF_BODY = re.compile(r"IF\s+([\w.-]+)(?:(!?=)([\w.|-]+))?$")
# A leftover is anything placeholder-shaped whose first character is not whitespace; this also
# catches a malformed one such as `{{pin:Node (via …)}}` that PLACEHOLDER itself does not match.
LEFTOVER_PLACEHOLDER = re.compile(r"\{\{[^\s{}][^{}\n]*\}\}")
LEFTOVER_DIRECTIVE = re.compile(r"<!--\s*(?:IF|END)\s|<!--\s*PATHS\s*-->")
PATHS_VAR = "PATHS"

PATH_EXTENSIONS = frozenset(
    "md xml yml yaml json kt kts java ts tsx js mjs cjs sh properties html css env example txt".split())
EXECUTABLE_NAMES = frozenset({"mvnw"})


class RenderError(Exception):
    """The render cannot proceed (exit 2)."""


# --------------------------------------------------------------------------------------------
# Sources: pins and the S2.1 table
# --------------------------------------------------------------------------------------------

def _cells(line: str):
    return [c.strip() for c in line.strip().strip("|").split("|")]


def load_pins(path: Path) -> dict[str, str]:
    """name -> value, from every table row of stack-pins.md whose second cell is one token with a digit.

    The name is the first cell without backticks or bold, as written; it is also registered
    without a trailing `(…)`, and `a / b` registers `a` and `b` as well. The value keeps its own
    form (`~1.2.3`, `v1.2.3`, an image tag) because that is what the consuming file wants.
    """
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise RenderError(f"cannot read pins {path}: {exc}")
    pins: dict[str, str] = {}
    for line in text.splitlines():
        if not line.lstrip().startswith("|"):
            continue
        cells = _cells(line)
        if len(cells) < 2:
            continue
        value = cells[1].replace("*", "").replace("`", "").strip()
        if not re.fullmatch(r"[^\s|]*\d[^\s|]*", value):
            continue
        raw = cells[0].replace("`", "").replace("*", "").strip()
        short = re.sub(r"\s*\(.*\)\s*$", "", raw)
        for name in {raw, short, *(n.strip() for n in short.split(" / "))}:
            if not name:
                continue
            if name in pins and pins[name] != value:
                raise RenderError(f"{path}: pin {name!r} has two values ({pins[name]}, {value})")
            pins[name] = value
    if not pins:
        raise RenderError(f"no pin found in the tables of {path}")
    return pins


def _plain(markdown: str) -> str:
    text = markdown.replace("`", "").replace("**", "").replace("*", "")
    text = re.sub(r"\s+", " ", text).strip()
    while "--" in text:  # an XML comment may not contain "--"
        text = text.replace("--", "-")
    return text


def load_why(path: Path) -> dict[str, str]:
    """artifact -> S2.1 "What breaks without it" text, keyed by coordinate and by artifactId."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise RenderError(f"cannot read contract {path}: {exc}")
    why: dict[str, str] = {}
    in_section = False
    for line in text.splitlines():
        if line.startswith("#"):
            in_section = bool(re.match(r"#+\s+S2\.1\b", line))
            continue
        if not in_section or not line.lstrip().startswith("|"):
            continue
        cells = _cells(line)
        if len(cells) < 3 or set(cells[1]) <= set("-: "):
            continue
        breaks = _plain(cells[2])
        for artifact in re.findall(r"`([^`]+)`", cells[1]):
            for key in {artifact, artifact.split(":")[-1]}:
                if key in why and why[key] != breaks:
                    raise RenderError(f"{path}: S2.1 lists {key!r} twice with different text")
                why[key] = breaks
    if not why:
        raise RenderError(f"no S2.1 table found in {path}")
    return why


# --------------------------------------------------------------------------------------------
# Answers
# --------------------------------------------------------------------------------------------

def derive_package(group_id: str, artifact_id: str) -> str:
    segment = re.sub(r"[^a-z0-9]", "", artifact_id.lower()).lstrip("0123456789")
    if not segment or segment == group_id.split(".")[-1]:
        return group_id
    return f"{group_id}.{segment}"


def check_package(package: str) -> None:
    for part in package.split("."):
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", part) or part in KEYWORDS:
            raise RenderError(f"package {package!r}: segment {part!r} is not a valid Java/Kotlin identifier")


def normalize_answers(raw: dict) -> dict:
    if not isinstance(raw, dict):
        raise RenderError("answers must be a JSON object")
    unknown = set(raw) - set(ENUMS) - set(METADATA) - set(OPTIONAL)
    if unknown:
        raise RenderError(f"unknown answer keys: {', '.join(sorted(unknown))}")
    answers = {}
    for key, allowed in ENUMS.items():
        if key not in raw:
            raise RenderError(f"answer {key!r} is missing")
        if raw[key] not in allowed or type(raw[key]) is not type(allowed[0]):
            raise RenderError(f"answer {key}={raw[key]!r} is not one of {list(allowed)}")
        answers[key] = raw[key]
    for key in METADATA:
        value = raw.get(key)
        if not isinstance(value, str) or not value.strip():
            raise RenderError(f"answer {key!r} must be a non-empty string")
        answers[key] = value
    if not re.fullmatch(r"[a-z][a-z0-9-]*", answers["artifactId"]):
        raise RenderError(f"artifactId {answers['artifactId']!r}: use lowercase letters, digits and '-'")
    check_package(answers["groupId"])
    package = raw.get("packagePath") or derive_package(answers["groupId"], answers["artifactId"])
    check_package(package)
    answers["packagePath"] = package
    return answers


def build_vars(answers: dict, pins: dict[str, str]) -> dict:
    if "essentials.version" not in pins:
        raise RenderError("stack-pins has no essentials.version row")
    kotlin = answers["language"] == "kotlin"
    return {
        **answers,
        "stack": "backend-only" if answers["frontend"] == "none" else "full-stack",
        "sourceLang": answers["language"],
        "sourceExt": "kt" if kotlin else "java",
        "appFile": "Application.kt" if kotlin else "Application.java",
        "packageDir": answers["packagePath"].replace(".", "/"),
        "db_label": "MongoDB" if answers["db"] == "mongo" else "PostgreSQL",
        "essentialsVersion": pins["essentials.version"],
    }


# --------------------------------------------------------------------------------------------
# Template grammar
# --------------------------------------------------------------------------------------------

@dataclass
class Context:
    vars: dict
    pins: dict[str, str]
    why: dict[str, str]


def _placeholder_value(name: str, ctx: Context, where: str) -> str:
    if name.startswith("pin:"):
        pin = name[4:]
        if pin not in ctx.pins:
            raise RenderError(f"{where}: unknown pin {{{{{name}}}}} (not a row of stack-pins.md)")
        return ctx.pins[pin]
    if name.startswith("why:"):
        parts = name.split(":", 2)
        if len(parts) != 3 or parts[1] != "S2.1":
            raise RenderError(f"{where}: {{{{{name}}}}} — only why:S2.1:<artifact> exists")
        if parts[2] not in ctx.why:
            raise RenderError(f"{where}: {{{{{name}}}}} — no S2.1 row names {parts[2]!r}")
        return ctx.why[parts[2]]
    if name not in ctx.vars:
        raise RenderError(f"{where}: unknown placeholder {{{{{name}}}}}")
    value = ctx.vars[name]
    if isinstance(value, bool):
        return "true" if value else "false"
    return str(value)


def substitute(text: str, ctx: Context, where: str) -> str:
    text = PLACEHOLDER.sub(lambda m: _placeholder_value(m.group(1), ctx, where), text)
    malformed = re.search(r"\{\{\s+(?:pin|why):[^{}\n]*\}\}|\{\{why:[^{}\n]*\s[^{}\n]*\}\}", text)
    if malformed:
        raise RenderError(f"{where}: malformed placeholder {malformed.group(0)} (see --list-names)")
    return text


def _directive_spans(text: str):
    """(start, end, body) per directive; a directive alone on its line takes the whole line."""
    spans = []
    last_end = 0
    for m in DIRECTIVE.finditer(text):
        start, end = m.start(), m.end()
        line_start = text.rfind("\n", 0, start) + 1
        line_end = text.find("\n", end)
        line_end = len(text) if line_end == -1 else line_end + 1
        if (line_start >= last_end and not text[line_start:start].strip()
                and not text[end:line_end].strip()):
            start, end = line_start, line_end
        spans.append((start, end, m.group(1).strip()))
        last_end = end
    return spans


def _condition(body: str, variables: dict, where: str) -> bool:
    m = IF_BODY.match(body)
    if not m:
        raise RenderError(f"{where}: malformed directive <!-- {body} -->")
    var, op, values = m.group(1), m.group(2), m.group(3)
    allowed = {**ENUMS, **DERIVED_ENUMS}
    if var not in allowed:
        raise RenderError(f"{where}: IF on unknown variable {var!r} (conditions: {', '.join(sorted(allowed))})")
    current = variables[var]
    if op is None:
        if not isinstance(current, bool):
            raise RenderError(f"{where}: bare IF {var} — {var} is not a boolean; use IF {var}=<value>")
        return current
    names = {"true" if v is True else "false" if v is False else v for v in allowed[var]}
    wanted = values.split("|")
    for value in wanted:
        if value not in names:
            raise RenderError(f"{where}: IF {var}={value} — {value!r} is not one of {sorted(names)}")
    shown = "true" if current is True else "false" if current is False else current
    return (shown in wanted) if op == "=" else (shown not in wanted)


def render_conditionals(text: str, variables: dict, where: str):
    """Resolve IF/END and PATHS; return (text, [rendered PATHS region text, …])."""
    out: list[str] = []
    regions: list[str] = []
    stack: list[list] = []  # [var, keep, buffer]
    pos = 0

    def emit(s: str):
        (stack[-1][2] if stack else out).append(s)

    for start, end, body in _directive_spans(text):
        emit(text[pos:start])
        pos = end
        parent_keep = stack[-1][1] if stack else True
        if body == PATHS_VAR:
            stack.append([PATHS_VAR, parent_keep, []])
        elif body.startswith("IF"):
            keep = _condition(body, variables, where) and parent_keep
            m = IF_BODY.match(body)
            if m is None:
                raise RenderError(f"{where}: malformed directive <!-- {body} -->")
            stack.append([m.group(1), keep, []])
        else:
            var = body.split(None, 1)[1]
            if not stack or stack[-1][0] != var:
                raise RenderError(f"{where}: END {var} closes {'IF ' + stack[-1][0] if stack else 'nothing'}")
            name, keep, buf = stack.pop()
            if keep:
                chunk = "".join(buf)
                if name == PATHS_VAR:
                    regions.append(chunk)
                emit(chunk)
    emit(text[pos:])
    if stack:
        raise RenderError(f"{where}: unclosed IF {stack[-1][0]}")
    rendered = "".join(out)
    leftover = LEFTOVER_DIRECTIVE.search(rendered)
    if leftover:
        line = rendered.count("\n", 0, leftover.start()) + 1
        raise RenderError(f"{where}: malformed directive at rendered line {line}")
    return rendered, regions


def render_text(text: str, ctx: Context, where: str):
    return render_conditionals(substitute(text, ctx, where), ctx.vars, where)


# --------------------------------------------------------------------------------------------
# Manifest and project rendering
# --------------------------------------------------------------------------------------------

@dataclass
class Entry:
    dst: str                  # output path; workspace entries are prefixed "@workspace/"
    mode: str                 # "644" | "755"
    content: str | None       # rendered text; None for a copy
    copy_src: str | None = None
    regions: list[str] = field(default_factory=list)

    @property
    def workspace(self) -> bool:
        return self.dst.startswith("@workspace/")

    @property
    def path(self) -> str:
        return self.dst.split("/", 1)[1] if self.workspace else self.dst


@dataclass
class Manifest:
    files: list[dict]
    generated: list[str]
    hooks: list[dict] = field(default_factory=list)


def _check_when(when: dict, where: str) -> None:
    if not isinstance(when, dict):
        raise RenderError(f"{where}: 'when' must be an object")
    for key, wanted in when.items():
        if key not in ENUMS and key not in DERIVED_ENUMS:
            raise RenderError(f"{where}: 'when' on unknown variable {key!r}")
        allowed = {**ENUMS, **DERIVED_ENUMS}[key]
        for value in wanted if isinstance(wanted, list) else [wanted]:
            if value not in allowed or type(value) is not type(allowed[0]):
                raise RenderError(f"{where}: when {key}={value!r} is not one of {list(allowed)}")


def load_manifest(assets: Path) -> Manifest:
    path = assets / "manifest.json"
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise RenderError(f"cannot read {path}: {exc}")
    except json.JSONDecodeError as exc:
        raise RenderError(f"{path}: {exc}")
    files = data.get("files")
    if not isinstance(files, list) or not files:
        raise RenderError(f"{path}: 'files' must be a non-empty list")
    extra = set(data) - {"files", "generatedPaths", "hooks"}
    if extra:
        raise RenderError(f"{path}: unknown keys {sorted(extra)}")
    referenced = set()
    for i, spec in enumerate(files):
        where = f"{path}: files[{i}]"
        extra = set(spec) - {"src", "copy", "dst", "when", "render", "mode", "root"}
        if extra:
            raise RenderError(f"{where}: unknown keys {sorted(extra)}")
        if ("src" in spec) == ("copy" in spec):
            raise RenderError(f"{where}: exactly one of 'src' and 'copy'")
        if not isinstance(spec.get("dst"), str) or not spec["dst"] or spec["dst"].startswith("/") \
                or ".." in spec["dst"].split("/"):
            raise RenderError(f"{where}: 'dst' must be a relative path")
        if spec.get("root", "project") not in ("project", "workspace"):
            raise RenderError(f"{where}: 'root' is 'project' or 'workspace'")
        if spec.get("mode", "644") not in ("644", "755"):
            raise RenderError(f"{where}: 'mode' is \"644\" or \"755\"")
        _check_when(spec.get("when", {}), where)
        if "src" in spec:
            src = assets / spec["src"]
            if not src.is_file():
                raise RenderError(f"{where}: src {spec['src']} does not exist")
            referenced.add(Path(spec["src"]).as_posix())
        else:
            if not (PLUGIN_ROOT / spec["copy"]).is_file():
                raise RenderError(f"{where}: copy {spec['copy']} does not exist in the plugin")
    present = {p.relative_to(assets).as_posix() for p in assets.rglob("*") if p.is_file()}
    unreferenced = sorted(present - referenced - {"manifest.json"})
    if unreferenced:
        raise RenderError(f"{path}: template files no entry names: {', '.join(unreferenced)}")
    generated = data.get("generatedPaths", [])
    if not isinstance(generated, list) or not all(isinstance(g, str) for g in generated):
        raise RenderError(f"{path}: 'generatedPaths' must be a list of paths")
    hooks = data.get("hooks", [])
    if not isinstance(hooks, list):
        raise RenderError(f"{path}: 'hooks' must be a list")
    ids = set()
    for i, hook in enumerate(hooks):
        where = f"{path}: hooks[{i}]"
        extra = set(hook) - {"id", "run", "cwd", "when", "why"}
        if extra:
            raise RenderError(f"{where}: unknown keys {sorted(extra)}")
        if not isinstance(hook.get("id"), str) or not isinstance(hook.get("run"), str) or hook["id"] in ids:
            raise RenderError(f"{where}: needs a unique string 'id' and a string 'run'")
        ids.add(hook["id"])
        _check_when(hook.get("when", {}), where)
    return Manifest(files, generated, hooks)


def _selected(spec: dict, variables: dict) -> bool:
    for key, wanted in spec.get("when", {}).items():
        options = wanted if isinstance(wanted, list) else [wanted]
        if variables[key] not in options:
            return False
    return True


def _mode(spec: dict, dst: str) -> str:
    if "mode" in spec:
        return spec["mode"]
    name = dst.rsplit("/", 1)[-1]
    executable = name.endswith(".sh") or name in EXECUTABLE_NAMES or dst.startswith(".githooks/")
    return "755" if executable else "644"


@dataclass
class Sources:
    assets: Path
    pins: dict[str, str]
    why: dict[str, str]
    manifest: Manifest


def load_sources(assets: Path, pins: Path, contract: Path) -> Sources:
    return Sources(assets, load_pins(pins), load_why(contract), load_manifest(assets))


def render_hooks(raw_answers: dict, src: Sources) -> list[dict]:
    """The manifest's post-render commands selected for these answers, in manifest order.

    The renderer never runs them (they need Maven, npm or the network); the caller does, from the
    output directory joined with `cwd`.
    """
    answers = normalize_answers(raw_answers)
    ctx = Context(build_vars(answers, src.pins), src.pins, src.why)
    hooks = []
    for hook in src.manifest.hooks:
        if _selected(hook, ctx.vars):
            where = f"hook {hook['id']}"
            hooks.append({"id": hook["id"], "cwd": substitute(hook.get("cwd", "."), ctx, where),
                          "run": substitute(hook["run"], ctx, where), "why": hook.get("why", "")})
    return hooks


def render_project(raw_answers: dict, src: Sources) -> list[Entry]:
    answers = normalize_answers(raw_answers)
    ctx = Context(build_vars(answers, src.pins), src.pins, src.why)
    entries: dict[str, Entry] = {}
    for spec in src.manifest.files:
        if not _selected(spec, ctx.vars):
            continue
        where = spec.get("src") or spec["copy"]
        dst = substitute(spec["dst"], ctx, f"manifest dst {spec['dst']}")
        dst = dst.replace("__PACKAGE__", ctx.vars["packageDir"])
        if spec.get("root") == "workspace":
            dst = "@workspace/" + dst
        if dst in entries:
            raise RenderError(f"two manifest entries write {dst} for one answer set")
        mode = _mode(spec, dst.removeprefix("@workspace/"))
        if "copy" in spec:
            entries[dst] = Entry(dst, mode, None, copy_src=spec["copy"])
            continue
        raw = (src.assets / spec["src"]).read_bytes()
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            raise RenderError(f"{where}: not UTF-8")
        text = text.replace("\r\n", "\n")
        if spec.get("render", True):
            text, regions = render_text(text, ctx, where)
        else:
            regions = []
        entries[dst] = Entry(dst, mode, text, regions=regions)
    return [entries[k] for k in sorted(entries, key=lambda d: (d.startswith("@workspace/"), d))]


# --------------------------------------------------------------------------------------------
# Serialized tree (the golden format)
# --------------------------------------------------------------------------------------------

NO_EOL = "\\ No newline at end of file\n"


def serialize(entries: list[Entry], title: str, hooks: list[dict] | None = None) -> str:
    parts = [f"# init-render golden: {title} — regenerate with init-render.py --update-golden\n"]
    for e in entries:
        if e.copy_src is not None:
            parts.append(f"=== COPY {e.dst} <- {e.copy_src} ({e.mode}) ===\n")
            continue
        content = e.content or ""
        parts.append(f"=== FILE {e.dst} ({e.mode}) ===\n")
        parts.append(content)
        if content and not content.endswith("\n"):
            parts.append("\n" + NO_EOL)
    for hook in hooks or []:
        parts.append(f"=== HOOK {hook['id']} (cwd {hook['cwd']}) ===\n{hook['run']}\n")
    return "".join(parts)


# --------------------------------------------------------------------------------------------
# Writing to disk
# --------------------------------------------------------------------------------------------

def _prepare_target(root: Path, preserve: set[str], strict: bool) -> None:
    if root.exists() and not root.is_dir():
        raise RenderError(f"{root} exists and is not a directory")
    if not root.exists() or not strict:
        return
    for child in root.iterdir():
        name = child.name
        if name == ".git" or name in preserve:
            continue
        if child.is_dir() and any(p == name or p.startswith(name + "/") for p in preserve):
            continue
        raise RenderError(f"{root} is not empty ({name}); name kept files with --preserve")


def _write(root: Path, rel: str, entry: Entry, side_render: bool) -> str:
    path = root / rel
    if path.exists() and side_render:
        rel = rel + ".essentials-init"
        path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    if entry.copy_src is not None:
        data = (PLUGIN_ROOT / entry.copy_src).read_bytes()
    else:
        data = (entry.content or "").encode("utf-8")
    path.write_bytes(data)
    os.chmod(path, 0o755 if entry.mode == "755" else 0o644)
    return rel


def write_project(entries: list[Entry], out: Path, workspace_out: Path | None, preserve: list[str]) -> None:
    keep = {p.strip("/") for p in preserve}
    _prepare_target(out, keep, strict=True)
    if workspace_out is not None:
        _prepare_target(workspace_out, set(), strict=False)
    out.mkdir(parents=True, exist_ok=True)
    for e in entries:
        if e.workspace:
            if workspace_out is not None:
                _write(workspace_out, e.path, e, side_render=True)
            continue
        preserved = e.path in keep and (out / e.path).exists()
        _write(out, e.path, e, side_render=preserved)


# --------------------------------------------------------------------------------------------
# Static invariants
# --------------------------------------------------------------------------------------------

def _strip_jsonc(text: str) -> str:
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            i = text.find("\n", i) if "\n" in text[i:] else n
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end == -1 else end + 2
        else:
            out.append(c)
            i += 1
    return re.sub(r",(\s*[}\]])", r"\1", "".join(out))


def _path_tokens(region: str):
    """Candidate paths of a PATHS region: backticked tokens outside fences, first tokens inside."""
    in_fence = False
    stack: list[tuple[int, str | None]] = []  # (indent, full path or None when skipped)
    for line in region.split("\n"):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            stack = []
            continue
        if in_fence:
            if not line.strip():
                continue
            indent = len(line) - len(line.lstrip())
            token = line.split()[0]
            if token.startswith("#"):
                continue
            while stack and stack[-1][0] >= indent:
                stack.pop()
            parent = stack[-1][1] if stack else ""
            full = None if parent is None or _skip_token(token) else parent + token
            if token.endswith("/"):
                stack.append((indent, full))
            if full is not None:
                yield full
        else:
            for token in re.findall(r"`([^`\n]+)`", line):
                if _skip_token(token):
                    continue
                name = token.rstrip("/").rsplit("/", 1)[-1]
                if "/" in token or name.rsplit(".", 1)[-1] in PATH_EXTENSIONS and "." in name:
                    yield token


def _skip_token(token: str) -> bool:
    return any(c in token for c in "<*… {}$") or "://" in token or token.startswith(("-", "../"))


def _normalize_path(token: str) -> str:
    token = re.sub(r"(/)?\.\.\.$", "", token)
    token = token.removeprefix("./")
    return token.rstrip("/")


def _existing_paths(entries: list[Entry], generated: list[str], artifact_id: str):
    project, workspace = set(), set()
    for e in entries:
        target = workspace if e.workspace else project
        parts = e.path.split("/")
        for i in range(1, len(parts) + 1):
            target.add("/".join(parts[:i]))
    for g in generated:
        g = g.strip("/")
        parts = g.split("/")
        for i in range(1, len(parts) + 1):
            project.add("/".join(parts[:i]))
    workspace |= {artifact_id} | {f"{artifact_id}/{p}" for p in project}
    return project, workspace


def check_invariants(entries: list[Entry], answers: dict, generated: list[str]) -> list[str]:
    problems = []
    project_paths, workspace_paths = _existing_paths(entries, generated, answers["artifactId"])
    java = answers["language"] == "java"
    for e in entries:
        if e.copy_src is not None:
            continue
        text, name = e.content or "", e.dst
        leftover = LEFTOVER_PLACEHOLDER.search(text)
        if leftover:
            problems.append(f"{name}: unsubstituted {leftover.group(0)}")
        if "__PACKAGE__" in text or "__PACKAGE__" in name:
            problems.append(f"{name}: leftover __PACKAGE__")
        if LEFTOVER_DIRECTIVE.search(text):
            problems.append(f"{name}: leftover directive")
        if text and not text.endswith("\n"):
            problems.append(f"{name}: does not end with a newline")
        suffix = name.rsplit(".", 1)[-1] if "." in name.rsplit("/", 1)[-1] else ""
        if suffix == "xml":
            try:
                ET.fromstring(text)
            except ET.ParseError as exc:
                problems.append(f"{name}: XML does not parse: {exc}")
        elif suffix == "json":
            base = name.rsplit("/", 1)[-1]
            body = _strip_jsonc(text) if base.startswith("tsconfig") else text
            try:
                json.loads(body)
            except json.JSONDecodeError as exc:
                problems.append(f"{name}: JSON does not parse: {exc}")
        elif suffix in ("yml", "yaml"):
            for n, line in enumerate(text.split("\n"), 1):
                if re.match(r"\s*\t", line):
                    problems.append(f"{name}:{n}: tab in YAML indentation")
                    break
        other_lang = ("kotlin", "kt") if java else ("java", "java")
        if re.search(rf"src/(main|test)/{other_lang[0]}\b", text) or e.path.endswith("." + other_lang[1]):
            problems.append(f"{name}: {answers['language']} render names src/…/{other_lang[0]} or a .{other_lang[1]} file")
        source_file = re.search(rf"\b[A-Z]\w*\.{other_lang[1]}\b", text)
        if source_file and not e.path.endswith("pom.xml"):
            problems.append(f"{name}: {answers['language']} render mentions {source_file.group(0)}")
        existing = workspace_paths if e.workspace else project_paths
        for region in e.regions:
            for token in _path_tokens(region):
                path = _normalize_path(token)
                if path and path not in existing:
                    problems.append(f"{name}: layout path `{token}` does not exist in the render")
    return problems


def all_combinations():
    base = {"projectName": "Combination App", "groupId": "com.example.combination", "artifactId": "combination-app"}
    gates = ENUMS["lintGate"]
    axes = [ENUMS[k] for k in ("language", "db", "web", "frontend", "compose")]
    for i, combo in enumerate(itertools.product(*axes)):
        answers: dict[str, str | bool] = dict(zip(("language", "db", "web", "frontend", "compose"), combo))
        answers["lintGate"] = gates[i % len(gates)]
        yield "-".join(str(v).lower() for v in combo), {**base, **answers}


# --------------------------------------------------------------------------------------------
# Cells and hosts
# --------------------------------------------------------------------------------------------

def load_answer_sets(path: Path, key: str) -> dict[str, dict]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RenderError(f"cannot read {path}: {exc}")
    defaults = data.get("defaults", {})
    sets = data.get(key)
    if not isinstance(sets, dict) or not sets:
        raise RenderError(f"{path}: '{key}' must be a non-empty object")
    return {name: {**defaults, **answers} for name, answers in sets.items()}


# --------------------------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------------------------

def stack_lint(entries: list[Entry], answers: dict, pins: Path, contract: Path) -> list[str]:
    """Findings of scripts/stack-lint.py on the rendered project, as problem lines."""
    import subprocess
    with tempfile.TemporaryDirectory() as tmp:
        out = Path(tmp) / "project"
        write_project(entries, out, None, [])
        cmd = [sys.executable, str(STACK_LINT), str(out), "--json", "--pins", str(pins), "--contract", str(contract),
               "--language", answers["language"], "--db", answers["db"], "--web", answers["web"],
               "--frontend", answers["frontend"]]
        run = subprocess.run(cmd, capture_output=True, text=True)
    if run.returncode == 2:
        return [f"stack-lint could not run: {run.stderr.strip() or run.stdout.strip()}"]
    try:
        report = json.loads(run.stdout)
    except json.JSONDecodeError:
        return [f"stack-lint printed no JSON (exit {run.returncode})"]
    return [f"{f['id']} {f['check']} ({f['severity']}) {f.get('file')}:{f.get('line')}: {f['message']}"
            for f in report.get("findings", [])]


def cmd_all_combinations(src: Sources, golden: Path, pins: Path, contract: Path, lint: bool) -> int:
    problems = []
    lint = lint and STACK_LINT.is_file()
    sets = list(all_combinations())
    for file_name, key in (("cells.json", "cells"), ("hosts.json", "hosts")):
        if (golden / file_name).exists():
            sets += [(f"{key}:{k}", v) for k, v in load_answer_sets(golden / file_name, key).items()]
    for name, answers in sets:
        try:
            entries = render_project(answers, src)
        except RenderError as exc:
            problems.append(f"[{name}] {exc}")
            continue
        normalized = normalize_answers(answers)
        problems += [f"[{name}] {p}" for p in check_invariants(entries, normalized, src.manifest.generated)]
        if lint:
            problems += [f"[{name}] {p}" for p in stack_lint(entries, normalized, pins, contract)]
    for p in problems:
        print(p)
    print(f"init-render: {len(sets)} answer sets rendered, {len(problems)} problem(s)"
          f"{'' if lint else ' (stack-lint not run)'}")
    return 1 if problems else 0


def _golden_trees(src: Sources, golden: Path) -> dict[str, str]:
    cells = load_answer_sets(golden / "cells.json", "cells")
    return {f"{name}.tree": serialize(render_project(answers, src), name, render_hooks(answers, src))
            for name, answers in cells.items()}


def cmd_check(src: Sources, golden: Path) -> int:
    expected = _golden_trees(src, golden)
    failed = False
    for name, text in expected.items():
        path = golden / name
        if not path.exists():
            print(f"missing golden {path} (run --update-golden)")
            failed = True
            continue
        current = path.read_text(encoding="utf-8")
        if current != text:
            failed = True
            sys.stdout.writelines(difflib.unified_diff(
                current.splitlines(keepends=True), text.splitlines(keepends=True),
                fromfile=f"golden/{name}", tofile=f"rendered/{name}"))
    for stale in sorted(p.name for p in golden.glob("*.tree") if p.name not in expected):
        print(f"stale golden {golden / stale} (no cell in cells.json)")
        failed = True
    print(f"init-render: {len(expected)} golden cell(s) {'DIFFER' if failed else 'match'}")
    return 1 if failed else 0


def cmd_update_golden(src: Sources, golden: Path) -> int:
    expected = _golden_trees(src, golden)
    for name, text in expected.items():
        (golden / name).write_bytes(text.encode("utf-8"))
        print(f"wrote {golden / name}")
    for stale in sorted(p for p in golden.glob("*.tree") if p.name not in expected):
        stale.unlink()
        print(f"removed {stale}")
    return 0


# --------------------------------------------------------------------------------------------
# Self-test
# --------------------------------------------------------------------------------------------

def self_test() -> int:
    import unittest

    pins_md = (
        "| What | Pin | Notes |\n|---|---|---|\n"
        "| `essentials.version` | **9.9.1** | x |\n"
        "| react / react-dom | 11.0.2 | |\n"
        "| Node (via frontend-maven-plugin) | v99.0.1 | |\n"
        "| typescript | ~8.8.8 | |\n"
        "| eslint | 3.3.3 | With `typescript-eslint` 8.x |\n"
        "| PostgreSQL image | `postgres:88.1` | |\n"
    )
    contract_md = (
        "## S2\n| a | b | c |\n|---|---|---|\n| x | `ignored-artifact` | not S2.1 |\n"
        "### S2.1 — What\n\n| Profile | Also declare | What breaks without it |\n|---|---|---|\n"
        "| pg | `org.postgresql:postgresql` (the JDBC driver) | No `DataSource` -- really |\n"
        "| pg | `jdbi3-core`, `jdbi3-postgres` | `NoClassDefFoundError` |\n"
        "### S3\n| p | `late-artifact` | no |\n"
    )
    answers = {"language": "kotlin", "db": "pg-crud", "web": "webflux", "frontend": "embedded",
               "compose": True, "lintGate": "hook", "projectName": "Demo App",
               "groupId": "com.example.demo", "artifactId": "orders-service"}

    class Case(unittest.TestCase):
        def setUp(self):
            self.tmp = tempfile.TemporaryDirectory()
            self.dir = Path(self.tmp.name)
            (self.dir / "pins.md").write_text(pins_md)
            (self.dir / "contract.md").write_text(contract_md)
            self.pins = load_pins(self.dir / "pins.md")
            self.why = load_why(self.dir / "contract.md")
            self.ctx = Context(build_vars(normalize_answers(answers), self.pins), self.pins, self.why)

        def tearDown(self):
            self.tmp.cleanup()

        def render(self, text):
            return render_text(text, self.ctx, "t")[0]

        def assets(self, manifest, files):
            root = self.dir / "assets"
            root.mkdir(exist_ok=True)
            for name, text in files.items():
                (root / name).parent.mkdir(parents=True, exist_ok=True)
                (root / name).write_text(text)
            (root / "manifest.json").write_text(json.dumps(manifest))
            return Sources(root, self.pins, self.why, load_manifest(root))

        def test_pins(self):
            self.assertEqual(self.pins["essentials.version"], "9.9.1")
            self.assertEqual(self.pins["react"], "11.0.2")
            self.assertEqual(self.pins["react-dom"], "11.0.2")
            self.assertEqual(self.pins["Node"], "v99.0.1")
            self.assertEqual(self.pins["Node (via frontend-maven-plugin)"], "v99.0.1")
            self.assertEqual(self.pins["react / react-dom"], "11.0.2")
            self.assertEqual(self.pins["PostgreSQL image"], "postgres:88.1")
            self.assertEqual(self.pins["typescript"], "~8.8.8")
            self.assertNotIn("What", self.pins)

        def test_why(self):
            self.assertEqual(self.why["postgresql"], "No DataSource - really")
            self.assertEqual(self.why["org.postgresql:postgresql"], self.why["postgresql"])
            self.assertEqual(self.why["jdbi3-postgres"], "NoClassDefFoundError")
            self.assertNotIn("ignored-artifact", self.why)
            self.assertNotIn("late-artifact", self.why)

        def test_derived(self):
            v = self.ctx.vars
            self.assertEqual(v["packagePath"], "com.example.demo.ordersservice")
            self.assertEqual(v["packageDir"], "com/example/demo/ordersservice")
            self.assertEqual(v["appFile"], "Application.kt")
            self.assertEqual(v["sourceExt"], "kt")
            self.assertEqual(v["stack"], "full-stack")
            self.assertEqual(v["db_label"], "PostgreSQL")
            self.assertEqual(v["essentialsVersion"], "9.9.1")
            self.assertEqual(derive_package("com.example.orders", "orders"), "com.example.orders")
            self.assertEqual(derive_package("com.example", "9-lives"), "com.example.lives")
            with self.assertRaises(RenderError):
                normalize_answers({**answers, "packagePath": "com.example.class"})
            with self.assertRaises(RenderError):
                normalize_answers({**answers, "web": "servlet"})
            with self.assertRaises(RenderError):
                normalize_answers({**answers, "compose": "yes"})
            with self.assertRaises(RenderError):
                normalize_answers({**answers, "extra": 1})

        def test_nested_and_whole_line(self):
            text = ("a\n<!-- IF stack=full-stack -->\nb\n  <!-- IF db=mongo -->\nc\n  <!-- END db -->\n"
                    "<!-- END stack -->\nd\n")
            self.assertEqual(self.render(text), "a\nb\nd\n")

        def test_inline(self):
            self.assertEqual(self.render("x <!-- IF language=kotlin -->K<!-- END language -->"
                                         "<!-- IF language=java -->J<!-- END language --> y\n"), "x K y\n")

        def test_alternatives_and_negation(self):
            self.assertEqual(self.render("<!-- IF db=pg-crud|pg-event-sourced -->pg<!-- END db -->"), "pg")
            self.assertEqual(self.render("<!-- IF db!=mongo -->pg<!-- END db -->"), "pg")
            self.assertEqual(self.render("<!-- IF db!=pg-crud|mongo -->x<!-- END db -->"), "")
            self.assertEqual(self.render("<!-- IF compose -->c<!-- END compose -->"), "c")
            self.assertEqual(self.render("<!-- IF compose=false -->c<!-- END compose -->"), "")

        def test_errors(self):
            for bad in ("<!-- IF db=postgres -->x<!-- END db -->",   # value not in the enum
                        "<!-- IF colour=red -->x<!-- END colour -->",  # unknown variable
                        "<!-- IF db -->x<!-- END db -->",              # bare IF on a non-boolean
                        "<!-- IF db=mongo -->x<!-- END web -->",       # mismatched END
                        "<!-- IF db=mongo -->x",                       # unclosed
                        "<!-- END db -->",                             # END without IF
                        "<!-- IF db=mongo x -->y",                     # malformed
                        "<!-- IF db=mongo -->{{nope}}<!-- END db -->",  # unknown placeholder, dead branch
                        "{{pin:nope}}", "{{why:S2.1:nope}}", "{{why:S3:postgresql}}",
                        "{{ pin:react}}", "{{pin:Node (via something else)}}"):
                with self.assertRaises(RenderError, msg=bad):
                    self.render(bad)

        def test_placeholders(self):
            self.assertEqual(self.render("{{pin:react}} {{groupId}} {{why:S2.1:jdbi3-core}} {{compose}}"),
                             "11.0.2 com.example.demo NoClassDefFoundError true")
            jsx = "<div style={{ color: 'red' }} />\n"
            self.assertEqual(self.render(jsx), jsx)

        def test_paths_region(self):
            text, regions = render_text(
                "<!-- PATHS -->\n`backend/pom.xml`\n<!-- IF db=mongo -->\n`gone.md`\n<!-- END db -->\n"
                "<!-- END PATHS -->\nafter\n", self.ctx, "t")
            self.assertEqual(text, "`backend/pom.xml`\nafter\n")
            self.assertEqual(regions, ["`backend/pom.xml`\n"])

        def test_path_tokens(self):
            region = ("- `backend/src/main/kotlin/...` and `contracts/openapi.json` and `<bc>/x` and "
                      "`dk.trustworks.essentials.*` and `mvn verify`\n"
                      "```\npom.xml   # parent\nbackend/\n  src/\n    Application.kt\n  <bc>/\n    x/\n"
                      "frontend/\n```\n")
            self.assertEqual(list(_path_tokens(region)), [
                "backend/src/main/kotlin/...", "contracts/openapi.json", "pom.xml", "backend/",
                "backend/src/", "backend/src/Application.kt", "frontend/"])

        def test_manifest_render_and_tree(self):
            src = self.assets(
                {"files": [
                    {"src": "App.kt.template", "dst": "backend/src/main/{{sourceLang}}/{{packageDir}}/{{appFile}}",
                     "when": {"language": "kotlin"}},
                    {"src": "App.java.template", "dst": "backend/src/main/java/__PACKAGE__/{{appFile}}",
                     "when": {"language": "java"}},
                    {"src": "fe.txt", "dst": "frontend/fe.txt", "when": {"frontend": ["embedded", "standalone"]}},
                    {"src": "raw.tsx", "dst": "frontend/raw.tsx", "render": False},
                    {"src": "dev.sh", "dst": "dev.sh"},
                    {"src": "noeol.txt", "dst": "noeol.txt"},
                    {"src": "WS.md", "dst": "CLAUDE.md", "root": "workspace"},
                    {"copy": "scripts/init-render.py", "dst": "scripts/tool.py", "mode": "755"}],
                 "hooks": [{"id": "wrapper", "run": "mvn -Dv={{pin:react / react-dom}}"},
                           {"id": "npm", "cwd": "frontend", "run": "npm i", "when": {"stack": "full-stack"}}]},
                {"App.kt.template": "package {{packagePath}}\n", "App.java.template": "package {{packagePath}};\n",
                 "fe.txt": "fe\n", "raw.tsx": "{{notAPlaceholderHere}}\n", "dev.sh": "#!/bin/sh\n",
                 "noeol.txt": "x", "WS.md": "<!-- PATHS -->\n`{{artifactId}}/dev.sh`\n<!-- END PATHS -->\n"})
            entries = render_project(answers, src)
            dsts = [e.dst for e in entries]
            self.assertEqual(dsts, [
                "backend/src/main/kotlin/com/example/demo/ordersservice/Application.kt", "dev.sh",
                "frontend/fe.txt", "frontend/raw.tsx", "noeol.txt", "scripts/tool.py", "@workspace/CLAUDE.md"])
            tree = serialize(entries, "demo")
            self.assertIn("=== FILE dev.sh (755) ===\n#!/bin/sh\n", tree)
            self.assertIn("=== COPY scripts/tool.py <- scripts/init-render.py (755) ===\n", tree)
            hooks = render_hooks(answers, src)
            self.assertEqual([(h["id"], h["cwd"], h["run"]) for h in hooks],
                             [("wrapper", ".", "mvn -Dv=11.0.2"), ("npm", "frontend", "npm i")])
            self.assertTrue(serialize(entries, "demo", hooks).endswith(
                "=== HOOK wrapper (cwd .) ===\nmvn -Dv=11.0.2\n=== HOOK npm (cwd frontend) ===\nnpm i\n"))
            self.assertEqual([h["id"] for h in render_hooks({**answers, "frontend": "none"}, src)],
                             ["wrapper"])
            self.assertIn("=== FILE noeol.txt (644) ===\nx\n" + NO_EOL, tree)
            self.assertIn("{{notAPlaceholderHere}}", tree)
            problems = check_invariants(entries, normalize_answers(answers), [])
            self.assertEqual([p for p in problems if "layout" in p], [])
            self.assertTrue(any("noeol.txt: does not end" in p for p in problems))
            self.assertTrue(any("raw.tsx: unsubstituted" in p for p in problems))
            java = render_project({**answers, "language": "java", "frontend": "none"}, src)
            self.assertIn("backend/src/main/java/com/example/demo/ordersservice/Application.java",
                          [e.dst for e in java])
            self.assertNotIn("frontend/fe.txt", [e.dst for e in java])

        def test_manifest_errors(self):
            with self.assertRaises(RenderError):   # file not named by any entry
                self.assets({"files": [{"src": "a", "dst": "a"}]}, {"a": "", "b": ""})
            with self.assertRaises(RenderError):   # bad when value
                self.assets({"files": [{"src": "a", "dst": "a", "when": {"db": "postgres"}}]}, {"a": ""})
            with self.assertRaises(RenderError):   # both src and copy
                self.assets({"files": [{"src": "a", "copy": "scripts/init-render.py", "dst": "a"}]}, {"a": ""})
            src = self.assets({"files": [{"src": "a", "dst": "x"}, {"src": "b", "dst": "x"}]}, {"a": "", "b": ""})
            with self.assertRaises(RenderError):   # dst collision
                render_project(answers, src)

        def test_invariants(self):
            def entry(dst, content, regions=()):
                return Entry(dst, "644", content, regions=list(regions))
            entries = [entry("pom.xml", "<project>\n"), entry("a.json", "{\n"),
                       entry("tsconfig.json", '{"a": 1, // c\n "b": "//x", /* d */}\n'),
                       entry("x.yml", "a:\n\tb: 1\n"),
                       entry("CLAUDE.md", "see Missing.kt\n", ["`pom.xml` `backend/missing.kt` `src/shared/api/generated/`\n"])]
            problems = check_invariants(entries, normalize_answers({**answers, "language": "java"}),
                                        ["src/shared/api/generated/"])
            text = "\n".join(problems)
            self.assertIn("pom.xml: XML does not parse", text)
            self.assertIn("a.json: JSON does not parse", text)
            self.assertNotIn("tsconfig.json", text)
            self.assertIn("x.yml:2: tab", text)
            self.assertIn("`backend/missing.kt` does not exist", text)
            self.assertIn("mentions Missing.kt", text)
            self.assertNotIn("generated", text)

        def test_write_rules(self):
            src = self.assets({"files": [{"src": "README.md", "dst": "README.md"},
                                         {"src": "WS.md", "dst": "CLAUDE.md", "root": "workspace"}]},
                              {"README.md": "r\n", "WS.md": "w\n"})
            entries = render_project(answers, src)
            out = self.dir / "ws" / "proj"
            out.mkdir(parents=True)
            (out / ".git").mkdir()
            (out / "README.md").write_text("mine\n")
            (self.dir / "ws" / "CLAUDE.md").write_text("theirs\n")
            with self.assertRaises(RenderError):
                write_project(entries, out, None, [])
            write_project(entries, out, self.dir / "ws", ["README.md"])
            self.assertEqual((out / "README.md").read_text(), "mine\n")
            self.assertEqual((out / "README.md.essentials-init").read_text(), "r\n")
            self.assertEqual((self.dir / "ws" / "CLAUDE.md.essentials-init").read_text(), "w\n")

        def test_combinations(self):
            combos = list(all_combinations())
            self.assertEqual(len(combos), 72)
            self.assertEqual({c[1]["lintGate"] for c in combos}, set(ENUMS["lintGate"]))

    suite = unittest.defaultTestLoader.loadTestsFromTestCase(Case)
    result = unittest.TextTestRunner(stream=sys.stdout, verbosity=1).run(suite)
    return 0 if result.wasSuccessful() else 1


# --------------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------------

def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Render an Essentials project from the plugin templates.")
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--answers", type=Path, help="answers JSON")
    mode.add_argument("--host", help="slice-compile host id from hosts.json, e.g. java-pg-event-sourced")
    mode.add_argument("--self-test", action="store_true")
    mode.add_argument("--all-combinations", action="store_true")
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--update-golden", action="store_true")
    mode.add_argument("--list-names", action="store_true",
                      help="print every {{pin:…}} and {{why:S2.1:…}} name the sources provide")
    ap.add_argument("--out", type=Path, help="target directory")
    ap.add_argument("--tree", action="store_true", help="print the serialized tree instead of writing")
    ap.add_argument("--hooks", action="store_true",
                    help="print the post-render commands for these answers as JSON (the caller runs them)")
    ap.add_argument("--workspace-out", type=Path, help="workspace root for the workspace pointer files")
    ap.add_argument("--preserve", action="append", default=[], help="existing path never overwritten")
    ap.add_argument("--no-stack-lint", action="store_true",
                    help="--all-combinations: skip running scripts/stack-lint.py on each render")
    ap.add_argument("--assets", type=Path, default=DEFAULT_ASSETS)
    ap.add_argument("--pins", type=Path, default=DEFAULT_PINS)
    ap.add_argument("--contract", type=Path, default=DEFAULT_CONTRACT)
    ap.add_argument("--golden", type=Path, default=DEFAULT_GOLDEN)
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()
    try:
        if args.list_names:
            for name, value in sorted(load_pins(args.pins).items()):
                print(f"{{{{pin:{name}}}}}\t{value}")
            for name in sorted(load_why(args.contract)):
                print(f"{{{{why:S2.1:{name}}}}}")
            return 0
        src = load_sources(args.assets, args.pins, args.contract)
        if args.all_combinations:
            return cmd_all_combinations(src, args.golden, args.pins, args.contract, not args.no_stack_lint)
        if args.check:
            return cmd_check(src, args.golden)
        if args.update_golden:
            return cmd_update_golden(src, args.golden)
        if args.host:
            hosts = load_answer_sets(args.golden / "hosts.json", "hosts")
            if args.host not in hosts:
                raise RenderError(f"unknown host {args.host!r}; hosts: {', '.join(sorted(hosts))}")
            answers, title = hosts[args.host], args.host
        else:
            try:
                answers = json.loads(args.answers.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError) as exc:
                raise RenderError(f"cannot read answers {args.answers}: {exc}")
            title = args.answers.name
        if args.hooks:
            json.dump(render_hooks(answers, src), sys.stdout, indent=2)
            print()
            return 0
        entries = render_project(answers, src)
        if args.tree:
            sys.stdout.write(serialize(entries, title, render_hooks(answers, src)))
            return 0
        if args.out is None:
            raise RenderError("--out DIR (or --tree) is required")
        write_project(entries, args.out, args.workspace_out, args.preserve)
        print(args.out)
        return 0
    except RenderError as exc:
        print(f"init-render: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
