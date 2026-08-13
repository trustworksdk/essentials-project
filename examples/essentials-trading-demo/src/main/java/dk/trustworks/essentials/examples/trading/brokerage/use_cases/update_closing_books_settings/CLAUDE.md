# Slice: brokerage.update_closing_books_settings

**Kind:** command   **Status:** live   **Owner:** brokerage-team
**Purpose:** Retune the trading-account closing-books policy — mode, event threshold, time boundary, zone and
interval days — in one atomic change.

## One slice, not four. Do not split it back up.
It replaced four separate mutating endpoints (`/closing-books/mode`, `/time-boundary`, `/zone-id`,
`/interval-days`) that raced with the load generator's benchmark scenario: four independent writes are not
one write, so a reader could see a new `mode` against the old `timeBoundary`, and the scenario's
swap-run-restore could interleave with an admin request into a combination neither party asked for. The
settings are now one immutable `ClosingBooksSettings` value swapped atomically under the policy's lock.

## Invariants
- The whole change is one reference swap under one lock (enforced by
  `TradingAccountClosingBooksPolicy.update`, which this handler calls exactly once, chaining the `withX`
  copy methods inside the lambda).
- `eventThreshold > 0` and `intervalDays > 0` (enforced by `ClosingBooksSettings.withEventThreshold` /
  `withIntervalDays`); `mode`, `timeBoundary` and `zoneId` are non-null once set (enforced by the record).
- A `null` component means "leave unchanged", so the command's compact constructor deliberately does **not**
  `requireNonNull` anything — a partial update is the normal case.

## Boundaries
**Reacts to / reads:** the `UpdateClosingBooksSettings` command.
**Publishes:** nothing — this changes in-memory policy configuration, not an event stream.
**Forbidden:** never import another slice's internals; never reintroduce per-field setters on the policy.

## Endpoint
`POST /api/admin/trading-accounts/closing-books` — body is the command; omit a field to leave it alone.

## Files
- `UpdateClosingBooksSettings.java` — the command; it *is* the request body, no DTO
- `UpdateClosingBooksSettingsHandler.java` — the slice's one `@CmdHandler`
- `UpdateClosingBooksSettingsAPI.java` — the slice's only endpoint
