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
| `FundingLoadGenerator`, `FundingLoadController` | Phase 2: simulated customers depositing and withdrawing, against the stub or the payment gateway, `/api/admin/funding-load` |
| `NoOpAccounts*Processor`, `BatchedAccountsForwarder` | EF-2 measurement processors, off by default |
| `load_driver/` (`LoadDriver`, `run-load.sh`) | Phase 6: the HTTP load driver - **not part of the application**, a plain `main` run in a JVM of its own |
| `LoadRunReport`, `LoadRunReportController` | `GET /api/admin/load-report?runPrefix=`: what the brokerage recorded of a driver run - orders by status, placed→filled/booked latency overall and per 10 s placement bucket, payments, durable-queue depth and dead letters, largest tables |

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

## The funding scenario (phase 2)

`FundingLoadGenerator`, `POST /api/admin/funding-load/start?customers&depositsPerCustomer&withdrawalsPerCustomer&threads`
(`GET /api/admin/funding-load`). Fresh `FL-<run>-<n>` accounts, each with a linked bank account and 1 000 000 of
seeded withdrawable cash (credited and cleared through the ledger's operator commands, so withdrawals need not wait out
a return window); each customer requests its deposits, then its withdrawals, `threads` customers at once. Done when
every payment has an outcome on `Deposits`/`Withdrawals` and every completed one is booked (`DepositCredited` /
`CashWithdrawn`, the seed deposit discounted); end to end is that booking's timestamp minus the request's return.

### Measured, 2026-10-22, devcontainer, `compose,simulators`, simulators 0.4.0, one instance, one sample

200 customers × (5 deposits + 5 withdrawals), 32 at once, fresh database and simulators:

| | |
|---|---|
| requested | 2 000 in 0.57 s - 3 527/s, p50 7 ms, p99 25 ms; 0 refused, 0 conflicts |
| drained | 19.7 s - 102 completed payments/s; 2 000 completed, 0 failed, 2 000 booked, 0 dead letters |
| deposit, request → ledger | p50 5.9 s, p99 10.2 s |
| withdrawal, request → ledger | p50 10.4 s, p99 18.2 s |
| reconciliation | the next bank statement: 2 000 entries, 0 breaks |

End to end is mostly the gateway's own simulated delays on the 1440× clock: a deposit 60-1 800 domain seconds (0.04-1.25
real s), a withdrawal 600-14 400 (0.4-10 s). The brokerage's side is the request (ms) and the two hops - gateway event
into `Funding:PaymentReports`, outcome into the ledger.

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

## The load driver (phase 6)

`./run-load.sh [--key=value ...]` against running instances (`docs/design/phase-6-realistic-load.md`). Customers
`LDA-<n>` (user `lda-<n>@load.local`, password `load-driver-password`), registered, linked and funded on first use and
reused by later runs; orders and payments `LD-<run>-O<n>` / `-D<n>` / `-W<n>`. Each customer logs in once through the
form login (CSRF token from `/login`, then `JSESSIONID`) and is pinned to `base-urls[n % size]`. Open-loop: steady
`orders-per-second`, `burst-orders-per-second` in the first `burst-fraction` of each business day (a close starts the
day), `payments-per-second`; `business-days` closes. Settings and defaults: `LoadDriverSettings`. Exit code 0 only if
every check passed; the full report goes to `target/load-driver/load-<run>.json`.

- **`main`, not Spring.** A Spring Boot app in this module would start every context and the Docker Compose support.
  It lives under `_demo_harness/load_driver`, so the application's component scan sees it, but it carries no
  annotations and registers nothing.
- **`run-load.sh` does not compile.** It runs `target/classes` with the runtime classpath resolved once into
  `target/load-driver.classpath`. Compile the module yourself, never while an instance is starting (`target/` has more
  than one writer - root `CLAUDE.md`).
- **Login is the expensive request**: ~100 ms p50 (BCrypt), which is why a session logs in once. HTTP Basic per request
  would cost that on every call.
