# Essentials 0.6x roadmap

The open fixes and improvements planned for the 0.6x line. Every item here was checked against the code on
2026-10-08 (0.60.0 is tagged `bcc17f35`), and only items that are still open or partly done are listed.

**How to use this file**

- The bucket names the earliest release an item may ship in. Project rule: breaking changes only in a new major;
  patch and minor releases are additive.
- The id in parentheses after each title (e.g. `ES-4`, `N-9`) is a stable label from the 0.60.0 maintainer review.
  Put it in the commit message that fixes the item, so the history can be searched by it.
- When an item ships, delete it here and add its entry to `docs/NEXT_VERSION_RELEASE.md` in the same commit. When an
  item is partly done, rewrite it to say only what remains.
- Each entry has three parts: the problem, why it matters, and the planned fix. Where the plan names an API, the name
  is a proposal, not a commitment.

**Scope of 0.60.1** (decided 2026-10-08): everything in section 0 and P1, plus three P2 items: ES-4
(`awaitProcessed`), DD-2 (the reserved-word check) and CB-4 (unhandled event types). Two P1 items ship softened,
because a patch must not reject a setup that works today:
- N-15's "the shard-owned builder requires a UnitOfWork factory" becomes a WARN.
- QL-3's redelivery-policy validation WARNs in 0.60.1 and fails from 0.61.

Added to 0.60.1 on 2026-10-08:
- **CB-4 per processor type:** only `ViewEventProcessor` WARNs about an ignored event type (with an
  `@IgnoresEventTypes` opt-out); `EventProcessor` and `InTransactionEventProcessor` log at DEBUG and count, because
  skipping is normal there. A view's handled and unhandled types are summarised at startup.
- **N-11 scope:** `essentials.immutable-jackson-module-enabled` defaults to `true` for the persistence mapper only;
  Boot's web mapper keeps the 0.60.0 behaviour (module only with an explicit `true`).
- **A startup report:** the Essentials startup findings are collected and printed as one block when the
  application is ready.

---

## 0. Planned for 0.60.0 but never shipped

All shipped in 0.60.1; see `docs/NEXT_VERSION_RELEASE.md`. One planned fix was rejected rather than shipped:
making objenesis a compile dependency of `immutable-jackson3` (N-5, SB-1). Every third-party dependency of a module
or starter stays `provided`, by design; the plugin's S2.1 table and its generated POM declare objenesis, and mapper
creation fails naming it when it is missing (SB-2).

---

## P1: 0.60.x patch releases

Bug fixes only; no API breaks. Everything planned here shipped in 0.60.1.

---

## P2: next 0.6x minor

Additive features.

### Turn the 0.60.1 warnings into failures

0.60.1 shipped two checks as a WARN, because a patch must not reject a setup that works today. In 0.61 they fail:
- **The shard-owned queue builder requires a UnitOfWork factory (N-15).** Without one, every enqueue commits on its
  own, outside the caller's transaction. 0.60.1 logs a WARN at build time.
- **Redelivery-policy builders reject an incomplete policy (QL-3).** Unset delays, a multiplier below 1.0 or unset,
  and a `maximumNumberOfRedeliveries` never set explicitly. 0.60.1 logs one WARN naming each setter; 0.61 throws
  `IllegalArgumentException`.
- **A command type registered twice fails at construction (CB-3).** 0.60.1 WARNs when an
  `EventStreamDeciderAndAggregateTypeConfigurator` registers a command type that another of its adapters already
  handles. A complete check needs a way to list a `CommandBus`'s handlers, e.g. a
  `findCommandHandlersCapableOfHandling(Class)` default method; today the configurator tracks its own adapters per
  bus.

### Event store

**A cross-instance `awaitProcessed` (ES-4 follow-up).** 0.60.1's `awaitProcessed` sees only the processor in this
JVM: a `ViewEventProcessor` whose fenced lock another instance holds times out. **Fix:** fall back to the persisted
resume point and the shared durable queue when the lock is held elsewhere, so a caller on any instance can wait.

**Typed event-order ranges (ES-1).**
- **Problem:** the event-store read APIs take an untyped `LongRange` for both per-stream `EventOrder` and cross-stream
  `GlobalEventOrder` ranges. Mixing them up compiles, and the result is a silent wrong read.
