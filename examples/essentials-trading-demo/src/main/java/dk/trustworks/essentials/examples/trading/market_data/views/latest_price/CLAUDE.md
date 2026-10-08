# Slice: `market_data.latest_price` (view)

Serves the current price of one instrument: `GET /api/admin/instrument-prices/{instrumentId}`.

## The one thing to know about this slice

**Its projection runs in the transaction that appends the price event.** `LatestPriceProjection` is an
`InTransactionEventProcessor` writing `projection_latest_price`, one row per instrument, so the row commits
with the event that set it. That makes this the demo's only strongly-consistent read, and it needs to be:
the bootstrap asks `LatestPriceQuery` whether a price already exists before deciding to seed, and an
eventually-consistent answer could say "absent" on a restart against a populated database, so the bootstrap
would seed on top of existing data.

The processor is non-exclusive, so every instance projects the events it appends itself. Each row stores the
`EventOrder` it was written from, and a write never moves it backwards.

It used to read the `InstrumentPrice` aggregate through a public `latestPrice()` accessor, a documented
exception to "views project". The exception is gone; do not bring the accessor back. A database created before
the projection existed has price events and no rows, so the probe would seed again: `docker compose down -v`.

## What would make it a different slice

Price **history**, or prices **across** instruments (a ticker, a movers list). Either is a different
read model with a different grain, so it gets its own slice — and that one *would* project, from
`InstrumentPriceEvent`.

`brokerage.trade_valuation` already does exactly that: it needs the latest price joined onto trades,
so it projects `PriceInitialized`/`PriceUpdated` into a model it owns rather than calling this slice
across the context boundary.
