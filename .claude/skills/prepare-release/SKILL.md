---
name: prepare-release
description: Prepare an Essentials release up to a local commit — convert docs/NEXT_VERSION_RELEASE.md into docs/RELEASE-NOTES-<version>.md and reset it, move the plugin version, CHANGELOG heading and essentials.version pin, write the plugin's migration notes, regenerate what reads the pin, run every release check, write the GitHub release body (local, uncommitted GITHUB-RELEASE-<version>.md), and print the tag, push and gh release commands for the maintainer. Never tags, never pushes. Use when asked to prepare, cut or get ready a release, a patch release or a version bump of Essentials. Argument - the version, e.g. 0.60.1.
argument-hint: "<version, e.g. 0.60.1>"
---

# prepare-release

`scripts/prepare-release.py <version>` makes the mechanical edits; `scripts/prepare-release.py --check <version>`
fails on anything left inconsistent. This skill carries the judgement around them and stops after a local commit.

Creating the GitHub release fires `.github/workflows/release-to-maven-central.yml`, which deploys to Maven Central.
That cannot be undone. This skill never tags, never pushes, and never runs `gh release create`; it prints those
commands for the maintainer at the end.

The root POM `<revision>` stays `DEV-SNAPSHOT`: the workflow sets the version from the release tag. Never edit it.

## 0. Pre-flight

Stop and report at the first one that fails.

1. **Nothing else is writing to this checkout.** Every background agent has finished and no other Maven run is
   active (root `CLAUDE.md`, "`target/` has more than one writer").
2. **The branch is the intended release source.** `git branch --show-current`. Ask the maintainer which ref the
   release is cut from: README's compatibility table links `tree/release/<major.minor>` branches, so check whether
   that branch exists (`git ls-remote --heads origin release/<major.minor>`) and whether it is the source.
3. **The files the script touches are clean.**
   `git status --porcelain -- docs LLM README.md essentials-plugin '*.java' '*.kt' '**/CLAUDE.md'` prints nothing.
   Untracked root files (`GITHUB-RELEASE-*.md`, reports) are fine; they are never committed.
4. **The previous release is the one you expect.** `git tag --merged HEAD --sort=-v:refname | head -1`.
5. **origin/main is current**, because the CHANGELOG decision reads plugin.json there: `git fetch origin main`.
6. **Patch, minor or major.** Patch: upgrade steps live in the release notes §1 only. Minor or major: you also
   write `docs/MIGRATION-<major.minor>.md` (§3.5); a major also carries out `docs/MIGRATION-NEXT_MAJOR.md`.

## 1. Read before running anything

- `docs/NEXT_VERSION_RELEASE.md` in full.
- `git log --oneline <previous tag>..HEAD`. Compare it with the file: a user-visible change with no entry is a
  missing entry. Add it to `NEXT_VERSION_RELEASE.md` now, in its section, before the conversion. (Judgement.)

## 2. Run the script

```bash
python3 scripts/prepare-release.py <version>
```

Without `origin/main` it asks for `--plugin-section rename|new`: `rename` when the CHANGELOG's top section was never
released, `new` when it was. Read the whole output. It lists every file it changed, every file a regenerator
changed (read those diffs: a golden is checked only by reading it), and what still needs a decision. When the
version is not the previous tag's next patch, it also rewrote that patch's "since …" marker across the docs and
sources; read that diff too (`git diff --stat`).

## 3. Judgement edits

Do them in this order: the migrations JSON cites the notes by line, so the notes settle first.

1. **The intro paragraph** of `docs/RELEASE-NOTES-<version>.md`. Replace the `<!-- prepare-release: … -->` marker and
   the draft below it (the old "So far" summary). Shape: `docs/RELEASE-NOTES-0.60.0.md` lines 6-37. Say what the
   release is, what a user must do, and whether the persisted format changes. A comparison table only when the
   platform moves.
2. **Section numbers.** The script drops empty sections; renumber the rest and every `§` reference and anchor
   that pointed at them. `--check` fails on a gap.
3. **§4, the plugin section.** The CHANGELOG top heading is now `## <version> — targets Essentials <version>`.
   Make §4 name the plugin version that ships with this release, not the `-N` versions it may still list. When the
   script reported several never-released CHANGELOG sections, ask the maintainer whether to fold them into one.
   When it inserted a new section, write its entry in place of the marker.
4. **Upgrade order.** Add a `## Recommended upgrade order` section (and its table-of-contents line) only when the
   release has two or more steps whose order matters.
5. **Minor or major only:** write `docs/MIGRATION-<major.minor>.md` in the shape of `docs/MIGRATION-0.60.md`, and
   link it from the notes.
