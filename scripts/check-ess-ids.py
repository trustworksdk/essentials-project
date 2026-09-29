#!/usr/bin/env python3
#
# Copyright 2021-2026 the original author or authors.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
"""check-ess-ids — the `ESS-NNN` ids on LLM/LLM-traps.md stay stable, and every citation of one resolves.

Why this exists
---------------
Each line of LLM/LLM-traps.md carries an id, `ESS-NNN`, with an anchor of the same name in lower case. The id is what
a review finding, a startup message or a migration note cites, so it is a public name: once handed out it must keep
meaning the same trap. Nothing fails when an id is quietly renumbered, deleted or handed to a different trap — the old
citations just start pointing at the wrong thing. This script is the gate that makes that loud:

- every trap line has an id, its anchor equals the lower-cased id, and no id appears twice;
- the active ids plus the tombstones under `## Retired ids` are exactly 1..max, so an id deleted without a tombstone
  leaves a gap and a retired id brought back is a duplicate — no git history needed;
- every relative link in the traps file resolves to a file and a heading (or anchor) in it;
- every `ESS-NNN` cited in LLM/, docs/, essentials-plugin/ (the generated references/llm/ copy aside) or any
  src/main Java/Kotlin file names an active id, and one cited in docs/MIGRATION-*.md belongs to a line linking there;
- with --baseline <git-ref>: no id present at that ref has vanished, and no id retired there is active again.

`ESS-S<n>` (stack contract) and `ESS-G<gate><clause>` (slice-check gates) are the essentials plugin's own namespaces,
numbered where they are defined; they are not matched here. A line containing `ess-ids: ignore` is not scanned for
citations, for negative test fixtures that need a bogus id.

Standard library only.

Usage:
  python3 scripts/check-ess-ids.py                  # exit 0 clean, 1 findings, 2 usage error
  python3 scripts/check-ess-ids.py --self-test
  python3 scripts/check-ess-ids.py --baseline HEAD  # also compare against the committed catalogue
  python3 scripts/check-ess-ids.py --list [--json]  # print the catalogue
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path

TRAPS = "LLM/LLM-traps.md"
GENERATED_COPY = "essentials-plugin/references/llm/"
RETIRED_HEADING = "## Retired ids"
IGNORE_MARKER = "ess-ids: ignore"

ID_LINE = re.compile(r'^- <a id="ess-(?P<anchor>[^"]*)"></a>`ESS-(?P<id>[^`]*)` (?P<rest>.*)$')
VALID_ID = re.compile(r"^\d{3}$")
CITATION = re.compile(r"(?<![\w-])ESS-(\d{3})(?!\d)")
TRAPS_LINK = re.compile(r"LLM-traps\.md#ess-(\d{3})(?!\d)")
LAST_LINK = re.compile(r" → \[(?P<label>[^\]]*)\]\((?P<href>[^)\s]*)\)\s*$")
MD_LINK = re.compile(r"\]\(([^)\s]+)\)")
HTML_ANCHOR = re.compile(r'<a\s+(?:id|name)="([^"]+)"')
PRUNED_DIRS = {".git", "target", "node_modules", "graphify-out", ".llm-consolidation", ".idea", ".gradle", "build"}


@dataclass
class Finding:
    path: str
    line: int
    rule: str
    message: str

    def __str__(self):
        return f"{self.path}:{self.line}: {self.rule}: {self.message}"


@dataclass
class Entry:
    number: int
    line: int
    retired: bool
    group: str
    module: str
    symptom: str
    href: str


@dataclass
class Catalogue:
    entries: list = field(default_factory=list)
    findings: list = field(default_factory=list)

    def active(self):
        return {e.number: e for e in self.entries if not e.retired}

    def retired(self):
        return {e.number: e for e in self.entries if e.retired}


def ess(number):
    return f"ESS-{number:03d}"


# ---------------------------------------------------------------------------------------------------------------------
# The catalogue


def parse_catalogue(text, path=TRAPS):
    catalogue = Catalogue()
    group = module = ""
    in_retired = False
    saw_retired = False
    in_fence = False
    for number, line in enumerate(text.splitlines(), 1):
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
        if in_fence:
            continue
        if line.startswith("## "):
            group = line[3:].strip()
            module = ""
            if in_retired:
                catalogue.findings.append(Finding(path, number, "retired-section",
                                                  f"'{RETIRED_HEADING}' must be the last section; found '{line}' after it"))
            in_retired = line.strip() == RETIRED_HEADING
            saw_retired = saw_retired or in_retired
            continue
        if line.startswith("### "):
            module = line[4:].split()[0] if line[4:].split() else ""
            continue
        if not line.startswith("- "):
            continue
        m = ID_LINE.match(line)
        if not m:
            catalogue.findings.append(Finding(path, number, "missing-id",
                                              'trap line does not start with `- <a id="ess-NNN"></a>`ESS-NNN``'))
            continue
        if not VALID_ID.match(m.group("id")):
            catalogue.findings.append(Finding(path, number, "format",
                                              f"'ESS-{m.group('id')}' is not ESS- followed by three digits"))
            continue
        if m.group("anchor") != m.group("id"):
            catalogue.findings.append(Finding(path, number, "anchor",
                                              f"anchor 'ess-{m.group('anchor')}' does not match id 'ESS-{m.group('id')}'"))
        rest = m.group("rest")
        link = LAST_LINK.search(rest)
        if not in_retired and not link:
            catalogue.findings.append(Finding(path, number, "format",
                                              "trap line does not end in ' → [section](link)' naming its owning section"))
        catalogue.entries.append(Entry(number=int(m.group("id")), line=number, retired=in_retired, group=group,
                                       module=module, symptom=rest[:link.start()] if link else rest,
                                       href=link.group("href") if link else ""))
    if not saw_retired:
        catalogue.findings.append(Finding(path, 1, "retired-section", f"no '{RETIRED_HEADING}' section"))
    return catalogue


def check_numbering(catalogue, path=TRAPS):
    findings = []
    seen = {}
    for entry in catalogue.entries:
        if entry.number in seen:
            first = seen[entry.number]
            what = "retired and active" if first.retired != entry.retired else "used twice"
            findings.append(Finding(path, entry.line, "duplicate",
                                    f"{ess(entry.number)} is {what} (first at line {first.line}); a new trap takes "
                                    f"the next unused number, and a retired id is never handed out again"))
        else:
            seen[entry.number] = entry
    if seen:
        missing = sorted(set(range(1, max(seen) + 1)) - set(seen))
        if missing:
            findings.append(Finding(path, 1, "gap",
                                    f"{', '.join(ess(n) for n in missing)} neither active nor retired; a trap that goes "
                                    f"away keeps its id as a tombstone under '{RETIRED_HEADING}'"))
    return findings


# ---------------------------------------------------------------------------------------------------------------------
# Links in the traps file


def slugify(heading):
    """GitHub's heading anchor: rendered text, lower-cased, punctuation dropped, spaces to hyphens."""
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading)
    text = text.replace("`", "").replace("*", "")
    # GitHub keeps letters, marks, digits, connector punctuation, hyphens and spaces (so an emoji's variation
    # selector survives, as a mark); Python's \w has no marks, hence the category test.
    kept = (c for c in text.lower() if c in "- " or unicodedata.category(c)[0] in "LMN" or unicodedata.category(c) == "Pc")
    return "".join(kept).replace(" ", "-")


