# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`0.61.0`**, that a user of the framework,
the `essentials` plugin or the examples would notice. The version number is decided at release time; this file
then becomes `docs/releases/RELEASE-NOTES-<version>.md` (same structure as
[`RELEASE-NOTES-0.61.0.md`](RELEASE-NOTES-0.61.0.md)) and is reset to an empty skeleton._

_Add an entry in the same commit as the change. Cite the commit or PR. Write it for the person upgrading: what
changes for them, and what they must do, if anything. Purely internal refactors, test-only changes and CI
plumbing that no user sees go in §7 at most, as one line._

_A bug-fix entry also says how to observe the fix: the call, and what it returns, throws or logs before the fix
and after it. A project that worked around the bug can then check whether its workaround is still needed and
remove it, instead of keeping it because nobody can tell._

**So far:** nothing yet.

- Shard-owned engine and adapter: enqueues no longer serialise their commits on `NOTIFY`; pumps visit only the owners
  that need them and take one horizon probe per pass; idle pumps can give their connections back; a cheaper message
  format; a queue depth read costs 3 statements instead of 69 (§2.13); a head sweep is one statement, not two (§2.14).
- Event store: concurrent gap give-ups no longer deadlock; a give-up costs what it gives up rather than every gap
  outstanding; CDC hands out event JSON as stored; an out-of-order commit no longer costs every CDC subscriber a
  commit of its own (§2.12).
- Aggregates: an update no longer re-queries the newest snapshot, policies on declared aggregates apply to
  repositories built at startup (§1.1.2), and the default snapshot interval is 1000 events (§1.1.3).
- Handler dispatch (`shared`): every aggregate event applied and every handler call no longer builds log messages that
  trace logging is off for.
- Event processors: `PartitionedEventProcessor` runs its handlers on every instance on the shard-owned engine (§2.7),
  `PartitionedViewEventProcessor` does the same for views (§2.10), and both reset only while stopped on every instance;
  an `EventProcessor` overridden to competing consumers on a per-node queue is warned about at start (§2.8); an opt-in
  `FailoverRedeliveryPolicy` retries for long enough to outlast a failover (§2.18).
- Command handlers: an `AnnotatedCommandHandler` failure is logged at DEBUG, not ERROR - the sender reports it (§1.1.4).
- CDC: the inbox deletes a row once it is dispatched by default, instead of keeping it until the TTL job (§1.1.5).
- Spring Boot starters: the tracing and queue metrics interceptors are registered when the Micrometer beans are
  present, not only with `management.tracing.enabled=true`, which Spring Boot 4 no longer reads (§1.1.6).
- Fenced locks: opt-in spreading of async-acquired locks, and so of exclusive processors, over the instances (§2.9).
- Interceptors: every interceptor family can replace what it passes on - queue, message-handler, command-bus and
  event-store interceptors share one contract (§2.19); handlers now receive replacements and `PatternMatchingMessageHandler`
  honours `@InterceptorOrder` (§1.1.7).
- Classic durable queues: polls of a queue that holds no ordered message skip the ordered claim - half the claim
  statements; an ordered message from another node can wait up to 1 s longer (§1.1, opt-out).

