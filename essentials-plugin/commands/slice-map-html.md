---
name: slice-map-html
description: >
  Write the Slice Atlas for a project that already follows the slice law: one self-contained HTML page
  with five lenses — the Atlas (contexts, journeys, an Event Modeling board per journey), Diff against a
  git baseline, field Lineage, a Coupling matrix and a Metro map — plus an issues dock and deep links.
  Built by script from the slice.yaml manifests and the source; on a feature branch the Diff lens
  compares with the merge base by default. Read-only except the page.
user-invocable: true
allowed-tools: [Read, Bash, Glob, Grep, AskUserQuestion]
argument-hint: "[<path>] [--bc <name>] [--out <file>] [--diff <ref>]"
---

# /essentials:slice-map-html

Answers the same question as `/essentials:slice-map` — **"what is here, where is it implemented, and how
is it connected?"** — as a page for a browser instead of a report in the terminal. Both commands read the
same manifests through the same scripts, so they share Steps 1–4 of
`${CLAUDE_PLUGIN_ROOT}/commands/slice-map.md` rather than restating them.

The page is written by one script, `slice-index.py atlas`. Everything on it — journeys, issues, field
lineage, the diff, every coordinate — is computed in Python before the page is written; the page only
draws, filters and navigates. **The model's whole job here is to run the scripts and report the line
they print.** It never reads a manifest to build the page and never edits the page.

**Read-only. Writes the page at a path the user agreed to, and nothing else** — no source, no manifests,
no git state (the baseline is read with `git archive` into a temporary directory, never a worktree or a
checkout).

## Arguments

| Argument | Meaning |
|---|---|
| `<path>` | Module or source root. Default: the resolved package root (`slice-map.md` Step 1) |
| `--bc <name>` | Open the page on this bounded context. The page still maps every context: a page cut to one context would hide the events that cross into it |
| `--out <file>` | Write the page here. Without it, propose a path and ask (Step 2) |
| `--diff <ref>` | Baseline for the Diff lens. Without it, the merge base with the default branch on a feature branch, else none (Step 3) |

## Step 1 — Terrain and the redirect

`slice-map.md` Step 1, unchanged: resolve the roots, glob for `slice.yaml`, and with no manifest
anywhere stop and redirect to `/essentials:slice-discover`. When `use_cases/` or `views/` exist without
manifests, point at `/essentials:slice-check --fix-manifests`.

## Step 2 — The output path

With `--out`, use it. Without it, propose `target/slice-map.html` — `build/slice-map.html` when the
project builds with Gradle — and **ask once** before writing, offering the proposal and a path of the
user's choice. A build directory is already git-ignored, which suits a page you regenerate rather than
commit. Never choose the path silently: this plugin owns no directory (`CLAUDE.md` design invariants).
Once the user has agreed, create the parent directory if it is missing (`mkdir -p`); write nothing else.

## Step 3 — The Diff baseline

The Diff lens answers *"what did this branch do to the shape?"*, the question a reviewer of generated
code most needs answered. Decide its baseline before running anything:

| Situation | Baseline |
|---|---|
| `--diff <ref>` given | that ref |
| Not a git repository | none |
| The current branch is the default branch, or `HEAD` is detached | none |
| Any other branch | the merge base of `HEAD` with the default branch |

The default branch is `git symbolic-ref --short refs/remotes/origin/HEAD` (for example `origin/main`),
else whichever of `main` and `master` exists locally. The merge base is `git merge-base HEAD <default>`;
pass its sha. When no baseline applies, run without one: the Diff lens then says "no baseline" and the
other four lenses render as usual. Name the baseline in the report, so a reader knows what "new issues"
was counted against.

## Step 4 — Run the scripts

Doctor first, for the runner, exactly as in `slice-map.md` Step 2:
`"${CLAUDE_PLUGIN_ROOT}/scripts/doctor.sh" --for slice`, whose `uv` line picks `uv run --script` or
`python3`; `MISSING` means stop and quote doctor's lines. Then the two `slice-source.py` runs into a
temporary directory outside the project, and the page:

```bash
tmp=$(mktemp -d)
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-source.py <root> --json          > "$tmp/facts.json"
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-source.py <root> --check --json  > "$tmp/check.json"
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-index.py atlas <root> --out <file> \
    --source-facts "$tmp/facts.json" --source-check "$tmp/check.json" \
    [--before-ref <baseline>] [--bc <name>]
```

The `slice-source.py` exit codes mean what `slice-map.md` Step 2 says: 0, 1 and 3 wrote JSON; 2 wrote
none, so drop that `--source-…` flag and say that payloads, columns and the source checks read **not
checked** on the page.