def anchors_of(text):
    anchors = set(HTML_ANCHOR.findall(text))
    counts = {}
    in_fence = False
    for line in text.splitlines():
        if line.lstrip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        m = re.match(r"^#{1,6}\s+(.*?)\s*#*\s*$", line)
        if not m:
            continue
        slug = slugify(m.group(1))
        n = counts.get(slug, 0)
        counts[slug] = n + 1
        anchors.add(slug if n == 0 else f"{slug}-{n}")
    return anchors


def check_links(root, text, path=TRAPS):
    findings = []
    base = (root / path).parent
    cache = {}
    for number, line in enumerate(text.splitlines(), 1):
        for href in MD_LINK.findall(line):
            if re.match(r"^[a-z]+:", href):
                continue
            target, _, anchor = href.partition("#")
            file = (base / target).resolve() if target else (root / path).resolve()
            if not file.is_file():
                findings.append(Finding(path, number, "link", f"'{href}': no file {target}"))
                continue
            if anchor:
                if file not in cache:
                    cache[file] = anchors_of(file.read_text(encoding="utf-8"))
                if anchor not in cache[file]:
                    findings.append(Finding(path, number, "link", f"'{href}': no heading or anchor '#{anchor}'"))
    return findings


# ---------------------------------------------------------------------------------------------------------------------
# Citations everywhere else


