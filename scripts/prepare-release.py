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
"""prepare-release — the mechanical edits of an Essentials release, and the check that none is left undone.

Why this exists
---------------
A release touches a dozen files that must agree: the release notes converted from
docs/NEXT_VERSION_RELEASE.md, that file reset, the plugin version and its CHANGELOG heading, the
`essentials.version` pin, the migration notes the plugin's /essentials:upgrade reads, every golden that holds
the pin, the generated references/llm copy, and the "since <next patch>" markers the docs wrote before the
version was decided. Each is mechanical; forgetting one is silent. This script makes the edits and
`--check` fails on any left inconsistent. Judgement (the intro paragraph, the upgrade steps, the README
compatibility table, the roadmap) stays with the prepare-release skill (.claude/skills/prepare-release/).

It never touches the root POM's `<revision>`: it stays `DEV-SNAPSHOT`, and the release workflow sets the
version from the GitHub release tag. It never commits, tags or pushes.

What `prepare-release.py VERSION` does, in order
------------------------------------------------
 1. Finds the previous release: the highest X.Y.Z tag reachable from HEAD. VERSION must be above it.
 2. Converts docs/NEXT_VERSION_RELEASE.md to docs/RELEASE-NOTES-VERSION.md: release title, a "Covers
    everything since" blurb from git, the "So far" summary as a draft intro under a marker `--check`
    rejects, a regenerated table of contents, and the sections without their empty ones. Then resets
    NEXT_VERSION_RELEASE.md to an empty skeleton derived from its own headings. Skipped when the release
    notes already exist.
 3. When VERSION is not the previous tag's next patch (the marker the docs use for unreleased behaviour),
    rewrites that marker to VERSION in LLM/, every README.md and CLAUDE.md, docs/*.md except release notes
    and migration guides, and *.java/*.kt. Never in essentials-plugin/ (its references/llm is regenerated
    below) and never in history.
 4. plugin.json `version` and the essentials.version pin in stack-pins.md become VERSION.
 5. CHANGELOG.md: an unreleased top section is renamed `## VERSION — targets Essentials VERSION`; a
    released one gets a new section above it. Unreleased means its version differs from plugin.json at
    origin/main, which is fetched first; when the fetch fails, say which with --plugin-section rename|new.
 6. Writes the migration notes skeleton essentials-plugin/references/stack/migrations/<prev m.m>-to-<m.m>.json
    citing the new notes, with a `_todo` key `--check` rejects until the skill has filled the steps.
 7. Moves the tests/migration-plan/already-there fixture to VERSION (it stands for a project on the pin),
    then runs the regenerators that read the pin: scripts/sync-plugin-llm.sh,
    init-render.py --update-golden and migration-plan.py --self-test --update-golden.
 8. Prints what it changed and what still needs a decision.

`--check VERSION` changes nothing and exits 1 when anything above is missing or inconsistent.

Usage
-----
    scripts/prepare-release.py 0.60.1 [--plugin-section rename|new]
    scripts/prepare-release.py --check 0.60.1
    scripts/prepare-release.py --self-test

Exit codes: 0 done / consistent · 1 --check found a problem, or a regenerator failed · 2 could not run.
Standard library only; Python 3.11+. Run from anywhere inside the repository.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NEXT = Path("docs/NEXT_VERSION_RELEASE.md")
PLUGIN_JSON = Path("essentials-plugin/.claude-plugin/plugin.json")
CHANGELOG = Path("essentials-plugin/CHANGELOG.md")
PINS = Path("essentials-plugin/references/stack/stack-pins.md")
MIGRATIONS = Path("essentials-plugin/references/stack/migrations")
ALREADY_THERE = Path("essentials-plugin/tests/migration-plan/already-there/pom.xml")
LLM_SRC, LLM_DST = Path("LLM"), Path("essentials-plugin/references/llm")
# Same link rewrite as scripts/sync-plugin-llm.sh (rewrite_links); --check compares against it.
LLM_LINK_BASE = "https://github.com/trustworksdk/essentials-project/blob/{pin}/"

TODO = "<!-- prepare-release:"
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)$")
PIN_ROW = re.compile(r"^(\| `essentials\.version` \| \*\*)([^*]+)(\*\*.*)$", re.M)
CHANGELOG_HEADING = re.compile(r"^## (\S+) — targets Essentials (\S+)\s*$", re.M)
FENCE = re.compile(r"^\s*(```|~~~)")
NONE_SO_FAR = "None so far."


class UsageError(Exception):
    pass


# ---- small helpers ----------------------------------------------------------------------------------------------

def git(*args: str, check: bool = True) -> str:
    r = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)
    if check and r.returncode != 0:
        raise UsageError(f"git {' '.join(args)}: {r.stderr.strip()}")
    return r.stdout if r.returncode == 0 else ""


def read(p: Path) -> str:
    return (ROOT / p).read_text(encoding="utf-8")


def write(p: Path, text: str, changed: list[str]) -> None:
    target = ROOT / p
    if target.is_file() and target.read_text(encoding="utf-8") == text:
        return
    target.write_text(text, encoding="utf-8")
    changed.append(p.as_posix())


def vtuple(v: str) -> tuple[int, int, int]:
    m = SEMVER.match(v)
    if not m:
        raise UsageError(f"{v!r} is not a release version X.Y.Z")
    major, minor, patch = (int(x) for x in m.groups())
    return major, minor, patch


def major_minor(v: str) -> str:
    return ".".join(v.split(".")[:2])


def version_re(v: str) -> re.Pattern:
    """`v` as a whole version: not inside 10.60.1, 0.60.10, 0.60.1.2 or 0.60.1-3."""
    return re.compile(r"(?<![\w.])" + re.escape(v) + r"(?!\d|\.\d|-\d)")


def previous_tag(v: str) -> str:
    tags = [t for t in git("tag", "--merged", "HEAD").split() if SEMVER.match(t)]
    if not tags:
        raise UsageError("no X.Y.Z release tag is reachable from HEAD")
    prev = max(tags, key=vtuple)
    if vtuple(v) <= vtuple(prev):
        raise UsageError(f"{v} is not above the previous release tag {prev}")
    return prev


def next_patch(v: str) -> str:
    a, b, c = vtuple(v)
    return f"{a}.{b}.{c + 1}"


def revision() -> str | None:
    m = re.search(r"<revision>([^<]+)</revision>", read(Path("pom.xml")))
    return m.group(1) if m else None


def plugin_version() -> str | None:
    return json.loads(read(PLUGIN_JSON)).get("version")


def pin() -> str | None:
    m = PIN_ROW.search(read(PINS))
    return m.group(2) if m else None


def origin_plugin_version() -> str | None:
    text = git("show", f"origin/main:{PLUGIN_JSON.as_posix()}", check=False)
    return json.loads(text).get("version") if text else None


def fetch_origin_main() -> None:
    """Brings origin/main up to date before the CHANGELOG decision reads plugin.json there. A stale ref (a plugin
    release landed since the last fetch) makes the released top section look unreleased, and `rename` would then
    rewrite a published heading that `--check` cannot tell from a right one."""
    try:
        r = subprocess.run(["git", "fetch", "--quiet", "origin", "+refs/heads/main:refs/remotes/origin/main"], cwd=ROOT, capture_output=True, text=True,
                           timeout=60, env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
        error = ((r.stderr.strip().splitlines() or [f"exit {r.returncode}"])[0]) if r.returncode != 0 else ""
    except subprocess.TimeoutExpired:
        error = "timed out after 60 s"
    if error:
        raise UsageError(f"git fetch origin main failed ({error}); whether the CHANGELOG's top section was released "
                         "is read from origin/main, so it must be current: fetch it, or pass --plugin-section rename|new")


def plugin_section_for(top: str, v: str, released: str | None) -> str:
    """done: the top section already is v · new: it is the release origin/main ships, so v goes above it · rename: it
    was never released, so it becomes v."""
    if top == v:
        return "done"
    if released is None:
        raise UsageError("origin/main is not available to tell whether the CHANGELOG's top section "
                         f"({top}) was released; pass --plugin-section rename|new")
    return "new" if top == released else "rename"


# ---- markdown structure -----------------------------------------------------------------------------------------

def github_slug(heading: str) -> str:
    """GitHub's anchor for a heading (same rules as .claude/skills/github-release/check.py)."""
    heading = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading)
    kept = [ch for ch in heading.strip().lower() if ch in "-_ " or unicodedata.category(ch)[0] in "LNM"]
    return "".join(kept).replace(" ", "-")


