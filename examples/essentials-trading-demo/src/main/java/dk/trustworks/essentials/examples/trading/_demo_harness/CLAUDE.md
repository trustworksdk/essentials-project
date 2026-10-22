# `_demo_harness` — not a slice

This directory is the law's `_`-prefixed escape hatch (`rules/slice-design.md` § Directory
vocabulary): **excluded from slice enumeration and from the §R4 boundary check.** It is not a fifth
slice kind and nothing here is a bounded context.

What lives here is the machinery that makes the demo demonstrate something:

| Component | What it is |
|---|---|
| `DemoUsersSeeder` | Registers the seeded users (`trading-demo.identity.seed-users`) at startup when absent |
| `QueueLoadGenerator` | Drives the **shard-owned queue engine** on both lanes at once — sustained trickle plus on-demand spikes — and checks per-key ordering as messages arrive |
| `QueueLoadGeneratorController` | `/api/admin/queue-load` — status, start/stop, `POST /spike?size=N`, `POST /faults?retries=N&poison=M&failures=K`, `POST /faults/blocked-key?behind=N` |
| `LedgerLoadGenerator`, `LedgerLoadController` | Phase 1: buy cycles on the `accounts` ledger, `/api/admin/ledger-load` |
| `OrderFlowLoadGenerator`, `OrderFlowLoadController` | Phase 3: simulated customers placing and cancelling orders, against the stub exchange or the exchange-simulator; placement vs completion throughput and end-to-end latency |
| `NoOpAccounts*Processor`, `BatchedAccountsForwarder` | EF-2 measurement processors, off by default |

The `brokerage` bootstrap runner, its trading load generator, the dashboard and the demo's own admin page went with
`brokerage` (phase 4 Q-3). The Essentials admin console covers closing books, snapshots, archives and lifecycle for
every aggregate; a demo page on the new contexts comes with phase 7.

## Why these are not slices

An automation slice reacts to a domain event and issues a follow-up command; a view slice answers a
query over a read model it owns. The load generator does neither — it *manufactures* activity that a
real deployment would receive from users, and the dashboard reports on the harness itself (counters,
timings) as much as on the domain. Classifying them as automations and views would
put demo scaffolding on the same footing as the domain, which is the bigger lie.

## Rules that still apply here

Being outside the boundary check is not permission to reach into the contexts:

- **Writes go through the command bus.** Every domain mutation is `commandBus.send(new SomeCommand(…))`,
  naming a slice's command type — which is §R4's sanctioned collaboration, not an exception to it.
- **Reads go through view slices**, never by rehydrating an aggregate. Nothing here reads an aggregate
  *field*.
- **It may not be imported by a bounded context.** The dependency runs one way. If something here
  looks like the contexts need it, it belongs in a slice instead.

## The shard-owned queue exercise

`QueueLoadGenerator` exists because the queue engine is unpublished and experimental: this app is
where it meets a real Spring application, a shared connection pool and a database that is also
serving an event store. Four things it caught that the engine's own tests could not, all of them
misuse rather than engine defects — which is the point of an integration demo.

- **`@Scheduled` is inert here.** The demo has no `@EnableScheduling`, so the annotation binds,
  validates and never fires. A load generator that generates no load is the quietest possible
  failure. Own a `ScheduledExecutorService`, as `QueueLoadGenerator` does.
- **`MessageQueue.consume` covers BOTH lanes.** The lane is chosen by the message — `Message.of` is
  unordered, `Message.ordered` carries a key — not by the consumer. A second `consume()` on the same
  queue is therefore a *competing consumer with its own instance identity*, not "the other lane":
  registering one per lane delivered price ticks to the ordered handler, dead-lettered 1 934 of them
  for failing to parse as a sequence number, and reported two live instances for one process. Use one
  subscription and discriminate on `payloadType`.
