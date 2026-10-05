---
name: init
description: >-
  Scaffold a new Trustworks Essentials project. Asks for target directory, frontend mode, backend
  language, web stack, DB profile, Docker Compose, project metadata and the slice-manifest lint
  gate; renders the project from the plugin's template tree with scripts/init-render.py (the stack
  contract S1-S11 applied, every version from stack-pins.md), lints it with scripts/stack-lint.py,
  and smoke-builds it so a context-startup failure surfaces here. Always writes a project CLAUDE.md
  that points back to the essentials-docs skill.
user-invocable: true
allowed-tools: [Read, Write, Edit, Bash, Glob, AskUserQuestion]
---

# /essentials:init

Bootstrap a new Trustworks Essentials project. Walk through the steps below in order, using
`AskUserQuestion` for every choice. Do not skip steps — each one captures information that later
steps depend on.

**This command asks; a script writes.** The project is rendered from the plugin's template tree by
`scripts/init-render.py`: two runs with the same answers produce the same bytes, and those bytes are
what the committed goldens under `${CLAUDE_PLUGIN_ROOT}/tests/golden/init/` show and what the
Essentials repository's CI builds against the framework. The model keeps the judgement calls — the
questions, merging into files the user already has, and diagnosing a failed smoke build. It never
writes project files freehand, and never patches a rendered file into working.

| Source | What it supplies |
|---|---|
| `${CLAUDE_PLUGIN_ROOT}/references/init-assets/project/` | The template tree and its `manifest.json` — every file a project can get, gated per answer |
| `${CLAUDE_PLUGIN_ROOT}/scripts/init-render.py` | The renderer (stdlib Python 3.11+). Its docstring is the template grammar and the variable list |
| `${CLAUDE_PLUGIN_ROOT}/references/stack/stack-contract.md` | Requirements **S1–S11** the rendered project satisfies |
| `${CLAUDE_PLUGIN_ROOT}/references/stack/stack-pins.md` | **Every version number.** The renderer reads it; the templates name none |
| `${CLAUDE_PLUGIN_ROOT}/scripts/stack-lint.py` | The decidable half of S1–S11, run on the render before the build |
| `${CLAUDE_PLUGIN_ROOT}/references/stack/frontend-react.md` | The S8 frontend modes, for explaining the Step 2 choice |

The contract is the specification and the template tree is its implementation. **When the render
and the contract disagree, the contract wins** — report the discrepancy as a plugin defect; do not
edit the generated project into agreement.

The LLM docs under `${CLAUDE_PLUGIN_ROOT}/references/llm/` are consumed by the `essentials-docs`
skill and are **never** copied into the generated project.

## Step 0 — Pre-flight check

Two questions, in this order. The second one is the one that matters, and it is not "is this
directory empty".

```bash
test -n "${CLAUDE_PLUGIN_ROOT}" || echo "CLAUDE_PLUGIN_ROOT unset"
"${CLAUDE_PLUGIN_ROOT}/scripts/doctor.sh" --for init
ls -A 2>/dev/null | head

# Is this already an Essentials project?
ls .claude/rules/essentials-slices.md 2>/dev/null
grep -rl 'dk\.trustworks\.essentials' --include=pom.xml --include=build.gradle \
  --include=build.gradle.kts . 2>/dev/null | head -3
grep -l 'Trustworks Essentials framework knowledge' CLAUDE.md */CLAUDE.md 2>/dev/null | head -3
```

`doctor.sh` exits 1 ⇒ stop and say so, with its `python3` line: no Python 3.11 or newer, and the
renderer is the only way this command writes a project, so there is no hand-written fallback. Its other
lines do not stop init; they say now what Steps 12 and 13.7 will lose — no JDK of the pinned major or no
`mvn` (no wrapper, no smoke build), no running Docker (the smoke build is `compiled-only`), no `npm` (no
frontend lockfile or check). Tell the user once, before the questions, so a missing tool can be fixed
before the build rather than discovered by it; Step 13.7 still checks Docker itself when it builds.

**Any hit in the second group means this is already an Essentials project, and `/essentials:init` is
the wrong command.** Do **not** fall through to Step 1 and offer the subdirectory option as the
recommended path: that answer scaffolds a second, unrelated project *beside* the real one, which
neither updates the existing project nor warns anyone that it was not updated. Being non-empty and
being an Essentials project are different findings and get different answers.

Stop here and say plainly what was found:

> `<the marker that matched>` — this is already a Trustworks Essentials project. `/essentials:init`
> scaffolds a **new** one; it never updates an existing project. To pick up what this plugin has
> shipped since the project was created — the slice-manifest lint gate, a refreshed rules pointer,
> and a conformance check against the current stack contract — run **`/essentials:upgrade`**.

