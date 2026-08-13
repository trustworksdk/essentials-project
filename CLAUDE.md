# Essentials

Java 17+ building blocks for strongly-typed, event-sourced distributed systems.
Multi-module Maven. GroupId: `dk.trustworks.essentials` / `dk.trustworks.essentials.components`.

## LLM Docs

Consumer-facing module docs: `LLM/LLM.md` (entry point), `LLM/LLM-*.md` (per-module).
Read before suggesting APIs — don't guess from class names.
Each module has own `CLAUDE.md` with contributor/dev context.

## Commands

```bash
mvn test                                              # unit only, no Docker (Jackson 3 flavor — the default)
mvn -Pjackson2 test                                   # same, against the Jackson 2 flavor
mvn verify                                            # unit + integration (needs Docker)
mvn test -pl types -am                                # single module, unit
mvn verify -pl components/postgresql-event-store -am  # single module, integration
mvn clean install                                     # full build
mvn clean install -DskipDependencyCheck=true          # skip OWASP check
mvn clean install -P test-release                     # simulated release
```

Integration-test speed knobs (ITs are ~99% of the build's wall clock):

```bash
mvn -T 1C verify                                      # parallel reactor; multiplies container concurrency with forkCount
mvn verify -Dfailsafe.forkCount=1                     # serialize ITs on a constrained machine / low Docker memory
mvn verify -Dfailsafe.forkCount=0.5C                  # more forks on a big machine — measure, don't assume
mvn verify -Dbenchmark.run=true                       # also run the opt-in latency/throughput suites (off by default)
mvn verify -Dloadtest.skip=true                       # skip the bulk-load suites (on by default) for a faster loop
scripts/test-timings.sh                               # rank test classes by elapsed time from the last run
scripts/test-timings.sh --csv > before.csv            # capture a baseline to diff against
```

`failsafe.forkCount` defaults to 2. Over-forking Docker makes things slower, not faster — use `scripts/test-timings.sh` to tune it rather than guessing.

## Module Layout

```
shared/                          # Tuples, collections, reflection, exceptions — zero deps
types/                           # SingleValueType pattern
immutable/                       # Immutable value objects
reactive/                        # EventBus, CommandBus
types-{jackson,jackson3}/        # Jackson/Jackson3 serialization for types
types-{jdbi,avro}/               # JDBI and Avro integration for types
types-{spring-web,springdata-jpa,springdata-mongo}/
immutable-{jackson,jackson3}/    # Jackson/Jackson3 deserialization for immutables
components/
  foundation-types/              # CorrelationId, EventId, AggregateType, …
  foundation/                    # UnitOfWork, FencedLock, DurableQueues, Inbox/Outbox
  foundation-test/               # Internal test utilities
  jdbc-queue-base/               # JDBC queue abstraction — shared by postgresql-queue + mssql-queue
  mssql-queue/
  postgresql-event-store/        # EventStore, subscriptions, EventProcessor, CDC
  eventsourced-aggregates/       # StatefulAggregate, Aggregate, Decider, Repository
  spring-postgresql-event-store/ # Spring TX integration for EventStore
  postgresql-{distributed-fenced-lock,queue,document-db}/
  springdata-mongo-{distributed-fenced-lock,queue}/
  kotlin-eventsourcing/          # Kotlin DSL for event sourcing
  admin-api-spec/                # Code-first OpenAPI contract for the admin *Api SPIs
  admin-api-client-java/         # Java client generated from that contract
  spring-boot-starter-admin-api/ # HTTP adapter serving the contract
  spring-boot-starter-admin-ui/  # Optional default UI — Thymeleaf + vanilla JS, no Node
  spring-boot-starter-*/         # Auto-configuration starters
examples/                        # Demo projects — not part of release
LLM/                             # Consumer-facing LLM doc tree
```

## Rules (`.claude/rules/`)

Topic rules, path-scoped so they load only when relevant:

| File | Loads when editing | Covers |
|------|--------------------|--------|
| `code-style.md` | `**/*.{java,kt}` | License header, formatter, imports, guards, builders, semantic types |
| `testing.md` | `*Test.*` / `*IT.*` | Unit-vs-IT suffix, JUnit5/AssertJ/Awaitility, Testcontainers, base ITs, fixtures, naming |
| `maintaining-claude-md.md` | `**/CLAUDE.md` | When/what/how to update CLAUDE.md files |

## Critical Gotchas

- **`provided` scope** — all third-party integrations NOT transitive; consumers declare own deps
- **Intra-service only** — FencedLock/Queues/Inbox/Outbox for same-service multi-instance; not cross-service
- **SQL/NoSQL injection** — table/collection names string-concatenated into queries; validate via `PostgresqlUtil.checkIsValidTableOrColumnName()` / `MongoUtil.checkIsValidCollectionName()`; prefer hardcoded names
- **EventOrder vs GlobalEventOrder** — per-stream vs across all streams of an AggregateType; don't conflate
- **No timestamp ordering** — event ordering via EventOrder/GlobalEventOrder, never timestamps
- **Docker required for integration tests** — `mvn test` runs without Docker; `mvn verify` needs Docker (TestContainers)
- **The flavor profile does not survive transitivity** — a profile only overrides `essentials.types-jackson.artifactId` for modules in the *current reactor*. Installed POMs keep the property unresolved (`flattenMode=resolveCiFriendliesOnly`) and it then resolves from the Essentials parent's **default**, now Jackson 3. So the non-default flavor is the exposed one: `mvn -Pjackson2 -pl <module>` can put **both** flavors on the classpath (same FQCNs) when a sibling comes from the local repo instead of the reactor. Add `-am`, or verify with the full reactor. `EssentialsJacksonModules` fails loudly on the mismatch — believe it rather than the profile
- **Jackson-flavor-neutral test wiring** — tests must build serializers via `EssentialsObjectMappers.createJSONSerializer()` / `EssentialsJSONEventSerializers.createForActiveJacksonFlavor()`. Hardcoding `new JacksonJSONSerializer(...)` makes the test silently exercise Jackson 2, and under `-Pjackson3` it either throws the flavor-mismatch error or persists value types as `{"value":"…"}`
- **Stable central APIs** — breaking changes only in new major; always additive in patch/minor
- **No Node / JavaScript build deps** — the whole build runs on a JVM alone. Any UI work uses Thymeleaf + vanilla JS; no npm, bundler, or JS framework
- **Two Jackson flavors, one wire format** — a build picks Jackson 3 (default, matching Spring Boot 4) or Jackson 2 (`-Pjackson2`) via `essentials.types-jackson.artifactId`; `types-jackson`/`types-jackson3` share FQCNs so only one is ever on the classpath. All persistence mappers must come from `EssentialsObjectMappers` so both majors write byte-identical JSON — existing persisted data must stay readable after an upgrade. CDC included. Touching serialization means running both profiles
- **Jackson 3 needs two per-type pins** — it disabled final-field mutation (Jackson 2's default), which is how immutable payloads get populated, so `EssentialsObjectMappers` re-enables it. That in turn makes a type that *is* a collection or scalar wrapper look like a bean, so those are pinned to delegating creators: `Jackson3CollectionWrapperModule` (foundation, by shape) and `SingleValueTypeCreatorIntrospector` (types-jackson3). Never do it with annotations on the Essentials types themselves
- **Under Jackson 3 a constructor parameter *name* is part of the JSON contract.** J3 reads parameter names from the bytecode and uses any constructor as an implicit properties-based creator — even when a no-arg constructor exists. The J2 mapper registers no parameter-names module, so it never did this and populated fields instead. A parameter whose name does not match the JSON property it ends up in therefore receives `null`, and the class either fails its own `requireNonNull` guard or comes back half-populated. Two shapes bite: a parameter named differently from the field it assigns (`priceValidity` → field `priceValidityPeriod`), and a parameter that is not a property at all because the value is routed elsewhere (classic `Event<ID>` subclasses taking `orderId` and calling `aggregateId(...)`, which persists as `aggregateId`). Fix on the type — rename the parameter, or `@JsonProperty("…")` (that annotation package is shared by both majors). `ConstructorDetector.EXPLICIT_ONLY` does **not** avoid it: with no other way to construct, J3 uses the sole constructor regardless
- **Map keys keyed by a value type need no annotation under Jackson 3** — `types-jackson3` registers `SingleValueTypeKeyDeserializers`. Under Jackson 2 they need `@JsonDeserialize(keyUsing=…)`, and that annotation is in J2's `com.fasterxml.jackson.databind.annotation` package which J3 does not read — so on upgrade it silently stops applying. It surfaced as aggregate snapshots deserializing into `BrokenSnapshot`
- **Admin surface = one contract** — an admin operation lives in 3 synced places: the `*Api` SPI, the `EssentialsAdminApiSpec` mapping table, and a controller in `spring-boot-starter-admin-api`

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