- **Fix:**
  - Add `EventOrderRange` and `GlobalEventOrderRange` to `foundation-types`, with typed overloads on `EventStore` and
    on the `FetchStream`/`LoadEventsByGlobalOrder` builders.
  - Mark the `LongRange` consumer entry points and `GlobalEventOrder.rangeFrom(...)` `@Deprecated`, not
    `forRemoval`.
  - The interceptor SPI stays on `LongRange` until the next major.
- Builds on the empty-range fix (`LongRange.isEmpty()`, shipped in 0.60.1).

**A state-based decider with snapshots (ES-9).** `EventStreamDeciderCommandHandlerAdapter` fetches and replays an
aggregate's whole event stream on every command and cannot use snapshots. Long-lived streams therefore get slower
with every event. `EventStreamDecider.handle(COMMAND, List<EVENT>)` receives raw history rather than folded state, so
a snapshot cannot simply be swapped in. **Fix:** add a state-based decider variant that decides over the `STATE`
folded by `EventStreamEvolver`, wired with an optional `AggregateSnapshotRepository`.

### Observability

**Subscription-lag health indicator and fenced-lock metrics (OB-1).** A subscription that has stalled produces no
health signal, and nothing reports fenced-lock ownership. A stalled projection or a lock nobody holds therefore
shows up only as stale data.

**Fix:**
- Add an opt-in `SubscriptionLagHealthIndicator`, modelled on `DurableQueuesHealthIndicator`, with thresholds unset
  by default. On an instance that does not own the subscription it reports UNKNOWN, never DOWN, because the current
  event order is local to an instance.
- Add Micrometer meters for fenced locks: held, acquired, released and lost, per lock name and per manager instance.

### Messaging

**Application LISTEN/NOTIFY channels (LN-1).**
- **Problem:** an application cannot use PostgreSQL LISTEN/NOTIFY for its own signals (for example, evicting caches
  across instances) without bending `MultiTableChangeListener`. That listener shares one poll thread, one connection
  and one duplicate filter with the framework's wake-ups.
- **Consequence:** one failing or slow application handler can drop or stall framework notifications.
- **Fix:** a separate foundation component, `PostgresqlNotificationChannels`, that owns one connection, optionally
  from a dedicated `DataSource`.
  - `subscribe(channel, Class<P>, Consumer<P>)`: dispatched off the poll thread and isolated per channel. Channel
    names are lower-cased with `Locale.ROOT`, and de-duplication happens only on request.
  - A static `publish(Handle|Connection, channel, Object)`: serializes with `JSONSerializer`, guards the 8000-byte
    payload limit, uses bind parameters and joins the caller's transaction, so delivery happens on commit.
  - Liveness checks with a WARN on connection loss, a `ListenerReconnected` signal, and opt-in health and metrics.
  - The Javadoc states that delivery is at most once, with no replay across a reconnect.
- Do not build it on `MultiTableChangeListener`. Reuse the LISTEN-connection loop it got in 0.60.1 (liveness probe,
  re-LISTEN on reconnect, close on stop) by extracting it.

### Fenced locks

**Exclusive acquire (QL-1).** Add `tryAcquireLockExclusively(LockName)`. It returns empty when this manager instance
already holds the lock, so a second caller in the same JVM cannot share, and then release, the first caller's lock.
Changing the default is deferred to the next major.

### Value types

**OpenAPI and AssertJ support (SJ-6).** Two problems:
- Single-value types (`CharSequenceType`, `NumberType`, ...) appear in generated OpenAPI schemas as objects instead
  of primitives.
- AssertJ's `assertThat(...)` is ambiguous for a `CharSequenceType`, which is both a `CharSequence` and a
  `Comparable`, so users need casts.

**Fix:**
- Promote the swagger-core `EssentialsValueTypeModelConverter` from `admin-api-spec` into a consumer module,
  `types-openapi`. It needs an exemption from the `ban-jackson-2` enforcer rule.
- Add a `types-assertj` module with an `EssentialsAssertions.assertThat(CharSequenceType)` entry point.

### Opt-ins

**Reject and commit on the UnitOfWork command interceptor (CB-1).** `UnitOfWorkControllingCommandBusInterceptor`
rolls back on any exception:
- A handler that rejects a command by throwing loses any work it meant to keep.
- Under `sendAndDontWait`, the rejection is redelivered by the default redelivery policy.

**Fix:** an opt-in set of exception types on which the interceptor commits and then rethrows. It can apply only
when the interceptor created the UnitOfWork, not when it joined an outer one.

