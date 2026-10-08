# Bounded context: market_data

Instrument reference data, and the authoritative event-sourced store of the latest market price per
instrument.

Separate lifecycle and separate write cadence from `brokerage`: reference data changes rarely, prices tick
at high frequency. No invariant is shared with trading — nothing here reads or writes a trading account, a
trade or a settlement.

**Write style: aggregate (`rules/slice-design.md` §R5).** Decisions live in `aggregates/`, on `Instrument`
and `InstrumentPrice`, both `AggregateRoot`s reached through a `StatefulAggregateRepository`. This is a
sanctioned lane, not a legacy one — do **not** convert these to `Decider`s. Command slices still own
everything else: their command type, their one API file, their handler and their test.

## Slices

| Slice | Kind | Role |
|---|---|---|
| `use_cases/register_instrument` | command | Registers an instrument with its symbol and display name |
| `use_cases/rename_instrument` | command | Changes an instrument's display name |
| `use_cases/suspend_instrument` | command | Suspends an instrument, with a reason. One-way |
| `use_cases/initialize_price` | command | Opens the price stream at a starting price |
| `use_cases/update_price` | command | Records a price tick. The authoritative write path |
| `use_cases/risk_approve_instrument` | automation | Runs a newly registered instrument through an external risk assessment and records the answer. The demo's `UnitOfWorkMode.NONE` example |
| `views/latest_price` | view | Serves the current price of one instrument, from an in-transaction projection. Strongly consistent |
| `views/instrument_details` | view | Lists instruments and serves one by id, from a projection. Includes risk status |

## Two aggregates, one id

`Instrument` and `InstrumentPrice` are separate consistency boundaries — no transaction writes both — but
they share an identity: `InstrumentPrice` is keyed by `InstrumentId`, not by a price id of its own. A price
stream *is* the instrument, stored under a second `AggregateType`. That means the price for an instrument is
reachable without a lookup table. Do not "fix" it by inventing an `InstrumentPriceId`.

| Aggregate | `AggregateType` | Id | Snapshots |
|---|---|---|---|
| `Instrument` | `Instruments` | `InstrumentId` | no — baseline aggregate, short streams |
| `InstrumentPrice` | `InstrumentPrices` | `InstrumentId` | `@AggregateSnapshotPolicy`, ASYNC_DURABLE, every 100 events |

`Instrument` is the demo's baseline: neither snapshots nor closing books, so it is what the other aggregates
in the demo are compared against.

## The authoritative price store is the aggregate

`InstrumentPrice` is the authority for latest price. The trade path loads it; a read model that needs the
current market price projects `PriceInitialized` / `PriceUpdated`, as `views/latest_price` and
`brokerage.trade_valuation` both do.

The demo also has a direct-write JDBC latest-price table. That is a **benchmark comparison artifact**: its
only purpose is to be timed against the aggregate path. It lives in the demo harness, not in this context,
so the trade path cannot reach it by accident. Do not move it here, and do not make anything in this context
read it.

## `views/latest_price` projects in the appending transaction

Aggregate state is private, and read models project events rather than reading the write model. That holds
for `views/latest_price` too, although it must answer **strongly consistently**: the demo's bootstrap uses it
to decide whether seed data already exists, and an eventually-consistent "absent" would let it seed on top of
a populated database. Its `LatestPriceProjection` is an `InTransactionEventProcessor`, so a price's row commits
in the same transaction as the event that set it. It used to read a public `InstrumentPrice.latestPrice()`
instead; that accessor is gone, and nothing on `InstrumentPrice` or `Instrument` is public state.

The cost is one upsert per price tick inside the tick's own transaction. Any read that wants price *history*,
or prices *across* instruments, is a different read model, and an eventually-consistent one is enough for it,
as `brokerage.trade_valuation` shows.

## Every mutating method is an idempotent no-op

`Instrument.rename` to the display name already held, `Instrument.suspend` on an already-suspended instrument,
and `Instrument.recordRiskApproval` / `recordRiskRejection` on an instrument that already carries a risk
decision all return without applying. So does `InstrumentPrice.updatePrice` when the tick repeats the price
already held — which matters at this write rate: an unchanged market must not lengthen the stream. Repeat
commands leave no trace instead of growing history. Keep it that way.

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

## Prices are positive by construction

`InstrumentPriceEvent.requirePositive` rejects a price of zero or less. Both variants validate through that
one method, so the rule cannot drift between them — and because it runs in the record's canonical
constructor it is enforced on *deserialization* too: a stream containing a non-positive price fails loudly
on replay rather than projecting a nonsense valuation.

## Boundaries

The importable surface of this context is `events/` and `types/`, and nothing else. `aggregates/` is
BC-private.

`brokerage` imports exactly two things from here, both on the sanctioned surface:

- `types/InstrumentId` — a trade names the instrument it was placed against.
- `events/PriceInitialized` and `events/PriceUpdated` — `brokerage.trade_valuation` projects them into its
  own read model so a trade can be valued at the current market price. That is the law's answer to a
  cross-context read: project the other context's events into a model you own, rather than calling into its
  write side. The old `TradeAdminQueryService` did the opposite — it injected this context's
  `InstrumentPriceService` — which is the §R4 violation this replaced.

It imports no aggregate and no other type. Keep the list at these two.

The stream names live in `types/MarketDataAggregateTypes` rather than on the repository wrappers, because a
foreign context that subscribes to these events has to name the stream — and `aggregates/` is not on its
importable surface. `Instruments`/`InstrumentPrices` re-expose them as `AGGREGATE_TYPE` so the aggregate and
its stream name still read together at the point of use.

## Directories beside the slices

- `events/` — both sealed hierarchies, `InstrumentEvent` and `InstrumentPriceEvent`, one variant per file
- `types/` — `InstrumentId`, `Symbol`, `RiskRating`
- `aggregates/` — the two aggregates plus their `StatefulAggregateRepository` wrappers (`Instruments`,
  `InstrumentPrices`), which persist an already-constructed aggregate and never build one
