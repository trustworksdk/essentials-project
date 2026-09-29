---
name: init
description: >-
  Scaffold a new Trustworks Essentials project. Asks for target directory, frontend mode, backend
  language, DB profile, Docker Compose and project metadata; produces a Spring Boot skeleton (Spring
  Initializr by default, or generated locally with no network), adds the Essentials layer by
  applying the stack contract (references/stack/, S1-S11), and smoke-builds the result so a
  context-startup failure surfaces here. Always writes a project CLAUDE.md that points back to the
  essentials-docs skill.
user-invocable: true
allowed-tools: [Read, Write, Edit, Bash, Glob, AskUserQuestion]
---

# /essentials:init

Bootstrap a new Trustworks Essentials project. Walk through the steps below in order, using `AskUserQuestion` for every choice. Do not skip steps — each one captures information that later steps depend on.

**There is no bundled project scaffold, and that is deliberate.** This command does not copy a
checked-in tree; it generates one. The Spring Boot skeleton comes from **Spring Initializr** by
default — it tracks the Boot parent, the wrapper and the release cadence so this plugin does not
have to — with a **fully supported offline path** that generates the skeleton locally instead
(Step 5.5 asks; Step 8 implements both). The Essentials layer on top is derived from the **stack
contract** either way:

| Source | What it supplies |
|---|---|
| `${CLAUDE_PLUGIN_ROOT}/references/stack/stack-contract.md` | Requirements **S1–S11** — what the generated project must satisfy |
| `${CLAUDE_PLUGIN_ROOT}/references/stack/stack-pins.md` | **Every version number.** Read it; never invent a pin |
| `${CLAUDE_PLUGIN_ROOT}/references/stack/<language>-spring-boot.md` | Language bindings — `kotlin-spring-boot.md` or `java-spring-boot.md`, whichever Step 3 selected (compiler args, which Jackson modules, whether the typed-edge converter is optional) |
| `${CLAUDE_PLUGIN_ROOT}/references/stack/frontend-react.md` | The S8 frontend mode, embedded or standalone |
| `${CLAUDE_PLUGIN_ROOT}/references/init-assets/` | `CLAUDE.md.template`, `README.md.template`, `dev.sh` |
| `${CLAUDE_PLUGIN_ROOT}/references/slice/project-rules-pointer.md.template` | The project's always-on slice rules |

The contract is the specification and this command is one implementation of it. **When a step below
and the contract disagree, the contract wins** — raise the discrepancy rather than papering over it.

The LLM docs under `${CLAUDE_PLUGIN_ROOT}/references/llm/` are consumed by the `essentials-docs`
skill and are **never** copied into the generated project.

> **Consequence of generating rather than copying:** two runs with identical answers may differ in
> incidental ways (comment wording, member order). The *contract-relevant* content must not vary —
> if a requirement in S1–S11 is satisfied differently between runs, that is a defect. Pins never
> vary: they come from `stack-pins.md`.

## Step 0 — Pre-flight check

Two questions, in this order. The second one is the one that matters, and it is not "is this
directory empty".

```bash
ls -A 2>/dev/null | head

# Is this already an Essentials project?
ls .claude/rules/essentials-slices.md 2>/dev/null
grep -rl 'dk\.trustworks\.essentials' --include=pom.xml --include=build.gradle \
  --include=build.gradle.kts . 2>/dev/null | head -3
grep -l 'Trustworks Essentials framework knowledge' CLAUDE.md */CLAUDE.md 2>/dev/null | head -3
```

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
substantive files (anything other than `.git/`, `README.md`, `LICENSE`, `.gitignore`), it is a
candidate for a new subdirectory; carry that hint into Step 1.

## Step 1 — Target directory

Ask via `AskUserQuestion`:

- **Question**: "Where should the new project be created?"
- **Header**: "Target"
- **Options**:
  1. **"In the current directory"** — "Scaffold into `$(pwd)`. Use only if the current directory is empty (or contains only .git/README/LICENSE)."
  2. **"In a new subdirectory"** — "Create `./<project-name>/` and scaffold there. Project name is captured later. Recommended if the current directory is non-empty."

Remember the choice as `TARGET_MODE`.

## Step 2 — Stack

Ask via `AskUserQuestion`:

- **Question**: "Which stack should the project include?"
- **Header**: "Stack"
- **Options**:
  1. **"Backend + embedded React frontend (Recommended)"** — "One deployable fat JAR. React + TypeScript + Vite, built into the backend JAR's static resources, wired via SpringDoc → openapi.json → Orval. Same origin, so no CORS and no API base URL to configure."
  2. **"Backend only"** — "Backend module only — no frontend, no Orval pipeline. Smaller scaffold, faster builds."
  3. **"Backend + standalone React frontend"** — "Two deployables: the JAR and a separately hosted SPA. Requires real CORS, a consumed API base URL, and an SPA rewrite at the static host. Choose when a separate team, a CDN, or multiple clients forces it."

Remember as `STACK` (one of `embedded`, `backend-only`, `standalone`).

This is the contract's **S8** choice. Before emitting anything for options 1 or 3, read
`${CLAUDE_PLUGIN_ROOT}/references/stack/frontend-react.md` — the two modes have different required
pieces and different *forbidden* pieces, and half-configuring either fails silently.

## Step 3 — Backend language

Ask via `AskUserQuestion`:

- **Question**: "Backend language?"
- **Header**: "Language"
- **Options**:
  1. **"Kotlin (Recommended)"** — "Kotlin with the Spring Boot Kotlin plugin. Value-class ids bind as path variables with nothing from Essentials, and the `kotlin-eventsourcing` DSL gives the decider lane its most concise form."
  2. **"Java"** — "Records for commands and events, and the `EventStream*` decider family. Also the only language on the **aggregate** write style (§R5), whose Essentials API is Java-native."

Remember as `LANGUAGE` (`kotlin` or `java`). **Both are fully supported** — the language selects
which bindings apply, not whether the project can be generated.

`${CLAUDE_PLUGIN_ROOT}/references/stack/<language>-spring-boot.md` is the authority for everything
that differs. Read the one matching the answer before Step 9; the two differ in ways that fail
silently rather than loudly (`-parameters`, which Jackson modules go on which mapper, whether the
typed-edge converter is optional).

## Step 4 — DB profile

Ask via `AskUserQuestion`:

