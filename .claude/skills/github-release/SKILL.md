---
name: github-release
description: Write the GitHub release body for an Essentials release (GITHUB-RELEASE-<version>.md at the repository root) from that release's docs/RELEASE-NOTES-<version>.md and migration guides, in the house style of the previous GITHUB-RELEASE-*.md — a one-screen summary that leads with the changes that are silent at compile time and links into the full notes. Use when asked for a GitHub release, release body, release announcement or release summary for a version, or to turn release notes into one. Argument - the version, e.g. 0.60.0.
argument-hint: "<version, e.g. 0.60.0>"
---

# github-release

The full release notes (`docs/RELEASE-NOTES-<version>.md`) are the record. The GitHub release body is the page a
consumer reads first, on the releases page or in a notification, and decides from it what to read next. Its job is
triage: what can hurt me without failing, what must I change, what do I get, where are the details. It never
replaces the release notes and never says anything they do not.

The output is `GITHUB-RELEASE-<version>.md` at the repository root. It stays local and is never committed, like
every earlier one; the release is tagged once the file exists. This skill writes the file and stops. Creating the
GitHub release is outward-facing; hand the user the command, never run it.

Its links are pinned to the tag, so they resolve against the docs *as tagged*. Any fix this skill's run makes to
`docs/` (see § 5) must be committed before the tag is cut, or the anchors it links to do not exist at the tag.

## 1. Resolve the inputs

- **Version.** The argument. Without one, take the newest `docs/RELEASE-NOTES-*.md` and confirm it with the user.
- **Previous release.** The newest tag below the version (`git tag --sort=-v:refname`), used for the comparison
  table's left column. The release notes' own comparison table usually names it already.
- **Sources**, all read in full before writing:
  - `docs/RELEASE-NOTES-<version>.md` — the primary source; every claim comes from here.
  - `docs/MIGRATION-<major.minor>.md` — the per-change instructions; use it to check the *what to do* half of
    each item and to find link targets.
  - `docs/MIGRATION-NEXT_MAJOR.md`, when the release carries out the removals it lists, or deprecates members for
    the next major — link to it rather than reproducing its tables.
  - The previous release's `GITHUB-RELEASE-*.md` (the newest one at the root), as the style exemplar. Read it
    before writing; match its voice, density and section order.
- **Repository URL**: `git remote get-url origin`, with `.git` dropped.

If the release notes do not exist, stop and say so. Do not assemble a release body from commit messages.

## 2. The shape

Sections in this order, separated by `---`. Emoji appear on section headings only.

1. **Lead paragraph**, no heading, at most two sentences: what the release moves (the platform triple in bold,
   e.g. **Java 25, Spring Boot 4.1, Jackson 3 only**) and its two to four headline additions or removals.
   Then one line: `📖 **[Full release notes, with the complete migration detail →](<tag-pinned URL>)**`.
2. **Comparison table** `| | <previous> | <version> |`, copied from the release notes' table and trimmed to the
   rows a consumer acts on (platform, serialization, behaviour switches, new engines). The changed value in bold.
