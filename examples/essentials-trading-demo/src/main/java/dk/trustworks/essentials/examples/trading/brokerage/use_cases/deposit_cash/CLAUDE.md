# Slice: brokerage.deposit_cash

**Kind:** command   **Status:** live   **Owner:** brokerage-team
**Purpose:** Book cash into a trading account's currently open books generation.

## Invariants
- Nothing may be booked once the books are closed (enforced by `TradingAccount.assertBooksOpen()`, before
  any event is applied — a rejected deposit leaves no trace in the stream).
- A deposit is strictly positive (enforced by the `CashDeposited` event record itself).

## Boundaries
**Reacts to / reads:** the `DepositCash` command, plus the account's current generation.
**Publishes:** `CashDeposited` — and, when the closing-books policy rolls the account on load,
`AccountBooksClosed` on the outgoing generation and `TradingAccountOpened` on the incoming one.
**Forbidden:** never import another slice's internals; never re-implement the rollover — load via
`TradingAccounts.getAccountForMutation`.

## Endpoint
`POST /api/admin/trading-accounts/{accountId}/deposits` — body is the command; the path id wins if the body
omits it, a mismatch is a 400.

## Files
- `DepositCash.java` — the command; it *is* the request body, no DTO
- `DepositCashHandler.java` — the slice's one `@CmdHandler`
- `DepositCashAPI.java` — the slice's only endpoint