- **A `DAY` order can legitimately live several business days' worth of real minutes**: one that reaches the exchange
  after a Friday cutoff belongs to the next business day, and a holiday Monday (2030-01-21) puts its expiry four closes
  away. The drain waits for it (`drain-timeout`, default 10 minutes).
- **`skipped:driver-saturated`** counts actions not started because `max-in-flight` were running; non-zero means the
  driver, not the brokerage, set the rate.

### Baseline, 2026-10-23, devcontainer, `compose,simulators`, simulators 0.6.0, one instance, one sample each

Same 2 000-customer population throughout, reads 3 per order, mix 45/20/20/15 (market buy / marketable limit / sell /
resting), 5 business days (~6 real minutes of load):

| Run | Orders | Steady / burst | Placement p50 / p99 | Placed → booked p50 / p99 | Checks |
|---|---|---|---|---|---|
| `g840`, 500 customers, 300 active | 8 964 | 20 / 100 per s | 3.3 / 22 ms | 194 / 752 ms | all pass |
| `g9bx`, 2 000 customers, 1 000 active | 46 263 (~127/s) | 100 / 600 per s | 10.7 / 414 ms | **5.3 s / 202 s** | pass, except driver saturated (2 036 skipped) |

- **Correctness held at the full target**: every order and payment reached its outcome, every fill was booked, 0 dead
  letters, 0 reconciliation cases, 0 missing statements.
- **Latency did not**: the 600/s bursts build a backlog in the order pipeline that one business day (60 s) does not
  drain, so it grows day by day (booked p99 51 s after day 3, 116 s after day 5); `CdcEventStore` logs "fell more than
  100 events behind the CDC bus" for every `Orders` subscriber. The known `EventProcessor` forward ceiling
  (~366 orders/s in process, EF-2), now with the HTTP edge, reads and every flow on the same 8 cores. Not yet split
  by stage; the report's buckets in `target/load-driver/load-g9bx.json` have the per-10 s curve.
- **The driver saturated** in the bursts (`max-in-flight` 1 024): with reads at p99 ~250-380 ms an action holds its
  permit for over a second. A larger `max-in-flight` or a lower burst; the brokerage, not the driver, is the limit.
- **First attempt (`g8ox`) failed for a reason outside the brokerage**: the exchange's liquidity provider hit its
  `max_inventory` (10 000 shares short per instrument) because customers buy more than they sell, stopped quoting asks,
  and with no ask there is no quote - 18 424 market buys refused "No current price", 5 055 `SELF_TRADE` rejections
  where our sells met our own resting buys priced off the driver's stale quotes. Fixed: `max_inventory` 10 000 000 in
  `simulators/exchange.yaml`, and the driver drops a quote that is missing or stale.

### Virtual threads, 2026-10-23, same profile as `g9bx`, one sample

`spring.threads.virtual.enabled: true` (Tomcat on virtual threads, `7e9deb6c`, **reverted** after this run), run `gehb`: **worse on every count.**
40 671 orders; driver skipped 6 648 (was 2 036); read p99 ~0.7-1.1 s (was ~0.25-0.38 s); after the 10-minute drain
9 865 orders still open and 2 492 fills not booked. The new stage split puts the backlog in the **router**:
placed → submitted p50 17 s, p99 948 s; filled → booked p50 1 s. (Submitted → filled is negative at p50: the exchange's
fill report is often recorded before the router records `OrderSubmitted`, which D11 tolerates.)

- **Unconfirmed explanation**: Tomcat's 200 platform threads were admission control. On virtual threads every request
  is admitted, up to ~1 000 at once, and they all compete with the event processors and inbox consumers for the same
  40 pooled connections and 8 cores. No Hikari timeout was logged, so this is not proven; the next run should record
  `hikaricp.connections.pending` and the router's lag.
- **Not like for like**: `gehb` ran on a database holding the earlier runs' ~55 000 orders; `g9bx` on ~9 000.
- Errors in the log were the expected kinds: `RecordSettlementReport` refused until the fill is projected (redelivered,
  10 634), buying-power refusals, re-registrations; 140 `OrderedShardOwner` key-order warnings on the exchange reports'
  inbox (network reordering, `integration/CLAUDE.md`).
