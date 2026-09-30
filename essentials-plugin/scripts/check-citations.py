#!/usr/bin/env python3
"""check-citations — the plugin cites the stack contract and the pins; it never restates them.

Why this exists
---------------
`references/stack/stack-pins.md` is the one place a version number lives, and
`references/stack/stack-contract.md` is the one place requirements S1-S11 are stated. Every other
file in the plugin cites them — "(stack-contract S3)", a link to the file — so that a pin move or
a contract edit reaches every citer without rewriting any of them. A restated pin or requirement
is a second copy, and a second copy goes stale silently: nothing fails when the pin moves, the old
number just keeps being read. This script is the gate that makes the copy loud.

It checks the plugin's own files, not a user's project, and needs nothing beyond Python 3's
standard library.

Rules
-----
pinned-version
    A version value from `stack-pins.md` appears outside the files allowed to carry it. The values
    are read from the pins tables on every run, never hard-coded here. A value with two or more
    dots (x.y.z) is distinctive enough to flag wherever it stands. A shorter one (the Java release,
    a two-part version) is a common number, so it is flagged only right after the name of the thing
    it pins ("Java <n>", "<java.version><n>", "objenesis <n>.<m>"). A compatibility line that is
    not the pin itself — "Spring Boot 4.1.x", "Testcontainers 2.x", "Kotlin 2.3 or newer" — is not
    flagged.

restated-requirement
    A sentence that carries an S-requirement id (S1-S11, S2.1, a range such as S1-S11), uses
    MUST or SHOULD (any case), and shares a run of at least MIN_RUN consecutive words with the text
    of a cited requirement in `stack-contract.md`. The id-plus-keyword test finds sentences that
    speak about a requirement; the shared run is what separates restating it from citing it. The
    heuristic is deliberately narrow: a false positive costs a maintainer more than a missed case.
    Fenced code blocks are not prose and are skipped for this rule.

allow-marker
    A `cite-ok` marker with no reason. An exception nobody can explain is not an exception.

Allowing a deliberate exception
-------------------------------
Put `<!-- cite-ok: <reason> -->` on the flagged line or on the line directly above it (for a
sentence that spans lines: on any of its lines). Outside markdown, write `cite-ok: <reason>` in the
file's own comment syntax. The reason is mandatory.

What is exempt, and why
-----------------------
references/stack/stack-pins.md       the source of every version value
references/stack/stack-contract.md   the source of S1-S11; S1 names the baseline on purpose
references/stack/{java,kotlin}-spring-boot.md, frontend-react.md
                                     the contract's per-language and frontend bindings: they
                                     elaborate S-requirements by design, so they are exempt from
                                     restated-requirement. They are NOT exempt from pinned-version —
                                     S1 says nothing in references/stack/ but the pins names a version
references/llm/**                    generated from the repository's LLM/ by sync-plugin-llm.sh;
                                     fixed at the source, never here
CHANGELOG.md                         a release record: the version a release targeted stays true
tests/fixtures/**                    sample projects the slice tooling reads; their build files pin
                                     what a user's project pinned, not what this plugin pins
tests/citations/**                   this script's own self-test samples, which must violate
tests/golden/**                      renderer output (scripts/init-render.py --update-golden): it
                                     carries every pin it rendered by design, and init-render.py
                                     --check fails it the moment it differs from a fresh render
references/init-assets/project/contracts/openapi.json.template, the `"openapi"` line only
                                     the OpenAPI document-format version springdoc writes, which
                                     equals the springdoc pin by coincidence; JSON has no comment
                                     for a cite-ok marker. Every other line of the file is scanned
.claude-plugin/plugin.json, the `"version"` line only
                                     the plugin's release version, which is the essentials.version pin
                                     plus an optional -N by design (the plugin-docs CI job holds it to
                                     the pin); JSON has no comment for a cite-ok marker

Usage
-----
    check-citations.py [ROOT] [options]

    ROOT            the plugin directory (default: the directory above this script)
    --self-test     run the checks against tests/citations/ and verify the expected findings
    --quiet         print findings only, no summary

Output is one finding per line, `path:line: rule: message`, with the path relative to ROOT.

Exit codes
----------
    0   no findings (with --self-test: every expectation met)
    1   findings (with --self-test: an expectation not met)
    2   could not run — bad arguments, an unreadable stack-pins.md or stack-contract.md, or a pins
        table with no version in it

Self-test
---------
`tests/citations/violating.md` marks every line that must be flagged with
`<!-- expect: <rule> -->`; `tests/citations/clean.md` must produce nothing. The samples do not
carry literal pins or contract text: `{{pin:<name>}}` is replaced by that row's current value in
`stack-pins.md`, and `{{quote:S<n>}}` by the first sentence of that requirement, so a pin move or a
contract edit does not break the self-test.

    python3 scripts/check-citations.py --self-test
"""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path

