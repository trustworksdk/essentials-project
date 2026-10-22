# Bounded context: market_data

Instrument reference data — the brokerage's decision to offer an instrument (D21) — and the current market as the
exchange's market-data feed reports it.

No invariant is shared with trading — nothing here reads or writes a trading account, a trade or a settlement.

**Write style: aggregate (`rules/slice-design.md` §R5).** Decisions live in `aggregates/`, on `Instrument`, an
`AggregateRoot` reached through a `StatefulAggregateRepository`. This is a sanctioned lane, not a legacy one — do
**not** convert it to a `Decider`. Command slices still own
everything else: their command type, their one API file, their handler and their test.

## Slices

| Slice | Kind | Role |
|---|---|---|
| `use_cases/register_instrument` | command | Registers an instrument with its symbol and display name |
| `use_cases/rename_instrument` | command | Changes an instrument's display name |
| `use_cases/suspend_instrument` | command | Suspends an instrument, with a reason. One-way |
| `use_cases/risk_approve_instrument` | automation | Runs a newly registered instrument through an external risk assessment and records the answer. The demo's `UnitOfWorkMode.NONE` example |
| `views/instrument_details` | view | Lists instruments and serves one by id, from a projection. Includes risk status |
| `views/market_quotes` | view | Latest quote per instrument from the exchange's market-data stream (synthetic without the simulators), in memory. Implements the `MarketQuotes` seam |

## `views/market_quotes` — the market as the exchange's feed reports it (D14, D19)

- **Not event-sourced and not persisted.** A quote is a high-rate feed, not a decision. Each instance subscribes to
  the feed itself and keeps the latest quote per instrument in memory; nothing is shared between instances.
- **`types/MarketQuotes` is a published read seam** (`rules/slice-design.md`, § The published read seam): `orders`
  sizes a market buy's hold on `bestAsk`, and the stub exchange prices its fills on it. It answers only from a fresh
  quote — stale (`trading-demo.market-data.stale-after`, 10s) is empty, so a hold is never sized on a vanished price.
- **Two feeds behind `MarketQuoteFeed`.** Under the `simulators` profile `ExchangeMarketQuoteFeed` reads the exchange's
  SSE stream (`/api/marketdata/stream`, SR-13) and resynchronises from its snapshot after every (re)subscription;
  otherwise `SyntheticMarketQuoteFeed`, a random walk per configured instrument (`trading-demo.market-data.synthetic.*`;
  `volatility: 0` holds quotes still, for tests). Both conditional on `trading-demo.simulators.exchange.base-url`.
- **The exchange quotes only on change, so the feed re-reads the snapshot every half `stale-after`**, and
  `LatestMarketQuotes` lets an *equal* `mdSequence` refresh a quote. Stale means "the exchange stopped confirming it",
  not "the price did not move" - otherwise a quiet book (a closed market, live prices) refuses every market buy `422`.
- **An empty side (`bestBid`/`bestAsk` null) publishes nothing**; the last quote goes stale rather than be trusted.
  `reference` messages are not quoted - the price source's price is not the market's.
- **It replaced the `InstrumentPrice` aggregate** (D19, removed in phase 3), together with its `initialize_price`
  and `update_price` slices, `views/latest_price` and the harness's price-path benchmark. Prices are a high-rate
  feed, not decisions; the aggregate was an exercise.

## Every mutating method is an idempotent no-op

`Instrument.rename` to the display name already held, `Instrument.suspend` on an already-suspended instrument,
and `Instrument.recordRiskApproval` / `recordRiskRejection` on an instrument that already carries a risk
decision all return without applying. Repeat commands leave no trace instead of growing history. Keep it that
way.

Suspension is one-way: there is no un-suspend event, so the first reason stands for the life of the stream.

For the risk decision that guard is load-bearing rather than tidy: `use_cases/risk_approve_instrument` calls
the risk service *outside* any transaction, so the call can succeed and the transaction recording its outcome
still fail, redelivering the event and repeating the call. Pinned by `InstrumentRiskDecisionTest`.

## `use_cases/risk_approve_instrument` is the demo's `UnitOfWorkMode.NONE` example

It is an **automation** — `slice.yaml` says `kind: automation`, it reacts to an event and exposes no endpoint —
but it lives under `use_cases/` alongside the command slices rather than in an `automations/` directory of its
own. The kind is declared in `slice.yaml`, not by the parent directory, and this demo keeps one directory of
slices per context. `postgresql-cqrs` does have `banking/automations/`; don't take the difference as a rule.

Its handler is declared `@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)`, so it runs with no `UnitOfWork`
and therefore holds no pooled database connection while it blocks on the external risk service; the write that
follows is wrapped in `usingUnitOfWork(...)`. The full argument, the consequences the mode shifts onto the
handler, and why the risk service is a `Thread.sleep` stub are in that slice's `CLAUDE.md`.

Two things follow from it that are easy to get wrong elsewhere:

- It is an `EventProcessor`, not a `ViewEventProcessor` — the latter rejects `NONE` handlers outright, because
  it handles each message in one `UnitOfWork` so the view update and the acknowledgement commit together.
- The stub's latency (`trading-demo.risk-approval.latency`) must stay well under
  `essentials.durable-queues.message-handling-timeout`, or the message is reset as stuck and
  redelivered while the first attempt is still blocked.

`InstrumentRiskRejected` and `InstrumentSuspended` stay separate events: a rejection says the instrument was
never cleared, a suspension says a cleared one was stopped.

## Boundaries

The importable surface of this context is `events/` and `types/`, and nothing else. `aggregates/` is
BC-private.

`brokerage` imports one thing from here: `types/InstrumentId` — a trade names the instrument it was placed
against. `orders` imports `types/InstrumentId` and the `types/MarketQuotes` seam. Nothing imports an aggregate.

The stream name lives in `types/MarketDataAggregateTypes` rather than on the repository wrapper, because a
foreign context that subscribes to these events has to name the stream — and `aggregates/` is not on its
importable surface. `Instruments` re-exposes it as `AGGREGATE_TYPE` so the aggregate and its stream name still
read together at the point of use.

## Directories beside the slices

- `events/` — the sealed `InstrumentEvent` hierarchy, one variant per file
- `types/` — `InstrumentId`, `Symbol`, `RiskRating`, `MarketDataAggregateTypes`, the `MarketQuotes` seam
- `aggregates/` — `Instrument` plus its `StatefulAggregateRepository` wrapper `Instruments`, which persists an
  already-constructed aggregate and never builds one