| `slice-index.py atlas` | What it means here |
|---|---|
| 0 | the page is written and the map is complete |
| 1 | the page is written, and at least one manifest did not parse. The page draws each one as a red error card in its context under a "map incomplete" banner — an omitted slice would be invisible, a red card is not. Say so, and apply the braced-path check of `slice-map.md` Step 2: when every failure is an unquoted path containing `{`, offer one rerun with `--repair-braced-paths`, and name `/essentials:slice-check --fix-manifests` as the repair |
| 2 | could not run (no `pyyaml`, a bad root, an unreadable baseline, an unwritable `--out`). Stop and say why. Never build a page by hand |
| 3 | no `slice.yaml` under the root: the Step 1 redirect |

A baseline `git archive` cannot read — a shallow clone without the merge base, a mistyped ref — is exit
2. When the baseline was Step 3's default rather than one the user named, rerun once without it and say
that the Diff lens has no baseline, and why.

## Step 5 — Report

Print the one summary line the script wrote to stdout, verbatim, then the baseline:

```
slice-index: wrote target/slice-map.html — 14 slices in 3 contexts · 6 processes · issues 1 error, 3 warn, 11 info · …
baseline: merge base with origin/main (3ef75e1c)
```

Then stop. No report sections — contexts, chains, flows and endpoints as text are
`/essentials:slice-map`. The issues are on the page, in its dock: do not list them here and do not grade
them. Like the map, the page grades nothing (`slice-map.md` Step 4).

## What the page shows

So the report can answer a question about it. Lenses switch with `1`–`5`:

| Lens | Answers |
|---|---|
| Atlas | *How is this shaped?* Semantic zoom over one drawing: contexts, then the journeys (processes) in them, then an Event Modeling board per journey — trigger, command and view, and event lanes, one column per slice in causal order — and at the deepest zoom each slice's scenarios as given / when / then stacks, or its invariants when it declares none |
| Diff | *What did this change do to the shape?* Added, removed and changed slices against the baseline, a summary bar, and new against fixed issues |
| Lineage | *Where does this field come from?* Every command, event and read model carrying a field of one name; a read-model column that no inbound event carries is an issue |
| Matrix | *Who touches what?* Write targets and read models against slices; cross-context cells stand out |
| Metro | *How do the journeys interleave?* One line per journey; a slice in several journeys is an interchange |

A journey is the manifest's `flow:` where one is declared, and is otherwise derived from the declared
cause and effect; the dock says which slices declare none. Scenarios are the manifest's `scenarios:`.
Both fields, and the checks on them, are `references/slice/manifest-guide.md` §3. The page also has an
issues dock (selecting an issue flies to its target), search (`/`), a minimap, a legend per lens, light
and dark themes, and the whole view state in the URL hash, so a link pasted into a pull request reopens
the same view. It is self-contained — no CDN, no fonts, no fetch — and opens from `file://`.

## Without the model

CI can publish the page with no model involved; the command adds nothing the script does not do. For a
per-PR artifact, keep the default branch's map and diff against it, which is cheaper than
`--before-ref`:

```bash
# on the default branch
uv run --script scripts/slice-index.py map <root> > base-map.json
# on the pull request
uv run --script scripts/slice-index.py atlas <root> --out slice-map.html --before-map base-map.json
```

Add the two `--source-…` flags of Step 4 for payloads, columns and the source checks. `--lens` and
`--focus` set the view the page opens on. Exit 1 still writes the page: a CI step that publishes it
should accept 1 and let the page's banner carry the news.

## Out of scope — state these if asked

- **Audits nothing.** No R1–R5 gates and no severities — `/essentials:slice-check` owns that.
- **Infers nothing from code.** Every slice and edge comes from a manifest — `/essentials:slice-discover`
  owns inference. A journey without `flow:` is derived from declared edges, never from source.
- **Writes no source and no manifests, and nothing on the page edits anything.**
- **Owns no directory.** The page goes where the user agreed.

## Verification fixture

`tests/scripts/test_slice_atlas.py` pins the data (processes, issues, lineage, the diff) against goldens
over `tests/fixtures/atlas/`, `tests/scripts/test_slice_atlas_layout.py` pins the layout, and
`tests/fixtures/slice-map/render-check.py` renders the fixture's page and a hand-written sample in
headless Chrome: every lens mounts with no console error, selecting an issue focuses its target, a deep
link restores the view, and the broken manifest is drawn as an error card.
