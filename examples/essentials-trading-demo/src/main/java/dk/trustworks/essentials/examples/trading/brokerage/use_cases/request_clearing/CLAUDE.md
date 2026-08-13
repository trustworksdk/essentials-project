# Slice: brokerage.request_clearing

**Kind:** command   **Status:** live   **Owner:** trading-demo
**Purpose:** Send a created settlement to clearing — the first step of its post-trade lifecycle.

## Invariants
- Nothing may happen to a closed settlement: `assertOpen()` throws before any event is applied (enforced by
  `Settlement`).
- Requesting clearing twice applies one `ClearingRequested`, not two (enforced by `Settlement`), which is what makes
  the command safe to re-deliver.

## Boundaries
**Publishes:** `ClearingRequested`.   **Writes / reads:** the `Settlement` stream (`AggregateType` `Settlements`).
**Forbidden:** importing another slice's internals — only `brokerage/events|types|aggregates` (§R5).

## Endpoint
`POST /api/admin/settlements/{settlementId}/clearing-requests` — typed `@PathVariable SettlementId`, command
assembled by the API. The handler does not `save`; the events persist on `UnitOfWork` commit.
