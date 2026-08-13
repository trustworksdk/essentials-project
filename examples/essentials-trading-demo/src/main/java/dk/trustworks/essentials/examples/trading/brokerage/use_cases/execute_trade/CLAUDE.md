# Slice: brokerage.execute_trade

**Kind:** command   **Status:** live   **Owner:** trading-demo
**Purpose:** Mark an already-placed trade as executed.

## Invariants
- Executing a trade twice applies one `TradeExecuted`, not two — the second call is a no-op (enforced by `Trade`).
  That is what makes the command safe to re-deliver.
- A trade that was never placed cannot be executed: the handler loads with `getTrade`, which fails rather than
  creating a stream.

## Boundaries
**Publishes:** `TradeExecuted`.   **Writes / reads:** the `Trade` stream (`AggregateType` `Trades`).
**Forbidden:** importing another slice's internals — only `brokerage/events|types|aggregates` (§R5).

## Endpoint
`POST /api/admin/trades/{tradeId}/execution` — the id is a typed `@PathVariable TradeId` and the API assembles
`ExecuteTrade` from it. The handler does not `save`; the events persist on `UnitOfWork` commit.