- **`key_order` allocation must be atomic with the enqueue that carries it.** `key_order` is the
  producer's statement of what order means, so the engine can only deliver a key in that order if
  numbering and committing agree. With a sustained arm and a spike arm numbering the same keys, a
  spike spends seconds inserting 50 000 rows while the sustained arm takes a *higher* `key_order` and
  commits it *first* — 7 390 ordering violations in one spike, the engine faithfully reporting a
  defect in the code feeding it. A real producer gets this free from one aggregate under one unit of
  work; a two-armed generator has to arrange it.
- **A key belongs to ONE producer, and the same is true across processes.** `nextOrderPerKey` is
  in-memory and starts at zero in every JVM, so two instances generating into one key space both
  number `ACC-77` 0, 1, 2 …. The ordered lane's primary key is `(queue_id, shard, msg_key, key_order)`,
  so the second arrival at a position is refused — `duplicate key value violates unique constraint
  "shard_queue_ordered_pkey"` once per sustained tick, plus an ordering violation for every pair that
  interleaved. That reads as broken ordered delivery and is the constraint working. Each instance now
  produces under its own `ACC-<instanceId>-<runToken>-` prefix (`keyPrefix`, from
  `ShardOwnedQueueFactory.instanceId()`), chosen over electing a single producer so both instances
  still exercise the producing side as well as the consuming one.
- **The same is true across restarts, which is why the prefix carries a run token.** With the
  instance id alone, a restarted `demo-1` numbered its keys from 0 again while the previous run's rows
  (numbered up to ~1 600) were still queued: the engine delivered 1 600, then 2, and logged an ordering
  violation for every such key. Correctly — the producer really did number them that way. A token from
  the start time gives each run fresh keys; the previous run's backlog drains under keys nothing
  produces any more.

Measured after those three were fixed, one instance, 4 unordered shards and 64 ordered units:

| | |
|---|---|
| spike | 100 000 messages (50 000 per lane) in one call |
| backlog at t+5s | 39 739 unordered, 34 257 ordered |
| fully drained | t+20s, roughly 5 000 msg/s combined |
| ordering violations | 0, against a 34 000-deep ordered queue |
| dead letters | 0 |
| ownership | 4 + 64 units, `fullyOwned` throughout |

### Injected faults

`POST /api/admin/queue-load/faults?retries=N&poison=M&failures=K` enqueues, on each lane, N messages
that fail K times and then succeed, and M that fail every attempt and are dead-lettered after the
consumer's `maxAttempts` (3 by default, so K must be 1 or 2). They are kept apart from the traffic
above, and both halves of that are load-bearing:

- **Own payload types**, so they are never ordering-checked and never counted as ticks or account
  activity. Watch `retryFaultsRecovered`, `injectedFailures` and `deadLetteredDepth` instead.
- **A single-use key per ordered fault message** (`FAULT-<instanceId>-<runToken>-<n>`). A dead
  letter blocks its key and dead-letters everything that arrives for it afterwards, so a poison
  message on an `ACC-` key would silently take that account's activity down with it.

The retry handler reads the attempt count with `MessageQueue.getMessage(messageId)` — the handler
is not given it — so the count lives in the database and survives a rebalance to another instance.
Poison dead letters stay until someone deletes or resurrects them; resurrecting one just fails it
again.

`POST /faults/blocked-key?behind=N` builds the one case the single-use fault keys cannot show: a key
that stops. Its head (`key_order` 0) fails until resurrected and the N behind it are parked by the
engine unhandled. The response names the admin call that recovers it,
`POST <admin-base>/shard-owned-queues/trading-events/ordered-keys/<key>/resurrect`; the key then
replays in `key_order`, checked by the same ordering assertion as account activity. Deleting the head
first skips it — the rest stay parked behind the lowest remaining dead letter until the resurrect.

