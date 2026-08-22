# DurableQueues Performance Improvement Plan

**Audience:** An autonomous implementation agent (Opus). This document is self-contained: it describes the current implementation with exact file/line references and verbatim SQL/queries, the bottlenecks, the target designs with code sketches, the phasing, the tests to write, and the acceptance criteria. Where a design decision is left open, the recommended default is stated explicitly — when in doubt, take the recommendation.

**Goal:** drastically reduce (a) **polling overhead** (DB queries issued while queues are idle) and (b) **enqueue→delivery latency** (time from `queueMessage(...)` commit to handler invocation), for both the PostgreSQL and MongoDB `DurableQueues` implementations, without breaking the public `DurableQueues` API or at-least-once delivery and ordered-message (`OrderedMessage`) guarantees.

**Primary inspiration:** the CDC work on the `cdc` branch for `postgresql-event-store` solved exactly this problem for event-store subscribers: NOTIFY-driven polling wake-up (`subscription/notify/` — `NotifyTriggerInstaller`, `NotifyAwareEventStorePollingOptimizer`, `NotifyEpochSource`), WAL-based push delivery, and availability-driven adaptive sources. The queue stack should adopt the *wake-up signal* patterns (S1), not the WAL/CDC machinery — queues mutate rows (claim/ack/retry), so logical-replication delivery is a poor fit; LISTEN/NOTIFY + change streams are the right tools.

---

## 0a. Status against measured evidence — read this before acting on any item below

This document was written on 2026-06-10 against branch `cdc` (HEAD 4f5d8a5e), **before** any of it was
measured. Its per-item numbers are explicitly hypotheses (see §5). Measurements have since been taken, and
they contradict this document in four places. Where the two disagree,
[`durable-queues-redesign-measurements.md`](durable-queues-redesign-measurements.md) and the evidence ledger
in [`durable-queues-v2-design-plan.md`](durable-queues-v2-design-plan.md) are authoritative — not the
estimates below.

| This document says | Measured result | Consequence for the plan |
|---|---|---|
| I4 §5: set `fillfactor = 70`, "enables HOT updates and reduces index churn" | **No effect, marginally worse.** `n_tup_hot_upd` was **zero in every arm**, split included — both schemas index `next_delivery_ts` and `is_being_delivered`, so neither *can* produce a HOT update | Drop the `fillfactor` change. The win in a table split is **index write amplification** — fewer indexes maintained per insert/update/delete — not page headroom or HOT |
| I4: splitting claim paths gives ordered queues batch claim, "×slots per round trip" | Splitting buys **1.07×** on an ordered workload. Insert improves 1.38×, but the ordered claim costs 10.7 s against 2.5 s unordered on identical volume, and the split does not touch it | I4's remaining justification is the **unordered** arm: 1.38× total, 1.62× insert, 6 secondary indexes → 1. Ordered traffic needs a different mechanism |
| (absent — not an item at all) | A **per-key progress cursor** (`completed_through`, gap-tolerant, driven from the key-state table via `LATERAL`) replacing the correlated `NOT EXISTS` barrier: ordered claim **4.0× faster**, ordered workload **2.64×** end to end, 1 secondary index instead of 3, index bytes more than halved. Ack gets 46% more expensive (702 → 1023 ms) because the cursor row must advance too | **This is the load-bearing change and it is missing from I1–I10.** It is owned by `durable-queues-v2-design-plan.md` §3.2. Treat that document as the design of record for the ordered path |
| I6: batch ack, "+20–40% on DRAIN", high confidence on the statement arithmetic | Removing **96% of DELETE statements moved throughput 13%** — and that is a lower bound, because the prototype could not remove the per-message unit of work | The bottleneck is the **per-message operation** (`acknowledgeMessageAsHandled` wraps the interceptor chain in its own unit of work), not the statement count. Batch ack is worth doing, but the UoW-per-message is the bigger lever and it is not an item here either |

Two further corrections of fact:

- **`useOrderedUnorderedQuery` now defaults to `true` on every construction path** (commit 58ccf725). §1.4's
  baseline and I4's framing were written when the builder still defaulted it to `false`, so the "today"
  column of the cost model overstates what a default deployment now pays.
- **Line references throughout are against `cdc`@4f5d8a5e and have drifted.** Re-verify with `grep -n`
  before editing, as the footer already warns.

**What is still unmeasured, and gates everything:** every prototype ratio above comes from raw SQL — single
connection, no consumers, no interceptors, no unit-of-work per message. The measurements doc found that the
per-message *operation* dominates end to end, so those ratios are an **upper bound** on what a full
implementation would show. Quantifying that framework overhead is the next measurement, and it bounds I4,
I5, I6 and the cursor simultaneously.

---

## 0. Constraints you MUST respect (repo conventions)

