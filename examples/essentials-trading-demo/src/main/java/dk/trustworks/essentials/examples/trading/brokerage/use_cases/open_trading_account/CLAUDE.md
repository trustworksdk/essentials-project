# Slice: brokerage.open_trading_account

**Kind:** command   **Status:** live   **Owner:** brokerage-team
**Purpose:** Open the first books generation of a trading account, at a zero cash balance and zero realized P&L.

## Invariants
- An account opens with zero cash, zero reserved funds and zero realized P&L (enforced by `TradingAccount`,
  whose four-arg constructor passes `Amount.ZERO` twice and whose `on(TradingAccountOpened)` is the only
  writer of those fields).
- A second open for the same `TradingAccountId` fails rather than silently no-ops (enforced by
  `ClosingBooksLogicalAggregateRepository.open` behind `TradingAccounts.openNewAccount`). Unlike the
  banking demo's `open_account`, an account id here is caller-chosen and reuse is a caller bug, not a retry.

## Boundaries
**Reacts to / reads:** the `OpenTradingAccount` command only.
**Publishes:** `TradingAccountOpened`.
**Forbidden:** never import another slice's internals — only `brokerage/aggregates/`, `brokerage/events/`
and `brokerage/types/`.

## Endpoint
`POST /api/admin/trading-accounts` — body is the command itself.

## Files
- `OpenTradingAccount.java` — the command; it *is* the request body, no DTO
- `OpenTradingAccountHandler.java` — the slice's one `@CmdHandler`; constructs the aggregate
- `OpenTradingAccountAPI.java` — the slice's only endpoint
