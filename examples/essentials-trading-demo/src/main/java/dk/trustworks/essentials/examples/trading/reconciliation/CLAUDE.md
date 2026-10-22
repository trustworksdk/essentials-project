# Bounded context: reconciliation

The brokerage's bookings checked against the outside world's statements. Phase 2 builds the first: the bank's
end-of-day statements against `funding`'s payments (design `docs/design/phase-2-funding.md` §7). Clearing statements
and the operator case workflow (`Break` aggregate) are phase 5.

## Rules that are easy to break

- **Statements are stored, not event-sourced** (`bank_statement`, `bank_statement_entry`): reference data from outside,
  stored as the bank issued it, once per `statementId`. Simulators profile only (`external_systems/bank`).
- **Breaks are worked out when read** (`BankStatementBreaksQuery`), from the statement and `known_payments` as they are
  now - never at intake, or a statement that arrives before `funding`'s completion is projected would keep a false
  break forever.
- **`known_payments` is reconciliation's own projection of `funding`'s events**, a cross-BC `events/` import, so
  nothing here calls into `funding`.
- **Matching is by `endToEndId` and `paymentId`** (our `DepositId` / `WithdrawalId`, the gateway's idempotency key,
  and the gateway's id it was taken under) and entry kind. The `paymentId` matters: the gateway's phantom entry copies a
  real entry's `endToEndId` and amount under a payment that does not exist. Entry kinds:
  `DEPOSIT`/`DEPOSIT_RETURN` belong to a deposit, `WITHDRAWAL`/`WITHDRAWAL_RETURN` to a withdrawal. Amounts are stored
  unsigned. A missing entry needs our completion's value date (SR-33), so it shows only once the gateway sends it.
- **The seeded breaks map one for one**: `PHANTOM_ENTRY` → `UNKNOWN_ENTRY`, `AMOUNT_CHANGED` → `AMOUNT_MISMATCH`,
  `OMITTED_ENTRY` → `MISSING_ENTRY` (needs SR-33). `LATE_STATEMENT` is not reported yet.

## Slices

| Slice | Kind | Driven by |
|---|---|---|
| `external_systems/bank` | external system | the gateway's `BankStatementIssued` (`POST /webhooks/payment-gateway/bank-statements`, event log) |
| `known_payments` | view | `funding`'s payment events |
| `bank_statement_breaks` | view | operator (`GET /api/admin/reconciliation/bank-statements[/{statementId}/breaks]`) |
