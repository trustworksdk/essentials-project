# Slice: `market_data.latest_price` (view)

Serves the current price of one instrument: `GET /api/admin/instrument-prices/{instrumentId}`.

## The one thing to know about this slice

**It has no projection, on purpose.** It reads the `InstrumentPrice` aggregate through
`InstrumentPrices.findPrice(...)` inside a read-only transaction, which makes it the only
strongly-consistent read in the demo.

Two reasons, both in `LatestPriceQuery`'s javadoc:

1. The demo's bootstrap asks this query whether a price already exists before deciding to seed. An
   eventually-consistent answer could say "absent" on a restart against a populated database, and the
   bootstrap would seed on top of existing data.
2. A latest-price read model would be one column, keyed by the same id as the stream, rebuilt from the
   same two events, and permanently one hop behind the aggregate it copies.

This is also why `InstrumentPrice.latestPrice()` is the only public accessor on any aggregate in this
module. It is a documented exception to § The aggregate's own bar, not a general licence — see
`REFACTORING_PLAN.md` § Open questions.

## What would make it a different slice

Price **history**, or prices **across** instruments (a ticker, a movers list). Either is a different
read model with a different grain, so it gets its own slice — and that one *would* project, from
`InstrumentPriceEvent`.

`brokerage.trade_valuation` already does exactly that: it needs the latest price joined onto trades,
so it projects `PriceInitialized`/`PriceUpdated` into a model it owns rather than calling this slice
across the context boundary.
