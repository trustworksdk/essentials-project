#!/usr/bin/env python3
"""Map every slice golden composition and every compilable fixture onto a scaffold host.

The slice-compile oracle builds rendered slice code and the Java/Kotlin fixtures inside a real project:
a host rendered by `scripts/init-render.py --host <lang>-<db>` (defined in `tests/golden/init/hosts.json`),
with one or more overlays copied into its `backend/src/` by the repo-root `scripts/plugin-scaffold.sh build-host`.
This script owns only the mapping and the overlay trees; it never renders a host and never runs Maven.

A case is one host build:
  - each composition in `tests/slice-golden/compositions.json`, on the host that composition names, `verify`: the
    host's ApplicationContextIT and OpenApiContractIT start the context with the slices in it, and the slices' own
    tests run. The `*-decider-two-bc` compositions also get the boot overlay (`boot/<lang>/src/`), because the defect
    they guard (a second decider configurator) is runtime-only;
  - the fixtures that are Essentials applications. Only `.java`/`.kt` files are copied. `fixture-worked-example`
    (`{{packagePath}}` rendered to the host's packagePath, plus a test-only adapter for its outbound port from
    `stubs/worked-example/`) runs `verify`; `fixture-multi-lane` and `fixture-aggregate-lane` live outside the host's
    package (`com.acme.multi`, `com.acme.billing`), so no context would load them: `test-compile`.

Every fixture (a directory under `tests/fixtures/` holding a TEST-GUIDE.md) is either a case or listed in
NOT_COMPILED with the reason, so a new fixture forces a decision (`check`).

Usage (stdlib only, Python >= 3.11):
  overlay.py list [--json]              every case: name, host, goal; then the fixtures not compiled, and why
  overlay.py host CASE                  the host id (a key of hosts.json)
  overlay.py goal CASE                  the default Maven goal: test-compile | verify
  overlay.py render CASE --out DIR      write the case's overlays under DIR (created; must be absent or empty),
                                        one directory per overlay, each holding a src/ tree; prints each on a line
  overlay.py check                      the mapping is complete and every source it names exists

Exit codes: 0 ok; 1 `check` found a problem; 2 could not run (unknown case, bad arguments, non-empty --out,
a placeholder left after rendering).
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
PLUGIN = HERE.parent.parent
GOLDEN = PLUGIN / "tests" / "slice-golden"
FIXTURES = PLUGIN / "tests" / "fixtures"
HOSTS = PLUGIN / "tests" / "golden" / "init" / "hosts.json"
BOOT = HERE / "boot"
STUBS = HERE / "stubs"

SOURCE_SUFFIXES = {".java", ".kt"}

# Fixture directories that are deliberately not compiled, and why. `check` fails on a fixture in neither list.
NOT_COMPILED = {
    "service-entity": "\"The entity here is JPA-shaped\" (its TEST-GUIDE.md, 'Mongo is not exercised'): it needs "
                      "spring-boot-starter-data-jpa (its pom.xml), which no scaffold host declares",
    "brownfield-layered": "a plain layered Spring application with no Essentials code; slice-discover's input, not a build",
    "slice-map": "JSON sample data for the slice-map page, no sources",
    "change-router": "prompts and cases for the change-router evals, no application sources",
}


class HarnessError(Exception):
    pass


@dataclass
class Part:
    label: str
    kind: str                     # "tree": copy src/ verbatim | "fixture": copy sources only | "worked": render + relocate
    source: Path
    language: str = ""


@dataclass
class Case:
    name: str
    host: str
    goal: str
    origin: str
    parts: list[Part] = field(default_factory=list)


def load_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as e:
        raise HarnessError(f"cannot read {path}: {e}") from e


def host_package(host: str) -> str:
    hosts = load_json(HOSTS)
    if host not in hosts.get("hosts", {}):
        raise HarnessError(f"host {host!r} is not in {HOSTS}")
    answers = {**hosts.get("defaults", {}), **hosts["hosts"][host]}
    package = answers.get("packagePath")
    if not package:
        raise HarnessError(f"host {host!r} has no explicit packagePath in {HOSTS}")
    return package


def cases() -> dict[str, Case]:
    result: dict[str, Case] = {}
    compositions = load_json(GOLDEN / "compositions.json").get("compositions", {})
    for name, spec in compositions.items():
        language = spec.get("language", "")
        case = Case(name, spec.get("host", ""), "verify", f"tests/slice-golden/{name}",
                    [Part("composition", "tree", GOLDEN / name / "src")])
        if name.endswith("-decider-two-bc"):
            case.parts.append(Part("boot", "tree", BOOT / language / "src"))
        result[name] = case
    result["fixture-multi-lane"] = Case(
        "fixture-multi-lane", "java-pg-event-sourced", "test-compile", "tests/fixtures/multi-lane",
        [Part("fixture", "fixture", FIXTURES / "multi-lane" / "src")])
    result["fixture-aggregate-lane"] = Case(
        "fixture-aggregate-lane", "java-pg-event-sourced", "test-compile", "tests/fixtures/aggregate-lane",
        [Part("fixture", "fixture", FIXTURES / "aggregate-lane" / "src")])
    result["fixture-worked-example"] = Case(
        "fixture-worked-example", "kotlin-pg-event-sourced", "verify", "tests/fixtures/worked-example",
        [Part("fixture", "worked", FIXTURES / "worked-example", "kotlin"),
         Part("stubs", "tree", STUBS / "worked-example" / "src")])
    return result


def find_case(name: str) -> Case:
    all_cases = cases()
    if name not in all_cases:
        raise HarnessError(f"unknown case {name!r}; known: {', '.join(all_cases)}")
    return all_cases[name]


def copy_tree(src: Path, dst: Path) -> int:
    if not src.is_dir():
        raise HarnessError(f"overlay source {src} does not exist")
    shutil.copytree(src, dst)
    return sum(1 for p in dst.rglob("*") if p.is_file())


def copy_sources(src: Path, dst: Path) -> int:
    if not src.is_dir():
        raise HarnessError(f"fixture source {src} does not exist")
    count = 0
    for path in sorted(src.rglob("*")):
        if path.is_file() and path.suffix in SOURCE_SUFFIXES:
            target = dst / path.relative_to(src)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, target)
            count += 1
    return count


def render_worked_example(src: Path, dst: Path, package: str) -> int:
    """The worked example ships `orders/` and `DeciderWiring.kt` under `{{packagePath}}`, with no src/ prefix."""
    root = dst / "main" / "kotlin" / package.replace(".", "/")
    sources = [p for p in sorted((src / "orders").rglob("*")) if p.is_file() and p.suffix in SOURCE_SUFFIXES]
    wiring = src / "DeciderWiring.kt"
    if not sources or not wiring.is_file():
        raise HarnessError(f"{src} has no orders/ sources or no DeciderWiring.kt")
    for path in [*sources, wiring]:
        text = path.read_text(encoding="utf-8").replace("{{packagePath}}", package)
        if "{{" in text:
            raise HarnessError(f"{path}: a placeholder other than {{{{packagePath}}}} is left after rendering")
        target = root / path.relative_to(src)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    return len(sources) + 1


def render(case: Case, out: Path) -> list[Path]:
    if out.exists() and any(out.iterdir()):
        raise HarnessError(f"--out {out} is not empty")
    out.mkdir(parents=True, exist_ok=True)
    written = []
    for index, part in enumerate(case.parts, start=1):
        overlay = out / f"{index}-{part.label}"
        if part.kind == "tree":
            copy_tree(part.source, overlay / "src")
        elif part.kind == "fixture":
            copy_sources(part.source, overlay / "src")
        else:
            render_worked_example(part.source, overlay / "src", host_package(case.host))
        written.append(overlay)
    return written


def check() -> list[str]:
    problems = []
    hosts = load_json(HOSTS).get("hosts", {})
    all_cases = cases()
    for case in all_cases.values():
        if case.host not in hosts:
            problems.append(f"{case.name}: host {case.host!r} is not in hosts.json")
        for part in case.parts:
            if not part.source.is_dir():
                problems.append(f"{case.name}: {part.source.relative_to(PLUGIN)} does not exist")
    covered = {c.origin.removeprefix("tests/fixtures/") for c in all_cases.values() if c.origin.startswith("tests/fixtures/")}
    # A fixture is a directory with a TEST-GUIDE.md; anything else there (__pycache__, editor dirs) is not one.
    for fixture in sorted(p.name for p in FIXTURES.iterdir() if (p / "TEST-GUIDE.md").is_file()):
        if fixture not in covered and fixture not in NOT_COMPILED:
            problems.append(f"tests/fixtures/{fixture}: neither a case nor in NOT_COMPILED (overlay.py)")
    return problems


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(prog="overlay.py", description=(__doc__ or "").split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    list_parser = sub.add_parser("list")
    list_parser.add_argument("--json", action="store_true")
    for name in ("host", "goal"):
        sub.add_parser(name).add_argument("case")
    render_parser = sub.add_parser("render")
    render_parser.add_argument("case")
    render_parser.add_argument("--out", required=True, type=Path)
    sub.add_parser("check")
    args = parser.parse_args(argv)

    try:
        if args.command == "list":
            all_cases = cases()
            if args.json:
                print(json.dumps({
                    "cases": [{"name": c.name, "host": c.host, "goal": c.goal, "origin": c.origin,
                               "overlays": [p.label for p in c.parts]} for c in all_cases.values()],
                    "notCompiled": [{"fixture": k, "why": v} for k, v in NOT_COMPILED.items()],
                }, indent=2))
            else:
                for c in all_cases.values():
                    print(f"{c.name} {c.host} {c.goal}")
                for k, v in NOT_COMPILED.items():
                    print(f"# not compiled: tests/fixtures/{k} — {v}", file=sys.stderr)
            return 0
        if args.command == "host":
            print(find_case(args.case).host)
            return 0
        if args.command == "goal":
            print(find_case(args.case).goal)
            return 0
        if args.command == "render":
            for overlay in render(find_case(args.case), args.out.resolve()):
                print(overlay)
            return 0
        problems = check()
        for problem in problems:
            print(problem)
        if not problems:
            print(f"{len(cases())} case(s), {len(NOT_COMPILED)} fixture(s) not compiled: mapping complete")
        return 1 if problems else 0
    except HarnessError as e:
        print(f"overlay.py: {e}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
