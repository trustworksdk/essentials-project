# Slice: brokerage.confirm_clearing

**Kind:** command   **Status:** live   **Owner:** trading-demo
**Purpose:** Record that clearing came back confirmed.

## Invariants
- Clearing cannot be confirmed before it has been requested — the guard throws before any event is applied, so a
  rejected command leaves no trace in the stream (enforced by `Settlement`).
- Confirming twice applies one `ClearingConfirmed`, not two (enforced by `Settlement`).
- Nothing may happen to a closed settlement (`assertOpen()`, enforced by `Settlement`).

## Boundaries
**Publishes:** `ClearingConfirmed`.   **Writes / reads:** the `Settlement` stream (`AggregateType` `Settlements`).
**Forbidden:** importing another slice's internals — only `brokerage/events|types|aggregates` (§R5).

## Endpoint
`POST /api/admin/settlements/{settlementId}/clearing-confirmations` — typed `@PathVariable SettlementId`, command
assembled by the API.
