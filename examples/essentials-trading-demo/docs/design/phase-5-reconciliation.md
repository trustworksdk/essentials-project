# Phase 5 design — reconciliation

**Status:** proposal (2026-10-22). Decisions this note asks for are marked **(Q-n)** and collected in §7.
**Plan:** [`../EXPANSION_PLAN.md`](../EXPANSION_PLAN.md) phase 5; decisions D8, D10, D11; simulator requirements
SR-11, SR-35.
**Builds on:** phase 2's first reconciliation ([`phase-2-funding.md`](phase-2-funding.md) §7): the bank's statements
stored as reference data, their breaks worked out when read; phase 3's fills and phase 4's settlements, which are what
the clearing statement is checked against.

Phase 5 checks the brokerage's books against both outside statements - the bank's, already, and now the
clearinghouse's - and turns a break into an **operator case** that is worked and closed. The simulators seed breaks of
known kinds; every one must be found, one for one, and nothing else.

---

## 1. What the clearinghouse reports (simulators 0.4.0)

At each business day's cutoff (`clearing.cutoff`, the same calendar as `BusinessDayClosed`), one
`ClearingStatementIssued` per participant that traded since the previous cutoff:

- **trades**, one line per execution: `executionId`, instrument, side, quantity, price (ticks), amount, trade date,
  intended settlement date, the obligation it nets into;
- **obligations**, one per instrument and settlement date: securities net, cash net (positive receives).

Seeded breaks (`clearing.breaks.*`, listed by `GET /test/clearing/breaks`): `OMITTED_TRADE`, `QUANTITY_CHANGED`,
`PRICE_CHANGED`, `PHANTOM_TRADE`, `LATE_STATEMENT`. The bank's (`statement.breaks.*`): `OMITTED_ENTRY`,
`AMOUNT_CHANGED`, `PHANTOM_ENTRY`, `LATE_STATEMENT`.

## 2. Clearing reconciliation

As the bank's (phase 2 Q-5): the statements are **stored as reference data** (`clearing_statement`,
`clearing_trade`, `clearing_obligation`, by webhook `demo-clearing` and catch-up), and the breaks are **worked out
when read**, against `reconciliation`'s own projection of the brokerage's executions (`orders`' `OrderFilled`:
execution, account, side, quantity, price, trade date). Matching:

| Break | Found as |
|---|---|
| a statement trade with no execution of ours | `UNKNOWN_TRADE` (the seeded `PHANTOM_TRADE`) |
| an execution of ours traded in the statement's window with no line | `MISSING_TRADE` (`OMITTED_TRADE`) |
| same execution, other quantity / price / side | `QUANTITY_MISMATCH` / `PRICE_MISMATCH` / `SIDE_MISMATCH` |
| an obligation whose nets differ from the sum of our executions into it | `OBLIGATION_MISMATCH` |

The statement window is the business day's: an execution belongs to the statement of its `tradeDate` (SR-29), never to
a timestamp range of our own. An execution traded for a business day whose statement has not arrived is not a break yet.

## 3. Late and missing statements

A statement is **expected** for a business day on which the brokerage traded (clearing) or a payment was booked
(bank). Expected and not received by a grace after the cutoff - `trading-demo.reconciliation.statement-grace`,
measured on the simulator's clock in business time - is a `MISSING_STATEMENT`; one that arrives later closes it. The
simulators' `LATE_STATEMENT` is issued within 60 minutes; a grace of 2 business hours finds none of them missing, a
grace of 0 finds every one (**Q-3**).

## 4. Cases (Q-1, Q-2)

A `ReconciliationCase` aggregate per break (`reconciliation/aggregates`), id = the break's stable key
(`<statementId>:<kind>:<reference>`):

```
CaseOpened(break) ─▶ CaseAssigned(operator) ─▶ CaseResolved(resolution, note, adjustment?) ─▶ (closed)
                 └──────────────────────────────▶ CaseCleared   (the break is gone: a late projection or late statement)
```

- **Opening (Q-1).** Breaks are computed when read, so one can be transient: the statement arrived before our own
  fill or payment was projected. Proposed: an automation opens a case for a break **still present at the next business
  day's close** (`BusinessDayClosedNotice`), and clears it if it disappears before it is resolved. Opening at once
  would open, then clear, a case for every statement that beats the projection.
- **Resolving (Q-2).** The operator chooses a resolution: `LEDGER_CORRECTED` (the operator posts the correction
  themselves - an `AdjustCash` with the case id as its reason, which the case records), `COUNTERPARTY_ERROR` (the
  statement is wrong; nothing to book), `WRITTEN_OFF` (immaterial). **A case never posts to the ledger by itself**:
  what to book is a judgement the brokerage makes, not the reconciler.
- **Endpoints**, operator only: list and filter cases, a case with its break and history, assign, resolve. The
  operator UI is phase 7; until then the admin API and the Essentials console.

## 5. Checked one for one

Two ITs turn the simulators' seeded breaks on (each probability at 1, as phase 2's does for the bank) and assert, for
every statement, that the brokerage's breaks map one for one onto the simulator's own list - no break missed, none
invented. A third runs the order flow and funding scenarios with breaks off and asserts zero cases.

## 6. Slices

| Context | Slice | Kind | Driven by |
|---|---|---|---|
| reconciliation | `external_systems/clearinghouse` | external system | `ClearingStatementIssued` |
| reconciliation | `known_executions` | view | `orders`' `OrderFilled` |
| reconciliation | `clearing_statement_breaks` | view | operator |
| reconciliation | `missing_statements` | view | the business calendar |
| reconciliation | `open_cases` | automation | `BusinessDayClosedNotice`: open a case per persisting break, clear the ones gone |
| reconciliation | `assign_case`, `resolve_case` | command | operator |
| reconciliation | `cases` | view | operator |

---

## 7. Decisions

| | Question | Proposed |
|---|---|---|
| **Q-1** | When a break becomes a case | When it is still there at the next business day's close; cleared if it goes before it is resolved (§4) |
| **Q-2** | Does resolving a case book anything? | Never by itself: the operator posts any correction (`AdjustCash`, case id as reason) and the case records it (§4) |
| **Q-3** | Grace before a statement counts as missing | 2 business hours after the cutoff, configurable (§3) |
| **Q-4** | Clearing statements stored as reference data with read-time breaks, as the bank's | Yes (§2) |
