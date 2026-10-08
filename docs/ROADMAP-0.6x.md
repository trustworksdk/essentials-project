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
- 0.2's "builder requires a UnitOfWork factory" becomes a WARN.
- QL-3's redelivery-policy validation WARNs in 0.60.1 and fails from 0.61.

---

## 0. Planned for 0.60.0 but never shipped

These were meant to block the 0.60.0 tag and are not in it. The two Jackson 3 persistence fixes (`is`-getters,
`FAIL_ON_NULL_FOR_PRIMITIVES`) are fixed for the next release; see `docs/NEXT_VERSION_RELEASE.md` §1.1.2 and §3.
What remains of them is 0.1.

### 0.1 A startup check for mappers Essentials did not build (N-1, SJ-3)

Every mapper Essentials builds now reproduces the 0.50 wire format, and regression tests pin the `is`-getter and
missing-primitive cases. The remaining risk is a mapper Essentials did not build. The `JSONSerializer` and
`JSONEventSerializer` beans are `@ConditionalOnMissingBean`, and `Jackson3JSONSerializer(ObjectMapper)` accepts any
mapper, so a hand-built one can drift without any signal.

**Fix:**
- Add `EssentialsObjectMappers.findWireFormatDeviations(ObjectMapper)` in foundation. It checks behaviour, not
  configuration: it round-trips a small probe type and compares the result with the expected 0.50 bytes. The probe
  covers `is`-getter and getter visibility, `FAIL_ON_NULL_FOR_PRIMITIVES` and the pinned temporal features.
- The starter runs it against every `JSONSerializer`/`JSONEventSerializer` bean backed by a `Jackson3JSONSerializer`
  and logs one ERROR banner per bean that deviates. It does not check Boot's web mapper.
- Control it with `essentials.json.wire-format-check=warn|fail|off`, default `warn`. Consider `fail` as the default
  in the next major.

### 0.2 The shard-owned queue engine does not join a Spring transaction on enqueue (N-15, RN-2)

`ShardOwnedDurableQueues.currentTransactionConnection()` joins only an Essentials `UnitOfWork` that is already bound.
Under a plain Spring `@Transactional` or `TransactionTemplate`, nothing is bound. `Outbox.sendMessage`,
`sendAndDontWait` and `queueMessage` then commit on their own connection, and a rollback of the business transaction
does not undo the enqueue. On this engine the transactional outbox is not transactional. 0.60.0 did not ship the
known-limitation note the plan called for either.

**Fix:**
- Run the enqueue inside `withUnitOfWork`, as `PostgresqlDurableQueues` does, so it joins the Spring transaction. Do
  not call the manual `getOrCreateNewUnitOfWork()`/`getRequiredUnitOfWork()` methods.
- Make the builder require a UnitOfWork factory.

### 0.3 Removed properties are silently ignored (N-18, RN-6)

The 0.60.0 release notes document the removed properties, but nothing warns when an application still sets one. The
only `additional-spring-configuration-metadata.json` is in the event-store starter, and it has one warning-level
entry. No starter checks for removed `essentials.*` keys at startup. This matters most for
`essentials.*.transactional-mode=fully-transactional`: it is now ignored, which changes transaction semantics
without any signal.

**Fix:**
- Add `additional-spring-configuration-metadata.json` entries with error-level deprecations for every removed
  property.
- Add a startup check that logs an ERROR for any removed `essentials.*` key that is still set.

### 0.4 The documentation still makes ordering and retry claims that are wrong (RN-4, RN-3, RN-5)

`components/postgresql-queue-shard-owned/README.md` still says "a retry never reorders", which is false across a
shard handover (see P1, "Shard-owned retry blocking survives a handover").

**Fix:** correct the claim in the README, the module `CLAUDE.md` and the design doc. Re-check the remaining 0.60.0
release-note corrections against the release notes; the 2026-10-08 check did not verify them one by one:
- RN-3: section 1.1.6.
- RN-5: the schema `emit` step.

---

## P1: 0.60.x patch releases

Bug fixes only; no API breaks.

### Durable queues

**Shard-owned retry blocking survives a handover (N-16, RN-4).**
- **Problem:** on the shard-owned engine's ordered lane, "this key is blocked while its message waits out a retry
  backoff" exists only in the owning instance's memory (`OrderedShardOwner.keysAwaitingRetry`). `inFlight()` does not
  count those keys.
- **Consequence:** after a handover (rebalance, graceful stop or crash), the new owner reads only rows with
  `visible_at <= now()`. A later message for the same key then overtakes the one being retried, which breaks the
  per-key ordering the docs promise.
- **Fix:** on takeover, load ordered rows whose `visible_at` is in the future as blocked keys, and count retry-blocked
  keys in `inFlight()`.
