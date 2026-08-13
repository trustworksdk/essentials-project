# Slice: brokerage.mark_trade_settled

**Kind:** command   **Status:** live   **Owner:** trading-demo
**Purpose:** Mark a trade as settled, once its settlement has completed.

## Invariants
- A trade cannot be marked settled before a settlement was requested for it (enforced by `Trade`) — the guard throws
  before any event is applied.
- Marking an already-settled trade is a no-op. Note the asymmetry with the guard above: idempotence forgives a
  repeat, not a skipped step.

## Boundaries
**Publishes:** `TradeSettled`.   **Writes / reads:** the `Trade` stream (`AggregateType` `Trades`).
**Forbidden:** importing another slice's internals — only `brokerage/events|types|aggregates` (§R5).

## Endpoint
`POST /api/admin/trades/{tradeId}/settlement` — the id is a typed `@PathVariable TradeId` and the API assembles
`MarkTradeSettled` from it. The handler does not `save`; the events persist on `UnitOfWork` commit.
