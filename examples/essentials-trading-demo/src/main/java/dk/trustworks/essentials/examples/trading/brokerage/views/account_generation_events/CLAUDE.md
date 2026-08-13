# Slice: brokerage.account_generation_events

**Kind:** view   **Status:** live   **Owner:** brokerage-team
**Purpose:** Serve the raw event stream of one books generation of a trading account.

## A view slice that owns no read model

Everything this slice serves is framework lifecycle data, read live from the event store through
`AggregateLifecycleApi`. There is nothing to project: a read model here would be a copy of the event store
with no question of its own to answer, and it would be stale into the bargain.

That also means the response body is the framework's own `ApiClosingBooksGenerationEventStream`. Nothing of
ours would be more faithful than the framework's own shape, and re-declaring it here would be a `…Response`
mirror — which §R2 rules out.

## Invariants
- An unknown generation fails with `Couldn't resolve generation <n> for trading account <id>` — unchanged
  from before this was a slice. It is deliberately not an empty stream, which would be indistinguishable
  from a generation that genuinely has no events.

## Boundaries
**Reacts to / reads:** `AggregateLifecycleApi`, scoped to `TradingAccounts.AGGREGATE_TYPE`.
**Publishes:** nothing — a view slice never produces events.
**Forbidden:** never import another slice's internals; never load the `TradingAccount` aggregate.

## Endpoint
`GET /api/admin/trading-accounts/{accountId}/generations/{generation}/events`

`{accountId}` binds as a `TradingAccountId` — the account's stable business id, not the `#`-suffixed stream
id. The generation number is the other half of that stream id and is passed separately; the framework joins
them.

## Files
- `AccountGenerationEventsQuery.java` — the lifecycle lookup and its failure message
- `AccountGenerationEventsAPI.java` — the slice's only endpoint