- **Acceptance:** a new `ShardOwnedOrderedRetryHandoverIT` covers rebalance, graceful stop and crash, both directly
  and through the `DurableQueues` adapter, plus the `postgresql-queue` and MongoDB counterparts. It must fail on the
  current code and pass after the fix.

**Opt-in guard against enqueueing outside a unit of work (RN-2).**
- **Problem:** since `TransactionalMode` was removed, an enqueue outside a unit of work silently commits on its own.
  Code that relied on "the entity change and the enqueue commit together" breaks without any signal.
- **Fix:** add an opt-in property, e.g. `essentials.durable-queues.require-active-unit-of-work-for-enqueue`, that
  makes such an enqueue fail. With the guard off, log a one-time WARN when it happens.
- **Tests:** add enqueue-rollback ITs for all three engines (PostgreSQL, MongoDB, shard-owned), each under an
  Essentials `UnitOfWork` and under a Spring `TransactionTemplate`.

**Validate redelivery policies when they are built (QL-3).** The backoff formula was fixed in 0.60.0, but
`RedeliveryPolicy.exponentialBackoff()...build()` still accepts an incomplete policy:
- an unset multiplier silently means no growth;
- an unset `maximumNumberOfRedeliveries` silently means zero redeliveries;
- a missing delay fails late, during Spring bean creation, with a message that reads like a wiring problem.

**Fix:** `ExponentialBackoffBuilder.build()` validates every required setting up front: the delays are set, the
multiplier is at least 1.0, and the maximum number of redeliveries is set explicitly. Each error message names the
missing setter.

### Schema harness

**Register an aggregate type only after its storage is initialised (N-17, RN-5).**
- **Problem:** `SeparateTablePerAggregateTypePersistenceStrategy.addAggregateEventStreamConfiguration` stores the
  configuration before it creates or validates the event-stream table. The check-then-put is also not atomic under
  concurrent registration.
- **Consequence:** if initialisation throws (a schema mismatch in `validate` mode, a DDL error), the type stays
  registered without a table. A retry then does nothing, and later appends fail with a bare "relation does not
  exist".
- **Fix:** register the type only once initialisation succeeds, using `computeIfAbsent` or removing the entry on
  failure.

**Name the `AggregateType` in the validate error (RN-5).** In `essentials.schema.mode=validate`, an aggregate type
that was missing from the emitted script fails with a message that names only the table. The operator cannot tell
which type was registered too late, for example from an `ApplicationRunner` or a lazy bean. **Fix:** name the
`AggregateType` and its table, and say to register the type at startup and re-run `emit`.

**List the aggregate types in the `emit` output (RN-5).** The script written by `essentials.schema.mode=emit` and
its log line do not say which aggregate types and tables they include. Types registered after the singleton phase
(runners, `@Lazy` beans, on-demand repositories) are silently missing. **Fix:** list the types and tables in the
script header and in the log line, and WARN when a registration arrives after the exit has been triggered.

**Test the real `emit` exit path (RN-5).** Every schema-mode IT sets `essentials.schema.emit.exit=false`. So the real
"write the script and exit with code 0" path, and the default output file `essentials-schema.sql` in the working
directory, are untested. **Fix:** add an IT that runs the real exit path, for example in a forked JVM, and asserts
the exit code and where the file lands.

### Event store

**Subscriber lag gauge (N-9, ES-4, ES-7).** `SubscriberGlobalOrderMicrometerMonitor` computes lag as
`max(0, highestPersisted - resumeFrom)`. It has three problems:
- It is off by one, because the resume point is the next order to read. One unprocessed event reads as lag 0.
- The clamp hides a subscriber whose resume point is *ahead* of the head. That is the sign of a reset or restored
  database, and such a subscriber silently skips events.
- It is registered only when `management.tracing.enabled=true`, so most deployments never get it.

**Fix:** correct the arithmetic, register the monitor whenever a `MeterRegistry` bean is present, and WARN when a
resume point is ahead of the head.

**An "empty" `LongRange` returns the first event (N-10).**
- **Problem:** `LongRange.EMPTY_RANGE` is `(0,0)`. It covers order 0, the first event, and it equals
  `LongRange.only(0)`, which the event store itself uses for "only the first event".
- **Consequence:** an empty range passed to `fetchStream` or `loadEventsByGlobalOrder` returns the first event
  instead of nothing.
- **Fix:**
  - Represent empty as `toInclusive < fromInclusive` (`EMPTY_RANGE = (0, -1)`), and add `LongRange.isEmpty()`.
  - Short-circuit an empty range where the `FetchStream`/`LoadEventsByGlobalOrder` operation is executed, after the
    interceptors have run (an interceptor can replace the range). Return `Optional.empty()` or `Stream.empty()` with
    no database round trip.
  - Update `LongRangeTest.test_empty_range` and add a release-note line.