PINS = "references/stack/stack-pins.md"
CONTRACT = "references/stack/stack-contract.md"
BINDINGS = {
    "references/stack/java-spring-boot.md",
    "references/stack/kotlin-spring-boot.md",
    "references/stack/frontend-react.md",
}
# Not scanned at all: the two sources (S1 names the baseline on purpose) and the files the module
# docstring justifies.
EXEMPT_FILES = {PINS, CONTRACT, "CHANGELOG.md"}
EXEMPT_DIRS = ("references/llm/", "tests/fixtures/", "tests/citations/", "tests/golden/")
# Single lines that hold a version which is not a pin, by file (see the module docstring).
NOT_A_PIN = {
    "references/init-assets/project/contracts/openapi.json.template":
        re.compile(r'^\s*"openapi"\s*:\s*"[\d.]+"\s*,?\s*$'),
    ".claude-plugin/plugin.json":
        re.compile(r'^\s*"version"\s*:\s*"[\d.]+(-\d+)?"\s*,?\s*$'),
}

SCANNED_SUFFIXES = {
    ".md", ".template", ".java", ".kt", ".kts", ".yaml", ".yml", ".json", ".html",
    ".sh", ".py", ".xml", ".properties", ".toml", ".ts", ".tsx",
}
PROSE_SUFFIXES = {".md", ".template"}
SKIP_DIRS = {".git", "target", "build", "out", "node_modules", ".idea", ".gradle", "dist", "__pycache__"}

# Consecutive words a sentence must share with the requirement it cites before it counts as a
# restatement. Eight words is a clause, not a shared term of art: a citation that names the
# requirement's subject ("the persistence serializer", "Testcontainers 2.x artifact names") stays
# well under it.
MIN_RUN = 8

# Names a short pin value is recognised after, beyond the words of its own row. Keyed by the
# first word of the pins-table name; these are names, never values.
ALIASES = {
    "java": ["java", "jdk", "jvm", "jvmtarget", "--release", "maven.compiler.release"],
    "spring-boot-starter-parent": ["spring boot", "boot"],
    "kotlin": ["kotlin"],
    "node": ["node", "node.js", "nodejs"],
    "essentials": ["essentials"],
}

REQ_ID = re.compile(r"(?<![\w.])S(1[01]|[1-9])(?:\.\d+)?(?:\s*[–-]\s*S(1[01]|[1-9]))?(?![\w])")
NORMATIVE = re.compile(r"\b(must|should)\b", re.IGNORECASE)
ALLOW = re.compile(r"cite-ok\s*:(?P<reason>(?:(?!-->|\*/).)*)")
EXPECT = re.compile(r"<!--\s*expect:\s*([\w-]+)\s*-->")
WORD = re.compile(r"[a-z0-9]+(?:[._'][a-z0-9]+)*")
FENCE = re.compile(r"^\s*(```|~~~)")
BLOCK_START = re.compile(r"^\s*(?:[-*+]\s|\d+[.)]\s|\||#|>)")
SENTENCE_END = re.compile(r"(?<=[.!?])[*_`)\"']*\s+(?=[A-Z*`(\[_\"])")


class UsageError(Exception):
    pass


class Finding:
    __slots__ = ("path", "line", "rule", "message")

    def __init__(self, path, line, rule, message):
        self.path = path
        self.line = line
        self.rule = rule
        self.message = message

    def __str__(self):
        return f"{self.path}:{self.line}: {self.rule}: {self.message}"


