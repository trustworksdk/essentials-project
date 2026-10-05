---
name: review
description: >-
  Review a change to a Trustworks Essentials project — the current branch, a ref such as HEAD~3, a
  pull request fetched by number, or a path — against the framework traps index, the stack contract
  (S1-S11) and the slice law. The deterministic half runs as scripts (review-scan, stack-lint,
  slice-lint, slice-source); the model judges only what they cannot see. Every finding carries an
  ESS id and a link to the section that owns it. Reports by default; --fix applies the mechanical
  fixes the scripts describe, one at a time, each confirmed.
user-invocable: true
allowed-tools: [Read, Write, Edit, Bash, Glob, Grep, AskUserQuestion]
argument-hint: "[<base-ref> | <path>] [--pr <n> [--remote <name>]] [--fix] [--out <file>]"
---

# /essentials:review

Reviews **a change**, not a project. It answers *"what does this diff get wrong about Essentials?"*:
a trap from the traps index, a stack-contract requirement the change breaks, a slice-law gate the
change fails. Whatever was already wrong before the change is counted, not listed — the whole-project
audits own that.

| Question | Command |
|---|---|
| What does **this change** get wrong? | **`/essentials:review`** (this) |
| Does the whole project obey the slice law? | `/essentials:slice-check` |
| Is the whole project behind the stack contract or the plugin's copies? | `/essentials:upgrade` |
| What is here and how does it connect? | `/essentials:slice-map` |

Three kinds of finding id, one per source of truth — never a second list:

| Id | Meaning | Defined by | Link |
|---|---|---|---|
| `ESS-NNN` | a framework trap | its line in `references/llm/LLM-traps.md` | `references/llm/LLM-traps.md#ess-NNN` |
| `ESS-S<n>[.<m>]` | a stack-contract requirement | its heading in `references/stack/stack-contract.md` | the heading's anchor, as `stack-lint.py --rules --json` prints it |
| `ESS-G<gate>[<clause>]` | a slice-law gate (`ESS-G4b` = gate 4(b)) | the gate table in `commands/slice-check.md` Step 2 | `commands/slice-check.md#g<gate>` |

All links are relative to `${CLAUDE_PLUGIN_ROOT}`. Gate 12 (project-copy freshness) is not a review
check: it is about the project, never about a change — `/essentials:upgrade` owns it.

## Usage

```
/essentials:review                       # the current branch vs the default branch: commits + uncommitted + untracked
/essentials:review <base-ref>            # vs the merge base with <base-ref>: a branch, a tag, a sha, HEAD~3
/essentials:review --pr <n> [<base-ref>] [--remote <name>]
                                         # a pull request by number, fetched with git; nothing is checked out
/essentials:review <path>                # no diff: every line of every file under <path> is in scope
/essentials:review … --fix               # after the report, offer the mechanical fixes one at a time
/essentials:review … --out <file>        # also write the report to a user-supplied path
```

An argument that `git rev-parse --verify --quiet '<arg>^{commit}'` resolves is a ref; otherwise an
existing path is a path; otherwise stop and say it is neither. When a name is both, it is taken as a
ref and the report says so — pass `./<name>` for the path.

**Default is report only: nothing in the project is written.** `--fix` is the one mode that edits, and
only as Step 6 describes. `--out` writes only the path the user named; with no path, print.

## Step 0 — Preconditions

- `${CLAUDE_PLUGIN_ROOT}` unset ⇒ abort: "This command must be invoked from within Claude Code with the
  essentials plugin installed."
- `python3` ≥ 3.11 is required; without it no deterministic check can run, so stop and say so rather
  than review by eye.
- `slice-lint.py` and `slice-source.py` need `pyyaml` (and `slice-lint.py` `jsonschema`). They carry their
  pinned dependencies in inline script metadata, which `uv run --script` installs. Without `uv`,
  `python3 <script>` works where the dependencies are installed; where they are not, the script exits 2
  and its gates are **not run** (Step 3). `review-scan.py` and `stack-lint.py` are standard library only.

## Step 1 — Gate and facts

Apply `${CLAUDE_PLUGIN_ROOT}/references/slice/change-procedure.md` §1, the gate table, to the tree
under review. It decides two things:

