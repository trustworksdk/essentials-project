---
name: upgrade
description: >-
  Bring an existing Trustworks Essentials project up to what the installed plugin ships: refresh the
  slice-rules pointer and the slice-manifest lint gate (installing and activating it if the project
  predates it), check the project CLAUDE.md still routes to the essentials-docs skill, audit the app
  against the stack contract (S1-S11), silent-startup failures first, and move a project on an older
  Essentials release to the plugin's - essentials.version plus only the platform pins that release
  requires, under a decision note you approve, on its own branch, code migrations included, verified
  by a full build. Reports every finding before writing and offers each fix singly. Never regenerates
  a skeleton, never restructures slice source, and never moves a pin the release does not require
  except a Kotlin compiler too old for the project's own Java baseline, and - one at a time, each on a
  yes - the Spring Boot patch, Kotlin compiler and Maven wrapper floors the Essentials build runs on.
user-invocable: true
allowed-tools: [Read, Write, Edit, Bash, Grep, Glob, AskUserQuestion]
---

# /essentials:upgrade

`/essentials:init` scaffolds a project **once**. Nothing afterwards pulls plugin changes into it: the
two files that leave the plugin go stale, capabilities added later are never offered, a project
created before a contract requirement existed stays silently non-conformant, and a project stays on
the Essentials release it was built against while the plugin moves on. This command is the only
thing that closes that gap.

It is an **audit that offers repairs**, not a regeneration. Everything it reports is derived from the
project's own files plus the plugin's current `references/stack/stack-contract.md` — never from a
diff against the template tree `/essentials:init` renders from. A project diverges from its render
the day work on it starts, and an older project was never rendered from this tree at all, so a diff
would report the application itself as drift. The contract is what a project must satisfy; the
template is one way of satisfying it.

A project on an older Essentials release also gets **Group D, the Essentials upgrade**: the move to
the release the plugin targets, planned by `scripts/migration-plan.py` from the release's migration
notes, approved as a decision note, and applied on its own branch in the release's order, each step
verified and the whole move gated by the project's full build.

## Separation of concerns

| Drift | Command |
|---|---|
| Project behind the installed plugin — stale copies, an uninstalled gate, unmet S1–S11, an older Essentials release | **`/essentials:upgrade`** (this) |
| Slices vs. the law — layout, manifests, R1–R5, wiring, handler shapes | `/essentials:slice-check` |
| A codebase not on the law at all — infer contexts and candidate slices | `/essentials:slice-discover` |
| What is here and how it connects | `/essentials:slice-map` |
| A **new** project | `/essentials:init` |

The overlap with `slice-check` is deliberate and bounded: both look at the two copied files
(`slice-check` gate 12). `slice-check` reports them as one Advisory line in the middle of a slice
audit; here they are the primary subject, and the **absence** of the lint gate is a finding rather
than a skipped clause. Group D edits slice source only where the release removed or renamed an API a
call site uses; a slice's structure stays `slice-check`'s business.

## Usage

```
/essentials:upgrade           # audit, report, then offer each fix — and the Essentials upgrade when the project is behind
/essentials:upgrade --check   # report only, the full upgrade plan included; writes nothing, asks nothing
```

`--check` is the safe first run and costs nothing — use it when you just want to know. It needs
neither a clean working tree nor Docker.

## Step 1 — Confirm the project, resolve the versions

```bash
# Is this an Essentials project at all?
ls .claude/rules/essentials-slices.md 2>/dev/null
grep -rl 'dk\.trustworks\.essentials' --include=pom.xml --include=build.gradle \
  --include=build.gradle.kts . 2>/dev/null | head -3

# Essentials version the installed plugin targets (the essentials.version pin)
grep -m1 '^| `essentials.version`' "${CLAUDE_PLUGIN_ROOT}/references/stack/stack-pins.md" \
  | grep -oE '[0-9]+\.[0-9]+\.[0-9]+[^ *|]*' | head -1

# Essentials version the project was scaffolded against (may be absent, or an older v<semver> stamp)
grep -rhoE 'essentials-init: (essentials [^ ]+|v[0-9.]+)' CLAUDE.md */CLAUDE.md 2>/dev/null | head -1

# Essentials version the project pins now (the essentials.version property)
grep -rhoE '<essentials\.version>[^<]+' --include=pom.xml . 2>/dev/null | head -1 | sed 's/.*>//'
```

**No Essentials marker ⇒ stop.** Do not audit, do not offer to install anything, do not suggest a
migration. Say that this is not an Essentials project and name `/essentials:init` for a new one.
This gate is the same one `essentials-change` enforces and for the same reason: a command that
starts writing files into unrelated repositories gets uninstalled.

Record `ESSENTIALS_VERSION` (from `stack-pins.md`) and `PROJECT_STAMP`: the version after
`essentials-init: essentials`; `pre-release` for the older `essentials-init: v<semver>` form, which
means the project was scaffolded before the plugin's first release (report it as that and do not
interpret the number); or `absent`. **An absent or `pre-release` stamp is normal** — projects
scaffolded before the first release have one or the other, and init's `skip`/`merge` paths can leave
none. It is at most an Advisory (B3) and never gates a check: every check below reads the project's
actual files, never the stamp.

Record `PROJECT_ESSENTIALS`, the Essentials release the project builds against — not the stamp,
which only says what it was scaffolded for. It is the `essentials.version` property the grep above
finds. When no POM in the project defines it — inherited from a parent outside the project, imported
from a BOM, or every Essentials dependency versioned directly — ask Maven, from the module that
declares the Essentials starter (`./mvnw` when the project has one):

```bash
mvn -q help:evaluate -Dexpression=essentials.version -DforceStdout
# when no parent defines the property either: the version the Essentials artifacts resolve to
mvn -B dependency:tree -Dincludes='dk.trustworks.essentials*' | grep -m1 -o 'dk\.trustworks\.essentials[^ ]*'
```

