# Essentials 0.6x roadmap

The open fixes and improvements planned for the 0.6x line. Every item here was checked against the code on
2026-10-08 (0.60.0 is tagged `bcc17f35`), and only items that are still open or partly done are listed.

**How to use this file**

- The bucket names the earliest release an item may ship in. Project rule: breaking changes only in a new major;
  patch and minor releases are additive.
- The id in parentheses after each title (e.g. `ES-4`, `N-9`) is a stable label from the 0.60.0 maintainer review;
  `NW-n` ids come from the 2026-10-13 test-speed wave, whose flaky-test fixes surfaced them.
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
- **All of P3 (documentation)**, plus a fix for the flaky `StarterAutoConfigurationIT.verify_api_beans`.

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

### Command bus

**Mark a Spring-managed transaction rollback-only when a command fails (CB-5).**
`SpringTransactionAwareUnitOfWork.markAsRollbackOnly` only logs at DEBUG when Spring manages the transaction. So
inside a `@Transactional`, a caller that catches the exception from `commandBus.send` commits whatever the handler
wrote before it threw, and a typed `Decider`'s `HandlerResult.error` mark is lost the same way. Plain Spring marks the
shared transaction rollback-only when an inner `@Transactional` method throws. **Fix:** mark the Spring transaction
rollback-only too (`TransactionStatus.setRollbackOnly()`). Not in a patch: a caller that catches today would then get
`UnexpectedRollbackException` at commit, so it needs a migration note. 0.60.1 documents it as `ESS-126`.

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

**Count only real sweep recoveries (NW-4).** The shard-owned engine's `sweepRecoveries` statistic also counts a row
that committed between a pass's cursor read and its head sweep (two statements, two snapshots). Nothing was stepped
over, and the counter's Javadoc already says a recovery is "not a defect by itself", but a dashboard alerting on it
fires on harmless rows, most often in the start-up sweep. **Fix:** count only swept rows with `seq <= safeCursor`, and
say in the release notes that the counter's meaning narrowed.

### Database schema at startup

**Raise the PostgreSQL floor in code (NW-1).** From 0.60.1 the supported PostgreSQL is 15+ (README § Version
Compatibility), but the code still carries paths for older servers. **Problem:** on PostgreSQL below 14,
`ListenNotify` drops and re-creates the change-notification trigger. The schema contribution does it on the one boot
that has to change the trigger, and the public `addChangeNotificationTriggerToTable` does it on every call. Either can
deadlock with instances using the table, which 0.60.1 fixed for 14+ only. **Fix:** remove the pre-14 branches (the
`server_version_num` check in the trigger `DO` block, the drop-and-create in `addChangeNotificationTriggerToTable`),
drop `ListenNotifyPostgresql12IT` and `MultiTableChangeListenerPostgresql12IT`, and log a WARN at startup on a server
below the supported floor. Not in a patch: a 0.60.x deployment on PostgreSQL 13 or 14 keeps working.

**Index DDL without a table lock on every boot (NW-2).** `CREATE INDEX IF NOT EXISTS` takes a SHARE lock on the table
even when the index exists, so every startup briefly stalls the queue's and the event store's writers while the
schema transaction runs. It can no longer deadlock (0.60.1), but the stall grows with the length of that transaction.
**Fix:** check `pg_indexes` first, in a `DO` block like the trigger's, and only create a missing index.

**Lock order of the event store's startup transaction (NW-3).** The event store applies several event tables in one
schema transaction. A user transaction that writes those tables in the opposite order could, in theory, deadlock with
a starting instance. Not reproduced. **Plan:** confirm or rule out with a test like `QueueSchemaReapplyConcurrencyIT`
first; if confirmed, apply each table's changes in its own transaction, or take the table locks in a fixed order.

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

**An acquire inside the caller's unit of work (NW-13).** Since 0.60.1 a lock is recorded as acquired only after its
acquire has committed, and a failed commit is resolved by looking the row up. That holds only when the lock manager
owns the unit of work. A `tryAcquireLock`/`acquireLock` called while the caller already has one active joins it, so the
lock is recorded before the caller commits; if the caller then rolls back, this instance holds a lock the database never
gave it, and a later confirmation can release another instance's lock under the same token. Either always acquire in a
unit of work of the lock manager's own (a new transaction), or record the lock from an after-commit callback of the
joined unit of work. Decide whether a caller relies on the acquire joining its transaction first.