- **Reverted** to Tomcat's platform pool: the baseline runs on the known configuration. Virtual threads with a cap on
  concurrent requests are a separate A/B, on a fresh database.

### Burst sweep, 2026-10-23 - the one-instance baseline

What it answers: **the largest market-open rush one instance absorbs** while the steady load is 100 orders/s. Each run
on a fresh database (`trading_load`) and freshly reset simulators, 2 000 customers / 1 000 active, 5 business days,
`max-in-flight` 2 048; platform threads. Script in the session scratchpad (`load-sweep.sh`: reset simulators, recreate
the DB and its CDC slot, start one instance on it, run, stop).

| Rush (6 s at each open) | Orders | Placed → booked p50 / p99 | Router (placed → submitted) p50 / p99 | Ledger (filled → booked) p50 / p99 | Checks |
|---|---|---|---|---|---|
| 200/s | 42 441 | 0.37 s / 29 s | 0.22 s / 31 s | 0.16 s / 9 s | all pass |
| 300/s | 44 488 | 0.41 s / 50 s | 0.28 s / 49 s | 0.18 s / 17 s | all pass |
| 400/s | 47 282 | 0.53 s / 102 s | **2.2 s** / 94 s | 0.21 s / 62 s | all pass |

- **Baseline: rushes up to 300/s.** The median stays under half a second and a rush's backlog clears before the next
  open. At 400/s the router's median jumps tenfold: that is the knee. The router (`ExchangeOrderRouter`, an exclusive
  `EventProcessor`) is the first stage to fall behind; the ledger processor follows at a distance.
- **One open per run is far worse than the others, at the same point every time**: the 5th-6th minute of each run
  (worst 10 s median 16 s at 200/s, 20 s at 300/s, 27 s at 400/s; the other opens 0.3-3 s). **Cause: the exchange's
  settlement wave.** It completes a settlement date's instructions all at one instant, 00:00 UTC (400/s run: 3 305,
  6 581, **16 860**, 6 202, 6 927 `SettlementCompleted` per date), and each one becomes a `TradeSettlement` event, a
  `SettleTrade` on the account and its projections - up to ~18 000 ledger events a minute on top of the order flow,
  through the same pool, CPU and CDC bus. The big one is Tuesday's: without trading sessions customers trade through
  the weekend, those trades are dated Monday, and Tuesday settles three days of trading. Closing books is not involved:
  0 `AccountBooksClosed` in the run - books roll at New York midnight on the application's real clock until EF-1.
  Not yet known: which shared resource carries the interference (CDC bus, connection pool, or CPU).
- **Correct throughout**: every order and payment reached its outcome, every fill booked, 0 dead letters, 0 cases,
  0 missing statements, at every rush rate.

### Business-day trading and the settlement wave, 2026-10-23

The driver now trades only inside the exchange's business days (`0eec2946`). Same 300/s profile, fresh database and
simulators, 7 business days (a weekend inside), sampled every ~3 s (pool, each subscriber's lag behind its stream's
head, queue depths, container CPU; `wave-sampler.sh` in the session scratchpad):

| | Trading 24/7 (`burst300`, earlier) | Business days only |
|---|---|---|
| Settlement completions per date | 3 305 - **16 860** | 2 765 - 4 563, flat |
| Worst 10 s median, placed → booked | 20 s | **1.8 s** |
| Placed → booked p50 / p99 | 0.41 s / 50 s | 0.37 s / **12.5 s** |
| Router p99 / ledger p99 | 49 s / 17 s | 15.5 s / 2.4 s |
| Checks | all pass | all pass |

- **The wave now lands outside trading.** The exchange settles at 00:00 UTC, which is 19:00 in New York, after the
  16:30 cutoff: the settlement ledger processor's lag peaks (1 000-3 000 events) while the order flow is idle. A
  nightly settlement batch is what real clearing does, so this is the realistic case; the overlap seen earlier came
  from weekend trading.