- The typed ranges in P2 build on this.

### Document DB

**`OptimisticLockingException` is checked (DD-1).**
- **Problem:** it extends `Exception` but is thrown from Kotlin `update`/`save` without `@Throws`.
- **Consequence:** javac rejects a Java `catch` for it, so Java callers must catch `Exception`.
- **Fix:** make it extend `RuntimeException`. Do not add `@Throws`, which would force every Java caller to handle it.

**A private `@Id` fails on every read (DD-3).**
- **Problem:** an entity with a non-public `@Id` field passes `EntityConfiguration.configureEntity`, then fails every
  read with an access exception.
- **Consequence:** in a projection, every message is dead-lettered and the view stays empty, with no error at
  startup.
- **Fix:**
  - Make the id property accessible at configuration time, or fail fast naming the entity and the field.
  - Add a factory overload for `CharSequenceType` ids that is usable from Java; today only a raw-typed call compiles.
  - Add a test that reads an entity with a private id.

**`getById` throws a bare NPE (DD-5).** `DocumentDbRepository.getById` is `findById(id)!!`. A missing document
therefore throws a `NullPointerException` without the entity type or id. **Fix:** throw a descriptive not-found
exception naming both.

### Fenced locks

**Document per-instance re-entrancy (QL-1).** `FencedLockManager.tryAcquireLock` is re-entrant per lock-manager
instance:
- a second acquire in the same JVM returns the same `FencedLock`;
- a `release()` by either caller releases the lock for both.

When two components in one JVM use the same lock name, this breaks the documented singleton-worker pattern. **Fix
now:** say in the Javadoc and the docs that ownership is per lock-manager instance, not per caller or thread. The
API fix is in P2.

**The default lock-manager id is the hostname (N-8, ES-8).** Two JVMs on one host, or two containers that share a
hostname, both appear to own a lock in the lock table and in diagnostics. The fence token still protects
correctness, but admin views and logs mislead. **Fix:** default to `hostname:pid`, and add a starter property to set
the id explicitly.

### Command bus

**A duplicate command registration fails only at first send (CB-3).**
- **Problem:** when two `EventStreamDeciderAndAggregateTypeConfigurator`s register handlers for the same command
  type, nothing fails until the first `send`.
- **Consequence:** that `send` throws `MultipleCommandHandlersFoundException`, listing two unhelpful
  `EventStreamDeciderCommandHandlerAdapter@hash` entries.
- **Fix:** give the adapter a `toString()` that names its aggregate type and deciders. Make the configurator fail at
  construction when a command type it would register is already handled.

### `MultiTableChangeListener`

**Robustness fixes (LN-2 to LN-6, N-6, N-7).** `MultiTableChangeListener` fans PostgreSQL LISTEN/NOTIFY out to the
event store and the queues. It has several silent-failure bugs:
- It holds a pooled connection forever and never detects a half-open socket. It logs connection failures only at
  DEBUG.
- It schedules polling in the constructor, so `stop()` followed by `start()` never resumes.
- A reconnect can drop the LISTENs for other channels, and `stop()` never closes the connection.
- One non-JSON NOTIFY payload drops the whole poll batch whenever a duplicate filter is registered, which it always
  is when the PostgreSQL queue is used. The batch includes event-store and queue wake-ups.
- De-duplication is shared across channels instead of being per channel.
- Mixed-case channel names never match, and `TableChangeNotification.equals` is not null-safe.

**Fix:**
- Rebuild the LISTEN connection loop on the design of the shard-owned queue's `ShardWakeupListener`:
  `Connection.isValid()` probing with WARN logging, scheduling in `start()`, re-LISTEN on reconnect, and closing the
  connection on stop.
- De-duplicate per channel, with no key for non-JSON payloads.
- Make `equals`/`hashCode` null- and class-safe.
- Normalise channel names with `toLowerCase(Locale.ROOT)`.

**Build the documented `MultiTableChangeListener.builder()` (N-14).** `LLM/LLM-foundation.md` shows a builder that
does not exist, so the snippet does not compile. Build it rather than delete the snippet. Its `set…` names follow
the `PostgresqlDurableQueues.builder()` convention, so the documented snippet compiles unchanged.
- **Required:** `setJdbi`, `setJsonSerializer`, `setEventBus`.
- **Optional, with the starter's defaults:** `setPollingInterval` (50 ms), `setFilterDuplicateNotifications` (true).
- **Home for the new listener options,** so they need no new constructor overloads:
  - a liveness-check interval;
  - the log level and rate for connection loss;
  - the per-channel de-duplication policy;
  - the poll thread name;
  - an optional dedicated connection outside the pool: `setListenerJdbi(Jdbi)` / `setListenerDataSource(DataSource)`
    on the builder, and `essentials.multi-table-change-listener.dedicated-connection=true` in the starter.