A property that resolves to nothing, to an unresolved `${…}`, or to something that is not a version
number (`DEV-SNAPSHOT`) is `unknown`. Step 4's `stack-lint.py` reads the same property and reports it
as `facts.essentialsVersion`; when it reports `null` and Maven gave you a version here, pass that
with `--essentials-version`. **The project is behind** when `PROJECT_ESSENTIALS` is below
`ESSENTIALS_VERSION` (stack-lint's `facts.essentialsBehind`). That changes how Group C reports
(Step 4) and turns on Group D (Step 4.5).

Then establish the two facts every conformance check is conditioned on, from the project itself:

| Fact | How |
|---|---|
| `LANGUAGE` | `kotlin-maven-plugin` / `src/main/kotlin` present ⇒ `kotlin`, else `java` |
| `DB_PROFILE` | The Essentials persistence starter in `pom.xml`: `spring-boot-starter-postgresql-event-store` ⇒ `pg-event-sourced`; `spring-boot-starter-postgresql` ⇒ `pg-crud`; `spring-boot-starter-mongodb` ⇒ `mongo` |

If neither can be determined, report that and run only Group A and Group B. **Do not guess a
profile** — every Group C check is profile-conditional, and a wrong profile produces confident
findings that are all false. Guessing here is worse than skipping.

## Step 2 — Group A: the two copied files

These are the only files this plugin ever puts in a project, and both are governed by the same rule:
**offer, never overwrite silently.**

**A1 — Slice-rules pointer.**

```bash
grep -o 'essentials-slices-rules: v[0-9]*' .claude/rules/essentials-slices.md 2>/dev/null | sed 's/.*v//'
grep -o 'essentials-slices-rules: v[0-9]*' \
  "${CLAUDE_PLUGIN_ROOT}/references/slice/project-rules-pointer.md.template" | sed 's/.*v//'
```

Compare the two numbers as integers, never as strings (`10` is newer than `9`).

- Absent ⇒ **Should-fix**. The project has no always-on slice rules; sessions start without the
  directory vocabulary, the four kinds, or the boundary rule.
- Stamp older than the plugin's ⇒ **Should-fix**. Offer to replace from
  `references/slice/project-rules-pointer.md.template`. It is a pointer, not a customisation surface
  — the law is read live from the plugin — so a straight copy is the correct repair. Say that
  plainly; if the user has edited it, replacing loses only local edits to a file that was never
  meant to hold any.
- Equal ⇒ current.

**A2 — Slice-manifest lint gate.** From the project directory:

```bash
TOP="$(git rev-parse --show-toplevel 2>/dev/null)"
PREFIX="$(git rev-parse --show-prefix 2>/dev/null)"   # "" at the top level, "orders-service/" below it
diff "${CLAUDE_PLUGIN_ROOT}/scripts/slice-lint.py" scripts/slice-lint.py 2>&1 | head -5
diff "${CLAUDE_PLUGIN_ROOT}/references/slice/slice-yaml.schema.json" \
     scripts/slice-yaml.schema.json 2>&1 | head -5
# The hook: in the project, or at the top level, where an older init put it for a nested project
ls .githooks/pre-commit "${TOP}/.githooks/pre-commit" 2>/dev/null
diff "${CLAUDE_PLUGIN_ROOT}/references/init-assets/project/.githooks/pre-commit.template" \
     .githooks/pre-commit 2>&1 | head -5
# Is it active? What core.hooksPath says, the hook git runs, and whether that one forwards to this one
HOOKS="$(git config --get core.hooksPath)"
RUNS="$(git rev-parse --git-path hooks/pre-commit)"   # the pre-commit hook git runs, core.hooksPath applied
grep -n "${PREFIX}.githooks/pre-commit" "$RUNS" 2>/dev/null          # a forwarder line (husky: .husky/pre-commit)
test -x "$RUNS" || echo "NOT EXECUTABLE: $RUNS"                      # git skips a hook without the execute bit
test -x .githooks/pre-commit || echo "NOT EXECUTABLE: .githooks/pre-commit"
# Other tools' hooks that setting core.hooksPath would silence (*.sample files are git's own, inert)
ls "$(git rev-parse --git-common-dir)/hooks" 2>/dev/null | grep -v '\.sample$'
# Per clone: a devcontainer whose post-create step could activate it, and a CI job that already runs the linter
ls .devcontainer/devcontainer.json "${TOP}/.devcontainer/devcontainer.json" 2>/dev/null
grep -rln 'slice-lint' "${TOP}/.github/workflows" "${TOP}/.gitlab-ci.yml" "${TOP}/Jenkinsfile" \
  "${TOP}/azure-pipelines.yml" "${TOP}/bitbucket-pipelines.yml" 2>/dev/null
```

`git rev-parse --git-path hooks/pre-commit` resolves the hook the way git does: `.git/hooks/pre-commit`
with `core.hooksPath` unset, the file under it when set, and a relative `core.hooksPath` against the
top level — so a bare `.githooks` in a project below the top level names a file that does not exist,
which is the inactive case below.

- **Not installed at all** ⇒ **Should-fix**, and offer the install. This is the case
  `/essentials:slice-check` gate 12 cannot see: its clause is conditional on the gate already
  existing, so a project scaffolded before `/essentials:init` offered it is never told the gate
  exists. Offer the same three options init's Step 9 offers — pre-commit hook, script only, or
  neither. The hook body is `${CLAUDE_PLUGIN_ROOT}/references/init-assets/project/.githooks/pre-commit.template`
  (it has no placeholders; copy it as `.githooks/pre-commit` in the project, executable, and activate
  it as below); copy the schema beside the script (**required**, not optional: installed into a
  project the script has no plugin root to walk up to).
- **Installed but inactive** ⇒ **Should-fix**: a hook git never runs is no gate. It is active only
  when it is both wired and executable:
  - **Wired**: `core.hooksPath` is `${PREFIX}.githooks` (or that directory's absolute path), or the
    pre-commit hook git does run — `RUNS`; for husky, `.husky/pre-commit` — has a line calling
    `${PREFIX}.githooks/pre-commit`. A `core.hooksPath` of bare `.githooks` in a project below the top
    level is not wired: a relative hooks path resolves against the top level, where no such directory
    exists, so the hook never ran.
  - **Executable**: neither `test -x` line printed `NOT EXECUTABLE` (for a file that does not exist,
    that line means not wired, not this). Git skips a pre-commit hook that
    lacks the execute bit, prints a hint, and commits; a forwarder that calls a project hook without it
    fails with `Permission denied` and blocks every commit, whether it stages a `slice.yaml` or not.
    An editor or a copy that rewrites the file in place can drop the bit (a `755` hook comes back
    `600`), and the wiring check alone still reads such a hook as active. The fix is `chmod +x` on each
    file named; when git tracks `.githooks/pre-commit` without it (`git ls-files -s .githooks/pre-commit`
    shows mode `100644`), also offer `git update-index --chmod=+x .githooks/pre-commit`, so that the
    next clone checks the hook out executable.

  Anything else is inactive. Offer the activation `/essentials:init` Step 12 performs, by its cases
  1–4: set `core.hooksPath` to `${PREFIX}.githooks` only when it is unset and the `ls` of `.git/hooks`
  above printed nothing. When it printed any hook — a `pre-commit`, or another tool's `post-commit` or
  `post-checkout` — recommend the forwarder line instead, in `.git/hooks/pre-commit` (created with a
  `#!/usr/bin/env bash` line and the execute bit when only other hooks exist), and name the hooks
  `core.hooksPath` would silence. With `core.hooksPath` already set, the forwarder goes in the hook
  git already runs. State the trade-off in the offer: `core.hooksPath` replaces every hook in
  `.git/hooks` rather than adding to them, and both it and a forwarder in `.git/` are local to this
  clone. Every other clone runs the activation once: when the project `README.md` has no
  "Slice-manifest lint gate" section, offer that section from a scratch render (Step 6) with
  `lintGate` set to the project's choice; on no, print the command for the user.
- **The self-test** — offer it once the hook reads as wired and executable, and again after any
  activation, `chmod` or refresh this run applies. The static checks above say how the hook is set
  up; only git running it says that a commit is actually checked. The hook decides whether to run
  from the names staged in the index, but the linter it calls reads the files in the working tree.
  So the test needs both: an unparseable `slice.yaml` in a temporary directory inside the project,
  and that file staged in a **temporary index** (`GIT_INDEX_FILE`), a copy of the real one. Git then
  runs the pre-commit hook the way a commit would, through `git hook run` (git 2.36 or later), and the
  hook must exit non-zero, naming the test file. Then the temporary directory and the temporary index
  are removed. The real index and every tracked file stay as they were; the only trace is an
  unreachable blob that `git gc` prunes. When `RUNS` is not the project hook itself — a forwarder in
  another tool's hook, a hook manager's wrapper — the test runs that whole hook, other tools' steps
  included; say so in the offer. From the project directory:

  ```bash
  # A2 hook self-test: from the project directory; leaves the real index and every tracked file as they were
  (
    T="$(mktemp -d)"; P=".slice-hook-selftest.$$"
    trap 'rm -rf "$T" "$P"' EXIT
    mkdir "$P" && printf '%s\n' 'slice: selftest.broken' 'kind: command' \
      'endpoints:' '  - { method: POST, path: /selftest/{id} }' > "$P/slice.yaml"
    cp "$(git rev-parse --git-path index)" "$T/index" 2>/dev/null   # no index yet: git starts an empty one
    GIT_INDEX_FILE="$T/index" git add -f -- "$P/slice.yaml"
    GIT_INDEX_FILE="$T/index" git -c advice.ignoredHook=true hook run --ignore-missing pre-commit >"$T/out" 2>&1
    rc=$?
    if [ $rc -ne 0 ] && grep -qF "$P/slice.yaml" "$T/out"; then echo "SELF-TEST PASS: git ran the hook and it blocked an unparseable manifest"
    elif grep -q 'is not a git command' "$T/out"; then echo "SELF-TEST NOT RUN: git hook run needs git 2.36 or later"
    elif grep -q 'not set as executable' "$T/out"; then echo "SELF-TEST FAIL: git skipped the hook, which is not executable"
    elif grep -q 'Permission denied' "$T/out"; then echo "SELF-TEST FAIL: a hook git runs could not run the next one, which is not executable"
    elif [ $rc -eq 0 ] && [ ! -s "$T/out" ]; then echo "SELF-TEST FAIL: git runs no pre-commit hook in this clone"
    else echo "SELF-TEST FAIL: the hook ran but did not block the unparseable manifest (exit $rc)"; fi
    cat "$T/out"
  )
  ```

  Take the first line as the verdict and show the output under it. A `FAIL` is the inactive finding
  above, whatever the static checks said: fix by its line, then run the test again. `NOT RUN` leaves
  the static checks as the only evidence; say so. A hook that ran and let the manifest through on a
  machine with neither `uv` nor `python3` is the hook's documented fail-open, not a wiring fault.
- **A hook is per clone, and CI is the gate.** Every activation above — `core.hooksPath`, a forwarder
  in `.git/hooks` — is configuration of this clone, which a fresh clone does not carry, and
  `git commit --no-verify` skips the hook on any clone. Say this in the report whenever the project
  has a hook: the hook is a convenience that fails a commit early, and the CI job is the gate that no
  contributor can bypass.
  - **A devcontainer** (`.devcontainer/devcontainer.json` above): its post-create step runs on every
    fresh container, so it can activate the hook for every contributor who uses it. When the
    activation is `core.hooksPath` (case 1), offer this line in the script `postCreateCommand` runs,
    or in `postCreateCommand` itself when it calls no script; it leaves a `core.hooksPath` that is
    already set alone, and works from any directory of the repository:
    `[ -n "$(git config --get core.hooksPath)" ] || git config core.hooksPath "${PREFIX}.githooks"`,
    with `${PREFIX}` written out (`orders-service/.githooks`, or `.githooks` at the top level). A
    forwarder in a hook-manager file the repository tracks (`.husky/pre-commit`) already reaches
    every clone and needs no line; a forwarder in `.git/hooks` cannot be added safely from a
    post-create step, so say that each clone adds it by hand.
  - **CI**: when the `grep` above found a CI file that runs `slice-lint`, name it and say the gate
    holds. When it found none, the manifests are checked only on clones whose hook is active ⇒
    **Advisory**, and **Should-fix** when this clone has no active hook either (a "script only"
    install included), since then nothing runs the linter at all. This check applies to every
    installed gate, hook or not. Offer a step that runs the project README's command from the project
    directory and fails the build on any exit but 0. Prefer `uv`, which reads the pins in the script's
    own PEP 723 header. On a runner without `uv`, install `pyyaml` and `jsonschema` at the versions in
    that header, never unpinned. Add it to the CI file the repository already has, beside its build
    step. The `--baseline` ratchet below goes into this step.
- **A hook at the top level only** (`${TOP}/.githooks/pre-commit`, the project below the top level)
  ⇒ part of the refresh below: the current hook locates its project from its own path, so it belongs
  in the project's `.githooks/`. Offer to move it there and activate it; leave a top-level hook that
  carries lines of its own, and say why.
- **Installed but differing** — the script, the schema or the hook differs ⇒ **Advisory**. A project
  running a stale schema validates against yesterday's contract and reports a clean pass it has not
  earned, and a stale hook can call the script in a way it no longer runs. The refresh replaces the
  script, the schema and the hook together, as one fix — they are one contract and must not drift
  apart. A schema copy older than 1.4 also rejects `flow`, `scenarios` and `requirements`, the
  optional fields a spec-driven generator writes: the generator checks the project's copy for an
  `x-manifestVersion` of `"1.4"` or later and, when it is absent, writes none of them and sends the
  user here (`manifest-guide.md` §3). The refresh
  is what lets those fields in. A project that chose "script only" has no `.githooks/pre-commit`;
  that is not a finding, and the refresh covers the other two. If the project's hook carries lines of
  its own beyond the template's, show the diff and offer to merge rather than replace.

  **Measure the refresh before offering it**, exactly as `/essentials:slice-check` § Refreshing the
  lint gate does: the plugin's copy over the project with `--baseline` of the project's copy's `--json`
  output, handed over by process substitution so no file is written —
  `slice-lint.py . --json --baseline <(uv run --script scripts/slice-lint.py . --schema scripts/slice-yaml.schema.json --json)`. What the second run prints is
  what the refresh adds, and every one of those findings fails the hook and CI. State it in the offer:
  "the refresh adds N findings in `<files>`; the hook and CI fail on them until they are fixed". Then
  offer the manifest fixes **before** the refresh, one file at a time, by that section's list — for
  `14 tier`, `tier: cqrs-es` where the BC is event-sourced, otherwise delete the `tier` line — so the
  gate never goes red; rerun the measurement, and offer the refresh with what is left. Where findings
  stay, also offer `--baseline` as a ratchet: commit the installed copy's `--json` output and pass
  `--baseline <that file>` in CI, so existing findings stop failing the build and new ones still do.
  It belongs in CI: the hook passes no baseline, and a baseline added to the hook is a project edit
  the next A2 diff reports.

  Say what the refreshed hook changes for contributors: it **blocks** a commit when the linter exits 2
  — a `python3` without `pyyaml` or `jsonschema`, and no `uv` — where an older hook let it through, and
  it fails when `scripts/slice-lint.py` is missing; only a machine with neither `uv` nor `python3`
  still commits unchecked. Contributors need `uv`, or `python3` with `pyyaml` and `jsonschema`.
- Identical and active — wired, executable, and the self-test passed where it ran ⇒ current.

**No third file joins these two.** If a check below wants a file copied into the project to fix
itself, that is a design decision to raise with the user, not one to take here.

## Step 3 — Group B: the orientation files

**B1 — Project `CLAUDE.md` framework-knowledge block.** Grep for `Trustworks Essentials framework
knowledge`. Absent ⇒ **Should-fix**: the `essentials-docs` skill still auto-loads on code signals,
but the project has no explicit instruction to prefer it over web search or recall. Offer to insert
the `## Trustworks Essentials framework knowledge` section of
`${CLAUDE_PLUGIN_ROOT}/references/init-assets/project/CLAUDE.md.template` (with
`{{essentialsVersion}}` replaced by `ESSENTIALS_VERSION`) **near the top,
preserving everything already in the file** — this is an insertion, never a re-render of the
template over the user's file.

Present ⇒ compare its content with the template's: each section from its heading to the next `## `
heading, without the `essentials-init:` stamp line, whitespace normalised.

```bash
section() { awk '/^## Trustworks Essentials framework knowledge/{p=1;next} p&&/^## /{exit} p' "$1" \
  | grep -v 'essentials-init:' | tr -s ' \t\n' ' '; }
[ "$(section CLAUDE.md)" = "$(section "${CLAUDE_PLUGIN_ROOT}/references/init-assets/project/CLAUDE.md.template")" ] \
  || echo DIFFERS
```

A difference ⇒ **Advisory**: the block still describes what an older plugin shipped. Show the diff
and offer to replace that section only — everything outside it stays, and so does the project's stamp
line, which Step 8 owns.

**B2 — Workspace pointer.** Only when the project is in a subdirectory of the invocation root: if
`../CLAUDE.md` exists but carries no Essentials pointer block, that is **Advisory** — planners
launched at the workspace root have no signal that the project lives in `<artifactId>/` and may
scaffold at the wrong root or invent their own pins. Offer the pointer from
`${CLAUDE_PLUGIN_ROOT}/references/init-assets/project/WORKSPACE-CLAUDE.md.template`, rendered for this
project (Step 6, "A scratch render").

**B3 — Version stamp.** Absent, or in the older `essentials-init: v<semver>` form ⇒ **Advisory**, and
repairing it is free: set `<!-- essentials-init: essentials <ESSENTIALS_VERSION> -->` under the
framework-knowledge heading, replacing any older stamp. Do **not** back-date it to a version you
inferred — stamp the version the installed plugin targets, and only once the run has actually applied
what the plugin ships (Step 8).

## Step 4 — Group C: conformance with the current stack contract

This group exists because of the failure class that produced S2.1: **most of these compile cleanly
and kill context startup**, several under a message that names nothing relevant. A project that
predates a requirement has no way to discover it except by booting and reading a misleading stack
trace.

**The decidable half is a script, and its output is taken verbatim.** Whether a POM declares an
artifact, a config file carries a key, or a source file imports a class is a fact, and a model
reading a POM confidently gets such facts wrong. Run:

```bash
python3 "${CLAUDE_PLUGIN_ROOT}/scripts/stack-lint.py" . --json
```

It detects `LANGUAGE`, the profile, the web stack and the frontend mode itself (`facts` in the
output; pass `--language`/`--db`/`--web`/`--frontend` only when Step 1 had to ask the user, and
`--essentials-version` only when Step 1 resolved `PROJECT_ESSENTIALS` through Maven). Report
every finding as it comes — `id` (`ESS-S<n>`), severity, `file:line`, `message`, `fix.text` — and
list `notRun` under "not run" with its reason. **Do not re-derive, re-grade or drop a finding**, and
do not add a finding for a row the script covers because the file "looks" wrong to you: that is the
second, weaker audit this split exists to prevent. Exit 2 means the script could not run (no
`pom.xml`, a POM that does not parse, a Gradle-only build): report "Group C: not run" with its
stderr line and run the judgement rows only. Never fall back to checking the decidable rows by hand.

**A project behind the plugin's Essentials release.** The contract describes `ESSENTIALS_VERSION`,
and some of its requirements do not hold on the release before it: there, the project's current
state is the correct one, and the "fix" breaks it — removing Jackson 2 `jackson-databind` from a
0.50 project takes away the type its starters' serializer beans are declared against. `stack-lint`
marks each such finding `targetOnly`, and `appliesWithUpgrade` as well when `facts.essentialsBehind`
is `true`. Report every `appliesWithUpgrade` finding in its own block of Group C, labelled
**applies with the Essentials upgrade**, and never offer it as a fix on its own: Group D applies all
of them, in the release's order, right after it moves the pins (Step 7.5). Every other finding holds
on the release the project runs and is reported and offered as usual, on a project that is behind
too. When `facts.essentialsBehind` is `null` (the release is unknown, or not a version number)
nothing is labelled, the header says Group C assumes the plugin's release, and Group D does not run.
A judgement finding you raise takes the same test: if what it asks for exists only in
`ESSENTIALS_VERSION`, it goes in that block.

What the rows cover, so the report can say what was checked and what was clean:

| # | Requirement | `stack-lint.py` checks | Judgement (yours, reading the contract live) |
|---|---|---|---|
| C1 | **S2.1** | `s2.1-*` — every row of S2.1's table for the profile and web stack, at compile scope | — |
| C2 | **S3.1 / S3.2 / S3.4** | `s3.1-*`, `s3.4-*` — no Jackson 2 Essentials module; Jackson 3 not overridden to a newer minor than Spring Boot manages (`s3.1-jackson3-override`, Should-fix: offered per Step 4, never applied unasked); on Kotlin, Jackson 3 `jackson-module-kotlin` and an own persistence serializer bean of the type the starter backs off from | Whether that bean really builds on `EssentialsObjectMappers` (S3.2) |
| C3 | **S3.3** | `s3.3-own-json-mapper` | — |
| C4 | **S4** | `s4-*` — exactly one `Essentials*WebConfigurer` `@Import`ed, matching the web stack (`EssentialsWebMvcConfigurer` on spring-webmvc, `EssentialsWebFluxConfigurer` on spring-webflux). `s4-typed-edge-unregistered` (Blocking) names each `@PathVariable` / `@RequestParam` typed as a project `CharSequenceType` / `SingleValueType` that only the Essentials converter can build, in a project without the dependency or the `@Import`: its symptom is **HTTP 500 on a well-formed request** (`ConversionNotSupportedException`), never a startup failure, and it then stands in for `s4-configurer-missing` / `s4-types-spring-web-missing` | — |
| C5 | **S3.5 / compiler** | `s3.5-*` | Constructor parameter names that differ from their JSON properties |
| C6 | **S5** | `s5-*` — the `DocumentDbRepositoryFactory` bean, no document store on `mongo`, no `transactional-mode` key, aggregates declared | Writes outside the `UnitOfWork`; handler idempotency |
| C7 | **S2** | `s2-*` — one starter; `spring.mongodb.*` connection keys; the modules the starters do not bring (`eventsourced-aggregates`, `kotlin-eventsourcing` on Kotlin, `postgresql-document-db`) | — |
| C8 | **S9** | `s9-admin-spi` | A `SecurityConfig` exists and someone has **decided** what it says. A permit-all left exactly as `/essentials:init` emitted it is **Should-fix** — the contract's word is that security is decided, never inherited |
| C9 | **S7** | `s7-*` — the spec is regenerated by an integration test, the committed spec exists, the frontend builds from it, and `SingleValueTypeModelConverter` is registered wherever springdoc runs (without it every generated client types the semantic ids as objects). Whatever is left of the forked-app pipeline (`springdoc-openapi-maven-plugin`, a `spring-boot-maven-plugin` start/stop execution, `application-openapi.*`) is `s7-forked-pipeline`, reported whether or not a test now writes the spec; a missing `springdoc.writer-with-order-by-keys: true` is `s7-key-order`. Without a test writer `s7-spec-generation` fires too: the fetch serves a stale spec silently once an endpoint needs the database | — |
| C10 | **S10** | `s10-*` — Testcontainers 2.x coordinates and packages, S10's `@AutoConfigureWebTestClient` package, Failsafe bound | Context caching and fork reuse |
| C11 | **S8** | `s8-*` — both half-choices: embedded without CORS, base URL or dead pieces and with its SPA fallback and static copy; standalone with a base URL its Orval mutator reads from the build-time environment and prefixes (`VITE_*`, `NEXT_PUBLIC_*`, `REACT_APP_*` — `facts.frontendTooling`), a `CorsConfigurationSource` whose origins are bound through `@ConfigurationProperties` (`@Value` on a list key Blocking, on a scalar Should-fix: `s8-standalone-cors-value-scalar`), no wildcard with credentials; the generated client git-ignored (the paths `orval.config.*` names) | Whether the chosen mode is the one the deployment needs |
| C12 | **S1 / S11** | `s1-*`, `s11-*` — the Boot line and Java baseline, one `essentials.version`, the Kotlin `jvmTarget` and compiler floor, the `skip-frontend` profile, `spring-boot-starter-parent`; and the patch floors, Advisory and offered per Step 4: Boot below the pinned patch of its line (`s1-boot-patch`), Kotlin at or above the floor but below the pin (`s1-kotlin-patch`), a Maven wrapper that downloads an older Maven than the pin (`s11-maven-wrapper`; not run without a wrapper) | — |

Severities are the script's; the judgement rows carry the severity in their text. **A judgement
finding needs a reason that names the file and the contract line**, the same standard the script
meets.

**Version pins move with a release, in Group D — the floors below aside.** Group D moves
`essentials.version` and the platform pins that release requires, under a decision note the user
approves — never a pin the release does not require, and never one of the floors below. Beyond the
findings `stack-lint` reports, do not compare the project's pins against `stack-pins.md`, do not
report a lag, and do not offer a bump: such a move has a blast radius across the whole application,
the project `CLAUDE.md` says pins are fixed and upgrades want an ADR, and no release asked for it.
If the user asks for a pin review, that is a separate conversation.

**Four specific floors are the exception, and each is offered on its own.** They are not a lag
report: each names the one release the Essentials build itself runs on, and `stack-lint` reports it
only below that release, never at or above it. `s1-boot-patch` (Advisory) — Spring Boot on the
targeted line but below its pinned patch, which manages dependency releases carrying security fixes
the earlier patch lacks. `s1-kotlin-patch` (Advisory) — a Kotlin compiler at or above the floor but
below the pin. `s11-maven-wrapper` (Advisory) — a Maven wrapper that downloads an older Maven than
the pin. `s3.1-jackson3-override` (Should-fix) — Jackson 3 overridden to a newer minor than Spring
Boot manages, which Essentials is not tested on and whose wire-format check it blinds; its fix
removes the override, it moves no pin up. Report each as the script states it and offer it in Step 6
like any other finding: one at a time, never applied without a yes, never bundled with another
move, and never folded into Group D's pins or its decision note. `s1-kotlin-patch` and
`s3.1-jackson3-override` are `mechanical: false`: show the change and confirm. None of them is
`appliesWithUpgrade`, and none fires beside the rule above it: a Boot version off the line is
`s1-boot-line` alone, a compiler below the floor `s1-kotlin-compiler-floor` alone. When Group D runs
and its plan moves the same pin (`pins[]`, `status: move`), the release move already takes it to the
same target: name the finding as covered by Group D and do not offer it in Step 6. On a project that is not behind,
`s1-boot-line` and `s1-java-baseline` are reported and never offered: with no release move to put
them under, moving the Boot line or the Java baseline is the user's decision.

**One pin move outside Group D: `s1-kotlin-compiler-floor` without `appliesWithUpgrade`.** It is not
a lag but a project that cannot compile — its own `java.version` is one the Kotlin compiler it pins
cannot target — whatever Essentials release it is on. Its fix raises `kotlin.version` to the
`stack-pins.md` pin and touches nothing else; it is `mechanical: false`, so show it and confirm
before applying (Step 6). With `appliesWithUpgrade` it is one of the pins Group D moves.

**A finding here can be a defect in the contract rather than in the project.** If the project is
missing something S1–S11 does not state, or if a check contradicts what the project plainly does,
say so and stop rather than "fixing" the project into agreement. That is the S2.1 lesson: the
requirement existed in `references/llm/` for the plugin's whole life and still shipped four broken
projects, because the contract never restated it.

## Step 4.5 — Group D: plan the Essentials upgrade

Only when `facts.essentialsBehind` is `true`. The plan is a script's, and it is taken verbatim —
never re-derived, reordered or added to:

```bash
python3 "${CLAUDE_PLUGIN_ROOT}/scripts/migration-plan.py" . --json    # read from standard output; nothing is written
```

Pass `--from <PROJECT_ESSENTIALS>` when Step 1 resolved it through Maven. The plan chains the
release-move notes in `references/stack/migrations/` from the project's release to the plugin's. It
holds `pins[]` — each pin the release requires, with the project's value and where it is set (`at`),
the `stack-pins.md` target, a `status` (`move`, `ok`, `tolerated`, `not set`, `unknown`), the
`fix.ops` that move it, its `tolerated` versions, `reason` and `cite`; `steps[]` in the release's
order, each with a `kind` (`compile-and-fix` on the `from` or the `to` release, `checklist` with a
`phase`, `pins`, `stack-lint`, `review-scan`), its catalogue ids and citations, and the
`review-scan --tree` and `stack-lint` findings filed under it verbatim (`also` names a stack-lint
finding that is the same defect); `fixedIn[]` — what the release fixes, whose project workarounds
become removable; and `outside`, the count of findings that are not part of the move (Group C owns
those). Exit 2 means it could not run — no notes cover the project's release, or a scanner failed:
report "Group D: not run" with its stderr line. Where no notes cover the release, `ESS-091` and
`ESS-092` in `references/llm/LLM-traps.md` § Upgrading name the migration guides to follow by hand.

**Views that will start warning.** Only when the move crosses into 0.60.1 — the plan's `from` is <!-- cite-ok: 0.60.1 is the release that added the warning, not the pin -->
below `0.60.1` and its `to` is `0.60.1` or later, compared as versions, never as strings. From that <!-- cite-ok: 0.60.1 is the release that added the warning, not the pin -->
release on, a `ViewEventProcessor` logs a WARN for each event type it receives and has no handler
for, and at start a WARN for each type that a sealed supertype of a handled type permits and that it
neither handles nor ignores (logger `…eventstore.postgresql.processor.UnhandledEventTypes`;
`docs/RELEASE-NOTES-0.60.1.md` §1.1.11, and `@IgnoresEventTypes` in §2). The startup half is <!-- cite-ok: 0.60.1 is the release that added the warning, not the pin -->
predictable from the source, so predict it while the project is still on the old release:

```bash
uv run --script "${CLAUDE_PLUGIN_ROOT}/scripts/slice-source.py" . --check --json   # read .unhandledVariants
```

Take `unhandledVariants` verbatim — each entry a view slice, the processor's `file:line`, the sealed
`parent` and the `variants` no handler accepts and no `@IgnoresEventTypes` lists — and ignore the
findings in the same output, which belong to `/essentials:slice-check`. Exit 1 only means it has
findings; exit 2 or 3 means the list is incomplete: say "unhandled variants: not predicted" with its
stderr line, and go on. Each entry gets one of two fixes, and choosing it is the user's: **handle** the
variant in the view (a `@MessageHandler` for it, when the read model should reflect it), or
**annotate** the processor with `@IgnoresEventTypes` naming it (when skipping it is deliberate). The
annotation exists only from 0.60.1 on, so neither fix is applied before the pins move, and Group D <!-- cite-ok: 0.60.1 is the release that added the warning, not the pin -->
applies neither: they are slice source that no migration step pins. They go in the decision note and
in the after-gate list (Step 7.5). The per-event WARN for a type outside every sealed hierarchy cannot
be predicted from the source; say that it surfaces in the logs once the moved project runs.

**A rerun after Group D stopped** finds the project on its `essentials-<to>` branch with
`essentials.version` already moved, so it is no longer behind. On that branch, ask which release the
move started from (the decision note names it) and plan with `--from` it: the steps already applied
show nothing left, and Step 7.5 skips the baseline and the `from` release's step — that release is no
longer what the branch builds.

Then check what applying the plan needs, for the report; `--check` needs none of it:

```bash
git status --porcelain --untracked-files=no                     # must print nothing: a clean working tree
docker info >/dev/null 2>&1 && echo docker-ok                   # the gate starts the context on Testcontainers
p=".essentials-write-probe.$$"; : > "$p" && rm -f "$p" && echo write-ok   # the branch, the edits and the build write here
git rev-parse --verify --quiet "refs/heads/essentials-${TO}"    # must not exist yet (TO = the plan's `to`)
```

Changes in the tree, no Docker, no git repository, a project this shell cannot write (no `write-ok`: a
read-only mount, a sandbox that allows no writes), or an `essentials-<to>` branch that exists and is
not the current one block Group D for this run; the report says which, and the run offers everything else. A release
move has no compile-only outcome: without Docker it does not start.

## Step 5 — Report before you write

Print the whole report first — every group, findings and non-findings, with the severity vocabulary
`/essentials:slice-check` uses (Blocking / Should-fix / Advisory). A run with nothing to do says so
in one line and exits.

```
ESSENTIALS UPGRADE — <project name>
  Scaffolded for: essentials <PROJECT_STAMP>   (or: before the plugin's first release · or: stamp absent)
  Project pins:   essentials <PROJECT_ESSENTIALS>   (or: unknown — Group C assumes the plugin's release)
  Plugin targets: essentials <ESSENTIALS_VERSION>
  Detected:       kotlin · pg-event-sourced

  A · Plugin copies
    [Should-fix] Slice rules pointer is v<project>; plugin ships v<plugin>
    [Should-fix] Slice-manifest lint gate installed but inactive (core.hooksPath is .githooks; project is orders-service/)
  B · Orientation
    [Advisory]   Framework-knowledge block differs from the plugin's
    [Advisory]   No version stamp in CLAUDE.md
  C · Stack contract (stack-lint: 5 findings)
    [Blocking]   ESS-S2.1 s2.1-jdbi  backend/pom.xml:41
                 org.jdbi:jdbi3-core is not declared — the starter declares it `provided`, so nothing
                 brings it; compiles, then fails at context startup
    [Blocking]   ESS-S2 s2-eventsourced-aggregates  backend/pom.xml:41
    [Advisory]   ESS-S10 s10-tc-package  backend/src/test/kotlin/…/IntegrationTestBase.kt:7
    [ok]         S3.x, S4, S5, S7, S8, S9, S11
    [Should-fix] S9 (judgement) — config/SecurityConfig is the permit-all init emitted, unchanged
    Applies with the Essentials upgrade (<PROJECT_ESSENTIALS> → <ESSENTIALS_VERSION>) — applied by Group D
    [Blocking]   ESS-S1 s1-boot-line  pom.xml:8
    [Advisory]   ESS-S3.1 s3.1-jackson2-databind  backend/pom.xml:119
  D · Essentials upgrade <from> → <to>   (references/stack/migrations/<notes file>)
    Pins:   essentials.version <from> → <to> (Essentials) · spring-boot-starter-parent, java.version (platform)
    Steps:  9 · 7 findings filed under them, 5 mechanical · 19 checklist items · 3 workarounds removable
    Views:  2 will warn about event types they neither handle nor ignore   (only when the move crosses the release that added the warning)
    Ready:  clean tree · Docker running   (or: blocked — uncommitted changes; commit or stash, then rerun)

  6 fixes offered · 2 Blocking · 2 apply with the Essentials upgrade · Essentials upgrade offered
```

The footer counts the fixes Step 6 will offer and the Blocking findings among them. An
`appliesWithUpgrade` finding, or a pin move Step 4 does not offer, is never one of them; the
first kind gets its own count. Under `--check` it reads `N fixes available (run without --check to
apply)` instead, since that run offers nothing.

Under `--check`, print the plan in full after the report — every pin, every step with its findings,
every checklist item and every removable workaround — as the script prints it, then stop. Write
nothing, ask nothing:

```bash
python3 "${CLAUDE_PLUGIN_ROOT}/scripts/migration-plan.py" .     # with --from as in Step 4.5
```

## Step 6 — Offer each fix, one at a time

Blocking first, then Should-fix, then Advisory. For each, `AskUserQuestion` with **Apply / Skip /
Show me the change first**. Never bundle unrelated fixes behind one confirmation, and never apply an
Advisory without asking because it "seemed safe". Nothing in the "applies with the Essentials
upgrade" block is offered here — Group D applies it — and no pin move but the ones Step 4 names.

**A `stack-lint` finding carries its own fix.** `fix.text` is what to show; `fix.ops` is the edit.
Apply the ops exactly as `commands/review.md` § Applying a fix descriptor specifies — the one
applier spec `/essentials:review --fix` uses too: one op at a time, the location re-read and its
signature confirmed first, skip and re-run the lint on a mismatch, and `mechanical: false` shown
and confirmed rather than applied.

**A scratch render** supplies whole files the contract describes and a fix needs — the
`OpenApiContractIT` of C9, a `config/` class, the workspace pointer of B2, the README section of A2.
Build the answers JSON from the project's facts (language, profile, web stack, frontend mode, whether
`backend/compose.yml` exists, `lintGate: none` unless A2 needs the README section, the name and
coordinates from the POM, and the package of the `@SpringBootApplication` class) and render it into an
empty scratch directory outside the project, the answers on standard input (inside the JSON, each `'`
as `'\''`):

```bash
S=$(mktemp -d)
printf '%s\n' '<answers JSON>' | python3 "${CLAUDE_PLUGIN_ROOT}/scripts/init-render.py" --answers - \
  --out "$S/render" --workspace-out "$S/workspace"     # --workspace-out only for the B2 pointer
```

Where `mktemp -d` fails (a read-only filesystem, a sandbox that allows no writes), print the render
instead: the same pipe with `--tree` in place of `--out` and `--workspace-out`. A file is the text under
its `=== FILE <path> (<mode>) ===` line up to the next `===` line, the workspace pointer is
`@workspace/CLAUDE.md` (a closing `\ No newline at end of file` line means its last line has none), and
`=== COPY <path> <- <plugin path>` is that plugin file unchanged. Then offer
the file the finding needs (`OpenApiContractIT` extends the rendered `IntegrationTestBase`;
offer that too when the project has no base of its own). Never copy more than the finding needs, and
never over a file the project already has — the project's version wins, and the difference is a
conversation.

**Moving off the forked-app spec pipeline (C9, `s7-forked-pipeline`)** is one fix, offered after the
replacing test has passed. It removes the residue as a checklist, each item shown before it goes:
`springdoc-openapi-maven-plugin`; the `spring-boot-maven-plugin` start/stop executions;
`application-openapi.*`; any execution whose output only the removed ones read (a
`build-helper-maven-plugin` `reserve-network-port` that only gave the forked app its port — keep one
that anything else reads); and `springdoc.writer-with-order-by-keys: true` when `s7-key-order` fired,
by its fix descriptor. Then list, and do not edit, what still names the pipeline across sources,
docs, CI and Dockerfiles; a hit in slice source is reported only:

```bash
grep -rnE '@Profile\("[^"]*openapi"\)|profiles\.active=openapi|application-openapi|openapi-start' . \
  --exclude-dir={.git,node_modules,target}
```

Warn before the first test run that the test writer rewrites `contracts/openapi.json` once, in its
own key order and format: commit that reformat on its own, so a reviewer does not read it as an API
change.

What a fix may touch: `backend/pom.xml` dependencies and build-plugin configuration, the `config/`
classes S1–S11 names, `application.yml` keys, the test sources S7 and S10 name
(`OpenApiContractIT`, `IntegrationTestBase`), `.claude/rules/`, `scripts/`, `.githooks/`, the hook
activation of A2, the project README's lint-gate section, and the project `CLAUDE.md`.

What a fix may **never** touch — these are not defaults to weigh, they are the boundary:

- **Slice source.** Anything under a bounded context is `/essentials:slice-check`'s business, and
  its repairs are opt-in there for the same reason. Group D's API migrations are the one exception,
  bounded in Step 7.5.
- **The skeleton.** No re-render over the project, no regenerated `Application.kt`, no parent POM
  rewrite.
- **Version pins**, per Step 4: only Group D moves them, and only those the release requires —
  besides the Kotlin compiler a project's Java baseline outgrew and the floors Step 4 names, each
  offered on its own.
- **Elicitation.** Never re-ask the init questions. Language, profile, frontend mode and coordinates
  are facts of the project now; read them, do not re-choose them.

## Step 7 — Verify, but only what you changed

If nothing in Group C was applied, skip this step and say so.

If a dependency, a compiler flag, a config class or a config key changed, the change is in exactly
the class of edits that compiles and then fails at context startup — so compiling is not enough:

```bash
python3 "${CLAUDE_PLUGIN_ROOT}/scripts/stack-lint.py" . --json   # the applied findings are gone
./mvnw -B verify 2>&1 | tail -60                                   # from the reactor root; needs Docker
```

`verify` is what starts the context — through the project's `*IT` tests (a project `/essentials:init`
rendered has `ApplicationContextIT`); a project with no integration test that starts the context has
no check here, so say so rather than calling a compile a pass. Add `-Pskip-frontend` when the
project has that profile. Without Docker, run `test-compile` and report the context start as
unverified. Use `mvn` when there is no wrapper, and say which you used. `/essentials:init` Step 13.7's symptom →
requirement table applies verbatim to reading the output — map a failure back to the requirement
rather than improvising dependencies until it boots.

