# Slice: brokerage.settle_trade

**Kind:** automation   **Status:** live   **Owner:** brokerage-team
**Purpose:** Once a trade has executed, drive it through settlement to the end — each step's event triggers the
next step's command — so the whole settlement of a trade is one causation tree in the admin console.

## The flow

```
TradeExecuted           -> RequestSettlement
SettlementRequested     -> CreateSettlement
SettlementCreated       -> RequestClearing
ClearingRequested       -> (clearing house, no UnitOfWork) ConfirmClearing
ClearingConfirmed       -> MarkSettlementSettled
SettlementMarkedSettled -> ReconcileSettlement, MarkTradeSettled, ApplyTradeSettlement
SettlementReconciled    -> CloseSettlement
```

Every command goes through the command bus to its own slice's handler — this slice imports only the command
records, never another slice's handler or aggregate. Because the handler of each event sends the next command, each
event records the event that caused it: one `TradeExecuted` roots a tree across `Trade`, `Settlement` and
`TradingAccount`, with a three-way fan-out under `SettlementMarkedSettled`.

## Things to know

- **The events carry what the next step needs.** `SettlementRequested` carries the trade's account and gross amount,
  `SettlementMarkedSettled` the trade, account and gross amount — added so this slice keeps no state and reads no
  eventually consistent view. Both are `null` on events persisted before they were added, and the handlers skip
  those with a warning.
- **Every step is safe to repeat.** A redelivered event repeats its command, so the aggregates ignore a step that
  already happened (`Settlement.closeSettlement` included), `CreateSettlement` ignores an existing settlement, and
  `TradingAccount` remembers the trades it has settled in the current generation.
- **The clearing house is a stub** (`ClearingHouseGateway`, `trading-demo.clearing-house.latency`, 200ms), called
  from a `UnitOfWorkMode.NONE` handler like `market_data.risk_approve_instrument`'s risk service. Every request is
  confirmed.
- **No realized P&L.** The cash moves by the gross amount; only the scripted harness simulates P&L.
- **It can be switched off.** `trading-demo.simulation.trade-lifecycle=scripted` keeps the processor out of the
  context and makes the harness send every settlement command itself, as the benchmark scenarios assume. It starts
  from the latest event, so enabling it against an existing database does not replay settled trades.
