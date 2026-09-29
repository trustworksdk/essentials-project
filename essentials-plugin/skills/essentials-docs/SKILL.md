---
name: essentials-docs
description: >
  Trustworks Essentials Java/Kotlin framework knowledge. Use for questions or code touching
  `dk.trustworks.essentials.*` — event store, durable queues, fenced locks, event-sourced aggregates
  (StatefulAggregate, Decider, EventProcessor), unit-of-work, single-value types (CharSequenceType,
  NumberType), or `spring-boot-starter-postgresql`/`mongodb` artifacts.
user-invocable: false
---

# Trustworks Essentials — framework knowledge

This skill is the entry point for everything related to the **Trustworks Essentials** framework (`dk.trustworks.essentials.*`). Follow the steps below before answering a framework question or writing framework code. Do not guess Essentials APIs and do not rely on web search — Essentials has its own opinionated patterns that differ from typical Spring/JPA/Kafka idioms; the bundled docs are the source of truth.

All paths below are relative to `${CLAUDE_PLUGIN_ROOT}/`. The full LLM-tailored docs live under `${CLAUDE_PLUGIN_ROOT}/references/llm/`.

## Step 1 — Detect the project's primary backend language

Before applying design guidance, determine whether the project is **Kotlin** or **Java**:

| Detection signal | Conclusion |
|---|---|
| `.kt` / `.kts` files exist under `backend/src/` (or anywhere in the project) | Kotlin |
| `pom.xml` references `kotlin-maven-plugin` or `org.jetbrains.kotlin:kotlin-stdlib` | Kotlin |
| Only `.java` files exist; no Kotlin stdlib in `pom.xml` | Java |
| Mixed: both `.kt` and `.java` present | Pick the language of the **file currently being edited**; if no file is being edited, prefer Kotlin (the project is Kotlin-first) |

Use these commands when in doubt:

```bash
find . -type f \( -name '*.kt' -o -name '*.kts' \) -not -path '*/target/*' -not -path '*/build/*' | head -1
find . -type f -name '*.java' -not -path '*/target/*' -not -path '*/build/*' | head -1
grep -l 'kotlin-maven-plugin\|kotlin-stdlib' pom.xml backend/pom.xml 2>/dev/null
```

The detection table this plugin uses everywhere lives in
`references/slice/slice-authoring.md` §1 — read it there rather than re-deriving the signals.

The design guide is **language-neutral**: `references/design/essentials-design.md` shows Java and
Kotlin side by side wherever they differ. The language you detect still matters, because the two use
different event-sourcing APIs (`EventStreamDecider` in Java, `kotlin.eventsourcing.Decider` in
Kotlin) — see `rules/slice-design.md` §R5.

## Step 2 — Lookup workflow (index-first, search as fallback)

Two ways to find content. Try them in this order:

1. **Index-driven** (default): use the Module Index in this skill (below) to identify the right `LLM-<topic>.md` file, then open it with `Read`.
2. **Search fallback**: if the user's question doesn't obviously map to a single topic, use the bundled search script. It searches `references/llm/` and the design guide in `references/design/`:

   ```bash
   ${CLAUDE_PLUGIN_ROOT}/skills/essentials-docs/search.sh "<query>"
   ${CLAUDE_PLUGIN_ROOT}/skills/essentials-docs/search.sh -l "<query>"           # list-only
   ${CLAUDE_PLUGIN_ROOT}/skills/essentials-docs/search.sh -t foundation "<query>" # restrict to one topic
   ${CLAUDE_PLUGIN_ROOT}/skills/essentials-docs/search.sh -t design "<query>"     # the design guide only
   ```

   Then `Read` the matched file(s).

For a full overview of the docs, the master index is `references/llm/LLM.md` — open it directly when the user asks "what's in Essentials?" or wants a tour.

`LLM.md` indexes framework docs only; the design guide, the slice-design law and the stack contract are routed from this skill (see the Cross-cutting indexes list below), not from `LLM.md`.

## Step 3 — Quick Facts (cite when introducing the framework)