def anchors(text: str) -> set[str]:
    seen: dict[str, int] = {}
    out = set()
    for _level, title in headings(text):
        slug = github_slug(title)
        n = seen.get(slug, 0)
        seen[slug] = n + 1
        out.add(slug if n == 0 else f"{slug}-{n}")
    return out


def headings(text: str) -> list[tuple[int, str]]:
    out, inside = [], False
    for line in text.splitlines():
        if FENCE.match(line):
            inside = not inside
        elif not inside and (m := re.match(r"^(#{1,6})\s+(.*?)\s*#*\s*$", line)):
            out.append((len(m.group(1)), m.group(2)))
    return out


def split_at(lines: list[str], prefix: str) -> tuple[list[str], list[tuple[str, list[str]]]]:
    """Lines before the first `prefix` heading, then (heading text, body lines) per heading; fence-aware."""
    head: list[str] = []
    parts: list[tuple[str, list[str]]] = []
    inside = False
    for line in lines:
        if FENCE.match(line):
            inside = not inside
        if not inside and line.startswith(prefix) and not line.startswith(prefix + "#"):
            parts.append((line[len(prefix):].strip(), []))
        elif parts:
            parts[-1][1].append(line)
        else:
            head.append(line)
    return head, parts


def paragraphs(lines: list[str]) -> list[list[str]]:
    """Blank-line separated blocks, without the `---` rules; a fenced block stays whole."""
    out: list[list[str]] = []
    cur: list[str] = []
    inside = False
    for line in lines:
        if FENCE.match(line):
            inside = not inside
        if not inside and (not line.strip() or line.strip() == "---"):
            if cur:
                out.append(cur)
                cur = []
        else:
            cur.append(line)
    if cur:
        out.append(cur)
    return out