1. **Read `/LLM/LLM.md` first**, then `/LLM/LLM-foundation.md` (DurableQueues concepts), `/LLM/LLM-postgresql-queue.md`, `/LLM/LLM-springdata-mongo-queue.md` before changing code. CLAUDE.md mandates this.
2. **Zero-dependency philosophy:** third-party deps in integration modules are `provided` scope. Do not add new dependencies. Micrometer, JDBI, Spring Data Mongo are already present in the respective modules.
3. **UnitOfWork pattern:** all DB access goes through `HandleAwareUnitOfWorkFactory` (Postgres/JDBI) or the Spring Mongo UoW factory. Never open raw connections, except where the existing code already does (none in the queue stack).
4. **SQL injection posture:** any configurable table/index/function name must pass `PostgresqlUtil.checkIsValidTableOrColumnName(...)` before interpolation (see `CdcSql` for the pattern). Column values always via bind parameters.
5. **Backward compatibility:** `DurableQueues` (interface, `components/foundation/.../messaging/queue/DurableQueues.java`) is public API used by Inbox/Outbox/`DurableLocalCommandBus`/`EventProcessor`. All improvements must be opt-in via builder flags or additive API, with current behavior as default for at least one release. New semantics (queue-type declarations) must default to today's behavior (`MIXED`).
6. **Schema migration:** the framework creates tables with `CREATE TABLE IF NOT EXISTS` at startup. New columns/indexes/triggers must be added idempotently in the same bootstrap path, guarded by `PostgresqlUtil.acquireBootstrapLock(handle)` (already used; see `PostgresqlDurableQueues.initializeQueueTables()` and the CDC branch's `CdcInboxRepository.createTableAndIndexes()` for the pattern).
7. **Docs:** per CLAUDE.md, every API/behavior change requires updating the module `README.md` AND `/LLM/LLM-<module>.md`. Plan this into every phase.
8. **Commit style:** past tense, backticked type names, grouped by module (see CLAUDE.md §Commit Message Guidelines).
9. **Formatting/licensing:** match `Essentials-Formatter.xml` style (aligned field declarations, `var` usage) and include the Apache license header in new files (copy from any 2026 file).
10. **Tests:** unit tests via Surefire (`mvn test`), integration via Failsafe (`mvn verify`), Testcontainers for Postgres/Mongo. Existing ITs in each module show the harness setup.

---

## 1. Current architecture (verified, with references)

### 1.1 Consumer/polling core (`components/foundation/.../messaging/queue/`)

Two consumption models exist:

**A. Centralized (default for Postgres):** `CentralizedMessageFetcher` (491 lines) runs **one thread** per `DurableQueues` instance with `scheduler.scheduleAtFixedRate(..., 0, pollingIntervalMs, MILLISECONDS)` — `CentralizedMessageFetcher.java:137-152`. Default tick: **20 ms** (`PostgresqlDurableQueuesBuilder.java:64`). Every tick:
1. `calculateAvailableWorkerSlotsPerQueue()` — pure in-memory (`CentralizedMessageFetcher.java:415-443`).
2. If `durableQueues instanceof BatchMessageFetchingCapableDurableQueues` → `fetchNextBatchOfMessages(queueNames, excludeKeysPerQueue, availableWorkerSlotsPerQueue)` (`CentralizedMessageFetcher.java:217-220`). For Postgres this issues **one (or two) SQL statements per registered queue per tick** (see §1.2). A true multi-queue single-statement variant `fetchNextBatchOfMessagesBatched` exists but is **commented out** with the note "Don't use … until it properly handles competing consumers" (`CentralizedMessageFetcher.java:210-221`).
3. Distributes claimed messages to per-consumer worker pools; tracks in-process ordered keys in `inProcessOrderedKeys` (`ConcurrentMap<QueueName, Set<String>>`) so a key being handled locally is excluded from the next fetch (`CentralizedMessageFetcher.java:288-292, 394-403`).

**B. Traditional (per-consumer, used by Mongo and when `useCentralizedMessageFetcher=false`):** `DefaultDurableQueueConsumer` schedules `pollQueue` per parallel consumer with `scheduleAtFixedRate` at the consumer-supplied `pollingInterval` (typically 100 ms), each poll calling `getNextMessageReadyForDelivery` (LIMIT 1). `SimpleQueuePollingOptimizer` (`SimpleQueuePollingOptimizer.java`) backs off on empty polls: increment = 0.5× interval, max = **20× interval**; `messageAdded(...)` (from NOTIFY/change-stream) resets the delay.

`CentralizedQueuePollingOptimizer` exists for per-queue skip decisions in the centralized loop (min 50% of tick, max 20× tick = **400 ms**, ×1.5 on empty, ×0.1 on hit).

**Latency consequence:** an idle queue's optimizer backs off to max. Worst-case enqueue→fetch latency without a wake-up signal is ~400 ms (centralized) or ~2 s (traditional, 100 ms × 20). With NOTIFY wired (opt-in today), `messageAdded` resets the optimizer but the fetcher still waits for its **next fixed tick** — so even the happy path pays up to one full tick (≤20 ms) plus the optimizer-reset race.

**Overhead consequence:** with the default 20 ms tick and N registered queues, an *idle* system still issues `50 × N` claim queries/second against Postgres (100/s for one queue with `useOrderedUnorderedQuery=true`).

### 1.2 PostgreSQL store (`components/postgresql-queue/`)

Table (DDL verbatim, `DurableQueuesSql.getCreateQueueTableSql()`, `DurableQueuesSql.java:488-509`):

```sql
CREATE TABLE IF NOT EXISTS durable_queues (
    id                     TEXT PRIMARY KEY,
    queue_name             TEXT NOT NULL,
    message_payload        JSONB NOT NULL,
    message_payload_type   TEXT NOT NULL,
    added_ts               TIMESTAMPTZ NOT NULL,
    next_delivery_ts       TIMESTAMPTZ,
    delivery_ts            TIMESTAMPTZ DEFAULT NULL,
    total_attempts         INTEGER DEFAULT 0,
    redelivery_attempts    INTEGER DEFAULT 0,
    last_delivery_error    TEXT DEFAULT NULL,
    is_being_delivered     BOOLEAN DEFAULT FALSE,
    is_dead_letter_message BOOLEAN NOT NULL DEFAULT FALSE,
    meta_data              JSONB DEFAULT NULL,
    delivery_mode          TEXT NOT NULL,
    key                    TEXT DEFAULT NULL,
    key_order              BIGINT DEFAULT -1
)
```

Six indexes exist (`DurableQueuesSql.java:517-600`), including partial covering indexes `idx_*_ordered_ready (key, queue_name, key_order, next_delivery_ts) INCLUDE (id) WHERE key IS NOT NULL AND NOT is_dead_letter_message AND NOT is_being_delivered` and `idx_*_unordered_ready (queue_name, next_delivery_ts) INCLUDE (id) WHERE key IS NULL AND ...`.

**Claim query** (default unified strategy, `DurableQueuesSql.buildOrderedSqlStatement`/`fetchNextMessageReadyForDelivery`, executed from `PostgresqlDurableQueues.java:1240-1248`, `LIMIT 1` hardcoded):

```sql
WITH queued_message_ready_for_delivery AS (
    SELECT id FROM durable_queues q1
    WHERE queue_name = :queueName
      AND is_dead_letter_message = FALSE
      AND is_being_delivered = FALSE
      AND next_delivery_ts <= :now
      AND NOT EXISTS (SELECT 1 FROM durable_queues q2
                      WHERE q2.key = q1.key AND q2.queue_name = q1.queue_name
                        AND q2.key_order < q1.key_order)
      {:excludeKeys}
    ORDER BY key_order ASC, next_delivery_ts ASC
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
)
UPDATE durable_queues queued_message SET
    total_attempts = queued_message.total_attempts + 1,
    next_delivery_ts = NULL,
    is_being_delivered = TRUE,
    delivery_ts = :now
FROM queued_message_ready_for_delivery
WHERE queued_message.id = queued_message_ready_for_delivery.id
  AND queued_message.queue_name = :queueName
RETURNING queued_message.*
```

Notes:
- The `NOT EXISTS` anti-join runs **for every candidate row, including unordered ones** (`key IS NULL` makes it vacuously true but the planner still evaluates it; it also blocks index-only plans on the unordered partial index).
- A *dead-lettered or in-flight* lower `key_order` row blocks the entire key — intentional ordering semantics; preserve them.
- `useOrderedUnorderedQuery=true` (builder flag, default `false`, `PostgresqlDurableQueuesBuilder.java:67`) splits into two statements, ordered first then unordered — **two round trips when the ordered set is empty** (`PostgresqlDurableQueues.java:1225-1236`).
- The batch path `fetchNextBatchOfMessages` (`PostgresqlDurableQueues.java:1485-1590`) loops per queue, same statements with per-queue `LIMIT availableSlots`.

**Ack:** `DELETE FROM durable_queues WHERE id = :id AND is_dead_letter_message = FALSE` — one round trip per message (`PostgresqlDurableQueues.java:1122-1147`). If `PostgresqlDurableQueuesStatistics` is installed, an `ON DELETE` trigger inserts a row into `durable_queues_statistics` — **2 write ops per ack** (`PostgresqlDurableQueuesStatistics.java:247-288`).

**Retry / dead-letter:** single UPDATE each (`PostgresqlDurableQueues.java:1017-1086`).

**Stuck-message reset:** `resetMessagesStuckBeingDelivered` is invoked inline at the top of every claim call (`PostgresqlDurableQueues.java:1185`), throttled per queue to once per `messageHandlingTimeout` (default 30 s). The UPDATE filters `is_being_delivered = TRUE AND delivery_ts <= :threshold` — **no index has `(is_being_delivered, delivery_ts)` as a usable prefix**, so this is a scan.

**LISTEN/NOTIFY (exists, opt-in, flawed):** when a `MultiTableChangeListener` is supplied (default: absent), `ListenNotify.addChangeNotificationTriggerToTable(handle, tableName, List.of(INSERT, UPDATE), "id","queue_name","added_ts","next_delivery_ts","delivery_ts","is_dead_letter_message","is_being_delivered")` installs a row trigger (`PostgresqlDurableQueues.java:444-449`), and notifications are routed to `durableQueueConsumer.messageAdded(...)` which resets the polling optimizer (`PostgresqlDurableQueues.java:514-554`, `QueueTableNotification.java`). Problems:
1. The trigger fires on **every UPDATE** — including the claim UPDATE itself (`is_being_delivered=TRUE`), acks-as-updates, retries, stuck-resets. Under load this is a **notification storm**: each claimed message generates a NOTIFY that wakes consumers to find nothing new.
2. The payload carries 7 columns of JSON per row — bandwidth and parse cost.
3. It only *resets the optimizer*; it does not wake the fetcher thread, so latency is still bounded below by the fixed tick.
4. It is opt-in and undocumented as the latency fix it should be.

### 1.3 MongoDB store (`components/springdata-mongo-queue/`)

- **Claim:** single-document `mongoTemplate.findAndModify(query(...).limit(1).with(Sort.by(ASC,"keyOrder, nextDeliveryTimestamp")), update(inc totalDeliveryAttempts, set isBeingDelivered=true, deliveryTimestamp=now))` (`MongoDurableQueues.java:1092-1113`). A **fair per-queue `ReentrantLock` with 1 s `tryLock`** guards the claim to reduce WriteConflicts (`MongoDurableQueues.java:1079-1088`) — a serialization point across parallel consumers.
  - Note the latent bug: `Sort.by(Sort.Direction.ASC, "keyOrder, nextDeliveryTimestamp")` passes **one** property string containing a comma rather than two properties — verify and fix while rewriting (it should be `Sort.by(ASC, "keyOrder").and(Sort.by(ASC, "nextDeliveryTimestamp"))` or `Sort.by(ASC, "keyOrder", "nextDeliveryTimestamp")`).
- **Ordered messages:** after claiming, `resolveIfMessageShouldBeDelivered` (`MongoDurableQueues.java:1190-1304`) issues an **extra query** for lower-`keyOrder` siblings (limit 10) and, on conflict, an extra `updateMulti` that bumps `nextDeliveryTimestamp` (+100 ms cascades) — 2–3 round trips per ordered claim, plus artificial 100 ms delays.
- **Change streams exist and are default-wired** (`startCollectionListener`, `MongoDurableQueues.java:547-598`): filter `operationType in (insert, update, replace)`, routes to `messageAdded` → optimizer reset. Falls back to pure polling on error 136 (DocumentDB). Same flaws as Postgres NOTIFY: fires on claim-updates too, and only resets the optimizer rather than waking a fetcher.
- **No `BatchMessageFetchingCapableDurableQueues` implementation** → Mongo always uses the traditional per-consumer polling model (`MongoDurableQueueConsumer` is a 36-line subclass of `DefaultDurableQueueConsumer`).
- **Stuck reset:** `updateMulti` throttled per queue by `messageHandlingTimeoutMs`, invoked from the claim path (`MongoDurableQueues.java:1090, 1315-1346`).

### 1.4 Derived cost model (what we are fixing)

| Scenario | Today (Postgres, centralized, defaults) | Today (Mongo, traditional) |
|---|---|---|
| Idle, 1 queue | 50 claim queries/s (20 ms tick; optimizer can stretch to 400 ms ⇒ still ≥2.5/s but latency rises to 400 ms) | 10 polls/s at 100 ms interval → backs off to 1 poll/2 s |
| Idle, 20 queues | up to 1 000 queries/s | 20× the above (per consumer thread) |
| Enqueue→delivery latency, idle system | ~10–400 ms (tick + optimizer state), NOTIFY opt-in only resets optimizer | ~50 ms–2 s |
| Claim of 1 ordered message | 1 statement w/ anti-join | 1 findAndModify + 1 sibling query (+ possible updateMulti + 100 ms penalty) |
| Ack of N messages | N DELETEs (+ N stats-trigger INSERTs) | N removes |

Targets after this plan:
- Idle steady state: **≤ 1 claim query per queue per fallback interval (default 1 s)** — i.e. ~99% reduction in idle DB chatter, with the fallback interval safely raisable to 5–30 s because NOTIFY/change-stream wake-ups carry the latency.
- Enqueue→delivery latency (idle system, same DB): **p50 < 10 ms, p99 < 50 ms** (Postgres; Mongo p50 < 25 ms).
- Busy throughput: no regression; batch claim should *increase* messages/s per fetcher round trip.

---

## 2. Improvement plan

Improvements are numbered I1…I10 and grouped into phases (§3). Each lists: design, files, compatibility, tests.

### I1 — Make the wake-up signal first-class and fix the notification storm (Postgres)

**Design:**

1. **New dedicated trigger** that fires only when a row *becomes claimable*, with a minimal payload. Do not reuse `ListenNotify.addChangeNotificationTriggerToTable` (its triggers are unconditional). Add to `DurableQueuesSql`:

```sql
CREATE OR REPLACE FUNCTION {:tableName}_notify_ready() RETURNS trigger AS $$
BEGIN
    PERFORM pg_notify('{:tableName}_ready', NEW.queue_name);
    RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS {:tableName}_ready_trigger ON {:tableName};
CREATE TRIGGER {:tableName}_ready_trigger
AFTER INSERT OR UPDATE ON {:tableName}
FOR EACH ROW
WHEN (NEW.is_being_delivered = FALSE
      AND NEW.is_dead_letter_message = FALSE
      AND NEW.next_delivery_ts IS NOT NULL)
EXECUTE FUNCTION {:tableName}_notify_ready();
```

   - The `WHEN` clause excludes claim UPDATEs (`is_being_delivered=TRUE`, `next_delivery_ts=NULL`) and dead-letter transitions → no storm.
   - Payload = queue name only. PostgreSQL deduplicates identical `(channel, payload)` notifications **within a transaction**, so `queueMessages(...)` batch-enqueueing 10 000 rows in one UoW emits **one** notification per queue. NOTIFY is delivered at COMMIT — exactly when the rows become visible to a claimer; no read-your-own-uncommitted race.
   - Retry/`resetMessagesStuckBeingDelivered` UPDATEs set `is_being_delivered=FALSE` and a `next_delivery_ts` → they correctly re-notify (the message may be due later — see I3's due-time handling).
   - Table/function/trigger names: interpolate only after `PostgresqlUtil.checkIsValidTableOrColumnName(sharedQueueTableName)` (constructor already validates).

2. **Listening**: keep using the existing `MultiTableChangeListener`/`ListenNotify` LISTEN loop if available, but register for the new channel with a tiny payload record. If `MultiTableChangeListener` is generic over JSON payloads, add a `QueueReadyNotification(queueName)` record. Otherwise add a minimal dedicated `Jdbi`-based LISTEN thread in `postgresql-queue` (pattern: `MultiTableChangeListener` in `foundation/postgresql`). **Recommended:** extend `MultiTableChangeListener` usage — it already exists and handles reconnects.

3. **Default-on:** in `PostgresqlDurableQueuesBuilder`, when a `MultiTableChangeListener` is provided, the new trigger replaces the old 7-column trigger for the queue table. Spring Boot starter (`spring-boot-starter-postgresql`) should provide the listener bean by default with property `essentials.durable-queues.notify-wakeup.enabled=true` (default true; document opt-out).

**Compatibility:** the old trigger (if present from a previous deployment) must be dropped during bootstrap: `ListenNotify` triggers follow a deterministic naming scheme — locate it (grep `addChangeNotificationTriggerToTable` for the name template) and `DROP TRIGGER IF EXISTS` it on the queue table when the new wake-up is enabled. Keep `QueueTableNotification` class for one release (deprecated).

**Files:** `DurableQueuesSql.java` (new SQL builders), `PostgresqlDurableQueues.java:431-455` (bootstrap; replace trigger wiring at 444-449), `PostgresqlDurableQueuesBuilder.java` (flag), starter autoconfig in `spring-boot-starter-postgresql`, plus a new `QueueReadyNotification` record.

**Tests:** IT (Testcontainers PG): enqueue in one connection, assert NOTIFY received < 100 ms; assert claim-UPDATE does NOT produce a notification (listen + claim + 200 ms quiet window); batch enqueue of 1 000 in one tx produces exactly 1 notification per queue.

### I2 — Event-driven `CentralizedMessageFetcher` (replace fixed 20 ms tick)

**Design:** replace `scheduleAtFixedRate` with a single-threaded wait-loop driven by a wake-up **epoch** (borrow the design from the CDC branch's `NotifyEpochSource` + `NotifyAwareEventStorePollingOptimizer` in `postgresql-event-store/subscription/notify/` — read those two classes before implementing; they solve the missed-wakeup race you must avoid).

```java
// inside CentralizedMessageFetcher
private final Object wakeMonitor = new Object();
private final AtomicLong wakeEpoch = new AtomicLong(0);     // bumped by wakeUp()
private volatile long fallbackIntervalMs;                    // default 1_000; old behavior = pollingIntervalMs

public void wakeUp(QueueName queueName) {                    // called from NOTIFY/change-stream handler
    pendingWakeQueues.add(queueName);                        // optional fine-grained filtering
    wakeEpoch.incrementAndGet();
    synchronized (wakeMonitor) { wakeMonitor.notify(); }
}

private void runLoop() {
    long seenEpoch = 0;
    while (started.get()) {
        boolean gotMessages = fetchAndDistributeMessages();  // make it return whether anything was claimed
        if (gotMessages) continue;                           // drain mode: immediately refetch while productive
        long epochBeforeWait = wakeEpoch.get();
        if (epochBeforeWait != seenEpoch) { seenEpoch = epochBeforeWait; continue; }  // missed-wakeup guard
        synchronized (wakeMonitor) {
            if (wakeEpoch.get() == seenEpoch) {
                wakeMonitor.wait(fallbackIntervalMs);        // fallback poll covers due-time + lost notifications
            }
        }
        seenEpoch = wakeEpoch.get();
    }
}
```

Key properties:
- **Drain mode:** while a fetch returns messages, loop immediately (no sleep) → busy throughput limited by DB and workers, not the tick.
- **Epoch guard:** a NOTIFY arriving between `fetchAndDistributeMessages()` returning empty and `wait()` is never lost.
- **Fallback poll** (default **1 000 ms**, builder-tunable `fallbackPollingInterval`) covers: lost notifications (LISTEN reconnects), messages with future `next_delivery_ts` (redeliveries), and multi-instance edge cases. With I3 it can be raised to 5–30 s.
- Keep the public `Lifecycle` contract; start the loop on a dedicated daemon thread (name `DurableQueues-CentralizedMessageFetcher` as today).
- `CentralizedQueuePollingOptimizer` becomes largely redundant; keep it functioning for compatibility but the loop no longer needs per-queue backoff (the wake-up + fallback replaces it). `shouldSkipPolling()` consultation in the store's batch fetch may remain.
- When **no** wake-up source is wired (no `MultiTableChangeListener`), set `fallbackIntervalMs = pollingIntervalMs` (20 ms default) → behavior identical to today. This is the compatibility story.

**Worker-slot wake-up:** also call `wakeUp()` when a worker finishes a message and frees a slot **iff** the last fetch was slot-limited — otherwise a queue with a backlog but saturated workers waits a full fallback interval after the workers free up. Track `lastFetchWasSlotLimited` per queue in the fetcher; hook the `finally` block of `processMessage` (`CentralizedMessageFetcher.java:393-406`).

**Files:** `CentralizedMessageFetcher.java` (core rewrite, keep class name/API), `PostgresqlDurableQueues.java:514-554` (notification handler → `centralizedMessageFetcher.wakeUp(queueName)` in addition to the existing `messageAdded` optimizer reset for traditional consumers), `PostgresqlDurableQueuesBuilder` (new `fallbackPollingInterval`).

**Tests:** unit-test the loop with a fake `DurableQueues`: (a) wakeUp during fetch → immediate refetch, no wait; (b) idle → exactly one fetch per fallback interval; (c) drain mode → continuous fetches while messages flow; (d) missed-wakeup race via latches. IT: end-to-end enqueue→handler latency p50 < 10 ms on idle system (Awaitility, generous CI bounds: assert < 250 ms but *record* the measured latency in logs).

### I3 — Due-time aware fallback (timer wheel for redeliveries)

**Problem:** retried messages carry a future `next_delivery_ts`; NOTIFY fires at retry-write time, not at due time. With a long fallback interval, a message due in 250 ms would wait for the fallback poll.

**Design:** maintain a per-queue `nextKnownDueTimeMs` (volatile long) inside `CentralizedMessageFetcher`:
- When the store reports "no claimable messages but earliest future `next_delivery_ts` = T" — extend `fetchNextBatchOfMessages` to return that watermark (cheap: the claim query already scans the ready index; add a second lightweight query `SELECT min(next_delivery_ts) FROM durable_queues WHERE queue_name = :q AND is_dead_letter_message = FALSE AND is_being_delivered = FALSE AND next_delivery_ts > :now` — index-only on `idx_*_unordered_ready`/`ordered_ready`; only run it when the claim returned empty AND a wake-up for that queue had fired, not on every fallback poll).
- The run-loop waits `min(fallbackIntervalMs, nextDueTimeAcrossQueues - now)`.
- On NOTIFY for a queue (which fires for retry-writes per I1's trigger), refresh that queue's watermark on the next fetch.

This makes redelivery latency precise without shortening the global fallback. `queryForMessagesSoonReadyForDelivery` (`PostgresqlDurableQueues.java:1362-1376`) already exists and can be reused.

**Files:** `CentralizedMessageFetcher.java`, `BatchMessageFetchingCapableDurableQueues` (additive default method returning `Optional<Instant> earliestFutureDelivery(QueueName)`), `PostgresqlDurableQueues.java`.

**Tests:** retry with 300 ms delay; assert redelivery happens at 300 ms ± 100 ms, not at the fallback boundary (set fallback = 10 s in the test).

### I4 — Split claim paths: `UNORDERED` / `ORDERED` / `MIXED` queue types (Postgres)

This is the "split into OrderedQueue and UnorderedQueues" ask. Splitting the *API surface* into two interfaces would break every existing call site (Inbox/Outbox/DurableLocalCommandBus all take `DurableQueues`); instead, split the **queue declaration and the claim path** — same wins, no breakage.

> **Measured, and the scope narrowed — see §0a.** The unordered half holds up (1.38× total, 1.62× insert,
> 6 secondary indexes → 1). The ordered half does not: splitting buys 1.07×, because the cost is the
> correlated `NOT EXISTS` barrier and a split does not touch it. The ordered path is now designed as a
> **per-key cursor** in `durable-queues-v2-design-plan.md` §3.2 (4.0× on the claim), which also makes the
> `DISTINCT ON (key)` head-claim sketched in step 3 below — and its key-cardinality risk, §5's most
> uncertain estimate — unnecessary. Read step 3 as a recorded alternative, not the plan.

**Design:**

1. **Foundation API (additive):**

```java
public enum QueueType { UNORDERED, ORDERED, MIXED }   // MIXED = today's behavior, the default

// DurableQueues (default method, additive):
default void configureQueue(QueueName queueName, QueueType type) {}
```

   `ConsumeFromQueue` builder gets an optional `queueType` hint as well. The store keeps a `ConcurrentMap<QueueName, QueueType>` (in-memory registry; persisting it is unnecessary — it is a *performance hint*, and `MIXED` is always safe). **Enforcement:** when a queue is declared `UNORDERED`, reject `queueMessage(...)` of an `OrderedMessage` with `IllegalArgumentException` (fail-fast prevents silent ordering loss); `ORDERED` queues accept both but warn on non-keyed messages.

2. **Unordered claim (no anti-join, true batch):**

```sql
WITH ready AS (
    SELECT id FROM durable_queues
    WHERE queue_name = :queueName
      AND key IS NULL
      AND is_dead_letter_message = FALSE
      AND is_being_delivered = FALSE
      AND next_delivery_ts <= :now
    ORDER BY next_delivery_ts
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
)
UPDATE durable_queues q SET
    total_attempts = q.total_attempts + 1,
    next_delivery_ts = NULL,
    is_being_delivered = TRUE,
    delivery_ts = :now
FROM ready WHERE q.id = ready.id
RETURNING q.*
```

   Planner: pure index scan on `idx_*_unordered_ready` (already exists, partial + covering). `LIMIT :limit` = available worker slots (batch claim — today's centralized path already passes slots; the single-consumer path should claim `min(slots, prefetch)` with a small prefetch buffer in the consumer, default 1 to preserve semantics, builder-tunable).

3. **Ordered claim (per-key head selection — replaces the O(candidates × anti-join) pattern AND the client-side exclusion lists for cross-instance coordination):**

```sql
WITH heads AS (
    -- the true head (lowest key_order) of every key in this queue, regardless of status:
    -- a dead-lettered or in-flight head must BLOCK its key (today's semantics)
    SELECT DISTINCT ON (key) id, key, key_order, next_delivery_ts,
           is_being_delivered, is_dead_letter_message
    FROM durable_queues
    WHERE queue_name = :queueName AND key IS NOT NULL
    {:excludeKeys}                      -- AND key NOT IN (<inProcessKeysOfThisInstance>)
    ORDER BY key, key_order
),
claimable AS (
    SELECT id FROM heads
    WHERE is_being_delivered = FALSE
      AND is_dead_letter_message = FALSE
      AND next_delivery_ts <= :now
    ORDER BY key_order
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
)
UPDATE durable_queues q SET
    total_attempts = q.total_attempts + 1,
    next_delivery_ts = NULL,
    is_being_delivered = TRUE,
    delivery_ts = :now
FROM claimable WHERE q.id = claimable.id
RETURNING q.*
```

   - `DISTINCT ON (key) … ORDER BY key, key_order` walks `idx_*_ordered_msg (queue_name, key, key_order)` — verify with `EXPLAIN` in the IT (assert no Seq Scan; Postgres ≥ 17 does index skip-scans for DISTINCT ON well; for older versions the index scan is still bounded by total rows of the queue, equal to today's cost but **once per fetch instead of per candidate row**).
   - Correctness notes: (a) a head that is `is_being_delivered=TRUE` (claimed by another instance) correctly blocks its key without any client-side exclusion-list exchange between instances — strictly better than today, where cross-instance protection relies on `FOR UPDATE SKIP LOCKED` of the *blocking row only while the claim transaction is open*; (b) keep the local `excludeKeys` for keys whose handler is still running in *this* JVM after the claim transaction committed; (c) `FOR UPDATE SKIP LOCKED` in `claimable` resolves same-instant races between instances — a skipped head simply doesn't get claimed this round.
   - **This claim can return multiple messages (different keys) in one statement** — ordered queues finally get batch claim.
   - Edge case to unit-test: two rows with same `(key, key_order)` (duplicate enqueue) — `DISTINCT ON` picks one arbitrarily; today's anti-join (`<`, strict) lets both through eventually. Acceptable; document it.

4. **MIXED queues** run *both* statements (ordered first, then unordered with remaining limit) — this replaces the unified anti-join query entirely. The unified query (`buildOrderedSqlStatement`'s `NOT EXISTS` variant) and the `useOrderedUnorderedQuery` flag are deprecated: with queue types, `MIXED` always uses the split pair, but **skips the ordered statement when the store has never seen an `OrderedMessage` for that queue** (track a `ConcurrentMap<QueueName, Boolean> hasSeenOrderedMessages`, seeded lazily with `SELECT EXISTS(SELECT 1 FROM durable_queues WHERE queue_name=:q AND key IS NOT NULL)` on first fetch) — this removes the 2× round-trip cost for de-facto-unordered queues without any user action.

5. **Index consolidation** (after the new claim paths are in): `idx_*_next_msg` and `idx_*_ready` become redundant (superseded by the two partial covering indexes); drop them in bootstrap with `DROP INDEX IF EXISTS` and a release-note. Add the missing stuck-reset index:

```sql
CREATE INDEX IF NOT EXISTS idx_{:tableName}_stuck ON {:tableName} (delivery_ts)
WHERE is_being_delivered = TRUE
```

   ~~Also set `ALTER TABLE {:tableName} SET (fillfactor = 70)` at creation time (new tables only) — every message is updated at least once (claim) before deletion; fillfactor headroom enables HOT updates and reduces index churn. Measure in perf-lab before/after.~~ **Measured and dropped — see §0a.** `fillfactor = 80` made no difference and was marginally worse, and `n_tup_hot_upd` was zero in every arm: the claim update writes `is_being_delivered` and `next_delivery_ts`, both of which are indexed, so it can never be HOT no matter how much page headroom it is given. Removing those columns from the indexes is a prerequisite to even asking the question, and index consolidation (above) is the win that is actually there.

**Files:** `DurableQueues.java` (enum + default method), `DurableQueuesSql.java` (two new statement builders; deprecate the unified one), `PostgresqlDurableQueues.java` (claim dispatch by queue type; `hasSeenOrderedMessages` tracking; bootstrap index changes), `PostgresqlDurableQueuesBuilder.java`, `Inbox`/`Outbox` configuration plumbing in `foundation` so `Inboxes`/`Outboxes` can declare their queue type (they know whether they use ordered messages — check `dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward`).

**Tests:**
- Unit: SQL builder output snapshots; queue-type registry; fail-fast on `OrderedMessage` → `UNORDERED` queue.
- IT (PG): ordering invariant under 4 competing consumer instances × 1 000 ordered messages across 50 keys — assert strict per-key order at the handler, no duplicates (this test exists in some form — find `Ordered` ITs in `postgresql-queue/src/test` and extend rather than duplicate); dead-lettered head blocks its key, other keys proceed; `EXPLAIN (FORMAT JSON)` assertion that claims use the partial indexes (no Seq Scan) with 100k-row fixture.
- Perf IT (optional, tagged): 100k unordered backlog drained with batch claim vs LIMIT 1 — assert ≥ 5× round-trip reduction via statement-count interceptor.

### I5 — Multi-queue single-statement claim (finish `fetchNextBatchOfMessagesBatched`)

**Design:** the commented-out call (`CentralizedMessageFetcher.java:210-221`) and WIP SQL (`PostgresqlDurableQueues.java:1595-1696`, `buildBatchedSqlStatement` at `DurableQueuesSql.java:593+`) attempted one statement for all queues. With I4's split paths the safe version is a `LATERAL` join over a `VALUES` list of (queue, limit) pairs, per claim family:

```sql
WITH targets(queue_name, max_messages) AS (VALUES (:q1, :l1), (:q2, :l2), ...),
ready AS (
    SELECT picked.id FROM targets t
    CROSS JOIN LATERAL (
        SELECT id FROM durable_queues
        WHERE queue_name = t.queue_name AND key IS NULL
          AND is_dead_letter_message = FALSE AND is_being_delivered = FALSE
          AND next_delivery_ts <= :now
        ORDER BY next_delivery_ts
        LIMIT t.max_messages
        FOR UPDATE SKIP LOCKED
    ) picked
)
UPDATE durable_queues q SET ... FROM ready WHERE q.id = ready.id RETURNING q.*
```

The historical competing-consumer concern ("Don't use … until it properly handles competing consumers") is addressed because `FOR UPDATE SKIP LOCKED` sits **inside** the lateral subquery (locks acquired per-row at scan time, skipped rows simply excluded) — but you MUST reproduce the original bug first: find the referenced Bug #19 scenario (`CentralizedMessageFetcher.java:430-434` comment), write the failing multi-pod IT before enabling, and verify the lateral form passes it. Gate behind builder flag `useMultiQueueClaim` (default false in the first release, flipped to true after a soak release).

**Win:** one round trip per fetch cycle regardless of queue count (today: N or 2N). Combined with I2's drain loop this is the steady-state busy path.

**Files:** `DurableQueuesSql.java`, `PostgresqlDurableQueues.java`, `CentralizedMessageFetcher.java` (re-enable the threshold branch; make threshold builder-tunable, default 1 — always batch when flag on).

**Tests:** 2-instance competing-consumer IT (Testcontainers, two `PostgresqlDurableQueues` instances on one DB): no duplicate deliveries, both instances make progress; ordered-across-instances invariant holds.

### I6 — Batch acknowledge / batch retry (Postgres + foundation)

> **Measured, and the mechanism was misattributed — see §0a.** Removing 96% of the DELETE statements moved
> throughput **13%**, not the 20–40% estimated below, and that figure is a lower bound only because the
> prototype could not remove the per-message unit of work. The statement arithmetic is right; the conclusion
> drawn from it is wrong. What dominates is the per-message *operation* — `acknowledgeMessageAsHandled`
> wrapping the interceptor chain in its own `UnitOfWork`, and the connection acquisition that comes with it.
> Batch ack is still worth doing, but on its own it is a 1.13× change, and the UoW-per-message is the lever
> this plan never named.

**Design:** workers currently issue one DELETE per ack from each worker thread (`CentralizedMessageFetcher.java:344`). Add a small **ack buffer** in the store:

- `DurableQueues` additive API: `default void acknowledgeMessagesAsHandled(Collection<QueueEntryId> ids)` with a loop fallback default implementation.
- Postgres: `DELETE FROM durable_queues WHERE id = ANY(:ids) AND is_dead_letter_message = FALSE`.
- In `CentralizedMessageFetcher.processMessage`, replace direct ack with `ackBatcher.submit(id)` — a tiny coalescing buffer (flush when ≥ 64 ids or 5 ms elapsed, whichever first, on a dedicated single thread; both tunable). **Failure semantics are preserved**: if the flush fails, ids stay claimed (`is_being_delivered=TRUE`) and the stuck-reset resurrects them — same as today's single-ack failure path (`CentralizedMessageFetcher.java:351-358`). At-least-once is unchanged; the redelivery window widens by ≤ 5 ms.
- Make it opt-in via builder `useBatchedAcknowledgement` (default false initially).
- Statistics: change the per-row `ON DELETE` trigger cost note in docs; with `DELETE … WHERE id = ANY(...)` the trigger still fires per row — acceptable, but document that disabling `PostgresqlDurableQueuesStatistics` removes 50% of ack write cost for hot paths.

**Tests:** unit-test the batcher (size flush, time flush, failure → no loss because stuck-reset IT covers resurrect); throughput IT comparing acks/s.

### I7 — Mongo: event-driven consumption + batch claim

**Design (mirror of I1/I2/I4 within Mongo's constraints):**

1. **Change stream → fetcher wake-up:** today the stream handler calls `messageAdded` (optimizer reset only). Implement `BatchMessageFetchingCapableDurableQueues` for `MongoDurableQueues` and route it through the (now event-driven) `CentralizedMessageFetcher` (I2 — the fetcher is store-agnostic). Filter the change stream server-side to *ready* transitions to kill the storm — extend the aggregation filter (`MongoDurableQueues.java:556-560`):

```java
match(new Criteria().andOperator(
    where("operationType").in("insert", "update", "replace"),
    where("fullDocument.isBeingDelivered").is(false),
    where("fullDocument.isDeadLetterMessage").is(false)))
```

   (requires `fullDocument: updateLookup` on the request builder — check `ChangeStreamRequest.builder().fullDocument(FullDocument.UPDATE_LOOKUP)`; measure: updateLookup adds a read per event server-side, still far cheaper than client-side wakeup storms. For inserts `fullDocument` is always present.)

2. **Batch claim with claim-token (3 round trips for N messages instead of N `findAndModify`):**

```java
var claimId = UUID.randomUUID().toString();
// 1) candidate ids (no lock):
var ids = mongoTemplate.find(query(readyCriteria).with(sort).limit(slots)
            .fields().include("_id"), ..., collection);
// 2) optimistic claim — conditional update re-checks claimability per doc:
mongoTemplate.updateMulti(
    query(where("_id").in(ids).and("isBeingDelivered").is(false)
          .and("isDeadLetterMessage").is(false)
          .and("nextDeliveryTimestamp").lte(now)),
    new Update().set("isBeingDelivered", true).set("deliveryTimestamp", now)
                .set("claimId", claimId).inc("totalDeliveryAttempts", 1),
    collection);
// 3) fetch what WE claimed (races lose silently — their docs were claimed by another instance):
var claimed = mongoTemplate.find(query(where("claimId").is(claimId)), DurableQueuedMessage.class, collection);
```

   - Add a `claimId` field to `DurableQueuedMessage` (nullable; cleared on retry/reset). No index needed if step 3 runs immediately (collection scan risk: add `{claimId: 1}` sparse index to be safe — cheap because sparse).
   - This *also removes the per-queue `ReentrantLock`* (`MongoDurableQueues.java:1079-1088`) for the batch path: WriteConflict pressure drops because `updateMulti` with disjoint candidate sets conflicts rarely; keep the lock only for the legacy single-claim path.
   - Keep `findAndModify` single-claim as the fallback (`getNextMessageReadyForDelivery` unchanged) for API compatibility and `FullyTransactional` mode (multi-statement claim inside a Mongo transaction is fine too, but don't block on it: batch claim requires `SingleOperationTransaction` mode initially — fail-fast with a clear message otherwise).

3. **Ordered messages, head-selection via aggregation** (replaces post-claim sibling checks + 100 ms reorder penalty): candidate step (1) for ordered queues becomes an aggregation:

```java
// per queue: heads = lowest keyOrder per key, claimable only if head is ready
Aggregation.newAggregation(
    match(where("queueName").is(queueName).and("key").ne(null)),
    sort(ASC, "key", "keyOrder"),
    group("key").first("$$ROOT").as("head"),
    replaceRoot("head"),
    match(where("isBeingDelivered").is(false)
          .and("isDeadLetterMessage").is(false)
          .and("nextDeliveryTimestamp").lte(now)
          .and("key").nin(localInProcessKeys)),
    sort(ASC, "keyOrder"),
    limit(slots),
    project("_id"))
```

   Then steps 2–3 as above. Delete `resolveIfMessageShouldBeDelivered`'s reordering writes for the batch path (the head-selection makes them unnecessary); keep the dead-letter-cascade behavior: when a head is dead-lettered, the `match` simply blocks the key — same semantics as Postgres. **Migration note:** the current code *advances* `nextDeliveryTimestamp` of out-of-order successors (+100 ms); the new path never delivers out of order in the first place, so that machinery (`MongoDurableQueues.java:1190-1304`) becomes dead code for batch-claim queues — remove it only for the new path, leave the legacy path intact.
   - Fix the `Sort.by(ASC, "keyOrder, nextDeliveryTimestamp")` single-string bug (§1.3) in the legacy path regardless.

4. **Fallback polling:** identical fallback-interval story as I2; when change streams are unavailable (DocumentDB error 136, already detected at `MongoDurableQueues.java:580`), set fallback = configured `pollingInterval` (today's behavior).

**Files:** `MongoDurableQueues.java` (implement `BatchMessageFetchingCapableDurableQueues`, claim-token claim, aggregation head-select, stream filter, builder knobs), `MongoDurableQueueConsumer.java` (route through centralized fetcher when enabled), foundation `CentralizedMessageFetcher` is reused as-is from I2.

**Tests (IT, Testcontainers Mongo replica set — change streams need a replica set; check existing Mongo ITs for the container setup):** ordering invariant under 4 consumers × 50 keys; claim-token race test with two `MongoDurableQueues` instances (no doc claimed twice — assert by `totalDeliveryAttempts`); change-stream wake-up latency < 50 ms; DocumentDB-fallback simulation (disable change streams flag → still functions at polling cadence).

### I8 — Payload column: stop paying JSONB tax (Postgres, opt-in)

**Design:** `message_payload JSONB` forces Postgres to parse/validate/re-serialize JSON on every INSERT and reassemble on claim; queue payloads are opaque to SQL (no queries ever filter on payload content — verified: no `message_payload ->` usage in `DurableQueuesSql`). New tables get `message_payload TEXT NOT NULL` (or keep JSONB for existing tables — column type is detected at bootstrap via `information_schema.columns` and the row mapper handles both). Builder flag `payloadColumnType` (JSONB default for compat; TEXT recommended in docs for new deployments). Measure in perf-lab: expect 10–25% enqueue CPU reduction for KB-size payloads.

**Files:** `DurableQueuesSql.java` (DDL), `PostgresqlDurableQueues.java` (bootstrap detection), `QueuedMessageRowMapper.java` (type-agnostic read — `rs.getString` works for both), docs.

**Tests:** IT matrix runs the full queue test suite against both column types (parameterized container fixture).

### I9 — Stuck-reset off the hot path

**Design:** `resetMessagesStuckBeingDelivered` is invoked inside every claim call (`PostgresqlDurableQueues.java:1185`, `MongoDurableQueues.java:1090`) even though throttled. Move it to a dedicated scheduled task per store instance (period = `messageHandlingTimeout / 2`, jittered ±10%), using the new partial index from I4. Remove the per-claim invocation and the `lastResetStuckMessagesCheckTimestamps` map. Multi-queue variant (`resetMessagesStuckBeingDeliveredAcrossMultipleQueues`, `PostgresqlDurableQueues.java:1769-1813`) becomes the only implementation (one UPDATE for all queues). After a successful reset that resurrects rows, call `centralizedMessageFetcher.wakeUp(...)` for the affected queues (the I1 trigger also fires on these UPDATEs — belt and braces; dedupe via epoch).

**Files:** `PostgresqlDurableQueues.java`, `MongoDurableQueues.java`, both builders (`stuckMessageResetInterval`).

**Tests:** kill a handler mid-message (sleep > timeout), assert resurrection within `messageHandlingTimeout + resetInterval`; assert zero reset queries issued from the claim path (statement-counting interceptor).

### I10 — Observability for the new paths (apply CDC metric conventions)

Add Micrometer meters mirroring the CDC naming style (`essentials.cdc.*` → `essentials.queues.*`), all tagged `queue`:
- `essentials.queues.wakeups` (counter; tag `source` = `notify|changestream|fallback|slotfreed|duetime`)
- `essentials.queues.fetch.latency` (timer), `essentials.queues.fetch.batch_size` (summary)
- `essentials.queues.enqueue_to_claim.latency` (timer — computed claim-side as `now − added_ts` for first attempts only, `total_attempts == 1`)
- `essentials.queues.claim.statements` (counter — proves the idle-overhead win)
- `essentials.queues.ack.batch_size` (summary, I6)

**Caution (learned in this branch's review):** never register the same meter name with inconsistent tag sets — Prometheus registries throw. Pick the tag set once per name.

**Files:** `CentralizedMessageFetcher.java`, both stores. Wire into `DurableQueuesMicrometerInterceptor` where natural.

---

## 3. Phasing and dependency order

| Phase | Items | Risk | Expected win |
|---|---|---|---|
| **P0** | I1 (trigger + default-on), I2 (event-driven fetcher), I10 (metrics) | Low — additive, old behavior behind absent listener | Idle queries −95%+, idle-enqueue latency 400 ms → <10 ms |
| **P1** | I3 (due-time), I9 (stuck-reset off hot path) | Low | Precise redelivery; claim path purity |
| **P2** | I4 (queue types + split claims + index work) | Medium — claim-SQL rewrite; mitigated by `MIXED` default + invariant ITs | Ordered batch claim; anti-join gone; 2× round-trip gone |
| **P3** | I5 (multi-queue statement, flag off), I6 (batch ack, flag off) | Medium — must reproduce Bug #19 first | Busy-path round trips → 1 per cycle |
| **P4** | I7 (Mongo: batch claim + streams + ordered aggregation) | Medium-high — claim-token concurrency | Mongo reaches parity |
| **P5** | I8 (payload TEXT), perf-lab validation (§4), docs sweep | Low | CPU/storage; proof |

Each phase = one PR, compiling and green (`mvn verify -pl components/foundation,components/postgresql-queue,components/springdata-mongo-queue -am`) before the next. Within each PR update: module `README.md`s, `/LLM/LLM-foundation.md`, `/LLM/LLM-postgresql-queue.md`, `/LLM/LLM-springdata-mongo-queue.md`, and the starter docs for new properties.

## 4. Validation in the performance lab

`examples/essentials-performance-lab` (added on this branch) already has `DurableQueueBenchmarkScenario` and a scenario harness (`scenario/LabScenario.java`, `ScenarioRunner.java`, run scripts under `scripts/`). Add three scenarios (copy the structure of `BaselinePollingVsCdcScenario`):

1. **`QueueIdleOverheadScenario`** — 20 queues, zero traffic, 60 s; report claim-statement count (from `essentials.queues.claim.statements`) and DB CPU; run with wake-up on/off.
2. **`QueueLatencyScenario`** — single enqueue every 2 s for 60 s on an idle system; report p50/p99 `enqueue_to_claim.latency`; matrix over {fallback 1 s/10 s} × {wakeup on/off}.
3. **`QueueThroughputScenario`** — 100k unordered + 100k ordered (200 keys) backlog drain; matrix over {batch claim on/off} × {batch ack on/off} × {multi-queue statement on/off}; report msgs/s and statements/msg.

Acceptance criteria (same DB container, same machine):
- Idle: ≤ 1.2 claim statements/queue/fallback-interval (wakeup on).
- Latency: p50 < 10 ms, p99 < 50 ms (PG, wakeup on, fallback 10 s) — vs current baseline you must first record.
- Throughput: ≥ 2× msgs/s on the ordered drain; statements/msg ≤ 0.15 with I5+I6 on (vs ~2.0+ today: claim + ack per message).
- All existing ITs in the three modules pass unchanged with default flags.
- Ordering invariant suites (I4/I7) pass 50 consecutive runs (`mvn verify -Dit.test=*Ordered* -DrerunFailingTestsCount=0` in a loop) — ordering bugs are flaky by nature; do not accept a single green run.

## 5. Expected impact per improvement — critical assessment and verification plan

This section estimates, **per improvement**, the realistic effect on the three target dimensions — **latency** (enqueue→handler), **load** (DB statements + server work while idle/busy), and **throughput** (messages/s at saturation) — together with the confidence in each estimate, what could erode the win, and the exact performance-lab test that confirms or falsifies it. Estimates are derived from the cost model in §1.4, not measured: **treat every number as a hypothesis until the corresponding lab test has produced a baseline-vs-treatment result.** Where an estimate has low confidence, that is stated — do not silently inherit these numbers into docs or release notes.

**Measurement protocol (applies to every test below):** (1) record the *baseline* on current `main`+`cdc` code with default flags before implementing anything — the numbers in §1.4 must be reproduced first; (2) same machine, same Testcontainers image, same JVM flags; (3) ≥ 3 runs per cell, report median; 60 s warmup excluded; (4) measure load via a **statement-counting `DurableQueuesInterceptor`** (and `pg_stat_statements` / Mongo `commandMonitoring` as ground truth), never wall-clock alone; (5) measure latency by stamping `added_ts` at enqueue and recording `now − added_ts` in the handler for `total_attempts == 1` (the `essentials.queues.enqueue_to_claim.latency` timer from I10), reporting p50/p99/p99.9 histograms; (6) every A/B flips exactly one builder flag.

**Test-type taxonomy used below:**
- **IDLE** — idle-overhead soak: N queues, zero traffic, 60–300 s; count claim statements and notifications.
- **PROBE** — cold-injection latency probe: single message into a fully idle (backed-off) system, repeated every 2–5 s; latency histogram.
- **DRAIN** — backlog drain: pre-load 100k+ messages, start consumers, measure msgs/s and statements/msg.
- **STEADY** — closed-loop steady state: producers at fixed rate (e.g. 1k/5k msg/s), measure sustained throughput, lag, latency under load.
- **SWEEP** — parameter sweep: vary one dimension (queue count, ordered-key cardinality, payload size, table size) and plot the curve.
- **SOAK-MULTI** — 2+ `DurableQueues` instances on one DB: duplicate-delivery and ordering-invariant assertions plus fairness (per-instance share).
- **FAULT** — fault injection: kill the LISTEN/change-stream connection, kill a handler mid-message, kill between handle and ack-flush.

| # | Latency (idle p99) | Idle load | Throughput | Confidence |
|---|---|---|---|---|
| I1 trigger+storm fix | enabler (see I2); alone: ~400 ms → ≤ 1 tick (20 ms) | notifications: −90%+ under load; claim queries unchanged | ~0 | High |
| I2 event-driven fetcher | ~400 ms → **< 10 ms** (with I1) | claim stmts **−95…99%** | drain mode: removes tick ceiling (~800 msg/s/queue @ batch 16) | High |
| I3 due-time wakeup | redelivery precision: ±fallback → ±100 ms | +1 cheap query per woken-empty fetch | ~0 | High |
| I4 split claims | claim-stmt latency −20…50% (unordered), variable (ordered) | MIXED 2×-query cost removed for de-facto-unordered queues | ordered: ×slots per round trip; unordered: enables LIMIT N | **Medium — must verify plans** |
| I5 multi-queue stmt | busy fetch cycle: N×RTT → 1×RTT | stmts/cycle N → 1 | fetcher ceiling ×N queues | Medium |
| I6 batch ack | +≤5 ms *post-handler* only | ack stmts ÷64 | statements/msg ~2.0 → ~0.1–0.3 (with I5) | High |
| I7 Mongo parity | 100 ms–2 s → **< 50 ms**; ordered −100 ms penalty | polls → ~0 idle | claim RTTs ÷5 (batch 16: 16→3); lock removal unblocks parallelism | Medium |
| I8 payload TEXT | ~0 | enqueue CPU −10…25% (KB payloads) | enqueue-bound workloads only | Medium-low |
| I9 stuck-reset off path | p99.9 tail jitter removal only | reset scan O(table) → O(in-flight) | ~0 | High |
| I10 metrics | ~0 (verify < 1 µs/op) | ~0 | ~0 | High |

### I1 — NOTIFY trigger + storm fix

- **Latency:** standalone (without I2) the wake-up only resets the optimizer, so the win is bounded by the fetcher tick: idle-system p99 drops from ~400 ms (optimizer at max backoff) to ≤ 20 ms. The full latency win is unlocked by I2; I1 is its enabler. Confidence: high — the mechanism is deterministic.
- **Load:** the `WHEN` clause is the real win under load: today's opt-in trigger fires on *every* UPDATE, so at 1k msg/s the system generates ≥ 2k notifications/s (claim + ack/retry writes), each parsed by every listening JVM. The new trigger fires roughly per *enqueue commit per queue* (intra-tx dedupe). Expect notifications/s ≈ commit rate, a −90%+ reduction on busy systems. Idle claim-query load is **unchanged** by I1 alone.
- **Cost side:** the trigger adds a per-row `pg_notify` evaluation on the enqueue path. Expect single-digit µs/row; must be measured — if batch enqueue of 10k rows shows > 5% INSERT regression, move the notify to a statement-level trigger with a transition table.
- **Eroders:** listeners on many JVMs each pay the LISTEN wakeup; cross-tx enqueue bursts (many small txs) don't dedupe.
- **Lab tests:** (a) **PROBE** with I1 on / I2 off — assert p99 ≤ 2× tick; (b) notification-count **STEADY** at 1k msg/s comparing old trigger vs new (counter on the listener side); (c) enqueue-cost **SWEEP**: `queueMessages` batch INSERT rate with trigger absent / old / new.

### I2 — Event-driven fetcher

- **Latency:** idle-system enqueue→claim becomes NOTIFY delivery (~sub-ms on same host) + one claim round trip. p50 < 10 ms, p99 < 50 ms is realistic against a local container; over a network DB add ~2×RTT. This is the headline number of the whole plan. Confidence: high.
- **Load:** idle claim statements drop from `(1000/tick) × N queues`/s (≈ 50/s/queue, §1.4) to `N / fallbackInterval` (≈ 1/s/queue at the 1 s default, ~0.03/s at a 30 s fallback). That is a **−95…99.9%** reduction — the single biggest load win available.
- **Throughput:** drain mode removes the inter-batch tick gap. Today the tick caps a queue at `batchSize × (1000/tick)` claims/s in the best case but in practice the optimizer's backoff bites after any empty poll. Post-change the ceiling is the claim round-trip: ~`batchSize / RTT` per fetcher thread (e.g. 16 / 1 ms = 16k msg/s). **Critical caveat:** the fetcher is one thread; with slow DB RTT it becomes the bottleneck — I5 raises this ceiling, and if still insufficient the design permits per-queue-group fetcher sharding later (out of scope).
- **Eroders:** lost LISTEN connection silently degrades latency to the fallback interval (FAULT test mandatory); GC pauses on the fetcher thread directly add to latency.
- **Lab tests:** (a) **IDLE** with 20 queues — assert ≤ 1.2 claim stmts/queue/fallback; (b) **PROBE** matrix {wakeup on/off} × {fallback 1 s/10 s} — the headline p50/p99 table; (c) **DRAIN** 100k unordered — assert no regression vs baseline and no inter-batch gaps (cycle-time histogram from I10's `fetch.latency`); (d) **FAULT** kill LISTEN connection mid-run — latency must degrade to ≤ fallback, never stall, and recover after reconnect.

### I3 — Due-time aware fallback

- **Latency:** affects *redelivery* latency only: a message retried with delay D is currently delivered at `D + up-to-fallbackInterval`; afterwards at `D ± ~100 ms`. With fallback raised to 10–30 s (the point of I2), this is what makes that raise safe. First-delivery latency: no effect.
- **Load:** one extra `min(next_delivery_ts)` index-only query per woken-but-empty fetch. Negligible; verify it stays index-only via EXPLAIN.
- **Lab tests:** redelivery-timing **PROBE**: handler fails first attempt with 300 ms redelivery delay, fallback set to 10 s — assert delivery at 300 ms ± 150 ms, and that the assertion *fails* when I3 is disabled (proves the test has teeth).

### I4 — Split claim paths (queue types)

- **Latency/load (unordered):** removing the vacuous anti-join shrinks per-claim planner+executor work. On a small table the absolute win is small (claims are already ~sub-ms); on a 100k+ row mixed table the anti-join evaluation per candidate dominates — expect 20–50% claim-statement latency reduction, more on large backlogs. The removal of the 2×-statement cost for de-facto-unordered MIXED queues is a clean −50% of claim load for affected queues. Confidence: medium-high.
- **Throughput (ordered) — the most uncertain estimate in this plan:** the `DISTINCT ON` head-claim delivers up to `min(slots, readyKeys)` messages per round trip vs 1 today, so a 200-key backlog with 16 slots *should* approach 16× fewer claim round trips. **However** `DISTINCT ON (key)` walks the index across *all* keys of the queue, including keys whose heads are far-future or dead-lettered: cost grows with key cardinality, not ready-message count. For a queue with 100k distinct keys and few ready messages this can be **slower** than today's candidate-first scan. This is precisely why the `useLegacyUnifiedClaim` escape hatch exists and why the cardinality SWEEP below is non-negotiable before flipping any default.
- **Eroders:** Postgres < 17 lacks efficient skip-scan for `DISTINCT ON`; table bloat (dead tuples from claim churn) inflates the head scan — the I4 fillfactor change interacts here, measure them together.
- **Lab tests:** (a) **DRAIN** ordered 100k msgs / 200 keys, A/B unified vs head-claim — headline ordered-throughput number; (b) **SWEEP** ordered-key cardinality {10, 1k, 10k, 100k keys} × {dense, sparse readiness} measuring claim-statement latency — find the crossover point and document it as guidance for the flag; (c) **SWEEP** table size {10k, 100k, 1M rows} for unordered claim latency, unified vs split; (d) EXPLAIN-plan assertions (no Seq Scan) as ITs, not lab scenarios; (e) **SOAK-MULTI** ordering invariant (correctness gate for the whole item).

### I5 — Multi-queue single-statement claim

- **Latency:** busy-path fetch cycle time drops from `N_queues × RTT` (sequential statements) to ~1 × RTT: with 20 queues at 1 ms RTT, ~20 ms → ~1–2 ms per cycle. This directly improves *busy-system* delivery latency (a message enqueued mid-cycle waits one cycle).
- **Load:** statements per fetch cycle: N → 1. At idle this multiplies with I2's win (the fallback poll also becomes 1 statement total).
- **Throughput:** raises the single-fetcher ceiling by ~N×. Caveats: one big statement holds row locks across all claimed rows marginally longer; fairness between queues inside `LATERAL` must be verified (a hot queue must not starve others — the per-target `LIMIT` should prevent it, but measure per-queue share).
- **Confidence: medium** — the SQL shape is sound, but this code path was previously disabled for a real competing-consumer bug (Bug #19); the estimate is conditional on the SOAK-MULTI test passing.
- **Lab tests:** (a) **SWEEP** queue count {5, 20, 50} at fixed aggregate rate — statements/s and cycle time, flag on/off; (b) **SOAK-MULTI** 2 instances × 30 min with mixed ordered/unordered — zero duplicates, both instances claim within 40–60% share; (c) **STEADY** per-queue fairness: 1 hot queue (5k msg/s) + 19 trickle queues — assert trickle-queue p99 latency unaffected by the hot queue.

### I6 — Batch acknowledge

- **Latency:** none on enqueue→handler (acks happen after handling). The ≤ 5 ms flush delay widens the at-least-once redelivery window only.
- **Load:** ack statements ÷ batch size (default 64). Combined with I4/I5, total statements/msg falls from ~2.0+ today (1 claim + 1 ack, + stats trigger write) toward **0.1–0.3**. For small-payload high-rate workloads where the DB is the bottleneck, statement count ≈ throughput, so:
- **Throughput:** expect +20–40% on DRAIN tests where ack round trips currently compete with claims for the connection pool. Confidence: high for the statement arithmetic, medium for the wall-clock translation (depends on pool contention).
- **Eroders:** the stats `ON DELETE` trigger still fires per row inside the batched DELETE; if statistics are enabled the win halves — measure both configurations.
- **Lab tests:** (a) **DRAIN** A/B flag on/off, with and without `PostgresqlDurableQueuesStatistics`; (b) statements/msg from the interceptor — assert ≤ 0.3 with I5+I6 on; (c) **FAULT** kill the JVM between handler completion and flush — assert redelivery occurs (correctness, not perf) and count duplicates (must be ≤ batch size).

### I7 — Mongo parity

- **Latency:** change-stream wake-up + event-driven fetcher takes idle latency from 100 ms–2 s to the change-stream propagation time (~10–30 ms on a local replica set) + claim RTTs — p50 < 25 ms, p99 < 100 ms realistic. The removal of the +100 ms ordered reorder penalty is a *guaranteed* win for contended ordered keys. Confidence: medium (change-stream propagation varies with oplog load).
- **Load:** idle polls drop to the fallback rate, mirroring I2 (−90%+). The `updateLookup` option adds a server-side document read per change event — on very busy collections this is real load; measure stream-server CPU at 5k msg/s and fall back to client-side filtering if it exceeds ~5% overhead.
- **Throughput:** batch claim turns 16 sequential `findAndModify` (each ~1 RTT, serialized behind the per-queue `ReentrantLock`) into 3 round trips with no lock — expect ~4–5× claim-path round-trip reduction and, more importantly, the lock removal lets parallel consumers actually scale (today they serialize). End-to-end msgs/s gain estimate: 2–4× on DRAIN. Caveat: the optimistic claim (step 2) loses races under multi-instance contention — losers do extra work; measure claimed/candidate efficiency in SOAK-MULTI and reduce candidate over-fetch if efficiency < 70%.
- **Lab tests:** mirror the Postgres scenario family on a Mongo replica-set container: (a) **IDLE**, (b) **PROBE**, (c) **DRAIN** ordered + unordered, A/B legacy vs batch claim, (d) **SOAK-MULTI** 2 instances with claim-efficiency metric, (e) **FAULT** disable change streams (simulate DocumentDB) — must degrade to polling cadence, never stall, (f) ordered-contention **SWEEP**: 1k messages on 1 key vs 100 keys — assert the +100 ms penalty is gone (today: ~100 s floor for 1k msgs on one contended key reordering path; after: handler-bound).

### I8 — Payload column TEXT

- **Latency:** negligible per message (µs-scale parse cost). **Load:** enqueue-side CPU on the Postgres server (JSONB parse/canonicalize on INSERT): −10–25% server CPU for KB-size payloads at high enqueue rates; near zero for tiny payloads. This is the lowest-priority, lowest-confidence win — it only matters for enqueue-bound workloads.
- **Lab tests:** **SWEEP** enqueue rate with payload {1 KB, 10 KB, 100 KB} × column {JSONB, TEXT}, measuring max sustainable enqueue rate and server CPU (`docker stats` on the container). If the win is < 5% at 1 KB, document TEXT as only recommended for ≥ 10 KB payloads.

### I9 — Stuck-reset off the hot path

- **Latency:** removes occasional inline reset work from the claim path — visible only in the p99.9 tail (a claim that happens to trigger the 30 s reset today pays a table scan). **Load:** the new partial index turns the reset query from O(table) to O(in-flight rows); on a 1M-row backlog this is the difference between a multi-second scan every 30 s and a millisecond probe.
- **Lab tests:** (a) p99.9 claim-latency comparison on **STEADY** with a 1M-row backlog table and forced reset cycles; (b) EXPLAIN assertion on the reset query (IT).

### I10 — Metrics

No performance win; it is the *instrument* every test above reads. Verify overhead: **STEADY** at 5k msg/s with meters registered vs `Optional.empty()` registry — assert < 2% throughput delta.

### Combined end-state vs baseline (what §4's acceptance criteria encode)

| Dimension | Baseline (today, defaults) | End state (P0–P4, flags on) | Dominant contributors |
|---|---|---|---|
| Idle DB load, 20 queues | ~1 000 claim stmts/s | ≤ ~1 stmt/s (1 multi-queue stmt per 1 s fallback; ~0.03/s at 30 s fallback) | I2 (×50), I5 (×20) |
| Enqueue→handler p99, idle (PG) | ~400 ms | < 50 ms (p50 < 10 ms) | I1+I2 |
| Enqueue→handler p99, idle (Mongo) | ~2 s | < 100 ms (p50 < 25 ms) | I7 |
| Statements per message, busy | ≥ 2.0 | 0.1–0.3 | I4+I5+I6 |
| Ordered drain throughput (PG) | 1 msg/claim-RTT | ~slots msgs/claim-RTT (×4–16, key-cardinality dependent) | ~~I4~~ — measured 1.07× for I4; the per-key cursor is the mechanism, at 4.0× on the claim (§0a) |
| Mongo drain throughput | lock-serialized | 2–4× | I7 |

These multipliers do **not** all compound into user-visible end-to-end gains — once polling overhead stops dominating, handler execution time becomes the bottleneck and further statement reductions become invisible in msgs/s (though still visible in DB CPU). The lab matrix must therefore always report **statements/msg and DB CPU alongside msgs/s**, so each improvement is verified on the dimension it actually targets.

## 6. Risks and explicit non-goals

- **Non-goal:** logical-replication/CDC delivery for queues. Claim/ack mutate rows; WAL-tailing queue state is strictly worse than NOTIFY. Do not build it.
- **Non-goal:** changing at-least-once to exactly-once. Batch ack slightly widens the redelivery window; document it.
- **Risk — LISTEN connection loss:** wake-ups vanish silently; the fallback poll is the safety net. Never set fallback > 30 s; log a WARN when the listener reconnects (MultiTableChangeListener already handles reconnect — verify and test by killing the connection in an IT).
- **Risk — `DISTINCT ON` plan regressions on huge queues:** keep the old unified query behind `useLegacyUnifiedClaim` builder flag for one release as an escape hatch.
- **Risk — multi-instance mixed versions during rolling deploy:** new triggers/indexes are additive and old code ignores them; old trigger removal (I1) only happens when new wake-up is enabled — call this out in release notes.
- **Bug #19 regression (duplicate consumption with multiple pods):** the `Math.max(0, …)` slot logic comment at `CentralizedMessageFetcher.java:429-434` is load-bearing. Preserve it; the multi-pod IT from I5 is mandatory before enabling any batched multi-queue claim.

## 7. File inventory (quick map for the implementer)

| Area | Files |
|---|---|
| Foundation core | `components/foundation/src/main/java/dk/trustworks/essentials/components/foundation/messaging/queue/{DurableQueues, CentralizedMessageFetcher, DefaultDurableQueueConsumer, CentralizedMessageFetcherDurableQueueConsumer, SimpleQueuePollingOptimizer, CentralizedQueuePollingOptimizer, BatchMessageFetchingCapableDurableQueues}.java` + `operations/{ConsumeFromQueue, GetNextMessageReadyForDelivery, QueueMessage(s)}.java` |
| Postgres store | `components/postgresql-queue/src/main/java/dk/trustworks/essentials/components/queue/postgresql/{PostgresqlDurableQueues, DurableQueuesSql, PostgresqlDurableQueuesBuilder, QueueTableNotification, QueuedMessageRowMapper, PostgresqlDurableQueuesStatistics}.java` |
| Mongo store | `components/springdata-mongo-queue/src/main/java/dk/trustworks/essentials/components/queue/springdata/mongodb/{MongoDurableQueues, MongoDurableQueueConsumer}.java` |
| Wake-up prior art (read, don't modify) | `components/postgresql-event-store/.../subscription/notify/{NotifyEpochSource, NotifyAwareEventStorePollingOptimizer, NotifyTriggerInstaller, NotifyPollingSettings}.java`, `components/foundation/.../postgresql/{ListenNotify, MultiTableChangeListener}.java` |
| Starters | `components/spring-boot-starter-postgresql` (queue autoconfig + new properties), `components/spring-boot-starter-mongodb` |
| Perf lab | `examples/essentials-performance-lab/src/main/java/.../scenario/` + `scripts/` |
| Docs to update every phase | `components/{foundation,postgresql-queue,springdata-mongo-queue}/README.md`, `/LLM/LLM-{foundation,postgresql-queue,springdata-mongo-queue}.md`, starter READMEs |

---

*Generated 2026-06-10 from branch `cdc` (HEAD 4f5d8a5e). Line references were verified against that commit; re-verify with `grep -n` before editing — files may have drifted.*