| Aspect | Value |
|---|---|
| **What** | Java building blocks for strongly-typed, framework-independent distributed systems (Java baseline: stack-contract S1) |
| **GroupIds** | `dk.trustworks.essentials` (core), `dk.trustworks.essentials.components` (components) |
| **License** | Apache 2.0 |
| **Spring Boot baseline** | 4.1.x — Jackson 3 + Jakarta EE 11. Spring Boot 4.0.x and 3.x are **not supported** (stack-contract S1; exact versions in `references/stack/stack-pins.md`) |
| **Philosophy** | Zero-dependency core; third-party integrations as `provided` scope |
| **Scope** | **Intra-service** coordination (multiple instances of the same service sharing one DB) — NOT cross-service messaging |

## Step 4 — Module Index (route to the right doc)

### Core (zero deps)

| Module | Topic | Doc |
|---|---|---|
| `shared` | Tuples, Collections, Reflection, Exceptions, FailFast | `LLM-shared.md` |
| `types` | `SingleValueType` pattern (`CharSequenceType`, `NumberType`, `JSR310SingleValueType`) | `LLM-types.md` |
| `immutable` | Immutable value objects | `LLM-immutable.md` |
| `reactive` | `EventBus`, `CommandBus` | `LLM-reactive.md` |

### Type integrations (`provided` scope)

| Module | Doc |
|---|---|
| `types-jackson3` | `LLM-types-jackson.md` |
| `types-jdbi` | `LLM-types-jdbi.md` |
| `types-avro` | `LLM-types-avro.md` |
| `types-spring-web` | `LLM-types-spring-web.md` |
| `types-springdata-mongo` | `LLM-types-springdata-mongo.md` |
| `types-springdata-jpa` | `LLM-types-springdata-jpa.md` |
| `immutable-jackson3` | `LLM-immutable-jackson.md` |
| Overview | `LLM-types-integrations.md` |

### Components

| Component | Doc |
|---|---|
| `foundation-types` (`CorrelationId`, `EventId`, `AggregateType`) | `LLM-foundation-types.md` |
| `foundation` (`FencedLock`, `DurableQueues`, `UnitOfWork`, `Inbox`/`Outbox`, `DurableLocalCommandBus`) | `LLM-foundation.md` |
| `foundation-test` (test utilities) | `LLM-foundation-test.md` |
| `postgresql-event-store` (`EventStore`, subscriptions, `EventProcessor`) | `LLM-postgresql-event-store.md` |
| `eventsourced-aggregates` (`StatefulAggregate`, `Aggregate`, `Decider`, repositories) | `LLM-eventsourced-aggregates.md` |
| `spring-postgresql-event-store` (Spring tx integration) | `LLM-spring-postgresql-event-store.md` |
| `postgresql-distributed-fenced-lock` | `LLM-postgresql-distributed-fenced-lock.md` |
| `postgresql-queue` | `LLM-postgresql-queue.md` |
| `postgresql-queue-shard-owned` (experimental shard-owned queue engine, not published) | `LLM-postgresql-queue-shard-owned.md` |
| `postgresql-document-db` | `LLM-postgresql-document-db.md` |
| `springdata-mongo-distributed-fenced-lock` | `LLM-springdata-mongo-distributed-fenced-lock.md` |
| `springdata-mongo-queue` | `LLM-springdata-mongo-queue.md` |
| `kotlin-eventsourcing` (Kotlin DSL) | `LLM-kotlin-eventsourcing.md` |
| `admin-api-spec` / `spring-boot-starter-admin-api` (HTTP admin + monitoring API) | `LLM-admin-api.md` |
| Components overview | `LLM-components.md` |

### Spring Boot starters

| Starter | Use case | Doc |
|---|---|---|
| `spring-boot-starter-postgresql` | Microservice + PG (CRUD) | `LLM-spring-boot-starter-modules.md` |
| `spring-boot-starter-postgresql-event-store` | Microservice + PG (event-sourced) | `LLM-spring-boot-starter-modules.md` |
| `spring-boot-starter-mongodb` | Microservice + Mongo | `LLM-spring-boot-starter-modules.md` |

> Note: the admin UI is `spring-boot-starter-admin-ui`, a Thymeleaf + vanilla-JS UI at
> `/essentials/admin`, built on the opt-in HTTP API in `spring-boot-starter-admin-api` — see
> `LLM-admin-api.md`. Both are deny-all until an `EssentialsAuthenticatedUser` **and** an
> `EssentialsSecurityProvider` are supplied.