| Gate result | What this command does |
|---|---|
| On the law (a `.claude/rules/essentials-slices.md`, a `slice.yaml`, or `use_cases/`/`views/` directories) | Everything below, slice-law checks included |
| Essentials, not on the law (`dk.trustworks.essentials` in a build file only) | Traps and stack only. Say once that the slice law is not in force here and name `/essentials:slice-discover`; list the slice gates under **Not run** with that reason |
| Neither | Stop with one line: this is not an Essentials project. Review nothing |

The rest of the facts — language, persistence profile, web stack, frontend mode — come from
`stack-lint.py`'s `facts` (Step 3), and the write-style lane per bounded context from `slice-source.py`'s
`lanes`. **Never guess one.** A check that needs a fact nobody could determine goes under **Not run**.

## Step 2 — Resolve the surface

Everything this command creates lives in one temporary directory outside the project, removed at the
end:

```bash
WORK=$(mktemp -d)
TOP=$(git rev-parse --show-toplevel)
```

Produce four things: `BASE` (a commit), `HEAD_TREE` (a directory holding the new side), `BASE_TREE` (a
directory holding the old side), and `$WORK/change.diff` (a unified diff from old to new).

**Default and `<base-ref>`** — the working tree, committed and uncommitted, plus untracked files that are
not ignored. With no argument the base ref is the default branch:
`git symbolic-ref --quiet --short refs/remotes/origin/HEAD`, else a local `main`, else `master`; if none
exists, stop and ask for a base ref — never guess one.

```bash
BASE=$(git merge-base "$REF" HEAD)
HEAD_TREE="$TOP"
{ git -C "$TOP" diff --merge-base "$REF" --no-color --no-ext-diff --no-renames
  (cd "$TOP" && git ls-files --others --exclude-standard -z \
     | xargs -0 -r -n1 git diff --no-index --no-color -- /dev/null)
} > "$WORK/change.diff"
mkdir "$WORK/base" && git -C "$TOP" archive "$BASE" | tar -x -C "$WORK/base" && BASE_TREE="$WORK/base"
```

`git diff --no-index` exits 1 when the files differ, which is always here; that is not an error.
`--merge-base` needs git 2.30 or later — on an older git, stop and say so.

**`--pr <n>`** — git only; no forge CLI is used or needed. Fetch the pull request's head without
checking it out:

```bash
git fetch --no-tags "${REMOTE:-origin}" "pull/<n>/head"            # GitHub
git fetch --no-tags "${REMOTE:-origin}" "merge-requests/<n>/head"  # GitLab, when the first fails
PR_HEAD=$(git rev-parse FETCH_HEAD)
```

A forge that exposes neither ref cannot be reviewed by number: say so and ask for the branch name. The
target branch of a pull request is not knowable from git alone, so the base is `<base-ref>` when given,
else `${REMOTE:-origin}/<default branch>`, and the report header prints which. Then:

```bash
BASE=$(git merge-base "$BASE_REF" "$PR_HEAD")
git diff --no-color --no-ext-diff --no-renames "$BASE" "$PR_HEAD" > "$WORK/change.diff"
mkdir "$WORK/head" "$WORK/base"
git archive "$PR_HEAD" | tar -x -C "$WORK/head" && HEAD_TREE="$WORK/head"
git archive "$BASE"    | tar -x -C "$WORK/base" && BASE_TREE="$WORK/base"
```

The only thing this writes to the repository is `FETCH_HEAD` and the fetched objects; say that in the
report header. No branch, no worktree, no checkout.

**`<path>`** — no base, no `BASE_TREE`. Every tracked or untracked, non-ignored file under the path is
in scope, as an all-added diff:

```bash
HEAD_TREE="$TOP"
(cd "$TOP" && git ls-files -co --exclude-standard -z -- "<path>" \
   | xargs -0 -r -n1 git diff --no-index --no-color -- /dev/null) > "$WORK/change.diff"
```

Outside a git repository, list the files with `find <path> -type f` and build the same diff with
`diff -u /dev/null <file>`.

**The change list.** The files the diff adds or modifies are **in scope**. Deleted files are listed in
the header, not reviewed. Binary files, and files under `target/`, `build/`, `node_modules/` or `dist/`,
are out of scope. An empty change list ends the run: say there is nothing to review.

## Step 3 — The deterministic half, by script