def citing_files(root):
    for top in ("LLM", "docs", "essentials-plugin"):
        base = root / top
        if not base.is_dir():
            continue
        for dirpath, dirnames, filenames in os.walk(base):
            dirnames[:] = sorted(d for d in dirnames if d not in PRUNED_DIRS)
            for name in sorted(filenames):
                path = Path(dirpath) / name
                rel = path.relative_to(root).as_posix()
                if rel == TRAPS or rel.startswith(GENERATED_COPY):
                    continue
                yield rel, path
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames
                             if d not in PRUNED_DIRS and not d.startswith(".")
                             and not (Path(dirpath) == root and d in ("LLM", "docs", "essentials-plugin")))
        rel_dir = Path(dirpath).relative_to(root).as_posix()
        if "/src/main/" not in f"/{rel_dir}/":
            continue
        for name in sorted(filenames):
            if name.endswith((".java", ".kt")):
                path = Path(dirpath) / name
                yield path.relative_to(root).as_posix(), path


def read_text(path):
    try:
        data = path.read_bytes()
    except OSError:
        return None
    if b"\0" in data[:8192]:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def check_citations(root, catalogue):
    findings = []
    active = catalogue.active()
    retired = catalogue.retired()

    def verdict(rel, number, line_no, how):
        if number in active:
            return None
        if number in retired:
            return Finding(rel, line_no, "retired-cited",
                           f"{how} {ess(number)}, which is retired; cite what replaced it (see {TRAPS} "
                           f"'{RETIRED_HEADING}')")
        return Finding(rel, line_no, "dangling", f"{how} {ess(number)}, which is not in {TRAPS}")

    for rel, path in citing_files(root):
        text = read_text(path)
        if text is None or ("ESS-" not in text and "#ess-" not in text):
            continue
        migration = re.fullmatch(r"docs/(MIGRATION-[^/]+\.md)", rel)
        for line_no, line in enumerate(text.splitlines(), 1):
            if IGNORE_MARKER in line:
                continue
            numbers = {int(n) for n in CITATION.findall(line)}
            for number in sorted(numbers):
                finding = verdict(rel, number, line_no, "cites")
                if finding:
                    findings.append(finding)
                elif migration and migration.group(1) not in active[number].href:
                    findings.append(Finding(rel, line_no, "migration-link",
                                            f"cites {ess(number)}, whose line in {TRAPS} links to "
                                            f"'{active[number].href}', not to {migration.group(1)}"))
            for number in sorted({int(n) for n in TRAPS_LINK.findall(line)} - numbers):
                finding = verdict(rel, number, line_no, "links to")
                if finding:
                    findings.append(finding)

    # Citations inside the catalogue itself (e.g. a tombstone naming its replacement), past each line's own id.
    text = (root / TRAPS).read_text(encoding="utf-8")
    for line_no, line in enumerate(text.splitlines(), 1):
        m = ID_LINE.match(line)
        body = m.group("rest") if m else line
        for number in sorted({int(n) for n in CITATION.findall(body)} | {int(n) for n in TRAPS_LINK.findall(body)}
                             | {int(n) for n in re.findall(r"\(#ess-(\d{3})\)", body)}):
            finding = verdict(TRAPS, number, line_no, "cites")
            if finding:
                findings.append(finding)
    return findings


# ---------------------------------------------------------------------------------------------------------------------
# Baseline


def check_baseline(base_text, current, ref):
    base = parse_catalogue(base_text, f"{ref}:{TRAPS}")
    base_active = {e.number for e in base.entries if not e.retired}
    base_retired = {e.number for e in base.entries if e.retired}
    now_active = set(current.active())
    now_all = now_active | set(current.retired())
    findings = []
    for number in sorted((base_active | base_retired) - now_all):
        findings.append(Finding(TRAPS, 1, "baseline-lost",
                                f"{ess(number)} exists at {ref} and is gone now; retire it with a tombstone instead"))
    for number in sorted(base_retired & now_active):
        findings.append(Finding(TRAPS, current.active()[number].line, "baseline-reused",
                                f"{ess(number)} is retired at {ref}; a retired id is never handed out again"))
    return findings


def baseline_text(root, ref):
    try:
        subprocess.run(["git", "-C", str(root), "rev-parse", "--verify", "-q", f"{ref}^{{commit}}"],
                       check=True, capture_output=True)
    except (OSError, subprocess.CalledProcessError):
        raise UsageError(f"--baseline {ref}: not a commit in {root}")
    shown = subprocess.run(["git", "-C", str(root), "show", f"{ref}:{TRAPS}"], capture_output=True)
    if shown.returncode != 0:
        return ""  # the catalogue did not exist yet at that ref: nothing to hold the current one to
    return shown.stdout.decode("utf-8")


# ---------------------------------------------------------------------------------------------------------------------


class UsageError(Exception):
    pass


