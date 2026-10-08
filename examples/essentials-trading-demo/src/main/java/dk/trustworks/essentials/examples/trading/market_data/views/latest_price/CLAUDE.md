# Slice: `market_data.latest_price` (view)

Serves the current price of one instrument: `GET /api/admin/instrument-prices/{instrumentId}`.

## The one thing to know about this slice

**It is an ordinary eventually-consistent view, and that is deliberate.** `LatestPriceProjection` is a
`ViewEventProcessor` writing `projection_latest_price`, one row per instrument. Being a `ViewEventProcessor`
is what makes it replayable: a new subscription starts at the first price event, so a database with price
events and no rows backfills on its own, and a subscription reset truncates the table and rebuilds it. Each
row stores the `EventOrder` it was written from, and a write never moves it backwards.

Do not use `LatestPriceQuery` to decide whether something exists before writing it. The demo bootstrap's
"already seeded?" probe needs a strongly-consistent answer, so it asks `InstrumentPrices.findPrice(...)` on the
write side instead. This slice has been both aggregate-backed (a public `InstrumentPrice.latestPrice()`) and
an `InTransactionEventProcessor` for that probe's sake; the first broke "views project", the second gave up
replay. Do not bring either back.

## What would make it a different slice

Price **history**, or prices **across** instruments (a ticker, a movers list). Either is a different
read model with a different grain, so it gets its own slice — and that one *would* project, from
`InstrumentPriceEvent`.

`brokerage.trade_valuation` already does exactly that: it needs the latest price joined onto trades,
so it projects `PriceInitialized`/`PriceUpdated` into a model it owns rather than calling this slice
across the context boundary.