6. **The migrations JSON**, `essentials-plugin/references/stack/migrations/<prev major.minor>-to-<major.minor>.json`.
   The script wrote a skeleton (the pin step and the `essentials.version` pin). Each §1 item with an `ESS-NNN` id
   in `LLM/LLM-traps.md` becomes a `checklist` item, with a `checklist` step for its phase; each fix in §3 that
   makes a project workaround removable becomes a `fixedIn` entry. A §1 item that has no `ESS` id: ask the
   maintainer whether to add a trap line (next free id; `scripts/check-ess-ids.py`) before citing it. For a minor
   or major, point `guide` at the new migration guide. Cite by `path`, `line`, `token`: the token must be on that
   line. Delete `_todo`. Shape and rules: `essentials-plugin/scripts/migration-plan.py` docstring. Then regenerate
   the goldens that hold the plan and check:
   ```bash
   python3 essentials-plugin/scripts/migration-plan.py --self-test --update-golden
   python3 essentials-plugin/scripts/migration-plan.py --check
   ```
   Any later edit to the release notes moves the cited lines: re-run `--check`.
7. **Prose that states the version.** The script printed the lines of `essentials-plugin/references/stack/*.md`
   that still name the previous release (for example `stack-contract.md` S1); move each that describes the
   current release.
8. **README § Version Compatibility.** Add or update the row for this line; mark the previous line "Maintained"
   instead of "Under active development" when this release starts a new one; drop the stale "Upgrading from
   0.40.x" note if it no longer applies; check the PostgreSQL floor paragraph.
9. **The roadmap.** `grep -n '<version>\|shipped' docs/ROADMAP-*.md`. Shipped items are deleted when they ship (the
   rule at the top of the file); a "see `docs/NEXT_VERSION_RELEASE.md`" pointer for shipped work now points at
   the release notes. Items waiting for "when the pins move to <version>" are now due: do them or report them.
10. **After any `LLM/` edit:** `scripts/sync-plugin-llm.sh`. The pre-commit hook does the same once installed
    (`scripts/install-git-hooks.sh`, once per clone).

## 4. Check, then commit

```bash
python3 scripts/prepare-release.py --check <version>
scripts/plugin-check.sh quick
python3 scripts/check-ess-ids.py
python3 scripts/check-release-exclusions.py
graphify update .
```

Every one exits 0. Fix and re-run until they do. Then commit with explicit paths, never `git add -A`: list them
from `git status --porcelain`, and leave out untracked root files such as `GITHUB-RELEASE-*.md` and reports.

```bash
git add -- <each path>
git commit -m "Release <version>: release notes, plugin <version>, essentials.version pin" -m "<trailer per session attribution rules>"
```

## 5. Release verification

Both need the machine quiet (§0.1) and take a while. A failure here is fixed in a new commit on top.

```bash
scripts/plugin-check.sh release --chrome "$(command -v chrome-headless-shell)"   # needs exit 0; a SKIPPED eval suite or render-check fails it
mvn clean install -P test-release                                                  # simulated release, OWASP check included: never pass -DskipDependencyCheck
gh run list --workflow plugin-scaffold-scheduled.yml --limit 1                     # the scheduled scaffold run is green on the release ref
```

Then work through the hand-checked list `plugin-check.sh release` prints at its end.

## 6. The GitHub release body

Always produce `GITHUB-RELEASE-<version>.md` at the repository root: run the `github-release` skill with the
version. It follows the newest `GITHUB-RELEASE-*.md` there (e.g. `GITHUB-RELEASE-0.60.0.md`) as the style exemplar.
The file stays local and uncommitted, like every earlier one: never stage it, and do not add it to `.gitignore`.
The tag need not exist yet; `check.py` resolves the tag-pinned links against the local files. It must pass:

```bash
python3 -I .claude/skills/github-release/check.py GITHUB-RELEASE-<version>.md <version> \
    docs/RELEASE-NOTES-<version>.md docs/MIGRATION-<major.minor>.md docs/MIGRATION-NEXT_MAJOR.md
```

Any fix it makes under `docs/` goes in a new commit before the tag, or its anchors do not exist at the tag. When an
earlier file for this version already exists, show the maintainer the difference before overwriting it.

## 7. Hand over

First confirm `GITHUB-RELEASE-<version>.md` exists, is untracked (`git status --porcelain GITHUB-RELEASE-<version>.md`
prints `??`) and passed `check.py`; name it in the hand-over. Then print these for the maintainer, filled in, and
stop. Do not run them.

```bash
git push origin <branch>                         # then land on main as usual; the plugin marketplace goes live when main has the new plugin.json
git tag -a <version> -m "Essentials <version>"   # on the release-source commit agreed in §0.2; previous tags are annotated too
git push origin <version>
gh release view <previous tag> --json name       # copy its title format
gh release create <version> --verify-tag --title "<title>" --notes-file GITHUB-RELEASE-<version>.md   # fires the Maven Central deploy
```

After `gh release create`:

- Watch the deploy: `gh run list --workflow release-to-maven-central.yml --limit 1`, then `gh run watch <id>`.
- Check the artifacts appear on Maven Central for `dk.trustworks.essentials` and `dk.trustworks.essentials.components`
  at `<version>`; indexing can lag the deploy.
- The plugin's generated docs (`essentials-plugin/references/llm/`) link to `blob/<version>`; they resolve only once
  the tag is pushed.
- Start a run of `plugin-scaffold-scheduled.yml` on the released ref if it has not had one since the release commit.

Also report what was left undecided: every question asked in §3 that the maintainer has not answered.
