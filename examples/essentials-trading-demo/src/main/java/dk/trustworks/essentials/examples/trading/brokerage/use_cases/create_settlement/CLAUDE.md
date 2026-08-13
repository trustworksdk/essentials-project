# Slice: brokerage.create_settlement

**Kind:** command   **Status:** live   **Owner:** trading-demo
**Purpose:** Open the post-trade settlement of a trade.

## Invariants
- A settlement starts open and at step zero: nothing is requested, cleared, settled, reconciled or closed until the
  matching command arrives (enforced by `Settlement`, whose `on(SettlementCreated)` sets every flag false).
- The caller supplies the `SettlementId`, so a retried request addresses the same stream rather than opening a second
  one. The demo derives it with `SettlementId.forTrade(tradeId)`.

## Boundaries
**Publishes:** `SettlementCreated`.   **Writes:** the `Settlement` stream (`AggregateType` `Settlements`).
**Forbidden:** importing another slice's internals — only `brokerage/events|types|aggregates` (§R5). It never reads
the `Trade` aggregate; `grossAmount` is carried in on the command, because the two are separate boundaries.

## Endpoint
`POST /api/admin/settlements` — the request body *is* the `CreateSettlement` command, no DTO.

This is the only place a `Settlement` is constructed; `Settlements.createNewSettlement` only persists it.