**WARN on DML outside a transaction (QL-5).** A Jdbi write made while no UnitOfWork is active auto-commits on its
own. It is an easy mistake and gives no signal. **Fix:** an opt-in Jdbi `StatementCustomizer` in the Spring Boot
starter that WARNs once when a DML statement runs with no active transaction.

---

## P3: documentation

Edit `LLM/` only; `essentials-plugin/references/llm/` is generated from it.

### Event store and processors

- **Start position (ES-6).** Document the processor hooks `AbstractEventProcessor.isStartSubscriptionFromLatestEvent()`
  and `resolveStartSubscriptionFromGlobalEventOrder()` in `LLM-postgresql-event-store.md` and the view-slice skill.
  Say that a new subscriber starts at global order 1, and warn that a projection starting from the latest event
  never backfills its history.
- **Stable subscriber ids and split streams (ES-8).** Beside the subscriber-id docs, cover three points:
  - A subscriber id must be stable across restarts. A per-instance id leaves a dead durable-subscription row behind
    for each identity, and it does not give each instance every event.
  - Two instances on one database split a processor's events, and the code of the instance holding the lock decides
    the behaviour.
  - To see which instance holds each processor, use `GET /fenced-locks` (`lockedByLockManagerInstanceId`) and
    `GET /event-store/subscriptions`.

  (Container reuse is already documented.)
- **Handler failures (ES-3).** In the `EventProcessor`/`ViewEventProcessor` sections and the view-slice skill:
  - State the rule: a handler throws on a failure it cannot handle. Alert on
    `essentials.messaging.durable_queues.dead_lettered` or `DurableQueuesHealthIndicator`, fix the cause, then call
    `resurrectDeadLetterMessage`. Never swallow the failure.
  - Say that the resume point keeps advancing, and that only that aggregate's key is blocked behind its dead letter.