### Cross-cutting indexes

- `LLM-types-index.md` — alphabetical lookup of every type by use case (great for "I have a CustomerId, how do I…?" questions).
- `LLM-traps.md` — **the traps index**: one line per footgun that compiles and looks right but bites at runtime, in production or during replay (injection surfaces, EventProcessor selection, fence-token staleness, silent unannotated handlers, etc.), grouped by module. Each line is a symptom and a link; the full explanation and the fix live in the module doc it links to. **Scan the relevant module's heading before editing code that touches that module, and follow the link** (see Step 6).
- `references/design/essentials-design.md` — **the design guide** (plugin-authored, language-neutral): the modelling that happens before a slice exists — aggregate boundaries and the noun trap, bounded contexts, event-processor selection in general form, uniqueness across aggregates, event design, error-handling policy, and the design anti-patterns. Framework facts it relies on link back into these docs.
- `rules/slice-design.md` — **the slice-design law**: the four slice kinds, the JVM directory vocabulary, R1–R5, sanctioned sharing, red flags. Structure lives here, not in the design guide.
- `references/slice/slice-model.md` — per-kind slice anatomy and the role → file-name mapping for both languages.
- `references/stack/stack-contract.md` — **the application stack contract**: what an Essentials application must provide for slice code to run at all, as numbered requirements **S1–S11** (baseline, module selection, the serialization contract, the typed edge, persistence, API/contract-first, frontend mode, security, testing, packaging). Cite by number; do not restate. Its language bindings are `references/stack/kotlin-spring-boot.md` and `references/stack/java-spring-boot.md`, the frontend modes are `references/stack/frontend-react.md`, and **every version number lives in `references/stack/stack-pins.md` and nowhere else**.

## Step 5 — Module Selection Guide (by use case)

| User goal | Recommend |
|---|---|
| Strongly-typed domain modeling | `types` + framework integrations → `LLM-types.md` |
| Event sourcing on Spring Boot + PG | `spring-boot-starter-postgresql-event-store` → `LLM-spring-boot-starter-modules.md` |
| Event sourcing without Spring Boot | `postgresql-event-store` + `eventsourced-aggregates` → `LLM-postgresql-event-store.md` |
| Microservice on PG (CRUD) | `spring-boot-starter-postgresql` |
| Microservice on Mongo | `spring-boot-starter-mongodb` |
| Wire up / review a whole application (deps, config, build) | `references/stack/stack-contract.md` (S1–S11) |
| "Context won't start" / `NoClassDefFoundError` / a bean cannot be deduced | `references/stack/stack-contract.md` **S2.1** first — the starters' framework dependencies are `provided` and non-transitive, so the JDBC starter, the driver, JDBI, and Kotlin stdlib/reflect are the application's to declare. None of them fails at compile time |
| "How do I do this in Java instead of Kotlin?" | `references/stack/java-spring-boot.md` — the bindings that differ |
| Attach a React/TypeScript frontend | `references/stack/frontend-react.md` — embedded vs standalone |
| "Which version of X?" | `references/stack/stack-pins.md` — never answer from memory |
| Distributed locking | `foundation` + DB-specific lock module |
| Message queues / Inbox / Outbox | `foundation` + DB-specific queue module |

## Step 6 — Proactive advisory (BEFORE editing Essentials code)

When Claude is **about to write or modify** code that uses Essentials types (importing `dk.trustworks.essentials.*` or referencing framework types from the description above), open both of these before writing:

- `Read` `references/design/essentials-design.md` — aggregate boundaries, event-processor selection (`InTransactionEventProcessor` vs `ViewEventProcessor`), uniqueness enforcement, error-handling policy, and the design anti-patterns. Language-neutral, with Java and Kotlin shown side by side.
- `Read` `rules/slice-design.md` — the slice-design law, if the change adds, moves, or restructures a slice.

**Apply** the guidance to what you write — don't just read it.

If the change is **structural** rather than a framework-API question — adding a capability, extending or amending an existing slice, changing a read model — hand it to the `essentials-change` skill, which is the routing path: it classifies the request, finds the owning slice from its manifest, and enters the scaffolding skills when a new slice is the answer. Do **not** end the turn by printing a command name and stopping; that is a dead end, not a route.

