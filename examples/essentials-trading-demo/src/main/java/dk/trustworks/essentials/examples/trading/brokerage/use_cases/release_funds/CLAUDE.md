# Slice: brokerage.release_funds

**Kind:** command   **Status:** live   **Owner:** brokerage-team
**Purpose:** Give previously reserved cash back to a trading account's available balance.

## Invariants
- More may not be released than is currently reserved (enforced by `TradingAccount.releaseFunds`, checked
  before any event is applied). Only the aggregate holds the reserved total, so the check cannot live here.
- Nothing may be booked once the books are closed (enforced by `TradingAccount.assertBooksOpen()`).

A books rollover zeroes reservations on the way out (`AccountBooksClosed`), so a release arriving after a
rollover has nothing left to release and is rejected. That is the intended reading of the model.

## Boundaries
**Reacts to / reads:** the `ReleaseFunds` command, plus the account's current generation.
**Publishes:** `FundsReleased` — plus `AccountBooksClosed`/`TradingAccountOpened` if the load triggers a rollover.
**Forbidden:** never import another slice's internals; never pre-check the reserved total here.

## Endpoint
`POST /api/admin/trading-accounts/{accountId}/fund-releases` — body is the command.

## Files
- `ReleaseFunds.java` — the command; it *is* the request body, no DTO
- `ReleaseFundsHandler.java` — the slice's one `@CmdHandler`
- `ReleaseFundsAPI.java` — the slice's only endpoint