- **Question**: "Which persistence profile?"
- **Header**: "DB profile"
- **Options**:
  1. **"PostgreSQL — event-sourced (Recommended)"** — "Wires `spring-boot-starter-postgresql-event-store`. Includes EventStore, EventProcessors, durable queues, fenced locks, inbox/outbox. The default Trustworks pattern."
  2. **"PostgreSQL — CRUD (no event store)"** — "Wires `spring-boot-starter-postgresql`. JDBI + UnitOfWork + queues + locks + inbox/outbox, but no EventStore."
  3. **"MongoDB"** — "Wires `spring-boot-starter-mongodb`. Spring Data Mongo + queues + locks + inbox/outbox."

Remember as `DB_PROFILE` (one of `pg-event-sourced`, `pg-crud`, `mongo`).

## Step 5 — Docker Compose

Ask via `AskUserQuestion`:

- **Question**: "Include Docker Compose for the database?"
- **Header**: "Docker Compose"
- **Options**:
  1. **"Yes, include (Recommended)"** — "Includes `docker-compose.yml` and Spring Boot Docker Compose support. Backend auto-starts the DB on `mvn spring-boot:run`."
  2. **"No, I'll manage my own DB"** — "Strips `docker-compose.yml` and disables Docker Compose support. You set up the DB and provide connection strings."

Remember as `DOCKER_COMPOSE` (boolean).

## Step 5.5 — Skeleton source

The Spring Boot skeleton can come from Spring Initializr or be generated locally. Ask via
`AskUserQuestion`:

- **Question**: "How should the Spring Boot skeleton be produced?"
- **Header**: "Skeleton"
- **Options**:
  1. **"Spring Initializr (Recommended)"** — "Fetches a skeleton from the official service at `https://start.spring.io`, run by the Spring team. Supplies the parent POM, the Maven wrapper, the application class (`Application.kt` or `Application.java`) and a starter test — so the Boot parent version, the wrapper and the release cadence are tracked upstream rather than by this plugin. Requires network access."
  2. **"Generate locally from the contract"** — "No network. Writes the parent and backend POMs directly from `references/stack/stack-pins.md` and the contract's S11, and emits the application class (`Application.kt` or `Application.java`) itself. No Maven wrapper unless you add one (`mvn wrapper:wrapper`), and the Boot parent version comes from our pinned table rather than from Initializr."

Remember as `SKELETON_SOURCE` (`initializr` or `local`).

**Both paths produce a project that satisfies the contract** — the difference is who tracks the Boot
skeleton's own versions, and whether the command touches the network at all.

> **On trusting Initializr.** `https://start.spring.io` is the official Spring Initializr service
> (TLS certificate `CN=*.spring.io`, VMware LLC) — the same endpoint IntelliJ, Spring Tool Suite,
> the VS Code Spring extension and the Spring Boot documentation use. It is nonetheless a
> **code-generating service whose output this command unzips into the user's project**, and because
> every archive is generated per request there is no published checksum to verify it against. What
> the Initializr path does guarantee: HTTPS to that host only, pinned request parameters, and a
> structural check before anything else runs. If that is more trust than a given project wants to
> extend, option 2 exists for exactly that reason — say so plainly rather than treating it as a
> degraded mode.

## Step 6 — Project metadata

Capture three free-text values. Use `AskUserQuestion` for each, with a single sensible default as the recommended option and "Other" letting the user enter their own.

1. **Project display name** — e.g., "Orders Service". Used in `pom.xml` `<name>` and project CLAUDE.md heading. Default suggestion: derive from current directory name.
2. **Maven groupId** — e.g., `dk.trustworks.orders`. Default suggestion: `dk.trustworks.example`.
3. **Maven artifactId** — e.g., `orders-service`. Default suggestion: derive from project display name lowercased + hyphen-separated.

Compute derived values:

- `PACKAGE_PATH = groupId + "." + artifactId-with-dashes-replaced-by-dots-and-no-leading-digits` — e.g., `dk.trustworks.orders.ordersservice`. (When `artifactId` is purely descriptive like `orders-service`, append it; when it duplicates the trailing groupId segment, just use `groupId`. Use your judgment; ask the user to confirm if ambiguous.)
- `PACKAGE_DIR = PACKAGE_PATH.replace('.', '/')` — e.g., `dk/trustworks/orders/ordersservice`.

Confirm the four-tuple with the user via a single `AskUserQuestion` summary before proceeding:

> "Confirm: project=`<displayName>`, groupId=`<groupId>`, artifactId=`<artifactId>`, package=`<PACKAGE_PATH>`?"
>
> Options: "Yes, proceed" / "No, let me change something" (loop back to Step 6 if changed).

## Step 7 — Resolve target directory

If `TARGET_MODE == "current directory"`: `TARGET_DIR = $(pwd)`.

Otherwise: `TARGET_DIR = $(pwd)/<artifactId>` — and create it with `mkdir -p`. If it already exists and is non-empty, abort with a clear message.

## Step 7.5 — Decide how to handle existing `CLAUDE.md` / `README.md`

Capture `WORKSPACE_DIR = $(pwd)` (the directory the user invoked `/essentials:init` from). When `TARGET_MODE == "new subdirectory"`, `WORKSPACE_DIR != TARGET_DIR` and a workspace-level pointer `CLAUDE.md` will be written in Step 13.5; when `TARGET_MODE == "current directory"`, `WORKSPACE_DIR == TARGET_DIR` and only the project-level files apply.

### 7.5a — Project-level files at `${TARGET_DIR}` (current-directory mode only)

When `TARGET_MODE == "current directory"`, the user may already have a `CLAUDE.md` and/or `README.md` in `TARGET_DIR` containing project-specific content that must not be lost. Step 13 would otherwise overwrite them.

Detect existing files:

```bash
ls -1 "${TARGET_DIR}/CLAUDE.md" "${TARGET_DIR}/README.md" 2>/dev/null
```

For each pre-existing file, ask via `AskUserQuestion` (skip the question if the file does not exist):

- **Question**: "An existing `CLAUDE.md` was found at `${TARGET_DIR}/CLAUDE.md`. How should it be handled?" (same for `README.md`)
- **Header**: "Existing CLAUDE.md" (or "Existing README.md")
- **Options**:
  1. **"Merge (Recommended)"** — "Preserve the existing project content and integrate the Essentials sections (framework knowledge block, stack, commands, conventions). Claude reads both files and produces a combined result, surfacing conflicts inline."
  2. **"Overwrite"** — "Replace the existing file entirely with the Essentials template output. Existing content is lost."
  3. **"Skip"** — "Leave the existing file untouched. NOTE: for `CLAUDE.md` this means the Essentials framework-knowledge block is NOT injected, so the `essentials-docs` skill won't auto-load until you add it manually."

Remember the choices as `CLAUDE_MD_MODE` and `README_MD_MODE` (one of `merge`, `overwrite`, `skip`, or `none` if the file did not exist).

