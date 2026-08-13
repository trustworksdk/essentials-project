# Slice: market_data.update_price

**Kind:** command   **Status:** live   **Owner:** market-data-team
**Purpose:** Record a new market price for an instrument — one tick. The demo's highest-frequency command.

## This slice is the authoritative latest-price store
Everything that needs the current market price — the trade path, the trade-valuation read model —
resolves to the `InstrumentPrice` aggregate this slice writes. The demo's direct-write JDBC latest-price
table is a **benchmark comparison artifact** that lives in the demo harness, not in this context; it
exists only to be timed against this path. Never reference it from this slice, and never make this slice
read it.

## Invariants
- A tick repeating the price already held emits nothing — `updatePrice` is a no-op when the price is
  unchanged (enforced by `InstrumentPrice`). At this write rate that matters: an unchanged market must not
  lengthen the stream. It also makes a redelivered command harmless.
- A price must be **greater than zero** — `InstrumentPriceEvent.requirePositive`, enforced on replay too.
- Ticking an instrument whose price stream was never initialized fails: the handler uses `getPrice`, not
  `findPrice`.

## Boundaries
**Reacts to / reads:** the `UpdatePrice` command; loads the `InstrumentPrice` aggregate.
**Publishes:** `PriceUpdated`.
**Forbidden:** only `market_data/events/`, `market_data/types/` and `market_data/aggregates/` — and never
the benchmark JDBC price table.

## Data
**Owns (writes):** the `InstrumentPrice` aggregate stream (`AggregateType` `InstrumentPrices`, snapshotted
ASYNC_DURABLE every 1000 events — this is the aggregate that needs them). Loads and mutates, so no `save`.

## Endpoint
`POST /api/admin/instrument-prices/{instrumentId}?price=…` — id from the path, price from a request
parameter, command built fully non-null in the API file.