Then offer exactly one escape hatch via `AskUserQuestion`, because continuing *is* legitimate in one
case: the user wants a **second, separate** project inside this repository (a monorepo gaining a
service).

- **"Run `/essentials:upgrade` instead (Recommended)"** — stop here; the existing project is what
  needs attention.
- **"Scaffold a second, separate project in a subdirectory"** — continue from Step 1 with the
  subdirectory option. The existing project is left untouched, and Step 14 must say so.

If no Essentials marker matched, the check is the ordinary one: if the current directory contains
substantive files (anything other than `.git/`, `README.md`, `CLAUDE.md`, `LICENSE`, `.gitignore`),
it is a candidate for a new subdirectory; carry that hint into Step 1.

## Step 1 — Target directory

Ask via `AskUserQuestion`:

- **Question**: "Where should the new project be created?"
- **Header**: "Target"
- **Options**:
  1. **"In the current directory"** — "Scaffold into `$(pwd)`. Use only if the current directory is empty (or contains only .git/README/CLAUDE.md/LICENSE/.gitignore)."
  2. **"In a new subdirectory"** — "Create `./<artifactId>/` and scaffold there. The artifactId is captured later. Recommended if the current directory is non-empty."

Remember the choice as `TARGET_MODE`.

## Step 2 — Frontend

Ask via `AskUserQuestion`:

- **Question**: "Which frontend should the project include?"
- **Header**: "Frontend"
- **Options**:
  1. **"Embedded React frontend (Recommended)"** — "One deployable fat JAR. React + TypeScript + Vite, built into the backend JAR's static resources, wired via SpringDoc → contracts/openapi.json → Orval. Same origin, so no CORS and no API base URL to configure."
  2. **"Backend only"** — "Backend module only — no frontend, no Orval pipeline. Smaller scaffold, faster builds."
  3. **"Standalone React frontend"** — "Two deployables: the JAR and a separately hosted SPA. Requires real CORS, a consumed API base URL, and an SPA rewrite at the static host. Choose when a separate team, a CDN, or multiple clients forces it."

Remember as `FRONTEND` (`embedded`, `none`, `standalone`).

This is the contract's **S8** choice; `frontend-react.md` explains the two modes if the user asks.
The renderer emits exactly the chosen mode's pieces and none of the other's.

## Step 3 — Backend language

Ask via `AskUserQuestion`:

- **Question**: "Backend language?"
- **Header**: "Language"
- **Options**:
  1. **"Kotlin (Recommended)"** — "Kotlin with the Spring Boot Kotlin plugin. Value-class ids bind as path variables with nothing from Essentials, and the `kotlin-eventsourcing` DSL gives the decider lane its most concise form."
  2. **"Java"** — "Records for commands and events, and the `EventStream*` decider family. Also the only language on the **aggregate** write style (§R5), whose Essentials API is Java-native."

Remember as `LANGUAGE` (`kotlin` or `java`). **Both are fully supported**; the language files in
`references/stack/` hold what differs.

## Step 4 — Web stack

Ask via `AskUserQuestion`:

- **Question**: "Which Spring web stack?"
- **Header**: "Web stack"
- **Options**:
  1. **"WebFlux (Recommended)"** — "Reactive (`spring-boot-starter-webflux`). On Kotlin, handlers may be `suspend` functions."
  2. **"WebMvc"** — "Servlet (`spring-boot-starter-webmvc`). Choose it when the team or its libraries are servlet-based."

Remember as `WEB` (`webflux` or `webmvc`). The answer picks the Spring web starter, the springdoc
starter, the one `Essentials*WebConfigurer` the project imports (S4), the SPA filter's form (S8) and
`reactor-core` on WebMvc (S2.1). Both are built in CI.

## Step 5 — DB profile

Ask via `AskUserQuestion`:

- **Question**: "Which persistence profile?"
- **Header**: "DB profile"
- **Options**:
  1. **"PostgreSQL — event-sourced (Recommended)"** — "Wires `spring-boot-starter-postgresql-event-store`. Includes EventStore, EventProcessors, durable queues, fenced locks, inbox/outbox. The default Trustworks pattern."
  2. **"PostgreSQL — CRUD (no event store)"** — "Wires `spring-boot-starter-postgresql`. JDBI + UnitOfWork + queues + locks + inbox/outbox, but no EventStore."
  3. **"MongoDB"** — "Wires `spring-boot-starter-mongodb`. Spring Data Mongo + queues + locks + inbox/outbox, on a replica set."

Remember as `DB_PROFILE` (`pg-event-sourced`, `pg-crud`, `mongo`).

## Step 6 — Docker Compose