# ---------------------------------------------------------------------------------------------
# The two sources


def read_pins(path: Path):
    """[(name, value)] from every table row of stack-pins.md whose second cell is a version."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise UsageError(f"cannot read {path}: {exc}")
    pins = []
    for line in text.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) < 2 or not line.lstrip().startswith("|"):
            continue
        value = cells[1].replace("*", "").replace("`", "").strip()
        value = value.lstrip("v~^")
        if re.fullmatch(r"\d+(?:\.\d+)*", value):
            pins.append((cells[0].replace("`", "").strip(), value))
    if not pins:
        raise UsageError(f"no version found in the tables of {path}")
    return pins


def name_keywords(name: str):
    """The words a short value is recognised after: the row's own names plus ALIASES."""
    keywords = set()
    for part in re.split(r"/|\(", name):
        part = part.strip(" )").lower()
        if not part:
            continue
        keywords.add(part)
        base = re.sub(r"(-bom)?\.version$", "", part)
        keywords.add(base)
        keywords.update(ALIASES.get(base.split()[0], []))
    return sorted(k for k in keywords if k)


def pin_patterns(pins):
    """[(name, value, compiled pattern)]."""
    patterns = []
    for name, value in pins:
        escaped = re.escape(value)
        tail = r"(?![\w]|\.\d)"
        if value.count(".") >= 2:
            pattern = re.compile(r"(?<![\w.])[v~^]?" + escaped + tail)
        else:
            names = "|".join(re.escape(k).replace(r"\ ", r"\s+") for k in name_keywords(name))
            pattern = re.compile(
                r"(?<![\w.-])(?:" + names + r")(?:\s+version)?[\s:=`*\"'<>/_-]{0,6}[v~^]?"
                + escaped + tail,
                re.IGNORECASE,
            )
        patterns.append((name, value, pattern))
    return patterns


def words(text: str):
    return WORD.findall(text.lower())


def read_contract(path: Path):
    """{requirement number: (word n-grams of its section, first sentence)}."""
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise UsageError(f"cannot read {path}: {exc}")
    sections, current, in_fence = {}, None, False
    for line in text.splitlines():
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        heading = re.match(r"^#{2,3}\s+S(\d+)(?:\.\d+)?\b", line)
        if heading:
            current = int(heading.group(1))  # S2.1 belongs to S2
            sections.setdefault(current, [])
            continue
        if re.match(r"^#{1,2}\s", line) or line.strip() == "---":
            current = None
            continue
        if current is not None and not in_fence:
            sections[current].append(line)
    # The conformance checklist restates each requirement in one line; copying one of those lines
    # is a restatement too.
    for m in re.finditer(r"^- \[ \] (.*)$", text, re.MULTILINE):
        for number in cited_numbers(m.group(1)):
            sections.setdefault(number, []).extend(["", m.group(1)])
    if not sections:
        raise UsageError(f"no S-requirement headings found in {path}")
    contract = {}
    for number, lines in sections.items():
        body = "\n".join(lines)
        grams = set()
        for paragraph in re.split(r"\n\s*\n", body):
            w = words(paragraph)
            grams.update(tuple(w[i : i + MIN_RUN]) for i in range(len(w) - MIN_RUN + 1))
        prose = " ".join(
            l.strip() for l in lines if l.strip() and not l.lstrip().startswith(("|", ">", "-"))
        )
        first = SENTENCE_END.split(prose, maxsplit=1)[0] if prose else ""
        contract[number] = (grams, first)
    return contract


# ---------------------------------------------------------------------------------------------
# Scanning


def allow_state(lines, first, last):
    """(allowed, missing_reason_line) for a finding spanning lines[first..last] (0-based)."""
    for i in range(max(first - 1, 0), last + 1):
        m = ALLOW.search(lines[i])
        if m:
            if m.group("reason").strip():
                return True, None
            return False, i + 1
    return False, None


