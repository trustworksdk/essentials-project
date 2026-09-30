---
name: upgrade
description: >-
  Bring an existing Trustworks Essentials project up to what the installed plugin ships: refresh the
  slice-rules pointer and the slice-manifest lint gate (installing it if the project predates it),
  check the project CLAUDE.md still routes to the essentials-docs skill, and audit the app against
  the stack contract (S1-S11), silent-startup failures first. Reports every finding before writing
  and offers each fix singly. Never regenerates a skeleton, edits slice source or moves a version
  pin.
user-invocable: true
allowed-tools: [Read, Write, Edit, Bash, Grep, Glob, AskUserQuestion]
---

# /essentials:upgrade

`/essentials:init` scaffolds a project **once**. Nothing afterwards pulls plugin changes into it: the
two files that leave the plugin go stale, capabilities added later are never offered, and a project
created before a contract requirement existed stays silently non-conformant. This command is the only
thing that closes that gap.

It is an **audit that offers repairs**, not a regeneration. Everything it reports is derived from the
project's own files plus the plugin's current `references/stack/stack-contract.md` — never from a
diff against the template tree `/essentials:init` renders from. A project diverges from its render
the day work on it starts, and an older project was never rendered from this tree at all, so a diff
would report the application itself as drift. The contract is what a project must satisfy; the
template is one way of satisfying it.

## Separation of concerns

| Drift | Command |
|---|---|
| Project behind the installed plugin — stale copies, an uninstalled gate, unmet S1–S11 | **`/essentials:upgrade`** (this) |
| Slices vs. the law — layout, manifests, R1–R5, wiring, handler shapes | `/essentials:slice-check` |
| A codebase not on the law at all — infer contexts and candidate slices | `/essentials:slice-discover` |
| What is here and how it connects | `/essentials:slice-map` |
| A **new** project | `/essentials:init` |

The overlap with `slice-check` is deliberate and bounded: both look at the two copied files
(`slice-check` gate 12). `slice-check` reports them as one Advisory line in the middle of a slice
audit; here they are the primary subject, and the **absence** of the lint gate is a finding rather
than a skipped clause.

## Usage

```
/essentials:upgrade           # audit, report, then offer each fix
/essentials:upgrade --check   # report only; writes nothing, asks nothing
```

`--check` is the safe first run and costs nothing — use it when you just want to know.

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
none. It degrades the report by one line and changes nothing else: every check below reads the
project's actual files, never the stamp. Do not treat it as a finding, and never make a check
conditional on it.

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

**A2 — Slice-manifest lint gate.** Two sub-cases, and the first is the one that matters most here:

```bash
diff "${CLAUDE_PLUGIN_ROOT}/scripts/slice-lint.py" scripts/slice-lint.py 2>&1 | head -5
diff "${CLAUDE_PLUGIN_ROOT}/references/slice/slice-yaml.schema.json" \
     scripts/slice-yaml.schema.json 2>&1 | head -5
```

- **Not installed at all** ⇒ **Should-fix**, and offer the install. This is the case
  `/essentials:slice-check` gate 12 cannot see: its clause is conditional on the gate already
  existing, so a project scaffolded before `/essentials:init` offered it is never told the gate
  exists. Offer the same three options init's Step 9 offers — pre-commit hook, script only, or
  neither. The hook body is `${CLAUDE_PLUGIN_ROOT}/references/init-assets/project/.githooks/pre-commit.template`
  (it has no placeholders; copy it as `.githooks/pre-commit`, executable, and set `core.hooksPath`
  when unset); copy the schema beside the script (**required**, not optional: installed into a
  project the script has no plugin root to walk up to).
- **Installed but differing** ⇒ **Advisory**. A project running a stale schema validates against
  yesterday's contract and reports a clean pass it has not earned. Offer to refresh both files
  together — the script and the schema are one contract and must not drift apart.
- Identical ⇒ current.

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
output; pass `--language`/`--db`/`--web`/`--frontend` only when Step 1 had to ask the user). Report
every finding as it comes — `id` (`ESS-S<n>`), severity, `file:line`, `message`, `fix.text` — and
list `notRun` under "not run" with its reason. **Do not re-derive, re-grade or drop a finding**, and
do not add a finding for a row the script covers because the file "looks" wrong to you: that is the
second, weaker audit this split exists to prevent. Exit 2 means the script could not run (no
`pom.xml`, a POM that does not parse, a Gradle-only build): report "Group C: not run" with its
stderr line and run the judgement rows only. Never fall back to checking the decidable rows by hand.

What the rows cover, so the report can say what was checked and what was clean:

| # | Requirement | `stack-lint.py` checks | Judgement (yours, reading the contract live) |
|---|---|---|---|
| C1 | **S2.1** | `s2.1-*` — every row of S2.1's table for the profile and web stack, at compile scope | — |
| C2 | **S3.1 / S3.2 / S3.4** | `s3.1-*`, `s3.4-*` — no Jackson 2 Essentials module; on Kotlin, Jackson 3 `jackson-module-kotlin` and an own persistence serializer bean of the type the starter backs off from | Whether that bean really builds on `EssentialsObjectMappers` (S3.2) |
| C3 | **S3.3** | `s3.3-own-json-mapper` | — |
| C4 | **S4** | `s4-*` — exactly one `Essentials*WebConfigurer` `@Import`ed, matching the web stack | — |
| C5 | **S3.5 / compiler** | `s3.5-*` | Constructor parameter names that differ from their JSON properties |
| C6 | **S5** | `s5-*` — the `DocumentDbRepositoryFactory` bean, no document store on `mongo`, no `transactional-mode` key, aggregates declared | Writes outside the `UnitOfWork`; handler idempotency |
| C7 | **S2** | `s2-*` — one starter; `spring.mongodb.*` connection keys; the modules the starters do not bring (`eventsourced-aggregates`, `kotlin-eventsourcing` on Kotlin, `postgresql-document-db`) | — |
| C8 | **S9** | `s9-admin-spi` | A `SecurityConfig` exists and someone has **decided** what it says. A permit-all left exactly as `/essentials:init` emitted it is **Should-fix** — the contract's word is that security is decided, never inherited |
| C9 | **S7** | `s7-*` — the spec is regenerated by an integration test, the committed spec exists, the frontend builds from it, and `SingleValueTypeModelConverter` is registered wherever springdoc runs (without it every generated client types the semantic ids as objects). A project still on the forked-app pipeline (`springdoc-openapi-maven-plugin` with start/stop executions and `application-openapi.yml`) is reported under `s7-spec-generation`: it serves a stale spec silently once an endpoint needs the database | — |
| C10 | **S10** | `s10-*` — Testcontainers 2.x coordinates and packages, S10's `@AutoConfigureWebTestClient` package, Failsafe bound | Context caching and fork reuse |
| C11 | **S8** | `s8-*` — both half-choices: embedded without CORS, base URL or dead pieces and with its SPA fallback and static copy; standalone with a consumed `VITE_API_BASE_URL`, a `CorsConfigurationSource` bound through `@ConfigurationProperties`, no wildcard with credentials; the generated client git-ignored | Whether the chosen mode is the one the deployment needs |
| C12 | **S1 / S11** | `s1-*`, `s11-*` — the Boot line and Java baseline, one `essentials.version`, the Kotlin `jvmTarget`, the `skip-frontend` profile, `spring-boot-starter-parent` | — |

Severities are the script's; the judgement rows carry the severity in their text. **A judgement
finding needs a reason that names the file and the contract line**, the same standard the script
meets.

**Version pins are out of scope, deliberately.** Do not compare the project's pins against
`stack-pins.md`, do not report a lag, and do not offer a bump. A pin move is an upgrade decision
with a blast radius across the whole application — the project `CLAUDE.md` says pins are fixed and
upgrades want an ADR. This command exists to close gaps the project could not have
known about, not to move it onto versions the user has not chosen. If the user asks for a pin
review, that is a separate conversation.

**A finding here can be a defect in the contract rather than in the project.** If the project is
missing something S1–S11 does not state, or if a check contradicts what the project plainly does,
say so and stop rather than "fixing" the project into agreement. That is the S2.1 lesson: the
requirement existed in `references/llm/` for the plugin's whole life and still shipped four broken
projects, because the contract never restated it.

## Step 5 — Report before you write

Print the whole report first — every group, findings and non-findings, with the severity vocabulary
`/essentials:slice-check` uses (Blocking / Should-fix / Advisory). A run with nothing to do says so
in one line and exits.

```
ESSENTIALS UPGRADE — <project name>
  Scaffolded for: essentials <PROJECT_STAMP>   (or: before the plugin's first release · or: stamp absent)
  Plugin targets: essentials <ESSENTIALS_VERSION>
  Detected:       kotlin · pg-event-sourced

  A · Plugin copies
    [Should-fix] Slice rules pointer is v<project>; plugin ships v<plugin>
    [Should-fix] Slice-manifest lint gate not installed
  B · Orientation
    [ok]         Framework-knowledge block present
    [Advisory]   No version stamp in CLAUDE.md
  C · Stack contract (stack-lint: 3 findings)
    [Blocking]   ESS-S2.1 s2.1-jdbi  backend/pom.xml:41
                 org.jdbi:jdbi3-core is not declared — the starter declares it `provided`, so nothing
                 brings it; compiles, then fails at context startup
    [Blocking]   ESS-S2 s2-eventsourced-aggregates  backend/pom.xml:41
    [Advisory]   ESS-S10 s10-tc-package  backend/src/test/kotlin/…/IntegrationTestBase.kt:7
    [ok]         S1, S3.x, S4, S5, S7, S8, S9, S11
    [Should-fix] S9 (judgement) — config/SecurityConfig is the permit-all init emitted, unchanged

  3 fixes offered · 1 Blocking
```