**If the context started before this run and does not now, the run made it worse.** Say that
plainly, name the fix that did it, and offer to revert exactly that fix. Do not chase it with
further changes.

This command — `./mvnw` (else `mvn`) `-B verify`, plus `-Pskip-frontend` when the project has that
profile, from the reactor root — is also **the gate** of Group D. When it cannot be derived (no
wrapper and no `mvn`, or no `pom.xml` at the reactor root), Group D asks the user for the command,
uses it for this run only, and never records it.

## Step 7.5 — Group D: move the project to the plugin's Essentials release

Only when Step 4.5 produced a plan and nothing blocks it, and never under `--check`. It runs after
Steps 6 and 7, so the move starts from a project whose own fixes are in and verified. The working
tree was clean when the run started; the only changes in it now are the ones Step 6 applied. Re-run
the plan when Step 6 applied anything — those fixes can change what it files under its steps.

**1. The decision note — the first confirmation.** Print it, built only from the plan:

- **From → to**, and the notes file the plan used.
- **Every pin it moves**, from → to, marked **Essentials** (`essentials.version`) or **platform**
  (the rest), with its `reason` and `cite`; the pins it leaves (`ok`, `not set`) named once.
- **The findings it closes** — those filed under the `pins`, `stack-lint` and `review-scan` steps,
  with id, `file:line` and `fix.text`.
