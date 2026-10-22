# Bounded context: accounts

The trading account ledger: cash buckets, positions, holds, reservations, and the figures customers see. Design:
`docs/design/phase-1-account-core.md`. Lives **beside** the frozen `brokerage` context (its own `Accounts` stream,
not `TradingAccounts`) until phase 4 deletes `brokerage` (design Q-1).

**Write style: aggregate (§R5)** — `Account` behind `Accounts` (a `ClosingBooksLogicalAggregateRepository`), closing
books `END_OF_DAY` in New York until EF-1, snapshots every 100 events.

## Rules that are easy to break

- **Requests are checked, facts are not.** `holdFunds`, `reserveShares` refuse with `LedgerRuleViolation` (`422`).
  Deposits, returns, withdrawals, trades and settlements are facts from outside and are never refused for funds; they
  can restrict the account (settled cash < 0). Do not add a funds check to a fact.
- **Every mutating slice loads through `Accounts.getAccountForMutation`** — the closing-books trigger. Only
  `roll_account_books` must not, or one roll becomes two.
- **Idempotency is per external reference**, and open items carry into the next generation (`OpeningBalances`). A new
  kind of open item must be added to `OpeningBalances` and `openStateForNextGeneration`, or it is lost at the next roll.
- **A close that finds nothing open is silent only if it is a duplicate.** Opened and closed in this generation → no
  event. Never opened here → `UnknownReferenceIgnored`, once per kind and reference. That is how reordering shows
  instead of silently leaving a hold or trade open (it cost a cycle in the phase 1 load test). Any new closing command
  must call `ignoreIfUnknown` the same way.
- **Events carry their own effect** (`holdConsumed`, `costBasisChange`, `realizedPnl`, `unclearedReleased`).
  Projections read those, never recompute them.
- **The two balance projections apply deltas, so they are versioned** (generation + last `EventOrder`, the event-store
  docs' "Version = EventOrder" pattern). A `ViewEventProcessor` may redeliver; an unguarded delta is wrong money.
  `account_activity` is insert-only on `(stream, event order)` instead.
- **Simple-name clashes with `brokerage` break Spring**: bean names are simple class names, so a new class may not be
  named like one of `brokerage`'s (hence `ReleaseHold`, `RollAccountBooks`).
- **`Money` in a request body is `{"amount": 100.50, "currency": "USD"}` with a JSON *number*.** Essentials' deserializer
  refuses a string amount (`400`, `cannot coerce value "100.50"`).
- **`Account` implements `HasClosingBooksPeriodId`, and must.** With `essentials.eventstore.closing-books.enabled`
  (the `compose` profile, not the tests) the starter refuses to start a time-boundary aggregate without it. The tests
  never enable it, so only a real run catches its absence.
- **Deposits clear on the exchange's business days**, never on a clock (D10): `DepositClearing` clears what
  `uncleared_deposits` lists as due when `BusinessCalendar` announces a close, and at startup from the latest closed day
  (a notice can be missed). Without the simulators nothing drives it; the operator endpoint still clears.
- **Do not send commands from a `@TransactionalEventListener(AFTER_COMMIT)` directly.** Spring's synchronization is
  inactive in the after-commit callback, and `SpringTransactionAwareUnitOfWorkFactory` fails to open a unit of work
  (`Transaction synchronization is not active`). `DepositClearing` hands the work to its own thread.
- **Snapshots**: test a change to `Account`'s state through `DefaultAggregateSnapshotStateAdapter` (see `AccountTest`),
  not a plain serializer — the adapter is what leaves the `AggregateRoot` base fields out.

## Slices

| Slice | Kind | Driven by (from phase) |
|---|---|---|
| `open_account` | command | customer, for themselves (1) |
| `adjust_cash` | command | operator; reconciliation (5) |
| `credit_deposit`, `clear_deposit`, `return_deposit` | command | operator now; payment gateway (2) |
| `hold_funds`, `release_hold`, `withdraw_cash` | command | operator now; orders (3), withdrawals (2) |
| `credit_withdrawal_return` | command | operator; `funding`, a withdrawal the bank returned (2) |
| `reserve_shares`, `release_shares` | command | operator now; orders (3) |
| `book_trade`, `settle_trade`, `reverse_trade_booking` | command | operator now; exchange (3), settlement (4) |
| `correct_settlement_amount` | command | operator; `settlement`, a corrected settlement (4) |
| `roll_account_books` | command | operator; end of day (EF-1) |
| `account_overview`, `positions`, `account_activity` | view | customer (own accounts) and operator |
| `uncleared_deposits` | view | `clear_deposits` |
| `automations/clear_deposits` | automation | the exchange's business days (`integration/BusinessCalendar`), simulators profile only (2) |
