# Slice: brokerage.close_books_and_open_next_period

**Kind:** command   **Status:** live   **Owner:** brokerage-team
**Purpose:** Roll a trading account's books — seal the current generation and open the next one. The manual
counterpart of the ON_ACCESS rollover `TradingAccounts.getAccountForMutation` performs on its own.

## Invariants
- The two steps happen in this order, in one transaction: `closeBooks` writes the closing entry into the
  *outgoing* stream, then `closeAndOpenNextGeneration` allocates the incoming one. Only the first leaves the
  account with no open generation; only the second loses the closing entry.
- What carries across is `TradingAccountNextGenerationFactory`'s decision, not this slice's: owner and cash
  carry, realized P&L resets, reserved funds are zeroed by `AccountBooksClosed` on the way out.
- Closing already-closed books is an idempotent no-op (enforced by `TradingAccount.closeBooks`).

## Boundaries
**Reacts to / reads:** the `CloseBooksAndOpenNextPeriod` command, plus the account's current generation.
**Publishes:** `AccountBooksClosed` on the outgoing generation, `TradingAccountOpened` on the incoming one.
**Forbidden:** never import another slice's internals; never re-implement the carry-forward rules here.

Loads via `getAccount`, not `getAccountForMutation` — the caller asked for exactly one rollover, and the
automatic one would make it two.

## Endpoint
`POST /api/admin/trading-accounts/{accountId}/generations` — body is the command. The path reads as "create
the next generation", which is what a rollover is.

## Files
- `CloseBooksAndOpenNextPeriod.java` — the command; it *is* the request body, no DTO
- `CloseBooksAndOpenNextPeriodHandler.java` — the slice's one `@CmdHandler`
- `CloseBooksAndOpenNextPeriodAPI.java` — the slice's only endpoint
