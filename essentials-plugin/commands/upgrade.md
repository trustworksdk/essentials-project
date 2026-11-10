---
name: upgrade
description: >-
  Bring an existing Trustworks Essentials project up to what the installed plugin ships: refresh the
  slice-rules pointer and the slice-manifest lint gate (installing and activating it if the project
  predates it), check the project CLAUDE.md still routes to the essentials-docs skill, audit the app
  against the stack contract (S1-S11), silent-startup failures first, and move a project on an older
  Essentials release to the plugin's essentials.version plus only the platform pins that release
  requires, under a decision note you approve, on its own branch, code migrations included, verified
  by a full build. Reports every finding before writing and offers each fix singly; --check reports
  only, the upgrade plan included, and writes nothing. Never regenerates
  a skeleton, never restructures slice source, and never moves a pin the release does not require
  except a Kotlin compiler too old for the project's own Java baseline, and - one at a time, each on a
  yes - the Spring Boot patch, Kotlin compiler and Maven wrapper floors the Essentials build runs on.
user-invocable: true
argument-hint: "[--check]"
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
| What is here and how it connects | `/essentials:slice-map` (printed), `/essentials:slice-map-html` (the page) |
| Human-owned manifest fields missing — journeys, scenarios, summary, tier | `/essentials:slice-enrich` |
| What **one change** gets wrong — a branch, a ref, a pull request | `/essentials:review` |
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

## Step 0 — Preconditions

- `${CLAUDE_PLUGIN_ROOT}` unset ⇒ abort: "This command must be invoked from within Claude Code with the
  essentials plugin installed."
- Run `"${CLAUDE_PLUGIN_ROOT}/scripts/doctor.sh" --for upgrade` and keep its lines for the report. A
  missing or too-old `python3` (its `python3` line, which makes doctor exit 1) means no script below can
  run — not `stack-lint.py`, `migration-plan.py`, `slice-lint.py` or `slice-source.py` — so Groups C and D
  cannot be decided: stop and say so rather than audit by eye. Any other gap is not a stop: each line names
  what it costs (the Step 7 re-check without Maven or Docker, Group D outside git), and that part is
  reported **not run**.
- Its `uv` line decides how `slice-lint.py` and `slice-source.py` run, which carry their pinned `pyyaml`
  (and, for slice-lint, `jsonschema`) in inline script metadata: `ok` ⇒ `uv run --script`, as written
  below; any other status — `cache read-only → python3 fallback` included, where this shell cannot write
  uv's cache and every `uv run` fails — ⇒ `python3 <script>` in its place, which uses the packages
  already installed. A script whose package is missing exits 2, and the step that needed it is reported
  **not run**; the line says in advance which one. `uv run --script` can also fail where doctor said `ok`,
  for want of a writable cache: use `python3 <script>` from then on. `stack-lint.py`, `migration-plan.py`
  and `init-render.py` are standard library only and always run with `python3`.

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

Read the output against `${CLAUDE_PLUGIN_ROOT}/references/init-assets/lint-gate.md`. It holds every A2
finding (§ Reading the A2 detection), the activation `/essentials:init` Step 12 performs (§ Activating the
hook), the hook self-test, the per-clone and CI checks, and how to measure a refresh before offering it.
Load only what the output calls for. One section prints on its own with:

```bash
awk -v s='## The self-test' '$0 == s { on = 1; print; next } /^## / { on = 0 } on' \
  "${CLAUDE_PLUGIN_ROOT}/references/init-assets/lint-gate.md"
```

- **Current** — both the script and the schema `diff` printed nothing, and either a "script only" install
  (no hook at either `ls` path) or a hook that is the project's own `.githooks/pre-commit`, whose `diff`
  printed nothing, that is wired (`HOOKS` is `${PREFIX}.githooks` or that directory's absolute path, or
  the `grep` printed a forwarder line) and for which no `NOT EXECUTABLE` line printed. Print only
  § The self-test (when there is a hook) and § Per clone, and CI: offer the self-test, and apply the CI
  check and the per-clone report line that section gives. Identical and active — wired, executable, and
  the self-test passed where it ran ⇒ current.
- **Anything else** — no `scripts/slice-lint.py` (its `diff` says `No such file`), a `diff` that printed
  lines, a hook that is not wired or not executable, or a hook only at `${TOP}/.githooks/pre-commit` —
  read the whole reference and report by its § Reading the A2 detection.

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
| C6 | **S5** | `s5-*` — the `DocumentDbRepositoryFactory` bean, no document store on `mongo`, no `transactional-mode` key, aggregates declared, and on WebFlux `spring.threads.virtual.enabled: true` in the base configuration (`s5-webflux-virtual-threads`, Should-fix: without it a blocking handler runs on the event loop and requests stall under load) | Writes outside the `UnitOfWork`; handler idempotency. On WebFlux, the offload undone: an application `Executor` bean without `spring.task.execution.mode: force` (handlers fall back onto the event loop), or a `Mono`/`Flux`/`suspend` handler doing blocking Essentials work on the loop. **Should-fix**; the symptom is a stall under load and blocking-call warnings |
| C7 | **S2** | `s2-*` — one starter; `spring.mongodb.*` connection keys; the modules the starters do not bring (`eventsourced-aggregates`, `kotlin-eventsourcing` on Kotlin, `postgresql-document-db`) | — |
| C8 | **S9** | `s9-admin-spi` | A `SecurityConfig` exists and someone has **decided** what it says. A permit-all left exactly as `/essentials:init` emitted it is **Should-fix** — the contract's word is that security is decided, never inherited. An inbound webhook that accepts a request with no configured secret, or compares it with `equals`, is not fail-closed (S9): **Should-fix**, report only |
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

The runner is Step 0's: `python3` in place of `uv run --script` under its fallback; exit 2 (a missing
`pyyaml`) ⇒ this prediction is **not run**, and say so in the report.

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
