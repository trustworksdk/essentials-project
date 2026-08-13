# Slice: brokerage.reserve_funds

**Kind:** command   **Status:** live   **Owner:** brokerage-team
**Purpose:** Set cash aside on a trading account so a trade in flight cannot be outspent by another.

## Invariants
- Funds may not be reserved beyond the cash that is not already reserved — `cashBalance - reservedFunds`
  (enforced by `TradingAccount.reserveFunds`, checked before any event is applied). This is the reason the
  check cannot move into this slice: only the aggregate holds a balance nothing else can concurrently change.
- Nothing may be booked once the books are closed (enforced by `TradingAccount.assertBooksOpen()`).
- A reservation is strictly positive (enforced by the `FundsReserved` event record itself).

## Boundaries
**Reacts to / reads:** the `ReserveFunds` command, plus the account's current generation.
**Publishes:** `FundsReserved` — plus `AccountBooksClosed`/`TradingAccountOpened` if the load triggers a rollover.
**Forbidden:** never import another slice's internals; never pre-check the balance here.

## Endpoint
`POST /api/admin/trading-accounts/{accountId}/fund-reservations` — body is the command.

## Files
- `ReserveFunds.java` — the command; it *is* the request body, no DTO
- `ReserveFundsHandler.java` — the slice's one `@CmdHandler`
- `ReserveFundsAPI.java` — the slice's only endpoint
