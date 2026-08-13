# Slice: market_data.suspend_instrument

**Kind:** command   **Status:** live   **Owner:** market-data-team
**Purpose:** Suspend trading in an instrument, recording why.

## Invariants
- Suspending an already-suspended instrument applies nothing (enforced by `Instrument.suspend`), so the
  **first** reason is the one that stands and a redelivered command leaves no trace.
- Suspension is one-way: there is no un-suspend event, hence no `DELETE` counterpart to the endpoint.
- Suspending an instrument that was never registered fails — the handler uses `getInstrument`, not
  `findInstrument`.

## Boundaries
**Reacts to / reads:** the `SuspendInstrument` command; loads the `Instrument` aggregate.
**Publishes:** `InstrumentSuspended`.
**Forbidden:** never import another slice's internals — only `market_data/events/`, `market_data/types/`
and `market_data/aggregates/`.

## Data
**Owns (writes):** the `Instrument` aggregate stream (`AggregateType` `Instruments`).
Loads and mutates, so no `save` — the `UnitOfWork` persists on commit.

Note the suspension flag is *not* consulted by the trade path in this demo; it is reference data, and
enforcing "no trading in a suspended instrument" would be a cross-context invariant, which this demo
deliberately does not have.

## Endpoint
`POST /api/admin/instruments/{instrumentId}/suspension?reason=…` — id from the path, reason from a
request parameter, command built fully non-null in the API file.
