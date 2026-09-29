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

**Every line has an id.** `ESS-NNN`, with an anchor of the same name in lower case (`LLM-traps.md#ess-017`), is the
stable name anything citing a trap uses — a review finding, a migration note, a startup check's message. Ids are
handed out in sequence and never reused: a new trap takes the next unused number wherever its line sits, and a trap
that goes away keeps its id as a tombstone under [Retired ids](#retired-ids), so an old citation still lands on a
line that says what replaced it. `scripts/check-ess-ids.py` in the Essentials repository enforces this and checks
that every `ESS-NNN` cited there exists. Two more namespaces belong to the Essentials Claude Code plugin and keep
the number they have where they are defined, never renumbered: `ESS-S<n>` for a stack-contract requirement
(`ESS-S2.1`) and `ESS-G<gate><clause>` for a slice-check gate (`ESS-G4b`).

---

## Universal

- <a id="ess-001"></a>`ESS-001` Table/column/collection names are string-concatenated into SQL/NoSQL; the name check is a first pass, not sanitising → [LLM-components.md § Security](LLM-components.md#security)
- <a id="ess-002"></a>`ESS-002` A handler missing `@MessageHandler`/`@EventHandler` is never called — silently in processors and aggregates, dead-lettered on a pattern-matching queue handler → [LLM-postgresql-event-store.md § Handlers without their annotation](LLM-postgresql-event-store.md#handlers-without-their-annotation)
- <a id="ess-003"></a>`ESS-003` Projection not current when the API responds, or an Inbox `EventProcessor` used for a plain projection → [LLM-postgresql-event-store.md § Gotchas](LLM-postgresql-event-store.md#gotchas)
- <a id="ess-004"></a>`ESS-004` Durable queues, fenced locks or Inbox/Outbox used between services — they are intra-service only → [LLM.md § Intra-Service Scope](LLM.md#intra-service-scope)

---

## Core modules

### shared ([LLM-shared.md](LLM-shared.md))
- <a id="ess-005"></a>`ESS-005` Null check throws `IllegalArgumentException`, not `NullPointerException` (`FailFast`) → [LLM-shared.md § Gotchas](LLM-shared.md#gotchas)
- <a id="ess-006"></a>`ESS-006` `Either` built with both or neither side set throws → [LLM-shared.md § Gotchas](LLM-shared.md#gotchas)
- <a id="ess-007"></a>`ESS-007` `StopWatch` shared across threads gives wrong timings → [LLM-shared.md § Gotchas](LLM-shared.md#gotchas)
- <a id="ess-008"></a>`ESS-008` `.safe()` wrapper throws `CheckedExceptionRethrownException`, so catching the original checked type misses it → [LLM-shared.md § Gotchas](LLM-shared.md#gotchas)
- <a id="ess-009"></a>`ESS-009` `Reflector` re-created in a hot path wastes its reflection cache → [LLM-shared.md § Gotchas](LLM-shared.md#gotchas)

### types ([LLM-types.md](LLM-types.md))
- <a id="ess-010"></a>`ESS-010` `null` passed to any value-type constructor fails at construction → [LLM-types.md § Gotchas](LLM-types.md#gotchas)
- <a id="ess-011"></a>`ESS-011` Map-key/path-variable conversion fails on a type declaring both `of(String)` and `of(CharSequence)` → [LLM-types.md § Gotchas](LLM-types.md#gotchas)
- <a id="ess-012"></a>`ESS-012` `Money` arithmetic across currencies throws; `Percentage` keeps scale ≥ 2 → [LLM-types.md § Gotchas](LLM-types.md#gotchas)

### immutable ([LLM-immutable.md](LLM-immutable.md))
- <a id="ess-013"></a>`ESS-013` Stale cached `hashCode()`/`toString()` from non-`final` or mutable-typed fields → [LLM-immutable.md § Common Pitfalls](LLM-immutable.md#common-pitfalls)
- <a id="ess-014"></a>`ESS-014` Equality ignores identity after `@Exclude.EqualsAndHashCode` on an id field → [LLM-immutable.md § Common Pitfalls](LLM-immutable.md#common-pitfalls)
- <a id="ess-015"></a>`ESS-015` Invalid/null values accepted because `@Immutable` validates nothing → [LLM-immutable.md § Common Pitfalls](LLM-immutable.md#common-pitfalls)

### reactive ([LLM-reactive.md](LLM-reactive.md))
- <a id="ess-016"></a>`ESS-016` Commands lost on restart after `LocalCommandBus.sendAndDontWait()` → [LLM-reactive.md § Gotchas](LLM-reactive.md#gotchas)
- <a id="ess-017"></a>`ESS-017` `NoCommandHandlerFoundException` / `MultipleCommandHandlersFoundException` on send → [LLM-reactive.md § Gotchas](LLM-reactive.md#gotchas)
- <a id="ess-018"></a>`ESS-018` Sync handler exception rolls back the publisher; async one is never retried → [LLM-reactive.md § Gotchas](LLM-reactive.md#gotchas)
- <a id="ess-019"></a>`ESS-019` `@AsyncEventHandler` inert outside Spring, or firing on every `EventBus` bean → [LLM-reactive.md § Gotchas](LLM-reactive.md#gotchas)

---

## Type integrations

### types-jackson ([LLM-types-jackson.md](LLM-types-jackson.md))
- <a id="ess-020"></a>`ESS-020` `JSR310SingleValueType` subclass fails to deserialize without `@JsonCreator` → [LLM-types-jackson.md § JSR310SingleValueType](LLM-types-jackson.md#jsr310singlevaluetype)
- <a id="ess-021"></a>`ESS-021` Old `@JsonDeserialize(keyUsing=…)` silently ignored on value-type map keys → [LLM-types-jackson.md § Map Keys](LLM-types-jackson.md#map-keys)
- <a id="ess-022"></a>`ESS-022` `Money` is a JSON object, not a scalar → [LLM-types-jackson.md § Gotchas](LLM-types-jackson.md#gotchas)
- <a id="ess-023"></a>`ESS-023` `createObjectMapper()` ignores getters/setters (fields only) → [LLM-types-jackson.md § Gotchas](LLM-types-jackson.md#gotchas)
- <a id="ess-024"></a>`ESS-024` Kotlin value-class id written as `{"value":…}` without `KotlinModule`, no error → [LLM-types-jackson.md § Kotlin semantic types](LLM-types-jackson.md#kotlin-semantic-types)
- <a id="ess-025"></a>`ESS-025` Value type serialized unexpectedly in a `@RequestBody`, or a module "registered" but not applied — web and persistence mappers are registered independently → [LLM-types-jackson.md § Quick Facts](LLM-types-jackson.md#quick-facts)

### types-jdbi ([LLM-types-jdbi.md](LLM-types-jdbi.md))
- <a id="ess-026"></a>`ESS-026` Queries fail with only `ArgumentFactory` or only `ColumnMapper` registered → [LLM-types-jdbi.md § Gotchas](LLM-types-jdbi.md#gotchas)
- <a id="ess-027"></a>`ESS-027` `ColumnMapper` returns `null` for SQL NULL → [LLM-types-jdbi.md § Gotchas](LLM-types-jdbi.md#gotchas)

### types-avro ([LLM-types-avro.md](LLM-types-avro.md))
- <a id="ess-028"></a>`ESS-028` Codegen/runtime failure from an incomplete LogicalType/Factory/Conversion set or a missing plugin registration → [LLM-types-avro.md § Gotchas](LLM-types-avro.md#gotchas)
- <a id="ess-029"></a>`ESS-029` Temporal values come back in UTC with nanoseconds truncated → [LLM-types-avro.md § Gotchas](LLM-types-avro.md#gotchas)

### types-spring-web ([LLM-types-spring-web.md](LLM-types-spring-web.md))
- <a id="ess-030"></a>`ESS-030` Typed `@RequestBody`/`@ResponseBody` not converted — the converter covers path variables and request params only → [LLM-types-spring-web.md § Gotchas](LLM-types-spring-web.md#gotchas)
- <a id="ess-031"></a>`ESS-031` Typed `@PathVariable` answers 500 after adding the dependency — nothing is registered until a configurer is `@Import`ed → [LLM-types-spring-web.md § Configuration](LLM-types-spring-web.md#configuration)
- <a id="ess-032"></a>`ESS-032` Registering `KotlinValueTypeConverter` by hand — the configurers already do it when `kotlin-reflect` is present → [LLM-types-spring-web.md § Configuration](LLM-types-spring-web.md#configuration)
- <a id="ess-033"></a>`ESS-033` `ZonedDateTimeType` path variable fails to parse — the client must URL-encode it; region zone ids cannot be path variables → [LLM-types-spring-web.md § Gotchas](LLM-types-spring-web.md#gotchas)
- <a id="ess-034"></a>`ESS-034` Assuming a Kotlin value-class id needs this module — a `@JvmInline value class` binds with nothing from Essentials → [LLM-types-spring-web.md § Kotlin semantic types](LLM-types-spring-web.md#kotlin-semantic-types)
- <a id="ess-035"></a>`ESS-035` Invalid Kotlin value-class id answers 500 instead of 400 — its `init` guard fires at handler invocation → [LLM-types-spring-web.md § Validation runs — but watch the status code](LLM-types-spring-web.md#validation-runs--but-watch-the-status-code)
- <a id="ess-036"></a>`ESS-036` Expecting `StringValueType` to validate — it is a bare interface; validation lives in the concrete type → [LLM-types-spring-web.md § Validation runs — but watch the status code](LLM-types-spring-web.md#validation-runs--but-watch-the-status-code)

### types-springdata-mongo ([LLM-types-springdata-mongo.md](LLM-types-springdata-mongo.md))
- <a id="ess-037"></a>`ESS-037` `OffsetDateTimeType`/`ZonedDateTimeType` unsupported; temporals UTC without nanoseconds → [LLM-types-springdata-mongo.md § Gotchas](LLM-types-springdata-mongo.md#gotchas)
- <a id="ess-038"></a>`ESS-038` `ObjectId`-backed `CharSequenceType` map key fails without explicit converter → [LLM-types-springdata-mongo.md § Gotchas](LLM-types-springdata-mongo.md#gotchas)

### types-springdata-jpa ([LLM-types-springdata-jpa.md](LLM-types-springdata-jpa.md))
- <a id="ess-039"></a>`ESS-039` Module is experimental and may be discontinued → [LLM-types-springdata-jpa.md § Gotchas](LLM-types-springdata-jpa.md#gotchas)
- <a id="ess-040"></a>`ESS-040` `@Id` on a value type, missing ids, or out-of-sync duplicate id field → [LLM-types-springdata-jpa.md § Gotchas](LLM-types-springdata-jpa.md#gotchas)
- <a id="ess-041"></a>`ESS-041` `Amount`/`Percentage` read back with lost scale (`double precision` column) → [LLM-types-springdata-jpa.md § Gotchas](LLM-types-springdata-jpa.md#gotchas)

### immutable-jackson ([LLM-immutable-jackson.md](LLM-immutable-jackson.md))
- <a id="ess-042"></a>`ESS-042` Deserialized object skipped constructor validation and field defaults (Objenesis) → [LLM-immutable-jackson.md § Gotchas](LLM-immutable-jackson.md#gotchas)
- <a id="ess-043"></a>`ESS-043` Collections absent from JSON come back `null` → [LLM-immutable-jackson.md § Gotchas](LLM-immutable-jackson.md#gotchas)

---

## Component modules

### foundation-types ([LLM-foundation-types.md](LLM-foundation-types.md))
- <a id="ess-044"></a>`ESS-044` Off-by-one or wrong-scope ordering — `EventOrder` is 0-based per aggregate, `GlobalEventOrder` 1-based per type → [LLM-foundation-types.md § Gotchas](LLM-foundation-types.md#gotchas)
- <a id="ess-045"></a>`ESS-045` Events lost between consumers — a `SubscriberId` reused across consumers shares one resume point → [LLM-foundation-types.md § Gotchas](LLM-foundation-types.md#gotchas)
- <a id="ess-046"></a>`ESS-046` `EventTypeOrName` extraction throws — check `hasEventType()`/`hasEventName()` first → [LLM-foundation-types.md § Gotchas](LLM-foundation-types.md#gotchas)

### foundation ([LLM-foundation.md](LLM-foundation.md))
- <a id="ess-047"></a>`ESS-047` Inner exception rolls back the outer work — nested `UnitOfWork`s share one transaction → [LLM-foundation.md § Nested Transaction Behavior](LLM-foundation.md#nested-transaction-behavior)
- <a id="ess-048"></a>`ESS-048` Duplicate side effects from a queue handler — delivery is at-least-once, handlers must be idempotent → [LLM-foundation.md § Key Features](LLM-foundation.md#key-features)
- <a id="ess-049"></a>`ESS-049` Two instances both act as lock holder after a GC pause — validate the fence token downstream → [LLM-foundation.md § The Solution: Fence Tokens](LLM-foundation.md#the-solution-fence-tokens)
- <a id="ess-050"></a>`ESS-050` Lock expires before renewal — keep `lockConfirmationInterval` 2–3× smaller than `lockTimeOut` → [LLM-foundation.md § FencedLock (Distributed Locking)](LLM-foundation.md#fencedlock-distributed-locking)
- <a id="ess-051"></a>`ESS-051` Deserialized object has `null` fields or fails its own `requireNonNull` — constructor parameter name differs from the JSON property; shows on replay → [LLM-foundation.md § JSONSerializer](LLM-foundation.md#jsonserializer)
- <a id="ess-052"></a>`ESS-052` Replay fails or reads wrong values after persisting with a hand-built `ObjectMapper` — use `EssentialsObjectMappers` → [LLM-foundation.md § JSONSerializer](LLM-foundation.md#jsonserializer)
- <a id="ess-053"></a>`ESS-053` Queued `sendAndDontWait` commands fail after a deploy — the command is persisted JSON, a serialized contract → [LLM-foundation.md § Commands are persisted](LLM-foundation.md#commands-are-persisted)

### foundation-test ([LLM-foundation-test.md](LLM-foundation-test.md))
- <a id="ess-054"></a>`ESS-054` Queue/lock tests fail — the component was never `start()`ed → [LLM-foundation-test.md § Common Pitfalls](LLM-foundation-test.md#common-pitfalls)
- <a id="ess-055"></a>`ESS-055` Unrelated ITs fail at random — a disruption test pauses a container other tests share → [LLM-foundation-test.md § Common Pitfalls](LLM-foundation-test.md#common-pitfalls)

### postgresql-event-store ([LLM-postgresql-event-store.md](LLM-postgresql-event-store.md))
- <a id="ess-056"></a>`ESS-056` Concurrent writers silently interleave — `appendToStream` without an expected `EventOrder` skips optimistic concurrency → [LLM-postgresql-event-store.md § Gotchas](LLM-postgresql-event-store.md#gotchas)
- <a id="ess-057"></a>`ESS-057` Every instance handles the same events — a non-exclusive async subscription runs on each node → [LLM-postgresql-event-store.md § Gotchas](LLM-postgresql-event-store.md#gotchas)
- <a id="ess-058"></a>`ESS-058` An event a projection never saw, one ERROR line and no retry — direct async subscribers skip a failing event by default (`SubscriptionErrorPolicy`) → [LLM-postgresql-event-store.md § Direct async subscribers skip a failing event by default](LLM-postgresql-event-store.md#direct-async-subscribers-skip-a-failing-event-by-default)

### eventsourced-aggregates ([LLM-eventsourced-aggregates.md](LLM-eventsourced-aggregates.md))
- <a id="ess-059"></a>`ESS-059` Emails/calls repeat on every load — `@EventHandler`s are replayed on rehydration; keep them pure → [LLM-eventsourced-aggregates.md § Event Handlers](LLM-eventsourced-aggregates.md#event-handlers)
- <a id="ess-060"></a>`ESS-060` Event-sourcing semantics break — events with setters are mutable → [LLM-eventsourced-aggregates.md § Event Design](LLM-eventsourced-aggregates.md#event-design)
- <a id="ess-061"></a>`ESS-061` An `EventStreamDecider` cannot emit two events — model the intent as one event → [LLM-eventsourced-aggregates.md § EventStreamDecider (Functional)](LLM-eventsourced-aggregates.md#eventstreamdecider-functional)
- <a id="ess-062"></a>`ESS-062` `NoActiveUnitOfWorkException` on save — no `UnitOfWork` or Spring transaction around it → [LLM-eventsourced-aggregates.md § Aggregate Creation](LLM-eventsourced-aggregates.md#aggregate-creation)
- <a id="ess-063"></a>`ESS-063` `@AggregateSnapshotPolicy`/`@AggregateClosingBooksPolicy` do nothing and admin lifecycle endpoints report nothing — aggregate not declared → [LLM-eventsourced-aggregates.md § Declaring Aggregates](LLM-eventsourced-aggregates.md#declaring-aggregates)

### spring-postgresql-event-store ([LLM-spring-postgresql-event-store.md](LLM-spring-postgresql-event-store.md))
- <a id="ess-064"></a>`ESS-064` JDBI writes ignore the Spring transaction — wrap the `DataSource` in `TransactionAwareDataSourceProxy` → [LLM-spring-postgresql-event-store.md § Gotchas](LLM-spring-postgresql-event-store.md#gotchas)
- <a id="ess-065"></a>`ESS-065` Unexpected nested transaction — `usingUnitOfWork()` inside `@Transactional` → [LLM-spring-postgresql-event-store.md § Gotchas](LLM-spring-postgresql-event-store.md#gotchas)
- <a id="ess-066"></a>`ESS-066` Failure in `afterCommit()` only logged, nothing rolled back — the transaction already committed → [LLM-spring-postgresql-event-store.md § Gotchas](LLM-spring-postgresql-event-store.md#gotchas)

### kotlin-eventsourcing ([LLM-kotlin-eventsourcing.md](LLM-kotlin-eventsourcing.md))
- <a id="ess-067"></a>`ESS-067` `NullPointerException` from `Evolver.applyEvents` for an aggregate with no events yet → [LLM-kotlin-eventsourcing.md § Gotchas](LLM-kotlin-eventsourcing.md#gotchas)
- <a id="ess-068"></a>`ESS-068` Kotlin `commandBus.send(cmd) as OrderEvent?` fails to compile ("cannot infer type for type parameter 'R'") — give `send` an expected type or explicit type arguments → [LLM-kotlin-eventsourcing.md § `CommandBus.send()` needs an expected type in Kotlin](LLM-kotlin-eventsourcing.md#commandbussend-needs-an-expected-type-in-kotlin--a-cast-is-not-enough)
- <a id="ess-069"></a>`ESS-069` Event with a sealed-interface field fails to deserialize; the processor cannot get past it → [LLM-kotlin-eventsourcing.md § Gotchas](LLM-kotlin-eventsourcing.md#gotchas)
- <a id="ess-070"></a>`ESS-070` Subtype of a `sealed` command/event interface in another package fails to compile (Kotlin rule, not Essentials) — make the command root a plain `interface` → [LLM-kotlin-eventsourcing.md § Sealed command/event interfaces must live in one package](LLM-kotlin-eventsourcing.md#sealed-commandevent-interfaces-must-live-in-one-package)

### postgresql-queue ([LLM-postgresql-queue.md](LLM-postgresql-queue.md))
- <a id="ess-071"></a>`ESS-071` Idle queues keep hammering the database — optimizer without `MultiTableChangeListener`, or a 1 ms interval → [LLM-postgresql-queue.md § Gotchas](LLM-postgresql-queue.md#gotchas)

### postgresql-distributed-fenced-lock ([LLM-postgresql-distributed-fenced-lock.md](LLM-postgresql-distributed-fenced-lock.md))
- <a id="ess-072"></a>`ESS-072` Nodes fight over locks — same lock-manager instance ID on every node → [LLM-postgresql-distributed-fenced-lock.md § Gotchas](LLM-postgresql-distributed-fenced-lock.md#gotchas)
- <a id="ess-073"></a>`ESS-073` Lock never released after an exception → [LLM-postgresql-distributed-fenced-lock.md § Gotchas](LLM-postgresql-distributed-fenced-lock.md#gotchas)
- <a id="ess-074"></a>`ESS-074` Lock expires before it is confirmed — confirmation interval too close to the timeout → [LLM-postgresql-distributed-fenced-lock.md § Gotchas](LLM-postgresql-distributed-fenced-lock.md#gotchas)

### postgresql-document-db ([LLM-postgresql-document-db.md](LLM-postgresql-document-db.md))
- <a id="ess-075"></a>`ESS-075` Projection applies a redelivered event twice — handler lacks `OrderedMessage` or the version skip check → [LLM-postgresql-document-db.md § Event Projection Pattern](LLM-postgresql-document-db.md#event-projection-pattern)
- <a id="ess-076"></a>`ESS-076` Save/load fails at runtime for a semantic-type property — JDBI argument factory/column mapper missing → [LLM-postgresql-document-db.md § Gotchas](LLM-postgresql-document-db.md#gotchas)
- <a id="ess-077"></a>`ESS-077` `Order::address.city` does not work in a query — use `then` → [LLM-postgresql-document-db.md § Gotchas](LLM-postgresql-document-db.md#gotchas)
- <a id="ess-078"></a>`ESS-078` Optimistic locking breaks after setting `version` by hand → [LLM-postgresql-document-db.md § Gotchas](LLM-postgresql-document-db.md#gotchas)
- <a id="ess-079"></a>`ESS-079` `create(...)` rejects a Java `CharSequenceType` id; `Version.of(...)` does not exist → [LLM-postgresql-document-db.md § Gotchas](LLM-postgresql-document-db.md#gotchas)
- <a id="ess-080"></a>`ESS-080` Java entity's first `save`/`update`/`delete` throws `IllegalCallableAccessException` (projection never populates) — `@Id` field is not `public` → [LLM-postgresql-document-db.md § Java gotchas](LLM-postgresql-document-db.md#java-gotchas)
- <a id="ess-081"></a>`ESS-081` Numeric or date range query returns wrong rows — path string compared as text without `DbType` → [LLM-postgresql-document-db.md § Gotchas](LLM-postgresql-document-db.md#gotchas)

### springdata-mongo-queue ([LLM-springdata-mongo-queue.md](LLM-springdata-mongo-queue.md))
- <a id="ess-082"></a>`ESS-082` No transactions or Change Streams — MongoDB is standalone, not a replica set → [LLM-springdata-mongo-queue.md § Common Pitfalls](LLM-springdata-mongo-queue.md#common-pitfalls)
- <a id="ess-083"></a>`ESS-083` Queue only polls and `QueueEntryId`/`QueueName` fail to map — `start()` or converters missing → [LLM-springdata-mongo-queue.md § Common Pitfalls](LLM-springdata-mongo-queue.md#common-pitfalls)

### springdata-mongo-distributed-fenced-lock ([LLM-springdata-mongo-distributed-fenced-lock.md](LLM-springdata-mongo-distributed-fenced-lock.md))
- <a id="ess-084"></a>`ESS-084` `MongoTransactionException` on lock acquisition — standalone MongoDB → [LLM-springdata-mongo-distributed-fenced-lock.md § Gotchas](LLM-springdata-mongo-distributed-fenced-lock.md#gotchas)
- <a id="ess-085"></a>`ESS-085` Lock contention or leaked locks — shared instance IDs, no try-with-resources, confirmation near timeout → [LLM-springdata-mongo-distributed-fenced-lock.md § Gotchas](LLM-springdata-mongo-distributed-fenced-lock.md#gotchas)

---

## Spring Boot starters

### spring-boot-starter-modules ([LLM-spring-boot-starter-modules.md](LLM-spring-boot-starter-modules.md))
- <a id="ess-086"></a>`ESS-086` Customisation ignored — auto-configured beans back off only when you declare your own `@Bean` → [LLM-spring-boot-starter-modules.md § Bean Override](LLM-spring-boot-starter-modules.md#bean-override)
- <a id="ess-087"></a>`ESS-087` MongoDB `CharSequenceType` backed by `ObjectId` or used as a map key misbehaves → [LLM-spring-boot-starter-modules.md § MongoDB Custom Converters](LLM-spring-boot-starter-modules.md#mongodb-custom-converters)
- <a id="ess-088"></a>`ESS-088` `MongoTimeoutException` at startup, app talks to `localhost/test` — the Mongo connection keys (`spring.data.mongodb.uri`, `host`, `database`, credentials, `ssl.*`, …) are no longer bound; use `spring.mongodb.*` (and `spring.mongodb.representation.uuid` for `uuid-representation`); the other `spring.data.mongodb.*` keys still apply → [LLM-spring-boot-starter-modules.md § Gotchas](LLM-spring-boot-starter-modules.md#gotchas)
- <a id="ess-089"></a>`ESS-089` No handler found for any command, event handlers never called — `reactive-bean-post-processor-enabled=false` → [LLM-spring-boot-starter-modules.md § Gotchas](LLM-spring-boot-starter-modules.md#gotchas)

### admin-api ([LLM-admin-api.md](LLM-admin-api.md))
- <a id="ess-090"></a>`ESS-090` Admin UI and API answer `401`/`403` for everyone — both security SPIs default to no access → [LLM-admin-api.md § Security](LLM-admin-api.md#security)

---

## Upgrading

- <a id="ess-091"></a>`ESS-091` Coming from before 0.50: the 0.50 changes that start clean and compile clean → [MIGRATION-0.60.md § Coming from before 0.50](../docs/MIGRATION-0.60.md#coming-from-before-050)
- <a id="ess-092"></a>`ESS-092` Coming from 0.50: every 0.60 change → [MIGRATION-0.60.md](../docs/MIGRATION-0.60.md)

### 0.60 changes that compile and still bite ([MIGRATION-0.60.md](../docs/MIGRATION-0.60.md))
- <a id="ess-093"></a>`ESS-093` `UnsupportedClassVersionError` at startup — the runtime is older than Java 25 → [MIGRATION-0.60.md § Platform: Java 25, Spring Boot 4.1, Kotlin 2.3](../docs/MIGRATION-0.60.md#platform-java-25-spring-boot-41-kotlin-23)
- <a id="ess-094"></a>`ESS-094` `IllegalStateException` "… is not a Jackson 3 module" at startup — a 0.50 `types-jackson`/`immutable-jackson` jar is still on the classpath → [MIGRATION-0.60.md § Swap the Jackson modules](../docs/MIGRATION-0.60.md#swap-the-jackson-modules)
- <a id="ess-095"></a>`ESS-095` Persisted JSON changes shape or stops reading back — a Jackson module bean no longer reaches the starters' persistence mapper; define your own `JSONSerializer`/`JSONEventSerializer` → [MIGRATION-0.60.md § Spring Boot starters](../docs/MIGRATION-0.60.md#spring-boot-starters)
- <a id="ess-096"></a>`ESS-096` Upgrade is not zero-downtime on a large queue table — the first start drops superseded queue indexes during table initialisation → [MIGRATION-0.60.md § Two indexes are dropped on startup](../docs/MIGRATION-0.60.md#two-indexes-are-dropped-on-startup)
- <a id="ess-097"></a>`ESS-097` Queue statistics gone after the first start, nothing exported — the statistics trigger, function and table are dropped; the `enable-queue-statistics` properties are silently unbound → [MIGRATION-0.60.md § The queue statistics feature is removed](../docs/MIGRATION-0.60.md#the-queue-statistics-feature-is-removed)
- <a id="ess-098"></a>`ESS-098` A message whose exception type is listed in `alwaysRetryOn(...)` is now retried instead of dead-lettered on its first delivery → [MIGRATION-0.60.md § `alwaysRetryOn(...)` now works](../docs/MIGRATION-0.60.md#alwaysretryon-now-works)
- <a id="ess-099"></a>`ESS-099` A handler failure that retried now dead-letters on its first delivery — an `IllegalArgumentException`/`ClassCastException` anywhere in the cause chain counts → [MIGRATION-0.60.md § The whole cause chain is examined](../docs/MIGRATION-0.60.md#the-whole-cause-chain-is-examined)
- <a id="ess-100"></a>`ESS-100` Dead letters arrive later, and a test waiting a fixed time for one times out — redelivery backoff now grows, the framework defaults included → [MIGRATION-0.60.md § Redelivery delays now grow](../docs/MIGRATION-0.60.md#redelivery-delays-now-grow)
- <a id="ess-101"></a>`ESS-101` A dashboard reading `GET /durable-queues/queues/{queueName}/statistics` breaks or misreads — the body is now `ApiQueueStatistics`, per-instance counters and handler duration rather than delivery latency → [MIGRATION-0.60.md § Queue statistics are replaced, not restored](../docs/MIGRATION-0.60.md#queue-statistics-are-replaced-not-restored)
- <a id="ess-102"></a>`ESS-102` Handlers observe a different order — `QueueMessage.builder().setMessage(orderedMessage)` used to queue it unordered and now keeps its ordering → [MIGRATION-0.60.md § `QueueMessage.builder().setMessage(…)` no longer discards ordering](../docs/MIGRATION-0.60.md#queuemessagebuildersetmessage-no-longer-discards-ordering)
- <a id="ess-103"></a>`ESS-103` `essentials.durable-queues.transactional-mode=fully-transactional` silently ignored — every queue operation runs in its own transaction, so a handler's rollback no longer undoes the delivery attempt → [MIGRATION-0.60.md § `TransactionalMode` is retired](../docs/MIGRATION-0.60.md#transactionalmode-is-retired)
- <a id="ess-104"></a>`ESS-104` `IllegalArgumentException` from `new AppendToStream(type, id, Optional, List)` — the removed constructor's call now binds to the varargs one → [MIGRATION-0.60.md § The 0.50 `forRemoval` members are removed everywhere else](../docs/MIGRATION-0.60.md#the-050-forremoval-members-are-removed-everywhere-else)
- <a id="ess-105"></a>`ESS-105` A stream holds an `Optional` and an array as two events — written through `EventStore.appendToStream(…, Optional<Long>, Object...)` before 0.60 → [MIGRATION-0.60.md § Fixed: `EventStore.appendToStream(…, Optional<Long>, Object...)` appended the wrong events](../docs/MIGRATION-0.60.md#fixed-eventstoreappendtostream-optionallong-object-appended-the-wrong-events)
- <a id="ess-106"></a>`ESS-106` Startup fails under `ddl-auto=validate`, or amounts read back at a different scale, after opting into `AmountNumericAttributeConverter`/`PercentageNumericAttributeConverter` → [MIGRATION-0.60.md § `types-springdata-jpa`: exact `numeric` converters](../docs/MIGRATION-0.60.md#types-springdata-jpa-exact-numeric-converters-for-amount-and-percentage-opt-in)
- <a id="ess-107"></a>`ESS-107` `SchemaValidationException` when an `AggregateType` is registered at runtime under `essentials.schema.mode=validate` — its table is not in the emitted script → [MIGRATION-0.60.md § Opting in: `essentials.schema.mode`](../docs/MIGRATION-0.60.md#opting-in-essentialsschemamode)
- <a id="ess-108"></a>`ESS-108` DDL still runs outside `essentials.schema.mode=create`, failing for a user without DDL rights — your own `ClosingBooksSetup` bean or hand-built component was not given the `SchemaOwnership` → [MIGRATION-0.60.md § Your own `ClosingBooksSetup` bean](../docs/MIGRATION-0.60.md#your-own-closingbookssetup-bean)
- <a id="ess-109"></a>`ESS-109` Registering a shard-owned queue fails outside `create` mode — its sequences are created at runtime and need `CREATE` on the schema → [MIGRATION-0.60.md § The shard-owned engine needs the right to create sequences at runtime](../docs/MIGRATION-0.60.md#the-shard-owned-engine-needs-the-right-to-create-sequences-at-runtime)
- <a id="ess-110"></a>`ESS-110` `validate`/`emit` never see the event store's notify trigger — `enableNotifyTriggerInstallation(...)` runs its DDL beside the schema harness; use `enableNotifyTriggers(...)` → [MIGRATION-0.60.md § Deprecated: installing the event store's notify trigger yourself](../docs/MIGRATION-0.60.md#deprecated-installing-the-event-stores-notify-trigger-yourself)

---

## Retired ids

A retired id keeps its line here, anchor included, so a citation of it still resolves: the old symptom, then
"retired:", why, and the id that replaced it, if any. No id has been retired.