If either is `merge`, back up the user's file **before** Step 13 overwrites it:

```bash
mkdir -p /tmp/essentials-init
[ -f "${TARGET_DIR}/CLAUDE.md" ]  && cp "${TARGET_DIR}/CLAUDE.md"  /tmp/essentials-init/CLAUDE.md.user
[ -f "${TARGET_DIR}/README.md" ] && cp "${TARGET_DIR}/README.md" /tmp/essentials-init/README.md.user
```

If either is `skip`, also back up the file so Step 13 can restore it after the cp clobbers it (cp the same way as above).

### 7.5b — Workspace-level pointer file at `${WORKSPACE_DIR}` (subdirectory mode only)

When `TARGET_MODE == "new subdirectory"`, future Claude Code sessions launched from `${WORKSPACE_DIR}` (e.g., a planner working at the workspace root) will **not** auto-discover `${TARGET_DIR}/CLAUDE.md` — Claude Code reads the cwd's `CLAUDE.md` and ancestors, not descendants. Without a workspace-level breadcrumb, downstream tooling has no signal that the project root is `<artifactId>/`, the package is pinned, or version pins live in `pom.xml`. This is the gap that lets a "Greenfield" design spec drop files at `${WORKSPACE_DIR}/src/main/kotlin/...` and invent its own stack pins.

Step 13.5 writes a small pointer `CLAUDE.md` at `${WORKSPACE_DIR}/CLAUDE.md` to close that gap. Detect any pre-existing file now:

```bash
[ -f "${WORKSPACE_DIR}/CLAUDE.md" ] && echo exists
```

If it exists, ask via `AskUserQuestion`:

- **Question**: "An existing `CLAUDE.md` was found at `${WORKSPACE_DIR}/CLAUDE.md` (the workspace root, parent of the new project directory). How should the Essentials workspace pointer be handled?"
- **Header**: "Workspace CLAUDE.md"
- **Options**:
  1. **"Merge (Recommended)"** — "Append a short Essentials workspace-pointer block (project location, pinned package, pom.xml as version-pin authority, no-source-files-at-workspace-root rule). Existing content is preserved."
  2. **"Overwrite"** — "Replace the existing workspace `CLAUDE.md` with the Essentials pointer file only. Existing content is lost."
  3. **"Skip"** — "Leave the existing workspace `CLAUDE.md` untouched. NOTE: downstream tooling launched at `${WORKSPACE_DIR}` will have no signal that the project lives at `<artifactId>/`, which is the failure mode this step exists to prevent."

Remember the choice as `WORKSPACE_CLAUDE_MD_MODE` (one of `merge`, `overwrite`, `skip`, or `none` if the file did not exist). If `merge`, also back up the file:

```bash
mkdir -p /tmp/essentials-init
cp "${WORKSPACE_DIR}/CLAUDE.md" /tmp/essentials-init/WORKSPACE_CLAUDE.md.user
```

## Step 8 — Produce the Spring Boot skeleton

Read `${CLAUDE_PLUGIN_ROOT}/references/stack/stack-pins.md` **first**, on both paths — the Boot
version, Java level and Kotlin version all come from there. Never invent a pin, and never "use the
latest".

### 8a — Spring Initializr (`SKELETON_SOURCE == "initializr"`)

```bash
mkdir -p /tmp/essentials-init
curl -fsSL --proto '=https' --tlsv1.2 https://start.spring.io/starter.zip \
  -d type=maven-project \
  -d language="${LANGUAGE}" \
  -d bootVersion="${BOOT_VERSION}" \
  -d javaVersion="${JAVA_VERSION}" \
  -d groupId="${GROUP_ID}" -d artifactId="${ARTIFACT_ID}" \
  -d name="${PROJECT_NAME}" -d packageName="${PACKAGE_PATH}" \
  -d dependencies=webflux,security,validation,actuator,docker-compose,devtools,jdbc,postgresql \
  -o /tmp/essentials-init/starter.zip
unzip -q /tmp/essentials-init/starter.zip -d "${TARGET_DIR}/backend"
```

Adjust `dependencies` to the answers: drop `docker-compose` when `DOCKER_COMPOSE == false`; on the
`mongo` profile drop `jdbc,postgresql` and add `data-mongodb`.

**`jdbc` and `postgresql` are not optional on a Postgres profile, and they are not hygiene.** They
are the first two rows of **S2.1**: no Essentials artifact pulls the JDBC starter or the driver, and
the failure is at context startup, not at compile. Without the JDBC starter there is no
`PlatformTransactionManager`, and on WebFlux that surfaces as *"Failed to deduce bean type for
`…reactiveHandlersBeanPostProcessor`"* — a message that names nothing relevant. Step 9a adds the
rest of the S2.1 set, which Initializr cannot supply. Initializr supplies the parent POM, the Maven wrapper,
the application class (`Application.kt` or `Application.java`) and a starter test — everything whose
version this plugin deliberately does not track.

**Inspect what arrived before building on it.** The archive is generated per request, so there is no
checksum to compare; a structural check is what is available:

```bash
unzip -l /tmp/essentials-init/starter.zip | head -30     # nothing outside the project, no absolute paths
test -f "${TARGET_DIR}/backend/pom.xml" && test -f "${TARGET_DIR}/backend/mvnw" \
  && grep -q "<artifactId>spring-boot-starter-parent</artifactId>" "${TARGET_DIR}/backend/pom.xml" \
  && echo "skeleton OK" || echo "skeleton FAILED — fall back to 8b"
```

**If Initializr is unreachable** (offline, firewall, DNS, non-200), do **not** fail and do **not**
retry in a loop: fall through to **8b**, and tell the user plainly that it happened and what changed
as a result. An automatic fallback that is not announced is how a project silently acquires a
different Boot pin than the user expected.

### 8b — Local generation (`SKELETON_SOURCE == "local"`, or 8a fell through)

No network. Write the skeleton directly from `stack-pins.md` and the contract's **S11**:

1. **Parent `pom.xml`** — `spring-boot-starter-parent` at the pinned Boot version, `<packaging>pom</packaging>`,
   the `<modules>` list, the version properties, and the BOM imports (Testcontainers, JDBI, Mockito,
   Spring Modulith).
2. **`backend/pom.xml`** — the module POM. Step 9 fills in its dependencies and build plugins.
3. **The application entry point**, in the language's source root:
   - Kotlin — `backend/src/main/kotlin/${PACKAGE_DIR}/Application.kt`: a plain
     `@SpringBootApplication` class plus `fun main(args: Array<String>) { runApplication<Application>(*args) }`.
   - Java — `backend/src/main/java/${PACKAGE_DIR}/Application.java`: a `@SpringBootApplication` class
     with `public static void main(String[] args) { SpringApplication.run(Application.class, args); }`.

   Set the POM's `<sourceDirectory>` / `<testSourceDirectory>` to match — `src/main/kotlin` only on
   the Kotlin path.
