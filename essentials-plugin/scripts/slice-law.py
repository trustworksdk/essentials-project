#!/usr/bin/env python3
"""slice-law — print the part of the slice law that applies to one lane, one slice kind and one project.

Why this exists
---------------
`rules/slice-design.md` is one file, cited by section name everywhere, and it covers three write
styles (§R5), four slice kinds and, cutting across both, the Spring Data repository surface. A skill scaffolding a decider-lane command slice needs well under
half of it, yet reading the file costs all of it, every time. Splitting the file would break every
citation of a moved section, including the ones rendered into user projects. So the file stays
whole and scopes its own sections instead: a `<!-- slice-law: lane=… kind=… -->` line right under a
heading limits that section, and every section under it, to the lanes and kinds it names. This
script prints the sections that apply, verbatim and in file order, and lists by name the ones it
left out, so a reader still knows they exist and can ask for one.

Selecting sections is substitution, not judgement, which is why it is a script: the model never
decides which part of the law applies to it.

Rules of the scope lines
------------------------
- `lane` values: decider, aggregate, service-entity. `kind` values: command, view, automation,
  translation. `store` values: spring-data, other — whether the project persists through Spring
  Data repositories, which is a property of the project, not of its lane. A dimension a scope line
  does not name is unrestricted.
- A scope line sits on the line directly under its heading (`##` or `###`), at most one per heading.
- A subsection inherits its parent's scope and may only narrow it: every value it names must be
  allowed by the parent. Otherwise a subsection could be printed under a parent that was not.
- A section is printed when, for each dimension, the requested values (all of them, when that
  dimension is not requested) and the section's allowed values overlap.

Usage
-----
    slice-law.py [--lane L ...] [--kind K ...] [--project DIR | --store S]
                                                  the law for that lane, kind and project
    slice-law.py --section NAME [--section ...]   those sections only, whatever their scope
    slice-law.py --outline [--lane L] [--kind K]  what would be printed: names, scopes and bytes
    slice-law.py --check                          scope lines valid, every view within budget

--project DIR decides `store` the way a reader would, but deterministically: spring-data when a
Java or Kotlin source under DIR imports a Spring Data repository package, or a build file declares
a Spring Data starter or artifact; other when neither does. With neither --project nor --store,
every store is printed.

Without --lane, --project also decides which lanes to print: every directory holding a slice
directory (`use_cases/`, `views/`, `automations/`, `external_systems/`) is a bounded context, on the
aggregate lane when it has `aggregates/`, the service-entity lane when it has `entities/`, the
decider lane otherwise; a project with no bounded context yet gets every lane. That is a choice of
what to print, never a lane verdict — a BC showing two lanes prints both here and is reported by
`slice-source.py` (`bcs[].lane`) and `/essentials:slice-check`.

NAME is a heading or its short name (the part before ` — ` or `: `), so `--section R5` and
`--section "Service-entity style"` both work. The printed text drops the scope lines and keeps
everything else byte for byte.

Exit codes
----------
    0   printed, or --check found nothing
    1   --check found a problem
    2   could not run: bad arguments, an unknown section name, an unreadable law file, or (outside
        --check) invalid scope lines

Standard library only.
"""

from __future__ import annotations

import argparse
import itertools
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path

LAW = Path(__file__).resolve().parent.parent / "rules" / "slice-design.md"
DIMENSIONS = {
    "lane": ("decider", "aggregate", "service-entity"),
    "kind": ("command", "view", "automation", "translation"),
    "store": ("spring-data", "other"),
}
# Ceilings in bytes. BUDGET_VIEW holds every single lane x kind x store view, BUDGET_FILE the whole file, which
# slice-check and slice-discover still read. They make growth a decision: raise one deliberately,
# in the commit that needs it, never to turn a red check green.
BUDGET_VIEW = 52_000
BUDGET_FILE = 66_000

