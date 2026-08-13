# Slice: brokerage.mark_settlement_settled

**Kind:** command   **Status:** live   **Owner:** trading-demo
**Purpose:** Record that the settlement itself has completed.

## Invariants
- A settlement cannot complete before clearing has been confirmed — the guard throws before any event is applied
  (enforced by `Settlement`).
- Marking an already-settled settlement is a no-op (enforced by `Settlement`).
- Nothing may happen to a closed settlement (`assertOpen()`, enforced by `Settlement`).

## Boundaries
**Publishes:** `SettlementMarkedSettled`.   **Writes / reads:** the `Settlement` stream (`Settlements`).
**Forbidden:** importing another slice's internals — only `brokerage/events|types|aggregates` (§R5). Marking the
`Trade` settled is `mark_trade_settled`; the two aggregates are never written in one transaction.

## Endpoint
`POST /api/admin/settlements/{settlementId}/settlement` — typed `@PathVariable SettlementId`, command assembled by
the API.