4. **No Maven wrapper.** Tell the user to run `mvn wrapper:wrapper` if they want one; do not
   hand-write `mvnw`, `mvnw.cmd` and `maven-wrapper.properties`.

Then:

```bash
test -f "${TARGET_DIR}/pom.xml" && test -f "${TARGET_DIR}/backend/pom.xml" \
  && echo "skeleton OK (local)" || { echo "skeleton FAILED"; exit 1; }
```

**Record which path was taken** as `SKELETON_ACTUAL` (`initializr` or `local`) — Step 14 reports it,
and it is the difference between "Boot's version came from Spring" and "Boot's version came from our
pinned table".

## Step 9 — Add the Essentials layer (the contract's S1–S5)

Now apply `${CLAUDE_PLUGIN_ROOT}/references/stack/stack-contract.md`. Read it; do not work from
memory. In order:

**9a — Dependencies (S1, S2, S3).** Add to `backend/pom.xml`, all Essentials artifacts pinned from a
single `essentials.version` property whose value comes from `stack-pins.md`:

- The persistence starter for `DB_PROFILE` — **exactly one** (S2's table).
- **The S2.1 set — the dependencies the starter does not bring.** Read S2.1 and emit its whole
  table for the chosen profile: `spring-boot-starter-jdbc`, the `postgresql` driver, `jdbi3-core`,
  `jdbi3-postgres`, `kotlin-stdlib-jdk8` and `kotlin-reflect` on both Postgres profiles;
  `spring-boot-starter-data-mongodb` on `mongo`; Jackson 3's `tools.jackson.core:jackson-databind`
  on every profile; `reactor-core` on a WebMvc stack. **Every one of
  these is `provided` upstream, so none is transitive, and none fails at compile time — they fail at
  context startup.** The Kotlin two go in **on the Java lane as well**: `postgresql-document-db` is a
  Kotlin module (`java-spring-boot.md` § Dependencies). Copy S2.1's "what breaks without it" text
  into the POM as comments, so the next reader knows the unused-looking dependencies are load-bearing
  and does not "clean them up".
- On **both Postgres profiles**: `postgresql-document-db`. S5 makes it the read-model store for
  PostgreSQL with no event-store precondition, and Step 9c emits `DocumentDbConfig` on both Postgres
  profiles — these two steps must agree. On Java it is additionally the interop surface view slices
  are generated against (`java-spring-boot.md` § Persistence).
- On `pg-event-sourced` only: `eventsourced-aggregates`, and — **on Kotlin only** —
  `kotlin-eventsourcing`. A Java project must **not** carry `kotlin-eventsourcing`: its
  decider family (`EventStreamDecider` / `EventStreamEvolver`) lives in `eventsourced-aggregates`,
  and the two families are parallel, not substitutes (`java-spring-boot.md` § Event sourcing).
- `types-spring-web` (S4). **Not optional on Java**: a Java id extends `CharSequenceType` and cannot
  bind without the converter, where a Kotlin `@JvmInline value class` binds with nothing from
  Essentials.
- On **Kotlin only**: `tools.jackson.module:jackson-module-kotlin` (S3.4,
  `kotlin-spring-boot.md` § Serialization). **No** Jackson 2 `jackson-databind` on either lane —
  Essentials is Jackson 3 only, and no Essentials artifact needs it (S3.1).
- Test scope: Testcontainers **2.x names** (S10), AssertJ, Awaitility, and jqwik — plus
  `jqwik-kotlin` on Kotlin only. When Step 12 or a later slice emits an integration-test base, use
  S10's imports verbatim: `@AutoConfigureWebTestClient` moved package in Boot 4 and is no longer
  applied implicitly by `@SpringBootTest`, and `PostgreSQLContainer` moved package in Testcontainers
  2.x while keeping its artifact coordinate. Both read as "this annotation/class does not exist".

**9b — Compiler configuration**, from the language's own file:

- **Kotlin** (`kotlin-spring-boot.md` § Compiler configuration): the all-open `spring` plugin,
  `-Xjsr305=strict`, `-Xannotation-default-target=param-property`, plus `-parameters`.
- **Java** (`java-spring-boot.md` § Compiler configuration): **no** `kotlin-maven-plugin` and no
  all-open plugin — Java classes are not `final`. `-parameters` on `maven-compiler-plugin` is
  **mandatory, not hygiene**: S3.5 makes constructor parameter names part of the JSON contract, and
  without it they erase to `arg0`/`arg1` and every properties-based creator fails to bind. Silent on
  write, fails on replay.

**9c — Configuration classes.** Emit each from the contract in the project's language (`.kt` or
`.java`), keeping the explanatory comments — they are the payload, not decoration:

| Class | Requirement | Why it exists |
|---|---|---|
| `config/EssentialsWebConfig.kt` | S4 | `@Import`s **one** `Essentials*WebConfigurer`. The `@Import` *is* the registration; the dependency alone is inert |
| `config/PersistenceSerializerConfiguration.kt` | S3.2, S3.4 | **Kotlin only.** Your own `JSONEventSerializer` (`pg-event-sourced`) or `JSONSerializer` (`pg-crud`, `mongo`) bean built on `EssentialsObjectMappers` with `KotlinModule` — the starter backs off from it, and a `KotlinModule` `@Bean` alone never reaches the persistence mapper. Java needs no equivalent — `EssentialTypesJacksonModule` already covers the Java `SingleValueType` hierarchy |
| `config/DocumentDbConfig.kt` | S5 | `DocumentDbRepositoryFactory` bean. **Postgres profiles only** — omit entirely on `mongo` |
| `config/SecurityConfig.kt` | S9 | A permit-all starting point. Tell the user plainly in Step 14 that this is not a posture |

**9d — Keep the types module on the web mapper (S3.3).** The starter publishes
`EssentialTypesJacksonModule` as a bean and Boot adds it to its web `JsonMapper` — so do **not**
define a `JsonMapper` bean of your own; if the project must, register the module on it explicitly.
Without it a typed wire contract is silently wrong.

**9e — MongoDB only:** connection properties are `spring.mongodb.*`, never `spring.data.mongodb.*`
(S2). The old keys are unbound in Boot 4 and fall back to `mongodb://localhost/test` with no warning.

## Step 10 — Configuration and the OpenAPI contract (S7)

