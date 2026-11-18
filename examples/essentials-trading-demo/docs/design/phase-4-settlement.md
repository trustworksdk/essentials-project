# Phase 4 design — settlement

**Status:** decided (§8, 2026-10-22). Decisions are marked **(Q-n)** in the text.
**Plan:** [`../EXPANSION_PLAN.md`](../EXPANSION_PLAN.md) phase 4; decisions D8, D10, D11, D20, D23; simulator
requirements SR-14 (closing prices), SR-29 (dates on executions), SR-35 (holidays, bundled).
**Builds on:** the ledger of phase 1, whose `settle_trade` and `reverse_trade_booking` exist with operator endpoints
and are driven by nothing yet, and the order flow of phase 3, which books every fill as a pending trade.

Phase 4 takes a booked trade to settled: the exchange's settlement simulator reports each execution's settlement, and
the account's pending cash and shares become settled, or the booking is undone when the settlement never happens. The
closing prices the exchange issues at each business day's end value the accounts. The frozen `brokerage` context,
whose last users this phase replaces, is deleted.

---

## 1. What the exchange reports (measured on simulators 0.3.0)

One settlement instruction per execution, per participant side, with `executionId`, both participants, quantity,
price, `settlementAmount`, `tradeDate`, `intendedSettlementDate` and an `attempt` counter. Observed lifecycles, each
forced at probability 1 on a throwaway exchange:

| Path | Events | Final? |
|---|---|---|
| Normal | `SettlementPending` → `SettlementCompleted` | yes |
| Delayed | `SettlementPending` → `SettlementDelayed` (new `intendedSettlementDate`, up to `max_delays`) → `SettlementCompleted` | yes |
| Failed | `SettlementPending` → `SettlementFailed` (`SIMULATED_FAILURE`); no retry over six business days | yes |
| Cancelled | `SettlementPending` → `SettlementCancelled` | yes |
| Rejected | `SettlementRejected` at once, no `SettlementPending` (`SIMULATED_REJECTION`, `AMOUNT_OUT_OF_RANGE`) | yes |
| Corrected | `SettlementCompleted` → `SettlementCorrected` (new `settlementAmount`, `previousSettlementAmount`) | — |

Settlement events are delivered when either side is a listed participant (SR-19), so a `participants: [DEMO-BROKER]`
subscription receives exactly the firm's.

## 2. Contexts

| Context | What it gains |
|---|---|
| `settlement` (new) | `TradeSettlement` per execution (id = `ExecutionId`): the instruction's life as the exchange reports it, its intended date, attempts, outcome. Its own projection of `orders`' `OrderFilled` names the account an execution belongs to. |
| `accounts` | A ledger fact for a corrected settlement amount (Q-2). |
| `market_data` | `ClosingPriceIssued` (SR-14) into a closing-prices view: per instrument and business day, price and basis. |
| `accounts` (view) | `account_valuation`: each account's positions at the day's closing prices, per business day. |
| `brokerage` | Deleted (Q-3). |

## 3. From settlement to the ledger

```
exchange Settlement* ── webhook demo-settlement + catch-up ──▶ settlement.TradeSettlement
   SettlementPending  ─▶ SettlementInstructed(intendedDate)
   SettlementDelayed  ─▶ SettlementDelayed(newDate, attempt)
   SettlementCompleted ─▶ SettlementCompleted ──▶ accounts SettleTrade(account, executionId)
   SettlementFailed / Cancelled / Rejected ─▶ SettlementAbandoned(reason) ──▶ (Q-1)
   SettlementCorrected ─▶ SettlementAmountCorrected(delta) ──▶ (Q-2)
```

- **The account comes from `orders`' `OrderFilled`** (`executionId`, `accountId`), projected into `settlement`'s own
  view, not looked up in `orders`. A settlement event for an execution not projected yet is redelivered, not dropped
  (the fill and its settlement race only at the start; the fill always comes first at the exchange).
- **Out of order (D11)**: a completion before its instruction implies it; a delay after a completion is ignored; a
  correction before the completion waits (redelivered), as a returned payment waits for its completion in `funding`.
- **The ledger is idempotent per execution** already (`SettleTrade` is a no-op for a settled trade).

## 4. A settlement that never happens (Q-1)

The simulator's failure, cancellation and rejection are final: the trade will never settle. A real broker would chase
a failed delivery (buy-in, a later attempt), but the exchange gives nothing more to act on. Proposed: the brokerage
**reverses the booking** (`ReverseTradeBooking`, reason the exchange's) - the customer's pending cash or shares go
back - and lists the execution as an **operator case** (`settlement/views/settlement_exceptions`), since the order
stays `FILLED` and the customer saw a fill. The alternative is to leave the trade pending forever and only raise the
case; the account would then show money and shares that will never arrive.

## 5. A corrected settlement amount (Q-2)

`SettlementCorrected` changes the cash a settled trade moved (observed: by one minor unit). Proposed: a ledger fact
`SettlementAmountCorrected` (execution, delta) on `settled` cash, never refused, idempotent per execution; for a buy the
delta also adjusts the position's cost basis, for a sell the period's realized P&L. An operator `AdjustCash` would book
the cash but leave cost basis and P&L wrong.

## 6. Closing prices and valuation

- `market_data/views/closing_prices`: `ClosingPriceIssued` per instrument and business day (price, basis
  `LAST_TRADE` / `REFERENCE`, volume), through the exchange's webhook and catch-up. Durable and replayable (SR-14),
  unlike the stream.
- `accounts/views/account_valuation`: each position (settled + pending in − pending out) at its latest closing price,
  and the total with the cash balance. **As built:** worked out when asked, from the current positions, so it is the
  valuation as of the latest close and not kept per business day - positions are current state, and a past day's
  valuation belongs to account statements (later). The intraday value from `MarketQuotes` stays as it is.

## 7. Deleting `brokerage` (Q-3)

`accounts` replaced its account in phase 1 and `orders` its trades in phase 3; with settlement here, nothing in the
domain uses it. What goes with it: the `TradingAccounts` stream and its slices (19 command, 1 automation, 5 view), the
harness's trading-account bootstrap, load generator and dashboard parts that drive it, and its admin UI section. What
it carried that the demo must keep showing: snapshots and closing books live on `accounts.Account` too;
**generation archival** (`archive_generation`) exists only in `brokerage` and must move to `accounts` first.

---

## 8. Decisions

All four proposals accepted (2026-10-22).

| | Question | Decided |
|---|---|---|
| **Q-1** | A failed, cancelled or rejected settlement | Reverse the booking and list the execution as an operator case (§4) |
| **Q-2** | A corrected settlement amount | A ledger fact `SettlementAmountCorrected` on settled cash, cost basis (buy) or realized P&L (sell) (§5) |
| **Q-3** | Delete `brokerage` in this phase | Yes, after moving generation archival to `accounts` (§7) |
| **Q-4** | Valuation | Closing prices view in `market_data`, end-of-day account valuation view in `accounts` (§6); no statements yet |