Run every script that applies and **take its findings verbatim** — id, severity, file, line, message,
fix, link. Parsing a POM, matching a retired key, intersecting manifest id sets and reading request
mappings are mechanical; a model doing them by eye produces confident wrong answers, and nothing in
Step 4 re-derives, re-grades or re-words what a script decided.

### 3a — Trap signatures (`review-scan.py`)

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/review-scan.py --diff "$WORK/change.diff" --root "$HEAD_TREE" --json \
  > "$WORK/scan.json"
```

Exit 0 or 1: the JSON is the result. Exit 2: nothing was scanned — report **Not run: trap signatures**
with the script's stderr line. Its `notRun` entries (a YAML key whose full path the diff does not show)
go under **Not run** verbatim. Each finding is one of two kinds:

- `confirmed` — the match is the trap. It is a finding as it stands.
- `candidate` — the match is where the trap lives. Step 4a confirms or dismisses it, with a reason.

### 3b — Stack contract (`stack-lint.py`)

The project root for this script is the directory holding the reactor `pom.xml`: the top of the tree
when it has one, else the shallowest `pom.xml` below it (`target/` excluded). The same relative path is
used in `BASE_TREE`.

```bash
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/stack-lint.py "$HEAD_TREE/<root>" --json > "$WORK/stack.head.json"
python3 ${CLAUDE_PLUGIN_ROOT}/scripts/stack-lint.py "$BASE_TREE/<root>" --json > "$WORK/stack.base.json"
```

Exit 2 on the head tree (no `pom.xml`, a Gradle-only build, no Essentials dependency, a POM that does not
parse): **Not run: stack contract S1–S11**, with the script's reason. Its `notRun` entries go under
**Not run** verbatim. Its `facts` are the language, profile, web stack and frontend mode for the header.

### 3c — Slice law (`slice-lint.py`, `slice-source.py`) — on the law only

```bash
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-lint.py   "$HEAD_TREE" --require-schema --json > "$WORK/lint.head.json"
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-source.py "$HEAD_TREE" --check --json         > "$WORK/source.head.json"
```

and the same two over `BASE_TREE` into `lint.base.json` / `source.base.json`.

| Script | Exit | Meaning here |
|---|---|---|
| `slice-lint.py` | 0, 1 | gates 1, 3 and 4 ran |
| | 2 | **Not run: ESS-G1, ESS-G3, ESS-G4**, naming the missing dependency. Never read the manifests yourself instead: an unvalidated manifest set reported as clean is the failure gate 1 exists to prevent |
| `slice-source.py` | 0, 1 | gates 6 (mappings, raw ids), 11(b) and 14 ran |
| | 3 | incomplete, not a pass: each `unverified` entry for an in-scope slice goes under **Not run** with its reason; `unparsed` files likewise |
| | 2 | **Not run: ESS-G6, ESS-G11b, ESS-G14** |

Both scripts put the id on each finding (`id`); it is the `gate` label's number and clause —
`"4(b) sole owner"` → `ESS-G4b`, `"3 uniqueness"` → `ESS-G3`. A manifest slice-lint could not parse
removes its slice from Step 4c; say which, once.

### 3d — What the change introduced

`stack-lint`, `slice-lint` and `slice-source` read the whole tree, so most of what they report was there
before the change. A whole-tree finding **belongs to the change** when no finding with the same check
(`check`, or `gate`), the same file and the same message exists in the base run — line numbers are
not compared, because the change moves them. Everything else is **pre-existing**: counted per script in
the report, never listed, with the command that lists it (`/essentials:upgrade --check` for the stack,
`/essentials:slice-check` for the slice law). In `<path>` mode there is no base run: every whole-tree
finding under the path belongs to the review. With no `BASE_TREE` in the other modes (the base could not
be extracted), say so and treat every whole-tree finding as pre-existing rather than guess.

`review-scan` reads only added lines, so all of its findings belong to the change.

### 3e — One row per defect

Two scripts report the same defect on the same place in three known cases. Keep the `ESS-NNN` row — it
carries the symptom text and the fix descriptor — and name the other id on it as `also ESS-S…`:

| review-scan `check` | stack-lint `check` | The same defect when |
|---|---|---|
| `ess-088-mongo-key` | `s2-mongo-keys` | same file, same line |
| `ess-103-transactional-mode` | `s5-transactional-mode` | same file, same line |
| `ess-094-jackson2-module` | `s3.1-jackson2-essentials-module` | same POM, and the stack-lint line is the review-scan fix op's `line` (the `<dependency>` element's first line) |

Different defects on one line stay separate rows. Never merge by id alone.

## Step 4 — The judgement half

Only what no script decides. Scope is the change: a judgement finding must point at a line the change
adds or modifies, or be a direct consequence of one (a new decider with no wiring; a new handler whose
event the manifest does not declare). Something wrong on a line the change did not touch is pre-existing
and out of scope, however visible it is in a file the change edited.

**Strip comments before counting anything**, exactly as `commands/slice-check.md` Step 2 says and for
the same reason: code that explains itself names the very annotations and keywords being counted.

### 4a — Every candidate, confirmed or dismissed

For each `candidate` from Step 3a: read the matched line, the code around it, and the owning section
(the finding's `section`), then decide. **Confirmed** — the trap bites here: the row stands with the
script's id, severity and fix, plus one clause of evidence (`file:line` of what makes it bite).
**Dismissed** — it does not: the row moves to **Dismissed candidates** with a one-line reason naming the
evidence. Never drop a candidate silently; an unexplained absence reads as a missed check.

### 4b — Traps the signatures cannot see

Read `${CLAUDE_PLUGIN_ROOT}/references/llm/LLM-traps.md` — the whole index; it is short. For each in-scope
source file, map its `dk.trustworks.essentials.*` imports to the index's `###` module headings (the
package-to-module routing is the Module Index in `${CLAUDE_PLUGIN_ROOT}/skills/essentials-docs/SKILL.md`),
and add `## Universal`, plus `## Upgrading` when the change touches a build or configuration file. For
each line under those headings whose symptom plausibly fits a changed hunk, open its linked section
before deciding. Report a trap only with evidence on a changed line; the id is the line's `ESS-NNN`. A
trap `review-scan` already reported at that place is not reported twice.

