# Bounded context: orders

Customer orders and their life at the exchange. Design: `docs/design/phase-3-orders-and-execution.md` (decisions §10,
what was built and where it deviates §11). Phase 3 order core: limit orders against a **stub exchange** (design Q-7);
the exchange-simulator adapter replaces the stub later.

**Write style: aggregate (§R5)** — `Order` behind `Orders` (a plain `StatefulAggregateRepository`, stream `Orders`),
and `UnknownExchangeOrder` behind `UnknownExchangeOrders` (stream `UnknownExchangeOrders`). No snapshots, no closing
books: an order is short-lived.

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
  trigger. A terminal status never changes afterwards; a late fill on a closed order is booked but does not reopen it,
  and is flagged `OrderFilledAfterClose` (operator case, Q-3; also an over-fill on a `FILLED` order).
- **Booking and release are one processor** (`automations/apply_order_to_account`). It delivers one order's events in
  order, so a release cannot overtake that order's bookings. Splitting it into two processors would break that.
- **Every exchange report goes through the `Orders:ExchangeReports` inbox**, never straight to the command bus. Reports
  for one order collide on its stream; the inbox redelivers the loser. The future webhook receiver must feed the same
  inbox (through `integration/ExternalEventIntake`).
- **`ExchangeOrderRouter`'s handlers are `UnitOfWorkMode.NONE`**: the exchange call holds no connection. Reading the
  order there needs an explicit `withUnitOfWork`. **Cancel by our `OrderId`, never wait for the exchange's id**
  (`ExchangeGateway.cancel(OrderId)`): that id is recorded only once the answer has gone through the reports inbox, and
  a cancel that waited for it by redelivery was dead-lettered after 20 attempts under load, leaving the order
  `CANCEL_PENDING` and its hold never released. The router's inbox is ordered per order, so the order was submitted
  before its cancel arrives. A closed order is let go.
- **Ownership is checked against `orders`' own projection of `AccountOpened`** (`views/account_owners`), eventually
  consistent: an account opened a moment ago answers `404` until it is projected.
- **A fill for an unknown order is an operator case, not a retry.** `record_fill` records it on an
  `UnknownExchangeOrder` (once per `executionId`) and books nothing. Never put it on the `Orders` stream: a stream with no
  `OrderPlaced` makes a later `PlaceOrder` with that id look like a repeat and place nothing.
- **DAY expiry is a cancel at the exchange, closed `EXPIRED`.** `expire_order` records `OrderExpiryRequested`; the
  router cancels at the exchange as for a customer cancel, and the exchange's `RecordCancellation` then closes the
  order `OrderExpired`. A stopgap until the exchange expires its own DAY orders (SR-17); triggered by
  `POST /api/admin/orders/business-day-closed` until the adapter receives `BusinessDayClosed` (SR-26). Reads the open
  orders from the eventually consistent `orders` view.
- **The retry budget for an unreachable exchange runs inside one delivery.** `ExchangeOrderRouter` retries
  `ExchangeUnavailableException` with a doubling backoff (`trading-demo.orders.exchange-submission.*`), then rejects
  `EXCHANGE_UNAVAILABLE`. Keep the total well inside the 30 s message-handling timeout; anything else the gateway throws
  goes to the processor's redelivery.
- **A market buy holds quantity x best ask x (1 + `trading-demo.orders.market-order-collar`, 0.05)**, rounded up to the
  cent (Q-5), from `market_data`'s `MarketQuotes` seam. No fresh quote → `422`, never a guess. A fill above the hold
  is still booked.

## Consumers

`trading-demo.orders.consumers.*` (`OrderProcessingProperties`): threads on `Inbox:ExchangeOrderRouter`
(`exchange-router`, 1), `Inbox:OrderLedgerProcessor` (`ledger`, 1) and `Inbox:Orders:ExchangeReports`
(`exchange-reports`, 2). Each inbox keeps one order's messages in order on any number of threads. 8 / 8 / 8 with a
40-connection pool (`spring.datasource.hikari.maximum-pool-size`) completed the order flow ~2x faster than the defaults
(60 did no better); more threads on the default 20-connection pool did not help (EF-2 doc §5, M5). Two instances at 40
fit PostgreSQL's `max_connections` of 100. Defaults left as they are, by decision.

## Stub exchange

`trading-demo.orders.stub-exchange.*`: `fill-mode` (`IMMEDIATE` matches on arrival; `NEVER`), `resting-instruments`
(accepted, never filled, cancellable), `rejected-instruments` (refused at submission), `unreachable-instruments`
(every submission fails as unreachable, for the retry budget), `zone-id` (trade date;
settlement T+1). Matching, once, on arrival, against `MarketQuotes`: market fills whole at ask/bid, or with no quote
is cancelled `MARKET_REMAINDER`; a marketable limit fills at the quote, an unmarketable one rests forever; an unquoted
limit fills at its limit. Per-instance memory: a restart forgets resting orders.

## Slices

| Slice | Kind | Driven by |
|---|---|---|
| `place_order` | command | customer (`POST /api/accounts/{accountId}/orders`) |
| `cancel_order` | command | customer (`POST /api/accounts/{accountId}/orders/{orderId}/cancellation`) |
| `record_exchange_answer`, `record_fill`, `record_cancellation` | command | the exchange, through `external_systems/exchange` |
| `expire_order` | command | the end of the business day, through `external_systems/exchange` |
| `external_systems/exchange` | external system | `OrderPlaced`, `OrderCancelRequested`, `OrderExpiryRequested`; operator (`POST /api/admin/orders/business-day-closed`) |
| `apply_order_to_account` | automation | `OrderFilled` → `BookTrade`; `OrderClosed` → `ReleaseHold` / `ReleaseShares` |
| `orders` | view | customer (own accounts) and operator |
| `account_owners` | view | order entry's ownership check |
| `unknown_exchange_orders` | view | operator (`GET /api/admin/orders/unknown-exchange-fills`) |
| `late_fills` | view | operator (`GET /api/admin/orders/late-fills`) |

## Not built yet

The exchange-simulator adapter (webhooks, synchronous executions in the submit answer, `BusinessDayClosed` in place of
the operator endpoint).