def check(root, baseline=None):
    traps = root / TRAPS
    try:
        text = traps.read_text(encoding="utf-8")
    except OSError as exc:
        raise UsageError(f"cannot read {traps}: {exc}")
    catalogue = parse_catalogue(text)
    findings = list(catalogue.findings)
    findings += check_numbering(catalogue)
    findings += check_links(root, text)
    findings += check_citations(root, catalogue)
    if baseline is not None:
        findings += check_baseline(baseline_text(root, baseline), catalogue, baseline)
    return catalogue, findings


def list_catalogue(catalogue, as_json, out):
    rows = [{"id": ess(e.number), "status": "retired" if e.retired else "active", "group": e.group,
             "module": e.module, "symptom": e.symptom.strip(), "href": e.href}
            for e in sorted(catalogue.entries, key=lambda e: e.number)]
    if as_json:
        json.dump(rows, out, indent=2, ensure_ascii=False)
        out.write("\n")
    else:
        for row in rows:
            out.write("\t".join(row[k] for k in ("id", "status", "group", "module", "symptom", "href")) + "\n")


# ---------------------------------------------------------------------------------------------------------------------
# Self-test: each case is a small repository tree and the rules it must (and only it must) raise.

SELF_TEST_DOC = "# A\n\n## Gotchas\n\n## Gotchas\n\n```\n## Not a heading\n```\n"
SELF_TEST_MIGRATION = "# Migration\n\n## Thing that bites\n"


def traps_text(lines, retired=()):
    body = "\n".join(lines)
    tombstones = "\n".join(retired) if retired else "None yet."
    return (f"# Traps index\n\nIntro [Universal](#universal).\n\n---\n\n## Universal\n\n{body}\n\n"
            f"---\n\n{RETIRED_HEADING}\n\n{tombstones}\n")


def trap(number, href="LLM-a.md#gotchas", anchor=None):
    anchor = anchor if anchor is not None else f"{number:03d}"
    return f'- <a id="ess-{anchor}"></a>`ESS-{number:03d}` Symptom {number} → [LLM-a.md § Gotchas]({href})'


CLEAN = [trap(1), trap(2, "LLM-a.md#gotchas-1"), trap(3, "../docs/MIGRATION-0.60.md#thing-that-bites")]

SELF_TEST_CASES = [
    ("clean", {}, set()),
    ("clean with a tombstone and its replacement cited",
     {TRAPS: traps_text(CLEAN, [trap(4) + " — retired: split into [ESS-003](#ess-003)"])}, set()),
    ("duplicate id", {TRAPS: traps_text(CLEAN + [trap(2)])}, {"duplicate"}),
    ("gap", {TRAPS: traps_text([trap(1), trap(3, "../docs/MIGRATION-0.60.md#thing-that-bites")])}, {"gap"}),
    ("anchor differs from id", {TRAPS: traps_text([trap(1, anchor="010")] + CLEAN[1:])}, {"anchor"}),
    ("line without an id", {TRAPS: traps_text(CLEAN + ["- Symptom → [x](LLM-a.md#gotchas)"])}, {"missing-id"}),
    ("four-digit id", {TRAPS: traps_text(CLEAN + [trap(4).replace("ESS-004", "ESS-0004")])}, {"format"}),
    ("no owning link", {TRAPS: traps_text(CLEAN + ['- <a id="ess-004"></a>`ESS-004` Symptom'])}, {"format"}),
    ("retired then reused", {TRAPS: traps_text(CLEAN, [trap(3)])}, {"duplicate"}),
    ("no Retired ids section", {TRAPS: traps_text(CLEAN).split(RETIRED_HEADING)[0]}, {"retired-section"}),
    ("section after Retired ids", {TRAPS: traps_text(CLEAN) + "\n## Later\n"}, {"retired-section"}),
    ("broken link file", {TRAPS: traps_text(CLEAN + [trap(4, "LLM-missing.md#gotchas")])}, {"link"}),
    ("broken link anchor", {TRAPS: traps_text(CLEAN + [trap(4, "LLM-a.md#not-a-heading")])}, {"link"}),
    ("dangling citation in the plugin", {"essentials-plugin/commands/x.md": "Reports ESS-009.\n"}, {"dangling"}),
    ("dangling traps link in LLM/", {"LLM/LLM-b.md": "See [x](LLM-traps.md#ess-042).\n"}, {"dangling"}),
    ("dangling citation in main code",
     {"components/x/src/main/java/X.java": 'throw new IllegalStateException("ESS-077 bad");\n'}, {"dangling"}),
    ("test code is not a citer", {"components/x/src/test/java/XTest.java": "// ESS-077\n"}, set()),
    ("generated copy is not a citer", {GENERATED_COPY + "LLM-b.md": "ESS-099\n"}, set()),
    ("ignore marker", {"essentials-plugin/tests/r/neg.diff": "+ ESS-999 <!-- ess-ids: ignore -->\n"}, set()),
    ("plugin namespaces are not ESS-NNN", {"essentials-plugin/commands/x.md": "ESS-S2.1, ESS-G4b, ESS-NNN\n"}, set()),
    ("retired id cited",
     {TRAPS: traps_text(CLEAN, [trap(4) + " — retired: replaced by [ESS-003](#ess-003)"]),
      "essentials-plugin/commands/x.md": "ESS-004\n"}, {"retired-cited"}),
    ("tombstone names a missing replacement",
     {TRAPS: traps_text(CLEAN, [trap(4) + " — retired: replaced by [ESS-008](#ess-008)"])}, {"dangling", "link"}),
    ("migration cites an id linking elsewhere",
     {"docs/MIGRATION-0.60.md": SELF_TEST_MIGRATION + "\nSee ESS-001.\n"}, {"migration-link"}),
    ("migration cites its own id", {"docs/MIGRATION-0.60.md": SELF_TEST_MIGRATION + "\nSee ESS-003.\n"}, set()),
]