def join_paragraphs(paras: list[list[str]]) -> list[str]:
    out: list[str] = []
    for p in paras:
        if out:
            out.append("")
        out.extend(p)
    return out


class Section:
    """A `## ` section of NEXT_VERSION_RELEASE.md: its intro paragraphs and its `### ` subsections."""

    def __init__(self, title: str, body: list[str]):
        self.title = title
        intro, subs = split_at(body, "### ")
        self.intro = paragraphs(intro)
        self.subs = [(t, paragraphs(b)) for t, b in subs]

    def is_plugin(self) -> bool:
        return "Claude Code plugin" in self.title

    def skeleton_intro(self, version: str) -> list[list[str]]:
        """What the reset file carries under this heading, derived from the section's current shape."""
        first = self.intro[0] if self.intro else []
        if first and first[0].startswith("|") and len(first) >= 2:
            return [first[:2]]  # the table header and its separator row
        if self.title.endswith("Examples") and first:
            return [first]  # a standing statement about the section, not about a release
        if self.is_plugin():
            return [[f"{version} shipped plugin `{version}`. Plugin releases that follow it are listed here, "
                     "newest first;",
                     "the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md)."]]
        return [[NONE_SO_FAR]]


def parse_next(text: str) -> tuple[list[str], list[Section]]:
    head, parts = split_at(text.splitlines(), "## ")
    return head, [Section(t, b) for t, b in parts if t.lower() != "table of contents"]


def is_placeholder(para: list[str], skeleton: list[list[str]]) -> bool:
    return para[0].startswith("None so far") or para in skeleton


def toc(entries: list[tuple[int, str]]) -> list[str]:
    out = ["## Table of contents", ""]
    for level, title in entries:
        if level == 2:
            m = re.match(r"^(\d+)\.\s+(.*)$", title)
            out.append(f"{m.group(1)}. [{m.group(2)}](#{github_slug(title)})" if m
                       else f"- [{title}](#{github_slug(title)})")
        else:
            out.append(f"   - [{title}](#{github_slug(title)})")
    return out


def render_sections(sections: list[tuple[str, list[list[str]], list[tuple[str, list[list[str]]]]]]) -> list[str]:
    out: list[str] = []
    for title, intro, subs in sections:
        out += ["---", "", f"## {title}", ""]
        body = join_paragraphs(intro)
        for st, sp in subs:
            if body:
                body.append("")
            body += [f"### {st}", ""] + join_paragraphs(sp)
        out += body + [""]
    return out


# ---- the edits --------------------------------------------------------------------------------------------------

def blurb(prev: str) -> str:
    commits = int(git("rev-list", "--count", f"{prev}..HEAD").strip())
    stat = git("diff", "--shortstat", prev, "HEAD")
    num = lambda pat: int(m.group(1)) if (m := re.search(pat, stat)) else 0
    files, added, deleted = num(r"(\d+) files? changed"), num(r"(\d+) insertions?"), num(r"(\d+) deletions?")
    short = lambda n: f"{round(n / 1000)}k" if n >= 10_000 else f"{n:,}"
    return (f"_Covers everything since `{prev}`: {commits:,} commits, {files:,} files, "
            f"+{short(added)}/−{short(deleted)} lines._")