- **"Fails until resurrected" is keyed on the head's `MessageId`.** Resurrecting gives a message a
  fresh sequence value, so a new id; the handler fails only under the id it was enqueued with. The
  set is in memory, so it is **single-instance only**: with two instances the key's unit may belong
  to the other one, which delivers the head first time and the key never blocks.
- **Head registration and the handler share one lock.** `enqueue` commits before it returns the ids,
  so a fast consumer can be handed the head before it is known to be one.


Its first run found an engine defect: every **unordered** poison message vanished — neither queued
nor dead-lettered — because the unordered lane's range acknowledgement deleted rows waiting out a
retry backoff. Fixed in the engine; see "The unordered range ack" in
`components/postgresql-queue-shard-owned/CLAUDE.md`. After a fault run,
`deadLetteredDepth` should equal `poisonFaultsEnqueued`.

`unownedShards` is the field to watch, not depth: depth cannot tell "nobody is consuming" from
"busy", and this engine's ownership failures have historically been invisible in depth alone.

## The Inbox exercise, which is the more honest one

`QueueLoadGenerator`'s handler sleeps a millisecond. Real handlers open a unit of work and write SQL,
and the way to exercise that without writing a fake is to put the app's own `DurableQueues` on the
engine: an `EventProcessor` forwards what it consumes through an `Inbox`, and an `Inbox` is a queue.
`essentials.shard-owned-queue.durable-queues-enabled` does that, so the four projections are delivered
by the engine and their handlers do the real work. The demo used to carry its own
`ShardOwnedDurableQueuesConfiguration` bean for this; the starter owns it now, and the demo is just
two lines of YAML. The engine auto-registers the queues the processors invent:

```
DefaultCommandQueue | Inbox:TradeSettlementProjection | Inbox:TradeValuationProjection
InstrumentDetailsProjection:queue | TradingAccountStatementProjection:queue | trading-events
```

(That list is from before phase 3. `Inbox:TradeValuationProjection` went with `brokerage.trade_valuation` and the
`InstrumentPrice` aggregate (D19), the rest of the `brokerage` queues with `brokerage` itself (phase 4); the order core
added its own processors' inboxes and `Orders:ExchangeReports`.)

What this caught that the engine's own tests and the synthetic generator could not:

- **`ViewEventProcessor` called `queuedMessage.getId()` in log arguments.** Arguments are evaluated
  eagerly, so it ran on every delivery whatever the level. `getId()` is the `QueueEntryId`, which the
  adapter's codec packs from `(QueueName, MessageId)`, and the push path has no `MessageId` — the
  engine's `MessageHandler` gets `(key, payload, payloadType)`. The adapter throws there rather than
  stub an id, so every projection message dead-lettered from a log statement nobody had enabled.
  Now trace-level behind `isTraceEnabled()`, which is what a per-message statement should have been
  anyway. The reminder is that the partial-message contract has to hold for *callers*, not just for
  the adapter's own tests.

The demo also had to turn `enable-queue-statistics` back off — it was explicitly on here, and it
installs a trigger on the `durable_queues` table, which only `PostgresqlDurableQueues` creates. Not
a gap in the adapter: the setting is off by default and is going away in the next major.

### Measured, one instance, 4 unordered shards and 64 ordered units per queue (before phase 3)

This was measured with the price path that phase 3 removed: trade bursts stop contributing at
`maxGeneratedTrades` (500), so the spike lever was price updates — also the heavier message, since each one
updated every trade on its instrument (~250 rows) in `trade_valuation`. That lever is gone; the order-flow scenario
below is the load to measure the Inbox path with now. Eight concurrent producers × 5 000:

| | |
|---|---|
| peak backlog | ~2 450 on `Inbox:TradeValuationProjection` |
| peak lag | ~8s, held flat while producers ran |
| drain | 2 441 → 0 in ~4s once producers stopped (~600/s, each message a bulk `UPDATE`) |
| dead letters | 0 |
| errors | 0 |
| fence | 1 on all 12 lanes throughout — no ownership churn under load |

The shape is the finding: lag plateaus rather than growing without bound while producers run, and the
backlog only appeared once *producers* were made concurrent. A single synchronous burst endpoint could not
outrun the projections — 4 000 price updates in 6s never took the Inbox past a depth of 8. That the
fence never moved is what the instance-liveness redesign was for; a lease-expiry design would have
churned ownership exactly here, under load, while the pool was contended.

## The ledger load scenario (phase 1)

`LedgerLoadGenerator`, `/api/admin/ledger-load`: buy cycles (hold → book → release → settle) on the `accounts`
ledger, fresh `LL-<run>-<n>` accounts per run. `ORDERED` sends every command through the `LedgerLoad:commands` inbox
keyed by account id; `DIRECT` sends cycles on the command bus from a pool and retries conflicts. Each run reports
drain, throughput, latency, conflicts, failures and how long `account_activity` takes to catch up afterwards.

- **The inbox is created at startup on every instance**, its mode from `trading-demo.ledger-load.inbox-consumption-mode`
  (default `SingleGlobalConsumer`) and `.inbox-consumers`. Created lazily it would exist only where a run started.
- **Drain is detected by counting the run's events in the event store** (`AccountEventCounter`), because another
  instance's handled-counter is not visible. Latency percentiles are the starting instance's share only.
- **One producer thread enqueues**, so each account's key order is numbered in commit order (the shard-owned lane's
  rule above). It is also the throughput ceiling below.