- **Testing asynchronous projections (ES-5).** Add a section to `LLM-postgresql-event-store.md` and the view-slice
  skill:
  - Await the last event you appended, not an earlier one.
  - Await the seam the code under test actually reads (an aggregate fold, an async view, a cache).
  - An existence check that must not lag, such as an idempotency probe, asks the write side (a repository's `findX`)
    rather than a view.
  - Until the ES-4 barrier exists, a test awaits with a polling assertion.
- **Snapshot support per pattern (ES-9).** Add snapshot support to the pattern-selection table in
  `LLM-eventsourced-aggregates.md`. `EventStreamDecider` / `EventStreamDeciderCommandHandlerAdapter` loads the full
  stream on every command and has no `AggregateSnapshotRepository`. Snapshots reach only the stateful-aggregate
  repository and the typed-error `Decider`.
- **The three decider families (DOC-9).** In the `LLM.md` module table, say in one sentence that there are three
  decider families, which are not interchangeable and do not compose:
  - Java `aggregates.decider.Decider<C,E,ERR,S>`;
  - Java `EventStreamDecider`/`EventStreamEvolver`;
  - Kotlin `Decider<C,E>`/`Evolver`.

  Drop the "Java equiv" wording in `LLM-kotlin-eventsourcing.md`.

### Command bus

- **Rejections (CB-1).** In `LLM-reactive.md` and `LLM-eventsourced-aggregates.md`, document what a throw from a
  command handler does:
  - It rolls back the handler's own writes, because `UnitOfWorkControllingCommandBusInterceptor` wraps
    `send`/`sendAsync`/`sendAndDontWait` in `withUnitOfWork`.
  - When the handler joined an outer UnitOfWork, the throw marks that UnitOfWork rollback-only, even if the caller
    catches the exception.
  - Under `sendAndDontWait`, a rejection is redelivered.

  A business rejection that must persist returns an outcome instead. A decider emits a rejection event, which
  `send()` returns.
- **Wrapped exceptions (CB-2).** Next to `send` in `LLM-reactive.md`:
  - The original exception arrives wrapped in `UnitOfWorkException`, one level per enclosing UnitOfWork, so
    `catch (DomainException e)` never matches.
  - Use `Exceptions.doesStackTraceContainExceptionOfType(e, X.class)` instead, or
    `MessageDeliveryErrorHandler.builder().stopRedeliveryOn(...)` for queues.

### Fenced locks and the unit of work

- **Lock predicates (QL-2).** Per-instance ownership (QL-1) is documented; what remains is the two predicates.
  - `FencedLockManager.isLockedByThisLockManagerInstance(LockName)` looks the owner id up in the database and ignores
    the fence token.
  - `FencedLock.isLockedByThisLockManagerInstance()` reads the lock object's state and can be one confirmation
    interval stale.
- **Hand-opened handles (QL-5).** In `LLM-foundation.md`, document how a hand-opened handle behaves with the
  starter's `Jdbi`, which wraps a `TransactionAwareDataSourceProxy`:
  - inside an active UnitOfWork or Spring transaction, `jdbi.useHandle(...)` joins it;
  - with no UnitOfWork active, a write auto-commits on its own connection and gets none of the queue, inbox or
    outbox guarantees.
- **The UnitOfWork usage rule (N-15).** In `LLM-foundation.md` and `components/foundation/README.md`:
  - **State the rule.** Application code uses `unitOfWorkFactory.usingUnitOfWork(...)`/`withUnitOfWork(...)`, or
    Spring `@Transactional`. `getOrCreateNewUnitOfWork()`, `getRequiredUnitOfWork()` and a UnitOfWork's own
    `start()`/`commit()`/`rollback()` are framework-internal. `getCurrentUnitOfWork()` is a read-only probe ("am I
    in a transaction?"), never a way to decide whether to join one.
  - **Rewrite the examples.** Rewrite the examples that call `getRequiredUnitOfWork()`/`getCurrentUnitOfWork()` from
    ordinary code to use `withUnitOfWork(uow -> …)`. Keep the README's "Internal Mechanism" sections, but label them
    as how the framework works, not as API to call.
  - **Correct the stale statement.** Both files claim that `DurableQueues` calls `getRequiredUnitOfWork()`
    internally. Since `TransactionalMode` was removed, queue operations go through `withUnitOfWork`: they join an
    active UnitOfWork or Spring transaction, or open their own.

### Serialization and the document DB

- **The constructor rule on the document-db page (SJ-2).** Copy the Jackson 3 constructor rule into
  `LLM-postgresql-document-db.md`:
  - Any multi-argument constructor is an implicit properties-based creator. A parameter that is not a stored
    property therefore reads as null, while writes succeed.
  - Put `@JsonCreator` on the persistence constructor, or `@JsonIgnore` on the convenience constructor.
  - `ConstructorDetector.EXPLICIT_ONLY` does not help.
- **Omitted primitives in request bodies (SJ-4).** Under Jackson 3, a request body that omits a primitive record
  component fails with a "Failed to read HTTP message" that names no field, before validation runs. The fix is
  `spring.jackson.deserialization.fail-on-null-for-primitives=false` on Boot's web mapper, which Essentials does not
  configure.
- **Test clients (SJ-5).** A hand-built `WebTestClient.bindToServer()` uses default codecs without the Essentials
  Jackson type module, so value types serialize differently from the application. Bind the test client to the
  application context instead.
- **Projection rows and versions (DD-4, DD-5).** In `LLM-postgresql-document-db.md`:
  - Warn that deleting a projection row also deletes its stored version, so a redelivered older event re-creates
    the row. Use a soft delete or keep a tombstone version where redelivery matters.
  - Add one paragraph explaining that `version`/`lastUpdated` are the `VersionedEntity` interface's property names,
    and that a `JavaVersionedEntity` may rename its backing fields.
  - Say that `save` overwrites the initial version value, and that a `version`/`lastUpdated` that cannot be mutated
    fails at startup.

### Reactive stack

- **WebFlux (SB-3).** Document that the PostgreSQL and MongoDB starters are blocking. Under WebFlux, wrap the whole
  UnitOfWork in one `Mono.fromCallable(...).subscribeOn(Schedulers.boundedElastic())`, because UnitOfWork and Spring
  transactions are bound to a thread. Recommend WebMvc with virtual threads as the default stack.

### Doc errors (N-12)

- `LLM-reactive.md` lists a `SendCommandException(Object, Throwable)` constructor. The real class is abstract with
  `(Class<?>, String[, Exception])` constructors.
- The fenced-lock examples call the protected `lockManager.releaseLock(lock)`. Use `lock.release()` or
  try-with-resources instead (`FencedLock` is `AutoCloseable`). The examples are in `LLM-foundation.md` and in both
  fenced-lock module docs.
- The `@return` Javadoc of `FencedLockManager.isLockAcquiredByAnotherLockManagerInstance` was copied from the
  method above it and says the opposite of what the method returns.

---

## P4: `essentials` plugin

- **S2.1's objenesis row when the pins move to 0.60.1.** `stack-contract.md` S2.1, the generated POM comment and the
  init goldens say a missing objenesis fails "at the first read", which is true for 0.60.0. From 0.60.1, mapper
  creation fails at startup naming the dependency (SB-2). Update the row with the `essentials.version` pin and
  regenerate the goldens.
- **Profile-aware project `CLAUDE.md` (DOC-2).** The templates are done. Two pieces remain:
  - Scope init's "say which write styles the project can use" to the chat reply, and make it aware of the DB
    profile (no event-sourced lanes on `pg-crud` or `mongo`).
  - Add an `/essentials:upgrade` check that flags statements in a project's `CLAUDE.md` that contradict the detected
    DB profile or backend language.
- **Projection test patterns (DOC-4).** In the view-slice skill and the `__View__ProjectionIT` templates, which are
  still all TODOs, prescribe two patterns:
  - **(a)** Call the projection's `@MessageHandler` directly with a synthetic `OrderedMessage`. This is also the only
    way to test that the projection survives redelivery.
  - **(c)** Make the fixture text unique per run, for example with a UUID suffix, so count assertions survive the
    history that accumulates.

  Reference the start-position hooks. Do not prescribe pattern (b), seeding the version above any real order.
- **Dispatch-only command slices (DOC-5).** In `slice-yaml.schema.json`, require `handles` plus
  `anyOf[{required:[writes]},{required:[dispatches]}]` for `kind: command`, with no new kind, following the
  automation precedent. Also:
  - add a gate 4(c) lint hint pointing at `dispatches`;
  - add one sentence in `rules/slice-design.md` that sanctions a command slice which validates a request and
    dispatches another context's command.
- **Read-seam interfaces in the provider's `types/` (DOC-7).** Extend the sanctioned cross-slice edit in
  `rules/slice-design.md` to cover one more case: a consumer adds a declaration-only read-seam interface to the
  provider context's `types/`. The provider slice then implements it and declares `provides:`. Do not sanction a
  port local to the consumer.
- **A symptom index in `LLM-traps.md` (DOC-8).** The 0.60 refresh is done. Add a second index keyed by symptom,
  linking to the existing ESS ids:
  - the projection stopped;
  - reads fail but writes worked;
  - the context will not start;
  - a field is null after replay;
  - it works on one instance only.

  Give each trap an "open" or "fixed in 0.x" status.
- **Automation template name (DOC-10).** Rename the Java automation template `__Slice__Repository.java` to
  `__Slice__RepositoryConfiguration.java`, as already done for the view template. State once in `stack-contract.md`
  that every `@Configuration` class name ends in `Configuration`.
- **Stream/event-type cross-check (DOC-11, N-13).** Make gate 1(c) in `slice-lint.py` report both sides of the
  conflict:
  - name the manifest whose `from` lists the name, as well as the one that declares it in `aggregateTypes`;
  - flag any `from` value that matches a known stream (any `aggregateTypes` or `writes` name), so the error is
    caught even when no manifest declares `aggregateTypes`.
- **A workaround register in init.** Have `/essentials:init` scaffold a register convention:
  - one file, and every workaround in the code carries a one-line pointer to its entry with the condition for
    deleting it;
  - a unit test that fails when the pinned Essentials version changes, prompting someone to walk the register;
  - optionally, reflection "canary" tests asserting that each worked-around behaviour still exists.

---

## Next major

- **Typed ranges, step two (ES-1).** Once the typed-range overloads have shipped deprecated-only in a minor, mark
  the `LongRange` `EventStore` overloads `@Deprecated(forRemoval = true)`. Also move the interceptor SPI operation
  objects (`FetchStream`, `LoadEventsByGlobalOrder`, ...) to the typed ranges.
- **Exclusive lock acquire by default (QL-1).** Consider making `tryAcquireLock` exclusive by default once
  `tryAcquireLockExclusively` exists. A caller that re-acquires a lock it already holds would then spin, so the
  change needs a migration note.
- **Rethrow the original exception from the UnitOfWork interceptor (CB-2).** Commands sent through
  `UnitOfWorkControllingCommandBusInterceptor` surface failures wrapped in `UnitOfWorkException`. Consider rethrowing
  the original exception after the rollback instead. That changes the thrown type for existing callers.
