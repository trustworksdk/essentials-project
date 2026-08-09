# Slice: banking.account_balance

**Kind:** view   **Status:** live   **Owner:** banking-team
**Purpose:** Serve the running balance per account from a read model projected off the `Account` events.

## Invariants
- Projecting an already-applied event is a no-op: every handler takes `OrderedMessage` and compares
  `message.getOrder()` against the row's stored version before writing, so at-least-once delivery and
  replay cannot double-count a balance (enforced by `AccountBalanceProjection`).

## Boundaries
**Reacts to / reads:** `AccountOpened`, `AccountDeposited`, `AccountWithdrawn` from the `Accounts`
aggregate type.
**Publishes:** nothing — a view slice never produces events.
**Forbidden:**
  - Never import another slice's internals — only `banking/events/` and `banking/types/`.
  - Never read the balance off the `Account` write aggregate. Projecting it is the point of this slice.

Two query methods, one slice: both interrogate the read model this slice owns (§R2). A query over a
different shape — a transfer history, say — would be a different slice, not a third method here.

Reads are **eventually consistent**: `AccountBalanceProjection` is an asynchronous `ViewEventProcessor`,
so a balance fetched immediately after a transfer may still be the pre-transfer figure.

## Data
**Owns (writes):** `AccountBalanceView`, a `@DocumentEntity` in the `banking_account_balance` table,
reached through a `DocumentDbRepository<AccountBalanceView, String>`.
**Reads:** its own read model only. The read model *is* the response body — no DTO, no mapper.

## Files
- `AccountBalanceView.java` — the read model / response body
- `AccountBalanceProjection.java` — the `ViewEventProcessor`; also wipes the model on subscription reset
- `AccountBalanceRepositoryConfiguration.java` — the repository `@Bean` and its index, for this slice only
- `AccountBalanceAPI.java` — `GET /banking/accounts` and `GET /banking/accounts/{accountId}`

## Tests
`views/account_balance/AccountBalanceViewIT` covers catch-up, the folded balance, and the idempotency
invariant. There is no unit test — this module has none. Note that with no `open_account` command slice
(see the BC `CLAUDE.md`), the test has to open accounts through `Accounts.openNewAccount` directly.
