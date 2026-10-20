# Simulator requirements for the trading demo expansion

New features the exchange-simulator (and, in small part, the payment-gateway) need so the trading demo can run as a
realistic retail brokerage. Context and the decisions behind these requirements are in
[`EXPANSION_PLAN.md`](EXPANSION_PLAN.md): in particular D8 (the firm is one omnibus participant), D9 (liquidity is
built into the exchange), D10 (business dates come from simulator events), D14–D16 (price source), D18 (the exchange
owns instruments) and D20 (books close at the end of the exchange's day).

Each requirement has a stable id (`SR-n`) so the demo's code, tests and issues can refer to it. Priorities:

- **MUST** — the demo phase named in brackets cannot start without it.
- **SHOULD** — wanted for realism or load testing; the demo can work around its absence for a while.
- **LATER** — a later demo phase; listed so the design leaves room for it.

Where this document describes behaviour, the wire details (field names, paths, config keys) are proposals. Change
them freely, as long as the OpenAPI spec is updated and the behaviour stays.

---

## Overview

```
                ┌────────────────────────── exchange-simulator ───────────────────────────┐
 Finnhub ──ws──▶│ price source ──reference price──▶ liquidity provider ──quotes──▶ order book │
 (or replay,    │  (SR-1..5)                          (SR-6..11)                   (matching) │
  synthetic)    │      │                                                              │      │
                │      └──────────────▶ market-data channel (SR-12..15) ◀─────────────┘      │
                │                        snapshot + SSE stream, not durable                  │
                │                        closing prices into the canonical event log         │
                └────────────────────────────────────────────────────────────────────────────┘
                                             │ SSE / REST                 │ webhooks / /api/events
                                             ▼                            ▼
                                      trading demo `market_data`    trading demo ingest edge
```

The trading demo never pushes prices into the exchange. It consumes the exchange's market data, as a broker consumes
an exchange feed.

---

## 1. Price source

The price source produces a **reference price** per instrument. The liquidity provider quotes around it, and the
market-data channel publishes it.

### SR-1 — Pluggable price source, selected per instrument (MUST) [phase 0]

Each instrument has a price source of one of these kinds:

| Kind | Produces | Needs network | Deterministic |
|---|---|---|---|
| `fixed` | A constant price from configuration or the API | no | yes |
| `synthetic` | A seeded random walk (SR-3) | no | yes |
| `replay` | Prices from a recorded file (SR-4) | no | yes |
| `finnhub` | Live prices from Finnhub (SR-2) | yes | no |
| `none` | Nothing. The instrument trades only on client orders, as today | no | yes |

A global default with per-instrument overrides is enough. `none` keeps today's behaviour, so existing simulator tests
and scenarios are unaffected.

**Accept when:** an instrument configured with each kind produces reference prices, or none for `none`, and a change of
kind at runtime (SR-11) takes effect without a restart.

### SR-2 — Finnhub live source (MUST) [phase 3]

- **Transport.** The Finnhub trades websocket (`wss://ws.finnhub.io?token=<key>`), subscribing per symbol. On connect,
  and whenever a symbol has no tick for a while, seed or refresh the price from the REST quote endpoint
  (`/api/v1/quote?symbol=`). The REST quote also covers a closed market, when no trades stream.
- **Symbol mapping.** Each instrument maps to a provider symbol (`referenceSymbol`, e.g. `AAPL`), which may differ from
  its exchange `symbol`.
- **The key** comes from a secret file or an environment variable. It is never logged, never returned by the API and
  never in metrics labels.
- **Resilience.** Reconnect with exponential backoff and jitter; re-subscribe every symbol after a reconnect. A source
  that is down must not stop the exchange: the liquidity provider keeps its last ladder, or pulls it per SR-9.
- **Staleness.** A reference price older than `stale_after` (real time) is marked stale in the market data (SR-12),
  and the liquidity provider applies its stale policy (SR-9).
- **Free-tier limits.** Respect the provider's limits on subscribed symbols and REST calls per minute. Exceeding a limit
  is a startup configuration error where it can be detected, and a logged, metered degradation otherwise. The limits
  must be checked against Finnhub's current terms, not taken from this document.
- **Time.** A tick is applied at the exchange's current time (the virtual clock), and carries the provider's own
  timestamp as `sourceTimestamp`. Live prices on an accelerated clock are allowed; the market data says which source
  produced a price, so a consumer can tell.
- **Conflation.** At most `max_updates_per_second` reference updates per instrument; the latest tick wins.

**Accept when:** with a valid key the reference price of a mapped US stock follows Finnhub during market hours;
killing the network for a minute marks prices stale and recovers without a restart; the key appears nowhere in logs,
API answers or `/metrics`.

### SR-3 — Synthetic source (MUST) [phase 0]

A seeded geometric Brownian motion per instrument: initial price, drift, volatility, and a tick interval in
simulated time. With deterministic ids and a virtual clock (the existing reproducible-run recipe), two runs give
identical reference prices. Prices are rounded to the instrument's tick size and never reach zero or below.

**Accept when:** two runs with the same seed and clock give a byte-identical event log, closing prices included.

### SR-4 — Replay source and recorder (SHOULD) [phase 6]

- **Replay.** Plays a file of `(timestamp, referenceSymbol, price, volume)` rows (CSV or JSON lines) on the virtual
  clock, offset so the file's first row lands at the clock's start. Options: loop at the end; speed taken from the
  clock.
- **Recorder.** In `finnhub` mode, optionally appends every applied tick to a local file in the replay format. A live
  session can then be replayed deterministically on an accelerated clock, which is how realistic price paths get into
  load tests.
- Recorded files stay local: vendor data must not be committed to either repository.

**Accept when:** a recorded session replayed at speed 60 reproduces the recorded price path, and replaying it twice
gives the same event log.

### SR-5 — Instruments declared in configuration (MUST) [phase 0]

Instruments can be declared in the configuration file, with their price source and liquidity settings, the way webhook
subscriptions already are. The market can then run before the trading demo starts, and `POST /test/reset` restores it.
API-registered instruments keep working and accept the same new fields (`referenceSymbol`, price source, liquidity).

**Accept when:** a fresh start with a declared instrument has a live book with liquidity before any client connects.

---

## 2. Built-in liquidity provider

Under D8 every client order of the trading demo comes from one participant, so two of our clients can never trade
with each other (`SELF_TRADE`). The liquidity provider is the other side.

### SR-6 — Liquidity provider participant (MUST) [phase 3]

- A participant created by the simulator (default id `LP`; configurable), present on clearing statements and in
  settlement like any participant. Clearing obligations then still net to zero across all participants, and the
  demo's reconciliation stays realistic.
- One provider for all instruments is enough; its settings are per instrument.
- It never trades with itself.

### SR-7 — Quote ladder (MUST) [phase 3]

Per instrument, around the reference price `R`:

| Setting | Meaning |
|---|---|
| `levels` | Price levels per side |
| `spread_bps` | Distance from `R` to the best bid and best ask |
| `level_step_ticks` | Distance between levels |
| `level_quantity` | Quantity per level, in lots; optionally growing with depth |
| `requote_threshold_ticks` | `R` must move at least this far before the ladder is re-quoted; limits churn |

- Prices are rounded away from `R` to the tick size (bids down, asks up), so the spread never shrinks by rounding.
- Re-quoting is atomic inside the exchange thread: no client order ever sees a half-replaced ladder.
- A market order sweeps the ladder. When the ladder is exhausted, the remainder is cancelled as today
  (`MARKET_REMAINDER`). Depth is a setting, so "not enough liquidity" is a scenario, not a bug.
- Client limit orders that cross the ladder fill against it at the ladder's prices (price-time priority unchanged).
- Client limit orders that rest inside the spread are allowed and can be filled by other client flow.

**Accept when:** with `R = 100.00`, `spread_bps = 10` and tick 0.01, the best bid is 99.90 and the best ask 100.10; a
market buy larger than the ladder fills level by level and cancels the remainder.

### SR-8 — Refill and inventory skew (MUST) [phase 3]

- **Refill.** A filled level is replenished after `refill_delay` (simulated time; 0 = at once).
- **Inventory.** The provider tracks its net position per instrument. Quotes are skewed by `skew_bps_per_lot` times the
  inventory: a long provider lowers both sides, so one-sided client buying pushes the traded price up, as in a real
  market.
- **Limit.** At `max_inventory` the provider stops quoting the side that would increase it.

**Accept when:** a run of client buys raises the best ask progressively, and stops at `max_inventory` with only bids
left.

### SR-9 — Stale and closed-market policy (MUST) [phase 3]

When the reference price is stale (SR-2), or the instrument's market is closed (SR-16 when it exists), the provider
either keeps its ladder, widens its spread by a factor, or pulls the ladder entirely. This is configurable per
instrument and visible in the market data.

### SR-10 — Order events of the provider are optional (MUST) [phase 3]

Re-quoting produces far more order events than client trading. Writing every one into the canonical event log and
fanning it out over webhooks would make the provider, not the clients, dominate the exchange's load.

- `liquidity.emit_order_events` (default `false`): when off, the provider's order lifecycle is **not** written to the
  event log and not delivered over webhooks.
- `TradeExecuted`, settlement and clearing for the provider's trades are **always** emitted: the provider is the
  counterparty of real client trades.
- Metrics count the provider's quotes, re-quotes and fills either way.

### SR-11 — Runtime control (SHOULD) [phase 5]

- `GET /api/liquidity` and `GET /api/liquidity/{instrumentId}`: settings, inventory, current ladder, source and
  staleness.
- `PATCH /api/liquidity/{instrumentId}`: change any setting at runtime, including the price source and `mode`
  (`on`/`off`).

Scenarios use it to drain liquidity (partial fills, unfilled market orders), widen spreads at "market open", or switch
an instrument from `finnhub` to `synthetic` when the market closes.

---

## 3. Market-data channel

### SR-12 — Market-data snapshot (MUST) [phase 3]

`GET /api/marketdata` and `GET /api/marketdata/{instrumentId}`. Per instrument:

- the reference price, its source kind, `sourceTimestamp` and whether it is stale;
- best bid and ask, with quantity;
- last trade price, quantity and time, and the session's volume;
- `mdSequence`, a per-instrument sequence number, monotonic.

### SR-13 — Market-data stream (MUST) [phase 3]

Server-sent events at `GET /api/marketdata/stream?instruments=…` (all instruments when absent).

- **Messages:** `quote` (best bid/ask changed), `trade` (an anonymous trade print: price, quantity, time; no
  participants) and `reference` (reference price changed, or became stale).
- **Ordering and gaps:** every message carries its instrument's `mdSequence`. A consumer that sees a gap fetches the
  snapshot (SR-12) and continues.
- **Conflation:** per instrument and message kind, at most one message per `conflation_interval_ms`; the latest wins.
- **Back-pressure:** a slow consumer is never allowed to slow the exchange. Its stream is conflated harder, or closed;
  the consumer reconnects and resynchronises from the snapshot.
- **Not durable, not replayable.** The stream is a live feed, like a real exchange's market data. It is not written to
  the canonical event log and not delivered over webhooks.

**Accept when:** a consumer that is killed and restarted resynchronises from the snapshot with no stale state; a
consumer that stops reading does not change the exchange's order-ack latency.

### SR-14 — Closing prices in the canonical event log (MUST) [phase 4]

At the clearing cutoff of each business day (the existing `clearing.cutoff`), one `ClosingPriceIssued` per traded
instrument into the canonical event log, delivered over webhooks like every other event: `instrumentId`,
`businessDate`, `closingPrice` (last trade, else the reference price), its basis (`LAST_TRADE` or `REFERENCE`), and
the day's volume.

Durable and replayable, unlike the stream. The trading demo uses it for end-of-day valuation, account statements and
closing books, so it must be reproducible.

### SR-15 — Market-data metrics (SHOULD) [phase 6]

Per instrument: reference updates, staleness, stream subscribers, messages sent, messages conflated and streams closed
for back-pressure. Source health: connected, reconnects, rate-limit hits.

---

## 4. Trading sessions and time in force

### SR-16 — Trading sessions per market (SHOULD) [phase 3]

Opening and closing time per MIC, in local time, on business days (the clearing calendar already knows business days).
Outside a session:

- new client orders are rejected with `rejectReason: MARKET_CLOSED`;
- the liquidity provider applies its closed-market policy (SR-9).

`none` (always open) stays the default, so existing behaviour is unchanged.

### SR-17 — Time in force (SHOULD) [phase 3]

`timeInForce` on `OrderRequest`: `DAY` (default when sessions are on), `GTC` and `IOC`.

- `DAY` orders still open at the session close are expired with a new `OrderExpired` event.
- `IOC` cancels any remainder at once, with `cancelReason: IOC_REMAINDER`.

Without this the trading demo cancels its own DAY orders at the close, which is acceptable as a stopgap.

---

## 5. Currency

### SR-18 — FX reference rates (LATER) [FX phase]

The price source also supplies FX rates for configured pairs (e.g. `EUR/USD`), from Finnhub's forex symbols or a
synthetic or fixed source. They are published on the market-data channel as non-tradable instruments, and as daily
`FxRateFixed` events at the cutoff for reproducible conversion. The trading demo starts in USD only (D3); this
requirement lets it later offer EUR accounts with simple conversion.

---

## 6. Webhooks and load

### SR-19 — Participant filter on webhook subscriptions (SHOULD) [phase 0]

An optional `participants` filter on a subscription: order lifecycle events are delivered only for orders of those
participants. Trade, settlement and clearing events are delivered when either side is a listed participant. This
lets two independent demo stacks share one exchange, and keeps the provider's order events out even when
`emit_order_events` is on.

### SR-20 — Load profile with the provider (SHOULD) [phase 6]

The existing benchmark harness gains a run with the liquidity provider on and a live or replayed price source, so the
cost of re-quoting is measured and not discovered. Report the order-ack latency with and without the provider at the
same client order rate.

---

## 7. Payment gateway

### SR-21 — USD and an opening balance (MUST) [phase 2]

USD in `payments.currencies`, and a USD opening balance in `statement.opening_balances`. Configuration only.

### SR-22 — Forced outcomes for tests (SHOULD) [phase 2]

With test control on, a way to force one payment's outcome (completed, failed with a given reason, returned after a
given delay). For example a test-only header on `POST /api/payments`, or a `/test/payments/{id}/outcome` call. Seeded
probabilities cover load; tests need a specific path, every time.

