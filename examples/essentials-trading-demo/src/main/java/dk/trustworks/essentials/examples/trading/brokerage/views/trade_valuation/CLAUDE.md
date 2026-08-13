# Slice: brokerage.trade_valuation

**Kind:** view   **Status:** live   **Owner:** brokerage-team
**Purpose:** Value one trade against the latest market price of its instrument.

## Why this slice projects another context's price events instead of calling that context

This is the whole reason the slice exists, so read it before "simplifying" it.

Valuing a trade needs the current market price of its instrument. The instrument, and its price, are
`market_data`'s concepts. The pre-slice `TradeAdminQueryService` got the price by injecting `market_data`'s
**write-side** `InstrumentPriceService` and loading the `InstrumentPrice` aggregate — one bounded context
reaching into another's write model. §R4 forbids that outright: the importable surface of a foreign context
is `events/` and `types/`, and nothing else. A write-side service is neither.

Three things go wrong with the call-across version beyond the rule:
- the read path takes a synchronous dependency on another context's availability and latency, per request;
- it couples to how `market_data` *stores* prices, so any change there breaks a query in this context;
- it makes the trade endpoint a hidden second reader of an aggregate whose contract says nothing about it.

The CQRS answer is to project what you need into a model you own. `TradeValuationProjection` subscribes to
`Trades` **and** `InstrumentPrices`, and imports `PriceInitialized` / `PriceUpdated` from
`market_data.events` — which is exactly the cross-context import the law allows. The price becomes a column
in a table this slice owns, and the query reads one row.

The cost is real and worth naming: a price tick fans out to every trade on that instrument
(`UPDATE … WHERE instrument_id = ?`), which is why the table carries an index on `instrument_id`. That cost
is paid on the write path of a projection, asynchronously, rather than on every read.

The trade-off it buys is staleness: the valuation is as of the last price event *projected*, not as of now.
For an admin dashboard that is the right side of the trade. A caller that genuinely needs the authoritative
price as of this instant should ask `market_data` for it through that context's own read slice — not reach
into its aggregate from here.

## The two aggregate types are not ordered against each other

The subtlest thing about this slice, and the source of a real bug that shipped in it once.

This processor subscribes to `Trades` and `InstrumentPrices` as **two independent subscriptions**.
`GlobalEventOrder` sequences events within one aggregate type, not across two — so for a given instrument the
price tick and the trade can be projected in either order, and nothing makes one wait for the other.

The first version only had `UPDATE … WHERE instrument_id = ?` on the price side, and its javadoc called a tick
that matched no rows "correctly a no-op". It is not: if the price is projected first, the trade's row does not
exist yet, the update matches nothing, and the row is then inserted with a `null` market price that **never
gets filled in** — nothing replays a tick that has already been consumed. Continuous demo traffic hid it
completely, because the next tick a second later repaired the row. It only shows when the price stops moving,
which is exactly what an integration test does.

The fix is `projection_trade_valuation_price`, a second table this slice owns: every tick upserts the latest
price per instrument, and `TradePlaced` seeds its row from it. Both interleavings now produce the same row.
`a_trade_projected_after_its_instruments_price_ticks_is_still_valued` pins it — it places a trade with no tick
after it, so it fails if the seeding is removed.

## Invariants
- The market price is projected, never fetched by calling into `market_data` (enforced by
  `TradeValuationProjection`). Do not reintroduce an `InstrumentPriceService` dependency.
- The row is correct regardless of whether the trade or the price is projected first.
- A replayed `TradePlaced` must not wipe a price already projected: the upsert restates the trade's booked
  terms and deliberately leaves `latest_market_price` alone.
- `latestMarketPrice`, `marketValue` and `unrealizedPnl` are `null` **together**. An unpriced trade is not a
  zero-valued trade, and reporting `0` would be a lie rather than a gap.
- `unrealizedPnl` is the price delta since execution, negated for `SELL`, times quantity — ported unchanged.
  The comparison is now on the `TradeSide` enum, not `"SELL".equalsIgnoreCase(aString)`.

## Computed on read, not stored

`marketValue` and `unrealizedPnl` are derived in `TradeValuationQuery` from the row. Storing them would put
the arithmetic on the write path of the highest-frequency event in the demo — every price tick would have to
rewrite them for every trade on the instrument — to save a multiplication on a much rarer read.

## Boundaries
**Reacts to / reads:** `TradePlaced`, `TradeExecuted`, `SettlementRequested`, `TradeSettled` from `Trades`;
`PriceInitialized`, `PriceUpdated` from `InstrumentPrices`.
**Publishes:** nothing — a view slice never produces events.
**Forbidden:**
  - Never import `market_data`'s aggregates, repositories or services — only its `events/` and `types/`.
  - Never rehydrate the `Trade` aggregate to answer this query.

## Data
**Owns (writes):** `projection_trade_valuation` and its `instrument_id` index, plus
`projection_trade_valuation_price` — all created by the projection's constructor and truncated on
subscription reset. The table names are hardcoded — never concatenate them.
**Reads:** its own read model only. The read model *is* the response body.

`onSubscriptionsReset` truncates **both** tables for each of the two aggregate types it is called with,
because a row is fed by both and cannot be deleted per aggregate type.

## Files
- `TradeValuation.java` — the read model / response body, including the two computed figures
- `TradeValuationProjection.java` — the `EventProcessor` over both aggregate types; owns the DDL
- `TradeValuationQuery.java` — the row lookup and the two derived figures
- `TradeValuationAPI.java` — `GET /api/admin/trades/{tradeId}`
