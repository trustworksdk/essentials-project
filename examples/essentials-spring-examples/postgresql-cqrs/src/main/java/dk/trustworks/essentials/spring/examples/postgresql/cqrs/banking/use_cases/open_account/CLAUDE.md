# Slice: banking.open_account

**Kind:** command   **Status:** live   **Owner:** banking-team
**Purpose:** Open a new bank account with a zero balance.

## Invariants
- Opening an account that already exists is an idempotent no-op rather than a second `AccountOpened`
  (enforced by `OpenAccountHandler`, which only constructs the aggregate when
  `Accounts.isAccountMissing(...)` is true). The command bus delivers at least once, so this matters.
- An account always starts at a zero balance (enforced by `Account`, whose `on(AccountOpened)` sets it).

## Boundaries
**Reacts to / reads:** the `OpenAccount` command, plus the `Account` stream for the existence check.
**Publishes:** `AccountOpened`.
**Forbidden:**
  - Never import another slice's internals — only `banking/events/` and `banking/types/`.
  - Never add this slice's logic to a shared decider/controller/event file.

This slice used to be a hole: `Accounts.openNewAccount(accountId, accountNumber)` constructed the
aggregate itself, so the decision lived on the repository and was reachable only from tests. The
repository now takes an already-constructed `Account` — matching `ShippingOrders.registerNewOrder` and
`IntraBankMoneyTransfers.requestNewTransfer` — and constructing it is this slice's job.

## Data
**Owns (writes):** `Account` (created here, persisted via `Accounts.openNewAccount`).
**Reads:** `Account` (existence only).

Note this slice is where an `Account` is *created*; deposits and withdrawals are driven by
`automations/transfer_money`, and the balance is read from `views/account_balance`.

## Files
- `OpenAccount.java` — the command; it *is* the request body, no DTO
- `OpenAccountHandler.java` — the slice's one `@CmdHandler`
- `OpenAccountAPI.java` — `POST /banking/open-account`, the slice's only endpoint

## Tests
`OpenAccountIT` covers both invariants: the zero opening balance, and that re-sending the same command
adds no second event.