Write `backend/src/main/resources/application.yml`: application name, server port, SpringDoc paths
(`/v3/api-docs`, `/swagger-ui.html`), logging level for `${PACKAGE_PATH}`, and — when
`DOCKER_COMPOSE == true` — the `spring.docker.compose` lifecycle keys plus a `compose.yml` with the
project's database.

Then wire contract-first generation exactly as S7 specifies: the `springdoc-openapi-maven-plugin`
fetching `/v3/api-docs` into `contracts/openapi.json`, the two `spring-boot-maven-plugin`
start/stop executions around it, and `application-openapi.yml` — the profile that turns off
Essentials life-cycles, management endpoints and security so generating a spec does not boot event
processors and do real work.

## Step 11 — Frontend (S8), if one was chosen

Skip entirely when `STACK == "backend-only"`.

Follow `${CLAUDE_PLUGIN_ROOT}/references/stack/frontend-react.md` for the chosen mode. Scaffold the
app with Vite (`npm create vite@latest frontend -- --template react-ts`), then add the pieces that
mode requires — the pins for every dependency come from `stack-pins.md`:

**Both modes:** `orval.config.ts` reading `../contracts/openapi.json`, a `custom-fetch.ts` mutator,
TanStack Query, and `.gitignore` entries for `src/shared/api/generated/` and `src/shared/api/model/`
— they are build output and **must not be committed**.

**Embedded** (`STACK == "embedded"`): `frontend-maven-plugin` (Node install → `npm ci` → `orval` →
`npm run build`, all on `prepare-package`, in that order), the `maven-resources-plugin` copy of
`frontend/dist` into `static/`, `web/SpaWebFilter.kt`, and the `skip-frontend` profile (S11).
**Do not emit a CORS configuration and do not set an API base URL** — same origin makes both dead
configuration, and Mode A lists them as forbidden.

**Standalone** (`STACK == "standalone"`): real CORS as a **`CorsConfigurationSource` bean** with the
origins bound via `@ConfigurationProperties` — not a standalone `CorsWebFilter`, which sits behind
Spring Security's chain, and not `@Value`, which cannot bind a YAML sequence
(`frontend-react.md` § Mode B); a
`VITE_API_BASE_URL` that `custom-fetch.ts` **actually reads** (declaring it is not wiring it), and
no SPA filter, no `frontend-maven-plugin`,
no static-resource copy. Tell the user the deep-link rewrite is now their static host's job, and
that the dev proxy will hide CORS problems until deployment.

## Step 12 — Project rules and the first slice

Copy the slice-rules pointer from its single source of truth:

```bash
mkdir -p "${TARGET_DIR}/.claude/rules"
cp "${CLAUDE_PLUGIN_ROOT}/references/slice/project-rules-pointer.md.template" \
   "${TARGET_DIR}/.claude/rules/essentials-slices.md"
grep -o 'essentials-slices-rules: v[0-9]*' "${TARGET_DIR}/.claude/rules/essentials-slices.md" \
  || echo "MISSING: slice rules pointer did not copy"
```

That file is a **pointer**, capped at ~35 lines: the directory vocabulary, the four slice kinds, and
the boundary rule. The full law stays in the plugin (`rules/slice-design.md`) and is read live, so
plugin updates reach the project without rewriting it. Its `<!-- essentials-slices-rules: vN -->`
stamp is what `/essentials:add-slice` and `/essentials:slice-check` compare against to offer a
refresh.

Also copy `dev.sh` from `${CLAUDE_PLUGIN_ROOT}/references/init-assets/dev.sh`, trimming the
`frontend` and `generate` cases when `STACK == "backend-only"`.

### Step 12.5 — Offer the manifest lint gate (ask; do not install silently)

Nothing in a Maven or Gradle build reads `slice.yaml`. A manifest that stops being valid YAML
therefore compiles, tests green, and ships — and the slice **silently drops out** of every gate and
of `/essentials:slice-map`. Invisible reads as compliant. `scripts/slice-lint.py` is the deterministic
gate that makes it loud, and it is only worth anything if the project runs it without being asked.

`AskUserQuestion`: **Install the slice-manifest lint gate?**

| Option | What it writes |
|---|---|
| **Pre-commit hook (recommended)** | `scripts/slice-lint.py` + a `.githooks/pre-commit` entry, and sets `core.hooksPath` if the project has no hooks path yet |
| **Script only** | `scripts/slice-lint.py`, for the user to wire into their own CI |
| **Neither** | Nothing. Say once that `/essentials:slice-check` still runs the same checks on demand |

```bash
mkdir -p "${TARGET_DIR}/scripts"
cp "${CLAUDE_PLUGIN_ROOT}/scripts/slice-lint.py" "${TARGET_DIR}/scripts/slice-lint.py"
chmod +x "${TARGET_DIR}/scripts/slice-lint.py"
cp "${CLAUDE_PLUGIN_ROOT}/references/slice/slice-yaml.schema.json" \
   "${TARGET_DIR}/scripts/slice-yaml.schema.json"
```

**The schema copy is required, not optional.** The script defaults to resolving the schema relative to
its own location inside the plugin; installed into a project it has no plugin root to walk up to, so
the project keeps its own copy beside it and the hook passes `--schema`.

The hook, if chosen — it must **fail open** when the interpreter or the dependencies are missing, so a
contributor without them can still commit:

```bash
#!/usr/bin/env bash
# Slice manifest gate — see the essentials plugin, /essentials:slice-check
git diff --cached --name-only --diff-filter=ACM | grep -q 'slice\.yaml$' || exit 0
command -v python3 >/dev/null || exit 0
python3 scripts/slice-lint.py . --schema scripts/slice-yaml.schema.json
status=$?
[ $status -eq 2 ] && { echo "slice-lint could not run (pip install pyyaml jsonschema) — not blocking"; exit 0; }
exit $status
```

**This is the second file this plugin copies into a project, and the only one besides the rules
pointer.** That is a deliberate exception to the one-copy invariant, taken because a gate that lives
only in the plugin cannot run in the project's CI, which is the entire point of it. Two obligations
come with it: `/essentials:slice-check` gate 12 reports a project copy that differs from the plugin's
alongside the rules-pointer staleness check, and `/essentials:upgrade` Group A2 does the same — both
*offer* a refresh and never overwrite silently.

**Say which write styles the project can use.** `/essentials:add-slice` scaffolds all three §R5
styles in Java — per-slice deciders, one aggregate per BC, and a state-stored entity — and two of
them in Kotlin. The **aggregate** lane is Java-only, because `AggregateRoot` /
`StatefulAggregateRepository` are a Java-native family and `/essentials:slice-check` treats an
`aggregates/` directory in a Kotlin BC as Advisory interop. If the user picked Kotlin and wants that
lane, say so now rather than at their first `/essentials:add-slice`.

