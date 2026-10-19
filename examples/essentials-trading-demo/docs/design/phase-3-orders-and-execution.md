# Phase 3 design — orders and execution

**Status:** decided (§10), being implemented — the order core first, against a stub exchange (Q-7).
**Plan:** [`../EXPANSION_PLAN.md`](../EXPANSION_PLAN.md) phase 3; decisions D8–D11, D14–D19, D21, D23; simulator
requirements SR-1–3, SR-5–SR-10, SR-12, SR-13, SR-16, SR-17, SR-28, SR-29.
**Builds on:** the account ledger of phase 1 ([`phase-1-account-core.md`](phase-1-account-core.md)), whose commands
— `hold_funds`, `release_hold`, `reserve_shares`, `release_shares`, `book_trade` — this phase starts sending.

Phase 3 lets a customer buy and sell: place an order, have it reach the exchange, see it fill, and see the fill land in
the account as a pending trade. Settlement — pending to settled — is phase 4.

Decisions this note asks for are marked **(Q-n)** and collected in §10.

---

## 1. Contexts

| Context | Contents in phase 3 |
|---|---|
| `orders` (new) | `Order` aggregate; the customer's place/cancel endpoints; the order views |
| `orders/external_systems/exchange/` | Outbound: submit and cancel at the exchange. Inbound: the exchange's order and trade events |
| `accounts` | Unchanged; its ledger commands are now sent by `orders`' automations |
| `market_data` | `Instrument` becomes the brokerage's *offering* of an exchange instrument (D21); the exchange's market-data feed into a latest-price view (SR-12/13); `InstrumentPrice` and its price-path benchmark removed (D19) |
| `brokerage` | Frozen; deleted in phase 4 |

---

## 2. The order lifecycle

```
            place (customer)                     exchange answers                  fills
  ┌────────┐  hold/reserve ok  ┌──────────┐  accepted   ┌──────┐ part  ┌──────────────────┐ rest ┌────────┐
  │ (none) │ ───────────────▶ │ PENDING  │ ──────────▶ │ OPEN │ ────▶ │ PARTIALLY_FILLED │ ───▶ │ FILLED │
  └────────┘                  └──────────┘             └──────┘       └──────────────────┘      └────────┘
       │ refused (buying power,     │ rejected / unreachable    │ cancel requested ▶ CANCEL_PENDING ▶ CANCELLED
       ▼ not offered, closed)       ▼                           │ day ends (DAY order) ▶ EXPIRED
   no order, 422              REJECTED                          ▼
```

- **An order exists only once its hold or reservation is in place** (Q-1). A customer whose buying power does not
  cover the order gets `422` and no order.
- **`PENDING`** means submitted, or being submitted, to the exchange. Submission is idempotent on `clientOrderId` =
  `OrderId`, so a resubmission after a timeout addresses the same exchange order.
- **Terminal states** — `FILLED`, `CANCELLED`, `REJECTED`, `EXPIRED` — release whatever remains of the hold or
  reservation. A filled buy's leftover is the difference between the hold and what the fills cost.
- **Fills are idempotent on `executionId`.** The order tracks filled quantity and average fill price; quantity never
  exceeds the order's.
- **Order types:** `LIMIT` and `MARKET`. **Time in force:** `DAY` (default) and `GTC`, `IOC` once SR-17 exists. Until
  then, a DAY order is cancelled by the brokerage at `BusinessDayClosed` (SR-26), and `IOC` is not offered.

### Events (on `Order`)

| Event | When |
|---|---|
| `OrderPlaced` | Accepted by the brokerage, hold/reservation in place: side, type, quantity, limit price, TIF, hold amount, account |
| `OrderSubmitted` | The exchange answered the submission: exchange order id |
| `OrderRejected` | The exchange refused it (with reason), or the brokerage gave up submitting |
| `OrderAccepted` | The exchange's `OrderAccepted` |
| `OrderFilled` | One execution: execution id, quantity, price, trade date, settlement date (SR-29) |
| `OrderCancelRequested` | The customer asked to cancel |
| `OrderCancelled` | The exchange's `OrderCancelled` (requested, market remainder, or IOC remainder) |
| `OrderExpired` | The exchange's `OrderExpired` (SR-17), or the brokerage's own end-of-day expiry |
| `OrderCompleted` | Terminal and settled with the account: the remainder has been released |

`OrderFilled` carries everything the account's `TradeBooked` needs, so the booking automation never calls back.

---

## 3. Placing an order (Q-1)

The customer waits for an answer, and buying power must be checked against the real ledger, not a view.

