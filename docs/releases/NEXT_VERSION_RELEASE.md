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

There are no API removals. One schema change removes a redundant index on the event store's gap table (§1). The
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

None so far.

### 1.1 ⚠️ Silent behaviour changes

None so far.

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

---

## 4. The `essentials` Claude Code plugin

0.61.0 shipped plugin `0.61.0`. Plugin releases that follow it are listed here, newest first;
the full entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

---

## 5. Examples

Not published; listed because they are the reference for how to use the framework.

---

## 6. Documentation

None so far.

---

## 7. Build and tooling

None so far.
