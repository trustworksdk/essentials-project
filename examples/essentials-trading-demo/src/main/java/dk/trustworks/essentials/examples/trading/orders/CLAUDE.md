# Bounded context: orders

Customer orders and their life at the exchange. Design: `docs/design/phase-3-orders-and-execution.md` (decisions §10,
what was built and where it deviates §11). Phase 3 order core: limit orders against a **stub exchange** (design Q-7);
the exchange-simulator adapter replaces the stub later.

**Write style: aggregate (§R5)** — `Order` behind `Orders` (a plain `StatefulAggregateRepository`, stream `Orders`).
No snapshots, no closing books: an order is short-lived.

## Rules that are easy to break

- **An order exists only together with its hold or reservation.** `place_order` saves the order and *sends* the
  ledger's `HoldFunds` / `ReserveShares` on the command bus inside its own unit of work, so a `LedgerRuleViolation`
  rolls the order back (`422`, no order). Do not move the hold into an automation: that is the saga the design
  rejected (Q-1).
- **The account stream is the account lock.** Two orders racing for one account's buying power collide on the
  account stream's optimistic append; the API retries the loser (`OptimisticAppendToStreamException`, 5 attempts,
  then `409`), and the retry is refused if the money is gone. `OrdersIT.concurrent_orders_cannot_spend_the_same_buying_power_twice`
  pins it. A retry loop that catches more than that exception would turn a `422` into a retry.
- **`HoldId` = `ReservationId` = `OrderId`.** The booking and release automation relies on it; nothing maps them.
- **Everything the exchange reports is a fact.** `RecordExchangeAnswer`, `RecordFill`, `RecordCancellation` are never
  refused for arriving late or out of order: a fill before the acceptance implies it, a fill after a cancel request or
  a rejection is still recorded (and booked), a repeated `executionId` adds nothing. Only the customer's own commands
  (`PlaceOrder`, `CancelOrder`) are checked.
- **`OrderClosed` is emitted exactly once**, together with the event that made the order terminal, and is the release
  trigger. A terminal status never changes afterwards; a late fill on a closed order is booked but does not reopen it.
- **Booking and release are one processor** (`automations/apply_order_to_account`). It delivers one order's events in
  order, so a release cannot overtake that order's bookings. Splitting it into two processors would break that.
- **Every exchange report goes through the `Orders:ExchangeReports` inbox**, never straight to the command bus. Reports
  for one order collide on its stream; the inbox redelivers the loser. The future webhook receiver must feed the same
  inbox (through `integration/ExternalEventIntake`).
- **`ExchangeOrderRouter`'s handlers are `UnitOfWorkMode.NONE`**: the exchange call holds no connection. Reading the
  order there needs an explicit `withUnitOfWork`. A cancel request before the exchange answered the submission is
  redelivered until the exchange order id is known; a closed order is let go.
- **Ownership is checked against `orders`' own projection of `AccountOpened`** (`views/account_owners`), eventually
  consistent: an account opened a moment ago answers `404` until it is projected.
- **A market buy holds quantity x best ask x (1 + `trading-demo.orders.market-order-collar`, 0.05)**, rounded up to the
  cent (Q-5), from `market_data`'s `MarketQuotes` seam. No fresh quote → `422`, never a guess. A fill above the hold
  is still booked.

## Stub exchange

`trading-demo.orders.stub-exchange.*`: `fill-mode` (`IMMEDIATE` matches on arrival; `NEVER`), `resting-instruments`
(accepted, never filled, cancellable), `rejected-instruments` (refused at submission), `zone-id` (trade date;
settlement T+1). Matching, once, on arrival, against `MarketQuotes`: market fills whole at ask/bid, or with no quote
is cancelled `MARKET_REMAINDER`; a marketable limit fills at the quote, an unmarketable one rests forever; an unquoted
limit fills at its limit. Per-instance memory: a restart forgets resting orders.

## Slices

| Slice | Kind | Driven by |
|---|---|---|
| `place_order` | command | customer (`POST /api/accounts/{accountId}/orders`) |
| `cancel_order` | command | customer (`POST /api/accounts/{accountId}/orders/{orderId}/cancellation`) |
| `record_exchange_answer`, `record_fill`, `record_cancellation` | command | the exchange, through `external_systems/exchange` |
| `external_systems/exchange` | external system | `OrderPlaced`, `OrderCancelRequested` |
| `apply_order_to_account` | automation | `OrderFilled` → `BookTrade`; `OrderClosed` → `ReleaseHold` / `ReleaseShares` |
| `orders` | view | customer (own accounts) and operator |
| `account_owners` | view | order entry's ownership check |

## Not built yet

DAY expiry at end of day, average fill price, the exchange-simulator adapter (webhooks, synchronous
executions in the submit answer, `EXCHANGE_UNAVAILABLE` after a retry budget), the `UnknownExchangeOrder` operator case.
