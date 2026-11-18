# Bounded context: funding

Deposits and withdrawals through the payment gateway, booked on the `accounts` ledger. Design:
`docs/design/phase-2-funding.md` (decisions §9). Two `PaymentGateway`s: `StubPaymentGateway` (completes every payment
at once; no simulator configured) and `SimulatorPaymentGateway` (the payment-gateway simulator, `simulators` profile,
`trading-demo.simulators.payment-gateway.base-url` set).

**Write style: aggregate (§R5)** — `BankAccountLink` (stream `BankAccountLinks`, id = `AccountId`), `Deposit`
(`Deposits`, id = `DepositId`), `Withdrawal` (`Withdrawals`, id = `WithdrawalId`), each behind a plain
`StatefulAggregateRepository` wrapper. No snapshots, no closing books.

## Rules that are easy to break

- **A payment needs a linked bank account** (Q-2) and takes the one linked when it is requested; relinking does not
  move a payment already requested. No link → `422`.
- **A withdrawal exists only together with its hold.** `request_withdrawal` saves the withdrawal and sends the
  ledger's `HoldFunds(WITHDRAWAL)` in the same unit of work, as `place_order` does: a `LedgerRuleViolation` (more than
  withdrawable, restricted account) rolls it back, `422`. The gateway checks no balance. `HoldId` = `WithdrawalId`.
- **Our id is the gateway's `idempotencyKey`** (`DepositId` / `WithdrawalId`), and its events name the payment by it.
  The gateway's `accountId` is our `AccountId`, never interpreted.
- **Everything the gateway reports is a fact**, recorded idempotently in any order. Two exceptions: a return before its
  completion throws `PaymentNotYetRecordable` (not an `IllegalArgumentException`, so the inbox redelivers instead of
  dead-lettering) until the completion is in; and a completion after a *failure* is recorded - the gateway's own
  failures are final, so it can only follow our `GATEWAY_UNAVAILABLE`, given up on a payment the gateway took anyway.
- **The value date comes from the gateway (SR-33, simulators 0.4.0)**, never from `completedAt` (D10). A
  `PaymentCompleted` or `PaymentReturned` without it is incomplete (`422`, the gateway retries). `PaymentGatewayIT`'s
  completion case still checks the gateway's spec first and skips against an older image.
- **Every gateway report goes through `Funding:PaymentReports`**, the initiation's answer and the gateway's events
  (envelopes, translated as consumed by `PaymentEventTranslation`) alike - as `orders`' `ExchangeReports`.
- **`PaymentRouter`'s handlers are `UnitOfWorkMode.NONE`**; the retry budget (`trading-demo.funding.payment-submission.*`)
  runs inside one delivery, then `GATEWAY_UNAVAILABLE`.
- **A forced outcome (SR-22) still waits for the payment's delay** on the gateway's clock (60 s at least). Tests run
  the gateway on a frozen virtual clock and advance it (`SimulatorImages.paymentGatewayAsTheDemoRunsIt`).
- **Ownership is checked against `funding`'s own projection of `AccountOpened`** (`views/account_owners`), eventually
  consistent, as in `orders`.

## Slices

| Slice | Kind | Driven by |
|---|---|---|
| `link_bank_account` | command | customer (`PUT /api/accounts/{accountId}/bank-account`) |
| `request_deposit`, `request_withdrawal` | command | customer (`POST /api/accounts/{accountId}/deposits`, `/withdrawals`) |
| `record_payment_outcome` | command | the gateway, through `external_systems/payment_gateway` |
| `external_systems/payment_gateway` | external system | `DepositRequested`, `WithdrawalRequested`; the gateway's payment events (`POST /webhooks/payment-gateway/funding`, event log) |
| `apply_payment_to_account` | automation | completed / failed / returned → `CreditDeposit`, `ReturnDeposit`, `WithdrawCash`, `ReleaseHold`, `CreditWithdrawalReturn` |
| `payments` | view | customer (`GET /api/accounts/{accountId}/payments[/{reference}]`) |
| `account_owners` | view | the endpoints' ownership check |

Tests: `FundingAggregatesTest`, `PaymentEventTranslationTest`; `FundingIT` (stub gateway); `PaymentGatewayIT` (real
`payment-gateway:dev`, skips without it).

## Not built yet

The bank-statement reconciliation (design §7), the funding load scenario.