### SR-23 — Memory persistence mode (SHOULD) [phase 6]

The exchange's `memory` and `fast` modes, for load runs where the gateway must not be the bottleneck.

---

## 8. Clock and reference data

The trading demo closes every account's books at the end of each day (`END_OF_DAY`) on the **exchange's** clock, not
its own (D10, D20). Essentials closing books takes an injectable `java.time.Clock`, so the demo supplies one that
follows the exchange. The simulator must make its clock observable without test control, and it must be possible to
turn the virtual clock off.

### SR-24 — Clock readable and controllable outside test control (MUST) [phase 1]

- **Read.** `GET /api/clock`, always mounted: `mode` (`virtual`/`system`), `running`, `speed`, `now`. The demo reads it
  at startup and after a gap.
- **Control.** Speed, freeze and resume through an operator API that does **not** require `test_control.enabled`.
  Today these exist only under `/test`, which also mounts reset and fault injection; a showcase deployment must be
  able to change speed without exposing those. `set` (jumping forward) and `reset` stay test-only.
- **Turning the virtual clock off.** `clock.mode: system` already runs on real time, and stays the way to switch the
  virtual clock off at startup. At runtime, the closest equivalent is `speed: 1`. A switch from `virtual` back to
  `system` at runtime is not required: simulated time is usually ahead of real time, and the clock never moves
  backwards.