- **Rarely the connection pool**: threads waited for a connection in 2 of 256 samples (9 at most, once during the
  first open); active connections usually well under 40. *Corrected: first recorded as "0 in every sample" from a
  window of the run.* PostgreSQL runs at ~2 cores while trading.
- **Behind the opens, one stage per processor**: at each open the router falls ~1 000-1 600 `Orders` events behind and
  catches up within the business day (~40 real seconds); the ledger processor tracks it. **`OrdersProjection` falls
  5 000-7 500 events behind** and catches up only in the evening: a customer's order list is seconds stale through
  the trading day. The view ceiling of phase 1 (~700-800 events/s per exclusive `ViewEventProcessor`), now visible to
  a customer - the case for the parked batched view handling.
- The sampler's `cpu:app` (`ps %cpu`) is a lifetime average and useless; read the app's CPU from `top` next time.

### The order path under a rush, 2026-10-23/24

Same 300/s profile, business days only, 7 days, one sample each unless noted. First the batched view handling
(`987d5c88`/`80bc5185`, compose batch 100), then where placed → submitted actually goes.

| Configuration | Placed → booked p50 / p99 | Placed → submitted p50 / p99 | Checks |
|---|---|---|---|
| Views unbatched (business-day run above) | 0.37 s / 12.5 s | 0.26 s / 15.5 s | all pass |
| Views batched 100, pool 40 - first run | 0.48 s / 17.8 s | 1.23 s / 23.9 s | all pass |
| Views batched 100, pool 40 - two reruns | 0.37 / 11.6 s, 0.36 / 10.6 s | 0.27 / 15.7 s, 0.25 / 14.4 s | all pass |
| Views batched 20, pool 40 | 0.34 s / 10.4 s | 0.22 s / 13.5 s | all pass |
| Views batched 100, pool 60 | 0.35 s / 11.9 s | 0.23 s / 14.7 s | all pass |
| Views batched 100, pool 40, **synchronous answers not reported** | **0.31 s / 8.1 s** | **0.15 s / 7.0 s** | all pass |

- **Batched views do not slow the order path.** The first batched run was an outlier; two reruns match the unbatched
  baseline. Threads waited for a connection in under 10 samples per run - not the pool.
- **The router is not slow; the exchange answers in 11 ms (p99 28 ms).** Split with debug lines in
  `ExchangeOrderRouter` and `ExchangeReports` plus event timestamps (35 521 orders, before the fix): placed → router
  handler start p90 3.6 s / p99 10 s, then the router's answer waited in `Orders:ExchangeReports` p90 7.0 s / p99 10.6 s.
  `OrderSubmitted` is the last thing recorded: `OrderAccepted` p90 3.5 s, `OrderFilled` 3.8 s, `OrderSubmitted` 9.7 s.
- **Every exchange fact went through `Orders:ExchangeReports` twice** - once from the `201` (Q-2), once from the
  webhook - about 5.5 messages per order. The inbox drains ~1 400/s on 8 consumers and a 300/s rush needs ~1 650/s:
  backlog ~2 950. Reporting only the submission's outcome from the `201` (Q-2 revised, now the default) cut its messages
  40 %, its backlog to 414 and its wait to p90 1.5 s / p99 3.4 s; `OrderAccepted` p90 3.5 → 1.4 s. Cost: a fill records
  ~80 ms later when quiet (submitted → filled p50 -60 → +20 ms).
- **The subscription lag the sampler shows is mostly its own artefact.** It reads the *saved* resume point, which the
  compose profile writes for every subscriber at once every 2 s (`snapshot-resume-points-every`): up to 2 s of events
  read as lag - hence the identical sawtooth on every `Orders` subscriber. Measured with the processor's
  `Forwarding-To-Inbox` debug line instead, placed → forwarded to the router's inbox is p99 175 ms.
