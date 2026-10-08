# Essentials — Next Release (unreleased)

_Working draft. Collects every change since the last release tag, **`0.60.0`**, that a user of the framework,
the `essentials` plugin or the examples would notice. The version number is decided at release time; this file
then becomes `docs/RELEASE-NOTES-<version>.md` (same structure as
[`RELEASE-NOTES-0.60.0.md`](RELEASE-NOTES-0.60.0.md)) and is reset to an empty skeleton._

_Add an entry in the same commit as the change. Cite the commit or PR. Write it for the person upgrading: what
changes for them, and what they must do, if anything. Purely internal refactors, test-only changes and CI
plumbing that no user sees go in §7 at most, as one line._

**So far:** no breaking change, one framework bug fix with a behaviour change (§1.1), plugin releases
`0.60.0-6` and `0.60.0-7`, and example and tooling updates. Nothing here requires a migration step. The persisted
format is unchanged.

---

## Table of contents

1. [Breaking — what you MUST apply](#1-breaking--what-you-must-apply)
   - [1.1 ⚠️ Silent behaviour changes](#11-️-silent-behaviour-changes)
2. [New features](#2-new-features)
3. [Bug fixes](#3-bug-fixes)
4. [The `essentials` Claude Code plugin](#4-the-essentials-claude-code-plugin)
5. [Examples](#5-examples)
6. [Documentation](#6-documentation)
7. [Build and tooling](#7-build-and-tooling)

---

## 1. Breaking — what you MUST apply

None so far. No public API changed, and no configuration key was added, renamed or removed.

### 1.1 ⚠️ Silent behaviour changes

#### 1.1.1 A message in flight when its consumer stops is redelivered, not dead-lettered

Affects `PostgresqlDurableQueues` (both the centralized fetcher, the default, and the per-consumer path) and
`MongoDurableQueues`, and therefore every `Inbox`, `Outbox`, `DurableLocalCommandBus` and `EventProcessor` built
on them. The shard-owned engine is unaffected: it never interrupts a handler on stop.

**Before:** stopping a consumer — application shutdown, `consumer.cancel()`, an `Outbox`/`Inbox` stopping —
interrupts its worker threads. A handler in the middle of I/O then fails with an interrupt it did not cause; a
Kafka send throws `org.apache.kafka.common.errors.InterruptException`. The centralized fetcher classified that
failure only *after* the consumer had unregistered, found no registration, and treated that as
`PERMANENT_ERROR` with no redeliveries. The message was dead-lettered on its first attempt whatever its
`RedeliveryPolicy` said, with a log line like:

```
Marking message as dead letter. PERMANENT_ERROR (the RedeliveryPolicy's MessageDeliveryErrorHandler returned
PERMANENT_ERROR; attempt 1 of 1). Error: ... InterruptException: java.lang.InterruptedException
```

The per-consumer path classified it under the policy, so it dead-lettered only when the redelivery cap had been
reached.

**Now:** a delivery that fails after its consumer began stopping is not classified. It is handed back with
`retryMessage(id, cause, Duration.ZERO)`, logged at INFO as `Handler failed after its consumer began stopping;
returning the message for redelivery`, and the next consumer to take it classifies its next failure as usual.

What you may notice:

- **Fewer dead letters after a deploy or restart.** Ones you have seen with `InterruptException` /
  `InterruptedException` in their cause after a shutdown were this bug; they can be resurrected.
- **The hand-back counts as one redelivery attempt**, because `retryMessage` always counts one. A message caught
  by several shutdowns uses one attempt of its budget per shutdown.
- **The handler may run again for the same message.** That was always possible under at-least-once delivery;
  previously the message was parked instead.

Commit `6ba568b5`. Pinned by `DurableQueuesIT.a_message_whose_handler_is_interrupted_by_the_consumer_stopping_is_redelivered_not_dead_lettered`
(PostgreSQL traditional and centralized, both transactional variants, and MongoDB).

---

## 2. New features

No framework features so far. The plugin's are in [§4](#4-the-essentials-claude-code-plugin).

---

## 3. Bug fixes

| Area | Fix | Commit |
|---|---|---|
| Durable queues | A message in flight when its consumer stops is no longer dead-lettered; see [§1.1.1](#111-a-message-in-flight-when-its-consumer-stops-is-redelivered-not-dead-lettered) | `6ba568b5` |
| Durable queues (MongoDB) | Handing such a message back on an interrupted thread used to fail silently: the MongoDB driver refuses a connection on an interrupted thread (`Interrupted waiting for lock`), the failure was logged at DEBUG, and the message stayed marked as being delivered until the stuck-message reset. The hand-back now clears the interrupt flag for its write and restores it afterwards | `6ba568b5` |

---

## 4. The `essentials` Claude Code plugin

0.60.0 shipped plugin `0.60.0-5`. Two plugin releases have followed, both targeting Essentials 0.60.0; the full
entries are in [`essentials-plugin/CHANGELOG.md`](../essentials-plugin/CHANGELOG.md).

**`0.60.0-7`**

- **`/essentials:slice-map-html` — the Slice Atlas.** One self-contained HTML page with five lenses (Atlas,
  Diff, Lineage, Matrix, Metro), Event Modeling colours and a copyable fix prompt per issue. On a folder holding
  several applications it writes one page per application plus an index page. `/essentials:slice-map --html` is
  retired in its favour.
- **`slice.yaml` schema 1.4** adds optional `flow:` (the journeys a slice belongs to) and `scenarios:`
  (given / when / then over the slice's own messages, each linked to its test).
- **`/essentials:slice-enrich`** drafts the absent human-owned manifest fields (`flow`, `scenarios`, `summary`,
  `invariants`, `tier`, `consistency`, `featureFlag`, `maps`) from evidence in the code and tests, and writes
  only what you accept.
- **`slice-source.py` sees more dispatches:** a command sent through a getter's bus
  (`getCommandBus().send(...)`), and one forwarded through an `Inbox` created with
  `Inboxes.getOrCreateInbox(config, commandBus)`. Gate 11(b) now also holds an inbound translation's
  `@KafkaListener` / `@RabbitListener` / `@JmsListener` payloads to its `consumes`.
- **Fixed:** `LLM-eventsourced-aggregates.md` and the Java command test template used a rejection method
  `GivenWhenThenScenario` does not have; they now use `thenFailsWithExceptionType(...)`.

**`0.60.0-6`**

- **`/essentials:upgrade` moves a project to the plugin's Essentials release, code included**, with
  `review-scan.py --tree` detecting the 0.50 → 0.60 changes across a whole project.
- **S8 judges standalone Next.js and Create React App frontends correctly**, and CORS origins bound with
  `@Value` are no longer reported as a startup failure.
- **The slice-manifest lint gate:** `slice-lint.py` no longer passes when it validated nothing, a gate refresh
  can be measured against a baseline before it turns CI red, and the pre-commit hook works in a project below
  the repository root.

---

## 5. Examples

Not published; listed because they are the reference for how to use the framework.

- **`essentials-trading-demo`: `market_data.views.latest_price` projects instead of reading the aggregate.**
  `LatestPriceProjection` is a `ViewEventProcessor` writing `projection_latest_price`, so the view is eventually
  consistent and can be replayed; an existing demo database backfills the table on first start.
  `InstrumentPrice.latestPrice()` is gone. The bootstrap's idempotency probe, which needs a strongly-consistent
  answer, asks `InstrumentPrices.findPrice(...)` on the write side instead of the view. (`9a6e3e86` and the commit after it)
- **`postgresql-cqrs`:** the `task` context, on the aggregate lane, drops its `routing/TaskCommand` marker,
  which only the decider lane uses (`f8e78176`). `AccountBalanceViewIT` now really redelivers an event to test
  the projection's idempotency (`e88f8e70`).
- **Slice manifests** in the trading demo and the three Spring examples are reconciled with the code and carry
  journeys, tiers, scenarios and a feature flag (`5fc606b7`, `6a521f9e`, `cbeb5abd`, `8a77ea89`, `46c0dc41`).

---

## 6. Documentation

- **`LLM/LLM-traps.md`** gains `ESS-119`–`ESS-123` for 0.60 upgrade symptoms: `QueuedMessageCounts` equality,
  the removed CDC live-drain stall alert, and three groups of compile errors (Jackson 2 serializers and WAL
  filters, `useOrderedUnorderedQuery`, the removed 0.50 `forRemoval` members) (`e088534c`).
- **Design documents:** `docs/slice-atlas-design.md` and `docs/slice-enrich-design.md` (`764af560`, `73d1993f`).

---

## 7. Build and tooling

- The devcontainer ships a headless Chromium for the plugin's `render-check`, and the Kotlin language server
  (`396ed9b7`).
- The scheduled scaffold run no longer fails its S2.1 MongoDB row on a stale Compose container (`dfeab2fb`).
- `check-citations` skips paths git ignores (`e61583a8`).