def convert(next_text: str, prev: str, v: str, covers: str) -> tuple[str, str]:
    """(release notes, reset NEXT_VERSION_RELEASE.md); `covers` is the "Covers everything since" line (blurb)."""
    head, sections = parse_next(next_text)
    so_far = next((p for p in paragraphs(head) if p[0].startswith("**So far:**")), None)

    kept = []
    for s in sections:
        skel = s.skeleton_intro(prev)
        subs = [(t, [p for p in ps if not p[0].startswith("None so far")]) for t, ps in s.subs]
        subs = [(t, ps) for t, ps in subs if ps]
        intro_has_content = any(not is_placeholder(p, skel) for p in s.intro)
        if not intro_has_content and not subs:
            continue  # an empty section is dropped, with its table-of-contents line
        kept.append((s.title, [p for p in s.intro if not p[0].startswith("None so far")], subs))

    draft = [re.sub(r"^\*\*So far:\*\*\s*", "", so_far[0])] + so_far[1:] if so_far else []
    notes = [f"# Essentials {v} — Release Notes", "", covers, "",
             f"{TODO} replace this comment and the draft below with the release's intro paragraph: what it is, "
             "what a user must do, whether the persisted format changes -->", ""]
    notes += draft + ["", "---", ""]
    notes += toc([e for t, _, subs in kept for e in [(2, t)] + [(3, st) for st, _ in subs]])
    notes += [""] + render_sections(kept)

    # The reset file: the same header with the previous tag moved to v, an empty "So far", empty sections.
    reset: list[str] = []
    for p in paragraphs(head):
        if p[0].startswith("# "):
            reset += p
        elif p[0].startswith("**So far:**"):
            reset.append("**So far:** nothing yet.")
        else:
            reset += [version_re(prev).sub(v, line) for line in p]
        reset.append("")
    reset += ["---", ""]
    skel_sections = [(s.title, s.skeleton_intro(v), [(t, [[NONE_SO_FAR]]) for t, _ in s.subs]) for s in sections]
    reset += toc([e for t, _, subs in skel_sections for e in [(2, t)] + [(3, st) for st, _ in subs]])
    reset += [""] + render_sections(skel_sections)
    return "\n".join(notes).rstrip() + "\n", "\n".join(reset).rstrip() + "\n"


def marker_scope() -> list[Path]:
    out = []
    for f in git("ls-files").splitlines():
        p = Path(f)
        if f.startswith("essentials-plugin/"):
            continue
        if (f.startswith("LLM/") or p.name in ("README.md", "CLAUDE.md") or p.suffix in (".java", ".kt")
                or (f.startswith("docs/") and p.suffix == ".md"
                    and not re.match(r"(RELEASE-NOTES-|MIGRATION-|NEXT_VERSION_RELEASE)", p.name))):
            out.append(p)
    return out


def marker_hits(marker: str) -> list[Path]:
    pat = version_re(marker)
    return [p for p in marker_scope() if (ROOT / p).is_file() and pat.search(read(p))]


def migrations_skeleton(prev: str, v: str, notes: str) -> dict:
    notes_path = f"docs/RELEASE-NOTES-{v}.md"
    lines = notes.splitlines()
    guide_line, guide_token = 1, f"Essentials {v}"
    for i, line in enumerate(lines, 1):
        if line.startswith("## 1. "):
            guide_line, guide_token = i, line[len("## 1. "):].strip()
            break
    title = {"path": notes_path, "line": 1, "token": f"Essentials {v}"}
    return {
        "_todo": "prepare-release: one step or checklist item per §1 entry of the notes, then delete this key",
        "from": prev,
        "to": v,
        "guide": {"path": notes_path, "line": guide_line, "token": guide_token},
        "requiredPins": [{"pin": "essentials.version", "tolerated": [], "reason": "the release itself", "cite": title}],
        "steps": [{"id": "pins", "kind": "pins", "title": f"Move essentials.version to {v}", "cites": [title]}],
        "checklist": [],
        "fixedIn": [],
    }


def run_tool(cmd: list[str], failures: list[str]) -> None:
    print(f"  $ {' '.join(cmd)}")
    r = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True)
    tail = (r.stdout + r.stderr).strip().splitlines()[-6:]
    for line in tail:
        print(f"    {line}")
    if r.returncode != 0:
        failures.append(f"{' '.join(cmd)} exited {r.returncode}")


