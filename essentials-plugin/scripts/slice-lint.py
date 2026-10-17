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
write style written into `tier`), rather than eyeballing the files. Gate 1(d) is the one
cross-field rule inside a manifest: a scenario (`scenarios:`, schema 1.4) names the slice's own
messages. It reads manifests only; the syntactic source facts are `slice-source.py`'s.

Usage
-----
    slice-lint.py [ROOT] [options]

    ROOT                 directory to scan (default: the current directory)
    --schema PATH        JSON Schema to validate against (default, resolved relative
                         to this script: ../references/slice/slice-yaml.schema.json in
                         the plugin, else slice-yaml.schema.json beside it, which is
                         where a project's installed copy keeps it)
    --allow-skip-schema  exit 0, not 2, when schema validation could not run; the
                         SKIPPED banner still prints. For a caller that has decided a
                         parse-only pass is enough — never for CI
    --require-schema     accepted for compatibility, does nothing: failing on a skipped
                         validation is the default (hooks and CI written for older
                         copies pass it)
    --baseline JSON      a document a previous `--json` run printed; report only the
                         findings it does not contain (see Baseline below)
    --json               emit findings as JSON on stdout; each finding carries its
                         `ESS-G<gate><clause>` id beside the gate label
    --quiet              print only findings, no summary

Exit codes
----------
    0   no findings, and every parsed manifest was schema-validated
    1   findings (Blocking or Should-fix — the script never emits Advisory). With
        --baseline, only findings absent from the baseline count
    2   could not run at all — bad arguments, unreadable schema or baseline, no YAML
        parser — or ran without schema validation (`jsonschema` missing) and found
        nothing; --allow-skip-schema turns that last case into 0

Findings win over a skipped validation: a run that found something exits 1 even when the
schema did not run, so read `schemaValidated` in the JSON rather than inferring it from the
exit code. A tree with no slice.yaml exits 0 — there was nothing to validate.

Baseline
--------
`--baseline previous.json` drops every finding the baseline already holds, matched on
(id, file, message) with the file, and any path inside the message, taken relative to each
run's own `root`, so a baseline taken in another checkout still matches. A baseline written
by an older copy without `id` fields is matched on the id its `gate` label gives. Two uses:
run a project's installed copy with `--json`, then this copy with `--baseline` over the same
tree, and what remains is exactly what refreshing the installed copy would add; or commit a
baseline and pass it in CI as a ratchet, so existing findings stop failing the build and new
ones still do. A reworded message counts as a new finding. The JSON carries
`baseline: {"path", "suppressed"}` when one was applied.

Dependencies
------------
`pyyaml` is required for parsing; `jsonschema` is required for schema validation. Both are
pinned in the inline script metadata above, so `uv run --script slice-lint.py` brings them
(this works from a project's installed copy too). When
`jsonschema` is absent the parse gate still runs, the script says loudly that
validation was skipped, and it exits 2 — silence is never allowed to read as a pass. When `pyyaml` is
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
# a reader must treat as `custom` — silently dropping the slice's tier-specific handling. Each maps
# to its repair, which names `cqrs-es` only where the lane guarantees it: an aggregate-lane BC is
# event-sourced by definition, a decider BC not necessarily, and a wrong tier is worse than an absent
# one (the schema's `tier` description), so there the fallback is to drop the field.
LANE_ONLY_TIERS = {
    "aggregate": "lane: aggregate; tier: cqrs-es (an aggregate-lane BC is event-sourced)",
    "decider": "lane: decider; tier: cqrs-es if the BC is event-sourced, else remove tier "
    "(absent ⇒ project default)",
}

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


GATE_REF = "#/$defs/knownKeys"


def version_gate(schema):
    """Index of the `allOf` clause that applies `knownKeys` to a manifest stamped at or below the
    schema copy's own version, or None for a copy older than the gate — there an unknown key is
    an ordinary schema error and needs no special handling."""
    for i, clause in enumerate(schema.get("allOf") or ()):
        if isinstance(clause, dict) and (clause.get("else") or {}).get("$ref") == GATE_REF:
            return i
    return None


def unknown_keys(error, schema):
    """(location, key) for each key the `knownKeys` check rejected; key is None for a failure it
    cannot pin on one key. A key outside a mapping's own list fails `propertyNames` with the key as
    the instance; a `then` object fails one `anyOf` over its three forms, so the form is read from
    the object and the keys outside it are reported."""
    location = list(error.absolute_path)
    if isinstance(error.instance, str) and "propertyNames" in error.relative_schema_path:
        return [(location, error.instance)]
    if isinstance(error.instance, dict) and location[-1:] == ["then"]:
        for form in schema["$defs"]["scenario"]["properties"]["then"]["oneOf"][1:]:
            if form["required"][0] in error.instance:
                return [(location, k) for k in error.instance
                        if k not in form["properties"] and not str(k).startswith("x-")]
    return [(location, None)]


def validate_schema(parsed, schema, findings, notes):
    """Gate 1(b). Returns True if validation actually ran.

    A key the schema does not define is rejected on a manifest stamped at or below the copy's
    `x-manifestVersion`, and only noted on one stamped with a newer 1.x — that manifest was written
    for a schema this copy has not seen. `x-` keys are extension keys and pass everywhere. The gate
    lives in the schema, so any validator agrees; this function only words it."""
    try:
        from jsonschema import validators
    except ImportError:
        return False

    validator_cls = validators.validator_for(schema)
    validator_cls.check_schema(schema)
    validator = validator_cls(schema)
    gate = version_gate(schema)
    if gate is not None:
        known = validator_cls({"$schema": schema.get("$schema"), "$defs": schema["$defs"], "$ref": GATE_REF})
        newer = validator_cls({"$schema": schema.get("$schema"), **schema["allOf"][gate]["if"]})
    copy_version = schema.get("x-manifestVersion")
    defined_by = f"schema {copy_version}" if copy_version else "the project's schema copy"

    for path, doc in parsed.items():
        for error in sorted(validator.iter_errors(doc), key=lambda e: list(e.absolute_path)):
            if gate is not None and list(error.absolute_schema_path)[:3] == ["allOf", gate, "else"]:
                continue  # worded below, once per key
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
        if gate is None:
            continue
        tolerated = newer.is_valid(doc)
        for error in known.iter_errors(doc):
            for location, key in unknown_keys(error, schema):
                where = "/".join(str(p) for p in location) or "(root)"
                what = f"unknown key '{key}'" if key is not None else error.message
                if tolerated:
                    notes.append(
                        f"{path}: {where}: {what} — not defined by {defined_by}; the manifest "
                        f"is stamped {doc.get('schemaVersion')}, so it is accepted unchecked. "
                        "/essentials:upgrade refreshes the schema copy"
                    )
                else:
                    findings.append(
                        Finding(
                            "Should-fix",
                            "1(b) schema",
                            path,
                            f"{where}: {what}",
                            hint=unknown_key_hint(location, key),
                        )
                    )
    return True


def unknown_key_hint(location, key):
    head = location[0] if location else None
    if head == "projections":
        if key == "subscribesTo":
            return (
                "`subscribesTo` is spelled `aggregateTypes` — the AggregateType stream(s) the "
                "projector subscribes to (PascalCase plural, e.g. Orders). If the value is a "
                "list of EVENT types it belongs in `from` instead; the two are different axes "
                "and moving one into the other corrupts it (manifest-guide.md §3)"
            )
        return (
            "only name, aggregateTypes, from, consistency and store are read; move the rest "
            "into `notes`. `handles`, `table` and `kind` are the keys most often reached for "
            "instead of `from`"
        )
    if head is None:
        return (
            "no reader consults an unknown root key. Use the field that fits, `notes:` for prose, "
            "or an `x-` extension key if it really is project-specific"
        )
    return "not a key this schema defines here — fix the spelling, or prefix it `x-` to keep it as an extension key"


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
    if head == "flow":
        return (
            "`flow` is one journey name or a non-empty list of distinct names, each lower-case "
            "kebab- or snake_case: flow: checkout, or flow: [checkout, returns] (manifest-guide.md §3)"
        )
    if head == "scenarios":
        # A `oneOf` reports at its own level; the cause sits in its context, so look there too.
        def causes(e):
            yield e
            for sub in e.context or ():
                yield from causes(sub)

        if any(c.validator == "type" and ({"with", "rows"} & set(map(str, c.absolute_path)))
               for c in causes(error)):
            return (
                "example values are scalars — string, number, boolean or null. Quote a date or a "
                "time: unquoted, YAML reads 2026-10-08 as a date (manifest-guide.md §3)"
            )
        if len(location) >= 3 and location[2] == "then":
            return (
                "`then` is exactly one of: a non-empty list of steps; { error: Name, message: … }; "
                "{ nothing: true }; { readModel: Name, rows: [...] }. An empty `then: []` is not "
                "allowed — write { nothing: true } (manifest-guide.md §3)"
            )
        if len(location) >= 3 and location[2] in ("given", "when"):
            return (
                "a step is a mapping with exactly one of event, command, query, state, external or "
                "schedule (schedule: true), plus an optional `with` (manifest-guide.md §3)"
            )
    # A copy older than the version gate rejects an unknown root key through `patternProperties`, whose
    # message reads "does not match any of the regexes", not "Additional properties".
    if head is None and ("Additional properties" in error.message
                         or "does not match any of the regexes" in error.message):
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
                hint=LANE_ONLY_TIERS[tier],
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


# The message keys of a scenario step (schema `$defs/scenarioStep`). The schema guarantees exactly
# one per step; a step with none or several is its finding, so the cross-field rule skips it.
STEP_KEYS = ("event", "command", "query", "state", "external", "schedule")

SCENARIO_HINT = (
    "a scenario names the slice's own messages, per kind (manifest-guide.md §3 `scenarios`). "
    "Fix the name, or declare it in the list named here if the slice really has it"
)


def _step(step):
    """(key, name) of a scenario step, or None when its shape is the schema's finding.
    `name` is None for `schedule: true`."""
    if not isinstance(step, dict):
        return None
    keys = [k for k in STEP_KEYS if k in step]
    if len(keys) != 1:
        return None
    key = keys[0]
    value = step[key]
    if key == "schedule":
        return (key, None) if value is True else None
    return (key, value) if isinstance(value, str) else None


def _a(kind):
    return ("an " if str(kind)[:1] in "aeiou" else "a ") + str(kind)


def _listed(names):
    return "[" + ", ".join(sorted(set(names))) + "]" if names else "nothing"


def _scenario_line(lines, name):
    """Line of `name: <name>` inside the root `scenarios:` block, or None."""
    start = next((i for i, t in enumerate(lines) if re.match(r"^scenarios:", t)), None)
    if start is None or not isinstance(name, str):
        return None
    pattern = re.compile(r"^\s*(?:-\s*)?(?:\{\s*)?name:\s*[\"']?" + re.escape(name)
                         + r"[\"']?\s*(?:[,}#]|$)")
    for i in range(start + 1, len(lines)):
        line = lines[i]
        if line and not line[0].isspace() and line[0] not in "-#":
            break  # the next root key: the block ended
        if pattern.match(line):
            return i + 1
    return None


def _scenario_problems(doc, scenario):
    """What one scenario names outside the slice's own lists (manifest-guide.md §3 `scenarios`).

    Intra-manifest only. A `given` event that no slice publishes, a `with`/`rows` key that is no
    payload field or column, and a `test:` that resolves to nothing need other files: they are the
    Atlas's (`slice-index.py atlas`), never this script's, which ships into projects.
    """
    kind = doc.get("kind")
    problems = []
    handles, publishes = _names(doc.get("handles")), _names(doc.get("publishes"))
    consumes, dispatches = _names(doc.get("consumes")), _names(doc.get("dispatches"))
    serves, writes = _names(doc.get("serves")), _names(doc.get("writes"))
    projected = [n for p in _projections(doc) for n in (p.get("from") or []) if isinstance(n, str)]
    inbound = consumes + projected
    # A view's own read model: `owns`, plus `reads` and `projections[].name`, because the plugin's
    # own view template records the model there and writes no `owns`.
    models = (_names(doc.get("owns")) + _names(doc.get("reads"))
              + [p["name"] for p in _projections(doc) if isinstance(p.get("name"), str)])

    given, when, then = scenario.get("given"), scenario.get("when"), scenario.get("then")

    if isinstance(given, list):
        # A view on the service-entity lane reads entity state and has no events to be given; any
        # other view is fed by events only (manifest-guide.md §3, the kind table).
        entity_view = kind == "view" and "service-entity" in (doc.get("lane"), doc.get("tier"))
        allowed = ("event",) if kind == "view" and not entity_view else ("event", "state")
        for step in given:
            parsed = _step(step)
            if parsed is None:
                continue
            key, name = parsed
            if key not in allowed:
                problems.append(f"`given` holds a `{key}` step; on {_a(kind)} slice `given` takes "
                                + " and ".join(f"`{a}`" for a in allowed) + " steps only")
            elif kind == "view" and key == "event" and name not in inbound:
                problems.append(f"`given: {{ event: {name} }}` is not an event this view projects — "
                                f"`projections[].from` and `consumes` give {_listed(inbound)}")
            elif kind == "command" and key == "state" and name not in writes:
                problems.append(f"`given: {{ state: {name} }}` is not in `writes` {_listed(writes)}")

    # A translation's direction decides what may trigger it, and the trigger decides what it emits.
    direction = doc.get("direction")
    can_in = direction in (None, "inbound", "both")
    can_out = direction in (None, "outbound", "both")
    mode = None
    if when is None:
        if kind in ("command", "automation", "translation"):
            problems.append(f"no `when` — {_a(kind)} slice's scenario needs its trigger")
    elif (w := _step(when)) is not None:
        key, name = w
        if kind == "command":
            if key != "command":
                problems.append(f"`when` is a `{key}` step; a command slice is triggered by a `command`")
            elif name not in handles:
                problems.append(f"`when: {{ command: {name} }}` is not in `handles` {_listed(handles)}")
        elif kind == "view":
            if key != "query":
                problems.append(f"`when` is a `{key}` step; a view's `when` is a `query` it serves")
            elif name not in serves:
                problems.append(f"`when: {{ query: {name} }}` is not in `serves` {_listed(serves)}")
        elif kind == "automation":
            if key == "schedule":
                if "schedule" not in doc:
                    problems.append("`when: { schedule: true }`, but the slice declares no `schedule`")
            elif key != "event":
                problems.append(f"`when` is a `{key}` step; an automation is triggered by an `event` "
                                f"it consumes or by `schedule: true`")
            elif name not in consumes:
                problems.append(f"`when: {{ event: {name} }}` is not in `consumes` {_listed(consumes)}")
        elif kind == "translation":
            if key == "external" and can_in:
                mode = "inbound"
            elif key == "event" and can_out:
                mode = "outbound"
                if name not in consumes:
                    problems.append(f"`when: {{ event: {name} }}` is not in `consumes` {_listed(consumes)}")
            else:
                expected = {(True, True): "an `external` message (inbound) or an `event` it consumes "
                                          "(outbound)",
                            (True, False): "an `external` message (`direction: inbound`)",
                            (False, True): "an `event` it consumes (`direction: outbound`)"}
                problems.append(f"`when` is a `{key}` step; this translation is triggered by "
                                + expected.get((can_in, can_out), "nothing it declares"))

    if isinstance(then, list):
        if kind == "view":
            problems.append("`then` lists steps; a view's `then` is `{ readModel, rows }`, or "
                            "`{ error }` for a `when` query")
            return problems
        if kind == "translation":
            emits = {"inbound": ("command", "event"), "outbound": ("external",)}.get(
                mode or "", (("command", "event") if can_in else ()) + (("external",) if can_out else ()))
        else:
            emits = {"command": ("event",), "automation": ("command",)}.get(str(kind), ())
        for step in then:
            parsed = _step(step)
            if parsed is None:
                continue
            key, name = parsed
            if key not in emits:
                who = f"an {mode} translation" if mode else f"{_a(kind)} slice"
                problems.append(f"`then` holds a `{key}` step; {who} emits "
                                + (" and ".join(f"`{a}`" for a in emits) or "no") + " steps")
            elif key == "event" and name not in publishes:
                problems.append(f"`then: {{ event: {name} }}` is not in `publishes` {_listed(publishes)}")
            elif key == "command" and name not in dispatches:
                problems.append(f"`then: {{ command: {name} }}` is not in `dispatches` "
                                f"{_listed(dispatches)}")
    elif isinstance(then, dict):
        if "readModel" in then and kind != "view":
            problems.append(f"`then: {{ readModel }}` is a view's outcome; {_a(kind)} slice ends in "
                            f"steps, `error` or `nothing`")
        elif "readModel" in then and isinstance(then["readModel"], str) and then["readModel"] not in models:
            problems.append(f"`then: {{ readModel: {then['readModel']} }}` is not this view's read model "
                            f"— `owns`, `reads` and `projections[].name` give {_listed(models)}")
        elif "nothing" in then and kind == "view":
            problems.append("`then: { nothing: true }` on a view; an empty read model is "
                            "`{ readModel: <Name>, rows: [] }`")
        elif "error" in then and kind == "view" and when is None:
            problems.append("`then: { error }` on a view needs the `when` query that fails")
    return problems


def scenario_checks(parsed, findings):
    """Gate 1(d) — a scenario that names a message outside the slice's own lists.

    The schema checks a scenario's shape; it cannot see that `when: { command: CancelOrdr }` is not
    the command the slice `handles`, which is the mistake a generator makes. Same manifest, no other
    file, so it is precise, and a finding. Also the one shape rule the schema cannot state: a
    scenario name is unique within its slice.
    """
    for path, doc in sorted(parsed.items(), key=lambda kv: str(kv[0])):
        scenarios = doc.get("scenarios")
        if not isinstance(scenarios, list):
            continue
        try:
            lines = path.read_text(encoding="utf-8").splitlines()
        except OSError:
            lines = []
        counts = {}
        for scenario in scenarios:
            if isinstance(scenario, dict) and isinstance(scenario.get("name"), str):
                counts[scenario["name"]] = counts.get(scenario["name"], 0) + 1
        for i, scenario in enumerate(scenarios):
            if not isinstance(scenario, dict):
                continue
            name = scenario.get("name")
            where = f"scenarios/{i}" + (f" '{name}'" if isinstance(name, str) else "")
            problems = []
            if isinstance(name, str) and counts[name] > 1:
                problems.append(f"{counts[name]} scenarios of this slice share the name; a scenario "
                                f"name is unique within its slice")
            problems.extend(_scenario_problems(doc, scenario))
            line = _scenario_line(lines, name) if problems else None
            for problem in problems:
                findings.append(Finding("Should-fix", "1(d) scenario", path, f"{where}: {problem}",
                                        line=line, hint=SCENARIO_HINT))


def baseline_key(finding, root):
    """(id, file, message) with `root` cut out of the file and the message, so a baseline taken in
    another checkout of the same tree still matches. Accepts a Finding or a `--json` finding."""
    if isinstance(finding, Finding):
        fid, file, message = finding_id(finding.gate), str(finding.path), finding.message
    else:
        fid = finding.get("id") or finding_id(str(finding.get("gate", "")))
        file, message = str(finding.get("file", "")), str(finding.get("message", ""))
    if root:
        prefix = str(root).rstrip("/\\") + os.sep
        if file.startswith(prefix):
            file = file[len(prefix):]
        message = message.replace(prefix, "")
    return fid, Path(file).as_posix(), message


def load_baseline(path: Path):
    """The (id, file, message) keys of a previous `--json` run. Raises ValueError when the file is
    not one — a baseline that silently matched nothing would report every finding as new."""
    doc = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(doc, dict) or not isinstance(doc.get("findings"), list):
        raise ValueError("not a slice-lint --json document (no `findings` list)")
    root = doc.get("root") or ""
    return {baseline_key(f, root) for f in doc["findings"] if isinstance(f, dict)}


SKIPPED_BANNER = (
    "SCHEMA VALIDATION SKIPPED — `jsonschema` is not installed, so only the parse "
    "gate ran. This is NOT a clean bill of health, and exits 2 unless --allow-skip-schema.\n"
    "  uv run --script slice-lint.py …   (or: pip install jsonschema)"
)


def report(findings, manifests, parsed, schema_ran, quiet, suppressed=None, notes=()):
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

    for note in notes:
        print(f"Note        {note}", file=out)
    if notes:
        print("", file=out)

    if not quiet:
        print(
            f"{len(manifests)} manifest(s) found, {len(parsed)} parsed, {len(findings)} finding(s)"
            + (f" not in the baseline ({suppressed} in it, not shown)." if suppressed is not None
               else "."),
            file=out,
        )
        if not findings and schema_ran and suppressed is None:
            print("Every manifest parses and validates.", file=out)
    if not schema_ran:
        print(SKIPPED_BANNER, file=sys.stderr if quiet else out)


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="slice-lint",
        description="Validate every slice.yaml in a project. Deterministic; reports, never writes.",
    )
    parser.add_argument("root", nargs="?", default=".", help="directory to scan (default: .)")
    parser.add_argument("--schema", help="path to slice-yaml.schema.json")
    skip = parser.add_mutually_exclusive_group()
    skip.add_argument(
        "--allow-skip-schema",
        action="store_true",
        help="exit 0, not 2, when schema validation could not run (the banner still prints)",
    )
    skip.add_argument(
        "--require-schema",
        action="store_true",
        help="accepted for compatibility; failing on a skipped validation is the default",
    )
    parser.add_argument(
        "--baseline",
        metavar="JSON",
        help="a previous --json output; report only findings it does not contain",
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

    baseline = None
    if args.baseline:
        try:
            baseline = load_baseline(Path(args.baseline))
        except (OSError, ValueError) as exc:
            print(f"slice-lint: cannot read baseline {args.baseline}: {exc}", file=sys.stderr)
            return 2

    manifests = find_manifests(root)
    if not manifests:
        if args.json:
            json.dump({"root": str(root), "manifests": 0, "parsed": 0, "schemaValidated": False,
                       "findings": [], "notes": []}, sys.stdout, indent=2)
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
    notes = []
    schema_ran = validate_schema(parsed, schema, findings, notes)
    stream_vs_event_checks(parsed, findings)
    scenario_checks(parsed, findings)
    cross_manifest_checks(parsed, findings)
    tier_checks(parsed, findings)

    suppressed = None
    if baseline is not None:
        kept = [f for f in findings if baseline_key(f, root) not in baseline]
        suppressed = len(findings) - len(kept)
        findings = kept

    if args.json:
        doc = {
            "root": str(root),
            "manifests": len(manifests),
            "parsed": len(parsed),
            "schemaValidated": schema_ran,
            "findings": [f.as_dict() for f in sorted(findings, key=Finding.sort_key)],
            "notes": notes,
        }
        if suppressed is not None:
            doc["baseline"] = {"path": str(Path(args.baseline).resolve()), "suppressed": suppressed}
        json.dump(doc, sys.stdout, indent=2)
        sys.stdout.write("\n")
        if not schema_ran:
            print(SKIPPED_BANNER, file=sys.stderr)
    else:
        report(findings, manifests, parsed, schema_ran, args.quiet, suppressed, notes)

    # Findings first: a skipped validation never hides one. Otherwise a run that could not validate
    # is not a pass, and says so with 2 — the code a hook or CI already treats as "did not run".
    if findings:
        return 1
    if not schema_ran and not args.allow_skip_schema:
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
