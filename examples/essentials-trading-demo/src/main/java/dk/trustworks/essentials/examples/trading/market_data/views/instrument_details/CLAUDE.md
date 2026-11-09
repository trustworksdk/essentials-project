# Slice: `market_data.instrument_details` (view)

Serves instrument reference data:

- `GET /api/admin/instruments` — every instrument
- `GET /api/admin/instruments/{instrumentId}` — one, 404 when absent

The same two queries are also mapped under `/api/market-data/instruments`, where a customer may read them (the
order ticket of the phase 7 customer UI needs names and suspension). One class-level `@RequestMapping` with both
paths, so the slice keeps its one API file; nothing in the read model is operator-only.

Two queries, one read model (`projection_instrument_details`), therefore one slice (§R2).

## Why it exists

`market_data` had three command slices that change an instrument — `register_instrument`,
`rename_instrument`, `suspend_instrument` — and, until this slice, no way to observe the result. The
aggregate's fields are private and it exposes no accessors, so a rename was literally unverifiable: the
integration test had to drop its assertion that the new display name stuck.

That is the shape of gap worth noticing in general. A bounded context whose write side has no matching read
side is not "minimal" — it is untestable through its own API.

## Why it projects

Nothing needs an instrument's display name strongly consistently, so it projects `InstrumentEvent` into a table it
owns and is eventually consistent like every other view in the demo.

`InstrumentRegistered` upserts rather than inserts, so redelivery re-applies the same row instead of failing
on the primary key. Its `ON CONFLICT` clause updates only symbol and display name: `risk_status` is set on
insert and never reset, because by the time a registration is redelivered the risk automation may already
have recorded a decision.

## `risk_status` is where the risk automation becomes visible

`market_data.risk_approve_instrument` calls an external risk service outside any transaction and records the
answer on the instrument. This read model projects both outcomes into `risk_status` / `risk_detail`, and
`PENDING` is the state it invents for the window in between — an instrument exists as soon as it is
registered, while the blocking call is still in flight. That window is the only place the demo shows a handler
doing blocking I/O, so the column is worth having even though nothing branches on it.
