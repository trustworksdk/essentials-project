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
| [event-store-subscription-redesign.md](event-store-subscription-redesign.md) | Event store subscriptions | Whole document: a proposal, nothing built or measured. |

## Open items from documents kept elsewhere

These documents describe built features (or are archived), but name open follow-ups that are not tracked in any
document above.

| Open item | Source |
|---|---|
| CDC P10 Tier 2 (deferred) | [../archive/cdc-improvements.md](../archive/cdc-improvements.md) |
| Notify-polling wake-up latency floor: the wake-up of S1/S2 can be no faster than the shared `MultiTableChangeListener` polling interval (`essentials.multi-table-change-listener.polling-interval`, default 50 ms). Lowering it also changes how often the durable queues are woken. An event-store-specific interval would need a second listener (a second LISTEN connection and a second thread). | [../archive/subscription-improvements.md](../archive/subscription-improvements.md), "S1 open questions: measured 2026-11-27" (Q3) and "What remains" item 4 |
| Notify-polling burst limit: the notify trigger is installed for every aggregate type, so a busy table's NOTIFYs can delay a quiet table's wake-up. The remedy is a per-aggregate-type opt-in for the notify trigger. | [../archive/subscription-improvements.md](../archive/subscription-improvements.md), "S2: implemented and measured 2026-11-27" (the known limit under a burst) |
| S3 cheaper alternative, only if multi-deployment users need it: detect a resume-point collision between deployments that share one database with identical `SubscriberId`s (record which deployment or group last wrote a resume point, and WARN or refuse when another one writes the same row; one nullable column on `durable_subscriptions`, no primary-key change). | [../archive/subscription-improvements.md](../archive/subscription-improvements.md), Backlog "S3" |
| Event causation phase 9: the webshop worked example (capture webhook binds `FundsCaptureRequested`, end-to-end test of the F6 chains) | [../design/event-causation.md](../design/event-causation.md) |
| Key-partitioned processing §9.1 batched handling of queued messages; §9.2 a rebuild path for `InTransactionEventProcessor` | [../design/key-partitioned-event-processing.md](../design/key-partitioned-event-processing.md) |
| Schema harness step 8 (Mongo contributors and `MongoSchemaApplier`), steps 11–12 (`essentials-schema-flyway`, `essentials-schema-liquibase`) | [../design/database-schema-harness.md](../design/database-schema-harness.md) §9 |
| A start-up check of total connections held against the pool size | [../archive/processor-settings-inventory.md](../archive/processor-settings-inventory.md) §3 |
| Load-test the failure paths: run the phase-6 harness with faults injected (CDC gap stalls, failed-batch replay, closing-books rollover, two-instance ownership), and line the load driver's start up with the exchange day so A/B arms trade the same number of days (compare equal `steadyState.bursts`) | Review-fix performance A/B, 2026-11-08 |
