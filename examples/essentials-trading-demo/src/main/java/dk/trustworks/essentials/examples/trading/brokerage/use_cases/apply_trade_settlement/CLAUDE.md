# Slice: brokerage.apply_trade_settlement

**Kind:** command   **Status:** live   **Owner:** brokerage-team
**Purpose:** Book the cash and realized-P&L result of a settled trade onto the trading account.

## Invariants
- Nothing may be booked once the books are closed (enforced by `TradingAccount.assertBooksOpen()`, before
  any event is applied).
- Cash and realized P&L only ever move through `on(TradeSettlementApplied)` — the aggregate's event handlers
  are the only writers of those fields, so a replay and a live command follow the identical path.

The deltas are signed, so no positivity guard applies here: a buy moves cash out and a losing trade's
realized P&L is negative.

## Boundaries
**Reacts to / reads:** the `ApplyTradeSettlement` command, plus the account's current generation.
`Settlement` is a separate consistency boundary — its outcome crosses to the account as a command, never
inside one transaction.
**Publishes:** `TradeSettlementApplied` — plus `AccountBooksClosed`/`TradingAccountOpened` if the load
triggers a rollover.
**Forbidden:** never import another slice's internals; never load the `Settlement` aggregate from here.

## Endpoint
`POST /api/admin/trading-accounts/{accountId}/trade-settlements` — body is the command.

## Files
- `ApplyTradeSettlement.java` — the command; it *is* the request body, no DTO
- `ApplyTradeSettlementHandler.java` — the slice's one `@CmdHandler`
- `ApplyTradeSettlementAPI.java` — the slice's only endpoint