| Option | How | For | Against |
|---|---|---|---|
| **(a) One unit of work** | The `place_order` handler appends `OrderPlaced` and holds funds on the account in the same unit of work (`FundsHeld` with `HoldId` = `OrderId`) | The customer gets "insufficient buying power" at once, from the aggregate. No order without a hold, no hold without an order | Two aggregates in one transaction. An HTTP request then writes to an account stream that fills also write to, so a rare optimistic-concurrency conflict is retried |
| (b) Saga | `OrderPlaced` first; an automation keyed by account holds funds and then confirms or rejects the order | Strict one-writer-per-account (D1) | The customer is told "accepted" and then maybe "rejected for buying power" a moment later; two more events and states |

**Decided: (a).** It is what a brokerage's order ticket does.

**No account lock is needed, and that is a guarantee, not an assumption.** A trading platform split into services
typically locks the account, or reserves through a protocol, between accepting an order and submitting it, so two
orders cannot spend the same buying power. Here the hold *is* that protection, enforced by the event store:

- every account mutation appends to the account's stream at an expected `EventOrder`, and PostgreSQL's unique
  constraint on `(aggregate_id, event_order)` rejects the second of two appends at the same position — across
  instances as well;
- two orders placed at once both load the account at version N, both see enough buying power, both try to append
  `FundsHeld` at N+1: one commits, the other's whole unit of work (its `OrderPlaced` included) fails with
  `OptimisticAppendToStreamException`;
- the API retries the losing order (a few attempts, then `409`); the retry reloads the account with the first hold in
  place and refuses with `422` if buying power no longer covers it.

So there is never a window in which two orders spend the same money. The hold also outlives a lock-until-submitted:
it stays until the order is terminal and is consumed fill by fill. And it holds nothing across the exchange call,
where a lock is dangerous — an exchange timeout would freeze the account. It works because D1 keeps the order and the
account in one service, so one transaction; split into services, this would need the reservation protocol after all.
**Pinned by a test**: concurrent orders that together exceed buying power — exactly one is placed.

 The conflict is cheap: the account stream's writers are
this command, fills and settlements, and a retry is one reload. Phase 1's load test measured what racing does under
contention (15 % retries at a 50 % hot share); order placement is far below that rate per account.

---

## 4. Talking to the exchange

### Outbound — submit and cancel

- An automation reacts to `OrderPlaced` and calls `POST /api/orders` (`clientOrderId` = `OrderId`, the firm's
  participant id, D8). Like `risk_approve_instrument`, it is a `@MessageHandler(unitOfWork = UnitOfWorkMode.NONE)`
  handler: it holds no connection while it waits on the exchange, then records the answer in its own unit of work.
- **The synchronous answer is an acknowledgement, plus any executions it carries** (Q-2). The `201` already lists the
  executions of a marketable order. Recording them at once — idempotent on `executionId`, like everything from the
  webhook — gives the customer the fill without waiting for a webhook, and the webhook copy then deduplicates.
- **Unreachable exchange.** Retried with backoff; `clientOrderId` makes resubmission safe. After the retry budget the
  order goes `REJECTED` (`EXCHANGE_UNAVAILABLE`) and its hold is released. If the exchange did take it, its events
  still arrive and are booked — an executed trade is a fact (Q-3).
- **Cancel:** `cancel_order` records `OrderCancelRequested`; an automation calls `DELETE /api/orders/{id}`. The order
  is cancelled when the exchange says so, not when the customer asks; a fill in between is still booked.

### Inbound — order and trade events

- A translation slice receives the exchange's order events (`OrderAccepted`, `OrderPartiallyFilled`, `OrderFilled`,
  `OrderCancelled`, `OrderRejected`, `OrderExpired`) through the `integration/` receiver (signature, validation,
  dedupe, catch-up), keyed by `clientOrderId`.
- **The fill comes from the order event, not from `TradeExecuted`** (Q-4). Order events carry `clientOrderId` and the
  `executionId`, so routing to our `Order` needs no lookup. `TradeExecuted` names exchange order ids and both
  participants; it is what settlement and clearing reconciliation consume (phases 4–5).
- **Out-of-order tolerance.** Subscriptions are unordered (D11). A fill may arrive before `OrderAccepted`: the order
  accepts the fill (and implies acceptance) rather than refusing it. Every inbound command is idempotent on the
  exchange's ids, and a fill for an order the brokerage does not know is recorded as an `UnknownExchangeOrder`
  operator case — the phase 1 lesson, applied here from the start.

---

## 5. From a fill to the account