**Database round trips under the manager-wide lock (NW-14).** `DBFencedLockManager`'s `reentrantLock` is held for the
whole of every blocking acquire, the confirmation tick and the async acquiring tick, and `usingUnitOfWork` and
`withUnitOfWork` take it for their own round trip too. An acquire can hold it across two round trips (the acquire and,
when its commit fails, the outcome lookup), so a slow database delays every other acquire and the confirmation of
locks already held; today the only mitigation is a `lockTimeOut` well above the confirmation interval plus a few slow
round trips, documented on the field. Give confirmation its own lock, or do the DB work outside the lock and take it
only to update `locksAcquiredByThisLockManager`. Either way the protected `usingUnitOfWork`/`withUnitOfWork` helpers
change what they guarantee to subclasses, so check every in-repo subclass and say so in the release notes. Needs the
fenced-lock ITs on PostgreSQL and MongoDB, and a test with a slow storage that shows confirmation is no longer held up.

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

Everything planned here before 2026-10-13 shipped in 0.60.1.

- **A lost fenced lock can be won straight back (NW-5).** After a database outage longer than the lock timeout, the
  instance that lost a lock can win it again through its still-running `acquireLockAsync`, under the next fence token.
  That is correct fencing, and `DBFencedLockManagerIT` now accepts either instance as the new holder, but the fenced
  lock docs (`LLM-foundation.md`, the module READMEs) do not say so. Say it, next to the fence-token explanation.

---

## P4: `essentials` plugin

- **S2.1's objenesis row when the pins move to 0.60.1.** `stack-contract.md` S2.1, the generated POM comment and the
  init goldens say a missing objenesis fails "at the first read", which is true for 0.60.0. From 0.60.1, mapper
  creation fails at startup naming the dependency (SB-2). Update the row with the `essentials.version` pin and
  regenerate the goldens.
- **Spring Boot's failsafe in generated projects (NW-6).** maven-failsafe-plugin 3.6.0 no longer binds `skipTests`, so
  `-DskipTests` runs every integration test (fixed in Essentials' own POM in 0.60.1 by defaulting `skipITs` to
  `skipTests`). Generated projects take failsafe from Spring Boot, which manages 3.5.6 in 4.1.1, so they are safe
  today. When the Boot pin moves, check the managed failsafe version; at 3.6+, add the same property to the backend
  POM template and a `stack-lint` rule.
- **Eval failures older than the grader fix (NW-7).** One run each on 2026-10-12, all also failing in the earlier
  3-run results: `slice-check-aggregate-lane` (`gen-find-f3`; `gen-not-t5`/`t6`, the gate-6 row citing the handlers
  at `:29`/`:36`), `slice-check-multi-lane` (`gen-find-ml-3`, `gen-lanes`), `slice-check-service-entity`
  (`gen-find-3`; `gen-not-t3`, a "beyond the gates" finding on `findByIdIn`; `gen-not-t10`) and
  `slice-discover-brownfield-layered` (0.71: find, ladder and slices graders, `gen-not-n4`). Triage each as a skill
  defect or a grader defect, as was done for `gen-not-e-t1`/`t3` and the `review-service-entity` expectations.
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

## P5: build and tooling

Internal to the Essentials build and the plugin's checks: nothing a framework or plugin user notices, so these items
need no release-notes entry beyond §7 and can ship in any release.

- **Re-measure the parallel reactor (NW-9).** `mvn -T 3 verify` took 12:53 against about 23 minutes serially on
  2026-10-12, but three `postgresql-queue-shard-owned` ITs failed under it, which is why the root `CLAUDE.md` says that
  module flakes under `-T`. All three failures came from the lease test and the watermark test, and both are fixed.
  Run `mvn -T 3 verify` three times; if it is clean, drop the warning from `CLAUDE.md` and consider `-T` for the CI
  `verify` job.
- **`plugin-check.sh release` has not run for real (NW-10).** The release lanes added on 2026-10-12 were tested with
  `--no-evals` only, and Ctrl-C was not tried. Before the next plugin release, run `scripts/plugin-check.sh release`
  with the evals, and press Ctrl-C once mid-scaffold to check that every child build stops and the work directories
  are left in a state the next run accepts.
- **Parallel scaffold builds on a cold `~/.m2` (NW-11).** `scripts/plugin-scaffold.sh` runs its lanes concurrently, and
  on an empty local repository every lane downloads the same artifacts at once. Maven 3.9 resolves that safely only
  with named file locks. Either warm the repository with one case before fanning out, or pass
  `-Daether.syncContext.named.factory=file-lock -Daether.syncContext.named.nameMapper=file-gav` to each lane.
- **Confirm the `review-service-entity` expectation fix (NW-12).** `gen-not-run` failed in all three runs before its
  expectations were corrected (NR1/NR2), and has been run once since. Run that case three times and confirm it passes
  every time.

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