There are no API removals. One schema change removes a redundant index on the event store's gap table (§1); another
adds a `fence_token` column to the durable subscriptions table, which ends exclusive subscriptions' resume points
freezing after a lock hand-over (§1, §3). The
shard-owned adapter writes a new message format, and **0.60.x and this release must not run side by side against the
same shard-owned queues** (§1).

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
   - [1.1 ⚠️ Silent behaviour changes](#11-️-silent-behaviour-changes)
2. [New features](#2-new-features)
   - [2.1 Shard-owned: idle pumps can give their connections back (`postgresql-queue-shard-owned`, Spring Boot starter)](#21-shard-owned-idle-pumps-can-give-their-connections-back-postgresql-queue-shard-owned-spring-boot-starter)
   - [2.2 Shard-owned: enqueues no longer serialise their commits on `NOTIFY` (`postgresql-queue-shard-owned`, adapter)](#22-shard-owned-enqueues-no-longer-serialise-their-commits-on-notify-postgresql-queue-shard-owned-adapter)
   - [2.3 Shard-owned: a pump visits only the owners that signalled, came due or are new (`postgresql-queue-shard-owned`)](#23-shard-owned-a-pump-visits-only-the-owners-that-signalled-came-due-or-are-new-postgresql-queue-shard-owned)
   - [2.4 Shard-owned: one horizon probe per pump pass (`postgresql-queue-shard-owned`)](#24-shard-owned-one-horizon-probe-per-pump-pass-postgresql-queue-shard-owned)
   - [2.5 An aggregate update no longer re-queries the newest snapshot (`eventsourced-aggregates`)](#25-an-aggregate-update-no-longer-re-queries-the-newest-snapshot-eventsourced-aggregates)
   - [2.6 Handler dispatch no longer builds log messages nobody reads (`shared`)](#26-handler-dispatch-no-longer-builds-log-messages-nobody-reads-shared)
   - [2.7 `PartitionedEventProcessor`: an event processor whose handlers run on every instance (`postgresql-event-store`, foundation, shard-owned adapter)](#27-partitionedeventprocessor-an-event-processor-whose-handlers-run-on-every-instance-postgresql-event-store-foundation-shard-owned-adapter)
   - [2.8 A handler that outlasts `message-handling-timeout` is logged (foundation, `postgresql-queue`)](#28-a-handler-that-outlasts-message-handling-timeout-is-logged-foundation-postgresql-queue)
   - [2.9 Fenced locks can be spread over the instances, opt-in (foundation, PostgreSQL/MongoDB lock managers, Spring Boot starters)](#29-fenced-locks-can-be-spread-over-the-instances-opt-in-foundation-postgresqlmongodb-lock-managers-spring-boot-starters)
   - [2.10 `PartitionedViewEventProcessor`: a view projector whose handlers run on every instance (`postgresql-event-store`, foundation, shard-owned engine and adapter)](#210-partitionedvieweventprocessor-a-view-projector-whose-handlers-run-on-every-instance-postgresql-event-store-foundation-shard-owned-engine-and-adapter)
   - [2.11 `InvokeMessageHandlerMethod` exposes the message being handled (foundation)](#211-invokemessagehandlermethod-exposes-the-message-being-handled-foundation)
   - [2.12 CDC: an out-of-order commit no longer costs each subscriber a commit of its own (`postgresql-event-store`)](#212-cdc-an-out-of-order-commit-no-longer-costs-each-subscriber-a-commit-of-its-own-postgresql-event-store)
   - [2.13 Shard-owned: a queue depth read is 3 statements, not 69 (`postgresql-queue-shard-owned`)](#213-shard-owned-a-queue-depth-read-is-3-statements-not-69-postgresql-queue-shard-owned)
   - [2.14 Shard-owned: a head sweep and its next-visible time are one statement (`postgresql-queue-shard-owned`)](#214-shard-owned-a-head-sweep-and-its-next-visible-time-are-one-statement-postgresql-queue-shard-owned)
   - [2.15 Closing books can roll at a business day's close, not at midnight (`eventsourced-aggregates`, Spring Boot starter, admin API)](#215-closing-books-can-roll-at-a-business-days-close-not-at-midnight-eventsourced-aggregates-spring-boot-starter-admin-api)
   - [2.16 Scheduled closing books can roll an aggregate the way the application does on access (`eventsourced-aggregates`)](#216-scheduled-closing-books-can-roll-an-aggregate-the-way-the-application-does-on-access-eventsourced-aggregates)
   - [2.17 Guarded closing-books rollovers refuse an aggregate from a generation that has since rolled (`eventsourced-aggregates`)](#217-guarded-closing-books-rollovers-refuse-an-aggregate-from-a-generation-that-has-since-rolled-eventsourced-aggregates)
   - [2.18 `FailoverRedeliveryPolicy`: an event processor retry budget that outlasts a failover, opt-in (`postgresql-event-store`)](#218-failoverredeliverypolicy-an-event-processor-retry-budget-that-outlasts-a-failover-opt-in-postgresql-event-store)
   - [2.19 Interceptors can replace what they pass on, in every interceptor family (foundation, `reactive`, `postgresql-event-store`, `shared`)](#219-interceptors-can-replace-what-they-pass-on-in-every-interceptor-family-foundation-reactive-postgresql-event-store-shared)
3. [Bug fixes](#3-bug-fixes)
4. [The `essentials` Claude Code plugin](#4-the-essentials-claude-code-plugin)
5. [Examples](#5-examples)
6. [Documentation](#6-documentation)
7. [Build and tooling](#7-build-and-tooling)

---

## 1. Breaking — what you MUST apply

**Under `essentials.schema.mode=validate`: apply the new schema change `durable-subscriptions-fence-token` before the
first start (`postgresql-event-store`).** It adds a nullable column `fence_token BIGINT` to the durable subscriptions
table (default name `durable_subscriptions`), which fences resume-point saves of exclusive subscriptions by their lock
token (§3). Under `create` (the default) the column is added on start, only when it is missing, so a running instance is
not locked out of the table. Under `validate` the first start is refused, naming `durable-subscriptions-fence-token` as
not applied: run with `emit` to write the script and apply it, or run the statement yourself
(`ALTER TABLE durable_subscriptions ADD COLUMN IF NOT EXISTS fence_token BIGINT`) and then the script's ledger insert.
The column is nullable, so instances of the previous release keep working against the altered table during a rolling
upgrade. Their saves carry no token and are checked by the reposition epoch alone, as before, so **the hazard the
column fixes (§3) persists until every instance runs this release**.

**If the fenced-lock tokens restart, clear the stored tokens.** Lock tokens only ever grow while the fenced-locks table
(default `fenced_locks`) keeps its rows. If that table is dropped and recreated, truncated, restored from an older backup,
or the lock manager is pointed at a different table, new lock holders get tokens lower than those stored in
`durable_subscriptions.fence_token`, and every save of an exclusive subscription is refused (one WARN per subscription:
`Did not save resumeFromAndIncluding … was written by a newer fenced lock holder (stored fence token is newer than …)`;
the stored resume point stops moving). Recovery: with the exclusive subscriptions stopped, or at least before their next
save, run `UPDATE durable_subscriptions SET fence_token = NULL` (all rows, or `WHERE subscriber_id = …` for one); the
next save of each lock holder then stores its own token.

### 1.1 ⚠️ Silent behaviour changes

- **Classic durable queues (`postgresql-queue`): a queue that holds no ordered message stops running the ordered
  claim on every poll.** Each poll used to claim ordered messages first and unordered ones second; on a queue that
  never receives an `OrderedMessage` the first statement always came back empty and cost one database round trip on
  the poller's single thread. Now a check finds that the queue holds no ordered message outside its dead letter
  queue, and polls leave that statement out until the next check, once a second. Measured with the
  `SteadyStateClaimCostBenchmarkIT` and the new `OrderedClaimOnUnorderedQueuesBenchmarkIT` (three alternating rounds
  each): claim statements per delivered message drop from 0.50 to 0.25 at 200 and 2 000 msg/s, all statements on ten
  unordered queues at 100 msg/s drop by 37%, and delivery latency is unchanged against a local database. The saving
  grows with the round-trip time to the database.

  What can change for you: an ordered message that **another** node queues or resurrects - or that is inserted by
  SQL - onto a queue that held no ordered message is noticed at the next check, so it can be delivered up to the
  re-check interval (1 s) later than before. An ordered message this node queues, retries or resurrects is delivered
  at once, and queues that hold ordered messages never skip. To restore the previous behaviour, set
  `PostgresqlDurableQueuesBuilder.setOrderedClaimSkipRecheckInterval(Duration.ZERO)`, or in Spring Boot
  `essentials.durable-queues.ordered-claim-skip-recheck-interval=0`. A shorter interval narrows the extra wait at
  the cost of one cheap existence check per queue per interval.

---

## 2. New features

None so far.

### 2.1 Shard-owned: idle pumps can give their connections back (`postgresql-queue-shard-owned`, Spring Boot starter)

None so far.

### 2.2 Shard-owned: enqueues no longer serialise their commits on `NOTIFY` (`postgresql-queue-shard-owned`, adapter)

None so far.

### 2.3 Shard-owned: a pump visits only the owners that signalled, came due or are new (`postgresql-queue-shard-owned`)

None so far.

### 2.4 Shard-owned: one horizon probe per pump pass (`postgresql-queue-shard-owned`)

None so far.

### 2.5 An aggregate update no longer re-queries the newest snapshot (`eventsourced-aggregates`)

None so far.

### 2.6 Handler dispatch no longer builds log messages nobody reads (`shared`)

None so far.

### 2.7 `PartitionedEventProcessor`: an event processor whose handlers run on every instance (`postgresql-event-store`, foundation, shard-owned adapter)

None so far.

### 2.8 A handler that outlasts `message-handling-timeout` is logged (foundation, `postgresql-queue`)

None so far.

### 2.9 Fenced locks can be spread over the instances, opt-in (foundation, PostgreSQL/MongoDB lock managers, Spring Boot starters)

None so far.

### 2.10 `PartitionedViewEventProcessor`: a view projector whose handlers run on every instance (`postgresql-event-store`, foundation, shard-owned engine and adapter)

None so far.

### 2.11 `InvokeMessageHandlerMethod` exposes the message being handled (foundation)

None so far.

### 2.12 CDC: an out-of-order commit no longer costs each subscriber a commit of its own (`postgresql-event-store`)

None so far.

### 2.13 Shard-owned: a queue depth read is 3 statements, not 69 (`postgresql-queue-shard-owned`)

None so far.

### 2.14 Shard-owned: a head sweep and its next-visible time are one statement (`postgresql-queue-shard-owned`)

None so far.

### 2.15 Closing books can roll at a business day's close, not at midnight (`eventsourced-aggregates`, Spring Boot starter, admin API)

None so far.

### 2.16 Scheduled closing books can roll an aggregate the way the application does on access (`eventsourced-aggregates`)

None so far.

### 2.17 Guarded closing-books rollovers refuse an aggregate from a generation that has since rolled (`eventsourced-aggregates`)

None so far.

### 2.18 `FailoverRedeliveryPolicy`: an event processor retry budget that outlasts a failover, opt-in (`postgresql-event-store`)

None so far.

### 2.19 Interceptors can replace what they pass on, in every interceptor family (foundation, `reactive`, `postgresql-event-store`, `shared`)

None so far.

---

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|
| CDC (`postgresql-event-store`) | PostgreSQL 17.12 and 18 refuse an output plugin that is not listed in `output_plugin_libraries` (default `pgoutput, test_decoding`), so creating a `wal2json` slot fails with `library "wal2json" may not be used as an output plugin`: under `cdc.mode=require` start-up fails, under `auto` subscribers silently stay on polling. What to do: add `wal2json` to the setting (`postgresql.conf`: `output_plugin_libraries = 'pgoutput, test_decoding, wal2json'`; SQL: `ALTER SYSTEM SET output_plugin_libraries = 'pgoutput', 'test_decoding', 'wal2json'`, each name quoted on its own, then `SELECT pg_reload_conf()`), or use `pgoutput`. How to observe: `Wal2JsonLogicalDecodingPlugin.unusableReason` read only "wal2json plugin not usable"; it now names `output_plugin_libraries` and both ways to add `wal2json`. `pgoutput` is unaffected. | ported from `bbf1d28f0` |
| Exclusive subscriptions (`postgresql-event-store`) | After a lock hand-over during a database outage, an exclusive subscription's stored resume point stopped moving for as long as the new lock holder held the lock, and the next restart or hand-over redelivered every event since the hand-over (no event was skipped). Cause: the previous holder, stopped while it retried an event, held its resume point at that event, which starts a new reposition epoch, and saved it after the new holder had read the row; saves were not checked against the lock, the newer epoch won, and the reposition-epoch guard then refused every save of the new holder. Affects 0.60.0 to 0.61.0, and the same way the batched exclusive subscription and a graceful stop whose final save lands after another instance took the lock. Fix: every resume-point save of an exclusive subscription carries the token of the fenced lock it acquired (`SubscriptionResumePoint.fencedBy` / `getFenceToken`), the table stores the highest token written (new column `fence_token`, schema change `durable-subscriptions-fence-token`, see §1), a save with a newer token wins whatever its reposition epoch, one with the same token is checked against the epoch as before (a reset still wins over that holder's in-flight saves), and one with an older token is refused. Non-exclusive subscriptions and `resetStoredResumePoint` save without a token and behave as before. `EventStoreSubscription.resetFrom` on an exclusive subscription now saves the reset before it lets go of the lock (it saved after the release, so an instance that took the lock over in between could read the old resume point and resume from there - with the token rule its first save would then have overwritten the reset), so the next lock holder resumes from the reset point. Until every instance runs this release, an instance of the previous release still saves without a token (see §1). How to observe: before, the new holder logged once `Did not save resumeFromAndIncluding … the stored resume point was repositioned by another writer (stored reposition epoch is newer than 0)` and `durable_subscriptions.resume_from_and_including_global_eventorder` stayed behind its progress; after, the new holder's saves land and `fence_token` holds its lock's token. A refusal now comes from the previous holder, late, and its WARN also names the fence token. A row frozen by the bug heals at the new holder's first save after the upgrade. Pinned by `PostgresqlDurableSubscriptionRepositoryIT` and `EventStoreSubscriptionManager_2_node_exclusive_resetFrom_IT`. | this commit |

---

## 4. The `essentials` Claude Code plugin

0.61.0 shipped plugin `0.61.0`. Plugin releases that follow it are listed here, newest first;
the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

- **0.61.0-1** — the event-store reference says CDC with `wal2json` on PostgreSQL 17.12 / 18 needs `wal2json` in
  `output_plugin_libraries`, and how to add it.

---

## 5. Examples

Not published; listed because they are the reference for how to use the framework.

- `essentials-trading-demo`: its compose files use the repository's PostgreSQL image under its new tag
  `essentials-postgres:17.5-2` (see §7).

---

## 6. Documentation

None so far.

---

## 7. Build and tooling

- The repository's PostgreSQL test image (`docker/postgresql`, now `essentials-postgres:17.5-2`) runs the server its tag
  names: it shipped the builder's whole PostgreSQL tree, so the 17.5 image ran 17.12, and all CDC integration tests failed
  because 17.12 refuses `wal2json` as an output plugin. Its extensions are now compiled against exactly the base image's
  server version, pg_cron is 1.6.8 (1.6.4 does not compile on 18), its initdb script creates pg_cron only when the server
  preloads it, and the CDC tests use this image instead of a second one of their own; their setup adds `wal2json` to
  `output_plugin_libraries` where the server has that setting. Any base works:
  `-Dessentials.postgres.base-image=postgres:18-bookworm`.