BASELINE_CASES = [
    ("unchanged", traps_text(CLEAN), traps_text(CLEAN), set()),
    ("id added", traps_text(CLEAN), traps_text(CLEAN + [trap(4)]), set()),
    ("id retired", traps_text(CLEAN), traps_text(CLEAN[:2], [trap(3, "../docs/MIGRATION-0.60.md#thing-that-bites")]),
     set()),
    ("id deleted with its tombstone", traps_text(CLEAN, [trap(4)]), traps_text(CLEAN), {"baseline-lost"}),
    ("tombstone dropped and id reused", traps_text(CLEAN, [trap(4)]), traps_text(CLEAN + [trap(4)]),
     {"baseline-reused"}),
    ("no catalogue at the ref", "", traps_text(CLEAN), set()),
]


def self_test(out):
    failures = 0

    def report(name, got, want):
        nonlocal failures
        if got == want:
            print(f"ok   {name}: {', '.join(sorted(want)) or 'clean'}", file=out)
        else:
            failures += 1
            print(f"FAIL {name}: expected {sorted(want) or 'clean'}, got {sorted(got) or 'clean'}", file=out)

    for name, files, want in SELF_TEST_CASES:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            tree = {TRAPS: traps_text(CLEAN), "LLM/LLM-a.md": SELF_TEST_DOC,
                    "docs/MIGRATION-0.60.md": SELF_TEST_MIGRATION}
            tree.update(files)
            for rel, content in tree.items():
                (root / rel).parent.mkdir(parents=True, exist_ok=True)
                (root / rel).write_text(content, encoding="utf-8")
            _, findings = check(root)
            report(name, {f.rule for f in findings}, want)
    for name, base, current, want in BASELINE_CASES:
        findings = check_baseline(base, parse_catalogue(current), "BASE")
        report(f"baseline: {name}", {f.rule for f in findings}, want)
    return 1 if failures else 0


def main(argv=None):
    parser = argparse.ArgumentParser(description="Check the ESS-NNN ids on LLM/LLM-traps.md and every citation of them.")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent,
                        help="repository root (default: the parent of this script's directory)")
    parser.add_argument("--baseline", metavar="REF",
                        help="also hold the catalogue to the one committed at this git ref")
    parser.add_argument("--self-test", action="store_true", help="run the built-in cases and exit")
    parser.add_argument("--list", action="store_true", help="print the catalogue instead of checking")
    parser.add_argument("--json", action="store_true", help="with --list: JSON instead of tab-separated rows")
    args = parser.parse_args(argv)
    try:
        if args.self_test:
            return self_test(sys.stdout)
        root = args.root.resolve()
        if args.list:
            catalogue = parse_catalogue((root / TRAPS).read_text(encoding="utf-8"))
            list_catalogue(catalogue, args.json, sys.stdout)
            return 0
        catalogue, findings = check(root, args.baseline)
    except (UsageError, OSError) as exc:
        print(f"check-ess-ids: {exc}", file=sys.stderr)
        return 2
    for finding in findings:
        print(finding)
    active, retired = len(catalogue.active()), len(catalogue.retired())
    if findings:
        print(f"check-ess-ids: {len(findings)} finding(s) ({active} active ids, {retired} retired)", file=sys.stderr)
        return 1
    print(f"check-ess-ids: {active} active ids, {retired} retired, every citation resolves")
    return 0


if __name__ == "__main__":
    sys.exit(main())