3. **`## ⚠️ Read this first — <N> changes that are silent at compile time`**. One sentence framing it ("Everything
   else fails loudly. These compile clean, start clean, and change runtime semantics."), then a numbered list with
   **every** change the release notes file under silent behaviour changes. Leaving one out is the worst error this
   document can make, so none is dropped for length — shorten each instead. Each item: the bold claim in one
   sentence, what concretely changes, the one thing to do or the property that restores the old behaviour, and
   a `→` link to its section. Order by blast radius: delivery semantics and lost or duplicated work first,
   timing changes last.
4. **`## 💥 Breaking changes`** — bold-lead paragraphs, one per area: platform floor (with the exact runtime
   error a too-old JVM gives), serialization, removed API (one paragraph plus a link to the per-class tables),
   per-component removals, records or DTOs that gained components (grouped into one paragraph), and database
   objects changed on first startup. A database change that runs without asking always gets its own paragraph
   with **take a backup** and any lock or downtime note.
5. **`## ✨ New features`** — one bullet each, biggest first: `**Name** — what it is, in one sentence.` Then
   whether it is on or off by default and the exact property or call that opts in, then the one fact a user gets
   wrong (a requirement, a cost, a default that reports healthy). Measured figures are quoted verbatim with their
   unit and the comparison they came from.
6. **`## 🐛 Fixes worth knowing about`** — only when the release notes have a bug-fix section. At most eight
   bullets: fixes that lost, duplicated or wrongly committed data, hung or stalled processing, or held a resource
   forever, grouped by area where several share one. Each names who was affected. Close with a link to the full
   table and its row count, e.g. "All 38 fixes →".
7. **`## 🗑️ Deprecations`** — what this release deprecates and what replaces each, as a short table or list.
   When this release carried out removals a previous release promised, say so in one sentence here too.
8. **`## 🔭 Coming in <next>`** — only what a source *commits* to, split into **Committed** and **Planned, not
   committed** when the sources make that distinction. If no source commits to anything beyond the
   deprecations, omit the section rather than writing a roadmap. Never infer one from design documents.
9. **Recommended upgrade order** — the release notes' upgrade steps compressed into one bolded-label line of
   `→`-separated steps. When the migration guide has a section for readers skipping a release (e.g. "Coming from
   before 0.50"), add one sentence pointing at it.

## 3. Style rules

- **Second person, present tense, plain verbs.** "Remove the property", "your handlers may see a different order".
- **Bold the consequence, not the noun.** **They were delivered unordered**, not **`QueueMessage.builder()`**.
- **Exact names, verbatim.** Every class, method, property, metric and value is copied from a source, in
  backticks. Never paraphrase a property name or round a number. Keep the sources' spelling (they use British
  English: *behaviour*, *flavour*).
- **Every default stated.** For anything optional say whether it is on or off and how to change it.
- **No claim without a source.** If the release notes do not say it, the release body does not either — including
  "no action needed".
- **Links are absolute and pinned to the tag**:
  `<repo URL>/blob/<version>/docs/RELEASE-NOTES-<version>.md#<anchor>`. Relative links are not resolved against
  the repository tree in a release body, and a link to `main` drifts after the release. Prefer linking a
  section of the release notes; link the migration guide or a module README where the release notes do.
- **Anchors** are GitHub slugs of the target heading, taken from the source's own table of contents or links
  where it has one. `check.py` verifies every one.
- **Length.** Budget in words (`wc -w`), not lines — each paragraph is one line. It scales with the release:
  0.50.0 (three silent changes) is about 1,100 words, 0.60.0 (eleven silent changes, 40 fixes) about 2,500. Past
  that, cut words inside items, never items from the silent-changes list.
- **No marketing.** No "we're excited", no "huge", no "blazing". Numbers make the case.

## 4. Verify

Run from the repository root:

```bash
python3 -I .claude/skills/github-release/check.py GITHUB-RELEASE-<version>.md <version> \
    docs/RELEASE-NOTES-<version>.md docs/MIGRATION-<major.minor>.md docs/MIGRATION-NEXT_MAJOR.md
```

- **`FAIL`** lines (a relative link, a link not pinned to the tag, a missing file or anchor) must reach zero.
- **`CHECK`** lines are code spans not found verbatim in any source. Each is either a typo — fix it — or a
  deliberate composite such as a property glob; confirm each by hand. Do not add a source file just to silence one.

Then check by reading, since no script can:

- Every numbered item in the source's silent-changes section has a numbered item here. Count both.
- Every number here appears in the release notes with the same unit and the same comparison.
- Every "default", "opt-in" and "off by default" matches the source.
- The comparison table's left column matches the previous release's own notes.

## 5. Report

Tell the user:

- the file path and its word count, next to the previous release body's;
- source defects found on the way (a duplicated section number, a broken table, a dangling sentence, an anchor
  that does not resolve) — report them and ask before fixing; when the user agrees, fix them, re-run `check.py`,
  and remind them the fix must be committed before the tag;
- the command to publish, for them to run once the tag exists, and never run by this skill:

```bash
gh release create <version> --title "Essentials <version>" --notes-file GITHUB-RELEASE-<version>.md --verify-tag
```