**Do not scaffold an example bounded context.** The generated project gets an empty
`${PACKAGE_PATH}` package and a pointer to `/essentials:add-slice`, which elicits the kind, the
bounded context and the names, and emits a slice that matches the project's actual lane. The worked
example still exists for *this plugin's* own verification at
`${CLAUDE_PLUGIN_ROOT}/tests/fixtures/worked-example/` — it is not user-facing content.

## Step 13 — Render conditional templates (CLAUDE.md, README.md)

Two files ship as `*.template` with **nested** conditional blocks, in `${CLAUDE_PLUGIN_ROOT}/references/init-assets/`: `CLAUDE.md.template` and `README.md.template`. Render both and write the results into `${TARGET_DIR}` (the sources stay in the plugin and are not copied into the project first).

### Conditional syntax

```
<!-- IF <var>[=<value>] -->...<!-- END <var> -->
```

- Variables: `stack`, `frontend`, `db`, `language`, `docker-compose`.
- The closing tag uses only the variable name (`<!-- END stack -->`), matching whichever `IF <var>…` opened most recently. This means **blocks nest** — an outer `IF stack=full-stack` may contain inner `IF db=…` blocks. A flat name-based matcher will report false "mismatched IF/END" warnings against this layout. **Use a stack-based parser.**
- Form `<!-- IF docker-compose -->` (no `=value`) is truthy when `DOCKER_COMPOSE` is true.

### Parser (use this Python — it handles nesting correctly)

```python
import os, re
from pathlib import Path

CHOICES = {
    # Derived: the templates gate on "does this project have a frontend at all".
    "stack": "full-stack" if STACK in ("embedded", "standalone") else "backend-only",
    "frontend": STACK,                 # "embedded" | "standalone" | "backend-only"
    "db": DB_PROFILE,                  # "pg-event-sourced", "pg-crud", or "mongo"
    "language": LANGUAGE,              # "kotlin" or "java"
    "docker-compose": DOCKER_COMPOSE,  # bool
}

TOKEN  = re.compile(r"<!--\s*(IF\s+[\w-]+(?:=[\w-]+)?|END\s+[\w-]+)\s*-->")
IF_RE  = re.compile(r"IF\s+([\w-]+)(?:=([\w-]+))?")
END_RE = re.compile(r"END\s+([\w-]+)")

def render(text: str) -> str:
    pos = 0
    out = []
    stack = []  # entries: [var, keep, buffer]

    def emit(s):
        if stack:
            stack[-1][2].append(s)
        else:
            out.append(s)

    for m in TOKEN.finditer(text):
        emit(text[pos:m.start()])
        pos = m.end()
        body = m.group(1).strip()
        if body.startswith("IF"):
            mm = IF_RE.match(body)
            var, val = mm.group(1), mm.group(2)
            chosen = CHOICES.get(var)
            keep = bool(chosen) if val is None else (str(chosen) == val)
            # If parent is already discarded, child stays discarded too.
            if stack and not stack[-1][1]:
                keep = False
            stack.append([var, keep, []])
        else:
            mm = END_RE.match(body)
            end_var = mm.group(1)
            assert stack and stack[-1][0] == end_var, (
                f"mismatched IF {stack[-1][0] if stack else '<none>'} / END {end_var}"
            )
            var, keep, buf = stack.pop()
            if keep:
                emit("".join(buf))
    out.append(text[pos:])
    assert not stack, f"unclosed IF {stack[-1][0]}"
    return "".join(out)

for tpl in ("CLAUDE.md.template", "README.md.template"):
    p = Path(os.environ["CLAUDE_PLUGIN_ROOT"], "references/init-assets", tpl)
    rendered = render(p.read_text())
    # … then substitute {{projectName}} / {{groupId}} / {{artifactId}} /
    # {{packagePath}} / {{packageDir}} from Step 6, {{sourceLang}} ("kotlin" | "java")
    # and {{appFile}} ("Application.kt" | "Application.java") from Step 3,
    # {{db_label}} from Step 4 ("PostgreSQL" on both pg-* profiles, "MongoDB" on
    # mongo), and {{essentialsVersion}} from stack-pins.md (see "Version stamp" below) …
    Path(TARGET_DIR, tpl[:-len(".template")]).write_text(rendered)
```

### Write or merge

For each rendered file (`CLAUDE.md`, `README.md`), follow the per-file decision from Step 7.5:

- **`overwrite`** or **`none`** (no pre-existing file): write the rendered text to `${TARGET_DIR}/<file>`. (This is what the parser block above already does.)
- **`merge`**: read the user's backup at `/tmp/essentials-init/<file>.user`. Then **as the LLM, do an intelligent merge**:
  - Preserve the user's existing project-specific content (project description, custom build steps, internal links, team conventions, prose).
  - Inject the rendered template's Essentials-specific sections (Stack, Project layout, Commands, Conventions, package/groupId metadata).
  - For `CLAUDE.md`, the **"Trustworks Essentials framework knowledge"** block (quoted at the end of this step) is mandatory — insert it near the top if it isn't already present.
  - When the same heading exists in both, prefer the user's prose and append Essentials-specific bullets under it.
  - Surface unresolvable conflicts inline using HTML comments — `<!-- ESSENTIALS-INIT: <note> -->` — so the user can review and prune.
  - Write the merged result to `${TARGET_DIR}/<file>` and delete `/tmp/essentials-init/<file>.user`.
- **`skip`**: do **not** write the rendered file. Restore the user's backup over the rendered copy the parser block wrote:
  ```bash
  cp /tmp/essentials-init/<file>.user "${TARGET_DIR}/<file>"
  rm /tmp/essentials-init/<file>.user
  ```
  In Step 14, **explicitly remind the user** that the framework-knowledge block was not injected (quote it for them) so they can paste it into their existing `CLAUDE.md` manually.

### Version stamp

`{{essentialsVersion}}` is the Essentials version this plugin targets: the `essentials.version` pin
in `stack-pins.md`, the same value Step 9a wrote into `backend/pom.xml`. Read it from there and
substitute it into the framework-knowledge block:

```bash
grep -m1 '^| `essentials.version`' "${CLAUDE_PLUGIN_ROOT}/references/stack/stack-pins.md" \
  | grep -oE '[0-9]+\.[0-9]+\.[0-9]+[^ *|]*' | head -1
```

