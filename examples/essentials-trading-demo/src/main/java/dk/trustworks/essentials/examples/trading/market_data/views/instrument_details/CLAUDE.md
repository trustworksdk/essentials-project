# Slice: `market_data.instrument_details` (view)

Serves instrument reference data:

- `GET /api/admin/instruments` — every instrument
- `GET /api/admin/instruments/{instrumentId}` — one, 404 when absent

Two queries, one read model (`projection_instrument_details`), therefore one slice (§R2).

## Why it exists

`market_data` had three command slices that change an instrument — `register_instrument`,
`rename_instrument`, `suspend_instrument` — and, until this slice, no way to observe the result. The
aggregate's fields are private and it exposes no accessors, so a rename was literally unverifiable: the
integration test had to drop its assertion that the new display name stuck.

That is the shape of gap worth noticing in general. A bounded context whose write side has no matching read
side is not "minimal" — it is untestable through its own API.

## Why it projects, unlike `views/latest_price`

Its sibling reads the aggregate directly, and that is the documented exception. This one is the normal case:
nothing needs an instrument's display name strongly consistently, so it projects `InstrumentEvent` into a
table it owns and is eventually consistent like every other view in the demo.

`InstrumentRegistered` upserts rather than inserts, so redelivery re-applies the same row instead of failing
on the primary key.
