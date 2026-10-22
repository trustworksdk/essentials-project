# Bounded context: orders

Customer orders and their life at the exchange. Design: `docs/design/phase-3-orders-and-execution.md` (decisions §10,
what was built and where it deviates §11). Two `ExchangeGateway`s: the **stub exchange** (design Q-7), used when no
simulator is configured, and `SimulatorExchangeGateway`, the exchange-simulator adapter, under the `simulators`
profile (`trading-demo.simulators.exchange.base-url` set).

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
  for one order collide on its stream; the inbox redelivers the loser. The exchange's events feed the same inbox
  (through `integration/ExternalEventIntake`, see "Exchange-simulator adapter").
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
- **DAY expiry: the exchange's under the simulators, the brokerage's with the stub.** The exchange expires its own
  `DAY` orders (SR-17); its `OrderExpired` becomes `RecordExpiry` (`record_expiry`), which closes the order `EXPIRED`
  as a fact. Business date empty until the exchange sends one (SR-32) - never derived from its clock. The stub has no
  business day, so with it - and only with it - `POST /api/admin/orders/business-day-closed` stands in: `expire_order`
  records `OrderExpiryRequested`, the router cancels at the exchange, and the cancellation closes the order `EXPIRED`.
  That endpoint is conditional on no exchange base URL; it reads open orders from the eventually consistent view.
- **The retry budget for an unreachable exchange runs inside one delivery.** `ExchangeOrderRouter` retries
  `ExchangeUnavailableException` with a doubling backoff (`trading-demo.orders.exchange-submission.*`), then rejects
  `EXCHANGE_UNAVAILABLE`. Keep the total well inside the 30 s message-handling timeout; anything else the gateway throws
  goes to the processor's redelivery.
- **A market buy holds quantity x best ask x (1 + `trading-demo.orders.market-order-collar`, 0.05)**, rounded up to the
  cent (Q-5), from `market_data`'s `MarketQuotes` seam. No fresh quote → `422`, never a guess. A fill above the hold
  is still booked.

## Consumers and forwarding

`trading-demo.orders.consumers.*` (`OrderProcessingProperties`): threads on `Inbox:ExchangeOrderRouter`
(`exchange-router`), `Inbox:OrderLedgerProcessor` (`ledger`) and `Inbox:Orders:ExchangeReports` (`exchange-reports`);
code defaults 1 / 1 / 2, the `compose` profile runs **8 / 8 / 8 on a 40-connection pool**. Each inbox keeps one order's
messages in order on any number of threads. `trading-demo.orders.forwarding.batch-size` (`OrderForwardingProperties`,
`EventProcessor#getForwardBatchSize()`): code default 1, `compose` **100**. The two only pay together: batching alone left
~180 completed orders/s, 8 / 8 / 8 alone gave ~298, both ~366 (EF-2 doc §5). More threads on a 20-connection pool did
not help - each consumer holds a connection while it handles a message. Two instances at 40 fit PostgreSQL's
`max_connections` of 100. The code defaults stay small so the tests, which run on a smaller pool, are unaffected.

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
| `record_exchange_answer`, `record_fill`, `record_cancellation`, `record_expiry` | command | the exchange, through `external_systems/exchange` |
| `expire_order` | command | the end of the business day with the stub exchange, through `external_systems/exchange` |
| `external_systems/exchange` | external system | `OrderPlaced`, `OrderCancelRequested`, `OrderExpiryRequested`; the exchange's order events (`POST /webhooks/exchange/orders`, event log); operator (`POST /api/admin/orders/business-day-closed`, stub only) |
| `apply_order_to_account` | automation | `OrderFilled` → `BookTrade`; `OrderClosed` → `ReleaseHold` / `ReleaseShares` |
| `orders` | view | customer (own accounts) and operator |
| `account_owners` | view | order entry's ownership check |
| `unknown_exchange_orders` | view | operator (`GET /api/admin/orders/unknown-exchange-fills`) |
| `late_fills` | view | operator (`GET /api/admin/orders/late-fills`) |

## Exchange-simulator adapter

`SimulatorExchangeGateway`, on the generated client (`integration/exchange_simulator/client`, from
`src/main/openapi`). Covered by `SimulatorExchangeGatewayIT` against the real image (skips without it).

- **The synchronous answers are reported at once** (Q-2): the `201`'s acceptance, one `RecordFill` per execution with
  the exchange's `tradeDate` and `intendedSettlementDate` (SR-29), the `IOC` remainder as `RecordCancellation`
  `IOC_REMAINDER`, and a `DELETE`'s `200` as `RecordCancellation` `REQUESTED`. The webhooks will report the same again;
  the order deduplicates. A resting order's later fills and a `DAY` order's expiry arrive only through the webhooks.
- **A market order is sent without a time in force**: it is always `IOC` at the exchange, and `DAY`/`GTC` on one is a
  `400`.
- **Prices go as ticks**: minor units of the instrument's `currencyScale` over its `tickSizeMinor`, both read from
  `GET /api/instruments` and cached (they cannot change once listed). A price off the tick is rejected
  `INVALID_PRICE_TICK` without a call.
- **Cancel by `clientOrderId`** (SR-30, `DELETE /api/orders?participantId&clientOrderId`). `404` and `409` (no longer open)
  are let go.
- **The firm registers itself as participant** (`trading-demo.simulators.exchange.participant-id`, D8) the first time the
  exchange answers `unknown_participant`, which also covers a `/test/reset`.
- **Only no answer, a timeout or a `5xx` is `ExchangeUnavailableException`** - the router's retry budget. Every
  other refusal rejects the order with the exchange's error code, upper-cased.
- **Inbound: `ExchangeOrderEvents`** - `POST /webhooks/exchange/orders` (subscription `demo-orders` in
  `simulators/exchange.yaml`, participant-filtered to the firm) plus an `ExternalEventLogCatchUp` on the same consumer.
  Both queue the envelope into **`Orders:ExchangeReports` itself**; `ExchangeReports` translates it as it consumes it
  (`ExchangeOrderEventTranslation`, keyed by `clientOrderId` = our `OrderId`). Keep `EVENT_TYPES` and the yaml's
  `event_types` in step. Built on `ApplicationReadyEvent` (the inbox exists only then); earlier deliveries get `503`.
- **Fill events are not subscribed** - they carry no execution quantity/price, and `executionId` only on a resting
  order (SR-31). Until SR-31 a resting order's later fills are not booked.
- `ExchangeOrderEventsIT` cancels a resting order *at the exchange* so only the inbound path can close it: once via
  catch-up alone, once via a signed webhook to a host port exposed before the containers start; and a `DAY` order the
  exchange expires when the test moves its clock a day on.

## Not built yet

Fills from the exchange's events (SR-31); the business date on an exchange-driven expiry (SR-32).
