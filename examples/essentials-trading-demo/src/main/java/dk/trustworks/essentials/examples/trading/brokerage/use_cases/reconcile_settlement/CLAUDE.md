# Slice: brokerage.reconcile_settlement

**Kind:** command   **Status:** live   **Owner:** trading-demo
**Purpose:** Record that a completed settlement has been reconciled against the books.

## Invariants
- A settlement cannot be reconciled before it has completed — the guard throws before any event is applied
  (enforced by `Settlement`).
- Reconciling twice applies one `SettlementReconciled`, not two (enforced by `Settlement`).
- Nothing may happen to a closed settlement (`assertOpen()`, enforced by `Settlement`).

## Boundaries
**Publishes:** `SettlementReconciled`.   **Writes / reads:** the `Settlement` stream (`Settlements`).
**Forbidden:** importing another slice's internals — only `brokerage/events|types|aggregates` (§R5).

## Endpoint
`POST /api/admin/settlements/{settlementId}/reconciliation` — typed `@PathVariable SettlementId`, command assembled
by the API.
