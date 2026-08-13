# Slice: market_data.rename_instrument

**Kind:** command   **Status:** live   **Owner:** market-data-team
**Purpose:** Change the display name of an already-registered instrument. The ticker symbol is not renameable.

## Invariants
- Renaming to the display name already held applies nothing — no `InstrumentRenamed`, no growth of the
  stream (enforced by `Instrument.rename`). A redelivered command therefore leaves no trace.
- Renaming an instrument that was never registered fails: the handler uses `getInstrument`, not
  `findInstrument`, so it will not silently create one.

## Boundaries
**Reacts to / reads:** the `RenameInstrument` command; loads the `Instrument` aggregate.
**Publishes:** `InstrumentRenamed`.
**Forbidden:** never import another slice's internals — only `market_data/events/`, `market_data/types/`
and `market_data/aggregates/`.

## Data
**Owns (writes):** the `Instrument` aggregate stream (`AggregateType` `Instruments`).
This slice loads and mutates, so it does **not** call `save`/`registerNewInstrument` — the `UnitOfWork`
persists the applied events on commit.

## Endpoint
`POST /api/admin/instruments/{instrumentId}/name?displayName=…` — the id comes from the path and the new
name from a request parameter, so the command is built fully non-null in the API file rather than
deserialized half-empty from a body.
