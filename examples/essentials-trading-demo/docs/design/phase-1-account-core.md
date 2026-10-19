# Phase 1 design — identity and the account core

**Status:** decided (§10), being implemented.
**Plan:** [`../EXPANSION_PLAN.md`](../EXPANSION_PLAN.md) phase 1, decisions D2–D5, D7, D10, D20–D24.

Phase 1 builds the two things every later phase hangs off: a **user** who can log in, and the **trading account
ledger** — cash, positions, holds, buying power. Later phases do not change the ledger; they add the processes that
drive it (funding in phase 2, orders in 3, settlement in 4, reconciliation in 5). So the ledger is designed in full
now, and §8 proposes building all of it in this phase.

Decisions this note asks for are marked **(Q-n)** and collected in §10.

---

## 1. Where it lives

| Context | New or existing | Phase 1 contents |
|---|---|---|
| `identity` | new | `User`, login, roles |
| `accounts` | new | `TradingAccount` (the new ledger), its views |
| `brokerage` | existing, **frozen** | Today's `TradingAccount`, `Trade`, `Settlement`, untouched |

**The new account is built beside the old one, not in its place (Q-1).** Today's account is load-bearing for the
bootstrap seeding, the trading load generator, the dashboard, the closing-books benchmark and the `settle_trade`
automation (the `UnitOfWorkMode.NONE` example and the two queue-engine automation tests). Rewriting it in place would
take all of that down until phase 4 rebuilds the trade lifecycle. Side by side, the harness keeps measuring what it
measures today, the new ledger gets its own load scenario as it grows, and `brokerage` is deleted in phase 4 when
`orders` and `settlement` replace its last users. The two use different `AggregateType`s (`TradingAccounts` stays the
old one; the new one is `Accounts`), so their streams, snapshots and closing-books settings never meet.

---

## 2. Identity

### 2.1 What a user is

| Field | Notes |
|---|---|
| `UserId` | Server-generated |
| `email` | Login name; unique, case-insensitive |
| `displayName` | |
| `roles` | `CUSTOMER`, `OPERATOR` |
| `status` | `ACTIVE`, `LOCKED` |
| password hash | BCrypt, via Spring Security's `DelegatingPasswordEncoder` |

### 2.2 Write style (Q-2)

| Option | For | Against |
|---|---|---|
| **(a) Service-entity lane** — a state-stored `users` table, the slice law's §R5 third lane; integration events (`UserRegistered`, …) published on the `EventBus` | Identity is CRUD with a uniqueness constraint, the thing a table does well. No password hash in an immutable store. Shows a second write lane in the demo next to the aggregate lane | No event history for users |
| (b) Event-sourced `User` aggregate, credentials in a side table | One lane throughout. User history in the admin console | Email uniqueness needs a separate guard table anyway; two stores for one concept; secrets must be kept out of events by discipline |
| (c) External identity provider (Keycloak in compose, OIDC) | The realistic production answer | A second system to run for a demo whose point is elsewhere; heavier for load tests |

**Decided: (b).** `User` is an event-sourced aggregate on the aggregate lane, like every other aggregate in the
demo. Its events carry identity and lifecycle — `UserRegistered` (email, display name, roles), `UserRolesChanged`,
`UserLocked`, `UserUnlocked` — and **never** a credential.

Credentials live in a separate state-stored table, `user_credentials (user_id PK, email UNIQUE, password_hash,
updated_at)`, which Spring Security's `UserDetailsService` reads. The table does a second job: its unique email
column is the registration's uniqueness guard. `register_user` writes the credentials row and appends
`UserRegistered` in **one unit of work**, so a duplicate email fails the whole registration and no aggregate is left
without credentials, or credentials without an aggregate. A password change touches only the table; it is not a
domain event. The email is in both places on purpose — the table for login and uniqueness, the event for history —
and an email change (later) updates both in one unit of work.

(c) stays the later upgrade: other contexts hold only the `UserId`.

### 2.3 Security

- **Form login** for the browser (the customer UI in phase 7, the existing admin pages), **HTTP basic** for API calls.
  Session-based; no JWT yet.
- **Paths:** `/api/admin/**`, `/admin/**` and the Essentials admin API need `OPERATOR`. `/api/accounts/**` needs
  `CUSTOMER` and checks that the caller owns the account. Webhook endpoints (`/webhooks/**`) are not behind login;
  they are authenticated by their signature. Actuator health stays open.
- **The harness does not log in.** The load generator sends commands on the in-process command bus, as it does today,
  so a load test measures the ledger rather than BCrypt. One end-to-end scenario does go through HTTP with real
  logins, to keep that path honest.
