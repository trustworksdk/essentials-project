# Trading demo — expansion plan

Grows this module from a snapshot/closing-books showcase into a realistic retail brokerage that trades against the
**exchange-simulator** and moves money through the **payment-gateway**, while keeping the test and performance
features it has today. Both simulators are ours and can be changed; their contracts are in
[`openapi/exchange-simulator/openapi.yaml`](openapi/exchange-simulator/openapi.yaml) and
[`openapi/payment-gateway/openapi.yaml`](openapi/payment-gateway/openapi.yaml), and their guides in
`exchange-simulator.zip`.

The demo has two purposes, and they are weighed equally: **showcase Essentials** in a realistic domain, and **push
it under realistic load** to drive new features, find performance improvements and flush out bugs.

This file records decisions as they are taken. § Open questions lists what is still undecided.

---

## Decisions

| # | Decision | Why |
|---|---|---|
| D1 | **Topology A: one Spring Boot app, a modular monolith, run as N instances.** Simulators run as separate containers in `compose.yml`. Push A as far as it goes before considering a split. | Essentials' FencedLock, DurableQueues, Inbox and Outbox coordinate *within* one service; A uses them as intended. Strict context boundaries keep a later extraction (the webhook-ingest edge first) possible. The previous platform was a B-style AWS split — A is the deliberate contrast. |
| D2 | **Cash account only**: no margin, no short selling. | Realistic retail baseline; margin later. |
| D3 | **Start in USD only**, but `Money` carries its currency from day one. EUR accounts with simple currency conversion come in a later FX phase (SR-18). *Revised: was EUR only.* | The Finnhub live feed is US stocks in USD. Retrofitting a currency field is expensive; adding conversion later is not. |
| D4 | **Settled vs. unsettled cash and positions from day one.** | T+N settlement and deposit returns make the distinction load-bearing for buying power and withdrawable cash. |
| D5 | **A `User` with Spring Security**, simple to begin with (form login + HTTP basic for API and load). No KYC. | Accounts need an owner who can log in; KYC adds no Essentials value yet. |
| D6 | **Breaking the existing event stream is fine.** Wipe the demo database. | Demo data has no value worth an upcasting effort. |
| D7 | **Existing features are kept where they make sense**, decided case by case as phases land: snapshots and closing books on `TradingAccount`, the `UnitOfWorkMode.NONE` examples, the automation tests on both queue engines, multi-instance runs, observability. | |
| D8 | **The firm is one exchange participant (omnibus).** `clientOrderId` = our `OrderId`; client attribution is our own bookkeeping. | What a real broker does. It makes clearing reconciliation realistic (net all clients first). |
| D9 | **Liquidity is a built-in feature of the exchange-simulator**, not a harness bot. | Under D8 our clients' crossing orders are rejected `SELF_TRADE`; someone else must be on the other side. |
| D10 | **Business dates come from simulator events**, never from our clock. | The simulators run a virtual clock (e.g. a simulated day per real minute). |
| D11 | **Webhook subscriptions are unordered; ordering is ours**, per key, on the shard-owned ordered queues. | `ordered: true` allows one delivery in flight per subscription and caps ingest under load. |
| D12 | **Customer UI in a later phase** (Thymeleaf + vanilla JS, per the repo rule). API and admin dashboard until then. | |
| D13 | **Simulator images are built locally** (`exchange-simulator:dev`, `payment-gateway:dev`). Simulator host ports move to 18080/18081. | 8080/8081 are the demo's two instances. |
| D14 | **The price feed lives in the exchange-simulator.** Its liquidity provider quotes around the reference price, and it publishes market data; our `market_data` consumes the exchange's feed. We never push prices into the exchange. | A broker consumes an exchange feed. One integration point, and the exchange keeps running when the demo is down. |
| D15 | **Price sources have modes**: `fixed`, `synthetic` (seeded, for tests and CI), `replay` (recorded, for load), `finnhub` (live). | Live prices cannot follow an accelerated virtual clock; tests and benchmarks need determinism. |
| D16 | **Finnhub for live prices** (an existing key), US stocks. | Free real-time EUR equities do not really exist. |
| D17 | **Simulator changes are specified in [`SIMULATOR_REQUIREMENTS.md`](SIMULATOR_REQUIREMENTS.md)** and implemented separately. | |
| D18 | **The exchange owns instruments.** Declared in its configuration (SR-5); our `market_data` learns them from `InstrumentListed`/`InstrumentUpdated` (SR-28). | Instruments are exchange reference data; a broker does not define them. |
| D19 | **A price is not an aggregate.** `InstrumentPrice` is removed; the latest price is a view fed by the exchange's market-data stream. The price-path benchmark that compared aggregate and direct price writes goes with it. **Removed in phase 3, not phase 0**: price updates fanning out into `trade_valuation` were the main load lever for the Inbox and shard-owned queue measurements (`_demo_harness/CLAUDE.md`), so the aggregate stayed until other load replaced it. **Done (phase 3)**: the order-flow scenario replaced that load; `InstrumentPrice`, its slices, `latest_price`, `brokerage.trade_valuation` and the price-path benchmark are deleted, and `market_data.market_quotes` (fed by a synthetic stand-in for the exchange's stream) is the latest-price view. | Prices are a high-rate feed, not decisions. The old aggregate was an exercise. |
| D20 | **`TradingAccount` closes its books `END_OF_DAY` on the exchange's clock.** Closing books gets an injected `java.time.Clock` that follows the exchange (`GET /api/clock` + `ClockChanged`, SR-24/25); `BusinessDayClosed` (SR-26) is the end-of-day signal. With the virtual clock off (`clock.mode: system`) it is an ordinary daily close. | Daily books match a broker's daily statement. On an accelerated clock (a day per real minute) every active account rolls a generation every minute, which exercises closing books, snapshots and archival hard — that is the point. |
| D21 | **Our app keeps an `Instrument` aggregate** in `market_data`: the brokerage's decision to *offer* an exchange instrument to its clients. A newly listed instrument starts not offered; the `risk_approve_instrument` automation (the `UnitOfWorkMode.NONE` example) approves or rejects it. No prices or exchange reference data in it. | Product approval is the brokerage's own decision, so it belongs in the event-sourced app, not the exchange. It keeps the `NONE` example in a realistic role. |
| D22 | **Books roll on the business day, not the calendar day.** A new Essentials closing-books feature: a period boundary driven by an external business calendar (here `BusinessDayClosed`, SR-26) instead of midnight in a zone. Additive only (stable-API rule). See § Essentials features. | A trade after the clearing cutoff belongs to the next business day; a calendar-day boundary would book it into the wrong period. |
| D23 | **Trades carry their trade date and settlement date from the exchange** (SR-29). The demo books a trade by its `tradeDate` and never derives a business date itself. The current business date is *not* added to `GET /api/clock`: on a cold start the catch-up reader replays the latest `BusinessDayClosed`. | Real exchanges send both with the execution (FIX 75, 64). Needed for booking, settlement dates and reconciliation, independent of closing books — the test for any simulator change is "does the domain need it and would a real exchange do it", not "does Essentials want it". |
| D24 | **EF-1 shape.** A `ClosingBooksBusinessCalendar` SPI behind the existing `END_OF_DAY` boundary, selected by a new annotation member and enum (`periodSource`) — acceptable in a minor release. The library ships **only the SPI and an in-memory implementation**; the demo stores its calendar itself (a table fed by `BusinessDayClosed`). An unknown business date means **no rollover**, with a warning and a metric. The scheduled-scan gaps are a separate **EF-1b**. Design: `docs/closing-books-business-day-boundary.md` at the repository root. | Small library surface; calendar sources differ between users. Promoting the demo's store into the library later is additive, the reverse is not. A broker keeps trading on unknown bookkeeping metadata and lets end-of-day reconciliation catch the rest. |
| D25 | **Phase 1 design decided** — [`design/phase-1-account-core.md`](design/phase-1-account-core.md) §10. A new `accounts` context beside a frozen `brokerage` (deleted in phase 4); `User` is an event-sourced aggregate with credentials in a separate `user_credentials` table; the whole ledger is built in phase 1; deposit return window 3 business days, configurable. | |

