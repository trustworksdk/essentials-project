#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["pyyaml==6.0.3", "jsonschema==4.26.0"]
# ///
"""slice-lint — deterministic validation of every slice.yaml in a project.

Why this exists
---------------
Nothing in a Maven or Gradle build reads `slice.yaml`. A manifest that is not valid
YAML therefore compiles, tests green, and ships — and the slice silently drops out of
every tool that reads manifests. Invisible reads as compliant. This script is the gate
that makes it loud.

It is deterministic by design: parsing and schema validation are mechanical, and a
language model has no business guessing at either. `/essentials:slice-check` runs this
script for its gates 1, 3 and 4, and for the one manifest-only clause of gate 14 (a
write style written into `tier`), rather than eyeballing the files. It reads manifests only;
the syntactic source facts are `slice-source.py`'s.

Usage
-----
    slice-lint.py [ROOT] [options]

    ROOT                 directory to scan (default: the current directory)
    --schema PATH        JSON Schema to validate against (default, resolved relative
                         to this script: ../references/slice/slice-yaml.schema.json in
                         the plugin, else slice-yaml.schema.json beside it, which is
                         where a project's installed copy keeps it)
    --require-schema     fail if schema validation could not run (use this in CI)
    --json               emit findings as JSON on stdout; each finding carries its
                         `ESS-G<gate><clause>` id beside the gate label
    --quiet              print only findings, no summary

Exit codes
----------
    0   no findings
    1   findings (Blocking or Should-fix)
    2   could not run at all — bad arguments, unreadable schema, no YAML parser

Dependencies
------------
`pyyaml` is required for parsing; `jsonschema` is required for schema validation. Both are
pinned in the inline script metadata above, so `uv run --script slice-lint.py` brings them
(this works from a project's installed copy too). When
`jsonschema` is absent the parse gate still runs and the script says loudly that
validation was skipped — silence is never allowed to read as a pass. When `pyyaml` is
absent nothing can be parsed, so the script falls back to scanning for the known-lethal
authoring mistakes it can find with a regex and exits 2.

    uv run --script slice-lint.py …        (or: pip install pyyaml jsonschema)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from pathlib import Path

# The one authoring mistake that costs a whole slice: inside a YAML flow mapping an
# unquoted scalar containing '{' opens a nested mapping, and the parse dies at the brace.
# Every REST path variable produces that brace. See manifest-guide.md §3.
UNQUOTED_BRACED_PATH = re.compile(r"""^\s*-?\s*.*?\bpath:\s*(?!["'])[^"'\n]*\{""")

SEVERITY_ORDER = {"Blocking": 0, "Should-fix": 1, "Advisory": 2}

# The write styles of §R5. They belong in `lane`; in `tier` they are an unrecognised value, which
# a reader must treat as `custom` — silently dropping the slice's tier-specific handling.
LANE_ONLY_TIERS = {"aggregate": "cqrs-es", "decider": "cqrs-es"}

GATE_ID = re.compile(r"^(\d+)(?:\(([a-z])\))?")


def finding_id(gate):
    """`4(b) sole owner` → `ESS-G4b`; `14 tier` → `ESS-G14` (the review finding ids)."""
    m = GATE_ID.match(gate)
    return f"ESS-G{m.group(1)}{m.group(2) or ''}" if m else None


class Finding:
    __slots__ = ("severity", "gate", "path", "line", "message", "hint")

    def __init__(self, severity, gate, path, message, line=None, hint=None):
        self.severity = severity
        self.gate = gate
        self.path = path
        self.line = line
        self.message = message
        self.hint = hint

    @property
    def location(self):
        return f"{self.path}:{self.line}" if self.line else str(self.path)

    def as_dict(self):
        return {
            "severity": self.severity,
            "gate": self.gate,
            "id": finding_id(self.gate),
            "file": str(self.path),
            "line": self.line,
            "message": self.message,
            "hint": self.hint,
        }

    def sort_key(self):
        return (SEVERITY_ORDER.get(self.severity, 9), str(self.path), self.line or 0)


def find_manifests(root: Path):
    """Every slice.yaml under root, skipping the usual build and VCS noise."""
    skip = {".git", "target", "build", "out", "node_modules", ".idea", ".gradle", "dist"}
    found = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in skip]
        if "slice.yaml" in filenames:
            found.append(Path(dirpath) / "slice.yaml")
    return sorted(found)


def scan_unquoted_paths(text: str):
    """Line numbers carrying an unquoted path with a brace. Works without a YAML parser."""
    return [i for i, line in enumerate(text.splitlines(), 1) if UNQUOTED_BRACED_PATH.match(line)]


def parse_manifests(manifests, findings):
    """Gate 1(a). Returns {path: document} for everything that parsed."""
    import yaml  # imported by the caller only once it knows the module is present

    parsed = {}
    for path in manifests:
        try:
            text = path.read_text(encoding="utf-8")
        except OSError as exc:
            findings.append(Finding("Blocking", "1(a) parse", path, f"unreadable: {exc}"))
            continue

        try:
            doc = yaml.safe_load(text)
        except yaml.YAMLError as exc:
            line = None
            mark = getattr(exc, "problem_mark", None)
            if mark is not None:
                line = mark.line + 1
            braced = scan_unquoted_paths(text)
            hint = None
            if braced:
                hint = (
                    "unquoted path containing '{' on line "
                    + ", ".join(str(n) for n in braced)
                    + " — quote it: path: \"/api/things/{id}\" (manifest-guide.md §3)"
                )
            findings.append(
                Finding(
                    "Blocking",
                    "1(a) parse",
                    path,
                    "not valid YAML — this slice is invisible to every manifest reader",
                    line=line,
                    hint=hint,
                )
            )
            continue

        if not isinstance(doc, dict):
            findings.append(
                Finding("Blocking", "1(a) parse", path, "parses, but is not a mapping at the root")
            )
            continue

        parsed[path] = doc

        # A file can parse and still carry the mistake — a braced path in BLOCK form is
        # legal YAML today and becomes a parse failure the moment someone reflows it.
        for line in scan_unquoted_paths(text):
            findings.append(
                Finding(
                    "Should-fix",
                    "1(a) parse",
                    path,
                    "unquoted path containing '{' — legal only because it is not in a flow "
                    "mapping; reflowing this entry makes the file unparseable",
                    line=line,
                    hint='quote it: path: "/api/things/{id}"',
                )
            )
    return parsed


def validate_schema(parsed, schema, findings):
    """Gate 1(b). Returns True if validation actually ran."""
    try:
        from jsonschema import validators
    except ImportError:
        return False

    validator_cls = validators.validator_for(schema)
    validator_cls.check_schema(schema)
    validator = validator_cls(schema)

    for path, doc in parsed.items():
        for error in sorted(validator.iter_errors(doc), key=lambda e: list(e.absolute_path)):
            location = list(error.absolute_path)
            where = "/".join(str(p) for p in location) or "(root)"
            findings.append(
                Finding(
                    "Should-fix",
                    "1(b) schema",
                    path,
                    f"{where}: {error.message}",
                    hint=schema_hint(location, error),
                )
            )
    return True


def schema_hint(location, error):
    """Plain-English hints for the mistakes a bare `oneOf` message explains badly."""
    head = location[0] if location else None

    if head == "serves" and isinstance(error.instance, str) and "/" in error.instance:
        return (
            "`serves` takes query NAMES, not routes — serves: [ListOrders]. The route belongs "
            "in `endpoints` (manifest-guide.md §3)"
        )
    if head == "projections":
        if "'from' is a required property" in error.message:
            return (
                "a projection declares its COMPLETE event list in `from`. `handles`, `table` and "
                "`kind` are the three keys most often reached for instead, and nothing reads them"
            )
        if "Additional properties" in error.message:
            if "subscribesTo" in error.message:
                return (
                    "`subscribesTo` is spelled `aggregateTypes` — the AggregateType stream(s) the "
                    "projector subscribes to (PascalCase plural, e.g. Orders). If the value is a "
                    "list of EVENT types it belongs in `from` instead; the two are different axes "
                    "and moving one into the other corrupts it (manifest-guide.md §3)"
                )
            return (
                "only name, aggregateTypes, from, consistency and store are read; move the rest "
                "into `notes`"
            )
    if head is None and "Additional properties" in error.message:
        return (
            "no reader consults an unknown root key. Use the field that fits, `notes:` for prose, "
            "or an `x-` prefixed key if it really is project-specific"
        )
    return None


def _names(value):
    """`handles`/`writes`/`owns` accept a bare string or {name: ...}. Normalise both."""
    out = []
    if isinstance(value, list):
        for item in value:
            if isinstance(item, str):
                out.append(item)
            elif isinstance(item, dict) and isinstance(item.get("name"), str):
                out.append(item["name"])
    return out


def cross_manifest_checks(parsed, findings):
    """Gates 3 and 4 — mechanical, manifest-only, no source reading."""
    slice_ids = {}
    handled = {}
    owned = {}
    written = {}
    twin_pairs = []

    for path, doc in parsed.items():
        sid = doc.get("slice")
        bc = doc.get("bc")
        if isinstance(sid, str):
            slice_ids.setdefault(sid, []).append(path)
            if isinstance(doc.get("supersedes"), str):
                twin_pairs.append((sid, doc["supersedes"]))
        for name in _names(doc.get("handles")):
            handled.setdefault(name, []).append((path, sid))
        for name in _names(doc.get("owns")):
            owned.setdefault(name, []).append((path, sid))
        for name in _names(doc.get("writes")):
            written.setdefault(name, {}).setdefault(bc, []).append(sid)

    # Gate 3 — slice ids are globally unique.
    for sid, paths in sorted(slice_ids.items()):
        if len(paths) > 1:
            for path in paths:
                findings.append(
                    Finding(
                        "Blocking",
                        "3 uniqueness",
                        path,
                        f"slice id '{sid}' is declared by {len(paths)} manifests: "
                        + ", ".join(str(p) for p in paths),
                    )
                )

    # Gate 4(a) — one handler per command type. The command bus enforces this at runtime
    # (MultipleCommandHandlersFoundException), so a duplicate here is a startup failure.
    for name, owners in sorted(handled.items()):
        if len(owners) > 1:
            for path, sid in owners:
                # By path, not by id: two manifests sharing an id (gate 3) would otherwise
                # list nobody.
                others = ", ".join(str(s) for p, s in owners if p != path)
                findings.append(
                    Finding(
                        "Blocking",
                        "4(a) sole handler",
                        path,
                        f"command type '{name}' is also handled by: {others}. The command bus "
                        f"allows exactly one handler per command type and throws "
                        f"MultipleCommandHandlersFoundException at startup",
                    )
                )

    # Gate 4(b) — a read model has exactly one writing slice (§R4 ownership). Owners that are
    # all one `supersedes` family are a declared migration twin: gate 13's Should-fix, not this.
    for name, owners in sorted(owned.items()):
        if len(owners) > 1 and not one_twin_family([s for _, s in owners], twin_pairs):
            for path, sid in owners:
                others = ", ".join(str(s) for p, s in owners if p != path)
                findings.append(
                    Finding(
                        "Blocking",
                        "4(b) sole owner",
                        path,
                        f"read model '{name}' is also owned by: {others}. Two slices sharing a "
                        f"read model is §R4; a deliberate migration twin declares `supersedes`",
                    )
                )

    # Gate 4(c) — an aggregate belongs to ONE bounded context. Several command slices
    # writing it INSIDE one BC is the design on every lane and is never reported.
    for name, by_bc in sorted(written.items()):
        if len(by_bc) > 1:
            listed = "; ".join(
                f"{bc}: {', '.join(str(s) for s in sorted(sids, key=str))}"
                for bc, sids in sorted(by_bc.items(), key=lambda kv: str(kv[0]))
            )
            for path, doc in parsed.items():
                if name in _names(doc.get("writes")):
                    findings.append(
                        Finding(
                            "Blocking",
                            "4(c) one BC per aggregate",
                            path,
                            f"'{name}' is written from {len(by_bc)} bounded contexts — {listed}. "
                            f"An aggregate belongs to one consistency boundary",
                        )
                    )


def one_twin_family(ids, twin_pairs):
    """True when every id is linked to the others through `supersedes`."""
    if any(not isinstance(i, str) for i in ids):
        return False
    wanted = set(ids)
    start = min(wanted)
    seen, todo = {start}, [start]
    while todo:
        cur = todo.pop()
        for pair in twin_pairs:
            if cur in pair:
                for other in pair:
                    if other in wanted and other not in seen:
                        seen.add(other)
                        todo.append(other)
    return seen == wanted


def tier_checks(parsed, findings):
    """Gate 14, its one manifest-only clause — a write style written into `tier`.

    `lane` and `tier` are different axes that coincide on one value (`service-entity`). The
    other two lanes are not tier vocabulary, so a reader falls back to `custom` without a word.
    """
    for path, doc in sorted(parsed.items(), key=lambda kv: str(kv[0])):
        tier = doc.get("tier")
        if not isinstance(tier, str) or tier not in LANE_ONLY_TIERS:
            continue
        line = None
        try:
            for i, text in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if re.match(r"^tier:\s*[\"']?" + re.escape(tier) + r"\b", text):
                    line = i
                    break
        except OSError:
            pass
        findings.append(
            Finding(
                "Should-fix",
                "14 tier",
                path,
                f"tier: {tier} is a write style (§R5 lane), not an architectureTier value; a reader "
                f"treats it as `custom` and silently drops the slice's tier-specific handling",
                line=line,
                hint=f"tier: {LANE_ONLY_TIERS[tier]} + lane: {tier}",
            )
        )


def _projections(doc):
    """Every projection mapping in a manifest, skipping malformed entries."""
    value = doc.get("projections")
    return [p for p in value if isinstance(p, dict)] if isinstance(value, list) else []


def stream_vs_event_checks(parsed, findings):
    """Gate 1(c) — an event type parked in `aggregateTypes`.

    `aggregateTypes` names the STREAM a projector subscribes to; `from` names the EVENT
    TYPES its handlers take. Both are arrays of strings, so the schema cannot tell them
    apart and a swap validates cleanly — the same silent-but-wrong outcome that made
    `projections` a closed object in the first place. What the schema cannot see, the
    corpus can: a name used as an event ANYWHERE in the project is not a stream here.
    """
    events = set()
    for doc in parsed.values():
        events.update(_names(doc.get("publishes")))
        events.update(_names(doc.get("consumes")))
        for projection in _projections(doc):
            source = projection.get("from")
            if isinstance(source, list):
                events.update(n for n in source if isinstance(n, str))

    for path, doc in sorted(parsed.items(), key=lambda kv: str(kv[0])):
        for projection in _projections(doc):
            declared = projection.get("aggregateTypes")
            if not isinstance(declared, list):
                continue
            for name in declared:
                if isinstance(name, str) and name in events:
                    findings.append(
                        Finding(
                            "Should-fix",
                            "1(c) stream vs event",
                            path,
                            f"projection '{projection.get('name', '?')}' declares "
                            f"aggregateTypes: [{name}], but '{name}' is used as an event type "
                            f"elsewhere in this project. `aggregateTypes` takes the "
                            f"AggregateType STREAM (PascalCase plural, e.g. Orders)",
                            hint=(
                                "if the projector handles this event, it belongs in `from`; "
                                "`aggregateTypes` is the stream those events arrive on "
                                "(manifest-guide.md §3)"
                            ),
                        )
                    )


def report(findings, manifests, parsed, schema_ran, quiet):
    out = sys.stdout
    findings.sort(key=Finding.sort_key)

    unparsed = [f for f in findings if f.gate == "1(a) parse" and f.severity == "Blocking"]
    if unparsed:
        print("UNPARSEABLE MANIFESTS — these slices are invisible to every gate:", file=out)
        for f in unparsed:
            print(f"  {f.location}  {f.message}", file=out)
            if f.hint:
                print(f"      → {f.hint}", file=out)
        print("", file=out)

    rest = [f for f in findings if f not in unparsed]
    if rest:
        for f in rest:
            print(f"{f.severity:<11} {f.gate:<24} {f.location}", file=out)
            print(f"            {f.message}", file=out)
            if f.hint:
                print(f"            → {f.hint}", file=out)
        print("", file=out)

    if not quiet:
        print(
            f"{len(manifests)} manifest(s) found, {len(parsed)} parsed, {len(findings)} finding(s).",
            file=out,
        )
        if not schema_ran:
            print(
                "SCHEMA VALIDATION SKIPPED — `jsonschema` is not installed, so only the parse "
                "gate ran. This is NOT a clean bill of health.\n"
                "  uv run --script slice-lint.py …   (or: pip install jsonschema)",
                file=out,
            )
        if not findings and schema_ran:
            print("Every manifest parses and validates.", file=out)


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="slice-lint",
        description="Validate every slice.yaml in a project. Deterministic; reports, never writes.",
    )
    parser.add_argument("root", nargs="?", default=".", help="directory to scan (default: .)")
    parser.add_argument("--schema", help="path to slice-yaml.schema.json")
    parser.add_argument(
        "--require-schema",
        action="store_true",
        help="exit non-zero if schema validation could not run (use in CI)",
    )
    parser.add_argument("--json", action="store_true", help="emit findings as JSON")
    parser.add_argument("--quiet", action="store_true", help="print findings only")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve()
    if not root.is_dir():
        print(f"slice-lint: not a directory: {root}", file=sys.stderr)
        return 2

    if args.schema:
        schema_path = Path(args.schema).resolve()
    else:
        here = Path(__file__).resolve().parent
        schema_path = here.parent / "references" / "slice" / "slice-yaml.schema.json"
        if not schema_path.is_file():
            schema_path = here / "slice-yaml.schema.json"
    try:
        schema = json.loads(schema_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        print(f"slice-lint: cannot read schema {schema_path}: {exc}", file=sys.stderr)
        return 2

    manifests = find_manifests(root)
    if not manifests:
        if args.json:
            json.dump({"root": str(root), "manifests": 0, "parsed": 0, "schemaValidated": False,
                       "findings": []}, sys.stdout, indent=2)
            sys.stdout.write("\n")
        elif not args.quiet:
            print(f"slice-lint: no slice.yaml found under {root}", file=sys.stderr)
        return 0

    try:
        __import__("yaml")
    except ImportError:
        print(
            "slice-lint: `pyyaml` is not installed, so no manifest can be parsed.\n"
            "  uv run --script slice-lint.py …   (or: pip install pyyaml jsonschema)\n"
            "Scanning for the one mistake a regex can find instead:",
            file=sys.stderr,
        )
        hits = 0
        for path in manifests:
            try:
                text = path.read_text(encoding="utf-8")
            except OSError:
                continue
            for line in scan_unquoted_paths(text):
                hits += 1
                print(f"  {path}:{line}  unquoted path containing '{{'", file=sys.stderr)
        if not hits:
            print("  (none found — but nothing was validated)", file=sys.stderr)
        return 2

    findings = []
    parsed = parse_manifests(manifests, findings)
    schema_ran = validate_schema(parsed, schema, findings)
    stream_vs_event_checks(parsed, findings)
    cross_manifest_checks(parsed, findings)
    tier_checks(parsed, findings)

    if args.json:
        json.dump(
            {
                "root": str(root),
                "manifests": len(manifests),
                "parsed": len(parsed),
                "schemaValidated": schema_ran,
                "findings": [f.as_dict() for f in sorted(findings, key=Finding.sort_key)],
            },
            sys.stdout,
            indent=2,
        )
        sys.stdout.write("\n")
    else:
        report(findings, manifests, parsed, schema_ran, args.quiet)

    if findings:
        return 1
    if args.require_schema and not schema_ran:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
