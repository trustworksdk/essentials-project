# Plans and improvement proposals

Work that is **not built yet**: plans, proposals and improvement lists. A document lives here while it has open
items. When the work ships, the part that describes the feature goes into a reference document in `docs/` (or the
module's `LLM/` doc), and the plan moves to [`../archive/`](../archive).

Last checked against the code: 2026-11-08.

## Documents

| Document | Area | What is open |
|---|---|---|
| [ROADMAP-0.6x.md](ROADMAP-0.6x.md) | Whole framework | The open fixes and improvements for the 0.6x line, bucketed by the earliest release they may ship in. Items are deleted when shipped. |
| [durable-queues-improvement-plan.md](durable-queues-improvement-plan.md) | `postgresql-queue`, shard-owned engine | D4 virtual-thread handlers for the classic queue; E3 document a lower HikariCP `minimumIdle`; F5 a retrying shard-owned message keeps no error; C4 "skip the ordered claim" (reverted, measure first); batched acknowledgements. |
| [shard-owned-queue-polling-reduction.md](shard-owned-queue-polling-reduction.md) | Shard-owned engine | Item 1(b) cache the Micrometer queue-depth read; items 2 and 4 (parked on `wip/` branches); item 5. |
| [shard-owned-consumer-overhead-proposal.md](shard-owned-consumer-overhead-proposal.md) | Shard-owned engine | Candidate 2: the cost of the batched cursor read when units are split (profile first). |
| [shard-owned-test-time-reduction.md](shard-owned-test-time-reduction.md) | Shard-owned engine tests | Items 1–4 and 6 done (engine module 12:42 → 8:40). Open: §7, a profiling pass over the remaining waits in `ShardOwnedLifecycleIT` and `ShardOwnedUnsubscribeIT`, then SQL lease ageing where safe. |
| [subscription-improvements.md](subscription-improvements.md) | Event store subscriptions | S2 interruptible (reactive) wake-up; S3 consumer-group-scoped resume points; S4 trimming the one-event overlap after a crash. S1 phase 1, S5 and S6 are built. |
| [event-store-subscription-redesign.md](event-store-subscription-redesign.md) | Event store subscriptions and append path | A proposal; nothing built. Revised 2026-11-10 with the Marten findings. Phase 0 (investigation) under way: P0.0, P0.1, P0.2, P0.4 and P0.6 measured (see the phase-0 documents below), P0.3 deferred until managed-PostgreSQL access exists, P0.5 (model of the reader and delivery modes) modelled, also with several events per transaction; its findings F1–F6 (F1: FRESH as written can break per-aggregate order) are folded into the plan as proposed revisions, not decided. |
| [event-store-phase0-baseline.md](event-store-phase0-baseline.md), [event-store-phase0-append.md](event-store-phase0-append.md), [event-store-phase0-jdbc.md](event-store-phase0-jdbc.md), [event-store-phase0-external-db.md](event-store-phase0-external-db.md), [event-store-phase0-model.md](event-store-phase0-model.md) | Event store redesign, phase 0 | Measurements of the redesign's phase 0 (P0.0 baseline, P0.1-P0.9 append, table design and CPU attribution, P0.6 JDBI vs raw pgjdbc), and P0.5's model of the subscription state machine (a deterministic Java simulation; findings, nothing decided), all so far on one Docker VM with `fsync=off`. Open: rerunning them against a dedicated PostgreSQL server with `fsync=on` (the external-db document says how). |
| [marten-event-store-design.md](marten-event-store-design.md) | Reference (Marten, .NET) | Not a plan: how Marten's event store works, the source of the redesign's 2026-11-10 revision. |

## Open items from documents kept elsewhere

These documents describe built features (or are archived), but name open follow-ups that are not tracked in any
document above.

| Open item | Source |
|---|---|
| CDC P10 Tier 2 (deferred) | [../archive/cdc-improvements.md](../archive/cdc-improvements.md) |
| Event causation phase 9: the webshop worked example (capture webhook binds `FundsCaptureRequested`, end-to-end test of the F6 chains) | [../design/event-causation.md](../design/event-causation.md) |
| Key-partitioned processing §9.1 batched handling of queued messages; §9.2 a rebuild path for `InTransactionEventProcessor` | [../design/key-partitioned-event-processing.md](../design/key-partitioned-event-processing.md) |
| Schema harness step 8 (Mongo contributors and `MongoSchemaApplier`), steps 11–12 (`essentials-schema-flyway`, `essentials-schema-liquibase`) | [../design/database-schema-harness.md](../design/database-schema-harness.md) §9 |
| A start-up check of total connections held against the pool size | [../archive/processor-settings-inventory.md](../archive/processor-settings-inventory.md) §3 |
| Load-test the failure paths: run the phase-6 harness with faults injected (CDC gap stalls, failed-batch replay, closing-books rollover, two-instance ownership), and line the load driver's start up with the exchange day so A/B arms trade the same number of days (compare equal `steadyState.bursts`) | Review-fix performance A/B, 2026-11-08 |