```
exchange order event ─▶ orders.external_systems.exchange ─▶ RecordFill(Order) ─▶ OrderFilled
                                                                                   │
                                     orders.automations.book_fill (keyed by account) ▼
                                                    BookTrade(Account, holdId/reservationId = OrderId)
```

- **Two hops, two keys.** Inbound events are processed per `OrderId`; `book_fill` is processed per `AccountId`, the D1
  model. The account sees each order's fills in order.
- **When the order is done**, `book_fill` (or a sibling automation) sends `ReleaseHold`/`ReleaseShares` for the
  remainder, and the order records `OrderCompleted`. Because the release goes through the same per-account key as the
  fills, it cannot overtake them — and if it ever did, the ledger now records `UnknownReferenceIgnored`.

---

## 6. Pre-trade checks

Checked synchronously in `place_order`:

| Check | Source |
|---|---|
| Instrument offered by the brokerage | `Instrument` (D21): approved by risk, not suspended, enabled at the exchange (SR-28) |
| Market open | Exchange session (SR-16) — until then, always open |
| Quantity a multiple of the lot size, limit price on the tick | Exchange instrument data (SR-28) |
| Buy: buying power covers the hold | The account aggregate, in the same unit of work (§3) |
| Sell: sellable quantity covers it (no short selling, D2) | The account aggregate |

**Hold amount for a buy** (Q-5): `LIMIT` = quantity × limit price. `MARKET` = quantity × best ask × (1 + collar), with
a configurable collar (5 %) and the best ask from the market-data view. A fill above the collar is still booked; the
account may go restricted, which is visible and rare.

---

## 7. Market data

- **Instruments** are exchange reference data (D18). `market_data` learns them from `InstrumentListed`/`InstrumentUpdated`
  (SR-28). `Instrument` keeps the brokerage's offering decision and the `risk_approve_instrument` automation (D21).
- **Prices:** a consumer of the exchange's market-data stream (SR-13) keeps a latest-price view (best bid/ask, last
  trade, staleness), refreshing from the snapshot (SR-12) on a gap. Not an aggregate (D19). It feeds the market-order
  collar, the customer's position values and, later, end-of-day valuation from `ClosingPriceIssued` (SR-14).
- **Removed in this phase** (D19): `InstrumentPrice`, its slices, `latest_price`, and the harness's price-path benchmark.
  The harness's main load lever moves to order flow.

---

## 8. Harness and load

- **Customers trading.** The load generator becomes simulated customers placing and cancelling orders through the same
  command types the HTTP API sends, against the exchange's liquidity provider (SR-6–SR-9). A market-open burst
  scenario exists from the start.
- **The phase 3 measurement:** orders/s end to end, from `place_order` to `TradeBooked`, and webhook-to-booking
  latency — on the fixed shard-owned engine, one and two instances.
- **Fault scenarios** reuse the simulators' fault injection: duplicated, damaged and reordered order events; exchange
  unreachable during submission; the liquidity provider pulled (unfilled market orders).

---

## 9. What waits for the simulators

| Needed for | Requirement |
|---|---|
| Any trading at all | SR-6–SR-10 (liquidity provider), SR-28 (instrument events) |
| Prices | SR-1, SR-3 (synthetic), SR-12, SR-13 (snapshot and stream); SR-2 for live |
| Booking with the right dates | SR-29 (trade and settlement date on executions) |
| DAY/IOC from the exchange | SR-16, SR-17 (else brokerage-side expiry) |

The `orders` aggregate, its slices, the ledger wiring and their tests can be built and tested against a stub exchange
before the simulator features land; the translation slices need the real contract.

---

## 10. Decisions

All seven proposals accepted (2026-10-19).

| | Question | Decided |
|---|---|---|
| **Q-1** | Place an order and hold funds in one unit of work, or as a saga? | One unit of work (§3) |
| **Q-2** | Book the executions in the synchronous submit answer, or wait for the webhook? | Book them at once; the webhook copy deduplicates |
| **Q-3** | When submission gives up, what if the exchange did take the order? | Its events are still booked as facts; the order shows `REJECTED` then a fill as an operator case |
| **Q-4** | Book fills from the exchange's order events or from `TradeExecuted`? | Order events (they carry `clientOrderId`); `TradeExecuted` is for settlement and reconciliation |
| **Q-5** | Market-order hold: collar on the best ask, and how wide? | Best ask × 1.05, configurable |
| **Q-6** | Offer `IOC` before SR-17, emulated by the brokerage? | No — DAY and GTC only until the exchange supports it |
| **Q-7** | Build the order aggregate and ledger wiring against a stub exchange now, before the simulator features? | Yes |
