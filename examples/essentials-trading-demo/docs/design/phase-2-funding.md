# Phase 2 design — funding

**Status:** decided (§9, 2026-10-22). Decisions are marked **(Q-n)** in the text.
**Plan:** [`../EXPANSION_PLAN.md`](../EXPANSION_PLAN.md) phase 2; decisions D3, D4, D10, D11, D23, D24; simulator
requirements SR-21, SR-22, SR-26, SR-27.
**Builds on:** the account ledger of phase 1 ([`phase-1-account-core.md`](phase-1-account-core.md)), whose funding
commands - `credit_deposit`, `clear_deposit`, `return_deposit`, `hold_funds` (purpose `WITHDRAWAL`), `release_hold`,
`withdraw_cash` - exist with operator endpoints and are not driven by anything yet. This phase drives them from the
payment gateway, as phase 3 drove the order commands from the exchange.

Phase 2 lets a customer move money in and out: deposit from a bank account, see the deposit credited and later cleared,
withdraw what is withdrawable, and see a returned payment reverse what it did. A first reconciliation compares the
bank's end-of-day statement with the payments the brokerage knows of.

---

## 1. Contexts

| Context | What it gains |
|---|---|
| `funding` (new) | `Deposit` and `Withdrawal` aggregates: the customer's request, its life at the gateway, and the ledger command each outcome sends. Outbound gateway adapter and inbound payment events, in `funding/external_systems/payment_gateway/`. |
| `accounts` | Nothing new in the ledger except a withdrawal return (§5). A clearing automation driven by the business calendar (§6). |
| `integration/` | The payment-gateway's generated client, next to the exchange's. A business calendar fed by the exchange's `BusinessDayClosed` (§6), shared - it is reference data, not a context's state. |
| `reconciliation` (new, small) | The bank statement's entries against `funding`'s payments (§7). The operator case workflow stays in phase 5. |

Every payment is the firm's (D8 applies to the bank too): the gateway's `accountId` is our `AccountId`, never
interpreted by the gateway, and its `idempotencyKey` is our `DepositId` / `WithdrawalId`.

---

## 2. A deposit

```
customer ── POST /api/accounts/{accountId}/deposits ──▶ funding.Deposit  DepositRequested
                                                            │
   funding automation (UnitOfWorkMode.NONE) ── POST /api/payments (DEPOSIT, idempotencyKey = depositId)
                                                            │  201 / 200 duplicate
                                                        DepositInitiated(paymentId)
   gateway events, by webhook and event-log catch-up:
     PaymentCompleted ─▶ DepositCompleted(valueDate) ─▶ accounts CreditDeposit(businessDate = valueDate)
     PaymentFailed    ─▶ DepositFailed(reason)          (nothing was credited, nothing to undo)
     PaymentReturned  ─▶ DepositReturned(reason)     ─▶ accounts ReturnDeposit
```

- **The customer names the bank account** the money comes from (`iban`, `holderName`) - see **Q-2** for whether it is
  linked to the trading account first.
- **Requested is not credited.** Nothing reaches the ledger until the gateway says the money arrived. Buying power
  moves at `PaymentCompleted`, withdrawable cash only once the deposit clears (§6, phase 1 §3.3).
- **Unreachable gateway**: the initiating automation retries within its delivery, as `ExchangeOrderRouter` does, then
  records `DepositFailed(GATEWAY_UNAVAILABLE)`. The `idempotencyKey` makes a resubmission safe; a payment the gateway
  did take despite the failure still reports `PaymentCompleted`, which completes the deposit (a fact, as Q-3 of
  phase 3).
- **The business date of a credit comes from the gateway** - see **Q-1**. The ledger needs it: `DepositCredited`
  carries the deposit's `clearsOn`, return window business days after it.

## 3. A withdrawal

```
customer ── POST /api/accounts/{accountId}/withdrawals ──▶ funding.Withdrawal  WithdrawalRequested
                                                          + accounts HoldFunds(WITHDRAWAL), one unit of work
   funding automation ── POST /api/payments (WITHDRAWAL, idempotencyKey = withdrawalId) ─▶ WithdrawalInitiated
     PaymentCompleted ─▶ WithdrawalCompleted ─▶ accounts WithdrawCash   (consumes the hold, debits settled)
     PaymentFailed    ─▶ WithdrawalFailed    ─▶ accounts ReleaseHold
     PaymentReturned  ─▶ WithdrawalReturned  ─▶ accounts CreditWithdrawalReturn   (new, §5)
```

- **The hold is placed in the request's unit of work**, as an order's is (phase 3 Q-1): the ledger refuses a hold
  beyond withdrawable cash or on a restricted account, and that refusal is the customer's `422` - no withdrawal exists
  without its hold. The gateway does no balance check of its own ("whether a client may withdraw is the caller's
  decision"), so this is the only one.
- **`HoldId` = `WithdrawalId`**, as `HoldId` = `OrderId` for orders.

## 4. The gateway's events

A translation slice per consuming context, on `integration/` exactly as the exchange's (signed webhook, dedupe per
event, event-log catch-up): subscription `demo-funding` in `simulators/gateway.yaml` for `PaymentPending`,
`PaymentCompleted`, `PaymentFailed`, `PaymentReturned`; `demo-reconciliation` for `BankStatementIssued`. Each payment
event names the payment by `idempotencyKey`, which is our id, so no lookup is needed. Out-of-order delivery (D11) is
tolerated the same way: every transition is a fact the aggregate applies idempotently; a `PaymentCompleted` before
`DepositInitiated` implies the initiation.

