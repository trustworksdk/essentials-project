#!/usr/bin/env python3
"""Mechanical checks for a GITHUB-RELEASE-<version>.md written by the github-release skill.

Usage (from the repository root):
    python3 -I .claude/skills/github-release/check.py GITHUB-RELEASE-0.60.0.md 0.60.0 \
        docs/RELEASE-NOTES-0.60.0.md docs/MIGRATION-0.60.md docs/MIGRATION-NEXT_MAJOR.md

Checks:
  links    every link is absolute; every link into this repository is pinned to the release tag,
           names a file that exists, and names an anchor that exists in that file (GitHub slug rules)
  spans    every inline code span appears verbatim in at least one source document
Exit code 1 when a link check fails. Untraced code spans are reported but do not fail the run:
a span such as a property glob may be composed on purpose - confirm each one by hand.
"""
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

LINK = re.compile(r"\[[^\]]*\]\(([^)\s]+)\)")
SPAN = re.compile(r"(?<!`)`([^`\n]+)`(?!`)")
FENCE = re.compile(r"^\s*(```|~~~)")


def strip_fences(text):
    out, inside = [], False
    for line in text.splitlines():
        if FENCE.match(line):
            inside = not inside
            continue
        if not inside:
            out.append(line)
    return "\n".join(out)


def github_slug(heading):
    heading = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading)  # keep link text only
    kept = []
    for ch in heading.strip().lower():
        if ch in "-_ " or unicodedata.category(ch)[0] in "LNM":
            kept.append(ch)
    return "".join(kept).replace(" ", "-")


def anchors(path):
    seen, result = {}, set()
    for line in strip_fences(path.read_text(encoding="utf-8")).splitlines():
        m = re.match(r"^#{1,6}\s+(.*?)\s*#*\s*$", line)
        if not m:
            continue
        slug = github_slug(m.group(1))
        n = seen.get(slug, 0)
        seen[slug] = n + 1
        result.add(slug if n == 0 else f"{slug}-{n}")
    return result


def repo_url():
    url = subprocess.run(["git", "remote", "get-url", "origin"], capture_output=True, text=True).stdout.strip()
    return re.sub(r"\.git$", "", url.replace("git@github.com:", "https://github.com/"))


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    release, version, sources = Path(sys.argv[1]), sys.argv[2], [Path(p) for p in sys.argv[3:]]
    text = release.read_text(encoding="utf-8")
    base = repo_url()
    failures, warnings = [], []

    for url in LINK.findall(text):
        if not url.startswith(("http://", "https://")):
            failures.append(f"relative link (does not resolve in a release body): {url}")
            continue
        if not url.startswith(base + "/"):
            continue  # external link - not checked
        m = re.match(re.escape(base) + r"/(?:blob|tree)/([^/]+)/([^#]+)(?:#(.+))?$", url)
        if not m:
            continue  # e.g. /releases, /compare
        ref, path, anchor = m.groups()
        if ref != version:
            failures.append(f"link pinned to '{ref}', not the tag '{version}': {url}")
        target = Path(path)
        if not target.exists():
            failures.append(f"link to a file that does not exist: {url}")
            continue
        if anchor and target.suffix == ".md" and anchor not in anchors(target):
            failures.append(f"anchor '#{anchor}' not found in {path}: {url}")

    corpus = "\n".join(p.read_text(encoding="utf-8") for p in sources)
    for span in sorted(set(SPAN.findall(strip_fences(text)))):
        if span not in corpus:
            warnings.append(span)

    for f in failures:
        print(f"FAIL  {f}")
    for w in warnings:
        print(f"CHECK code span not found verbatim in any source: `{w}`")
    print(f"{len(failures)} link failure(s), {len(warnings)} untraced code span(s)")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