Under `--check`, stop here. Write nothing, ask nothing.

## Step 6 — Offer each fix, one at a time

Blocking first, then Should-fix, then Advisory. For each, `AskUserQuestion` with **Apply / Skip /
Show me the change first**. Never bundle unrelated fixes behind one confirmation, and never apply an
Advisory without asking because it "seemed safe".

**A `stack-lint` finding carries its own fix.** `fix.text` is what to show; `fix.ops` is the edit.
Apply the ops exactly as `commands/review.md` § Applying a fix descriptor specifies — the one
applier spec `/essentials:review --fix` uses too: one op at a time, the location re-read and its
signature confirmed first, skip and re-run the lint on a mismatch, and `mechanical: false` shown
and confirmed rather than applied.

**A scratch render** supplies whole files the contract describes and a fix needs — the
`OpenApiContractIT` of C9, a `config/` class, the workspace pointer of B2. Write an answers file from
the project's facts (language, profile, web stack, frontend mode, whether `backend/compose.yml`
exists, `lintGate: none`, the name and coordinates from the POM, and the package of the
`@SpringBootApplication` class), render it into an empty scratch directory with
`python3 "${CLAUDE_PLUGIN_ROOT}/scripts/init-render.py" --answers F --out /tmp/essentials-upgrade/render`
(plus `--workspace-out /tmp/essentials-upgrade/workspace` for the B2 pointer), and offer the file the finding needs (`OpenApiContractIT` extends the rendered `IntegrationTestBase`;
offer that too when the project has no base of its own). Never copy more than the finding needs, and
never over a file the project already has — the project's version wins, and the difference is a
conversation.

What a fix may touch: `backend/pom.xml` dependencies and build-plugin configuration, the `config/`
classes S1–S11 names, `application.yml` keys, the test sources S7 and S10 name
(`OpenApiContractIT`, `IntegrationTestBase`), `.claude/rules/`, `scripts/`, `.githooks/`, and the
project `CLAUDE.md`. Moving a project off the forked-app spec pipeline (C9) also removes its
`springdoc-openapi-maven-plugin`, the two `spring-boot-maven-plugin` start/stop executions and
`application-openapi.yml` — offer that as one fix, after the replacing test has passed.

What a fix may **never** touch — these are not defaults to weigh, they are the boundary:

- **Slice source.** Anything under a bounded context is `/essentials:slice-check`'s business, and
  its repairs are opt-in there for the same reason.
- **The skeleton.** No re-render over the project, no regenerated `Application.kt`, no parent POM
  rewrite.
- **Version pins**, per Step 4.
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

## Step 8 — Restamp

Only after fixes have actually been applied, set `<!-- essentials-init: essentials <ESSENTIALS_VERSION> -->`
in the project `CLAUDE.md`, replacing any older stamp. **Stamp truthfully**: if the user skipped
findings, say in the final line which ones remain open and stamp anyway (the stamp records the
Essentials version the plugin audited against, not a claim of full conformance) — but never stamp a run where the user skipped everything, and never
stamp a `--check` run.

Close with what remains: skipped findings as runnable next steps, and `/essentials:slice-check` if
the lint gate was just installed — its first run is the one that finds the manifests that have been
silently unparseable.

## Rules

- **Idempotent.** A second run immediately after a first reports nothing to do. Every check reads
  current state; none depends on having run before.
- **Report before write, offer per finding, never overwrite silently.** The same discipline the two
  copied files have always had, extended to everything this command touches.
- **Read the contract live.** S1–S11 is the authority for Group C, and `stack-pins.md` is the only
  place a version may appear. Never restate a requirement from memory, and never invent one.
- **Detect, never assume.** Language and profile come from the project's files. If they cannot be
  determined, run Groups A and B and say why C was skipped.
- **Not an upgrader of versions.** No pin moves, no "upgrade to latest", no Boot major.
- **Stateless.** The version stamp is a comment in the project's own `CLAUDE.md`. Do not introduce
  a `.essentials/` directory, a config file, or a run log — that is a design decision to raise.

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