### SR-25 — Clock changes in the canonical event log (MUST) [phase 1]

A `ClockChanged` event (`mode`, `running`, `speed`, `now`, and the real time it took effect) for every change of speed,
freeze, resume or set, delivered over webhooks like every other event. The demo's exchange-following clock then
interpolates between events (`now + elapsed real time × speed`) instead of polling, and stays correct across a
freeze or speed change.

### SR-26 — Business day closed (MUST) [phase 1]

A `BusinessDayClosed` event at the clearing cutoff of every business day (`businessDate`, `cutoffAt`, `nextBusinessDate`),
emitted **even on a day without trades**. It is the authoritative end-of-day signal for the demo's end-of-day work
(statements, valuation, closing books), and orders it before that day's `ClearingStatementIssued` and
`ClosingPriceIssued` (SR-14). Emitted whether or not `clearing.enabled` is on.

### SR-27 — The gateway follows the exchange's clock (SHOULD) [phase 2]

A `clock.mode: follow` for the payment gateway, taking its time from the exchange's (`GET /api/clock` and
`ClockChanged`). Today the two virtual clocks are resumed together by hand and drift apart by the length of any
restart; one clock removes a whole class of "the bank statement is for a different day" confusion.

### SR-28 — Instrument events (MUST) [phase 0]

