# Slice: brokerage.place_trade

**Kind:** command   **Status:** live   **Owner:** trading-demo
**Purpose:** Place a new trade against a trading account.

## Invariants
- The gross amount is always `quantity x price`. The command carries neither it nor a way to override it —
  `Trade` computes it when it applies `TradePlaced` (enforced by `Trade`).
- The caller supplies the `TradeId`, so a retried request addresses the same stream rather than opening a second one.

## Boundaries
**Publishes:** `TradePlaced`.   **Writes:** the `Trade` stream (`AggregateType` `Trades`).
**Forbidden:** importing another slice's internals — only `brokerage/events|types|aggregates` (this BC's §R5
aggregate style) plus `market_data.types.InstrumentId`.

## Endpoint
`POST /api/admin/trades` — the request body *is* the `PlaceTrade` command, no DTO.

This is the only place a `Trade` is constructed; `Trades.placeNewTrade` only persists it.
