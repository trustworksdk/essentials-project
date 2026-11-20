# Plans and improvement proposals

Work that is **not built yet**: plans, proposals and improvement lists. A document lives here while it has open
items. When the work ships, the part that describes the feature goes into a reference document in `docs/` (or the
module's `LLM/` doc), and the plan moves to [`../archive/`](../archive). Being linked from somewhere is not a reason for a finished plan to stay here: move it (or condense several into one archive record), repoint the links, and delete what nothing needs any more. Git history keeps the rest.

Last checked against the code: 2026-11-20.

## Documents

| Document | Area | What is open |
|---|---|---|
| [ROADMAP-0.6x.md](ROADMAP-0.6x.md) | Whole framework | The open fixes and improvements for the 0.6x line, bucketed by the earliest release they may ship in. Items are deleted when shipped. |
| [durable-queues-improvement-plan.md](durable-queues-improvement-plan.md) | `postgresql-queue`, shard-owned engine | D4 virtual-thread handlers for the classic queue; E3 document a lower HikariCP `minimumIdle`; F5 a retrying shard-owned message keeps no error; C4 "skip the ordered claim" (reverted, measure first); batched acknowledgements. |
| [shard-owned-queue-polling-reduction.md](shard-owned-queue-polling-reduction.md) | Shard-owned engine | Item 1(b) cache the Micrometer queue-depth read; items 2 and 4 (parked on `wip/` branches); item 5. |
| [shard-owned-consumer-overhead-proposal.md](shard-owned-consumer-overhead-proposal.md) | Shard-owned engine | Candidate 2: the cost of the batched cursor read when units are split (profile first). |
| [shard-owned-test-time-reduction.md](shard-owned-test-time-reduction.md) | Shard-owned engine tests | Items 1–4 and 6 done (engine module 12:42 → 8:40). Open: §7, a profiling pass over the remaining waits in `ShardOwnedLifecycleIT` and `ShardOwnedUnsubscribeIT`, then SQL lease ageing where safe. |
| [subscription-improvements.md](subscription-improvements.md) | Event store subscriptions | S2 interruptible (reactive) wake-up; S3 consumer-group-scoped resume points; S4 trimming the one-event overlap after a crash. S1 phase 1, S5 and S6 are built. |
| [old-engine-leftover-transient-gaps.md](old-engine-leftover-transient-gaps.md) | Existing event store (`postgresql-event-store`) | Investigation to do: under CDC, view subscribers keep `transient_subscriber_gaps` rows for gaps whose fill they already handled (found by the phase-9 migration gate). Open; any fix needs the maintainer's go-ahead. |
| [pgjdbc-notification-drain-options.md](pgjdbc-notification-drain-options.md) | Shard-owned queue Tier 1 and event store wake-up tier 1 | Options for draining LISTEN notifications that pgjdbc holds back under sustained traffic, measured with test-only prototypes (reflection that pins pgjdbc's peek cool-down, a query-driven poll, a paced notifier), plus reasoning on a protocol client, another driver and an upstream fix, with a draft pgjdbc issue. Recommendation only; nothing decided. Open: an SSL run, a multi-process producer run, the poll's idle cost |

## Open items from documents kept elsewhere

These documents describe built features (or are archived), but name open follow-ups that are not tracked in any
document above.

| Open item | Source |
|---|---|
| CDC P10 Tier 2 (deferred) | [../archive/cdc-improvements.md](../archive/cdc-improvements.md) |
| Event causation phase 9: the webshop worked example (capture webhook binds `FundsCaptureRequested`, end-to-end test of the F6 chains) | [../design/event-causation.md](../design/event-causation.md) |
| Key-partitioned processing §9.1 batched handling of queued messages; §9.2 a rebuild path for `InTransactionEventProcessor` | [../design/key-partitioned-event-processing.md](../design/key-partitioned-event-processing.md) |
| Schema harness step 8 (Mongo contributors and `MongoSchemaApplier`), steps 11–12 (`essentials-schema-flyway`, `essentials-schema-liquibase`) | [../design/database-schema-harness.md](../design/database-schema-harness.md) §9 |
| Shard-owned queue Tier 1: pgjdbc's `getNotifications(timeout)` returns nothing while notifications keep arriving under ~1 ms apart, so cross-process wake-ups fall back to the backstop from roughly 1 000 notifications/s across the cluster (measured 2026-11-12). Needs a listener drain that the driver's loop cannot hold back; options measured in [pgjdbc-notification-drain-options.md](pgjdbc-notification-drain-options.md) | [../design/durable-queue-measurements.md](../design/durable-queue-measurements.md) §3.17 |
| A start-up check of total connections held against the pool size | [../archive/processor-settings-inventory.md](../archive/processor-settings-inventory.md) §3 |
| Event store redesign, open from phase 0: P0.3 (managed PostgreSQL services), the dedicated server's slow flush phase, splitting P0.9's CPU outside recorded execution, a separate wired client host, a pooled connection under `force_generic_plan`, `CONTIGUOUS` attach/detach behind a long writer, a rollback correctness test for the five-aggregate one-flush append | [../archive/event-store-subscription-redesign.md](../archive/event-store-subscription-redesign.md), [../archive/event-store-redesign-phase0.md](../archive/event-store-redesign-phase0.md) |
| New event store engine after the phase-10 gate: the one-instance tail in the second after a burst window closes (suspected slower backlog drain, not established). PostgreSQL CPU per order, measured 2026-11-20 with 4-day trading-demo runs: 1.04× classic with one instance and 1.11× with two, after processors began subscribing only to the event types they handle (statement execution is cheaper than classic; the rest is per-transaction and per-round-trip overhead). With two instances most of what remains is the shard-owned queue's horizon probe running about 16 times per order against 10 on classic: sharing one probe across an instance's pumps or spacing a pump's probes out both trade latency for CPU and need an A/B run. The per-page `idle_in_transaction_session_timeout` round trip stays: no option keeps its guarantee | [../design/event-log.md](../design/event-log.md) §13, [../archive/event-store-engine-phase10.md](../archive/event-store-engine-phase10.md) §12 |
| Load-test the failure paths: run the phase-6 harness with faults injected (CDC gap stalls, failed-batch replay, closing-books rollover, two-instance ownership), and line the load driver's start up with the exchange day so A/B arms trade the same number of days (compare equal `steadyState.bursts`) | Review-fix performance A/B, 2026-11-08 |
