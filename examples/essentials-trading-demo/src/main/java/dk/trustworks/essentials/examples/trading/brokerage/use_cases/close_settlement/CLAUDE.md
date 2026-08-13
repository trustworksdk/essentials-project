# Slice: brokerage.close_settlement

**Kind:** command   **Status:** live   **Owner:** trading-demo
**Purpose:** Close a reconciled settlement — the last step of its lifecycle.

## Invariants
- A settlement cannot be closed before reconciliation is complete — the guard throws before any event is applied
  (enforced by `Settlement`).
- A closed settlement accepts nothing further, this command included (`assertOpen()`, enforced by `Settlement`).
  This is the one settlement step that is *not* idempotent, deliberately: `assertOpen()` has already rejected the
  repeat, so no "already closed" short-circuit is needed.

## Boundaries
**Publishes:** `SettlementClosed`.   **Writes / reads:** the `Settlement` stream (`Settlements`).
**Forbidden:** importing another slice's internals — only `brokerage/events|types|aggregates` (§R5).

## Endpoint
`POST /api/admin/settlements/{settlementId}/closure` — typed `@PathVariable SettlementId`, command assembled by the
API.