- **The code migrations and their risks**: which edits are mechanical; which are per call site and
  confirmed one at a time (a `candidate`, a fix that is not mechanical); and that the
  `compile-and-fix` steps can be counted only once the compiler runs, each error fixed by the
  migration guide the step cites, in slice source too, never structurally. When no integration test
  starts the context, say that the gate then proves compile and tests, not startup.
- **The checklist items**, by phase, with ESS id, symptom and link — the judgement the move needs and
  no script makes.
- **The workarounds the release makes removable** (`fixedIn`).
- **The views that will start warning** (Step 4.5, when the move crosses the release that added the warning): each
  `unhandledVariants` entry with its slice, `file:line`, parent and variants, and the two fixes —
  handle the variant, or name it in `@IgnoresEventTypes` — for the user to apply once the pins
  have moved.

`AskUserQuestion`: **Approve and print only** / **Approve and write it to a file** / **Stop**. A
written note is the ADR: offer `docs/adr/NNNN-essentials-<to>.md`, `NNNN` the next number in that
directory, when `docs/adr/` exists, else ask for a path. The path is always the user's — this
command keeps no file of its own. The note is written once the branch exists (4.).

**2. The pins — one combined ask.** List each pin with `status: move`: from → to, its `stack-pins.md`
row, its `reason`. When no required pin has a `tolerated` entry, ask once with `AskUserQuestion`:
**Move all** / **Stop** / **Show impact**. Show impact prints the migration-guide sections the notes
cite for the pins (each `cite`, and the `pins` step's `cites`) and asks again. When a pin has
`tolerated` entries, ask per pin instead: a pin at a tolerated version offers **Keep `<current>`** /
**Move to `<target>`** / **Stop**, any other pin **Move** / **Stop**, both with **Show impact**.

A pin Group D cannot move stops it before anything is written: a `move` row without `fix.ops` (set
outside this project, in a parent or BOM it imports), an `unknown` one (its value could not be read),
or an `essentials.version` row that is not `move`. Name where it is set and stop Group D.

**Stop**, or keeping a required pin that no `tolerated` entry covers, ends Group D: state what the
contract says — the pin's `reason` and `cite` — and that the release does not run without it. The
"applies with the Essentials upgrade" findings, `s1-boot-line` among them, stay open; the rest of the
run goes on.

**3. The branch.** `git switch -c essentials-<to>`, unless a rerun is already on it. The changes Step 6 applied come with it. The
starting branch keeps exactly the commits it had; nothing is merged, and merging stays the user's.

**4. Commits.** Write the note file if one was chosen. Ask once: **Commit each step?** On yes, first
commit what is already there — Step 6's fixes, then the note, each on its own — then one commit per
step below that changed a file. On no, everything stays uncommitted on the branch.

**5. The steps, in the plan's order, each verified before the next.** Every edit follows
`commands/review.md` § Applying a fix descriptor; the note's approval covers a `mechanical` op, and a
`mechanical: false` one is shown and confirmed on its own.

- **The baseline.** Before the first edit the gate must be green on the current release — Step 7's
  run if it ran on this tree, else run the gate now. A red baseline stops Group D: a failure after
  the move could not be told from one that was already there. Point at the Group C findings and
  `/essentials:init` Step 13.7's table.
- **`compile-and-fix` on the `from` release** (clearing deprecations): compile on the current release
  with deprecation warnings shown (`-Dmaven.compiler.showDeprecation=true test-compile`; the Kotlin
  compiler prints them anyway). Each warning on a member the step's ids name is a call site: show it
  with the replacement its deprecation names, and apply it on a yes, one at a time. Verified: it
  compiles.
- **`checklist`, phase `before-move`**: print its items — they are cheapest to check while the old
  release is still in place — and ask **Continue** / **Stop**. Nothing is applied.
- **`pins`** — one step: apply every approved pin row's `fix.ops` (`set-property`,
  `set-parent-version`, `set-dependency-version`). Verified: each op's location now reads the target.