Severity of a trap row found here: **Blocking** when the owning section's failure is a startup failure,
lost or duplicated data, or persisted state that no longer reads back; **Should-fix** otherwise;
**Advisory** only when the section itself calls the case harmless or cosmetic.

### 4c — The slice law (on the law only)

Apply the gates of `${CLAUDE_PLUGIN_ROOT}/commands/slice-check.md` Step 2 **as written there** — never
restated here — to the in-scope files, with the lane `slice-source.py` detected for each bounded
context deciding which gates apply and how (its gate applicability table).

- Gates 1, 3 and 4 are `slice-lint.py`'s; gate 6's request-mapping and raw-id checks, gate 11(b) and gate 14
  are `slice-source.py`'s. Take them from Step 3; do not run them again by eye. What remains of gate 6 (the
  no-adapter rule, a view mapping reading a repository the slice does not own) is judgement.
- Gate 12 is not a review check (above).
- The cross-file gates (9, 13, 17) run only when the change adds, removes or renames a slice, a decider,
  a handler, a projection, a routing marker or a `config/` class — and then for that element only.
- Every false-positive trap slice-check names for a gate applies here unchanged.

The id is `ESS-G<gate><clause>`; the severity is the one slice-check gives that gate; the fix is one
sentence, and structural repairs are routed (Step 5). When a gate finding and a trap line (4b) describe
the same defect at the same place, report one row under the trap's `ESS-NNN` — it carries the symptom
and the owning section — and name the gate on it as `also ESS-G…`, as Step 3e does for the scripts.

### 4d — The stack contract, beyond the script