- **Compatibility:** keep the 5-argument constructor (stable API), and switch the starter and the internal call
  sites to the builder. It is additive, so it can ship in a patch together with the robustness fixes.

### Spring Boot starters

**Missing optional dependencies fail without saying what is missing (SB-2).** Every third-party integration has
`provided`/optional scope. A missing dependency therefore surfaces as a raw `NoClassDefFoundError` or
`ClassNotFoundException` deep in a bean-creation stack, often only at first use. **Fix:** add one Spring Boot
`FailureAnalyzer` that maps the known missing classes to the Maven coordinates to add.

**`essentials.immutable-jackson-module-enabled` has no effect (N-11).** The property is documented with default
`true`, but its condition lacks `matchIfMissing = true`. The module bean is therefore created only when the property
is set explicitly, and the persistence mapper ignores the bean anyway. The PostgreSQL and MongoDB starters both have
this. **Fix:** add `matchIfMissing = true` and make the persistence serializer honour the property, or deprecate the
property if it cannot apply.

---

## P2: next 0.6x minor

Additive features.

### Event store

**Wait until a processor has handled an event (ES-4).** A caller that appends an event cannot wait until a given
processor has handled it. Read-your-writes flows and integration tests therefore poll the read model under a guessed
timeout. Under load, every such timeout becomes a source of flakiness that nothing can diagnose.

**Fix:** a processor-level `awaitProcessed(AggregateType, GlobalEventOrder, Duration)`, plus a `Mono` form.
- **Why on the processor and not the subscription:** a subscription's resume point being past N does not mean event
  N was processed. `EventProcessor` only forwards the event to its Inbox, and `ViewEventProcessor` may have queued it
  after a failure. The barrier must therefore require both: the resume point is past N, and no queued message
  remains for the keys involved.
- **Surface the appended order:** `EventStreamDeciderCommandHandlerAdapter.persistEvent` currently logs the
  `GlobalEventOrder` the append returned and then discards it. The adapter and the command bus path should hand it
  to the caller, for example on a result wrapper or in UnitOfWork metadata, so a caller has an N to wait for.

**Typed event-order ranges (ES-1).**
- **Problem:** the event-store read APIs take an untyped `LongRange` for both per-stream `EventOrder` and cross-stream
  `GlobalEventOrder` ranges. Mixing them up compiles, and the result is a silent wrong read.
- **Fix:**
  - Add `EventOrderRange` and `GlobalEventOrderRange` to `foundation-types`, with typed overloads on `EventStore` and
    on the `FetchStream`/`LoadEventsByGlobalOrder` builders.
  - Mark the `LongRange` consumer entry points and `GlobalEventOrder.rangeFrom(...)` `@Deprecated`, not
    `forRemoval`.
  - The interceptor SPI stays on `LongRange` until the next major.
- Builds on the empty-range fix in P1.

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
- Do not build it on `MultiTableChangeListener`. Extract the shared LISTEN-connection loop instead, so that class
  gets the reconnect fixes too.

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

### Document DB

**Do not apply the SQL reserved-word check to JSON property names (DD-2).** The document DB checks every entity
property name, recursively, against the SQL reserved-keyword list:
- It rejects ordinary names, including properties of JDK types such as `Optional.value` and `URI.path`.
- The error never says that a reserved keyword is the cause.

Property names reach SQL only as quoted JSONB path segments, bind names or prefixed index names, so the check
protects nothing.

**Fix:**
- For JSON property and path segments, check only the character class and the length. Keep the reserved-word check
  for table names.
- Name "reserved keyword" in the message when it is the cause.
- Do not recurse into `java.*` types.

### Handler coverage

**Make unhandled event types visible (CB-4).** Event processors allow unmatched messages, so they silently drop any
event type that has no handler. A missing handler stays invisible until someone notices data is missing.

**Fix:**
- At startup, log the event types each processor handles.
- Optionally, add a rate-limited WARN or a per-type counter for unmatched events.
- For deciders whose command root type is sealed, the configurator walks `getPermittedSubclasses()` at startup and
  reports any command that no decider handles.

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

- **Lock ownership and predicates (QL-1, QL-2).**
  - Ownership is per lock-manager instance: a second `tryAcquireLock` in the same JVM returns the same lock, and its
    `release()` releases it for everyone. Correct the "Singleton Worker Pattern" to match.
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