Ask via `AskUserQuestion`:

- **Question**: "Include Docker Compose for the development database?"
- **Header**: "Docker Compose"
- **Options**:
  1. **"Yes, include (Recommended)"** — "Writes `backend/compose.yml` and Spring Boot's Docker Compose support: `spring-boot:run` starts the database and stops it on shutdown."
  2. **"No, I'll manage my own DB"** — "No compose file. `application.yml` points at a local database, overridable by environment variables."

Remember as `COMPOSE` (boolean). Integration tests use Testcontainers either way.

## Step 7 — Project metadata

Capture three free-text values. Use `AskUserQuestion` for each, with a single sensible default as
the recommended option and "Other" letting the user enter their own.

1. **Project display name** — e.g., "Orders Service". Used in the POM `<name>` and the project
   CLAUDE.md heading. Default suggestion: derive from the current directory name.
2. **Maven groupId** — e.g., `dk.trustworks.orders`. Default suggestion: `dk.trustworks.example`.
3. **Maven artifactId** — e.g., `orders-service`: lowercase letters, digits and `-`. Default
   suggestion: the display name lowercased and hyphen-separated.

Derive the package the way the renderer does, so what the user confirms is what they get: take the
artifactId, lowercase it, drop every character that is not `a-z0-9`, and strip leading digits. If
that is empty or equals the groupId's last segment, the package is the groupId; otherwise it is
`groupId.<segment>` — `dk.trustworks.orders` + `orders-service` → `dk.trustworks.orders.ordersservice`.

Confirm the four-tuple with the user via a single `AskUserQuestion` before proceeding:

> "Confirm: project=`<displayName>`, groupId=`<groupId>`, artifactId=`<artifactId>`, package=`<PACKAGE_PATH>`?"
>
> Options: "Yes, proceed" / "No, let me change something" (loop back to Step 7 if changed; the user
> may name the package directly).

Every package segment must be a Java/Kotlin identifier and not a keyword; the renderer refuses
anything else (exit 2), so ask again rather than letting it fail.

## Step 8 — Resolve the target directory and existing files

`WORKSPACE_DIR = $(pwd)`, the directory the user invoked `/essentials:init` from.

- `TARGET_MODE == "current directory"` ⇒ `TARGET_DIR = WORKSPACE_DIR`.
- `TARGET_MODE == "new subdirectory"` ⇒ `TARGET_DIR = WORKSPACE_DIR/<artifactId>`. If it exists and
  is non-empty, abort: "directory <X> already exists and is non-empty; remove it or pick a
  different artifactId". Do **not** overwrite.

The renderer writes only into an absent or empty directory (`.git/` aside). An entry that already
exists is named with `--preserve`: the renderer never overwrites it, and when it would have written
that path it writes `<path>.essentials-init` beside it instead. Step 13 decides what happens to
each side-rendered file.

**Current-directory mode.** List the entries already there (`ls -A`, ignoring `.git`); each becomes a
`--preserve` argument. For a pre-existing `CLAUDE.md` or `README.md`, ask via `AskUserQuestion`:

- **Question**: "An existing `CLAUDE.md` was found at `${TARGET_DIR}/CLAUDE.md`. How should it be handled?" (same for `README.md`)
- **Header**: "Existing CLAUDE.md" (or "Existing README.md")
- **Options**:
  1. **"Merge (Recommended)"** — "Preserve the existing project content and integrate the Essentials sections (framework knowledge block, stack, commands, conventions). Claude reads both files and produces a combined result, surfacing conflicts inline."
  2. **"Overwrite"** — "Replace the existing file entirely with the rendered one. Existing content is lost."
  3. **"Skip"** — "Leave the existing file untouched. NOTE: for `CLAUDE.md` this means the Essentials framework-knowledge block is NOT injected, so the `essentials-docs` skill won't auto-load until you add it manually."

Remember the choices as `CLAUDE_MD_MODE` and `README_MD_MODE` (`merge`, `overwrite`, `skip`, or
`none` when the file did not exist). An existing `.gitignore` is always merged (Step 13); any other
existing entry the render also produces is reported in Step 14 and left for the user.

**Subdirectory mode — the workspace pointer.** Future Claude Code sessions launched from
`${WORKSPACE_DIR}` (a planner at the workspace root, say) do **not** discover
`${TARGET_DIR}/CLAUDE.md` — Claude Code reads the cwd's `CLAUDE.md` and its ancestors, not
descendants. Without a breadcrumb, downstream tooling has no signal that the project root is
`<artifactId>/`, the package is fixed, or the version pins live in the root `pom.xml`; that is the gap
that lets a "Greenfield" design spec drop files at `${WORKSPACE_DIR}/src/main/kotlin/...` and invent its
own pins. The renderer writes a pointer `CLAUDE.md` at `${WORKSPACE_DIR}` (`--workspace-out`). If one
exists already, it is side-rendered as `CLAUDE.md.essentials-init`; ask via `AskUserQuestion`:

- **Question**: "An existing `CLAUDE.md` was found at `${WORKSPACE_DIR}/CLAUDE.md` (the workspace root, parent of the new project directory). How should the Essentials workspace pointer be handled?"
- **Header**: "Workspace CLAUDE.md"
- **Options**:
  1. **"Merge (Recommended)"** — "Add a short Essentials workspace-pointer section (project location, pinned package, the root pom.xml as the version-pin authority, no source files at the workspace root). Existing content is preserved."
  2. **"Overwrite"** — "Replace the existing workspace `CLAUDE.md` with the pointer only. Existing content is lost."
  3. **"Skip"** — "Leave the existing workspace `CLAUDE.md` untouched. NOTE: tooling launched at `${WORKSPACE_DIR}` will have no signal that the project lives at `<artifactId>/`, which is the failure mode the pointer exists to prevent."

Remember as `WORKSPACE_CLAUDE_MD_MODE` (`merge`, `overwrite`, `skip`, or `none`).

## Step 9 — Offer the manifest lint gate (ask; do not install silently)

Nothing in a Maven or Gradle build reads `slice.yaml`. A manifest that stops being valid YAML
therefore compiles, tests green, and ships — and the slice **silently drops out** of every gate and
of `/essentials:slice-map`. Invisible reads as compliant. `scripts/slice-lint.py` is the deterministic
gate that makes it loud, and it is only worth anything if the project runs it without being asked.

`AskUserQuestion`: **Install the slice-manifest lint gate?**

| Option | `lintGate` | What the render adds |
|---|---|---|
| **Pre-commit hook (recommended)** | `hook` | `scripts/slice-lint.py`, `scripts/slice-yaml.schema.json` and `.githooks/pre-commit` |
| **Script only** | `script` | `scripts/slice-lint.py` and `scripts/slice-yaml.schema.json`, for the user to wire into their own CI |
| **Neither** | `none` | Nothing. Say once that `/essentials:slice-check` still runs the same checks on demand |

The schema copy is required: installed into a project, the script has no plugin root to resolve
its schema from, so the project keeps its own copy beside it and the hook passes `--schema`. The
hook (`references/init-assets/project/.githooks/pre-commit.template`) runs the script with `uv run`
when uv is installed, else with `python3`, and **fails open** when neither can run it, so a
contributor without the tooling can still commit.

**This is the second file set this plugin copies into a project, and the only one besides the
rules pointer.** That is a deliberate exception to the one-copy invariant, taken because a gate that
lives only in the plugin cannot run in the project's CI, which is the entire point of it. Two
obligations come with it: `/essentials:slice-check` gate 12 reports a project copy that differs from
the plugin's alongside the rules-pointer staleness check, and `/essentials:upgrade` Group A2 does the
same — both *offer* a refresh and never overwrite silently.

## Step 10 — Write the answers

```bash
mkdir -p /tmp/essentials-init
```

Write `/tmp/essentials-init/answers.json` with exactly these keys (the renderer rejects unknown
keys, a missing one, and a value outside its set):

```json
{"language": "kotlin",
 "db": "pg-event-sourced",
 "web": "webflux",
 "frontend": "embedded",
 "compose": true,
 "lintGate": "hook",
 "projectName": "Orders Service",
 "groupId": "dk.trustworks.orders",
 "artifactId": "orders-service",
 "packagePath": "dk.trustworks.orders.ordersservice"}
```

`language` = `LANGUAGE`, `db` = `DB_PROFILE`, `web` = `WEB`, `frontend` = `FRONTEND`, `compose` =
`COMPOSE` (a JSON boolean), `lintGate` from Step 9, the metadata from Step 7, and `packagePath` as
the user confirmed it.

## Step 11 — Render the project

```bash
# Subdirectory mode: the project, plus the workspace pointer at the invocation root
python3 "${CLAUDE_PLUGIN_ROOT}/scripts/init-render.py" --answers /tmp/essentials-init/answers.json \
  --out "${TARGET_DIR}" --workspace-out "${WORKSPACE_DIR}"

# Current-directory mode: one --preserve per entry Step 8 listed (here: an existing CLAUDE.md and .gitignore)
python3 "${CLAUDE_PLUGIN_ROOT}/scripts/init-render.py" --answers /tmp/essentials-init/answers.json \
  --out "${TARGET_DIR}" --preserve CLAUDE.md --preserve .gitignore
```