Record it as `ESSENTIALS_VERSION` — Step 14 reports it. It renders as
`<!-- essentials-init: essentials <ESSENTIALS_VERSION> -->` and is the **only** state this command
leaves behind beyond the project's own files — a comment in a file that was being written anyway, not
a plugin-owned directory. `/essentials:upgrade` reads it to report which Essentials version the
project was scaffolded against. Its absence is not an error: a project with no stamp, or with an older
`v<semver>` stamp, is reported as scaffolded before the plugin's first release, and every conformance
check runs the same way regardless.

On a **merge** or **skip** path the stamp may not land — say so in Step 14 rather than assuming it did.

### Mandatory framework-knowledge block

The rendered (or merged) `CLAUDE.md` must include this block — verify it is present and that placeholders have been substituted:

> ## Trustworks Essentials framework knowledge
>
> <!-- essentials-init: essentials {{essentialsVersion}} -->
>
> For ANY question about Trustworks Essentials APIs, modules, or patterns, **always consult the `essentials-docs` skill** from the `essentials` plugin. It auto-loads on Essentials-related questions and on code signals (imports of `dk.trustworks.essentials.*`, type names like `StatefulAggregate`, `DurableQueues`, `EventStore`, `FencedLock`). Do **not** search the web or guess — Essentials has its own opinionated patterns documented under the plugin's `references/llm/`.
>
> Before editing Essentials code, the skill consults the plugin's design guide and the traps index. Trust its advisory output.

## Step 13.5 — Write the workspace-level pointer `CLAUDE.md` (subdirectory mode only)

Skip this step entirely when `TARGET_MODE == "current directory"` — there is no separate workspace root to point from, and the project-level `CLAUDE.md` already serves that role.

When `TARGET_MODE == "new subdirectory"`, render the pointer block below with the substituted values, then write it according to `WORKSPACE_CLAUDE_MD_MODE` (captured in Step 7.5b):

### Pointer block (substitute `{{...}}` from Step 6, and render `<!-- IF stack=full-stack -->` from the Step 2 choice)

```markdown
# {{projectName}} workspace

The Trustworks Essentials project lives at **`./{{artifactId}}/`**. All implementation work — source files, tests, configurations, frontend assets, build scripts — belongs inside that subdirectory. Do **not** create new source files at this workspace root.

When using any planner/implementer tooling at this workspace level: either `cd {{artifactId}}/` first, or scope every file path in design/implementation specs with the `{{artifactId}}/` prefix. A "Greenfield. All paths are new" design that omits this prefix is a contradiction with the existing scaffold and must be corrected, not followed literally.

## Project pins (do not invent — these are fixed by /essentials:init)

- **Package root**: `{{packagePath}}` (source `package` declarations and Maven coordinates derive from this)
- **Maven groupId / artifactId**: `{{groupId}}` / `{{artifactId}}`
- **Stack version pins** (Spring Boot, the language and Java levels, Trustworks Essentials, and every other dependency): authoritative in `{{artifactId}}/backend/pom.xml`. Read it before any planning step that names a dependency, version, or language level. Do **not** propose your own pins, do **not** "upgrade to latest", and do **not** invent versions that look plausible.

## Code locations for new code

- Backend (production): `{{artifactId}}/backend/src/main/{{sourceLang}}/{{packageDir}}/...`
- Backend (tests): `{{artifactId}}/backend/src/test/{{sourceLang}}/{{packageDir}}/...`
- Backend resources / config: `{{artifactId}}/backend/src/main/resources/`
<!-- IF stack=full-stack -->
- Frontend (production): `{{artifactId}}/frontend/src/...`
- Frontend (tests): co-located with sources or under `{{artifactId}}/frontend/src/__tests__/` per the project's vitest setup
- Generated API client (do not hand-edit): `{{artifactId}}/frontend/src/shared/api/generated/`
- OpenAPI contract (regenerated, not hand-edited): `{{artifactId}}/contracts/openapi.json`
<!-- END stack -->

## More

For full project context, framework-knowledge auto-loading (the `essentials-docs` skill), conventions, and commands, see **`{{artifactId}}/CLAUDE.md`** — that file is the project-level source of truth.
```

### Write or merge

- **`overwrite`** or **`none`**: write the rendered pointer to `${WORKSPACE_DIR}/CLAUDE.md`.
- **`merge`**: read the user's backup at `/tmp/essentials-init/WORKSPACE_CLAUDE.md.user`. Append the rendered pointer as a clearly delimited section (e.g., under a new `## Trustworks Essentials project pointer` heading near the top), preserving the user's existing content verbatim. If a previous Essentials pointer block is already present (the user re-ran `/essentials:init`), replace it in-place rather than duplicating. Write the merged result to `${WORKSPACE_DIR}/CLAUDE.md` and delete `/tmp/essentials-init/WORKSPACE_CLAUDE.md.user`.
- **`skip`**: write nothing. In Step 14, **explicitly remind the user** that downstream tooling at the workspace root will not auto-discover the project location and quote the pointer block so they can paste it manually.

Apply the Step 6 `{{...}}` substitutions and `{{sourceLang}}` (Step 3) to the pointer text before writing. The `<!-- IF stack=full-stack -->` block uses the same conditional syntax as Step 13 — render it with the same parser, or simply include the block whenever `STACK != "backend-only"`.

## Step 13.7 — Smoke-build the generated project (MUST — do not skip)

**This step exists because every requirement in S2.1 compiles cleanly and then kills context
startup.** A generated project that has never been built is a specification, not a project; the
whole S2.1 table was discovered by a user running the command and booting the result, not by
reading it. Compiling is not enough — the failures are bean-wiring failures, so the context has to
*start*.

Run all three, in order, from `${TARGET_DIR}/backend`:

```bash
cd "${TARGET_DIR}/backend"

# 1. Compile — catches -parameters config, source roots, and kotlin-reflect on the Java lane
./mvnw -q -B test-compile 2>&1 | tail -30   || MVNW_MISSING=1

# 2. Resolve — every S2.1 dependency present and downloadable
./mvnw -q -B dependency:resolve 2>&1 | tail -20

# 3. Start the context — the only check that catches S2.1, S3.1 and S4
./mvnw -B test -Dtest='*ApplicationTests' -DfailIfNoTests=false 2>&1 | tail -40
```

If Step 8b produced the skeleton there is no `mvnw`; use `mvn` and say so.

**Reading the result — map the failure back to the requirement rather than improvising a fix:**

