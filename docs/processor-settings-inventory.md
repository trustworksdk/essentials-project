# Processor and queue settings: what can go wrong

**Status:** for decision, 2026-10-29. Each row carries a recommendation; decided rows say so.

The event-store, queue and lock settings number about 140 (`essentials.*` in the starters' metadata, plus the
processors' overridable `get…()` hooks). Almost all are tuning: a wrong value makes something slower or noisier, not
wrong. This list keeps only the settings that **interact** - where one value is only safe relative to another, or
relative to how long application code runs - and the leftovers worth cleaning up. Each row says what goes wrong, how a
user would find out today, and what to do: **remove**, **derive** (the framework picks it), **fail fast** (refuse to
start), **warn** (log at start or at runtime), or **document**.

## Already done on this branch

| Setting | Was | Now |
|---|---|---|
| Where an event processor's handlers run (inbox consumption mode) | An override, plus an unreleased global property | A class: `PartitionedEventProcessor` or `EventProcessor` (`docs/event-processor-specializations.md`) |
| `GlobalCompetingConsumers` on a queue that keeps key order per node only | Ran, could reorder an aggregate across nodes | Refuses to start |

## 1. Retry budgets shorter than a failover

**Decided 2026-10-29: derive the default, safety factor ×4** - built as `FailoverRedeliveryPolicy` (release notes
§1.1.4). The user-built `Inbox`/`Outbox` row is covered by documenting the helper.

| Setting | Default | What goes wrong | Found out today | Recommendation |
|---|---|---|---|---|
| `EventProcessor.getInboxRedeliveryPolicy()` | 20 redeliveries, 200 ms growing ×1.1 to 3 s: **~10 s in all** | A handler that waits for state another processor produces ("not yet", retried by throwing) gives up and dead-letters when that state is late. A failover makes it late by the lease TTL (30 s) or the lock timeout (15 s) - and every later message of the same key is blocked behind the dead letter. Measured: the demo's settlement reports, 438 dead letters from one killed instance | A dead-letter count after an incident | **Derive the default from the failover window:** keep the quick first retries, let the budget outlast lease TTL + lock timeout (e.g. doubling from 200 ms to a 10 s cap: ~7 min over 50 attempts, what the demo now uses). Behaviour change - a poison message takes minutes, not seconds, to dead-letter - so release note. Alternative if that is too much: **warn** at start when a processor's budget is shorter than the failover window |
| `getDurableQueueRedeliveryPolicy()` (`ViewEventProcessor` queue) | Same ~10 s | The same, for a view that queues a failed event | Same | Same as above |
| A user-built `Inbox`/`Outbox` `RedeliveryPolicy` | Whatever the user sets | The same risk, with no framework default to fix | Same | **Document** the rule ("outlast a failover if the handler waits for other processors") in the `RedeliveryPolicy` javadoc and the LLM doc |

## 2. Timing that must stay above how long code runs

| Setting | Default | What goes wrong | Found out today | Recommendation |
|---|---|---|---|---|
| `essentials.shard-owned-queue.lease-ttl` vs handler duration | 30 s | A handler running longer than the lease while its instance stops renewing (GC pause, overload) lets another instance start the same key beside it: one key in two handlers at once | Already a WARN (`LeaseOverrun`, at most once a minute per consumer, naming the fix) plus the metric `handlersOutlastingLease`; dispatch pauses at 4/5 of the lease | **Decided 2026-10-29 (warn + document):** the warning existed already; documented next to `UnitOfWorkMode.NONE` in the event-store LLM doc |
| `essentials.durable-queues.message-handling-timeout` vs handler duration (`PostgresqlDurableQueues`, MongoDB) | 30 s | A handler blocking longer is taken for stuck and its message redelivered while it still runs: a second, concurrent attempt | Was: a DEBUG line only | **Decided 2026-10-29 (C):** a WARN where a handler finishes past the timeout (`HandlerTimeoutOverrun`, once a minute per queue), and the take-back count at INFO (release notes §2.8) |
| `essentials.shard-owned-queue.hole-expiry` vs enqueue transaction length | 10 s | **Suspected message loss on the unordered lane (from reading the code, not reproduced):** the range acknowledgement deletes `(takeoverCursor, contiguousThrough]` and only a still-tracked hole holds `contiguousThrough` back. An enqueue transaction committing more than `hole-expiry` after its `nextval` can therefore have its row deleted by the next range ack before the head sweep delivers it - what the property's own javadoc calls "written off". The ordered lane uses the watermark instead (60 s cap): late, possibly out of order, not deleted | Nothing | **Decided and fixed: it was a real loss.** Reproduced by `ShardOwnedLateCommitAfterHoleExpiryIT` (failed 3 of 3 runs): the late row sat between the takeover cursor and the first acknowledged sequence value of the contiguous run, so the range delete took it. The range now starts just below that first acknowledged value (`ShardOwner.flushAcks`), so every value it deletes is one the owner handled; the late message is delivered by the head sweep. `hole-expiry` past the longest enqueue transaction now only avoids that delay; LLM doc, design doc and property javadoc say so. Release notes §3 |
| `essentials.fenced-lock-manager.lock-confirmation-interval` vs `lock-time-out` | 4 s / 15 s | Confirming no more often than the timeout loses locks spuriously | Settings validation | **Done:** equal values are now refused too (`>= 0`), matching the documented "strictly shorter"; foundation `CLAUDE.md` corrected. A configuration with equal values now fails at start (release notes §3) |

## 3. Connections

| Setting | Default | What goes wrong | Found out today | Recommendation |
|---|---|---|---|---|
| Pool size vs connections held by processors | Hikari 10 | Every inbox/queue consumer thread holds a pooled connection while it handles a message: processors × consumer threads, plus pumps, the NOTIFY listener, subscription polls and CDC. `PoolBudget` counts pumps only. Exhaustion shows as Hikari timeouts under load, far from the cause | Hikari timeouts | **Quick fix done (user's suggestion):** an `EventProcessor` with more than 4 inbox consumer threads says at start, at INFO, that each holds a pooled connection and the pool must fit them. A real total against the pool size (a start-up hook after every processor started) stays a possible follow-up |

## 4. Identity

| Setting | Default | What goes wrong | Found out today | Recommendation |
|---|---|---|---|---|
| `essentials.shard-owned-queue.instance-id`, `essentials.fenced-lock-manager.instance-id` | `<hostname>:<pid>` (since 0.60.1) | Only where processes share a hostname *and* a pid | Halved shards per instance | **Done:** the demo's `CLAUDE.md` corrected |

## 5. Silent fallbacks and inert settings

| Setting | Default | What goes wrong | Recommendation |
|---|---|---|---|
| `essentials.eventstore.cdc.mode` | `AUTO` | A half-configured CDC setup falls back to polling and looks fine | Keep (documented; `CdcEffectivenessMonitor` and `fallback_total` are the alert). Nothing to do |
| `essentials.eventstore.cdc.event-bus.live-drain-stall-threshold` | 180 s | Inert since the drain stopped waiting for holes; kept for compatibility | **Nothing to do:** already `@Deprecated(forRemoval = true)` |
| `essentials.fenced-lock-manager.release-acquired-locks-in-case-of-i-o-exceptions-during-lock-confirmation` | `false` | Keeps locks through database blips; risks two holders if the database really moved the lock | Keep (a documented trade-off). Nothing to do |
| `isStartSubscriptionFromLatestEvent()` | `false` | `true` on a processor that should see history skips it for good | Keep (documented). Nothing to do |
| `InTransactionEventProcessor` javadoc | - | Said handlers get `RedeliveryPolicy` retries; a handler that throws fails the append instead | **Done** |

## Suggested order

1. The retry budgets (§1): the only row with measured damage, and a default change - decide it before 0.61 ships.
2. The lock-check off-by-one and the two stale docs (§2, §4, §5): small and certain.
3. The runtime warnings (§2, §3): each one names the setting that caused it, which is what turns a production surprise
   into a log line.
4. `hole-expiry`: done - reproduced, fixed in the engine.