Instruments are owned by the exchange (D18): declared in its configuration (SR-5) or registered through its API. The
demo learns them from the exchange, so instrument changes must be in the canonical event log: `InstrumentListed` (all
of `Instrument`, plus `referenceSymbol` and session information when SR-16 exists) and `InstrumentUpdated` (trading
enabled or disabled, or any other change). Declared instruments are listed at startup like any other.

### SR-29 — Trade date and settlement date on every execution (MUST) [phase 3]

A real exchange tells the member which business day a trade belongs to, with the execution itself: FIX tag 75
`TradeDate` and tag 64 `SettlDate` on the execution report. The simulator already computes both — they are on
`SettlementPending` (`tradeDate`, `intendedSettlementDate`) and on the clearing statement's lines — but not on the
events a member books trades from. Without them the trading demo would have to re-implement the exchange's calendar
(cutoff, time zone, weekends, per-market settlement cycles) to book a trade into the right day, compute its
settlement date and reconcile it, which is exactly the "own clock" mistake D10 rules out.

**Fields.** Add to the payload of:

| Event | `tradeDate` | `intendedSettlementDate` |
|---|---|---|
| `TradeExecuted` | yes | yes |
| `OrderPartiallyFilled`, `OrderFilled` | yes, of the execution named by `executionId` | yes |
| The synchronous `OrderResult.executions[]` and `GET /api/trades` (`Trade` schema) | yes | yes |