### Measured, 2026-10-18, devcontainer, `compose` profile (shard-owned engine), one sample each

1 000 accounts, 5 000 cycles (20 000 commands), 10 hot accounts taking 50 % of cycles.

| Run | Instances | Drain | Commands/s | Handler p50 / p99 | Conflicts | View catch-up after drain |
|---|---|---|---|---|---|---|
| ORDERED, `SingleGlobalConsumer` | 1 | 15.3 s (enqueue 11.0 s) | 1 309 | 1.4 / 3.8 ms | 0 | 15.2 s |
| DIRECT, 8 threads | 1 | 6.9 s | 2 896 | cycle 9.6 / 31.7 ms | **2 978** | **24.6 s** |
| ORDERED, `SingleGlobalConsumer` | 2 | 22.5 s (enqueue 12.6 s) | 889 | 1.7 / 5.0 ms | 0 | 21.8 s |
| ORDERED, `GlobalCompetingConsumers` | 1 | 15.0 s (enqueue 12.0 s) | 1 335 | 1.5 / 5.1 ms | 0 | 16.5 s |
| ORDERED, `GlobalCompetingConsumers` | 2 | 17.6 s (enqueue 16.9 s) | 1 134 | 1.9 / 8.3 ms | 0 | 13.5 s |

What it shows:

- **Keying by account removes conflicts entirely**; racing the same commands costs 15 % retries at this hot share
  (and at test size, 80 % on 2 hot accounts, commands exhausted 20 retries).