## 5. The ledger gap: a returned withdrawal

`PaymentReturned` on a withdrawal means the bank sent the money back (`WITHDRAWAL_RETURN` on the statement): the
customer's settled cash must be credited again. Phase 1 has no command for it. Proposed: `CreditWithdrawalReturn`
(withdrawal id, amount) - a fact, never refused, idempotent on the withdrawal id, `settled +`. An `AdjustCash` would
book the right number but lose the reference, and the ledger's idempotency is per external reference (phase 1 §5).

## 6. Clearing deposits: the business calendar

A deposit becomes withdrawable when its return window has passed (`ClearDeposit`, phase 1 Q-7). That is a business-day
question, and the only source of business days is the exchange's `BusinessDayClosed` (D10, SR-26).

- **A business calendar in `integration/`**: a table of closed business days, fed by the exchange's `BusinessDayClosed`
  through its own webhook subscription and catch-up reader. It is the "table fed by `BusinessDayClosed`" D24 already
  planned for EF-1, built now because phase 2 needs it first; EF-1 later reads the same store.
- **`accounts/automations/clear_deposits`**: on each closed business day, every uncleared deposit whose `clearsOn` is on
  or before it is cleared. The uncleared deposits come from an `accounts` view (`clearsOn` per deposit).
- On a cold start the catch-up reader replays the exchange's log, so the calendar is rebuilt and overdue deposits clear
  (D23's rule: the current business date is never asked for, it is replayed).

## 7. A first reconciliation

`BankStatementIssued` (one per currency per business day, from the gateway's `bank` source) lists every booked
movement with its `endToEndId` (our id), kind and amount. `reconciliation` matches each entry against `funding`'s
payments and records, per statement, the entries that match, the entries with no payment or a different amount, and
the completed payments missing from the statement. An entry is ours only if its `paymentId` is the one the gateway
took our payment under, too: the gateway's phantom entries copy a real entry's reference and amount. A view and an operator page list them. Nothing is corrected
automatically and there is no case workflow yet (phase 5); the gateway's seeded breaks (`statement.breaks.*`,
`/test/statements/breaks`) are what the tests check the matching against, one for one.

## 8. Slices

| Context | Slice | Kind | Driven by |
|---|---|---|---|
| funding | `request_deposit`, `request_withdrawal` | command | customer |
| funding | `record_payment_outcome` (initiated / completed / failed / returned, per payment kind) | command | the gateway, through `external_systems/payment_gateway` |
| funding | `external_systems/payment_gateway` | external system | `DepositRequested`, `WithdrawalRequested`; the gateway's payment events |
| funding | `apply_payment_to_account` | automation | completed / failed / returned → the ledger's command |
| funding | `deposits`, `withdrawals` | view | customer (own accounts) and operator |
| accounts | `credit_withdrawal_return` | command | `funding` |
| accounts | `clear_deposits` | automation | the business calendar |
| accounts | `uncleared_deposits` | view | `clear_deposits` |
| reconciliation | `external_systems/bank`, `bank_statement_breaks` | external system, view | `BankStatementIssued`; operator |
| `_demo_harness` | funding load scenario | — | deposits, withdrawals, returns at volume (SR-22 forced outcomes) |

Tests: unit tests per aggregate; ITs against the real `payment-gateway:dev` image with forced outcomes (SR-22,
`/test/payments/{id}/outcome`) for completion, failure and return, and its fault injection on the webhook; a
reconciliation IT against seeded statement breaks.

---

## 9. Decisions

All five proposals accepted (2026-10-22).

| | Question | Decided |
|---|---|---|
| **Q-1** | The business date of a completed or returned payment: the gateway's events carry timestamps only | A new simulator requirement, **SR-33**: `valueDate` on `PaymentCompleted` and `PaymentReturned`. Never derived from `completedAt` (D10) |
| **Q-2** | Bank accounts: named on every deposit and withdrawal, or linked to the trading account first? | Linked first: one bank account per trading account (`link_bank_account`, `funding`); deposits come from it, withdrawals go only to it |
| **Q-3** | A returned withdrawal in the ledger | A new fact `CreditWithdrawalReturn` (§5) |
| **Q-4** | Where the business calendar lives | `integration/BusinessCalendar`, fed by the exchange's `BusinessDayClosed`; reused by EF-1 (§6). Built (`e9b7db43`) |
| **Q-5** | Reconciliation in phase 2 | Matching and a breaks view only; corrections and the case workflow stay in phase 5 (§7) |

The simulator changes found while checking every remaining phase against 0.3.0 are bundled as SR-33 to SR-38 in
`SIMULATOR_REQUIREMENTS.md` § 9.
