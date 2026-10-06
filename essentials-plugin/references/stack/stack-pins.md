# Stack pins

**The single place in `references/stack/` where a version number may appear.** `stack-contract.md`
and the language files state requirements without versions on purpose: a document that names a
version rots the day that version moves, and a rotting requirement is worse than no requirement.

**This file is authoritative.** `/essentials:init` renders a project from this table
(`scripts/init-render.py` reads every `{{pin:…}}` from it), so a pin changed here is a pin changed
in every project generated afterwards — and in every golden under `tests/golden/init/`, which
`init-render.py --update-golden` regenerates for review.

## Backend

| What | Pin | Notes |
|---|---|---|
| `spring-boot-starter-parent` | **4.1.1** | Essentials 0.60.0 requires Boot 4.1.x and is built and tested against 4.1.1; 4.0.x and 3.x unsupported (S1) |
| `java.version` | **25** | Baseline is **25** (S1) — Essentials class files are built at `--release 25` |
| `kotlin.version` | **2.4.20** | The compiler Essentials itself builds with. Essentials artifacts carry Kotlin 2.3 `@Metadata` (language and API level 2.3); the floor is Kotlin **2.3**, because an older compiler cannot target JVM 25, and the application's `jvmTarget` must be 25 to inline Essentials' `inline`/`reified` functions. **Load-bearing on Java projects too** — `postgresql-document-db` is Kotlin, so `kotlin-stdlib-jdk8`/`kotlin-reflect` are runtime/compile requirements with no Kotlin sources present (S2.1) |
| `essentials.version` | **0.60.0** | One property, every Essentials artifact (S1) |
| `springdoc.version` | 3.1.1 | `springdoc-openapi-starter-webflux-ui`; the WebMvc variant on a servlet stack. Essentials builds `types-spring-web`'s springdoc converter against the same release (root `pom.xml` `springdoc.version`) |
| `spring-modulith.version` | 2.0.7 | BOM import |
| `testcontainers-bom.version` | 2.0.5 | 2.x artifact names: `testcontainers-postgresql`, `testcontainers-mongodb`, `testcontainers-kafka` (S10) |
| `jdbi3-bom.version` | 3.55.0 | BOM import |
| `mockito-bom.version` | 5.23.0 | BOM import |
| `objenesis.version` | 3.6 | |
| `awaitility.version` | 4.3.0 | `awaitility-kotlin` on Kotlin |
| `assertj.version` | 3.27.7 | |
| jqwik | 1.9.3 | `jqwik` + `jqwik-kotlin` on Kotlin; `jqwik` alone on Java (S10) |
| PostgreSQL image | postgres:18.4 | Docker Compose and the Testcontainers base (`IntegrationTestBase`); the tag Essentials' own integration tests use |
| MongoDB image | mongo:8.2 | Docker Compose and the Testcontainers base, both run as a replica set |
| `mongodb.version` | 5.13.0 | The MongoDB Java driver, `mongo` profile only: set above Spring Boot's managed driver, as Essentials itself builds and tests against it, for CVE-2026-18710, CVE-2026-88032 and CVE-2026-88033 (S11 — Boot reads this property for its `mongodb-driver-bom` import) |

### Build plugins

| Plugin | Pin | Role |
|---|---|---|
| `frontend-maven-plugin` | 1.15.1 | Node install, `npm ci`, `orval`, `npm run build` — embedded mode only |
| Node (via frontend-maven-plugin) | v25.0.0 | Build-time only; not a runtime dependency |
| Maven | 3.9.16 | The version the generated Maven wrapper downloads |
| `maven-wrapper-plugin` | 3.3.4 | Generates `mvnw` (`only-script`) when `/essentials:init` runs |

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
| @vitejs/plugin-react | 5.1.2 | |
| @types/react | 19.2.10 | |
| @types/react-dom | 19.2.3 | |
| @tailwindcss/postcss | 4.1.18 | Same release as `tailwindcss` |
| typescript-eslint | 8.54.0 | |
| @eslint/js | 9.39.2 | Same release as `eslint` |
| eslint-plugin-react-hooks | 7.0.1 | |

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

The goldens under `tests/golden/init/` carry every pin a template uses, so a changed row shows up as
a golden diff; nothing tells you a row has gone stale — which keeps the discipline human rather than
mechanical. Three rules:

- **This table is what a generated project gets.** `/essentials:init` renders the project from it,
  so every row is the version a new project starts on — the Spring Boot parent, the Maven wrapper
  and the database images included. Bump the Spring Boot row when Essentials' requirement moves.
- **Everything else is bumped deliberately**, with the load-bearing four above checked first.
- **`mongodb.version` goes once Spring Boot manages that driver release or a newer one** — the same
  rule the Essentials root `pom.xml` states for its own `mongodb-driver-bom` entry. Until then a
  generated Mongo project would otherwise run Boot's older driver.

When Essentials itself releases, the pins move as part of a documented upgrade — for these pins that
is the Essentials repository's `docs/MIGRATION-0.60.md` § Platform, routed from
`references/llm/LLM-traps.md` § Upgrading.
