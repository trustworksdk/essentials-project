# The slice-manifest lint gate in a project

The lint gate is `scripts/slice-lint.py` and `scripts/slice-yaml.schema.json` copied into a project,
optionally with the `.githooks/pre-commit` hook that runs them. This file is shared by the three commands
that install, check or refresh it. Each reads only the sections it needs, and only when it needs them:

| Section | Read by |
|---|---|
| § Activating the hook | `/essentials:init` Step 12 (`lintGate` is `hook`); `/essentials:upgrade` Group A2, for a hook that is not active |
| § Reading the A2 detection | `/essentials:upgrade` Group A2, when its detection output is anything but the current case |
| § The self-test | `/essentials:upgrade` Group A2, once the hook reads as wired and executable |
| § Per clone, and CI | `/essentials:upgrade` Group A2, for every installed gate |
| § Measuring a refresh | `/essentials:slice-check` gate 12(b) and `/essentials:upgrade` Group A2, when a copy is stale |

To print one section without the rest (POSIX awk, so the same with the BSD awk of macOS, gawk and mawk):

```bash
awk -v s='## The self-test' '$0 == s { on = 1; print; next } /^## / { on = 0 } on' \
  "${CLAUDE_PLUGIN_ROOT}/references/init-assets/lint-gate.md"
```

## Activating the hook

A hook is inert until git is told about it. A relative `core.hooksPath` resolves against the repository's
**top level**, not against the project directory, so when the project sits below the top level — init's
`subdirectory` mode, its `current` mode inside a larger repository, or any nested project upgrade finds —
a bare `.githooks` names a directory that does not exist and the hook never runs. Use the project's path
from the top level. Below, `DIR` is the project directory: `${TARGET_DIR}` in `/essentials:init`, and `.`
in `/essentials:upgrade`, which runs from the project directory (its A2 detection block has already
printed the same facts as `PREFIX`, `HOOKS` and the `ls` of the hooks directory; read them from there).

```bash
git -C "$DIR" rev-parse --is-inside-work-tree     # not a repository ⇒ case 4
PREFIX="$(git -C "$DIR" rev-parse --show-prefix)"  # "" at the top level, "orders-service/" below it
git -C "$DIR" config --get core.hooksPath          # set ⇒ case 2
ls "$(git -C "$DIR" rev-parse --git-common-dir)/hooks" | grep -v '\.sample$'   # any ⇒ case 3
```