When the change touches a build file, an `application*` configuration file, a `config/` class, a
serializer, mapper, command-bus or event-bus bean, security configuration, or a test base class, read
the owning sections of `${CLAUDE_PLUGIN_ROOT}/references/stack/stack-contract.md` live and apply the
requirements `stack-lint.py` does not decide — among them whether security was decided and whether a
payload's constructor parameter names match its JSON properties (its docstring names both), a
persistence serializer built outside `EssentialsObjectMappers`, and a bean of the project's own that
makes a starter's bean back off. Report under
`ESS-S<n>`, linked to the heading `stack-lint.py --rules --json` gives for that id (for an id with no
rule there, the `## S<n>` heading's anchor). A requirement S1–S11 does not state is not a finding: if the
change breaks something the contract does not say, report it as a suspected contract gap under
**Notes**, without an id.

## Step 5 — Report

```
ESSENTIALS REVIEW — feature/invoice-reminders + working tree · base main (3f2c1ab) · 9 files changed, 8 in scope
  kotlin · mongo · webflux · lanes: billing: aggregate
  Deterministic: review-scan 1 confirmed, 1 candidate · stack-lint 1 new · slice-lint 0 new · slice-source 1 new
  Pre-existing (not listed): stack-lint 2 → /essentials:upgrade --check · slice 4 → /essentials:slice-check

  [Blocking]   ESS-088   backend/src/main/resources/application.yml:6   `spring.data.mongodb.uri` is not bound — the app talks to localhost/test
               → references/llm/LLM-traps.md#ess-088 · also ESS-S2 · fix: rename to spring.mongodb.uri   [review-scan]
  [Blocking]   ESS-G10   billing/views/reminder_list/ReminderListProjection.kt:31   writes a versioned read model from a handler without OrderedMessage
               → commands/slice-check.md#g10 · fix: take OrderedMessage as the 2nd parameter and pass its order to save/update   [judgement]
  [Should-fix] ESS-G11b  billing/views/reminder_list/ReminderListProjection.kt:40   handles InvoicePaid, not in projections[].from
               → commands/slice-check.md#g11 · fix: /essentials:slice-check --fix-manifests   [slice-source]

  Dismissed candidates
    ESS-016  billing/automations/send_reminder/ReminderTestSupport.kt:12 — builds a LocalCommandBus for a local demo runner; nothing is sent with sendAndDontWait

  Not run
    none

  Next: 1 mechanical fix → re-run with --fix · manifests → /essentials:slice-check --fix-manifests
```

Rules for the report:

- **Row shape:** `[Severity] id file:line message`, then `→ link · also <ids> · fix: <one line>
  [(mechanical)] [source]`. The source is the script's name or `judgement`. Rows sort Blocking →
  Should-fix → Advisory, then by file and line. Severities are `rules/slice-design.md` § Reporting
  severities' three levels; a script's severity is never changed.
- **Not run is never omitted.** Every check that did not run is listed with its reason — a missing
  script dependency, no `pom.xml`, not on the law, a `notRun` or `unverified` entry, a file too large to
  read. A report with no findings says **"no findings in the checks that ran"**, followed by the Not run
  list, and never "clean", "passes" or "LGTM" while that list is non-empty. When everything ran, the Not
  run block says `none`.
- **Nothing pre-existing is listed.** It is counted, with the command that lists it.
- **Repairs are routed, not performed** (outside `--fix`): a manifest drift → `/essentials:slice-check
  --fix-manifests`; one of slice-check's three structural fixes → `/essentials:slice-check --fix-source`;
  a pre-existing stack finding → `/essentials:upgrade`; a mechanical row → `--fix`. Name each route once,
  in the closing line.
- Three real findings stated plainly beat thirty padded ones.

With `--out <file>`, write the same text to that path and nowhere else.

## Step 6 — `--fix`: the mechanical fixes, one at a time

Runs after the report is printed, never instead of it. It applies only a **fix descriptor a script
emitted** — the `fix` object on a finding — so the edit is exactly what the script's own check pinned
down; this command holds no repair logic of its own.

**Eligible:** a row from Step 3 whose source is `review-scan` with `kind: confirmed`, or `stack-lint`,
whose `fix.mechanical` is `true` and whose `fix.ops` is not empty. Not eligible, and never applied here:
candidates (confirmed or not), judgement rows, slice-law rows, pre-existing findings, and every
`ESS-S1` row (baseline and version pins) — moving a version is a decision this command does not take.

**Refused** with `--pr` (the reviewed tree is a temporary copy nobody has checked out) and when the run
has no eligible row (say so in one line).

For each eligible row, Blocking first, then by file and line:

1. **Re-read and confirm the signature.** Open the file each op names and confirm what the op expects
   is still there — the `from` text on that line, the `key`, the `<dependency>` element starting at
   `line` with that `groupId`/`from`, or the absence of what `add-*` would add. A mismatch means the file
   moved on since the scan: skip the row, say so, and continue.
2. **Show the edit**: the finding in one sentence, then the exact lines before and after.
3. **Ask** with `AskUserQuestion`: *Apply* / *Skip* / *Stop here*. No option is marked recommended.
4. **Apply only on Apply**, with `Edit`, exactly as the op says (below). Never batch rows, never apply one
   that was not asked about, never continue past *Stop here*.

When the loop ends, re-run the script that produced each applied row (review-scan on a freshly built
diff, stack-lint on the tree) and confirm its finding is gone. One that remains is reported as **not
fixed**, with the new output. Close with the list of files changed, so `git diff` shows exactly what
this run did.

### Applying a fix descriptor

The one definition of each op's edit — `/essentials:upgrade` applies stack-lint's descriptors by this
table too. Each op edits one place; `file`/`pom` are relative to the tree the script read. Which rows
this command applies is Step 6's eligibility rule above, not this table.

| Op | Edit |
|---|---|
| `replace-text` `{file, line, from, to}` | On that line only, replace the substring `from` with `to` |
| `rename-config-key` `{file, line, from, to}` | `.properties`: rewrite the key on that line. YAML: move the key and its value to the `to` path, creating missing parent mappings and deleting parents left empty |
| `delete-config-key` `{file, line, key}` | Delete the key on that line (in YAML, with its subtree), then any parent mapping left empty |
| `add-dependency` `{pom, groupId, artifactId, scope, version?}` | Insert a `<dependency>` into that POM's `<dependencies>`; `scope: null` = compile (no `<scope>`). A `version` is written as given (e.g. `${essentials.version}`); none means managed — and a JDBI coordinate is managed only where the POM imports the `jdbi3-bom`, so without it say so and skip rather than invent a version |
| `remove-dependency` `{pom, line, groupId, artifactId}` | Delete the `<dependency>` element whose first line is `line` |
| `set-dependency-scope` `{pom, line, groupId, artifactId, from, to}` | Change that element's `<scope>` from `from` to `to`; `to: null` deletes `<scope>` |
| `set-artifact-id` `{pom, line, groupId, from, to}` | In the `<dependency>` element whose first line is `line`, change `<artifactId>` from `from` to `to` |
| `set-dependency-version` `{pom, line, groupId, artifactId, from, to}` | In the `<dependency>` element whose first line is `line`, change `<version>` from `from` to `to` |
| `set-property` `{pom, name, from, to}` | Set `<properties><name>` from `from` to `to`; `from: null` = add the property |
| `add-compiler-arg` `{pom, plugin, arg}` | Add `arg` to that plugin's `<compilerArgs>` (`maven-compiler-plugin`) or `<args>` (`kotlin-maven-plugin`), creating the element when absent |
| `add-kotlin-compiler-plugin` `{pom, name}` | Add `<plugin>name</plugin>` to `kotlin-maven-plugin`'s `<compilerPlugins>`, and the `kotlin-maven-allopen` dependency to that plugin when absent |
| `append-line` `{file, text}` | Append `text` as a line (create the file when absent) |

An op not in this table is not applied: report it as unknown and leave the row for the user.

## Rules

- **Scripts decide what scripts can decide.** Take their output verbatim; never re-derive a gate, a
  key, a dependency or a mapping by eye, and never fall back to doing so when a script cannot run —
  that check is **not run**.
- **Never claim a clean result for a check that did not run.**
- **The change is the scope.** Pre-existing findings are counted and routed, never listed.
- **Report before write.** Nothing is written without `--fix`, and under `--fix` nothing without a
  per-fix yes. `--out` writes only the named path.
- **Every finding has an id and a link.** A defect with no id in any of the three namespaces is a note,
  not a finding.
- **Stateless.** The temporary directory is removed at the end; no state, cache or log is kept.

## Error handling

- Not an Essentials project ⇒ one line, review nothing.
- Not a git repository ⇒ only `<path>` mode works; say so for any other argument.
- The base ref does not resolve, or `--pr` fetches nothing ⇒ stop and say which; do not fall back to
  another base silently.
- A script exits 2 ⇒ its checks go under **Not run** with the script's message; the run continues
  with the others.
- The change list is large ⇒ judge every in-scope file or list the ones not judged under **Not run**
  ("judgement: <n> files not read"); never sample silently.
