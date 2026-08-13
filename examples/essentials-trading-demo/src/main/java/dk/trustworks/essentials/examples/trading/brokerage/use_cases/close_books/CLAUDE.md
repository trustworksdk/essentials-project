# Slice: brokerage.close_books

**Kind:** command   **Status:** live   **Owner:** brokerage-team
**Purpose:** Seal a trading account's current books generation, naming the period the next one will open in.
Closes only — it does not open the next generation.

## Invariants
- Closing already-closed books is an idempotent no-op, not a failure (enforced by
  `TradingAccount.closeBooks`, which returns early when `booksClosed`). An automatic ON_ACCESS rollover may
  have got there first, and that is not an error.
- Closing zeroes reserved funds (enforced by `TradingAccount.on(AccountBooksClosed)`).

## Boundaries
**Reacts to / reads:** the `CloseBooks` command, plus the account's current generation.
**Publishes:** `AccountBooksClosed`.
**Forbidden:** never import another slice's internals.

**Loads via `TradingAccounts.getAccount`, not `getAccountForMutation`** — this slice *is* the manual
closing-books trigger. Letting the ON_ACCESS policy roll first would seal a generation the caller never
asked about, then seal the fresh one it opened: one request, two rollovers. Do not "fix" this to match the
sibling slices.

## Endpoint
`POST /api/admin/trading-accounts/{accountId}/books-closures` — body is the command.

## Files
- `CloseBooks.java` — the command; it *is* the request body, no DTO
- `CloseBooksHandler.java` — the slice's one `@CmdHandler`
- `CloseBooksAPI.java` — the slice's only endpoint
