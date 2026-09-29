# Stack pins

**The single place in `references/stack/` where a version number may appear.** `stack-contract.md`
and the language files state requirements without versions on purpose: a document that names a
version rots the day that version moves, and a rotting requirement is worse than no requirement.

**This file is authoritative.** `/essentials:init` reads this table, so a pin changed here is a pin
changed everywhere.

## Backend

| What | Pin | Notes |
|---|---|---|
| `spring-boot-starter-parent` | **4.1.1** | Essentials 0.60.0 requires Boot 4.1.x and is built and tested against 4.1.1; 4.0.x and 3.x unsupported (S1) |
| `java.version` | **25** | Baseline is **25** (S1) — Essentials class files are built at `--release 25` |
| `kotlin.version` | **2.4.10** | The compiler Essentials itself builds with. Essentials artifacts carry Kotlin 2.3 `@Metadata` (language and API level 2.3); the floor is Kotlin **2.3**, because an older compiler cannot target JVM 25, and the application's `jvmTarget` must be 25 to inline Essentials' `inline`/`reified` functions. **Load-bearing on Java projects too** — `postgresql-document-db` is Kotlin, so `kotlin-stdlib-jdk8`/`kotlin-reflect` are runtime/compile requirements with no Kotlin sources present (S2.1) |
| `essentials.version` | **0.60.0** | One property, every Essentials artifact (S1) |
| `springdoc.version` | 3.1.0 | `springdoc-openapi-starter-webflux-ui`; the WebMvc variant on a servlet stack |
| `spring-modulith.version` | 2.0.7 | BOM import |
| `testcontainers-bom.version` | 2.0.5 | 2.x artifact names: `testcontainers-postgresql`, `testcontainers-mongodb`, `testcontainers-kafka` (S10) |
| `jdbi3-bom.version` | 3.54.0 | BOM import |
| `mockito-bom.version` | 5.23.0 | BOM import |
| `objenesis.version` | 3.6 | |
| `awaitility.version` | 4.3.0 | `awaitility-kotlin` on Kotlin |
| `assertj.version` | 3.27.7 | |
| jqwik | 1.9.3 | `jqwik` + `jqwik-kotlin` on Kotlin; `jqwik` alone on Java (S10) |

### Build plugins

| Plugin | Pin | Role |
|---|---|---|
| `springdoc-openapi-maven-plugin` | 1.4 | Fetches `/v3/api-docs` to `contracts/openapi.json` (S7) |
| `frontend-maven-plugin` | 1.15.1 | Node install, `npm ci`, `orval`, `npm run build` — embedded mode only |
| Node (via frontend-maven-plugin) | v25.0.0 | Build-time only; not a runtime dependency |

## Frontend

| What | Pin | Notes |
|---|---|---|
| react / react-dom | 19.2.4 | |
| react-router-dom | 7.13.0 | Client-side routing — the reason both modes need a SPA fallback |
| @tanstack/react-query | 5.90.20 | The client Orval generates against |
| orval | 8.2.0 | Generates `src/shared/api/generated` + `model` from `contracts/openapi.json` |
| vite | 7.3.1 | |
| typescript | ~5.9.3 | |
| tailwindcss | 4.1.18 | With `@tailwindcss/postcss` |
| vitest | 4.0.18 | |
| eslint | 9.39.2 | With `typescript-eslint` 8.x |

## Which pins are load-bearing

Most of the table is ordinary dependency hygiene. Four entries are **constraints**, not choices:

1. **Spring Boot 4.1.x + Java 25+** — Essentials 0.60.0 does not run otherwise (S1).
2. **Kotlin 2.3 or newer, `jvmTarget` 25** — an older compiler cannot target JVM 25, which inlining
   Essentials' `inline`/`reified` functions requires of the calling code, **and a Java
   project on a Postgres profile still needs `kotlin-stdlib` at runtime** (S2.1). This is not a
   Kotlin-lane-only pin.
3. **Testcontainers 2.x** — the 1.x artifact coordinates no longer resolve (S10).
4. **One `essentials.version`** — mixed Essentials versions on a classpath is unsupported (S1).

Everything else may be bumped on its own cadence.

## Refreshing this file

Nothing mirrors this table, so there is nothing to diff it against — which makes the
discipline human rather than mechanical. Two rules:

- **How much the Spring Boot row carries depends on which skeleton path a project took.** On the
  Initializr path (`/essentials:init` Step 8a) the parent POM, the wrapper and the Boot version come
  from `start.spring.io`, and this row is only the *input* to that request plus a record of what
  Essentials requires. On the **local** path (Step 8b) there is no upstream to defer to: this row
  **is** the Boot version the generated project gets. Keep it accurate for the second case; bump it
  when Essentials' requirement moves.
- **Everything else is bumped deliberately**, with the load-bearing four above checked first.

When Essentials itself releases, the pins move as part of a documented upgrade — for these pins that
is the Essentials repository's `docs/MIGRATION-0.60.md` § Platform, routed from
`references/llm/LLM-traps.md` § Upgrading.