- **Seeded users:** an `operator` and a few customers, created by the bootstrap with demo passwords from
  configuration, never in source.

---

## 3. The account

### 3.1 Identity and ownership

- `AccountId` is the logical id; the stream id is `AccountId#generation`, exactly as today's closing-books convention.
- An account has **one owner** (`UserId`) and **one currency** (USD now, D3). A user may hold several accounts
  (Q-3); phase 1 opens one per customer.
- `Money` is Essentials' own type throughout; every amount in an account is in the account's currency, and the
  aggregate rejects any other.

### 3.2 State

```
Cash
  settled         cash that is final
  pendingIn       sale proceeds bought by the exchange, not yet settled
  pendingOut      purchase costs owed to the exchange, not yet settled
  holds           Map<HoldId, Hold>   — cash set aside for an open buy order or a pending withdrawal
  uncleared       Map<DepositId, UnclearedDeposit> — completed deposits still inside their return window

Positions         Map<InstrumentId, Position>
  settled         shares held and settled
  pendingIn       bought, not yet settled
  pendingOut      sold, not yet settled
  reserved        Map<OrderId, Quantity> — set aside for an open sell order
  costBasis       total cost of the shares held (average-cost method, Q-5)

realizedPnl       this period's realized profit and loss
references        external references already booked, for idempotency (§5)
```

### 3.3 The figures customers see

All derived, never stored as truth:

| Figure | Formula | Meaning |
|---|---|---|
| **Cash balance** | `settled + pendingIn − pendingOut` | What the cash is worth once everything settles |
| **Buying power** | `settled + pendingIn − pendingOut − Σholds` | What a new buy order may commit (Q-4) |
| **Withdrawable** | `settled − pendingOut − Σholds − Σuncleared`, floored at 0 | What may leave the firm today |
| **Sellable quantity** | `settled + pendingIn − pendingOut − Σreserved` | Shares a new sell order may commit |
| **Market value** | `Σ (settled + pendingIn − pendingOut) × latest price` | From the market-data view, not the aggregate |

**Restricted.** When `settled` goes negative — a deposit returned after its money was spent — the account is
restricted: sells and deposits are accepted, buys and withdrawals are not, until the balance is positive again. A
derived state, not an event.

### 3.4 Invariants the aggregate enforces

1. A hold never exceeds buying power at the moment it is placed.
2. A share reservation never exceeds the sellable quantity at the moment it is placed.
3. A withdrawal hold never exceeds the withdrawable amount.
4. Every amount is in the account's currency.
5. Nothing is booked twice for the same external reference (§5).
6. A closed generation accepts nothing (unchanged from today).

Settlement, returns and corrections are **not** refused for lack of funds: they are facts from outside. They can
make the account restricted; they cannot be rejected.

---

## 4. Events

Grouped by the phase that first sends the command; all are designed now.

| Event | Fields (besides the stream and logical ids) | Effect | Driven by |
|---|---|---|---|
| `AccountOpened` | owner, currency, period, **opening balances** (§6) | Starts a generation | Customer (phase 1), closing books |
| `CashAdjusted` | adjustment id, amount (±), reason, operator | `settled ± amount` | Operator (phase 1; reconciliation in 5) |
| `DepositCredited` | deposit id, amount, business date, clears on | `settled +`, `uncleared +` | `PaymentCompleted` (2) |
| `DepositCleared` | deposit id | `uncleared −` | Business day passing its return window (2) |
| `DepositReturned` | deposit id, amount | `settled −`, `uncleared −` if still there | `PaymentReturned` (2) |
| `FundsHeld` | hold id, purpose (`ORDER`/`WITHDRAWAL`), amount | `holds +` | Order placed (3), withdrawal requested (2) |
| `FundsReleased` | hold id, amount | `holds −` | Order cancelled/rejected/done (3), withdrawal failed (2) |
| `CashWithdrawn` | withdrawal id, hold id, amount | `holds −`, `settled −` | `PaymentCompleted` for a withdrawal (2) |
| `SharesReserved` | order id, instrument, quantity | `reserved +` | Sell order placed (3) |
| `SharesReleased` | order id, instrument, quantity | `reserved −` | Sell order cancelled/rejected/done (3) |
| `TradeBooked` | execution id, order id, instrument, side, quantity, price, gross, trade date, settlement date, hold released, realized P&L | Buy: `pendingOut +`, `pos.pendingIn +`, `holds −`. Sell: `pendingIn +`, `pos.pendingOut +`, `reserved −`, `realizedPnl ±` | Execution from the exchange (3) |
| `TradeSettled` | execution id | Buy: `pendingOut −`, `settled −`, `pos.pendingIn −`, `pos.settled +`. Sell: the mirror | `SettlementCompleted` (4) |
| `TradeBookingReversed` | execution id, reason | Undoes a `TradeBooked` that was never settled | `SettlementCancelled`, correction (4) |
| `AccountBooksClosed` | next period | Closes the generation | Closing books (unchanged) |
| `UnknownReferenceIgnored` | kind of closing command, reference | None — a close named a reference this generation never opened | Any closing command that overtook its open (added after the phase 1 load test: out-of-order delivery lost a cycle silently) |

