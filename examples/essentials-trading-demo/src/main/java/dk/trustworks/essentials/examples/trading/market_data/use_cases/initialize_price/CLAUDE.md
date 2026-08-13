# Slice: market_data.initialize_price

**Kind:** command   **Status:** live   **Owner:** market-data-team
**Purpose:** Open the price stream for an instrument at its first known market price.

## Invariants
- A price must be **greater than zero** — `InstrumentPriceEvent.requirePositive` rejects zero or less.
  Because it runs in the event record's canonical constructor it is enforced on *deserialization* too, so
  a stream containing a non-positive price fails loudly on replay rather than projecting nonsense.
- The price stream is keyed by `InstrumentId` — it *is* the instrument's identity under a second
  `AggregateType`. There is no price id to supply and none should be invented.

## Boundaries
**Reacts to / reads:** the `InitializePrice` command. Reads nothing else.
**Publishes:** `PriceInitialized`.
**Forbidden:** never import another slice's internals — only `market_data/events/`, `market_data/types/`
and `market_data/aggregates/`. In particular, never the demo harness's direct-write JDBC price table.

## Data
**Owns (writes):** the `InstrumentPrice` aggregate stream (`AggregateType` `InstrumentPrices`, snapshotted
every 1000 events).

This is the only slice that *constructs* an `InstrumentPrice`; `update_price` loads and mutates.

## Endpoint
`POST /api/admin/instrument-prices` — body is the `InitializePrice` command itself.