def prepare(v: str, plugin_section: str | None) -> int:
    prev = previous_tag(v)
    marker = next_patch(prev)
    if revision() != "DEV-SNAPSHOT":
        raise UsageError(f"root pom.xml <revision> is {revision()!r}, expected DEV-SNAPSHOT")
    top = CHANGELOG_HEADING.search(read(CHANGELOG))
    if not top:
        raise UsageError(f"{CHANGELOG}: no `## <version> — targets Essentials <pin>` heading")
    if plugin_section is None and top.group(1) != v:
        fetch_origin_main()
    released = origin_plugin_version()
    if plugin_section is None:
        plugin_section = plugin_section_for(top.group(1), v, released)
        origin = git("log", "-1", "--format=%h, %cs", "origin/main", check=False).strip() or "absent"
        print(f"CHANGELOG: top section {top.group(1)}, origin/main ({origin}) ships plugin {released}: {plugin_section}")

    changed: list[str] = []
    notes_path = Path(f"docs/RELEASE-NOTES-{v}.md")
    print(f"Release {v}; previous tag {prev}; unreleased-behaviour marker {marker}")

    # 2. release notes and the reset working draft
    if (ROOT / notes_path).is_file():
        print(f"- {notes_path} exists: conversion and reset skipped")
        notes = read(notes_path)
    else:
        next_text = read(NEXT)
        if marker != v:
            next_text = version_re(marker).sub(v, next_text)
        notes, reset = convert(next_text, prev, v, blurb(prev))
        write(notes_path, notes, changed)
        write(NEXT, reset, changed)

    # 3. the "since <next patch>" marker
    if marker != v:
        pat = version_re(marker)
        for p in marker_hits(marker):
            write(p, pat.sub(v, read(p)), changed)

    # 4. plugin version and the pin
    write(PLUGIN_JSON, re.sub(r'("version":\s*")[^"]*(")', rf"\g<1>{v}\g<2>", read(PLUGIN_JSON), count=1), changed)
    write(PINS, PIN_ROW.sub(rf"\g<1>{v}\g<3>", read(PINS), count=1), changed)

    # 5. CHANGELOG
    text = read(CHANGELOG)
    heading = f"## {v} — targets Essentials {v}"
    unreleased = []
    for m in CHANGELOG_HEADING.finditer(text):
        if m.group(1) == released or m.group(1) == v:
            break
        unreleased.append(m.group(1))
    if plugin_section == "rename":
        text = text[:top.start()] + heading + text[top.end():]
    elif plugin_section == "new":
        text = (text[:top.start()] + heading + "\n"
                f"- {TODO} what this release changes for a plugin user; at least that it targets Essentials {v} -->\n\n"
                + text[top.start():])
    write(CHANGELOG, text, changed)

    # 6. migration notes skeleton
    mig = MIGRATIONS / f"{major_minor(prev)}-to-{major_minor(v)}.json"
    if (ROOT / mig).is_file():
        print(f"- {mig} exists: left as it is")
    else:
        write(mig, json.dumps(migrations_skeleton(prev, v, notes), indent=2, ensure_ascii=False) + "\n", changed)

    # 7. the fixture that stands for a project already on the pin, then the regenerators
    if (ROOT / ALREADY_THERE).is_file():
        write(ALREADY_THERE, re.sub(r"(<artifactId>spring-boot-starter-postgresql</artifactId>\s*<version>)[^<]+",
                                    rf"\g<1>{v}", read(ALREADY_THERE)), changed)
    before = set(git("status", "--porcelain").splitlines())
    print("Regenerating what reads the pin:")
    failures: list[str] = []
    run_tool(["sh", "scripts/sync-plugin-llm.sh"], failures)
    run_tool([sys.executable, "essentials-plugin/scripts/init-render.py", "--update-golden"], failures)
    run_tool([sys.executable, "essentials-plugin/scripts/migration-plan.py", "--self-test", "--update-golden"], failures)
    after = set(git("status", "--porcelain").splitlines())

    # 8. report
    print("\nChanged by this script:")
    for c in changed:
        print(f"  {c}")
    print("Changed by the regenerators (review the diff; a golden is checked by reading it):")
    regen = sorted({line[3:] for line in after - before} - set(changed))
    print("\n".join(f"  {r}" for r in regen) or "  (nothing)")
    print("\nStill needs a decision (prepare-release skill):")
    if not (ROOT / notes_path).is_file() or TODO in read(notes_path):
        print(f"  - {notes_path}: write the intro paragraph; add an Upgrade order section if the release needs one;"
              " reconcile §4 with the CHANGELOG heading")
    print(f"  - {mig}: fill steps/checklist from §1 of the notes, delete `_todo`")
    if plugin_section == "new":
        print(f"  - {CHANGELOG}: write the entry under `{heading}`")
    if len(unreleased) > 1 or (unreleased and plugin_section == "new"):
        print(f"  - {CHANGELOG}: sections {', '.join(unreleased)} are newer than origin/main's {released}, so never "
              f"released; decide whether they fold into {v}")
    prose = Path("essentials-plugin/references/stack")
    for f in sorted((ROOT / prose).glob("*.md")):
        if f.name == PINS.name:
            continue
        for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            if version_re(prev).search(line):
                print(f"  - {f.relative_to(ROOT)}:{i} names {prev}: {line.strip()[:110]}")
    if failures:
        print("\nRegenerator failures:\n" + "\n".join(f"  {x}" for x in failures))
        return 1
    print(f"\nThen: scripts/prepare-release.py --check {v}")
    return 0


# ---- --check ----------------------------------------------------------------------------------------------------