HEADING = re.compile(r"^(#{2,3})\s+(.+?)\s*$")
SCOPE = re.compile(r"^<!--\s*slice-law:\s*(.*?)\s*-->\s*$")
FENCE = re.compile(r"^\s*(```|~~~)")
SPRING_DATA_SOURCE = re.compile(
    r"^\s*import\s+org\.springframework\.data\.(?:repository|jpa\.repository|mongodb\.repository)\b",
    re.MULTILINE)
SPRING_DATA_BUILD = re.compile(
    r"spring-boot-starter-data-(?:jpa|mongodb|jdbc|r2dbc|mongodb-reactive)\b"
    r"|<groupId>\s*org\.springframework\.data\s*</groupId>|[\"']org\.springframework\.data:")
BUILD_FILES = {"pom.xml", "build.gradle", "build.gradle.kts"}
SKIP_DIRS = {".git", "target", "build", "out", "node_modules", ".gradle", ".idea", "dist", ".claude"}


class UsageError(Exception):
    pass


@dataclass(eq=False)
class Section:
    level: int
    title: str
    line: int  # 1-based line of the heading
    text: list[str] = field(default_factory=list)  # heading and body, scope line removed
    own: dict[str, set[str]] = field(default_factory=dict)
    scope: dict[str, set[str]] = field(default_factory=dict)  # own, narrowed by the parent's
    parent: Section | None = None

    @property
    def names(self) -> set[str]:
        plain = plain_text(self.title)
        return {plain, re.split(r" — |: ", plain, maxsplit=1)[0]}

    def scope_label(self) -> str:
        return " ".join(f"{d}={','.join(v for v in DIMENSIONS[d] if v in self.own[d])}"
                        for d in DIMENSIONS if d in self.own)

    def size(self) -> int:
        return sum(len(line.encode("utf-8")) + 1 for line in self.text)


def plain_text(text: str) -> str:
    return re.sub(r"\s+", " ", text.replace("`", "").replace("*", "")).strip()


def size_of(lines: list[str]) -> int:
    return sum(len(line.encode("utf-8")) + 1 for line in lines)


def parse_scope(spec: str, where: str, problems: list[str]) -> dict[str, set[str]]:
    scope: dict[str, set[str]] = {}
    for part in spec.split():
        name, _, values = part.partition("=")
        if name not in DIMENSIONS:
            problems.append(f"{where}: unknown dimension {name!r} (known: {', '.join(DIMENSIONS)})")
            continue
        if name in scope:
            problems.append(f"{where}: {name} given twice")
        chosen = {v for v in values.split(",") if v}
        if not chosen or chosen - set(DIMENSIONS[name]):
            problems.append(f"{where}: {name}={values!r} — allowed: {', '.join(DIMENSIONS[name])}")
        scope[name] = chosen & set(DIMENSIONS[name])
    if not scope:
        problems.append(f"{where}: scope line names no dimension")
    return scope


def read_law(path: Path):
    """(preamble lines, sections in file order, problems found while parsing)."""
    try:
        lines = path.read_text(encoding="utf-8").split("\n")
    except OSError as exc:
        raise UsageError(f"cannot read {path}: {exc}")
    if lines and lines[-1] == "":
        lines.pop()
    preamble: list[str] = []
    sections: list[Section] = []
    problems: list[str] = []
    stack: list[Section] = []
    in_fence = False
    for number, line in enumerate(lines, 1):
        if FENCE.match(line):
            in_fence = not in_fence
        heading = None if in_fence else HEADING.match(line)
        scope = None if in_fence else SCOPE.match(line)
        if heading:
            level = len(heading.group(1))
            while stack and stack[-1].level >= level:
                stack.pop()
            section = Section(level, heading.group(2), number, [line],
                              parent=stack[-1] if stack else None)
            sections.append(section)
            stack.append(section)
        elif scope:
            where = f"{path.name}:{number}"
            current = sections[-1] if sections else None
            if current is None or current.line != number - 1:
                problems.append(f"{where}: scope line not directly under a heading")
            elif current.own:
                problems.append(f"{where}: second scope line for one heading")
            else:
                current.own = parse_scope(scope.group(1), where, problems)
        elif sections:
            sections[-1].text.append(line)
        else:
            preamble.append(line)
    if not sections:
        raise UsageError(f"no ##/### headings in {path}")
    for section in sections:
        inherited = section.parent.scope if section.parent else {}
        section.scope = dict(inherited)
        for dim, values in section.own.items():
            if dim in inherited and not values <= inherited[dim]:
                problems.append(f"{path.name}:{section.line}: § {plain_text(section.title)} widens its "
                                f"parent's {dim} scope with {', '.join(sorted(values - inherited[dim]))}")
            section.scope[dim] = values & inherited[dim] if dim in inherited else values
    return preamble, sections, problems