- **The rest was the router's inbox, and behind it the shard-owned engine's pumps.** Forwarded → handler start p99
  6.4 s, while thread dumps found at most one of the router's 8 consumers inside its handler: both `shard-queue-pump`
  threads, which deliver and acknowledge for every queue, sat in database round trips. The router started at most
  ~230 orders/s against 300/s arriving. `pump-threads` (A on, one sample each):

| `pump-threads` | Placed → booked p99 | Placed → submitted p99 | Router inbox wait p90 / p99 | Reports inbox wait p99 | Placed → forwarded p99 |
|---|---|---|---|---|---|
| 2 | 8.1 s | 7.1 s | 1.2 s / 6.4 s | 3.9 s | 175 ms |
| **4 (compose default now)** | **6.8 s** | **5.0 s** | **0.56 s / 4.6 s** | 0.52 s | 315 ms |
| 8 | 7.8 s | 6.3 s | 0.93 s / 5.9 s | 0.14 s | 474 ms |

  At 8 the router's handlers were busy (3-7 of 8 in dumps) and half of them waited in Hikari's `borrow`: eight pumps
  hold eight connections for good. The pumps themselves spent 25 of 40 sampled stacks in `runningWriteTransactionIds`
  (the safe-watermark query, once per owner per pump cycle), then acknowledgements and reads. Open: pumps that scale
  between a minimum and a maximum instead of holding connections for good, and a cheaper watermark query.

Then, 4 pumps throughout, one instrumented run each (debug lines for the router, the reports inbox, the processor's
forwarding and the webhook receiver):

| Change | Placed → booked p50 / p99 | Placed → submitted p99 | Filled → booked p99 | Pool: samples waiting / max waiting |
|---|---|---|---|---|
| one horizon probe per pump pass (`3be11d75`) | 321 ms / 7.6 s | 5.6 s | 4.1 s | 16 / 74 |
| + 16 router consumers (not kept) | 352 ms / 8.3 s | 1.6 s | 5.6 s | 17 / 99 |
| + key check on the caller's connection, once per view batch | 283 ms / 5.2 s | 4.5 s | 1.1 s | 12 / 40 |

