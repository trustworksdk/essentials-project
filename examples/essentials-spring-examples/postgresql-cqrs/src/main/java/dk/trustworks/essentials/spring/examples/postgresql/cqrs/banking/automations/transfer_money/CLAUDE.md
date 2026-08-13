# Slice: banking.transfer_money

**Kind:** automation   **Status:** live   **Owner:** banking-team
**Purpose:** Carry one requested intra-bank transfer through withdrawal, deposit and completion.

## Invariants
- The withdrawal from the source account is rejected when it would overdraw the balance — the automation
  always withdraws with `AllowOverdrawingBalance.NO`, and the rule itself lives in `Account.withdraw`,
  which throws `InsufficientFundsException` (enforced by `Account`).
- The transfer lifecycle is a strict state machine — `REQUESTED` → `FROM_ACCOUNT_WITHDRAWN` →
  `TO_ACCOUNT_DEPOSITED` → `COMPLETED`; `markFromAccountAsWithdrawn` / `markToAccountAsDeposited` throw
  `IllegalStateException` from any other state (enforced by `IntraBankMoneyTransfer`).

## Boundaries
**Reacts to / reads:** `IntraBankMoneyTransferRequested`, `IntraBankMoneyTransferStatusChanged`,
`AccountWithdrawn`, `AccountDeposited` — the four states of one process, which is why they are four
`@MessageHandler` methods on one processor rather than four slices.
**Publishes:** nothing directly, and it **dispatches no commands**: each handler loads an aggregate
through `Accounts` / `IntraBankMoneyTransfers` and calls a method on it, so events reach the store as a
side effect of the aggregate. `dispatches` is absent from `slice.yaml` rather than filled with invented
command types. Each handler writes exactly **one** aggregate, never two in the same transaction — which
is why this is an automation rather than a command slice.
**Forbidden:**
  - Never import another slice's internals — only `banking/events/` and `banking/types/`.
  - An automation has no external API. Do not add a controller here.

## Data
**Owns (writes):** `Account` (withdraw/deposit) and `IntraBankMoneyTransfer` (lifecycle marks).
**Reads:** `Account`, `IntraBankMoneyTransfer`, correlated by `TransactionId`, which flows through both
event streams.

## Files
- `TransferMoneyProcessor.java` — the live slice: an `EventProcessor` with the four `@MessageHandler`s
- `TransferMoneyProcessorOld.java` — **not part of this slice's behaviour.** Deliberately unwired (its
  `@Service` is commented out, nothing references it), kept as the "before" half of a before/after pair
  showing how this process manager had to be written with hand-rolled subscriptions and an `Outbox`
  before `EventProcessor` existed. Do not copy it into new code.

## Tests
`banking/TransferMoneyProcessorIT` covers the whole lifecycle and injects `TransferMoneyProcessor`, so an unwired processor fails the test. No unit test — this module has none.