def detect_store(project: Path) -> str:
    """spring-data when the project's sources or build use Spring Data repositories, else other."""
    if not project.is_dir():
        raise UsageError(f"--project: not a directory: {project}")
    for dirpath, dirnames, filenames in os.walk(project):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for name in sorted(filenames):
            if name in BUILD_FILES:
                pattern = SPRING_DATA_BUILD
            elif name.endswith((".java", ".kt")):
                pattern = SPRING_DATA_SOURCE
            else:
                continue
            try:
                text = (Path(dirpath) / name).read_text(encoding="utf-8", errors="replace")
            except OSError:
                continue
            if pattern.search(text):
                return "spring-data"
    return "other"


SLICE_DIRS = {"use_cases", "views", "automations", "external_systems"}


def detect_lanes(project: Path) -> set[str]:
    """The lanes of the project's bounded contexts, by marker directory; every lane when it has none."""
    lanes: set[str] = set()
    for dirpath, dirnames, _ in os.walk(project):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        present = set(dirnames)
        if present & SLICE_DIRS:
            if "aggregates" in present:
                lanes.add("aggregate")
            if "entities" in present:
                lanes.add("service-entity")
            if not present & {"aggregates", "entities"}:
                lanes.add("decider")
    return lanes or set(DIMENSIONS["lane"])


def applies(section: Section, wanted: dict[str, set[str]]) -> bool:
    return all(not (wanted.get(dim) or set(DIMENSIONS[dim])).isdisjoint(values)
               for dim, values in section.scope.items())


def render(preamble: list[str], sections: list[Section], wanted: dict[str, set[str]]):
    """(text, bytes of the law printed, bytes of the whole law)."""
    printed = [s for s in sections if applies(s, wanted)]
    shown = size_of(preamble) + sum(s.size() for s in printed)
    total = size_of(preamble) + sum(s.size() for s in sections)
    label = " ".join(f"{d}={','.join(v for v in DIMENSIONS[d] if v in wanted[d])}"
                     for d in DIMENSIONS if wanted.get(d)) or "every lane and kind"
    out = [f"<!-- rules/slice-design.md for {label}: {shown} of {total} bytes, printed by "
           "scripts/slice-law.py. Section names are the file's own; cite them as they stand. -->",
           *preamble, *itertools.chain.from_iterable(s.text for s in printed)]
    # The outermost sections left out: a left-out parent stands for its subsections.
    left_out = [s for s in sections
                if s not in printed and (s.parent is None or s.parent in printed)]
    if left_out:
        out += ["", "---", "",
                "Not printed, scoped to another lane or kind: "
                + "; ".join(f"§ {plain_text(s.title)} ({s.scope_label()})" for s in left_out)
                + ". If one applies after all — a Spring Data read model on an event-sourced lane, a"
                " second lane in the same change — print it with"
                f" `python3 {Path(__file__).resolve()} --section \"<name>\"`."]
    return "\n".join(out) + "\n", shown, total