Exit 0 prints the target directory. **Exit 2 means nothing usable was written** — report the
`init-render:` line from stderr verbatim and stop. Never write the project, or any file of it, by
hand as a fallback: a hand-written file is exactly the non-determinism the renderer exists to
remove.

What the render contains, and the requirement each part satisfies (read the file for its content;
the contract for the reason):

| Rendered | Requirement |
|---|---|
| Root `pom.xml` (the reactor parent, every version property) and `backend/pom.xml` | S1, S2 (one starter, plus the modules S2 says to declare), S2.1 (with its "what breaks" text as comments), S3.1, S3.4, S10, S11 |
| `config/EssentialsWebConfig` | S4 (the configurer for `WEB`), S7 (`SingleValueTypeModelConverter`) |
| `config/PersistenceSerializerConfiguration` (Kotlin only) | S3.2, S3.4 |
| `config/DocumentDbConfig` (Postgres profiles) | S5 |
| `config/SecurityConfig` | S9 — a permit-all starting point, not a posture |
| `config/SpaWebFilter` (embedded) · `config/CorsConfig` + `CorsProperties` (standalone) | S8 |
| `application.yml`, `backend/compose.yml` (Compose on; a replica set on Mongo) | S2, S7 |
| `IntegrationTestBase`, `ApplicationContextIT`, `OpenApiContractIT`, `CorsPreflightIT` (standalone) | S7, S8, S10 |
| `contracts/openapi.json` — a seed spec, byte-identical to what the empty application generates | S7 |
| `frontend/**` including `custom-fetch.ts` and `custom-fetch.test.ts` (with a frontend) | S7, S8 |
| `CLAUDE.md`, `README.md`, `dev.sh`, `.gitignore`, `.claude/rules/essentials-slices.md` | — |
| The Step 9 lint-gate files, and the workspace `CLAUDE.md` (subdirectory mode) | — |

**Do not scaffold an example bounded context.** The project gets an empty `${PACKAGE_PATH}` package
and a pointer to `/essentials:add-slice`, which elicits the kind, the bounded context and the names,
and emits a slice that matches the project's lane. The worked example at
`${CLAUDE_PLUGIN_ROOT}/tests/fixtures/worked-example/` is for this plugin's own verification — it is
not user-facing content.

`.claude/rules/essentials-slices.md` is the slice-rules **pointer**, copied verbatim from
`references/slice/project-rules-pointer.md.template`: the directory vocabulary, the four slice kinds
and the boundary rule, capped at ~35 lines. The full law stays in the plugin and is read live. Its
`<!-- essentials-slices-rules: vN -->` stamp is what `/essentials:add-slice` and
`/essentials:slice-check` compare to offer a refresh.

**Say which write styles the project can use.** `/essentials:add-slice` scaffolds all three §R5
styles in Java — per-slice deciders, one aggregate per BC, and a state-stored entity — and two of
them in Kotlin. The **aggregate** lane is Java-only, because `AggregateRoot` /
`StatefulAggregateRepository` are a Java-native family and `/essentials:slice-check` treats an
`aggregates/` directory in a Kotlin BC as Advisory interop. If the user picked Kotlin and wants that
lane, say so now rather than at their first `/essentials:add-slice`.

## Step 12 — Run the post-render hooks

Two files cannot be rendered: the Maven wrapper (a download) and the frontend lockfile (a registry
resolution). The manifest names the commands that create them, with their pins already substituted:

```bash
python3 "${CLAUDE_PLUGIN_ROOT}/scripts/init-render.py" --answers /tmp/essentials-init/answers.json --hooks
```

That prints a JSON list of `{"id", "cwd", "run", "why"}`. Run each `run` from `${TARGET_DIR}/<cwd>`,
in order, and record each outcome for Step 14:

- `maven-wrapper` needs `mvn` on the PATH. Without it, skip the hook and say so: the project builds
  with `mvn` once Maven is installed, `./dev.sh` falls back to it, and running the same command
  later creates the wrapper.
- `frontend-lockfile` needs `npm` and the registry. Without them, skip it and say plainly what that
  costs: the embedded build's `npm ci` fails until a `package-lock.json` exists, so until then the
  backend builds with `-Pskip-frontend`.

If `lintGate == "hook"` and `${TARGET_DIR}` is inside a git repository whose `core.hooksPath` is
unset, set it: `git -C "${TARGET_DIR}" config core.hooksPath .githooks`. Otherwise tell the user
that one command, to run after `git init`.

## Step 13 — Merge what the user already had

Every `*.essentials-init` file the render left beside an existing file gets the decision Step 8
recorded:

- **`overwrite`**: move the rendered file over the existing one.
- **`merge`**: **as the LLM, do an intelligent merge** of the rendered file into the user's file:
  - Preserve the user's project-specific content (description, custom build steps, internal links,
    team conventions, prose).
  - Bring in the rendered Essentials sections (framework knowledge, project pins, code locations,
    stack, layout, commands, conventions).
  - For `CLAUDE.md`, the **`## Trustworks Essentials framework knowledge`** section is mandatory and
    is carried over **verbatim**, stamp line included — insert it near the top if it is not already
    there.
  - When the same heading exists in both, prefer the user's prose and append the Essentials bullets
    under it.
  - Surface unresolvable conflicts inline as `<!-- ESSENTIALS-INIT: <note> -->` for the user to
    review and prune.
  - Write the result over the user's file and delete the `.essentials-init` file.
- **`skip`**: leave the user's file untouched. Keep the rendered `CLAUDE.md.essentials-init` until
  Step 14 has quoted its framework-knowledge section, then delete it.
- **`.gitignore`**: append the rendered lines the existing file lacks, then delete the side file.
- **Workspace `CLAUDE.md`** (subdirectory mode), by `WORKSPACE_CLAUDE_MD_MODE`: `overwrite` as above;
  `merge` adds the rendered pointer as its own clearly delimited section near the top
  (`## Trustworks Essentials project pointer`), preserving the user's content verbatim, and replaces
  a pointer section a previous run left rather than duplicating it; `skip` as above, and Step 14
  quotes the rendered pointer.

No `.essentials-init` file may be left behind at the end of the run, except one Step 14 names.

### Version stamp

The rendered `CLAUDE.md` carries `<!-- essentials-init: essentials <ESSENTIALS_VERSION> -->` under the
framework-knowledge heading. `ESSENTIALS_VERSION` is the Essentials release this plugin targets, the
`essentials.version` pin — the same value the rendered root `pom.xml` holds. Record it for Step 14:

```bash
grep -m1 '^| `essentials.version`' "${CLAUDE_PLUGIN_ROOT}/references/stack/stack-pins.md" \
  | grep -oE '[0-9]+\.[0-9]+\.[0-9]+[^ *|]*' | head -1
```

The stamp is the **only** state this command leaves behind beyond the project's own files — a comment
in a file that was being written anyway, not a plugin-owned directory. `/essentials:upgrade` reads it
to report which Essentials version the project was scaffolded against. Its absence is not an error: a
project with no stamp, or with an older `v<semver>` stamp, is reported as scaffolded before the
plugin's first release, and every conformance check runs the same way regardless.

On a **merge** path check that the stamp survived; on the **skip** path it did not land. Say so in
Step 14 rather than assuming.

## Step 13.5 — Check the workspace pointer (subdirectory mode only)

Skip this step when `TARGET_MODE == "current directory"` — there is no separate workspace root, and
the project `CLAUDE.md` serves that role.

In subdirectory mode the pointer came from
`references/init-assets/project/WORKSPACE-CLAUDE.md.template` in Step 11 and was merged or skipped in
Step 13. Confirm the result: unless the mode was `skip`, `${WORKSPACE_DIR}/CLAUDE.md` names
`./<artifactId>/`, the package, and the root `pom.xml` as the version-pin authority.

## Step 13.7 — Lint and smoke-build the generated project (MUST — do not skip)

**This step exists because the contract's most expensive failures compile cleanly and then kill
context startup.** A rendered project that has never been built is a specification, not a project.
The renderer and the templates are built in CI, but not on this machine, with this JDK, Maven,
Docker and network.

**1. Lint the render.** Pass the answers, so the lint checks what was asked for rather than what it
detects:

```bash
python3 "${CLAUDE_PLUGIN_ROOT}/scripts/stack-lint.py" "${TARGET_DIR}" \
  --language "${LANGUAGE}" --db "${DB_PROFILE}" --web "${WEB}" --frontend "${FRONTEND}"
```

Exit 0 is the expected result. **Exit 1 on a fresh render is a defect in the templates or the
contract**, not in the project: report every finding verbatim (id, file:line, message) and treat it
as a plugin bug to file. Do not patch the project. Exit 2: report the stderr line.

**2. Build.** From `${TARGET_DIR}` (the reactor root), with the wrapper when Step 12 created one:

```bash
cd "${TARGET_DIR}"
if [ -x ./mvnw ]; then MVN=./mvnw; else MVN=mvn; fi
SKIP=""; [ "${FRONTEND}" = embedded ] && SKIP=-Pskip-frontend     # the SPA is checked in 3.
cp contracts/openapi.json /tmp/essentials-init/openapi.seed.json

if docker info >/dev/null 2>&1; then
  # Compile, unit tests, then the integration tests against a Testcontainers database:
  # ApplicationContextIT starts the context, OpenApiContractIT regenerates the spec,
  # CorsPreflightIT sends a preflight through the security chain (standalone).
  "$MVN" -B verify $SKIP 2>&1 | tail -60
  cmp -s contracts/openapi.json /tmp/essentials-init/openapi.seed.json || echo "SPEC CHANGED"
else
  # No Docker: compile only. The context start is NOT tested.
  "$MVN" -B test-compile $SKIP 2>&1 | tail -30
fi
```

Be honest about the no-Docker branch: every context-startup failure — the whole S2.1 class — is
invisible to a compile, so the result is `compiled-only`, not `passed`, and Step 14 tells the user
to run `./mvnw verify` once Docker is available. `SPEC CHANGED` after a green `verify` means the seed
spec and the generated one disagree; report it as a template defect (and keep the regenerated file,
which is the correct one).

**3. The frontend** (`FRONTEND != none`), when `npm` is on the PATH and Step 12 created the lockfile:

```bash
cd "${TARGET_DIR}/frontend" && npm ci && npx orval && npx tsc --noEmit && npx vitest run && npm run build
```

`custom-fetch.test.ts` is the check that matters here: it proves the base URL is read (standalone)
or never read (embedded). Without npm, say that the frontend is unchecked. On embedded, the JAR with
the SPA inside is `./mvnw package` without `-Pskip-frontend`, which downloads its own Node — leave
that first full build to the user and say so.

**Reading a failure — map it back to the requirement rather than improvising a fix:**

| Symptom | Cause | Requirement |
|---|---|---|
| `Failed to deduce bean type for …reactiveHandlersBeanPostProcessor` | No `PlatformTransactionManager` | S2.1 — `spring-boot-starter-jdbc`; the message names nothing relevant, so do not chase the post-processor |
| `NoClassDefFoundError: org/jdbi/v3/postgres/PostgresPlugin` | JDBI not declared | S2.1 — `jdbi3-core` + `jdbi3-postgres` |
| `Failed to load driver class org.postgresql.Driver` | The JDBC driver not declared | S2.1 — `org.postgresql:postgresql` |
| `NoClassDefFoundError: …/MongoCustomConversions$MongoConverterConfigurationAdapter` (behind *Failed to deduce bean type*) | Spring Data MongoDB not declared | S2.1 — `spring-boot-starter-data-mongodb` |
| `NoClassDefFoundError: org/reactivestreams/Publisher` | `reactor-core` absent on WebMvc | S2.1 |
| `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics` | Kotlin stdlib absent | S2.1 — `kotlin-stdlib-jdk8`, **including on the Java lane** |
| `cannot access kotlin.reflect.KClass` at compile | `kotlin-reflect` absent | S2.1 |
| Context starts, typed path variable returns **500** | No `Essentials*WebConfigurer` `@Import` | S4 — the dependency alone is inert |
| Every persistence serializer throws `IllegalStateException` at startup | A Jackson 2 Essentials module (`types-jackson` / `immutable-jackson`) on the classpath | S3.1 |
| `MongoTimeoutException`, or a transaction error on Mongo | Unbound `spring.data.mongodb.*` connection keys, or no replica set | S2 |
| `Could not find a valid Docker environment` | Docker is not running | — rerun without it: `compiled-only` |

**A failure here is a defect in the plugin — the templates, the renderer or the contract — not in the
project.** Report exactly what failed, with the command output, and the requirement you believe is
unmet. Do not add dependencies or edit rendered files until the context happens to start: an
unexplained fix is a contract gap that needs filing, and a render patched by guesswork diverges from
what every other user receives. If the user wants the project anyway, leave it as rendered and say
which command failed.

Record `SMOKE_RESULT`: `passed` (lint clean, `verify` green, and the frontend checked or absent),
`compiled-only` (no Docker), or `failed` (name the step), plus the frontend outcome.

## Step 14 — Final report

Report to the user:

```
Created Essentials project at: ${TARGET_DIR}

Configuration:
  Frontend:        ${FRONTEND}          # embedded | standalone | none
  Language:        ${LANGUAGE}          # both fully supported
  Web stack:       ${WEB}
  DB profile:      ${DB_PROFILE}
  Docker Compose:  ${COMPOSE}
  Lint gate:       ${LINT_GATE}         # hook | script | none
  Project name:    ${displayName}
  groupId:         ${groupId}
  artifactId:      ${artifactId}
  Package:         ${PACKAGE_PATH}
  Essentials:      ${ESSENTIALS_VERSION}   # stamped into CLAUDE.md; /essentials:upgrade reads it
  Stack lint:      ${LINT_RESULT}
  Smoke build:     ${SMOKE_RESULT}      # passed | compiled-only | failed
  Maven wrapper:   ${WRAPPER_RESULT}    # created | skipped (no mvn)

Next steps:
  cd ${TARGET_DIR}
  ./dev.sh             # full dev stack (backend + frontend, if one was scaffolded)
  # or:
  ./dev.sh backend     # backend only on :8080 (auto-starts the DB if Docker Compose is enabled)
  ./dev.sh frontend    # frontend on :5173, proxies /api/* to :8080  [frontend only]
  ./mvnw verify        # integration tests (Docker); regenerates contracts/openapi.json — commit it

For framework documentation while developing, the project CLAUDE.md tells Claude to consult
the essentials-docs skill. Just ask Essentials questions normally and Claude will load it.

Slice rules are active: .claude/rules/essentials-slices.md loads every session (directory
vocabulary, the four slice kinds, the boundary rule). The full law lives in the plugin.

Add your first slice:
  /essentials:add-slice              # elicits kind, bounded context, and names
  /essentials:slice-check            # audit slices against the law
```

If `FRONTEND == "none"`, drop the frontend lines from "Next steps". Use `mvn` instead of `./mvnw`
when no wrapper was created, and say how to create it (the Step 12 command).

Say plainly that `config/SecurityConfig` permits everything: a starting point, not a posture (S9).

If `SMOKE_RESULT` is `compiled-only`, say that the context was never started and that
`./mvnw verify` with Docker running is the check that was skipped. If the frontend lockfile hook
was skipped, say that the embedded build needs `npm install --package-lock-only` in `frontend/` first.

If `CLAUDE_MD_MODE == "merge"` or `README_MD_MODE == "merge"`, append a one-line note: "Merged
Essentials sections into existing `<file>` — review for any `<!-- ESSENTIALS-INIT: … -->` markers."

If the `<!-- essentials-init: essentials … -->` stamp did not land (the `skip` path, or a merge that
dropped it), say so in one line — `/essentials:upgrade` will then report the project as unstamped.
That is a degraded report, not a broken one.

If Step 0 detected an existing Essentials project and the user chose to scaffold a second one
anyway, say so explicitly: "The existing Essentials project at `<path>` was **not** modified. Run
`/essentials:upgrade` from its directory to bring it up to what the installed plugin ships."

If `CLAUDE_MD_MODE == "skip"`, quote the `## Trustworks Essentials framework knowledge` section of
`CLAUDE.md.essentials-init`, tell the user to paste it into their `CLAUDE.md` so the `essentials-docs`
skill can auto-load, then delete the side file.

If `TARGET_MODE == "new subdirectory"`, add a line about the workspace pointer:

- `WORKSPACE_CLAUDE_MD_MODE == "none"` or `"overwrite"`: "Wrote the workspace pointer at `${WORKSPACE_DIR}/CLAUDE.md`, so planner and implementer sessions launched from the workspace root know the project lives at `${artifactId}/` and that the stack pins are authoritative in `${artifactId}/pom.xml`."
- `WORKSPACE_CLAUDE_MD_MODE == "merge"`: "Merged the Essentials workspace pointer into `${WORKSPACE_DIR}/CLAUDE.md` — review the new `## Trustworks Essentials project pointer` section."
- `WORKSPACE_CLAUDE_MD_MODE == "skip"`: warn loudly — "Skipped writing `${WORKSPACE_DIR}/CLAUDE.md`. Planners and implementers launched at this workspace will have no signal that the project lives at `${artifactId}/` and may scaffold files at the wrong root or invent their own stack pins. Recommended: paste the pointer (quoted below) into your workspace `CLAUDE.md`." Then quote the side-rendered `CLAUDE.md.essentials-init` and delete it.

## Error handling

- `${CLAUDE_PLUGIN_ROOT}` unset (the command was invoked outside Claude Code's plugin runner) ⇒
  abort: "This command must be invoked from within Claude Code with the essentials plugin installed."
- No Python 3.11+ ⇒ abort and say so; there is no fallback that writes the project by hand.
- The target directory exists and is non-empty in subdirectory mode (Step 8) ⇒ abort with a clear
  message; never overwrite.
- `init-render.py` exits 2 (unknown placeholder or pin, a malformed template, bad answers, a
  non-empty target) ⇒ report its message verbatim and stop. An unknown placeholder or pin is a
  plugin defect; bad answers are yours — fix the answers file and rerun.
- A pin the render needs and `stack-pins.md` does not list surfaces as that exit 2. Do not guess a
  version.
- `stack-lint.py` reports a finding on a fresh render, or the Step 13.7 build fails ⇒ **stop and
  report** with the output. Do not improvise dependencies until the context happens to start.
