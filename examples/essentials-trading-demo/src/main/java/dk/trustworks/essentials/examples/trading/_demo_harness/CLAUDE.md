# `_demo_harness` — not a slice

This directory is the law's `_`-prefixed escape hatch (`rules/slice-design.md` § Directory
vocabulary): **excluded from slice enumeration and from the §R4 boundary check.** It is not a fifth
slice kind and nothing here is a bounded context.

What lives here is the machinery that makes the demo demonstrate something:

| Component | What it is |
|---|---|
| `TradingSimulationRunner` | One-shot bootstrap that seeds instruments, accounts, trades and settlements so a freshly started app has something to look at. Idempotent — it probes for existing seed data and skips |
| `TradingLoadGeneratorManager` | Generates continuous runtime traffic, plus on-demand bursts and two benchmark scenarios |
| `TradingLoadGeneratorController` | The harness's own admin API |
| `TradingDashboard*` | The lightweight status screen and its SSE stream |
| `DirectInstrumentPriceService` | A deliberately **non**-event-sourced latest-price table, written with raw JDBC, whose only purpose is to be benchmarked against the `market_data` aggregate path |
| `QueueLoadGenerator` | Drives the **shard-owned queue engine** on both lanes at once — sustained trickle plus on-demand spikes — and checks per-key ordering as messages arrive |
| `QueueLoadGeneratorController` | `/api/admin/queue-load` — status, start/stop, `POST /spike?size=N` |
| `ShardOwnedDurableQueuesConfiguration` | Puts the app's whole `DurableQueues` — every `EventProcessor`'s Inbox included — on the shard-owned engine. Property-gated so the default engine stays an A/B |

## Why these are not slices

An automation slice reacts to a domain event and issues a follow-up command; a view slice answers a
query over a read model it owns. The load generator does neither — it *manufactures* activity that a
real deployment would receive from users, and the dashboard reports on the harness itself (counters,
timings, snapshot metrics) as much as on the domain. Classifying them as automations and views would
put demo scaffolding on the same footing as the domain, which is the bigger lie.

## Rules that still apply here

Being outside the boundary check is not permission to reach into the contexts:

- **Writes go through the command bus.** Every domain mutation is `commandBus.send(new SomeCommand(…))`,
  naming a slice's command type — which is §R4's sanctioned collaboration, not an exception to it.
- **Reads go through view slices**, never by rehydrating an aggregate. Nothing here reads an aggregate
  *field*. Two classes hold a repository wrapper, both documented below and for different reasons.
- **It may not be imported by a bounded context.** The dependency runs one way. If something here
  looks like the contexts need it, it belongs in a slice instead.

## Exception 1: the bootstrap idempotency probe

`TradingSimulationRunner.seedDataState()` — and nothing else in this package — may inject `TradingAccounts`,
`Trades`, `Settlements` and `Instruments`, and may call their `findX(…)` methods. It must be **strongly
consistent**: the brokerage projections are eventually consistent, so on a restart against a populated database
a projection-backed probe could answer "absent" while the data is there, and the runner would seed a second time
on top of existing data. The price half of the probe uses `LatestPriceQuery`, which reads the price aggregate and
is already strongly consistent.

The probe reads *existence*, never a field, and it runs once at startup.

## Exception 2: the closing-books benchmark's read pass

`TradingLoadGeneratorManager.runTradingAccountScenario` injects `TradingAccounts` and calls `getAccount(...)`
inside its timed read loop, one unit of work per load.

That scenario exists to measure **how expensive it is to rehydrate a trading account** under two rollover
policies — how far back the replay reaches, and how often a snapshot spares it. Rehydration is a write-model
operation by definition, so `readElapsedMillis` and the four `snapshot*Delta` figures only mean anything if
the pass performs one. Routed through `brokerage.account_statement` it would time a single-row `SELECT`
against a projection and every snapshot delta would read zero — measuring nothing the scenario claims to
compare.

The load *is* the measurement: the returned aggregate is discarded and no field is read off it.

## Everything else reads a view slice

Two of those reads are eventually consistent and knowingly so: the dashboard's account balances and the load
generator's seed-data check both come from `brokerage.account_statement`. The dashboard can therefore show
fewer accounts than are configured for a moment after bootstrap.

## `DirectInstrumentPriceService` is here on purpose

It is a second write path for a concept `market_data` already owns, which would be a sole-writer
defect if it were domain code. It is not: it exists so the dashboard can show what event sourcing
costs against a plain upsert, and keeping it in the harness is what guarantees no domain path can
read it by accident. The authoritative latest price is always the `InstrumentPrice` aggregate.

See `../../REFACTORING_PLAN.md` § Open questions for the argument that it belongs in `market_data`
instead.

## The shard-owned queue exercise

`QueueLoadGenerator` exists because the queue engine is unpublished and experimental: this app is
where it meets a real Spring application, a shared connection pool and a database that is also
serving an event store. Three things it caught that the engine's own tests could not, all of them
misuse rather than engine defects — which is the point of an integration demo.

- **`@Scheduled` is inert here.** The demo has no `@EnableScheduling`, so the annotation binds,
  validates and never fires. A load generator that generates no load is the quietest possible
  failure. Own a `ScheduledExecutorService`, as `TradingLoadGeneratorManager` does.
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

Measured after those three were fixed, one instance, 4 unordered shards and 64 ordered units:

| | |
|---|---|
| spike | 100 000 messages (50 000 per lane) in one call |
| backlog at t+5s | 39 739 unordered, 34 257 ordered |
| fully drained | t+20s, roughly 5 000 msg/s combined |
| ordering violations | 0, against a 34 000-deep ordered queue |
| dead letters | 0 |
| ownership | 4 + 64 units, `fullyOwned` throughout |

`unownedShards` is the field to watch, not depth: depth cannot tell "nobody is consuming" from
"busy", and this engine's ownership failures have historically been invisible in depth alone.

## The Inbox exercise, which is the more honest one

`QueueLoadGenerator`'s handler sleeps a millisecond. Real handlers open a unit of work and write SQL,
and the way to exercise that without writing a fake is to put the app's own `DurableQueues` on the
engine: an `EventProcessor` forwards what it consumes through an `Inbox`, and an `Inbox` is a queue.
`ShardOwnedDurableQueuesConfiguration` does that, so the four projections are delivered by the engine
and their handlers do the real work. The engine auto-registers the queues the processors invent:

```
DefaultCommandQueue | Inbox:TradeSettlementProjection | Inbox:TradeValuationProjection
InstrumentDetailsProjection:queue | TradingAccountStatementProjection:queue | trading-events
```

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

### Measured, one instance, 4 unordered shards and 64 ordered units per queue

Trade bursts stop contributing at `maxGeneratedTrades` (500), so the spike lever is price updates —
also the heavier message, since each one updates every trade on its instrument (~250 rows). Eight
concurrent producers × 5 000:

| | |
|---|---|
| peak backlog | ~2 450 on `Inbox:TradeValuationProjection` |
| peak lag | ~8s, held flat while producers ran |
| drain | 2 441 → 0 in ~4s once producers stopped (~600/s, each message a bulk `UPDATE`) |
| dead letters | 0 |
| errors | 0 |
| fence | 1 on all 12 lanes throughout — no ownership churn under load |

The shape is the finding: lag plateaus rather than growing without bound while producers run, and the
backlog only appears once *producers* are made concurrent. A single synchronous burst endpoint cannot
outrun the projections — 4 000 price updates in 6s never took the Inbox past a depth of 8. That the
fence never moved is what the instance-liveness redesign was for; a lease-expiry design would have
churned ownership exactly here, under load, while the pool was contended.
