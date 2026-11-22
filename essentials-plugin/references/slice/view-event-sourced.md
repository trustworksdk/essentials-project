# View slice — the event-sourced lanes

Read by `skills/essentials-view-slice/SKILL.md` Step 2 on the **decider** and **aggregate** lanes only.
A service-entity view has no projector, so none of this applies there and the skill never reads it.

The section numbers continue the skill's Step 2 (§2a and §2b stay in the skill, because they bind on
every lane), so a citation such as "§2f" means the same section wherever it appears.

## 2c. Processor selection

```
Must the read model be current the moment the command API returns?
├─ YES → InTransactionEventProcessor   (synchronous, in the same transaction as the append;
│                                       also the tool for cross-aggregate uniqueness)
└─ NO  → ViewEventProcessor            (async, low latency, eventually consistent, replayable)
```

A plain `EventProcessor` is **never** right for a view — that is the Inbox-backed base for external
integration. The template ships `ViewEventProcessor`, which is correct for almost every read model.

`ViewEventProcessor` takes **`ViewEventProcessorDependencies`**; `EventProcessor` and
`InTransactionEventProcessor` take `EventProcessorDependencies`. They are not interchangeable.

## 2d. Idempotency via `EventOrder`

The Inbox redelivers. A projector that is not idempotent will double-count.

The view carries a `version` equal to the projected event's `EventOrder`, and the update is
conditional on it:

- **Kotlin** — `repository.update(entity, Version(message.order))`. Use the constructor:
  **`Version.of()` does not exist.**
- **Java** — `repository.update(entity, message.getOrder())`, using the `long` overload so Java
  never constructs the Kotlin `Version` value class.

`repository.update(entity)` with no version is CRUD auto-increment, not projection. Skip an event
whose order is not newer than the stored version.

## 2e. Handler shape

Every `@MessageHandler` **in a view projection** takes `OrderedMessage` as its second parameter.

The reason is idempotency, not dispatch. `PatternMatchingMessageHandler` treats the second parameter
as **optional** — a single-argument `@MessageHandler` is invoked normally. But `message.order` /
`message.getOrder()` is the `EventOrder` a projection compares against the row's stored `version`,
and that comparison is the whole mechanism in 2d. Without the parameter the projection has no way to
recognise a replay, so redelivery double-applies. A handler that needs no ordering — a
fire-and-forget publisher in a translation slice, say — may legitimately omit it.

Do not report a missing `OrderedMessage` on an arbitrary `@MessageHandler` as a defect. Report it
where the handler mutates a versioned read model without it.

## 2f. Persistence

Both languages use **DocumentDB**. Kotlin entities implement `VersionedEntity`; Java entities extend
**`JavaVersionedEntity<ID, SELF>`**, which bridges the Kotlin `Version` value class to two primitive
`long` accessors, and use the `Class<T>` factory overloads (`createForStringId`) and the string-path
condition API (`eq("address.city", value)`, with an explicit `DbType` for numeric and temporal
paths).

A JDBI `@SqlObject` repository over a Flyway-managed table is the supported alternative in both
languages — choose it when you need hand-tuned SQL or the view must live in an existing relational
schema. No template ships for it: DocumentDB manages its own table and indexes, whereas JDBI means
writing the migration and the SQL yourself.

Java entity gotchas:

- **The `@Id` field must be `public`.** `EntityConfiguration` resolves `@Id` through Kotlin reflection
  over `memberProperties`; for a Java class the property is synthesised from the *field*, so reading
  it is a direct field access, not a call to the getter. A private `@Id` makes the first `save`/
  `update`/`delete` throw Kotlin's `IllegalCallableAccessException` (wrapping `IllegalAccessException`),
  the message is dead-lettered, and the projection silently never populates — nothing fails at startup. `version` and `lastUpdated` escape this because they are
  declared on `JavaVersionedEntity` as Kotlin properties with real getters, so their `KProperty1`
  getter is a method call. Kotlin-declared property → method call; Java-declared field → field read.
- Initialise the version field to `Version.NOT_SAVED_YET_VALUE` (`-1`), not `0`.
- `version` and `lastUpdated` are hardcoded names in the reflection layer, so keep them and keep them
  mutable.
- **Name the `@Configuration` class `<View>RepositoryConfiguration`, never `<View>Repository`.** Spring
  registers a `@Configuration` class's own bean under its decapitalised simple name, which would
  collide with the `<viewCamel>Repository()` `@Bean` method it declares — a
  `BeanDefinitionOverrideException` at context startup that fails *every* `@SpringBootTest` in the
  project, not just this slice. Keep the `@Bean` method name: that is what consumers inject by.
- **Declare `kotlin-stdlib-jdk8` and `kotlin-reflect` in the module's `pom.xml`.**
  `postgresql-document-db` declares both in `provided` scope, so they are not transitive, and a pure-Java
  consumer still needs them at compile time — `createForStringId` and the `Condition` DSL expose
  `KClass`/`KProperty1` overloads javac must resolve. Missing them fails with
  `cannot access kotlin.reflect.KClass`. Adding a view slice is what pulls this module in; the skill's
  Step 4 `requires` names whichever of the three the build file lacks.

## 2g. Start position

A new projector starts at global order 1 and replays the whole history — that is what a view needs, so leave
`isStartSubscriptionFromLatestEvent()` at its default `false` and do not override
`resolveStartSubscriptionFromGlobalEventOrder()`. A view that starts from the latest event never backfills: aggregates
whose events came before its first start never get a row. Either hook only applies on the first start under the
processor name; after that the stored resume point wins. See `references/llm/LLM-postgresql-event-store.md` § Start
position.

## 2h. Handler failures

A projection handler throws on a failure it cannot handle; never wrap it in a `try`/`catch` that logs and returns — the
event is then acknowledged and the row is missing for good. A throw queues the event for redelivery and, once the
redeliveries are used up, dead-letters it. The resume point keeps advancing, and only that aggregate's later events wait
behind the dead letter. Say in the report that the team alerts on `essentials.messaging.durable_queues.dead_lettered`
(or `DurableQueuesHealthIndicator`), fixes the cause and calls `resurrectDeadLetterMessage`. See
`references/llm/LLM-postgresql-event-store.md` § Handler failures.

## 2i. Testing the projection

When filling the projector IT's "await the projection" TODO, call `awaitProcessed` on the projector with the **last**
event the test appended (`appendToStream(...).lastEvent()`, or `AppendedEvents.recordDuring(...)` around the
`commandBus.send` - import `dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.AppendedEvents`; it
records every append, decider or repository), then read the row once. Do not poll the view under a guessed timeout. `awaitProcessed` sees only
the projector in this JVM; poll with an assertion and a deadline only for what it cannot see — a seam downstream of the
projector, or a projector whose lock another instance holds. An existence check that must not lag (an idempotency
probe) reads the write side, never this view. See `references/llm/LLM-postgresql-event-store.md` § Testing
asynchronous projections.