def check(v: str) -> int:
    prev = previous_tag(v)
    marker = next_patch(prev)
    problems: list[str] = []
    warnings: list[str] = []

    if revision() != "DEV-SNAPSHOT":
        problems.append(f"pom.xml <revision> is {revision()!r}; it stays DEV-SNAPSHOT (the workflow sets the version)")

    notes_path = Path(f"docs/RELEASE-NOTES-{v}.md")
    if not (ROOT / notes_path).is_file():
        problems.append(f"{notes_path} does not exist")
    else:
        notes = read(notes_path)
        lines = notes.splitlines()
        if not lines or lines[0] != f"# Essentials {v} — Release Notes":
            problems.append(f"{notes_path}:1 is not `# Essentials {v} — Release Notes`")
        if f"_Covers everything since `{prev}`" not in notes:
            problems.append(f"{notes_path}: no `_Covers everything since `{prev}`…` blurb")
        for i, line in enumerate(lines, 1):
            if TODO in line:
                problems.append(f"{notes_path}:{i}: unresolved prepare-release marker")
            if line.startswith("None so far") or "**So far:**" in line or "(unreleased)" in line:
                problems.append(f"{notes_path}:{i}: working-draft wording left: {line.strip()[:80]}")
            if marker != v and version_re(marker).search(line):
                problems.append(f"{notes_path}:{i}: names {marker}, which is not this release")
        have = anchors(notes)
        for i, line in enumerate(lines, 1):
            for a in re.findall(r"\]\(#([^)\s]+)\)", line):
                if a not in have:
                    problems.append(f"{notes_path}:{i}: link to #{a}, which no heading produces")
        body = [t for lvl, t in headings(notes) if lvl == 2 and t.lower() != "table of contents"]
        toc_text = notes.split("## Table of contents", 1)[1].split("\n---", 1)[0] if "## Table of contents" in notes else ""
        if not toc_text:
            problems.append(f"{notes_path}: no table of contents")
        for t in body:
            if f"(#{github_slug(t)})" not in toc_text:
                problems.append(f"{notes_path}: section `{t}` is not in the table of contents")
        nums = [int(m.group(1)) for t in body if (m := re.match(r"^(\d+)\.", t))]
        if nums != list(range(1, len(nums) + 1)):
            problems.append(f"{notes_path}: sections are numbered {nums}; renumber them and the § references")

    next_text = read(NEXT)
    _head, sections = parse_next(next_text)
    if f"**`{v}`**" not in next_text:
        problems.append(f"{NEXT}: does not name `{v}` as the last release tag (not reset)")
    for s in sections:
        skel = s.skeleton_intro(v)
        if any(not is_placeholder(p, skel) for p in s.intro) or any(
                not p[0].startswith("None so far") for _, ps in s.subs for p in ps):
            problems.append(f"{NEXT}: section `{s.title}` still has entries (not reset)")

    if plugin_version() != v:
        problems.append(f"{PLUGIN_JSON}: version is {plugin_version()!r}, not {v!r}")
    if pin() != v:
        problems.append(f"{PINS}: essentials.version is {pin()!r}, not {v!r}")
    cl = read(CHANGELOG)
    top = CHANGELOG_HEADING.search(cl)
    if not top or top.group(0).strip() != f"## {v} — targets Essentials {v}":
        problems.append(f"{CHANGELOG}: top heading is {top.group(0).strip() if top else None!r}, "
                        f"not `## {v} — targets Essentials {v}`")
    elif TODO in cl[top.end():(m.start() if (m := CHANGELOG_HEADING.search(cl, top.end())) else len(cl))]:
        problems.append(f"{CHANGELOG}: the {v} section still has its prepare-release marker")

    mig = MIGRATIONS / f"{major_minor(prev)}-to-{major_minor(v)}.json"
    if not (ROOT / mig).is_file():
        problems.append(f"{mig} does not exist")
    else:
        doc = json.loads(read(mig))
        if doc.get("from") != prev or doc.get("to") != v:
            problems.append(f"{mig}: from/to are {doc.get('from')}/{doc.get('to')}, not {prev}/{v}")
        if "_todo" in doc:
            problems.append(f"{mig}: `_todo` is still there (steps not filled from §1 of the notes)")
    for cmd in (["essentials-plugin/scripts/migration-plan.py", "--self-test"],
                ["essentials-plugin/scripts/init-render.py", "--check"]):
        r = subprocess.run([sys.executable, *cmd], cwd=ROOT, capture_output=True, text=True)
        if r.returncode != 0:
            tail = " | ".join((r.stdout + r.stderr).strip().splitlines()[-4:])
            problems.append(f"{' '.join(cmd)} exited {r.returncode}: {tail}")

    if marker != v:
        for p in marker_hits(marker):
            problems.append(f"{p}: still names {marker} (re-run without --check)")
        rest = [f for f in git("grep", "-l", "-F", marker, check=False).splitlines()
                if not re.search(r"(RELEASE-NOTES-|MIGRATION-|CHANGELOG|GITHUB-RELEASE-|references/llm/)", f)
                and Path(f) not in marker_scope()]
        for f in rest:
            warnings.append(f"{f}: names {marker}, outside what this script rewrites; check by hand")

    if pin():
        base = LLM_LINK_BASE.format(pin=pin())
        for src in sorted((ROOT / LLM_SRC).rglob("*")):
            if src.is_file():
                dst = ROOT / LLM_DST / src.relative_to(ROOT / LLM_SRC)
                want = src.read_text(encoding="utf-8").replace("](../", "](" + base)
                if not dst.is_file() or dst.read_text(encoding="utf-8") != want:
                    problems.append(f"{dst.relative_to(ROOT)}: out of step with LLM/ (run scripts/sync-plugin-llm.sh)")

    for w in warnings:
        print(f"note: {w}")
    for p in problems:
        print(f"FAIL {p}")
    print(f"prepare-release --check {v}: {len(problems)} problem(s)")
    return 1 if problems else 0


