# Demo Runbook — The Event Is The Record

Three segments, 4.5 minutes, slides 25–27. English only: everything here is a command or an endpoint name.

The snapshots-and-generations segment was deliberately dropped. Snapshots and closing books get one slide
each in Act 3; demoing them as well made two secondary mechanisms the centre of the talk. The endpoints are
kept at the bottom of this file under **Retired segment** in case you want them for a longer session.

**The single rule: nothing is built on stage.** The app is running and warm before the room fills.

## Pre-flight, at least 20 minutes before

```bash
cd /workspace/examples/essentials-trading-demo

# 1. Wipe. Event-type FQCNs changed in the slice refactor and no upcasting is provided,
#    so an old volume yields a half-empty demo rather than a broken one.
docker compose down -v && docker compose up -d

# 2. Start, from the repo root
cd /workspace
mvn -q -pl examples/essentials-trading-demo -Dspring-boot.run.profiles=compose spring-boot:run
```

Wait for bootstrap to finish seeding, then confirm the state you are about to demo:

```bash
curl -s localhost:8080/actuator/health
curl -s localhost:8080/api/admin/projections/account-statements | head
curl -s localhost:8080/api/admin/instruments
```

Then open two windows and leave them open:

- a browser on `http://localhost:8080/admin`
- a terminal in `/workspace`, cleared, with a large font

Stop the live generator so the numbers you read are yours, not the background load's:

```bash
curl -X POST localhost:8080/api/admin/load-generator/stop
```

**Checklist before you start talking:**

- [ ] `/admin` loads and the KPI cards show non-zero values
- [ ] Three accounts exist: `ACC-DEMO-001`, `ACC-DEMO-002`, `ACC-DEMO-003`
- [ ] Live generator stopped
- [ ] Terminal font large enough to read from the back row
- [ ] `recordings/` open in a third window, minimised, ready

## Segment A — one slice, round trip (2 min, slide 26)

```bash
# 1. The command record IS the request body. No DTO.
curl -X POST localhost:8080/api/admin/instruments \
  -H 'Content-Type: application/json' \
  -d '{"instrumentId":"INS-DEMO","symbol":"NOVO-B","displayName":"Novo Nordisk B"}'

# 2. Projection-backed, so eventually consistent.
curl -s localhost:8080/api/admin/instruments/INS-DEMO

# 3. Rename — a second event, not an overwrite.
curl -X POST 'localhost:8080/api/admin/instruments/INS-DEMO/name?displayName=Novo%20Nordisk%20B%20A/S'

# 4. Renamed.
curl -s localhost:8080/api/admin/instruments/INS-DEMO

# 5. THE MONEY SHOT — the raw stream, straight off the event store.
curl -s localhost:8080/api/admin/trading-accounts/ACC-DEMO-001/generations/1/events
```

Stop on call 5 and let the room read it. Every decision, in order, still there.

Two things to say while it scrolls:

- the rename in step 3 did not destroy anything — it added a fact
- if step 2 lagged a beat, that is the eventual consistency from slide 18, not a bug

Optional, if a typed-id question comes up: a malformed id is rejected as a `400`, not a `500`, because
the semantic types are registered for web binding.

```bash
curl -si localhost:8080/api/admin/instrument-prices/ | head -1
```

## Segment B — what event sourcing costs (2 min, slide 27)

```bash
curl -X POST 'localhost:8080/api/admin/load-generator/comparisons/price-path?count=100'
```

Then refresh `/admin` and read the **Price Path Comparison** panel: operation counts and latency for
`aggregate-event-sourced` against `direct-write`.

**Say the caveat before anyone else can.** The aggregate path is measured slightly heavier than it needs
to be: it sends `UpdatePrice` on the command bus, so command dispatch, handler lookup and the
`UnitOfWork` interceptor all sit inside the timed window. The direct-write path is unchanged and the
transaction count per step is the same. The comparison is biased *against* event sourcing, and we are
showing it anyway.

Then the rule: event-source what you must be able to explain. Do not event-source a tick feed because you
can.

Optional, if there is time and appetite — the same honesty applied to rollover policy:

```bash
curl -X POST 'localhost:8080/api/admin/load-generator/comparisons/trading-account?count=90&readPasses=25&eventThreshold=20'
```

This one deliberately loads the aggregate on every read pass, because it exists to measure rehydration
cost under two rollover policies. Routing it through a projection would time a single-row `SELECT` and
report zero.

## Segment C — the admin console (1 min, no slide)

In the admin console:

1. **Aggregates → Aggregate lookup** — the three demo accounts, their generations, and the snapshot
   policy that is actually registered on `InstrumentPrices`
2. **Queues** — depth, and anything in the dead letter queue
3. **Locks** — which instance holds what

One sentence: none of this was built for the demo, it arrives with `spring-boot-starter-admin-api`.

**Cut this segment first** if you are past 29 minutes at slide 25.

## Retired segment — snapshots and generations

Not part of the talk. Kept here for a longer session, or for anyone who asks a snapshot question in Q&A and
you want to answer it live.

Bootstrap leaves three accounts in three different states, one per rollover mechanism:

| Account | Rolled by | Ends up as |
|---|---|---|
| `ACC-DEMO-001` | the closing-books **policy**, no application involvement | generation 2, and the only account with snapshots |
| `ACC-DEMO-002` | an explicit **command** on the command bus, no policy involvement | generation 2, no snapshots |
| `ACC-DEMO-003` | nothing — the baseline | generation 1, still open |

```bash
curl -X POST 'localhost:8080/api/admin/load-generator/burst/trade-lifecycles?count=500'
curl -s localhost:8080/actuator/metrics/essentials.aggregate_snapshot.save_snapshot
curl -s localhost:8080/actuator/metrics/essentials.aggregate_snapshot.load_snapshot
curl -s localhost:8080/api/admin/trading-accounts/ACC-DEMO-001
```

Thresholds are production-shaped — snapshot every 100 events, closing-books threshold 100 — not shrunk to
make the demo easy.

## Reset, between rehearsals

```bash
# stop the app (Ctrl-C in the mvn window), then
cd /workspace/examples/essentials-trading-demo
docker compose down -v && docker compose up -d
```

A wipe is required, not optional, if you want the three-account bootstrap state back — the runner detects
existing seed data and refuses to seed on top of it.

## Recordings

Capture all three segments, one file each, into `presentation/recordings/`:

```
recordings/
  demo-a-slice-round-trip.mp4
  demo-b-price-path-comparison.mp4
  demo-c-admin-console.mp4
  ai-delist-instrument-slice.mp4     # the recorded Act 6 segment
```

Record them during the rehearsal run, from a wiped database, in the same window layout you will present
in. If a live segment fails, open the recording and keep talking — do not debug on stage.

## If something breaks

| Symptom | Cause | Do this |
|---|---|---|
| Half-empty demo, warning in the log about legacy seed data | Old Docker volume — event-type FQCNs changed | `docker compose down -v`, restart. Not fixable live; play the recording. |
| `404` on an instrument that was just created | Projection has not caught up | Wait a beat and retry. This is the point of slide 18 — say so. |
| Numbers on `/admin` moving on their own | Live generator running | `POST /api/admin/load-generator/stop` |
| Typed path variable gives `500` | `EssentialsWebMvcConfigurer` not registered | Not a live fix. Play the recording. |
| Anything else | — | Recording. Keep talking. |