Both are ISO dates (`format: date`), never null on these events.

**Rules.**

- **One calendar, one answer.** For a given `executionId`, `tradeDate` and `intendedSettlementDate` are identical on
  every event and response that carries them: `TradeExecuted`, the fill events, `SettlementPending`, every later
  `Settlement*` event (until a `SettlementDelayed` moves the settlement date, which then applies to later events
  only), and the `ClearingTrade` line. A test asserts this across all of them.
- **The business-day rule does not depend on `clearing.enabled`.** A trade at or after the cutoff
  (`clearing.cutoff` in `clearing.time_zone`), or on a non-business day, has the next business day as its
  `tradeDate`. The same calendar decides `BusinessDayClosed` (SR-26), so a consumer that rolls its books on
  `BusinessDayClosed` and books a trade by its `tradeDate` always agrees with itself.
- **The settlement date** is `tradeDate` plus the instrument's market cycle (`settlement_days`,
  `market_settlement_days`) in business days, also in `settlement.mode: external`, where it is the date the
  exchange *expects*, not one it will simulate.
- **Additive.** New fields on existing payloads; existing consumers are unaffected. Bump the events'
  `schemaVersion` if the simulator's own versioning policy asks for it.
- **On the virtual clock** both dates come from the exchange's domain time, like `executedAt`.

**Accept when** (cutoff 17:30 Europe/Copenhagen, T+1):

- a trade at 17:29 local on Tuesday has `tradeDate` Tuesday and `intendedSettlementDate` Wednesday;
- a trade at 17:30 local on Tuesday has `tradeDate` Wednesday and `intendedSettlementDate` Thursday;
- a trade at 18:00 local on Friday has `tradeDate` Monday;
- for a sample of executions, `tradeDate` and `intendedSettlementDate` are equal on `TradeExecuted`, the fill
  events, `SettlementPending` and the clearing statement line.

### SR-30 — Cancel by client order id (SHOULD) [phase 3]

`DELETE /api/orders/{id}` takes the exchange's order id only. A member cancels by its own id on a real exchange: FIX
`OrderCancelRequest` names the order by `OrigClOrdID`. The trading demo cancels by `clientOrderId` for a reason that
only shows under load: the exchange's id reaches the order through the same report pipeline as everything else, and a
cancel requested a moment after placing the order would otherwise have to wait for it (it did, by redelivery, and was
dead-lettered when the wait outlasted the retries).

- A cancel addressed by `clientOrderId` within the participant, e.g. `DELETE /api/orders?clientOrderId=…`, with the
  same responses and the same `OrderCancelled` as a cancel by id.
- An unknown `clientOrderId` answers `404`, as an unknown id does.

Without it the demo's adapter resolves the id by resubmitting the order, which the simulator answers idempotently
(`200`, `duplicate: true`, with the existing order) - a workaround, and only while the order's terms are at hand.

**Accept when** an order submitted with `clientOrderId` `C` is cancelled by `C`, and a second cancel by `C` answers as
a second cancel by id does.

---

## Summary by demo phase

| Demo phase | Requirements |
|---|---|
| 0 Foundation | SR-1, SR-3, SR-5, SR-28 (MUST); SR-19 (SHOULD) |
| 1 Identity and account core | SR-24, SR-25, SR-26 (MUST) |
| 2 Funding | SR-21 (MUST); SR-22, SR-27 (SHOULD) |
| 3 Orders and execution | SR-2, SR-6–SR-10, SR-12, SR-13, SR-29 (MUST); SR-16, SR-17, SR-30 (SHOULD) |
| 4 Settlement | SR-14 (MUST) |
| 5 Reconciliation | SR-11 (SHOULD) |
| 6 Realistic load | SR-4, SR-15, SR-20, SR-23 (SHOULD) |
| FX | SR-18 (LATER) |