---

## Bounded contexts

| Context | Aggregates | Responsibility |
|---|---|---|
| `identity` | `User` | Users, credentials, roles `CUSTOMER` and `OPERATOR`. |
| `accounts` | `TradingAccount` | The core ledger. Cash buckets: settled, unsettled in/out, holds. Positions per instrument: settled, pending, reserved for open sells. Derived buying power and withdrawable cash. Keeps snapshots and closing books. |
| `orders` | `Order` | Lifecycle NEW → HELD → SUBMITTED → ACCEPTED → PARTIALLY_FILLED → FILLED, or CANCELLED / REJECTED. Fills by `executionId`, average price. |
| *(no context)* `integration/` | — | Shared receiving mechanics for both simulators, like `config/`: signature check, dedupe per `(consumer, eventId)` into the consumer's Inbox, catch-up read of `/api/events`. Each consuming context owns its own translation slice under `external_systems/<system>/` (its subscription, endpoint, Inbox, payload validator); the outbound order client lives in `orders/external_systems/exchange/`. *Replaces the earlier `exchange_gateway` context: the slice law puts translation slices inside the context that consumes them.* |
| `funding` | `Deposit`, `Withdrawal` | Payments through the gateway. A withdrawal holds funds before initiating. `PaymentReturned` reverses a completed payment. |
| `settlement` | `TradeSettlement` | One per execution, driven by the simulator's settlement events. Pending → settled; failed, delayed, corrected. |
| `reconciliation` | `Break` | Clearing statements vs. our bookings, bank statements vs. our payments. Operator case workflow. |
| `market_data` | `Instrument` | Instruments. Consumes the exchange's market data (SR-12, SR-13) into a latest-price view, and `ClosingPriceIssued` (SR-14) for end-of-day valuation. |
| `_demo_harness` | — | Simulated users, scenarios (market-open burst, faults), clock control, dashboard. |