Notes:

- **Events carry their own effect.** `TradeBooked` states the hold it released and the P&L it realized, rather than
  leaving a projection to recompute them from the order's limit price and the cost basis. Projections then never
  need the aggregate's private state.
- **`TradeSettlementFailed` is not an account event.** A failed settlement leaves the trade pending, which it already
  is; the failure lives on the settlement in phase 4, and an operator resolves it.
- **No `CashDeposited`.** Today's event credits cash with no source. Money arrives as a deposit (with an id, a
  business date and a return window) or as an operator adjustment (with a reason); nothing else.

### Worked example

Account opens empty. Deposit of 10 000 completes, return window 3 business days.

| Step | Event | settled | pendingIn | pendingOut | holds | uncleared | Buying power | Withdrawable |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | `DepositCredited` 10 000 | 10 000 | | | | 10 000 | 10 000 | 0 |
| 2 | `FundsHeld` buy 10 × limit 101 = 1 010 | 10 000 | | | 1 010 | 10 000 | 8 990 | 0 |
| 3 | `TradeBooked` buy 10 @ 100.50, gross 1 005, hold released 1 010 | 10 000 | | 1 005 | 0 | 10 000 | 8 995 | 0 |
| 4 | `TradeSettled` | 8 995 | | 0 | 0 | 10 000 | 8 995 | 0 |
| 5 | `DepositCleared` | 8 995 | | | | 0 | 8 995 | 8 995 |

Had the deposit come back (`DepositReturned`) after step 4, `settled` would be −1 005: restricted, and the
position can only be sold.

---

## 5. Idempotency across generations

Every booking carries an external reference: an execution id, a deposit id, a hold id. The aggregate keeps the
references it has booked and ignores a repeat, as today's account does with `settledTrades`.

**The catch is closing books.** An execution booked at 16:29 in one generation can be redelivered after the books
rolled at 16:30, into a generation that has never heard of it, and be booked twice. The intake's `eventId` dedupe
(phase 0) stops redelivered *webhooks*, but not an internal redelivery of an inbox message whose first attempt
committed the booking and then failed to acknowledge.

**Proposal:** the opening balances carry forward the references of every *open* item — unsettled trades, open holds,
uncleared deposits — because those are the ones a later message can legitimately name. Closed items are not carried,
and the commands that close an item treat an unknown reference as already done:

- `TradeSettled`, `FundsReleased`, `DepositCleared` for a reference the generation does not hold open → ignored with
  a warning. It was closed earlier (a redelivery) or never existed (a defect the warning surfaces).
- `TradeBooked`, `FundsHeld`, `DepositCredited` for a reference already open or closed *in this generation* →
  ignored. A redelivery of an opening command that crosses a roll **is** the residual risk: the booking happened in
  the old generation, was carried forward as open (so it is still caught), or was closed before the roll (so it is
  not). The last case needs a message to be redelivered after the item it opened has both settled and rolled — a
  day or more on the real clock. The intake's `eventId` dedupe catches it when the redelivery is a webhook; an inbox
  redelivery that late would have to outlive the queue's redelivery policy.

The cost is bounded by the number of open items, not by history.

**Added after the phase 1 load test:** a closing command for a reference this generation never opened is no longer
silent. It emits `UnknownReferenceIgnored` (once per kind and reference) and still changes nothing. A queue defect
delivered a cycle's release and settle before its hold and booking, and the ledger's silent no-ops hid it: the hold
stayed open and the trade unsettled with no trace. A close of an item opened and closed in this generation remains an
ordinary, silent duplicate.

---

## 6. Closing books

- **Opening balances are the whole open state:** settled cash, pending in/out with their trades, holds, uncleared
  deposits, positions with their cost basis and reservations. A new generation must be able to settle a trade booked
  in the previous one. Today's account carries only cash and realized P&L, which is enough only because it has nothing
  pending across a roll.