# ---- --self-test ------------------------------------------------------------------------------------------------
# convert() runs once per release and rewrites the release's source of truth, so its rules are pinned here rather than
# discovered on release day: a fixture whose expected notes and reset file are spelled out in full, the version
# matcher behind the marker rewrite, the CHANGELOG decision, and the real docs/NEXT_VERSION_RELEASE.md converted in
# memory (nothing is written, nothing is fetched). The fixtures use 9.8.x, a version no release will have, so
# --check's sweep for a stray unreleased-behaviour marker never finds this file.

SELF_TEST_NEXT = """\
# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`9.8.0`**, that a user would notice._

**So far:** one breaking change (§1.1)
and a bug fix (§3).

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
2. [New features](#2-new-features)

---

## 1. Breaking — what you MUST apply

None so far.

### 1.1 ⚠️ Silent behaviour changes

The queue redelivers, as 9.8.1 promised:

```text
## not a heading
```

---

## 2. New features

None so far.

---

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|
| Queues | No longer dead-letters | `abc1234` |

---

## 4. The `essentials` Claude Code plugin

9.8.0 shipped plugin `9.8.0`. Plugin releases that follow it are listed here, newest first;
the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

**`9.8.0-1`**

- The Slice Atlas.

---

## 5. Examples

Not published; listed because they are the reference for how to use the framework.

---

## 6. Documentation

| Doc | Change |
|---|---|
"""

# Covers: "None so far" intro dropped under content (§1); a fenced `## ` line that does not split; the marker 9.8.1
# rewritten in content; an empty section dropped with its TOC line (§2); a table kept with its rows, reset to header
# and separator (§3); the plugin section kept, its skeleton regenerated for the new version (§4); Examples, standing
# text only, dropped from the notes and kept in the reset (§5); a header-only table section dropped (§6); the
# two-line "So far" paragraph as the draft intro.
SELF_TEST_NOTES = """\
# Essentials 9.8.2 — Release Notes

_Covers everything since `9.8.0`._

<!-- prepare-release: replace this comment and the draft below with the release's intro paragraph: what it is, what a user must do, whether the persisted format changes -->

one breaking change (§1.1)
and a bug fix (§3).

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
   - [1.1 ⚠️ Silent behaviour changes](#11-️-silent-behaviour-changes)
3. [Bug fixes](#3-bug-fixes)
4. [The `essentials` Claude Code plugin](#4-the-essentials-claude-code-plugin)

---

## 1. Breaking — what you MUST apply

### 1.1 ⚠️ Silent behaviour changes

The queue redelivers, as 9.8.2 promised:

```text
## not a heading
```

---

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|
| Queues | No longer dead-letters | `abc1234` |

---

## 4. The `essentials` Claude Code plugin

9.8.0 shipped plugin `9.8.0`. Plugin releases that follow it are listed here, newest first;
the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

**`9.8.0-1`**

- The Slice Atlas.
"""

SELF_TEST_RESET = """\
# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`9.8.2`**, that a user would notice._

**So far:** nothing yet.

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
   - [1.1 ⚠️ Silent behaviour changes](#11-️-silent-behaviour-changes)
2. [New features](#2-new-features)
3. [Bug fixes](#3-bug-fixes)
4. [The `essentials` Claude Code plugin](#4-the-essentials-claude-code-plugin)
5. [Examples](#5-examples)
6. [Documentation](#6-documentation)

---

## 1. Breaking — what you MUST apply

None so far.

### 1.1 ⚠️ Silent behaviour changes

None so far.

---

## 2. New features

None so far.

---

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|

---

## 4. The `essentials` Claude Code plugin

9.8.2 shipped plugin `9.8.2`. Plugin releases that follow it are listed here, newest first;
the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

---

## 5. Examples

Not published; listed because they are the reference for how to use the framework.

---

## 6. Documentation

| Doc | Change |
|---|---|
"""

# (version, text, whole-version match expected)
VERSION_RE_CASES = [
    ("9.8.2", "(9.8.2)", True),
    ("9.8.2", "since 9.8.2.", True),
    ("9.8.2", "`9.8.2`", True),
    ("9.8.2", "19.8.2", False),
    ("9.8.2", "9.8.21", False),
    ("9.8.2", "9.8.2.1", False),
    ("9.8.2", "9.8.2-3", False),
]