def prose_units(lines):
    """Yield (first_line_index, [(line_index, text)]) per paragraph, list item, table row, heading."""
    unit, in_fence = [], False
    for i, line in enumerate(lines):
        if FENCE.match(line):
            if unit:
                yield unit
                unit = []
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if not line.strip():
            if unit:
                yield unit
                unit = []
            continue
        if BLOCK_START.match(line) and unit:
            yield unit
            unit = []
        unit.append((i, line))
        if line.lstrip().startswith(("|", "#")):
            yield unit
            unit = []
    if unit:
        yield unit


def sentences(unit):
    """Split a unit into sentences, each as (first_line_index, last_line_index, text)."""
    text, offsets = "", []
    for i, line in unit:
        offsets.append((len(text), i))
        text += line.strip() + " "
    def line_at(pos):
        index = offsets[0][1]
        for start, i in offsets:
            if start <= pos:
                index = i
        return index
    pos = 0
    for m in list(SENTENCE_END.finditer(text)) + [None]:
        end = m.start() if m else len(text)
        chunk = text[pos:end].strip()
        if chunk:
            yield line_at(pos), line_at(max(end - 1, pos)), chunk
        pos = m.end() if m else len(text)


def cited_numbers(sentence):
    numbers = set()
    for m in REQ_ID.finditer(sentence):
        low = int(m.group(1))
        high = int(m.group(2)) if m.group(2) else low
        numbers.update(range(low, high + 1))
    return numbers


def check_text(rel, text, patterns, contract, prose, restatement_rule=True):
    findings, lines = [], text.splitlines()
    seen_markers = set()

    def report(first, last, rule, message):
        allowed, bare = allow_state(lines, first, last)
        if allowed:
            return
        if bare and bare not in seen_markers:
            seen_markers.add(bare)
            findings.append(Finding(rel, bare, "allow-marker", "cite-ok marker without a reason"))
        findings.append(Finding(rel, first + 1, rule, message))

    not_a_pin = NOT_A_PIN.get(rel)
    for i, line in enumerate(lines):
        if not_a_pin and not_a_pin.match(line):
            continue
        for name, value, pattern in patterns:
            if pattern.search(line):
                report(i, i, "pinned-version",
                       f"{value} is the `{name}` pin — cite references/stack/stack-pins.md, "
                       "do not restate it")

    if prose and restatement_rule:
        for unit in prose_units(lines):
            for first, last, sentence in sentences(unit):
                numbers = cited_numbers(sentence)
                if not numbers or not NORMATIVE.search(sentence):
                    continue
                w = words(sentence)
                grams = {tuple(w[k : k + MIN_RUN]) for k in range(len(w) - MIN_RUN + 1)}
                hits = sorted(n for n in numbers if n in contract and grams & contract[n][0])
                if hits:
                    ids = ", ".join(f"S{n}" for n in hits)
                    report(first, last, "restated-requirement",
                           f"restates the text of {ids} — cite it by number "
                           f"(stack-contract {ids}) instead")

    # A bare marker that suppressed nothing is still a finding.
    for i, line in enumerate(lines):
        m = ALLOW.search(line)
        if m and not m.group("reason").strip() and (i + 1) not in seen_markers:
            seen_markers.add(i + 1)
            findings.append(Finding(rel, i + 1, "allow-marker", "cite-ok marker without a reason"))
    return findings


def scan(root: Path, patterns, contract):
    findings = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if d not in SKIP_DIRS)
        for filename in sorted(filenames):
            path = Path(dirpath) / filename
            rel = path.relative_to(root).as_posix()
            if rel in EXEMPT_FILES or rel.startswith(EXEMPT_DIRS):
                continue
            if path.suffix not in SCANNED_SUFFIXES:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                continue
            findings.extend(
                check_text(rel, text, patterns, contract,
                           prose=path.suffix in PROSE_SUFFIXES,
                           restatement_rule=rel not in BINDINGS)
            )
    return findings


# ---------------------------------------------------------------------------------------------
# Self-test


