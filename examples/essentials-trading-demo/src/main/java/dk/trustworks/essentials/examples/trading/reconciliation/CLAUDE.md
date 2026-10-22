# Bounded context: reconciliation

The brokerage's bookings checked against the outside world's statements, and the breaks that persist worked as
operator cases. Phase 2 built the bank's statements against `funding`'s payments (`docs/design/phase-2-funding.md` §7);
phase 5 adds the clearinghouse's statements against `orders`' executions, missing statements, and the
`ReconciliationCase` workflow (`docs/design/phase-5-reconciliation.md`).

## Rules that are easy to break

- **Statements are stored, not event-sourced** (`bank_statement*`, `clearing_statement`/`_trade`/`_obligation`):
  reference data from outside, stored as issued, once per `statementId`. Receivers are simulators-profile only; their
  tables (`*StatementTables`) always exist, because the breaks, missing-statement and case reads use them regardless.
- **Breaks are worked out when read** (`*StatementBreaksQuery`), never at intake, or a statement that beats our own
  projection keeps a false break forever.
- **`known_payments` / `known_executions` are reconciliation's own projections** of `funding`'s and `orders`' events
  (cross-BC `events/` imports); nothing here calls into those contexts.
- **The event log is not participant-filtered** - the webhook subscription is. `ClearingStatements` drops another
  participant's statement (the liquidity provider's `CS:<date>:LP`) itself.
- **Bank matching is by `endToEndId` and `paymentId`** and entry kind: the gateway's phantom entry copies a real
  entry's `endToEndId` and amount under a payment that does not exist. Amounts stored unsigned.
- **Clearing matching is by `executionId`**; a statement's trades belong to its business date as an execution belongs
  to its `tradeDate`. A phantom line has a fresh `executionId`. A changed price shows as a changed amount.
- **Obligations are checked against the statement's own lines, not our executions.** The clearinghouse nets them from
  the (altered) lines it reports, so comparing with ours would report every line break twice. As built, differs from
  the design's §2 table.
- **The seeded breaks map one for one**: bank `PHANTOM_ENTRY`/`AMOUNT_CHANGED`/`OMITTED_ENTRY` →
  `UNKNOWN_ENTRY`/`AMOUNT_MISMATCH`/`MISSING_ENTRY`; clearing `PHANTOM_TRADE`/`OMITTED_TRADE`/`QUANTITY_CHANGED`/
  `PRICE_CHANGED` → `UNKNOWN_TRADE`/`MISSING_TRADE`/`QUANTITY_MISMATCH`/`PRICE_MISMATCH`; `LATE_STATEMENT` →
  `MISSING_STATEMENT` only with a grace shorter than its lateness (default 2h finds none).
- **Missing statements run on the exchange's clock** (`BusinessCalendar.businessNow()`, `GET /api/clock`) from the
  day's cutoff in `business_day_closed`; grace `trading-demo.reconciliation.statement-grace`.
- **A break becomes a case at the next close (Q-1)**: `CaseOpening` on `BusinessDayClosedNotice` opens one per break
  of statements before the closed day, and clears open cases whose break is gone. Before counting a statement missing
  it reads both statement sources to their end and waits for their inboxes (`catchUp(Duration)`): the calendar's reader
  can be ahead, most visibly when the clock jumps several days and every close arrives at once.
- **A case never books (Q-2).** `LEDGER_CORRECTED` requires and records the operator's own `AdjustCash` id.
- **Case id = break key** `<statementId>:<kind>:<reference>`; opening an existing case is a no-op, so a break that
  comes back after a case was cleared does not reopen it.

## Slices

| Slice | Kind | Driven by |
|---|---|---|
| `external_systems/bank` | external system | gateway `BankStatementIssued` (`POST /webhooks/payment-gateway/bank-statements`, event log) |
| `external_systems/clearinghouse` | external system | exchange `ClearingStatementIssued` (`POST /webhooks/exchange/clearing-statements`, event log) |
| `known_payments`, `known_executions` | view | `funding`'s payment events; `orders`' `OrderFilled` |
| `bank_statement_breaks`, `clearing_statement_breaks` | view | operator (`/api/admin/reconciliation/{bank,clearing}-statements[/{id}/breaks]`) |
| `missing_statements` | view | operator (`/api/admin/reconciliation/missing-statements`) |
| `open_cases` | automation | `BusinessDayClosedNotice` → `OpenCase` / `ClearCase` |
| `track_breaks` | command | `open_cases` (no endpoint) |
| `assign_case`, `resolve_case` | command | operator (`POST /api/admin/reconciliation/cases/{caseId}/{assignment,resolution}`) |
| `cases` | view | operator (`GET /api/admin/reconciliation/cases[?state=]`, `/{caseId}` with history) |

Tests: `ReconciliationCaseTest` (unit); `BankStatementReconciliationIT`, `ClearingReconciliationIT` (seeded breaks one
for one, then cases), `LateStatementIT` (grace 0), `CleanReconciliationIT` (no breaks, no case) - real simulator
images, skipped without them.
