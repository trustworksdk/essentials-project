# Slice: banking.request_intra_bank_money_transfer

**Kind:** command   **Status:** live   **Owner:** banking-team
**Purpose:** Accept a request to move money between two accounts in the same bank and start the transfer.

## Invariants
- A transfer is rejected unless both the from-account and the to-account exist — `TransactionException`
  (enforced by `RequestIntraBankMoneyTransferHandler`).
- Re-sending the same `TransactionId` is an idempotent no-op rather than a second transfer
  (enforced by `RequestIntraBankMoneyTransferHandler`, which only creates the aggregate when
  `IntraBankMoneyTransfers.findTransfer(...)` comes back empty).

The money does **not** move here. This slice only records the intent; the withdrawal, the deposit and the
lifecycle transitions belong to `automations/transfer_money`.

## Boundaries
**Reacts to / reads:** the `RequestIntraBankMoneyTransfer` command, plus the `Account` streams (existence
check only) and the `IntraBankMoneyTransfer` stream (idempotency check).
**Publishes:** `IntraBankMoneyTransferRequested`.
**Forbidden:**
  - Never import another slice's internals — only `banking/events/` and `banking/types/`.
  - Never add this slice's logic to a shared decider/controller/event file.

The existence check reads a *different* aggregate type than the one this slice writes — a
consistency-boundary crossing, racy by construction. It is benign only because an account can never be
closed, so existence is monotone; see the class javadoc and the BC `CLAUDE.md`.

## Data
**Owns (writes):** `IntraBankMoneyTransfer` (created via `IntraBankMoneyTransfers.requestNewTransfer`).
**Reads:** `Account` (existence), `IntraBankMoneyTransfer` (existence).

## Files
- `RequestIntraBankMoneyTransfer.java` — the command; it *is* the request body, no DTO
- `RequestIntraBankMoneyTransferHandler.java` — the slice's one `@CmdHandler`; enforces both invariants
- `RequestIntraBankMoneyTransferAPI.java` — `POST /banking/transfer-money`, the slice's only endpoint

## Tests
`banking/TransferMoneyProcessorIT` drives this slice end to end: it sends the command over the
`CommandBus` and injects `RequestIntraBankMoneyTransferHandler` so an unwired handler fails the test.
There is no unit test — this module has none.