# (CHANGELOG top section, release, plugin version on origin/main, decision; None = UsageError)
PLUGIN_SECTION_CASES = [
    ("9.8.2", "9.8.2", "9.8.0-7", "done"),
    ("9.8.0-7", "9.8.2", "9.8.0-7", "new"),
    ("9.8.0-8", "9.8.2", "9.8.0-7", "rename"),
    ("9.8.0-8", "9.8.2", None, None),
]


def self_test_real_next(text: str) -> list[str]:
    """Problems converting the real working draft: lost content, or a reset that is not a fixed point."""
    m = re.search(r"\*\*`(\d+\.\d+\.\d+)`\*\*", text)
    if not m:
        return [f"{NEXT}: no **`X.Y.Z`** last release tag"]
    prev = m.group(1)
    v = next_patch(prev)
    problems: list[str] = []
    notes, reset = convert(text, prev, v, "_Covers_")
    have = set(notes.splitlines())
    _head, sections = parse_next(text)
    for s in sections:
        skel = s.skeleton_intro(prev)
        paras = [p for p in s.intro if not is_placeholder(p, skel)]
        paras += [p for _, ps in s.subs for p in ps if not p[0].startswith("None so far")]
        lost = [line for p in paras for line in p if line not in have]
        if lost:
            problems.append(f"section `{s.title}`: {len(lost)} line(s) missing from the notes, first: {lost[0][:80]}")
    reset_titles = {t for _lvl, t in headings(reset)}
    for s in sections:
        for t in [s.title] + [st for st, _ in s.subs]:
            if t not in reset_titles:
                problems.append(f"reset lost the heading `{t}`")
    v2 = next_patch(v)
    notes2, reset2 = convert(reset, v, v2, "_Covers_")
    kept = [t for lvl, t in headings(notes2) if lvl == 2 and t.lower() != "table of contents"]
    if kept:
        problems.append(f"converting the reset file keeps sections {kept}; a reset section must read as empty")
    if reset2 != version_re(v).sub(v2, reset):
        problems.append("resetting the reset file changes more than the version: the skeleton is not stable")
    return problems


def self_test() -> int:
    failures = 0

    def report(name: str, problems: list[str]) -> None:
        nonlocal failures
        if not problems:
            print(f"ok   {name}")
            return
        failures += 1
        print(f"FAIL {name}")
        for problem in problems:
            print(f"     {problem}")

    def diff(name: str, got: str, want: str) -> list[str]:
        if got == want:
            return []
        got_lines, want_lines = got.splitlines(), want.splitlines()
        for i, (g, w) in enumerate(zip(got_lines, want_lines), 1):
            if g != w:
                return [f"{name} line {i}: got {g!r}, want {w!r}"]
        return [f"{name}: {len(got_lines)} lines, want {len(want_lines)}"]

    marker, prev, v = "9.8.1", "9.8.0", "9.8.2"
    notes, reset = convert(version_re(marker).sub(v, SELF_TEST_NEXT), prev, v, f"_Covers everything since `{prev}`._")
    report("convert: release notes", diff("notes", notes, SELF_TEST_NOTES))
    report("convert: reset NEXT_VERSION_RELEASE.md", diff("reset", reset, SELF_TEST_RESET))
    for ver, text, want in VERSION_RE_CASES:
        got = bool(version_re(ver).search(text))
        report(f"version_re({ver}) in {text!r}: {'matches' if want else 'no match'}",
               [] if got == want else [f"got {'a match' if got else 'no match'}"])
    for top, ver, released, want in PLUGIN_SECTION_CASES:
        try:
            got = plugin_section_for(top, ver, released)
        except UsageError:
            got = None
        report(f"CHANGELOG top {top}, release {ver}, origin/main {released}: {want or 'usage error'}",
               [] if got == want else [f"got {got or 'usage error'}"])
    report(f"{NEXT} converts without losing content, and its reset is stable", self_test_real_next(read(NEXT)))
    print(f"prepare-release --self-test: {failures} failure(s)")
    return 1 if failures else 0


def main() -> int:
    ap = argparse.ArgumentParser(description="Mechanical release preparation; see the module docstring.")
    ap.add_argument("version", nargs="?")
    ap.add_argument("--check", action="store_true", help="change nothing; exit 1 on anything inconsistent")
    ap.add_argument("--plugin-section", choices=("rename", "new"),
                    help="rename the CHANGELOG's top section, or add a new one (default: decided from origin/main)")
    ap.add_argument("--self-test", action="store_true", help="run the built-in cases and exit; changes nothing")
    args = ap.parse_args()
    if args.self_test:
        return self_test()
    if args.version is None:
        ap.error("the version is required")
    try:
        vtuple(args.version)
        return check(args.version) if args.check else prepare(args.version, args.plugin_section)
    except UsageError as exc:
        print(f"prepare-release: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