1. **`core.hooksPath` unset and no hook in `.git/hooks`** (git's own `*.sample` files do not count): set it,
   `git -C "$DIR" config core.hooksPath "${PREFIX}.githooks"`, and say it is local to this
   clone — every other clone runs the README's command once.
2. **`core.hooksPath` already set** — to `${PREFIX}.githooks` (or that directory's absolute path), nothing
   to do; to anything else (a hook manager such as husky): leave it. Changing it would silence every hook
   the manager runs, and leaving the gate unwired installs it dead. Offer (`AskUserQuestion`) to add one
   forwarder line to the pre-commit hook that setting points at — the hook git already runs; for husky,
   `.husky/pre-commit` — before any `exit` in it:
   `"$(git rev-parse --show-toplevel)/${PREFIX}.githooks/pre-commit" || exit 1`.
   On no, print the line for the user to add.
3. **`core.hooksPath` unset, but `.git/hooks` holds a hook** — a `pre-commit`, or another tool's
   `post-commit` or `post-checkout`: setting `core.hooksPath` would disable every one of them, so name
   them. Offer the same forwarder line in `.git/hooks/pre-commit` instead, creating that file with a
   `#!/usr/bin/env bash` line and the execute bit when only other hooks exist, and state the
   trade-off: the forwarder keeps the existing hooks running but, being in `.git/`, is local to this
   clone exactly like `core.hooksPath`. On no, print the line.
4. **Not a git repository yet**: tell the user to run the README's command from the project
   directory after `git init` — `git config core.hooksPath "$(git rev-parse --show-prefix).githooks"`.

State the trade-off in the offer whichever case applies: `core.hooksPath` replaces every hook in
`.git/hooks` rather than adding to them, and both it and a forwarder in `.git/` are local to this clone.
Every other clone runs the activation once, with the command in the project README's "Slice-manifest lint
gate" section. `/essentials:init` renders that section. When `/essentials:upgrade` finds a project
`README.md` without it, offer the section from a scratch render (upgrade's Step 6) with `lintGate` set to
the project's choice; on no, print the command for the user.

## Reading the A2 detection

The output of the detection block in `/essentials:upgrade` Group A2; `TOP`, `PREFIX`, `HOOKS` and `RUNS`
are its variables.

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
  it by § Activating the hook); copy the schema beside the script (**required**, not optional:
  installed into a project the script has no plugin root to walk up to).
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

  Anything else is inactive. Offer the activation `/essentials:init` Step 12 performs, by the cases
  1–4 of § Activating the hook: set `core.hooksPath` to `${PREFIX}.githooks` only when it is unset and
  the `ls` of `.git/hooks` above printed nothing. When it printed any hook — a `pre-commit`, or another
  tool's `post-commit` or `post-checkout` — recommend the forwarder line instead, in
  `.git/hooks/pre-commit`, and name the hooks `core.hooksPath` would silence. With `core.hooksPath`
  already set, the forwarder goes in the hook git already runs. Then run § The self-test.
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

  **Measure the refresh before offering it**, by § Measuring a refresh, with the runner Step 0 chose.

  Say what the refreshed hook changes for contributors: it **blocks** a commit when the linter exits 2
  — a `python3` without `pyyaml` or `jsonschema`, and no `uv` — where an older hook let it through, and
  it fails when `scripts/slice-lint.py` is missing; only a machine with neither `uv` nor `python3`
  still commits unchecked. Contributors need `uv`, or `python3` with `pyyaml` and `jsonschema`.
- Identical and active — wired, executable, and the self-test passed where it ran ⇒ current.

## The self-test

Offer it once the hook reads as wired and executable, and again after any activation, `chmod` or refresh
this run applies. The static checks of § Reading the A2 detection say how the hook is set up; only git
running it says that a commit is actually checked. The hook decides whether to run from the names staged
in the index, but the linter it calls reads the files in the working tree. So the test needs both: an
unparseable `slice.yaml` in a temporary directory inside the project, and that file staged in a
**temporary index** (`GIT_INDEX_FILE`), a copy of the real one. Git then runs the pre-commit hook the way
a commit would, through `git hook run` (git 2.36 or later), and the hook must exit non-zero, naming the
test file. Then the temporary directory and the temporary index are removed. The real index and every
tracked file stay as they were; the only trace is an unreachable blob that `git gc` prunes. When `RUNS`
is not the project hook itself — a forwarder in another tool's hook, a hook manager's wrapper — the test
runs that whole hook, other tools' steps included; say so in the offer. From the project directory:

```bash
# A2 hook self-test: from the project directory; leaves the real index and every tracked file as they were
(
  T="$(mktemp -d)"; P="slice-hook-selftest.$$"   # not a dot directory: the manifest walkers prune those
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

Take the first line as the verdict and show the output under it. A `FAIL` is the inactive finding of
§ Reading the A2 detection, whatever the static checks said: fix by its line, then run the test again.
`NOT RUN` leaves the static checks as the only evidence; say so. A hook that ran and let the manifest
through on a machine with neither `uv` nor `python3` is the hook's documented fail-open, not a wiring
fault.

## Per clone, and CI

A hook is per clone, and CI is the gate. Every activation of § Activating the hook — `core.hooksPath`, a
forwarder in `.git/hooks` — is configuration of this clone, which a fresh clone does not carry, and
`git commit --no-verify` skips the hook on any clone. Say this in the report whenever the project has a
hook: the hook is a convenience that fails a commit early, and the CI job is the gate that no contributor
can bypass.

- **A devcontainer** (`.devcontainer/devcontainer.json` in the A2 detection output): its post-create step
  runs on every fresh container, so it can activate the hook for every contributor who uses it. When the
  activation is `core.hooksPath` (case 1), offer this line in the script `postCreateCommand` runs,
  or in `postCreateCommand` itself when it calls no script; it leaves a `core.hooksPath` that is
  already set alone, and works from any directory of the repository:
  `[ -n "$(git config --get core.hooksPath)" ] || git config core.hooksPath "${PREFIX}.githooks"`,
  with `${PREFIX}` written out (`orders-service/.githooks`, or `.githooks` at the top level). A
  forwarder in a hook-manager file the repository tracks (`.husky/pre-commit`) already reaches
  every clone and needs no line; a forwarder in `.git/hooks` cannot be added safely from a
  post-create step, so say that each clone adds it by hand.
- **CI**: when the A2 detection's `grep` found a CI file that runs `slice-lint`, name it and say the gate
  holds. When it found none, the manifests are checked only on clones whose hook is active ⇒
  **Advisory**, and **Should-fix** when this clone has no active hook either (a "script only"
  install included), since then nothing runs the linter at all. This check applies to every
  installed gate, hook or not. Offer a step that runs the project README's command from the project
  directory and fails the build on any exit but 0. Prefer `uv`, which reads the pins in the script's
  own PEP 723 header. On a runner without `uv`, install `pyyaml` and `jsonschema` at the versions in
  that header, never unpinned. Add it to the CI file the repository already has, beside its build
  step. The `--baseline` ratchet of § Measuring a refresh goes into this step.

## Measuring a refresh

The refresh — `/essentials:slice-check` gate 12(b)'s offer, and the "installed but differing" finding of
`/essentials:upgrade` Group A2 — replaces the project's `scripts/slice-lint.py` and
`scripts/slice-yaml.schema.json`. A newer copy can report what the installed one did not — a tightened
schema, a new check — and every finding it reports fails the project's hook and CI. Never offer the
refresh blind. From the project root, with the runner the calling command chose (`uv run --script`, or
`python3` under its fallback; each `uv run --script` below becomes `python3` then):

```bash
# outer run: the plugin's copy, with the plugin's schema; inner run: the project's installed copy, with the project's schema
uv run --script ${CLAUDE_PLUGIN_ROOT}/scripts/slice-lint.py . --json \
    --baseline <(uv run --script scripts/slice-lint.py . --schema scripts/slice-yaml.schema.json --json)
```

The inner command runs the **project's** copy against its own schema; pass it nothing newer, since an
older copy rejects flags it does not know. The outer one runs the plugin's copy and keeps only the
findings the installed copy did not report, matched on id, file and message: exactly what the refresh
adds. The baseline goes in by process substitution, so no file is written — this works where Bash has
no writable directory. If the project's copy printed no JSON, the plugin's copy exits 2 `cannot read
baseline …`: say the cost could not be measured and why, and treat every slice-lint finding of the audit (slice-check Step 1.5) as
possibly new.

1. **No new findings** — offer the refresh, saying it adds none.
2. **New findings** — state them in the offer: "the refresh adds N findings in `<files>`; the hook and
   CI fail on them until they are fixed". Then offer the manifest fixes **before** the refresh, one
   file at a time, each shown and applied only on a yes, so the gate never goes red:
   - `14 tier` — the hint's repair as a per-file edit: `tier: cqrs-es` if the BC is event-sourced
     (gate 14's detected lane and the BC's store say so), otherwise delete the `tier` line. A deleted
     tier is not a defect (`/essentials:slice-check` Step 3); `--adopt-tier` can backfill it later from
     the BC's resolved value.
   - `1(a) parse` — the quoting text fix of `/essentials:slice-check --fix-manifests` (its Step 4).
   - `1(b) schema`, `1(c) stream vs event`, `1(d) scenario` — the finding's hint, as a per-file edit;
     where the field is machine-derived, `--fix-manifests` instead. A `1(d)` finding after
     `--fix-manifests` renamed a derived field is the scenario that went stale: report it — scenarios
     are generator- or human-owned and never rewritten from code.
   - `3` and `4` — a design defect, not a manifest typo: report it and leave it. The refresh still
     fails the gate on it, so say that in the offer.

   Rerun the command after the fixes, and offer the refresh with what is left.
3. **In `/essentials:upgrade`, where findings stay**, also offer `--baseline` as a ratchet: commit the
   installed copy's `--json` output and pass `--baseline <that file>` in CI, so existing findings stop
   failing the build and new ones still do. It belongs in CI: the hook passes no baseline, and a baseline
   added to the hook is a project edit the next A2 diff reports.
