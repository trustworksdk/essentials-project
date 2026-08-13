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