| Symptom | Cause | Fix |
|---|---|---|
| `Failed to deduce bean type for …reactiveHandlersBeanPostProcessor` | No `PlatformTransactionManager` | `spring-boot-starter-jdbc` (S2.1) — the message names nothing relevant, so do not chase the post-processor |
| `NoClassDefFoundError: org/jdbi/v3/postgres/PostgresPlugin` | JDBI not declared | `jdbi3-core` + `jdbi3-postgres` (S2.1) |
| `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics` | Kotlin stdlib absent | `kotlin-stdlib-jdk8` — **including on the Java lane** (S2.1) |
| `cannot access kotlin.reflect.KClass` at compile | `kotlin-reflect` absent | S2.1; Java code selecting the `Class`-based overloads needs it |
| Context starts, typed path variable returns **500** | No `Essentials*WebConfigurer` `@Import` | S4 — the dependency alone is inert |
| Every persistence serializer throws `IllegalStateException` at startup | A leftover Jackson 2 Essentials module (`types-jackson` / `immutable-jackson`) on the classpath | S3.1 — remove it |

**A failure here is a defect in this command or in the contract — fix it there, not only in the
generated project.** If a dependency was needed that S2.1 does not list, say so explicitly in the
Step 14 report and treat it as a contract bug to file; silently patching the POM is how S2.1 came
to be missing in the first place.

**Do not leave a broken project behind.** If the context will not start after applying the table
above, stop, report exactly what failed with the command output, and say which requirement you
believe is unmet. A half-working scaffold reported as success costs more than an honest failure.

Record the outcome as `SMOKE_RESULT` (`passed`, `passed-with-fixes`, or `failed`) — Step 14 reports
it, and `passed-with-fixes` must name each fix.

## Step 14 — Final report

Report to the user:

```
Created Essentials project at: ${TARGET_DIR}

Configuration:
  Stack:           ${STACK}
  Skeleton:        ${SKELETON_ACTUAL}   # initializr | local
  Smoke build:     ${SMOKE_RESULT}      # passed | passed-with-fixes | failed
  Language:        ${LANGUAGE}   # both fully supported
  DB profile:      ${DB_PROFILE}
  Docker Compose:  ${DOCKER_COMPOSE}
  Project name:    ${displayName}
  groupId:         ${groupId}
  artifactId:      ${artifactId}
  Package:         ${PACKAGE_PATH}
  Essentials:      ${ESSENTIALS_VERSION}   # stamped into CLAUDE.md; /essentials:upgrade reads it

Next steps:
  cd ${TARGET_DIR}
  ./dev.sh             # full dev stack (backend + frontend, if one was scaffolded)
  # or:
  ./dev.sh backend     # backend only on :8080 (auto-starts DB if Docker Compose enabled)
  ./dev.sh frontend    # frontend on :5173, proxies /api/* to :8080  [frontend only]

For framework documentation while developing, the project CLAUDE.md tells Claude to consult
the essentials-docs skill. Just ask Essentials questions normally and Claude will load it.

Slice rules are active: .claude/rules/essentials-slices.md loads every session (directory
vocabulary, the four slice kinds, the boundary rule). The full law lives in the plugin.

Add your first slice:
  /essentials:add-slice              # elicits kind, bounded context, and names
  /essentials:slice-check            # audit slices against the law

Roadmap items in this plugin (not yet shipped):
  - Anti-pattern hook
  - Kotlin aggregate lane — deliberate, not a backlog item (AggregateRoot is a Java-native family)
```

If `STACK == "Backend only"` or `DOCKER_COMPOSE == false`, omit the irrelevant lines from the "Next steps" block.

If `CLAUDE_MD_MODE == "merge"` or `README_MD_MODE == "merge"`, append a one-line note: "Merged Essentials sections into existing `<file>` — review for any `<!-- ESSENTIALS-INIT: … -->` markers."

If the `<!-- essentials-init: essentials … -->` stamp did not land (the `skip` path, or a merge that
dropped it), say so in one line — `/essentials:upgrade` will then report the project as unstamped and
fall back to checking artifacts rather than versions. That is a degraded report, not a broken one.

If Step 0 detected an existing Essentials project and the user chose to scaffold a second one
anyway, say so explicitly: "The existing Essentials project at `<path>` was **not** modified. Run
`/essentials:upgrade` from its directory to bring it up to what the installed plugin ships."

If `CLAUDE_MD_MODE == "skip"`, append the framework-knowledge block (quoted in Step 13) and tell the user to paste it into their existing `CLAUDE.md` so the `essentials-docs` skill can auto-load.

If `TARGET_MODE == "new subdirectory"`, append a line about the workspace pointer:

- `WORKSPACE_CLAUDE_MD_MODE == "none"` or `"overwrite"`: "Wrote workspace pointer at `${WORKSPACE_DIR}/CLAUDE.md` so planner and implementer sessions launched from the workspace root know the project lives at `${artifactId}/` and that stack pins are authoritative in `${artifactId}/backend/pom.xml`."
- `WORKSPACE_CLAUDE_MD_MODE == "merge"`: "Merged the Essentials workspace-pointer block into `${WORKSPACE_DIR}/CLAUDE.md` — review the new `## Trustworks Essentials project pointer` section."
- `WORKSPACE_CLAUDE_MD_MODE == "skip"`: warn loudly — "Skipped writing `${WORKSPACE_DIR}/CLAUDE.md`. Downstream planners and implementers launched at this workspace will have no signal that the project lives at `${artifactId}/` and may scaffold files at the wrong root, or invent their own stack pins. Recommended: paste the pointer block (quoted below) into your existing workspace `CLAUDE.md` manually." Then quote the rendered pointer block from Step 13.5.

## Error handling

- If the target directory exists and is non-empty (Step 7), abort with a clear "directory <X> already exists and is non-empty; remove it or pick a different artifactId" message — do **not** overwrite.
- If an `${CLAUDE_PLUGIN_ROOT}/references/init-assets/` file is missing, or a rendered document still contains an unsubstituted `{{...}}` placeholder, fail loudly and name the file — do not silently continue.
- If Spring Initializr is unreachable, fall through to Step 8b and **say so** — never silently, and never retry in a loop. Never invent a Boot version on either path; both read `stack-pins.md`.
- If a pin is needed that `stack-pins.md` does not list, stop and ask. Do not guess a version.
- If the Step 13.7 smoke build fails and the S2.1 table does not explain it, **stop and report** —
  do not improvise dependencies until the context happens to start. An unexplained fix is a contract
  gap that needs filing, and a scaffold patched into working by guesswork teaches the user a wiring
  this plugin cannot support.
- If `${CLAUDE_PLUGIN_ROOT}` is unset (the command was invoked outside Claude Code's plugin runner), abort with: "This command must be invoked from within Claude Code with the essentials plugin installed."
