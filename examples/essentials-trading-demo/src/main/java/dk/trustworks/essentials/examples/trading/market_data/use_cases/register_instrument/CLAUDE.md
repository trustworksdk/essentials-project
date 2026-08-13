# Slice: market_data.register_instrument

**Kind:** command   **Status:** live   **Owner:** market-data-team
**Purpose:** Register a new instrument's reference data (symbol + display name).

## Invariants
- An instrument always starts un-suspended with the supplied symbol and display name (enforced by
  `Instrument`, whose `on(InstrumentRegistered)` sets all four fields).
- The caller supplies the `InstrumentId`, so a retried call addresses the same instrument instead of
  minting a second one.

## Boundaries
**Reacts to / reads:** the `RegisterInstrument` command. Reads nothing else.
**Publishes:** `InstrumentRegistered`.
**Forbidden:** never import another slice's internals — only `market_data/events/`, `market_data/types/`
and `market_data/aggregates/` (this BC's §R5 aggregate style).

## Data
**Owns (writes):** the `Instrument` aggregate stream (`AggregateType` `Instruments`).

This is the only slice that *constructs* an `Instrument`; `rename_instrument` and `suspend_instrument`
load and mutate, and so do not call `registerNewInstrument`.

## Endpoint
`POST /api/admin/instruments` — body is the `RegisterInstrument` command itself.