- **Period:** `END_OF_DAY` on the exchange's business day (D20, D22) once EF-1 exists. Until then the new account
  uses `END_OF_DAY` on the wall clock in `America/New_York`, and switches its `periodSource` when EF-1 lands —
  period ids are `yyyy-MM-dd` either way, so nothing migrates.
- **Snapshots:** every 100 events, as today. With a fill per event and a roll per business day, an active account
  stays well inside that.
- **Realized P&L resets per period**; lifetime P&L is a view.

---

## 7. Views

| View | Grain | Serves |
|---|---|---|
| `account_overview` | one row per account | The §3.3 figures, owner, status, restricted flag. Customer and operator |
| `positions` | account × instrument | Quantities, cost basis, average cost; market value joined from the market-data view |
| `account_activity` | one row per event | The statement: what happened, when (business date), the running cash balance |
| `users` | one row per user | Operator listing; owned by `identity` |

All eventually consistent, like today's projections. The order ticket in phase 3 checks buying power **on the
aggregate**, inside the command, never on `account_overview`; the view is for display.

---

## 8. Slices in phase 1

**Proposal (Q-6): the whole ledger now.** Every command in §4 gets its slice and aggregate tests in phase 1, with
operator endpoints so they can be exercised before the processes that will drive them exist. Phases 2–4 then add
translation and automation slices that send these commands, and do not touch the account.

| Context | Slice | Kind | Endpoint |
|---|---|---|---|
| identity | `register_user` | command | `POST /api/admin/users` (operator); self-registration later |
| identity | `lock_user` | command | `POST /api/admin/users/{userId}/lock` |
| identity | `users` | view | `GET /api/admin/users` |
| accounts | `open_account` | command | `POST /api/accounts` (customer, for self) |
| accounts | `adjust_cash` | command | `POST /api/admin/accounts/{accountId}/cash-adjustments` |
| accounts | `credit_deposit`, `clear_deposit`, `return_deposit` | command | operator endpoints now; translation-driven in phase 2 |
| accounts | `hold_funds`, `release_funds`, `withdraw_cash` | command | operator now; phase 2/3 |
| accounts | `reserve_shares`, `release_shares` | command | operator now; phase 3 |
| accounts | `book_trade`, `settle_trade`, `reverse_trade_booking` | command | operator now; phase 3/4 |
| accounts | `close_books`, `close_books_and_open_next_period` | command | as today |
| accounts | `account_overview`, `positions`, `account_activity` | view | `GET /api/accounts/{accountId}/…` (owner or operator) |

**Harness:** a new seeding step opens a few customers' accounts beside the existing seed, and a new load scenario
drives the ledger commands per account through the shard-owned ordered queue keyed by `accountId` (the D1 scaling
model), so phase 1 ends with a measurement as the plan requires.

---

## 9. Concurrency

- **From HTTP** (open an account, an operator adjustment): synchronous on the command bus. A rare optimistic-concurrency
  conflict is retried once, then reported.
- **From processes** (fills, settlements, payments — phases 2–4): through an `Inbox` keyed by `accountId` on the
  shard-owned engine, so one account is processed serially, cluster-wide. That is what keeps a busy account's
  stream free of conflicts under load.
- **Order placement** (phase 3) sits between the two: the customer waits for the answer, and the hold must not race a
  fill. Deferred to the phase 3 design.

---

## 10. Decisions

| | Question | Decided |
|---|---|---|
| **Q-1** | Build the new account beside `brokerage`, which is frozen and deleted in phase 4? | Yes |
| **Q-2** | Identity's write style | Event-sourced `User` aggregate; credentials in a separate `user_credentials` table (§2.2) |
| **Q-3** | Several accounts per user from the start? | The model allows it; phase 1 opens one per customer |
| **Q-4** | May unsettled sale proceeds be used to buy? (They may never be withdrawn before settlement.) | Yes, as most cash accounts allow; "good-faith violations" are not modelled |
| **Q-5** | Cost basis: average cost, or FIFO tax lots? | Average cost; lots are a later refinement |
| **Q-6** | Build the whole ledger in phase 1, with operator endpoints, or only what phase 1 itself triggers? | The whole ledger |
| **Q-7** | Deposit return window, in business days | 3 by default, configurable (`trading-demo.accounts.deposit-return-window-business-days`). The window is applied when a deposit is credited and its `clearsOn` date is in `DepositCredited`, so a change affects new deposits only and replay is unaffected |