- **A `SingleGlobalConsumer` inbox defeats the shard-owned engine.** It takes a fenced lock ("Exclusive
  DurableQueueConsumer for Inbox" in the log), so one instance owns every shard (`liveInstances: 1`) and a second
  instance adds nothing. `GlobalCompetingConsumers` lets the engine split keys (`liveInstances: 2`) with per-key
  order intact — 0 conflicts with two instances.
- **The command side keeps pace; the producer and the views do not.** With competing consumers the drain ends under a
  second after the last enqueue. The single producer (~1.2–1.7 k messages/s in batches of 500) is the command-side
  ceiling, and the projections — each an exclusive `ViewEventProcessor`, one instance cluster-wide — process roughly
  700–800 events/s, so they trail every run by as long again as the run itself.
- **On `PostgresqlDurableQueues` (the tests' engine) ordered delivery of a hot key is poll-bound**: ~60 commands/s at
  test size with a 2 ms handler.

Next measurements: parallel producers partitioned by account (keeping per-key commit order), 50 000+ cycles, and the
views under a key-partitioned processor if EF-2 is built.

### Parallel producers, 2026-10-19 — and an ordering defect they exposed

Two instances, `GlobalCompetingConsumers`, 1 000 accounts, 5 000 cycles, one sample each:

| Producers | Hot share | Enqueue | Drain | Commands/s | View catch-up |
|---|---|---|---|---|---|
| 1 | 50 % | 18.2 s | 19.8 s | 1 010 | 17.0 s |
| 4 | 50 % | 5.3 s | 14.9 s | 1 339 | 29.6 s |
| 8 | 50 % | 3.7 s | 13.1 s | 1 531 | 23.4 s |
| 8 | 0 % | 3.4 s | 15.0 s | 1 333 | 17.7 s |
| 8 | 10 % | 2.2 s | 12.1 s | 1 654 | 19.2 s |

- **Producers are no longer the ceiling; the consumer side is, and not because of hot keys** (0 % hot drains no
  faster). The Hikari pool is not saturated (pending ≈ 0). 20 000 messages over 16 consumers in ~9 s is ~7 ms per
  message against ~2.5 ms in the handler: ~4 ms per message is spent outside it (inbox unit of work, ack, dispatch).
  An inference from one run, not a profile.
- **Ordering defect.** Every run with more than one producer (4 or 8) logged `OrderedShardOwner` warnings — "key 'X' is
  being delivered key_order 52 after 103 was already delivered" — 4 to 147 per run, on both instances; the runs with
  one producer logged none. Each key has exactly one producer, which numbers and commits its batches strictly in
  sequence, so the producer side cannot be the cause: the engine delivered a long-committed row late, or redelivered
  it. **It changed the outcome**: in the 50 000-cycle run (200 000 commands, run `LL-mvezwz95`) account
  `LL-mvezwz95-653` got `ReleaseHold` and `SettleTrade` for hold `LL-mvezwz95-H-30415` before its `HoldFunds` and
  `BookTrade`; both were no-ops (an unknown reference is ignored by design), so the hold was never released and the
  trade never settled — 201 998 of 202 000 events, with an empty queue and no dead letters. Not yet reproduced in the
  engine's own tests.

**Fixed in the engine** (`cbe0f747` on `queue_improvements`, merged here as `cd1e0616`): the ordered watermark retired
candidates against the horizon probe taken after a pass's read, so a writer committing between the two was stepped
over and its rows recovered late by the head sweep. Reproduced at engine level by `ShardOwnedConcurrentProducersOrderIT`.
Rerun of the same 200 000-command run (two instances, 8 producers, 50 % hot), 2026-10-19: DONE, 202 000 of 202 000
events, 0 ordering warnings on either instance, 1 365 commands/s, handler p50/p99 2.3/6.0 ms, enqueue 24.5 s, drain
146.5 s — and `account_activity` needed another **147 s** to catch up, the views (~700–800 events/s) now plainly the
bottleneck (EF-2).

## The order flow scenario (phase 3)

`OrderFlowLoadGenerator`, `POST /api/admin/order-flow/start?customers&ordersPerCustomer&threads&marketSharePercent&restingSharePercent&instrument`
(`GET /api/admin/order-flow` for the result). Simulated customers, each one account placing its orders one after
another, `threads` at once, through `PlaceOrder`/`CancelOrder` against the stub exchange: market buys, marketable
limit buys, and resting limits that are cancelled at once. It needs a fresh quote for `instrument` (the synthetic feed
quotes `ACME` by default). Done when every order released its hold and every fillable one is booked, counted in the
`Accounts` event table; end to end is the `TradeBooked` timestamp minus the moment `PlaceOrder` returned.

- **Placement and completion are separate numbers, on purpose.** Placement is the HTTP-equivalent write; completion
  includes both `EventProcessor` hops. They differ by 30×.
- **Per-stage latency comes from the event tables**: the `OrderPlaced`, `OrderSubmitted`, `OrderFilled` timestamps
  in `orders_events` and `TradeBooked` in `accounts_events`, joined on `EXE-<orderId>`.
- **A marketable limit is priced at ask × 1.05, and a run that strands orders still ends.** The stub matches a limit
  once, on arrival, against the ask of that moment, and an order arrives only after the pipeline's latency. At ask ×
  1.01 a slow run (pure polling: ~60 s from placement to submission) saw the synthetic feed move past its late orders:
  they rested for good, the run never drained, and it read exactly like lost events - the intermittent "stall" chased
  across several sessions (2026-10-21: 319 such orders in one run, every one inside the ask's band at its submission,
  no event missing). Now, once bookings and releases stand still for 10 s, the run cancels the orders the exchange
  accepted and neither filled nor cancelled, and reports them as `restedUnexpectedly`. Non-zero means the pipeline was
  slow enough for the market to move past them; `OrderFlowStrandedOrdersIT` pins the path.

### Measured, 2026-10-19, devcontainer, `compose` profile, one instance

| Run | Placed | Placement | Completion | End to end p50 / p99 |
|---|---|---|---|---|
| 50 × 20, 16 at once | 1 000 | 1 408/s | 71/s | 7.6 s / 13.5 s |
| 200 × 50, 32 at once | 10 000 | 3 174/s, p99 67 ms | 109/s | 59 s / 89 s |

**2026-10-22**, same 200 × 50 run with the demo's defaults (`trading-demo.orders.consumers` 1 / 1 / 2, 20-connection
pool) and the fixes since (CDC warm-up, shard-owned read plans, batched CDC inbox): **~196 completed orders/s on polling, ~184 on
CDC**, end to end p50 ~30 s on CDC, every run alike. What bounds it now is per-event handling; see
`docs/key-partitioned-event-processing.md` §5, "Two speeds, and where the ceiling sits now". With the `compose`
profile's present settings - 8 / 8 / 8 consumers, a 40-connection pool, the processors forwarding in batches of 100 -
it completes at **~366 orders/s**, end to end p50 ~16 s (same section, "Batched forward, built and measured"). The
paragraph below is the 2026-10-19 reading.

No conflicts and no refusals in either. The cap is the `EventProcessor` forward: the router reaches `OrderPlaced`
45 s late (p50) and the ledger processor adds 15 s, while the stub and the reports' inbox add nothing measurable.
Each processor forwards all five `Orders` events of an order, one per unit of work. Analysis and what it means for
EF-2: `docs/key-partitioned-event-processing.md` §5, "Field evidence".

## EF-2 measurement processors

`NoOpAccountsViewProcessor` and `NoOpAccountsEventProcessor` — off unless `trading-demo.ef2.noop-view-processor` /
`noop-event-processor` is `true`; `/api/admin/ef2/noop-stats` (GET, `POST /reset`). Handlers only count, so their rate
is the reader's ceiling (M1 in `docs/key-partitioned-event-processing.md` §5, results there). A fresh one reads the
stream from the beginning, so the existing backlog is the workload — measure a fixed window after warm-up, one
processor at a time. Their resume points persist under their own names; they do nothing while switched off.
`BatchedAccountsForwarder` (`trading-demo.ef2.batched-forwarder=true`, `.batch-size`, `.consumers`, `.run-token`) is
M4: the proposed EF-2 topology built from today's parts — fenced lock, batched subscription, one `addMessagesReceived`
per batch, competing inbox. A new `run-token` reads the backlog from the start. **Horizontal results from this
devcontainer are not evidence**: PostgreSQL and every JVM share 8 cores, PostgreSQL alone takes 2.5–4 of them under
load, and identical runs vary by tens of percent on the handling side.
