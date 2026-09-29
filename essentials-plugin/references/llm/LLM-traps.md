# Traps index

A symptom-first index of the **footguns** in Trustworks Essentials: the things that compile, look right, and then
bite at runtime, in production, or during a replay. Each line names the symptom and links to the section of the
module doc that owns the full explanation and the fix — the module doc is the single source, next to the API it
concerns; this file only points at it.

**Before editing code that touches a module, scan its heading here.** The [Universal](#universal) entries apply to
every component module.

**Before you type code against a class named in any doc, check its source root.** A class shown in a snippet may
live under `src/test`, which means it is not on a consumer's classpath and is not API, however finished the snippet
looks. The same discipline applies one level up: a *dependency* is not a *registration*. Adding a module to your
`pom.xml` does nothing until something registers its converter or module — `types-spring-web`, for one, ships no
auto-configuration at all. Confirm the registration; don't infer it from the artifact being present.

---

## Universal

- Table/column/collection names are string-concatenated into SQL/NoSQL; the name check is a first pass, not sanitising → [LLM-components.md § Security](LLM-components.md#security)
- A handler missing `@MessageHandler`/`@EventHandler` is never called — silently in processors and aggregates, dead-lettered on a pattern-matching queue handler → [LLM-postgresql-event-store.md § Handlers without their annotation](LLM-postgresql-event-store.md#handlers-without-their-annotation)
- Projection not current when the API responds, or an Inbox `EventProcessor` used for a plain projection → [LLM-postgresql-event-store.md § Gotchas](LLM-postgresql-event-store.md#gotchas)
- Durable queues, fenced locks or Inbox/Outbox used between services — they are intra-service only → [LLM.md § Intra-Service Scope](LLM.md#intra-service-scope)

---

## Core modules

### shared ([LLM-shared.md](LLM-shared.md))
- Null check throws `IllegalArgumentException`, not `NullPointerException` (`FailFast`) → [LLM-shared.md § Gotchas](LLM-shared.md#gotchas)
- `Either` built with both or neither side set throws → [LLM-shared.md § Gotchas](LLM-shared.md#gotchas)
- `StopWatch` shared across threads gives wrong timings → [LLM-shared.md § Gotchas](LLM-shared.md#gotchas)
- `.safe()` wrapper throws `CheckedExceptionRethrownException`, so catching the original checked type misses it → [LLM-shared.md § Gotchas](LLM-shared.md#gotchas)
- `Reflector` re-created in a hot path wastes its reflection cache → [LLM-shared.md § Gotchas](LLM-shared.md#gotchas)

### types ([LLM-types.md](LLM-types.md))
- `null` passed to any value-type constructor fails at construction → [LLM-types.md § Gotchas](LLM-types.md#gotchas)
- Map-key/path-variable conversion fails on a type declaring both `of(String)` and `of(CharSequence)` → [LLM-types.md § Gotchas](LLM-types.md#gotchas)
- `Money` arithmetic across currencies throws; `Percentage` keeps scale ≥ 2 → [LLM-types.md § Gotchas](LLM-types.md#gotchas)

### immutable ([LLM-immutable.md](LLM-immutable.md))
- Stale cached `hashCode()`/`toString()` from non-`final` or mutable-typed fields → [LLM-immutable.md § Common Pitfalls](LLM-immutable.md#common-pitfalls)
- Equality ignores identity after `@Exclude.EqualsAndHashCode` on an id field → [LLM-immutable.md § Common Pitfalls](LLM-immutable.md#common-pitfalls)
- Invalid/null values accepted because `@Immutable` validates nothing → [LLM-immutable.md § Common Pitfalls](LLM-immutable.md#common-pitfalls)

### reactive ([LLM-reactive.md](LLM-reactive.md))
- Commands lost on restart after `LocalCommandBus.sendAndDontWait()` → [LLM-reactive.md § Gotchas](LLM-reactive.md#gotchas)
- `NoCommandHandlerFoundException` / `MultipleCommandHandlersFoundException` on send → [LLM-reactive.md § Gotchas](LLM-reactive.md#gotchas)
- Sync handler exception rolls back the publisher; async one is never retried → [LLM-reactive.md § Gotchas](LLM-reactive.md#gotchas)
- `@AsyncEventHandler` inert outside Spring, or firing on every `EventBus` bean → [LLM-reactive.md § Gotchas](LLM-reactive.md#gotchas)

---

## Type integrations

### types-jackson ([LLM-types-jackson.md](LLM-types-jackson.md))
- `JSR310SingleValueType` subclass fails to deserialize without `@JsonCreator` → [LLM-types-jackson.md § JSR310SingleValueType](LLM-types-jackson.md#jsr310singlevaluetype)
- Old `@JsonDeserialize(keyUsing=…)` silently ignored on value-type map keys → [LLM-types-jackson.md § Map Keys](LLM-types-jackson.md#map-keys)
- `Money` is a JSON object, not a scalar → [LLM-types-jackson.md § Gotchas](LLM-types-jackson.md#gotchas)
- `createObjectMapper()` ignores getters/setters (fields only) → [LLM-types-jackson.md § Gotchas](LLM-types-jackson.md#gotchas)
- Kotlin value-class id written as `{"value":…}` without `KotlinModule`, no error → [LLM-types-jackson.md § Kotlin semantic types](LLM-types-jackson.md#kotlin-semantic-types)
- Value type serialized unexpectedly in a `@RequestBody`, or a module "registered" but not applied — web and persistence mappers are registered independently → [LLM-types-jackson.md § Quick Facts](LLM-types-jackson.md#quick-facts)

### types-jdbi ([LLM-types-jdbi.md](LLM-types-jdbi.md))
- Queries fail with only `ArgumentFactory` or only `ColumnMapper` registered → [LLM-types-jdbi.md § Gotchas](LLM-types-jdbi.md#gotchas)
- `ColumnMapper` returns `null` for SQL NULL → [LLM-types-jdbi.md § Gotchas](LLM-types-jdbi.md#gotchas)

### types-avro ([LLM-types-avro.md](LLM-types-avro.md))
- Codegen/runtime failure from an incomplete LogicalType/Factory/Conversion set or a missing plugin registration → [LLM-types-avro.md § Gotchas](LLM-types-avro.md#gotchas)
- Temporal values come back in UTC with nanoseconds truncated → [LLM-types-avro.md § Gotchas](LLM-types-avro.md#gotchas)

### types-spring-web ([LLM-types-spring-web.md](LLM-types-spring-web.md))
- Typed `@RequestBody`/`@ResponseBody` not converted — the converter covers path variables and request params only → [LLM-types-spring-web.md § Gotchas](LLM-types-spring-web.md#gotchas)
- Typed `@PathVariable` answers 500 after adding the dependency — nothing is registered until a configurer is `@Import`ed → [LLM-types-spring-web.md § Configuration](LLM-types-spring-web.md#configuration)
- Registering `KotlinValueTypeConverter` by hand — the configurers already do it when `kotlin-reflect` is present → [LLM-types-spring-web.md § Configuration](LLM-types-spring-web.md#configuration)
- `ZonedDateTimeType` path variable fails to parse — the client must URL-encode it; region zone ids cannot be path variables → [LLM-types-spring-web.md § Gotchas](LLM-types-spring-web.md#gotchas)
- Assuming a Kotlin value-class id needs this module — a `@JvmInline value class` binds with nothing from Essentials → [LLM-types-spring-web.md § Kotlin semantic types](LLM-types-spring-web.md#kotlin-semantic-types)
- Invalid Kotlin value-class id answers 500 instead of 400 — its `init` guard fires at handler invocation → [LLM-types-spring-web.md § Validation runs — but watch the status code](LLM-types-spring-web.md#validation-runs--but-watch-the-status-code)
- Expecting `StringValueType` to validate — it is a bare interface; validation lives in the concrete type → [LLM-types-spring-web.md § Validation runs — but watch the status code](LLM-types-spring-web.md#validation-runs--but-watch-the-status-code)

### types-springdata-mongo ([LLM-types-springdata-mongo.md](LLM-types-springdata-mongo.md))
- `OffsetDateTimeType`/`ZonedDateTimeType` unsupported; temporals UTC without nanoseconds → [LLM-types-springdata-mongo.md § Gotchas](LLM-types-springdata-mongo.md#gotchas)
- `ObjectId`-backed `CharSequenceType` map key fails without explicit converter → [LLM-types-springdata-mongo.md § Gotchas](LLM-types-springdata-mongo.md#gotchas)

### types-springdata-jpa ([LLM-types-springdata-jpa.md](LLM-types-springdata-jpa.md))
- Module is experimental and may be discontinued → [LLM-types-springdata-jpa.md § Gotchas](LLM-types-springdata-jpa.md#gotchas)
- `@Id` on a value type, missing ids, or out-of-sync duplicate id field → [LLM-types-springdata-jpa.md § Gotchas](LLM-types-springdata-jpa.md#gotchas)
- `Amount`/`Percentage` read back with lost scale (`double precision` column) → [LLM-types-springdata-jpa.md § Gotchas](LLM-types-springdata-jpa.md#gotchas)

### immutable-jackson ([LLM-immutable-jackson.md](LLM-immutable-jackson.md))
- Deserialized object skipped constructor validation and field defaults (Objenesis) → [LLM-immutable-jackson.md § Gotchas](LLM-immutable-jackson.md#gotchas)
- Collections absent from JSON come back `null` → [LLM-immutable-jackson.md § Gotchas](LLM-immutable-jackson.md#gotchas)

---

## Component modules

### foundation-types ([LLM-foundation-types.md](LLM-foundation-types.md))
- Off-by-one or wrong-scope ordering — `EventOrder` is 0-based per aggregate, `GlobalEventOrder` 1-based per type → [LLM-foundation-types.md § Gotchas](LLM-foundation-types.md#gotchas)
- Events lost between consumers — a `SubscriberId` reused across consumers shares one resume point → [LLM-foundation-types.md § Gotchas](LLM-foundation-types.md#gotchas)
- `EventTypeOrName` extraction throws — check `hasEventType()`/`hasEventName()` first → [LLM-foundation-types.md § Gotchas](LLM-foundation-types.md#gotchas)

### foundation ([LLM-foundation.md](LLM-foundation.md))
- Inner exception rolls back the outer work — nested `UnitOfWork`s share one transaction → [LLM-foundation.md § Nested Transaction Behavior](LLM-foundation.md#nested-transaction-behavior)
- Duplicate side effects from a queue handler — delivery is at-least-once, handlers must be idempotent → [LLM-foundation.md § Key Features](LLM-foundation.md#key-features)
- Two instances both act as lock holder after a GC pause — validate the fence token downstream → [LLM-foundation.md § The Solution: Fence Tokens](LLM-foundation.md#the-solution-fence-tokens)
- Lock expires before renewal — keep `lockConfirmationInterval` 2–3× smaller than `lockTimeOut` → [LLM-foundation.md § FencedLock (Distributed Locking)](LLM-foundation.md#fencedlock-distributed-locking)
- Deserialized object has `null` fields or fails its own `requireNonNull` — constructor parameter name differs from the JSON property; shows on replay → [LLM-foundation.md § JSONSerializer](LLM-foundation.md#jsonserializer)
- Replay fails or reads wrong values after persisting with a hand-built `ObjectMapper` — use `EssentialsObjectMappers` → [LLM-foundation.md § JSONSerializer](LLM-foundation.md#jsonserializer)
- Queued `sendAndDontWait` commands fail after a deploy — the command is persisted JSON, a serialized contract → [LLM-foundation.md § Commands are persisted](LLM-foundation.md#commands-are-persisted)

### foundation-test ([LLM-foundation-test.md](LLM-foundation-test.md))
- Queue/lock tests fail — the component was never `start()`ed → [LLM-foundation-test.md § Common Pitfalls](LLM-foundation-test.md#common-pitfalls)
- Unrelated ITs fail at random — a disruption test pauses a container other tests share → [LLM-foundation-test.md § Common Pitfalls](LLM-foundation-test.md#common-pitfalls)

### postgresql-event-store ([LLM-postgresql-event-store.md](LLM-postgresql-event-store.md))
- Concurrent writers silently interleave — `appendToStream` without an expected `EventOrder` skips optimistic concurrency → [LLM-postgresql-event-store.md § Gotchas](LLM-postgresql-event-store.md#gotchas)
- Every instance handles the same events — a non-exclusive async subscription runs on each node → [LLM-postgresql-event-store.md § Gotchas](LLM-postgresql-event-store.md#gotchas)
- An event a projection never saw, one ERROR line and no retry — direct async subscribers skip a failing event by default (`SubscriptionErrorPolicy`) → [LLM-postgresql-event-store.md § Direct async subscribers skip a failing event by default](LLM-postgresql-event-store.md#direct-async-subscribers-skip-a-failing-event-by-default)

### eventsourced-aggregates ([LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md))
- Emails/calls repeat on every load — `@EventHandler`s are replayed on rehydration; keep them pure → [LLM-eventsourced-aggregates.md § Event Handlers](LLM-eventsourced-aggregates.md#event-handlers)
- Event-sourcing semantics break — events with setters are mutable → [LLM-eventsourced-aggregates.md § Event Design](LLM-eventsourced-aggregates.md#event-design)
- An `EventStreamDecider` cannot emit two events — model the intent as one event → [LLM-eventsourced-aggregates.md § EventStreamDecider (Functional)](LLM-eventsourced-aggregates.md#eventstreamdecider-functional)
- `NoActiveUnitOfWorkException` on save — no `UnitOfWork` or Spring transaction around it → [LLM-eventsourced-aggregates.md § Aggregate Creation](LLM-eventsourced-aggregates.md#aggregate-creation)
- `@AggregateSnapshotPolicy`/`@AggregateClosingBooksPolicy` do nothing and admin lifecycle endpoints report nothing — aggregate not declared → [LLM-eventsourced-aggregates.md § Declaring Aggregates](LLM-eventsourced-aggregates.md#declaring-aggregates)

### spring-postgresql-event-store ([LLM-spring-postgresql-event-store.md](LLM-spring-postgresql-event-store.md))
- JDBI writes ignore the Spring transaction — wrap the `DataSource` in `TransactionAwareDataSourceProxy` → [LLM-spring-postgresql-event-store.md § Gotchas](LLM-spring-postgresql-event-store.md#gotchas)
- Unexpected nested transaction — `usingUnitOfWork()` inside `@Transactional` → [LLM-spring-postgresql-event-store.md § Gotchas](LLM-spring-postgresql-event-store.md#gotchas)
- Failure in `afterCommit()` only logged, nothing rolled back — the transaction already committed → [LLM-spring-postgresql-event-store.md § Gotchas](LLM-spring-postgresql-event-store.md#gotchas)

### kotlin-eventsourcing ([LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md))
- `NullPointerException` from `Evolver.applyEvents` for an aggregate with no events yet → [LLM-kotlin-eventsourcing.md § Gotchas](LLM-kotlin-eventsourcing.md#gotchas)
- Kotlin `commandBus.send(cmd) as OrderEvent?` fails to compile ("cannot infer type for type parameter 'R'") — give `send` an expected type or explicit type arguments → [LLM-kotlin-eventsourcing.md § `CommandBus.send()` needs an expected type in Kotlin](LLM-kotlin-eventsourcing.md#commandbussend-needs-an-expected-type-in-kotlin--a-cast-is-not-enough)
- Event with a sealed-interface field fails to deserialize; the processor cannot get past it → [LLM-kotlin-eventsourcing.md § Gotchas](LLM-kotlin-eventsourcing.md#gotchas)
- Subtype of a `sealed` command/event interface in another package fails to compile (Kotlin rule, not Essentials) — make the command root a plain `interface` → [LLM-kotlin-eventsourcing.md § Sealed command/event interfaces must live in one package](LLM-kotlin-eventsourcing.md#sealed-commandevent-interfaces-must-live-in-one-package)

### postgresql-queue ([LLM-postgresql-queue.md](LLM-postgresql-queue.md))
- Idle queues keep hammering the database — optimizer without `MultiTableChangeListener`, or a 1 ms interval → [LLM-postgresql-queue.md § Gotchas](LLM-postgresql-queue.md#gotchas)

### postgresql-distributed-fenced-lock ([LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md))
- Nodes fight over locks — same lock-manager instance ID on every node → [LLM-postgresql-distributed-fenced-lock.md § Gotchas](LLM-postgresql-distributed-fenced-lock.md#gotchas)
- Lock never released after an exception → [LLM-postgresql-distributed-fenced-lock.md § Gotchas](LLM-postgresql-distributed-fenced-lock.md#gotchas)
- Lock expires before it is confirmed — confirmation interval too close to the timeout → [LLM-postgresql-distributed-fenced-lock.md § Gotchas](LLM-postgresql-distributed-fenced-lock.md#gotchas)

### postgresql-document-db ([LLM-postgresql-document-db.md](LLM-postgresql-document-db.md))
- Projection applies a redelivered event twice — handler lacks `OrderedMessage` or the version skip check → [LLM-postgresql-document-db.md § Event Projection Pattern](LLM-postgresql-document-db.md#event-projection-pattern)
- Save/load fails at runtime for a semantic-type property — JDBI argument factory/column mapper missing → [LLM-postgresql-document-db.md § Gotchas](LLM-postgresql-document-db.md#gotchas)
- `Order::address.city` does not work in a query — use `then` → [LLM-postgresql-document-db.md § Gotchas](LLM-postgresql-document-db.md#gotchas)
- Optimistic locking breaks after setting `version` by hand → [LLM-postgresql-document-db.md § Gotchas](LLM-postgresql-document-db.md#gotchas)
- `create(...)` rejects a Java `CharSequenceType` id; `Version.of(...)` does not exist → [LLM-postgresql-document-db.md § Gotchas](LLM-postgresql-document-db.md#gotchas)
- Java entity's first `save`/`update`/`delete` throws `IllegalCallableAccessException` (projection never populates) — `@Id` field is not `public` → [LLM-postgresql-document-db.md § Java gotchas](LLM-postgresql-document-db.md#java-gotchas)
- Numeric or date range query returns wrong rows — path string compared as text without `DbType` → [LLM-postgresql-document-db.md § Gotchas](LLM-postgresql-document-db.md#gotchas)

### springdata-mongo-queue ([LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md))
- No transactions or Change Streams — MongoDB is standalone, not a replica set → [LLM-springdata-mongo-queue.md § Common Pitfalls](LLM-springdata-mongo-queue.md#common-pitfalls)
- Queue only polls and `QueueEntryId`/`QueueName` fail to map — `start()` or converters missing → [LLM-springdata-mongo-queue.md § Common Pitfalls](LLM-springdata-mongo-queue.md#common-pitfalls)

### springdata-mongo-distributed-fenced-lock ([LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md))
- `MongoTransactionException` on lock acquisition — standalone MongoDB → [LLM-springdata-mongo-distributed-fenced-lock.md § Gotchas](LLM-springdata-mongo-distributed-fenced-lock.md#gotchas)
- Lock contention or leaked locks — shared instance IDs, no try-with-resources, confirmation near timeout → [LLM-springdata-mongo-distributed-fenced-lock.md § Gotchas](LLM-springdata-mongo-distributed-fenced-lock.md#gotchas)

---

## Spring Boot starters

### spring-boot-starter-modules ([LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md))
- Customisation ignored — auto-configured beans back off only when you declare your own `@Bean` → [LLM-spring-boot-starter-modules.md § Bean Override](LLM-spring-boot-starter-modules.md#bean-override)
- MongoDB `CharSequenceType` backed by `ObjectId` or used as a map key misbehaves → [LLM-spring-boot-starter-modules.md § MongoDB Custom Converters](LLM-spring-boot-starter-modules.md#mongodb-custom-converters)
- `MongoTimeoutException` at startup, app talks to `localhost/test` — `spring.data.mongodb.*` is no longer bound; use `spring.mongodb.*` → [LLM-spring-boot-starter-modules.md § Gotchas](LLM-spring-boot-starter-modules.md#gotchas)
- No handler found for any command, event handlers never called — `reactive-bean-post-processor-enabled=false` → [LLM-spring-boot-starter-modules.md § Gotchas](LLM-spring-boot-starter-modules.md#gotchas)

### admin-api ([LLM-admin-api.md](LLM-admin-api.md))
- Admin UI and API answer `401`/`403` for everyone — both security SPIs default to no access → [LLM-admin-api.md § Security](LLM-admin-api.md#security)

---

## Upgrading

- Coming from before 0.50: the 0.50 changes that start clean and compile clean → [MIGRATION-0.60.md § Coming from before 0.50](https://github.com/trustworksdk/essentials-project/blob/main/docs/MIGRATION-0.60.md#coming-from-before-050)
- Coming from 0.50: every 0.60 change → [MIGRATION-0.60.md](https://github.com/trustworksdk/essentials-project/blob/main/docs/MIGRATION-0.60.md)