- **The cheaper probe moved the bottleneck, not the end result**: probe stacks 25/40 -> 2/24 in the dumps, the router's
  8 handlers busy instead of idle (mostly in the exchange's HTTP reply). 16 router consumers emptied the router's inbox
  and pushed the wait onto the fills: the exchange's webhook backlog (~480 deliveries, ~70 in flight) and the
  reports inbox, with the pool fully used at every open. Kept at 8.
- **The pool was being held idle, not used.** At saturation PostgreSQL showed 19-25 sessions `idle in transaction`
  and 2-9 active. One cause found: every view processor's per-event `hasOrderedMessageQueuedForKey` took a SECOND
  pooled connection on the shard-owned engine while its batch held the first (~5 at a time). Fixed in the library -
  the adapter asks on the unit of work's connection, and a batched view asks once per batch
  (`getKeysWithOrderedMessagesQueued`). The run after it looked like a large gain (booked p99 5.2 s) - see below for
  why it was not.
- Still open: 14-22 sessions `idle in transaction` at peaks from causes not yet named, and the router's inbox
  (p99 3.8 s) behind 8 consumers waiting on the exchange.
**Single runs were within noise, and the machine is CPU-bound at every rush.** The same code, uninstrumented, gave booked
p99 8.5 s the run after 5.2 s. Five alternating uninstrumented runs, `3be11d75` (before the key check fix) against the
fix:

| Run | Booked p50 / p99 | Placed → submitted p99 | Filled → booked p99 | Pool: max waiting / samples waiting |
|---|---|---|---|---|
| before 1 | 311 ms / 7.0 s | 5.5 s | 3.3 s | 67 / 13 |
| before 3 | 343 ms / 8.1 s | 6.1 s | 3.1 s | 77 / 13 |
| fix 1 | 275 ms / 5.0 s | 2.4 s | 3.6 s | 48 / 10 |
| fix 2 | 317 ms / 8.1 s | 5.9 s | 3.9 s | 60 / 12 |
| fix 3 | 318 ms / 7.5 s | 5.6 s | 4.2 s | 59 / 13 |

(before 2 lost its result: the driver's final load-report request timed out.) No measurable end-to-end change; fewer
threads wait for the pool. The reason is in the JVM's CPU, sampled per interval with `top` (the earlier `ps %cpu` was
a lifetime average): 100-200 % between rushes, **760-800 % of the 8 cores during every rush** (about 13 % of the
samples), with PostgreSQL (~200 %), the exchange simulator and the load driver's own JVM on the same cores. The
`idle in transaction` sessions (18-27 at saturation, with or without debug logging) are threads holding a transaction
that are waiting for a core. Connection waits are therefore not what bounds a rush on this machine, and comparing
end-to-end latency needs several alternating runs per arm. Next: what the JVM spends its CPU on during a rush (JFR).
**Where the JVM's CPU went, and the pump that visits only owners with work.** A JFR profile across a rush
(`settings=profile`, which itself slowed the run to booked p99 ~12 s - shares only): HTTP request threads 36 % of the
samples, `shard-queue-pump` threads 18 % (10.6 % in asking every owner whether it needed attention), Jackson ~11 %,
subscription gap handling ~6-7 %, reflection in `AggregateRoot.initialize` 2.8 %. bcrypt (6.6 %) was the monitoring
scripts' HTTP Basic polls, not the driver, whose customers keep a session. The pump now visits only owners that
signalled or came due (`752c4fa2`, `docs/durable-queue-measurements.md` §3.13). Alternating uninstrumented runs,
before (`a106dae6`) against it:

| | Before, 6 runs | One-visit pump, 5 runs |
|---|---|---|
| Placed → booked p99, median (each run) | 5.0 s (3.8, 4.0, 5.0, 5.1, 5.4, 6.0) | **2.9 s** (2.6, 2.8, 2.9, 4.5, 5.6) |
| Placed → submitted p99, median | 2.6 s | 1.4 s |
| Filled → booked p99, median | 3.2 s | 1.7 s |
| Placed → booked p50 | 273-286 ms | 271-283 ms |

Probably better, not proven: three of the five runs beat every run before, but the arms overlap and a rank test on
the eleven stops short of significance. The JVM is still at 630-700 % of the 8 cores during rushes in both arms. Two
of the eighteen runs over these two days lost their result to the driver's final load-report request timing out;
`drain()` now asks again until its deadline (`report-retried` in the step counts).

**Subscription gap handling.** JFR (`settings=default`, 1 GB, whole run) plus `pg_stat_statements`: the gap handler
was 10.5 % of the JVM's CPU samples while trading (12-13 % at the busiest minutes) and ~540 000 statements per run.
Cause: ~6 400 `Orders` sequence holes per run - one per append that loses an optimistic-concurrency race - each
recorded, held and promoted by every subscriber of the aggregate type. 69 % of the handler's samples ended in JDK
collections code: a set of all known gaps rebuilt per batch, and the promotion strategy streamed over every gap on the
CDC path, where its result is always discarded. After the fix (skip that promotion; record new gaps in one statement):
4.7 % of the samples, record statements 490 882 → 266 826, their DB time 12.2 → 8.2 s; booked p99 4.3 s, all checks
pass. A rewrite of the give-up statement was reverted: fast on two synthetic ranges, 5-9 s a call on real ones, it
stopped every `Orders` subscriber for 65 s (booked p99 94.5 s, a dead letter).

- Instruments used: `SignedWebhookReceiver` debug lines (arrival before any connection, queued after the intake
  commits) against the router's "Exchange accepted" line; the exchange's own `/api/webhooks/{id}/deliveries?status=`
  counts; thread dumps and `pg_stat_activity` triggered on more than 20 pool waiters. The exchange's `occurredAt` is on
  its virtual clock and cannot be compared with real time.
- **PostgreSQL's NOTIFY lock shows at peaks.** Snapshots caught 5-13 `COMMIT`s waiting on `object` 1262/0 (the
  cluster-wide notification-queue lock, held through the commit's WAL flush) in about one in three snapshots. Every
  shard-owned enqueue transaction sends one `pg_notify` (coalesced per shard per transaction); a single-message inbox
  enqueue is one each. No setting removes it; fewer notifying commits does. Not the bottleneck yet.
- Side findings, not chased: ~240 ordered-unit "delivered key_order N after N+2" warnings per run on the settlement
  queue, each followed by a failed `RecordSettlementReport` (retried; checks pass); ~4 000 more webhook acceptances
  consumed than orders, so some copies pass the intake's deduplication.

### The JVM's CPU at the opens, 2026-10-27 - and the JIT every earlier run used

**`spring-boot:run` runs the JVM with the C1 compiler only.** The Spring Boot Maven plugin adds
`-XX:TieredStopAtLevel=1` unless `-Dspring-boot.run.optimizedLaunch=false`, and `run-instance.sh` launches through it.
Same 300/s profile, business days only, 7 days, `compose,simulators`, queue load generator off, fresh database and
simulators, the application started with plain `java` from `target/classes`:

| JIT | JVM CPU while trading, p50 / p99 / max | Placed → booked p50 / p99 | Filled → booked p99 |
|---|---|---|---|
| C1 only (`spring-boot:run` default) | 163 / 397 / 405 % | 240 ms / 1 238 ms | 686 ms |
| Tiered, C2 (plain `java`) | 104 / 254 / 293 % | 213 ms / 399 ms | 187 ms |

So every load measurement above was probably taken on C1-only code. Under C2 the opens no longer saturate the 8 cores;
the setup phase (2 000 registrations and logins, BCrypt) is the only stretch above 60 %. Measure with plain `java`, or
pass `optimizedLaunch=false`.

Where the JVM's CPU went while trading (C2, JFR `settings=default`): `Accounts.getAccount` 26 % of the samples, almost
all replaying events (snapshot loads 1.2 %); `PatternMatchingMethodInvoker` 19 %, of which ~7 % went to log
descriptions built on every call with trace logging off (fixed in the library, `81e3e158`); the Spring Security filter
chain 5 %.

### Snapshot interval, 2026-10-27

`Account` snapshotted every 100 events, but under this load an account sees ~65 events per run, so most accounts never
got a snapshot and every load replayed the whole history. Same profile as above, C2, two alternating runs per arm,
`essentials.eventstore.snapshots.aggregates[Accounts].every-n-events` overriding the annotation:

| Arm | JFR samples while trading | CPU-seconds (`top`) | `getAccount` / rehydrate / snapshot store samples | Snapshot inserts | Booked p99 |
|---|---|---|---|---|---|
| every 100, before the invoker fix | 6 885 | 365 | 1 645 / 1 534 / 80 | 228 | 379, 408 ms |
| every 100 | 6 244 | 354 | 1 172 / 1 038 / 80 | 238 | 370, 366 ms |
| **every 25 (now the policy)** | **5 798** | **340** | 792 / 482 / 325 | 4 228 | 351, 565 ms |
| every 10 | 5 872 | 346 | 660 / 280 / 452 | 12 158 | 359, 338 ms |

- 25 cuts the JVM's samples ~7 % against 100; 10 replays less again but pays it back in snapshot writes (three times
  the inserts of 25, each a serialization and an insert inside the command's transaction, as the policy is `SYNC`).
- Latency did not move beyond the spread (the 565 ms is one run; its pair was 351 ms): at this load the demo is not
  CPU-bound under C2, so the saving is headroom.
- Every run passed every check. Scripts and per-run lines: `~/trading-demo-profiling/cpu-2026-10-27/` on the machine
  that ran them (`rush-prof.sh`, `sweep.sh`, `JfrHot.java`); the exchange's `/test/reset` takes minutes after a full
  run, so the runner recreates only the two simulator containers and their own volumes instead.