The explicit commands remain available and are the right answer when the user asks for one: `/essentials:add-slice` (or `/essentials:add-{command,view,automation,translation}-slice`) scaffolds a whole slice, `/essentials:slice-check` audits existing slices against the law, and `/essentials:slice-map` shows what a project contains and how its slices connect.

Then, for the **specific module(s)** the code touches (event store, queues, fenced locks, types-jackson/jdbi, etc.), scan the matching heading of `references/llm/LLM-traps.md` — one line per footgun most likely to slip through review (missing handler annotations, stale fence tokens, name-injection surfaces, `appendToStream()` without an expected `EventOrder`, …) — and follow each relevant link into the module doc, which holds the full text and the fix. The anti-pattern table below is the cross-module summary; the module docs are the deeper per-module detail.

After writing, do a **self-check pass** against the most common anti-patterns:

| Anti-pattern | Quick test |
|---|---|
| Aggregate without `@EventHandler` for each event type | Every applied event should have a corresponding `@EventHandler` method |
| User input passed directly to a table/column/index/queue/collection name | Names should be hardcoded constants OR validated via `PostgresqlUtil.checkIsValidTableOrColumnName` / `MongoUtil.checkIsValidCollectionName` |
| Missing fence token check on update operations under a `FencedLock` | Operations done while holding a lock should pass `lock.getCurrentToken()` to downstream writes that need stale-lock protection |
| Using `ViewEventProcessor` (async) when the read model must be current at the API response | Switch to `InTransactionEventProcessor` for synchronous read-model updates |
| Cross-service messaging built on `DurableQueues` / `Inbox`/`Outbox` | Essentials is intra-service only — recommend Kafka/RabbitMQ/HTTP for cross-service |
| Concrete `String` / `UUID` parameters where a `SingleValueType` exists | Replace with the typed wrapper to prevent argument swapping at compile time |
| `OrderedMessage` not used when ordering matters | Use `OrderedMessage` for queue messages that must be processed in sequence per key |
| Anti-corruption between Inbox/Outbox and consumer logic missing | Use `PatternMatchingMessageHandler` with `handleUnmatchedMessage` to avoid silent drops |

If you find a violation, surface it explicitly to the user with a one-line "Heads-up:" note and a pointer to the relevant section of the design guide or module doc. Don't silently rewrite — name the pattern, cite the doc.

**Re-scan this anti-pattern table row-by-row before reporting the change as complete.** The table only fires if you actually re-read it after writing — make this a deliberate post-write step, not a passive intent.

## Step 7 — Critical security reminder

Many Essentials components accept customizable table/column/index/queue/collection names that are **string-concatenated** into SQL/NoSQL — a classic injection surface. The framework provides only a *first-pass* naming convention check, **not** exhaustive sanitization. Always:

- Hardcode names where possible.
- For names from configuration, run them through `PostgresqlUtil.checkIsValidTableOrColumnName` / `MongoUtil.checkIsValidCollectionName` AND validate against an allow-list.
- Never accept names from API request bodies / URL params.

See the **Security** section of `LLM.md` for the full breakdown.

## Step 8 — Pointers (when appropriate)

Some user questions are better answered by a command or a decision than by the docs; mention them when relevant (don't auto-route — name the option):

| User intent | Route |
|---|---|
| "Should we use event sourcing here? CQRS? Which DB?" — architectural decision | Stop and raise it with the user; record the outcome as an ADR. The per-BC write style is `rules/slice-design.md` §R5 |
| "Bootstrap a new Essentials project from scratch" | `/essentials:init` |
| "What is in here / where is this implemented / how do these connect?" | `/essentials:slice-map` |

## Step 9 — When the docs aren't enough

If the user's question can't be answered from `references/llm/`:

1. Be explicit: "The Essentials docs in this plugin don't cover X."
2. Suggest options: read the Javadoc / module README in the consumer's project (under `target/dependency-sources/` or via `mvn dependency:sources`), check the upstream source on the user's clone of `essentials/`, or contact the Trustworks team.
3. **Do not** invent APIs or guess type signatures — Essentials' opinions diverge from typical Spring idioms in subtle ways.

---

**Doc sources**: every framework claim in this skill is sourced from `references/llm/LLM*.md` files. When the user wants the canonical text, open the cited file directly with `Read`.