- **`stack-lint`** — every finding the plan filed under it (the `appliesWithUpgrade` ones), by its
  `fix.ops`. Verified by the stack-lint re-run below.
- **`review-scan`** — the `confirmed` findings with mechanical ops first; then every other finding
  under it, per call site, shown with the proposed edit from its `fix.text` and catalogue line, and
  confirmed one at a time.
- **`compile-and-fix` on the `to` release**: compile (`test-compile`, with `-Pskip-frontend` as the
  gate has it). Show each compiler error with its call site and the edit the step's ids and cited
  guide sections give, confirmed one at a time; recompile until it compiles. This step and the
  `review-scan` step are the only ones that edit slice source, and only where a signature or a
  compiler error pins the call site — never a structural change, which stays
  `/essentials:slice-check`'s. An error none of the step's ids explains is a stop (Fail-safe below).
  Then re-run `review-scan.py --tree . --json`: no `confirmed` finding with an id of the plan's
  `review-scan` step may remain.
- **Re-run `stack-lint.py . --json`**: no finding whose check the plan filed under its `pins` or
  `stack-lint` step, and no `targetOnly` finding, may remain.
- **The gate** (Step 7's command). Green is the only pass; a release move has no compile-only result.

**6. After the gate.** Print the remaining checklist steps (`review`, `deploy`, `after`), each item
with its ESS id, symptom and link — reported, never applied. Then, per `fixedIn` entry, say what the
release fixed and look for the project's workaround where its trap line points; show each removal,
apply it on a yes, and run the gate again. A workaround in slice source is named with its location
and left: removing it is not an API migration. Last, list the views that will start warning (Step
4.5) again, each with its two fixes, now that `@IgnoresEventTypes` compiles; they are reported, never
applied.

**Fail-safe.** When a step's verification or the gate fails: name the step, map the failure through
`/essentials:init` Step 13.7's symptom table and the `ESS-093`… lines of
`references/llm/LLM-traps.md` § Upgrading, and offer to revert that step — `git revert` its commit,
else undo exactly its edits. Never add dependencies or edits until it boots. Then stop the run: the
branch stays for the user, the starting branch is untouched, and Step 8 does not restamp.

## Step 8 — Restamp

Only after fixes have actually been applied — and when Group D ran, only after its gate is green —
set `<!-- essentials-init: essentials <ESSENTIALS_VERSION> -->` in the project `CLAUDE.md`, replacing
any older stamp. **Stamp truthfully**: if the user skipped findings, say in the final line which ones
remain open and stamp anyway (the stamp records the Essentials version the plugin audited against,
not a claim of full conformance) — but never stamp a run where the user skipped everything, a run
whose Group D failed, or a `--check` run.

When the stamp being replaced has the older `v<semver>` form, first look for anything that parses it:

```bash
grep -rn 'essentials-init:' . --exclude=CLAUDE.md --exclude-dir={.git,node_modules,target}
```

A hit means a script, CI job or tool reads the stamp, and the `essentials <version>` form will not
match what it expects: name the files and warn. Report only; replace the stamp as above.

Close with what remains: skipped findings as runnable next steps; when the project is behind and
Group D did not run, the open "applies with the Essentials upgrade" block and why; when it ran, the
branch, its commits, and the checklist items still to decide; and `/essentials:slice-check` if the
lint gate was just installed — its first run is the one that finds the manifests that have been
silently unparseable.

## Rules

- **Idempotent.** A second run immediately after a first reports nothing to do. Every check reads
  current state; none depends on having run before — a rerun of a stopped Group D included
  (Step 4.5).
- **Report before write, offer per finding, never overwrite silently.** The same discipline the two
  copied files have always had, extended to everything this command touches.
- **Read the contract live.** S1–S11 is the authority for Group C, and `stack-pins.md` is the only
  place a version may appear. Never restate a requirement from memory, and never invent one.
- **The plan is the script's.** Group D's pins, steps, findings and checklist come from
  `migration-plan.py` verbatim; never add a step, a pin or a fix of your own.
- **Detect, never assume.** Language and profile come from the project's files. If they cannot be
  determined, run Groups A and B and say why C was skipped.
- **Moves only the pins a release requires.** `essentials.version` and the platform pins the release
  requires, under a decision note, on the `essentials-<to>` branch — never a pin the release does not
  require, no "upgrade to latest". The moves outside a release are a Kotlin compiler the project's
  own Java baseline already outgrew, and the Spring Boot patch, Kotlin and Maven wrapper floors the
  Essentials build runs on, each offered on its own (Step 4).
- **Stateless.** The version stamp is a comment in the project's own `CLAUDE.md`. The decision note is
  printed, or written to a path the user chooses. The gate command is derived each run and never
  recorded. Do not introduce a `.essentials/` directory, a config file, or a run log — that is a
  design decision to raise.

## Error handling

- Not an Essentials project ⇒ stop with one line and name `/essentials:init`. Never audit.
- `${CLAUDE_PLUGIN_ROOT}` unset ⇒ abort: "This command must be invoked from within Claude Code with
  the essentials plugin installed."
- The persistence profile cannot be determined ⇒ run Groups A and B, report C as skipped **with the
  reason**, and ask the user which profile applies rather than guessing.
- A Group C finding that S1–S11 does not actually state ⇒ report it as a suspected contract gap and
  do not apply a fix. Silently patching the project is how S2.1 came to be missing.
- The Step 7 verification fails in a way `/essentials:init` Step 13.7's table does not explain ⇒
  stop and report, with the command output. Do not improvise dependencies until it starts.
- `migration-plan.py` exits 2 ⇒ Group D not run, with its stderr line; the rest of the run goes on.
- Uncommitted changes, no Docker, no git repository, a project this shell cannot write, or an
  `essentials-<to>` branch that exists and is not the current one ⇒ Group D blocked for this run, with
  the reason; `--check` and Groups A–C are unaffected.
- A shell command of a fix fails with `Read-only file system` or `Permission denied` (a copy, a
  `chmod`, `git config`) ⇒ the shell cannot write the project. Fixes made with the Edit or Write tool
  still go; A2's install, refresh and activation do not — the script must stay executable and the
  activation is `git config` — and are never retyped with the Write tool. Stop offering them, and name
  each in the closing "what remains" with the error.
- A Group D step or the gate fails ⇒ the Fail-safe of Step 7.5: name the step, map the symptom, offer
  that step's revert, stop. Never pile on changes until it boots.