### Scaling under A

Every account-scoped message is keyed by `accountId` on the shard-owned ordered queues. One account is processed
serially, cluster-wide, by whichever instance owns its shard; different accounts run in parallel. This removes
optimistic-concurrency conflicts on the hot account stream, and is why positions live inside `TradingAccount`: the
buying-power and sell checks stay consistent without cross-aggregate coordination.

---

## Phases

Each phase ends with a load scenario and before/after measurements. Performance work runs throughout, not at the end.

0. **Foundation.** Simulators in compose and Testcontainers. `Money` (USD, D3) is Essentials' own
   `dk.trustworks.essentials.types.Money` (amount + currency, mixed currencies rejected; a JSON object, ESS-022) — no
   demo type; existing `Amount` fields move to it as phase 1 rewrites the account. The ingest edge: webhook receiver → Inbox
   → per-account routing, with fault-injection tests (duplicates, out-of-order, missing fields). Needs SR-1, SR-3 and
   SR-5 from the simulator; the liquidity provider itself is needed by phase 3.
1. **Identity and account core.** `User`, login, account opening, the cash-bucket and position ledger, a buying-power
   view.
2. **Funding.** Deposits and withdrawals, returns, withdrawable-cash rules, a first bank-statement reconciliation.
3. **Orders and execution.** Order lifecycle, holds, fills, cancels, market data.
4. **Settlement.** Simulated T+N, pending → settled, failures and corrections.
5. **Reconciliation.** Clearing and bank breaks, checked one-for-one against the simulators' `/test/*/breaks`; operator
   case workflow.
6. **Realistic load.** User-session load model, market-open burst, multiple instances, benchmarks against `main`.
7. **Customer UI.** Portfolio, order ticket, funding.

Later: fees and commission, multi-currency and FX, margin, corporate actions, DAY-order expiry (cancel at the close),
customer notifications, and **corrections of executed trades** — the exchange-simulator emitting a price or quantity
correction for an execution (it already has `SettlementCorrected`), and the ledger rebooking it: reverse and rebook, or
a correcting delta event, and what that does to average cost and realized P&L when the position has moved since.

---

## Open questions

- ~~Q1 — Price source~~ → D14–D16.
- ~~Q2 — The liquidity feature's design~~ → SR-6 to SR-11; implemented separately (D17).
- ~~Q3 — Who owns instruments?~~ → D18.
- ~~Q4 — Closing-books periods vs. the virtual clock~~ → D20.
- ~~Q5 — Does the app keep an `Instrument` aggregate?~~ → D21.
- ~~Q6 — Calendar day or business day?~~ → D22.

---

## Essentials features this plan needs

Library changes the demo drives. Each is designed and measured in its own right, in the library's module, before the
demo depends on it.

- **EF-1 — Business-day closing-books boundary (D22).** Today a time boundary is computed from a `Clock` and a zone
  (`END_OF_DAY` = midnight). Needed: a boundary whose current period comes from an application-supplied business
  calendar, advanced by an external signal, so `ON_ACCESS` evaluation compares an aggregate's period with the
  calendar's current business date. Shape decided in D24; design in `docs/closing-books-business-day-boundary.md`.
- **EF-1b — Scheduled-scan closing books.** Found while designing EF-1: the built-in evaluator never rolls on a
  scan (`asDecisionPolicy()` answers keep-open), and a scan rollover seeds no period id and carries no state
  forward. Separate change, after EF-1.
- **EF-2 — Key-partitioned event processing on the shard-owned engine (candidate, from phase 1 measurements).**
  `EventProcessor`/`ViewEventProcessor` always consume exclusively (one instance cluster-wide behind a fenced lock),
  and so does a `SingleGlobalConsumer` inbox. On the shard-owned engine that throws away what the engine is for: it
  already gives per-key order cluster-wide. Measured (`_demo_harness/CLAUDE.md`, "The ledger load scenario"): the
  account views process ~700–800 events/s and trail a 20 000-command run by 13–25 s whatever the command side does,
  while an inbox switched to `GlobalCompetingConsumers` splits keys across two instances with zero conflicts. A
  processor mode where the subscription's events are keyed by aggregate id and consumed by every instance, with the
  engine's per-key order instead of the fenced lock, is the candidate. Open: the subscription/resume-point model (today
  one resume point per processor, owned by the lock holder), and how a view's catch-up and reset work when several
  instances project.
- **EF-3 — Doc fix (later, requested):** `LLM/LLM-foundation.md` and the `Inboxes` javadoc show `InboxConfig.builder().setInboxName(...)`; the builder's
  methods are `inboxName(...)`, `messageConsumptionMode(...)`, etc.
