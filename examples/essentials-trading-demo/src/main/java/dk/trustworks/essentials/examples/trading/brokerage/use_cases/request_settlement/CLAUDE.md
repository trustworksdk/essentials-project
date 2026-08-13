# Slice: brokerage.request_settlement

**Kind:** command   **Status:** live   **Owner:** trading-demo
**Purpose:** Record on a trade that settlement has been requested, under a named `SettlementId`.

## Invariants
- Settlement cannot be requested before the trade has been executed — the guard throws before any event is applied,
  so a rejected request leaves no trace in the stream (enforced by `Trade`).
- Re-requesting settlement is a no-op; the first `SettlementId` stands (enforced by `Trade`).

## Boundaries
**Publishes:** `SettlementRequested`.   **Writes / reads:** the `Trade` stream (`AggregateType` `Trades`).
**Forbidden:** importing another slice's internals — only `brokerage/events|types|aggregates` (§R5). It does *not*
create the `Settlement` aggregate; that is `create_settlement`, a separate consistency boundary.

## Endpoint
`POST /api/admin/trades/{tradeId}/settlement-requests?settlementId=…` — path variable plus request parameter, both
typed. Not a `@RequestBody`: the body could not supply `tradeId`, and `RequestSettlement` requires both to be
non-null. `SettlementId.forTrade(tradeId)` is the demo's usual choice of id.