def find_sections(sections: list[Section], names: list[str]) -> list[Section]:
    chosen: list[Section] = []
    for name in names:
        hits = [s for s in sections if plain_text(name) in s.names]
        if not hits:
            raise UsageError(f"no section named {name!r} in rules/slice-design.md")
        chosen.extend(h for h in hits if h not in chosen)

    def under_chosen(section: Section | None) -> bool:
        while section is not None:
            if section in chosen:
                return True
            section = section.parent
        return False

    return [s for s in sections if under_chosen(s)]


def check(path: Path, preamble, sections, problems: list[str], out) -> int:
    _, _, total = render(preamble, sections, {})
    if total > BUDGET_FILE:
        problems.append(f"{path.name}: {total} bytes, over BUDGET_FILE ({BUDGET_FILE})")
    largest = 0
    for combination in itertools.product(*DIMENSIONS.values()):
        wanted = {dim: {value} for dim, value in zip(DIMENSIONS, combination)}
        _, shown, _ = render(preamble, sections, wanted)
        largest = max(largest, shown)
        if shown > BUDGET_VIEW:
            label = " ".join(f"{dim}={value}" for dim, value in zip(DIMENSIONS, combination))
            problems.append(f"{label}: {shown} bytes, over BUDGET_VIEW ({BUDGET_VIEW})")
    for problem in problems:
        print(problem, file=out)
    if not problems:
        print(f"ok: {len(sections)} sections, {sum(1 for s in sections if s.own)} scoped; whole file "
              f"{total}/{BUDGET_FILE} bytes, largest single view {largest}/{BUDGET_VIEW}",
              file=out)
    return 1 if problems else 0


def outline(preamble, sections: list[Section], wanted, out) -> None:
    printed = [s for s in sections if applies(s, wanted)]
    for s in sections:
        scope = f"  [{s.scope_label()}]" if s.own else ""
        print(f"{'+' if s in printed else '-'} {s.size():>6}  {'  ' * (s.level - 2)}"
              f"{plain_text(s.title)}{scope}", file=out)
    shown = size_of(preamble) + sum(s.size() for s in printed)
    print(f"= {shown:>6}  printed, preamble ({size_of(preamble)}) included", file=out)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        prog="slice-law",
        description="Print the sections of rules/slice-design.md that apply to a lane and a slice "
        "kind. Deterministic; reads, never writes.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="exit: 0 printed / check clean, 1 check found a problem, 2 usage error",
    )
    for dim, values in DIMENSIONS.items():
        parser.add_argument(f"--{dim}", action="append", choices=values, default=[],
                            help=f"repeatable; omitted means every {dim}")
    parser.add_argument("--project", type=Path, metavar="DIR",
                        help="detect --store, and --lane when not given, from the project at DIR")
    parser.add_argument("--section", action="append", default=[], metavar="NAME",
                        help="print this section and its subsections only (repeatable)")
    parser.add_argument("--outline", action="store_true", help="list sections, scopes and sizes")
    parser.add_argument("--check", action="store_true", help="validate scope lines and budgets")
    parser.add_argument("--law", type=Path, default=LAW, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)

    try:
        preamble, sections, problems = read_law(args.law)
        if args.check:
            return check(args.law, preamble, sections, problems, sys.stdout)
        if problems:
            raise UsageError("invalid scope lines (run --check):\n  " + "\n  ".join(problems))
        wanted = {dim: set(getattr(args, dim)) for dim in DIMENSIONS}
        if args.project is not None:
            if wanted["store"]:
                raise UsageError("--project decides --store; give one or the other")
            wanted["store"] = {detect_store(args.project)}
            if not wanted["lane"]:
                wanted["lane"] = detect_lanes(args.project)
        if args.outline:
            outline(preamble, sections, wanted, sys.stdout)
        elif args.section:
            chosen = find_sections(sections, args.section)
            sys.stdout.write("\n".join(itertools.chain.from_iterable(s.text for s in chosen)) + "\n")
        else:
            sys.stdout.write(render(preamble, sections, wanted)[0])
        return 0
    except UsageError as exc:
        print(f"slice-law: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