def self_test(root: Path, pins, patterns, contract, out):
    values = {name: value for name, value in pins}
    samples = root / "tests" / "citations"

    def render(path):
        text = path.read_text(encoding="utf-8")
        def pin(m):
            if m.group(1) not in values:
                raise UsageError(f"{path}: no pin named {m.group(1)!r} in {PINS}")
            return values[m.group(1)]
        def quote(m):
            number = int(m.group(1))
            if number not in contract or not contract[number][1]:
                raise UsageError(f"{path}: no text for S{number} in {CONTRACT}")
            return contract[number][1]
        text = re.sub(r"\{\{pin:([\w.-]+)\}\}", pin, text)
        return re.sub(r"\{\{quote:S(\d+)\}\}", quote, text)

    failures = 0
    for name, expect_clean in (("violating.md", False), ("clean.md", True)):
        path = samples / name
        try:
            text = render(path)
        except OSError as exc:
            raise UsageError(f"cannot read {path}: {exc}")
        found = check_text(f"tests/citations/{name}", text, patterns, contract, prose=True)
        got = {(f.line, f.rule) for f in found}
        want = set()
        if not expect_clean:
            for i, line in enumerate(text.splitlines(), 1):
                want.update((i, rule) for rule in EXPECT.findall(line))
            if not want:
                raise UsageError(f"{path}: no <!-- expect: rule --> markers")
        for line, rule in sorted(want - got):
            failures += 1
            print(f"FAIL tests/citations/{name}:{line}: expected {rule}, not reported", file=out)
        for line, rule in sorted(got - want):
            failures += 1
            print(f"FAIL tests/citations/{name}:{line}: unexpected {rule}", file=out)
        if not (want ^ got):
            print(f"ok   tests/citations/{name}: {len(want)} expected finding(s), "
                  f"{len(got)} reported", file=out)
    return 1 if failures else 0


# ---------------------------------------------------------------------------------------------


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="check-citations",
        description="Flag version pins and S1-S11 requirement text restated outside "
        "stack-pins.md / stack-contract.md. Deterministic; reports, never writes.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=f"""\
rules:
  pinned-version        a stack-pins.md value outside the files allowed to carry it
  restated-requirement  a MUST/SHOULD sentence citing S<n> that shares {MIN_RUN}+ consecutive
                        words with that requirement's text in stack-contract.md
  allow-marker          a cite-ok marker with no reason

output:  path:line: rule: message   (path relative to ROOT)
allow:   <!-- cite-ok: reason --> on the flagged line or the line above it
exempt:  stack-pins.md, stack-contract.md, CHANGELOG.md, references/llm/**,
         tests/fixtures/**, tests/citations/**, tests/golden/**, the seed spec's
         "openapi" line, plugin.json's "version" line; the three binding docs in references/stack/ are exempt
         from restated-requirement only
exit:    0 clean, 1 findings, 2 usage error (bad arguments, unreadable pins/contract)
         with --self-test: 0 every expectation met, 1 one was not""",
    )
    parser.add_argument("root", nargs="?",
                        help="plugin directory (default: the directory above this script)")
    parser.add_argument("--self-test", action="store_true",
                        help="check tests/citations/{violating,clean}.md against their expectations")
    parser.add_argument("--quiet", action="store_true", help="print findings only")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else Path(__file__).resolve().parent.parent
    if not root.is_dir():
        print(f"check-citations: not a directory: {root}", file=sys.stderr)
        return 2

    try:
        pins = read_pins(root / PINS)
        patterns = pin_patterns(pins)
        contract = read_contract(root / CONTRACT)
        if args.self_test:
            return self_test(root, pins, patterns, contract, sys.stdout)
    except UsageError as exc:
        print(f"check-citations: {exc}", file=sys.stderr)
        return 2

    findings = scan(root, patterns, contract)
    findings.sort(key=lambda f: (f.path, f.line, f.rule))
    for f in findings:
        print(f)
    sys.stdout.flush()
    if not args.quiet:
        if findings:
            print(f"{len(findings)} finding(s). Cite stack-pins.md / stack-contract.md, or mark a "
                  f"deliberate exception with <!-- cite-ok: reason -->.", file=sys.stderr)
        else:
            print(f"No restated pins or requirements ({len(pins)} pins, "
                  f"{len(contract)} requirements checked).", file=sys.stderr)
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
