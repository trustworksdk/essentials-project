# Slice: brokerage.closing_books_configuration

**Kind:** view   **Status:** live   **Owner:** brokerage-team
**Purpose:** Report the closing-books policy currently in force.

## A view over runtime configuration, not over events

The closing-books policy is in-memory state the demo lets an admin retune; it is not event-sourced and there
is nothing to project. So this slice owns no read model — only the *rendering* of one, from the
`ClosingBooksSettings` snapshot `TradingAccountClosingBooksPolicy` holds.

## The mutating side is not here

`POST /api/admin/trading-accounts/closing-books` belongs to `use_cases/update_closing_books_settings`. A view
slice never writes. The two sit at the same path precisely because they are the read and the write of one
thing — do not merge them, and do not add per-field POSTs here (those are what the single command replaced).

## Invariants
- `settings()` is read **once** and rendered whole (enforced by `ClosingBooksConfigurationAPI`). Reading the
  policy field-by-field could interleave with an update and report a combination that was never in force —
  which is exactly what the immutable settings record exists to prevent.
- `mode` and `timeBoundary` render **lowercase-with-hyphens** (`end-of-month`, not `END_OF_MONTH`). That is
  the vocabulary the admin UI has always spoken and the spelling the update command accepts, so the two
  halves stay symmetric. The conversion happens in one place, `ClosingBooksConfiguration.from`.
- The JSON field names are unchanged from the pre-slice `TradingAccountClosingBooksConfigurationView`:
  `mode`, `eventThreshold`, `timeBoundary`, `zoneId`, `intervalDays`, `description`.

## Boundaries
**Reacts to / reads:** `TradingAccountClosingBooksPolicy.settings()` and `.description()`.
**Publishes:** nothing — a view slice never produces events.
**Forbidden:** never mutate the policy from here; never read it field-by-field.

`TradingAccountClosingBooksPolicy` lives in `brokerage/aggregates/` beside `TradingAccount`, which is
BC-private — this slice is in the same bounded context, so reading it is not a boundary crossing.

## Files
- `ClosingBooksConfiguration.java` — the response body and the enum-to-hyphen rendering
- `ClosingBooksConfigurationAPI.java` — `GET /api/admin/trading-accounts/closing-books`
