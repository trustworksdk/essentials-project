# Essentials

Java 17+ building blocks for strongly-typed, event-sourced distributed systems.
Multi-module Maven. GroupId: `dk.trustworks.essentials` / `dk.trustworks.essentials.components`.

## LLM Docs

Consumer-facing module docs: `LLM/LLM.md` (entry point), `LLM/LLM-*.md` (per-module).
Read before suggesting APIs — don't guess from class names.
Each module has own `CLAUDE.md` with contributor/dev context.

## Commands

```bash
mvn test                                              # unit only, no Docker
mvn verify                                            # unit + integration (needs Docker)
mvn test -pl types -am                                # single module, unit
mvn verify -pl components/postgresql-event-store -am  # single module, integration
mvn clean install                                     # full build
mvn clean install -DskipDependencyCheck=true          # skip OWASP check
mvn clean install -P test-release                     # simulated release
```

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
  vaadin-ui/                     # Admin views
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
- **Stable central APIs** — breaking changes only in new major; always additive in patch/minor
