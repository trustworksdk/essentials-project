# Slice: brokerage.account_statement

**Kind:** view   **Status:** live   **Owner:** brokerage-team
**Purpose:** Serve the statement state of each logical trading account from a projected read model, and one
account's overview together with the closing-books generations behind it.

## Invariants
- One row per **logical** account, not per generation stream (enforced by `AccountStatementProjection`): the
  account's events arrive under a succession of `TradingAccountGenerationId`s, and the row is keyed on
  `logicalAccountId`. A rollover restates the row; it does not add one.
- The generation number comes from `TradingAccountGenerationId.generation()`, never from a local parse. This
  projection used to carry its own `lastIndexOf('#')` copy, which could drift from the concatenation that
  produced the id. The `#` convention lives on the id type and only there.
- A replay must go through `onSubscriptionsReset`, which truncates. The opening event upserts, but every
  other handler is a `+= delta` — re-applying one on a live row doubles it. There is no `OrderedMessage`
  version check to fall back on.
- Balances are read from the projection row, never from a rehydrated `TradingAccount`
  (enforced by `AccountStatementQuery`).

## Two queries, one slice

`GET /api/admin/projections/account-statements` and `GET /api/admin/trading-accounts/{accountId}` both
interrogate `projection_trading_account_statement`, which is what §R2 scopes a view slice by.

The overview additionally carries the account's **closing-books generations**, read live from
`AggregateLifecycleApi`. That is framework lifecycle metadata — event-store bookkeeping that nothing in this
application projects and no slice owns — not a second read model. So this is one slice with two queries
rather than two slices sharing a model. If the generations ever became something we projected ourselves,
they would become a model, and then they would deserve their own slice.

## Boundaries
**Reacts to / reads:** `TradingAccountOpened`, `CashDeposited`, `FundsReserved`, `FundsReleased`,
`TradeSettlementApplied`, `AccountBooksClosed` from the `TradingAccounts` aggregate type; plus
`AggregateLifecycleApi` for the generations.
**Publishes:** nothing — a view slice never produces events.
**Forbidden:**
  - Never import another slice's internals — only `brokerage/events/`, `brokerage/types/` and the
    `AggregateType` constant off `TradingAccounts`.
  - Never load the `TradingAccount` aggregate to answer a query. Projecting the statement is the point.

## Consistency

Reads are **eventually consistent**: `AccountStatementProjection` is an asynchronous `ViewEventProcessor`.
The overview mixes two clocks — an eventually-consistent statement row against always-current generation
metadata — so an account that has just rolled its books can briefly show the new generation against the old
period. The pre-slice version read the write aggregate and so was strongly consistent; that is the behaviour
change this slice deliberately makes.

A 404 from the overview endpoint means "not projected yet", not "no such account": an unknown account fails
inside the query with `Couldn't resolve current generation for trading account …`, which is the message it
has always failed with.

## Data
**Owns (writes):** `projection_trading_account_statement`, created by the projection's constructor and
truncated on subscription reset. The table name is hardcoded in both files — never concatenate it.
**Reads:** its own read model, plus framework lifecycle metadata. The read model *is* the response body.

## Processor name is load-bearing

`getProcessorName()` returns `"TradingAccountStatementProjection"` — the pre-slice class name, not the new
one. It is the key subscription progress is stored under; renaming it silently replays from the beginning of
time.

## Files
- `AccountStatement.java` — one row of the read model / the list response body
- `AccountOverview.java` — one account's row plus its generations
- `AccountGeneration.java` — one generation, nested in the overview
- `AccountStatementProjection.java` — the `ViewEventProcessor`; owns the DDL and the truncate
- `AccountStatementQuery.java` — the `JdbcTemplate` queries and the lifecycle lookup
- `AccountStatementAPI.java` — the slice's two endpoints
