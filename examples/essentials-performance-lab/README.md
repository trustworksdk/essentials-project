# Essentials Performance Lab

This module is a Spring Boot example app intended to:

- showcase core Essentials building blocks in one place
- run repeatable performance scenarios for EventStore/CDC, DurableQueues, Inbox/Outbox, and subscriber topologies

## Status

Initial scaffold:

- `catalog` scenario (prints effective config)
- working scenario:
  - `baseline-polling-vs-cdc` (fixed-seed append + subscription run with JSON metrics output)
- placeholder scenarios:
  - `cdc-hybrid`
  - `durable-queues`

## Run

From repository root:

```bash
mvn -pl examples/essentials-performance-lab -DskipTests spring-boot:run
```

Run a specific scenario:

```bash
mvn -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.arguments=--essentials.lab.scenario=baseline-polling-vs-cdc spring-boot:run
```

## Local Postgres + CDC Plugins

The local compose Postgres image includes:

- `pgoutput` support via PostgreSQL itself
- `wal2json` plugin for explicit comparison runs

Start local Postgres:

```bash
docker compose -f examples/essentials-performance-lab/docker-compose.yml up -d --build
```

The default CDC plugin is `pgoutput`. The publication (`essentials_cdc_publication` by
default) is **not** pre-created by the Docker image — the framework's
`essentials.eventstore.cdc.pg-output.publication.auto-manage=true` setting creates it as
`FOR TABLE <event-stream-tables>` at tailer startup. See "Publication management" below for
why this matters. In the EventStore CDC path, `pgoutput` only converts insert messages for
configured aggregate event tables; non-insert replication messages are ignored at the
tailer (see `PgOutputRawPayloadFilter`).

Run the app against compose DB (port `55432`) using the default `pgoutput` path:

```bash
mvn -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.profiles=compose \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc-compare --essentials.lab.metrics-output-file=./target/baseline-compare-pgoutput.json" \
  spring-boot:run
```

If you want to force the old `wal2json` path for comparison, set:

```bash
mvn -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.profiles=compose \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc-compare --essentials.eventstore.cdc.plugin=wal2json --essentials.lab.metrics-output-file=./target/baseline-compare-wal2json.json" \
  spring-boot:run
```

Stop compose:

```bash
docker compose -f examples/essentials-performance-lab/docker-compose.yml down -v
```

## Configuration

Main properties:

- `essentials.lab.mode` = `showcase|benchmark`
- `essentials.lab.scenario` = `catalog|baseline-polling-vs-cdc|baseline-polling-vs-cdc-compare|cdc-hybrid|durable-queues`
- `essentials.lab.warmup` (duration)
- `essentials.lab.duration` (duration)
- `essentials.lab.producer-threads`
- `essentials.lab.subscriber-count`
- `essentials.lab.queue-count`
- `essentials.lab.aggregate-cardinality`
- `essentials.lab.random-seed`
- `essentials.lab.metrics-output-file` (optional JSON output path)

CDC starter knobs are available as usual under `essentials.eventstore.cdc.*`.

Important CDC plugin settings:

- `essentials.eventstore.cdc.plugin=pgoutput|wal2json`
- `essentials.eventstore.cdc.pg-output.publication-name`

Defaults:

- `essentials.eventstore.cdc.plugin=pgoutput`
- `essentials.eventstore.cdc.pg-output.publication-name=essentials_cdc_publication`

### Publication management (pgoutput)

The pgoutput plugin requires a server-side publication; it tells Postgres which tables'
row changes should stream over the replication slot. The framework offers two control models:

**Auto-manage (recommended default for most deployments)** — the framework creates and
maintains the publication at tailer startup using the registered aggregate event-stream
tables:

```yaml
essentials:
  eventstore:
    cdc:
      pg-output:
        publication:
          auto-manage: true
          mode: FOR_TABLE_LIST    # default — one explicit table per registered aggregate
          # mode: FOR_ALL_TABLES  # alternative — requires superuser, broader net
```

Why `FOR_TABLE_LIST` (the default):

- **Server-side filter.** pgoutput drops entire transactions that don't touch any listed
  table — the WAL message never reaches the client. A `FOR ALL TABLES` publication would
  stream every transaction's B/C envelopes plus row changes on chatty framework tables
  (`durable_queues`, `fenced_lock`, `subscription_tracking`, TTL timestamps, …). At load
  that's meaningful wasted server CPU + network + client-side filtering.
- **No superuser required.** `CREATE PUBLICATION ... FOR TABLE <list>` needs only
  ownership of the listed tables, which the framework user already has (it created them).
  `FOR ALL TABLES` requires superuser and is usually not available in managed Postgres.
- **Self-healing** — on privilege failure or any SQLException, auto-manage logs a loud
  WARN with the remediation SQL and the tailer continues; subsequent startup runs retry.

**DBA-managed (traditional)** — leave `auto-manage=false` (default) and have an operator
create the publication as part of your Postgres migrations or bootstrapping. The framework
will log the publication's current state + coverage at tailer start (WARN if event-stream
tables aren't covered), so misconfiguration is immediately visible.

**Known limitation (auto-manage):** the publication is only (re-)evaluated at tailer
startup. Aggregates registered at runtime (after `Lifecycle.start()`) won't automatically
be added to the publication until the next restart — the startup coverage-check will WARN
about them. For the vast majority of apps aggregate registration happens at Spring
startup (`@PostConstruct`, `@Configuration` beans), so this is rarely an issue. If your
workload requires truly dynamic aggregates, either restart the tailer after registration
or pre-declare the publication with `FOR ALL TABLES`.

### Fresh slot per JVM (dev/test)

Each `matrix case` starts a new JVM against the same Postgres instance. Without
intervention, the replication slot persists across cases and each new JVM inherits the
previous case's WAL backlog — at heavy load the tailer spends the whole measurement
window replaying backlog instead of reaching fresh events. The perf-lab opts into
`essentials.eventstore.cdc.slot.recreate-on-start=true` so each JVM drops + re-creates
the slot at current WAL head, giving clean per-case measurements.

Never enable this in production: the drop discards any unacknowledged WAL. For production,
rely on `auto-recover`, the adaptive polling fallback, and the self-healing
`auto-recreate-slot-on-stuck` flag documented in the CDC properties.

## Baseline CDC vs polling runs

Run with CDC enabled using the default `pgoutput` plugin:

```bash
mvn -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc --essentials.eventstore.cdc.enabled=true --essentials.lab.metrics-output-file=./target/baseline-cdc.json" \
  spring-boot:run
```

Run with CDC enabled using `wal2json` explicitly:

```bash
mvn -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc --essentials.eventstore.cdc.enabled=true --essentials.eventstore.cdc.plugin=wal2json --essentials.lab.metrics-output-file=./target/baseline-cdc-wal2json.json" \
  spring-boot:run
```

Run with CDC disabled (polling path):

```bash
mvn -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc --essentials.eventstore.cdc.enabled=false --essentials.lab.metrics-output-file=./target/baseline-polling.json" \
  spring-boot:run
```

Run side-by-side in one command:

```bash
mvn -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc-compare --essentials.eventstore.cdc.enabled=false --essentials.lab.metrics-output-file=./target/baseline-compare.json" \
  spring-boot:run
```

Comparison JSON includes `polling`, `cdcInbox`, `cdcDirect`, `deltaInbox`, and `deltaDirect` (plus backward-compatible `cdc`/`delta` aliases).

Note: `baseline-polling-vs-cdc-compare` starts child Spring contexts internally (polling + CDC inbox + CDC direct).  
With defaults (`warmup=10s`, `duration=30s`) this takes roughly 120+ seconds.

### Compare metrics meaning

- `appendEventsPerSecond` (`append eps`): successful event appends per second (producer-side throughput)
- `deliveredEventsPerSecond` (`delivery eps`): delivered events per second to subscribers (consumer-side throughput)
- `p95LatencyMs` (`p95 ms`): 95th percentile end-to-end latency from append to subscriber delivery (lower is better)
- `slaUnder500msPct` / `slaUnder1000msPct`: percentage of delivered events below latency SLO thresholds
- `timeToFirstDeliveryMs`: time from measurement start to first delivered event
- `timeToCatchUpMs`: time after producer stop until expected deliveries are reached (`-1` if timeout)
- `deliveryLagEventsEnd`: undelivered events at measurement end (`expectedDeliveries - deliveredEvents`)
- `deliveryCompletionPct`: delivered share of expected events at end of wait window

For compare runs:

- `deltaInbox.*` = `cdcInbox - polling`
- `deltaDirect.*` = `cdcDirect - polling`

Interpretation rule of thumb:

- higher `append eps` is better
- higher `delivery eps` is better
- lower `p95 ms` is better

Print a compact result summary from a compare JSON file:

```bash
examples/essentials-performance-lab/scripts/summarize-compare.sh \
  examples/essentials-performance-lab/target/baseline-compare-3way-bytes-long-after-directfix.json
```

## CI smoke test (Testcontainers)

There is a CI-friendly smoke test that runs the compare scenario with Testcontainers Postgres and validates that comparison JSON is produced:

```bash
mvn -pl examples/essentials-performance-lab -Dtest=BaselineComparisonScenarioSmokeIT test
```

This smoke test does not require `wal2json`; CDC path is exercised in `auto` fallback semantics for robustness.

## Benchmark matrix scripts

Helper scripts are included under `examples/essentials-performance-lab/scripts`:

- `run-baseline-matrix.sh`: fixed small matrix over subscriber/cardinality shapes
- `run-cdc-tuning-matrix.sh`: CDC tuning matrix over dispatcher/backfill/tailer knobs
- `run-cdc-dispatched-policy-ab.sh`: repeated A/B compare of `mark-dispatched` vs `delete` inbox row policy
- `run-backpressure-matrix.sh`: slow-subscriber matrix that validates the CDC pipeline's bounded buffers hold under sustained producer pressure

Run full CDC tuning matrix (recommended default):

```bash
examples/essentials-performance-lab/scripts/run-cdc-tuning-matrix.sh
```

Run only selected cases (fastest way to re-check top candidates):

```bash
CUSTOM_CASES='base|1000|200|PT0.025S|PT0.025S;backfill-2000|2000|200|PT0.025S|PT0.025S;disp-p50ms|1000|200|PT0.05S|PT0.025S' \
WARMUP=PT20S DURATION=PT120S SUBSCRIBER_COUNT=2 AGGREGATE_CARDINALITY=5000 \
examples/essentials-performance-lab/scripts/run-cdc-tuning-matrix.sh
```

Run quick smoke-only matrix check:

```bash
CUSTOM_CASES='smoke|1000|200|PT0.025S|PT0.025S' \
WARMUP=PT2S DURATION=PT5S SUBSCRIBER_COUNT=1 AGGREGATE_CARDINALITY=200 \
examples/essentials-performance-lab/scripts/run-cdc-tuning-matrix.sh
```

Outputs are written per run to:

- `examples/essentials-performance-lab/target/cdc-tuning/<run-id>/summary.json`
- `examples/essentials-performance-lab/target/cdc-tuning/<run-id>/summary.md`

Run dispatched-row policy A/B (median summary over repeated runs):

```bash
REPEATS=3 WARMUP=PT20S DURATION=PT120S \
examples/essentials-performance-lab/scripts/run-cdc-dispatched-policy-ab.sh
```

Optional slot hygiene toggles for compose runs:

```bash
AUTO_CLEANUP_INACTIVE_SLOTS=true
SLOT_PREFIX=lab_
SLOT_CLEANUP_CONTAINER=essentials-perf-lab-postgres
```

The script now prints invalid-run reasons (e.g. CDC fallback/failed state) and excludes invalid runs from medians.

Outputs are written per run to:

- `examples/essentials-performance-lab/target/cdc-dispatched-policy-ab/<run-id>/summary.json`
- `examples/essentials-performance-lab/target/cdc-dispatched-policy-ab/<run-id>/summary.md`

## Benchmark Command Cookbook

Use these exact commands from repository root (copy/paste friendly):

### 0) Long 3-way compare with default CDC tuning (polling vs cdcInbox vs cdcDirect)

```bash
mvn -q -pl examples/essentials-performance-lab -DskipTests -Dspring-boot.run.profiles=compose \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc-compare --essentials.lab.warmup=PT20S --essentials.lab.duration=PT120S --essentials.eventstore.cdc.wal-parser-mode=BYTES --essentials.eventstore.cdc.cdc-event-store-backfill-batch-size=1000 --essentials.eventstore.cdc.cdc-dispatcher.batch-size=200 --essentials.eventstore.cdc.cdc-dispatcher.poll-interval=PT0.05S --essentials.eventstore.cdc.wal2-json-tailer.poll-interval=PT0.025S --essentials.lab.metrics-output-file=./target/baseline-compare-defaults-long.json" \
  spring-boot:run
```

To run the same compare with `wal2json` instead of the default `pgoutput`, add:

```bash
--essentials.eventstore.cdc.plugin=wal2json
```

Summarize:

```bash
examples/essentials-performance-lab/scripts/summarize-compare.sh \
  examples/essentials-performance-lab/target/baseline-compare-defaults-long.json
```

### 1) A/B policy compare (mark-dispatched vs delete), base shape

```bash
REPEATS=3 WARMUP=PT20S DURATION=PT120S \
PRODUCER_THREADS=4 SUBSCRIBER_COUNT=2 AGGREGATE_CARDINALITY=5000 \
WAL_PARSER_MODE=BYTES \
examples/essentials-performance-lab/scripts/run-cdc-dispatched-policy-ab.sh
```

### 2) A/B policy compare, subscriber pressure (subs=5)

```bash
REPEATS=3 WARMUP=PT20S DURATION=PT120S \
PRODUCER_THREADS=4 SUBSCRIBER_COUNT=5 AGGREGATE_CARDINALITY=5000 \
WAL_PARSER_MODE=BYTES \
examples/essentials-performance-lab/scripts/run-cdc-dispatched-policy-ab.sh
```

### 3) A/B policy compare, hot aggregates (cardinality=50)

```bash
REPEATS=3 WARMUP=PT20S DURATION=PT120S \
PRODUCER_THREADS=4 SUBSCRIBER_COUNT=2 AGGREGATE_CARDINALITY=50 \
WAL_PARSER_MODE=BYTES \
examples/essentials-performance-lab/scripts/run-cdc-dispatched-policy-ab.sh
```

### 4) A/B policy compare, combined stress (subs=5, card=50)

```bash
REPEATS=3 WARMUP=PT20S DURATION=PT120S \
PRODUCER_THREADS=4 SUBSCRIBER_COUNT=5 AGGREGATE_CARDINALITY=50 \
WAL_PARSER_MODE=BYTES \
examples/essentials-performance-lab/scripts/run-cdc-dispatched-policy-ab.sh
```

View latest A/B summary:

```bash
LATEST="$(ls -td examples/essentials-performance-lab/target/cdc-dispatched-policy-ab/* | head -n 1)"
cat "$LATEST/summary.md"
```

Notes:

- Keep machine awake during long runs (sleep will skew results).
- A/B script excludes invalid runs from medians and prints reasons.

## Backpressure / slow-subscriber validation

The `backpressure` scenario exercises a deliberately-slow subscriber to validate that the CDC
pipeline's bounded buffers hold under sustained producer pressure. It reports four invariants in
its JSON output — three are correctness signals, one is a delivery-timeliness signal:

**Correctness (should always hold — block ship on failure):**
- `invariantBoundedBufferHeld`: peak `BackfillThenLiveOrdered` buffer size stayed ≤
  `essentials.eventstore.cdc.event-bus.backpressure-buffer-size` (default 8192).
- `invariantNoEventsActuallyLost`: every produced event is durably persisted in the aggregate's
  event-stream table by end of run. This is the true "no data loss" check.
- `invariantNoDispatcherTickFailures`: zero dispatcher tick failures during the run.

**Delivery-timeliness (advisory, not a bug if it fails):**
- `invariantCaughtUpWithinTimeout`: every produced event reached every subscriber before the
  catchup budget elapsed. False here means "backlog still draining when we gave up waiting" —
  typically caused by a stale inbox from a prior unclean run. Data is safe (`invariantNoEventsActuallyLost`
  still holds); delivery just hadn't finished yet. Use `RESET_CDC_STATE=true` on the matrix
  script to start from a clean baseline.

### Ad-hoc single run

```bash
mvn -q -pl examples/essentials-performance-lab -DskipTests -Dspring-boot.run.profiles=compose \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=backpressure --essentials.eventstore.cdc.enabled=true --essentials.lab.warmup=PT5S --essentials.lab.duration=PT60S --essentials.lab.producer-threads=4 --essentials.lab.subscriber-count=1 --essentials.lab.subscriber-handler-delay-ms=25 --essentials.lab.producer-rate-hz=80 --essentials.lab.aggregate-cardinality=1000 --essentials.lab.metrics-output-file=./target/backpressure-single.json" \
  spring-boot:run
```

Key knobs:

- `--essentials.lab.subscriber-handler-delay-ms=<N>` — artificial sleep (ms) inside each
  subscriber handler. `0` = baseline, `25` = moderate pressure, `100` = heavy.
- `--essentials.lab.producer-rate-hz=<N>` — target aggregate production rate across all
  producer threads (eps). `0` (default) = unthrottled. **With a slow subscriber, set this to
  roughly `2 × 1000 / handler-delay-ms`** (e.g. 80 eps for 25ms, 20 eps for 100ms) so the
  produced backlog stays drainable within the catchup budget. The scenario logs a warning
  if you forget.
- The scenario caps its catchup budget at `max(3 × duration, 120s)`. Cases that don't fully
  drain within that window fail the `invariantNoEventsLost` check and move on — they won't
  stall the matrix.

### Matrix

```bash
examples/essentials-performance-lab/scripts/run-backpressure-matrix.sh
```

Default cases sweep `subscriber-count` × `handler-delay-ms` (no-delay / light / moderate / heavy /
5-subscriber fan-out). Override `PLUGIN=wal2json`, `DELIVERY_MODE=DIRECT`, or `BUFFER_SIZE=<N>`
to exercise other configurations.

The perf-lab's `application.yml` already enables
`essentials.eventstore.cdc.slot.recreate-on-start=true`, which drops + re-creates the
replication slot at the start of every JVM. Matrix cases therefore begin with clean slot
state by default — no manual `RESET_CDC_STATE` needed.

`RESET_CDC_STATE=true` is still supported and clears the inbox table as well (useful if
an older run left inbox rows behind that the slot no longer knows about).

The script picks one of two connection paths, in preference order:

1. **Host `psql`** — if `psql` is on PATH, uses libpq env vars (`PGHOST`, `PGPORT`, `PGUSER`,
   `PGPASSWORD`, `PGDB`).
2. **Docker exec into the compose Postgres container** — if no host `psql`, falls back to
   `docker exec` into `$CDC_RESET_CONTAINER` (default `essentials-perf-lab-postgres`) using its
   `$CDC_RESET_CONTAINER_USER` / `$CDC_RESET_CONTAINER_DB` (defaults `essentials` /
   `essentials_lab`). Works out of the box with the `PROFILE=compose` stack — no host psql needed.

Override `CDC_INBOX_TABLE` / `CDC_SLOT_NAME` if you're not using defaults.

```bash
# With compose stack running (no host psql needed):
RESET_CDC_STATE=true \
examples/essentials-performance-lab/scripts/run-backpressure-matrix.sh

# With custom Postgres + host psql:
PGHOST=localhost PGPORT=5432 PGUSER=essentials PGPASSWORD=essentials PGDB=essentials_lab \
RESET_CDC_STATE=true \
examples/essentials-performance-lab/scripts/run-backpressure-matrix.sh
```

Custom cases via `CUSTOM_CASES`:

```bash
CUSTOM_CASES='smoke|1|0;heavy|1|100' \
WARMUP=PT2S DURATION=PT10S \
examples/essentials-performance-lab/scripts/run-backpressure-matrix.sh
```

Outputs per run:

- `examples/essentials-performance-lab/target/backpressure/<run-id>/summary.json`
- `examples/essentials-performance-lab/target/backpressure/<run-id>/summary.md` — per-case pass/fail
  table plus an invariant-violations section flagging any case that failed.

## Slot validation suite

Six scenarios exercise the slot / inbox / dispatcher pipeline at runtime. Five are
in-process Java `LabScenario` impls; one is a multi-step bash workflow. Each produces a
self-contained JSON or Markdown output with PASS/FAIL assertions. Designed to be run
individually during development and together via the suite scripts for end-to-end
validation.

| Scenario | Type | What it stresses | Key invariants |
|---|---|---|---|
| `slot-lag-bounded` | Java | Steady-rate writes; verifies WAL retention stays bounded | `lagBytesMax` ≤ threshold, slot drains at end, framework gauge agrees with PG |
| `slot-idle-push` | Java | Zero-write idle period | `confirmed_flush_lsn` advances purely via the P4 idle push (no producer-driven WAL) |
| `consumer-pause-recovery` | Java | Three-phase normal → dispatcher stopped → resumed | Inbox backlog grows during pause and drains after resume; dispatcher restarts cleanly |
| `poison-flood` | Java | N malformed inbox rows alongside valid stream | Poison gauge tracks the count exactly; valid delivery rate ≥ 99%; dispatcher keeps running |
| `slot-invalidation` | Java | Tightens `max_slot_wal_keep_size` + paused dispatcher to force slot loss | `wal_status` reaches non-`reserved`; `CdcAvailability` flips off; events still durable in event store |
| `pg-restart` (script) | bash | Restarts the PG container mid-run | Tailer reconnects, scenario JSON written, no JVM crash |
| `orphaned-slot` (script) | bash | Stops the JVM, watches the slot persist | `active=false`, `inactive_since_seconds` grows; `pg_drop_replication_slot` works |

### Quick start

Bring up Postgres once:

```bash
docker compose -f examples/essentials-performance-lab/docker-compose.yml up -d --build
```

Then either run scenarios individually (sections below), or hit the suite runner that
chains the four core slot scenarios:

```bash
./examples/essentials-performance-lab/scripts/run-slot-suite.sh
```

Outputs land in `target/slot-suite/<run-id>/<scenario>.json` plus a combined
`suite-summary.{json,md}`. Tear down compose when done:

```bash
docker compose -f examples/essentials-performance-lab/docker-compose.yml down -v
```

### Running individual Java scenarios

All Java scenarios share a uniform invocation shape: pick the scenario name, set CDC
on, supply a duration, point `metrics-output-file` at where you want the JSON. The
common knobs (`producer-threads`, `producer-rate-hz`, `aggregate-cardinality`,
`random-seed`) work the same as in the baseline scenarios.

```bash
mvn -q -pl examples/essentials-performance-lab -DskipTests \
    -Dspring-boot.run.profiles=compose \
    -Dspring-boot.run.arguments="\
--essentials.lab.scenario=<scenario-name> \
--essentials.eventstore.cdc.enabled=true \
--essentials.lab.duration=<ISO-8601 duration, e.g. PT60S> \
--essentials.lab.metrics-output-file=./target/<scenario>.json" \
    spring-boot:run
```

Per-scenario knobs that meaningfully change behaviour:

| Scenario | Knob | Default | Effect |
|---|---|---|---|
| `slot-lag-bounded` | `--essentials.lab.slot-lag-max-bytes` | 100 MiB | Pass-criterion threshold for peak lag |
| `slot-lag-bounded` | `--essentials.lab.slot-lag-sample-interval` | PT5S | How often to sample `pg_replication_slots` |
| `slot-lag-bounded` | `--essentials.eventstore.cdc.slot.metrics-interval` | PT30S | Set tighter (e.g. PT2S) on short runs so the framework gauge has time to refresh |
| `slot-idle-push` | `--essentials.eventstore.cdc.wal-replication-tailer.idle-lsn-push-interval` | PT30S | Set tighter (e.g. PT5S) so a 60s test sees multiple pushes |
| `consumer-pause-recovery` | `--essentials.lab.duration` | (required) | Run divided into thirds — pause is the middle third |
| `poison-flood` | `--essentials.lab.poison-flood-count` | 100 | Number of malformed rows injected at run start |
| `slot-invalidation` | `--essentials.lab.aggregate-cardinality` | 1000 | Smaller = more contention per aggregate (more conflicts, less effective WAL); 200–1000 is the sweet spot |

#### Concrete examples

```bash
# Steady-state lag test, 60s run, tight metrics interval so framework gauge refreshes
mvn -q -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.profiles=compose \
  -Dspring-boot.run.arguments="\
--essentials.lab.scenario=slot-lag-bounded \
--essentials.eventstore.cdc.enabled=true \
--essentials.eventstore.cdc.slot.metrics-interval=PT2S \
--essentials.lab.duration=PT60S \
--essentials.lab.producer-threads=4 \
--essentials.lab.producer-rate-hz=1000 \
--essentials.lab.aggregate-cardinality=5000 \
--essentials.lab.slot-lag-max-bytes=104857600 \
--essentials.lab.slot-lag-sample-interval=PT2S \
--essentials.lab.metrics-output-file=./target/slot-lag.json" \
  spring-boot:run

# Idle-push validation: 40s of idle time, 5s push cadence so we see ~8 pushes
mvn -q -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.profiles=compose \
  -Dspring-boot.run.arguments="\
--essentials.lab.scenario=slot-idle-push \
--essentials.eventstore.cdc.enabled=true \
--essentials.eventstore.cdc.wal-replication-tailer.idle-lsn-push-interval=PT5S \
--essentials.lab.duration=PT40S \
--essentials.lab.metrics-output-file=./target/idle-push.json" \
  spring-boot:run

# Pause/recovery: producer runs through all three phases, dispatcher off for the middle third
mvn -q -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.profiles=compose \
  -Dspring-boot.run.arguments="\
--essentials.lab.scenario=consumer-pause-recovery \
--essentials.eventstore.cdc.enabled=true \
--essentials.lab.duration=PT60S \
--essentials.lab.producer-threads=2 \
--essentials.lab.producer-rate-hz=200 \
--essentials.lab.metrics-output-file=./target/pause-recovery.json" \
  spring-boot:run

# Poison flood: 50 malformed inbox rows, valid stream concurrent
mvn -q -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.profiles=compose \
  -Dspring-boot.run.arguments="\
--essentials.lab.scenario=poison-flood \
--essentials.eventstore.cdc.enabled=true \
--essentials.lab.poison-flood-count=50 \
--essentials.lab.duration=PT30S \
--essentials.lab.producer-threads=2 \
--essentials.lab.producer-rate-hz=200 \
--essentials.lab.metrics-output-file=./target/poison.json" \
  spring-boot:run

# Slot invalidation: DESTRUCTIVE. Tightens max_slot_wal_keep_size + stops dispatcher.
# 60s usually enough to push past the 4 MiB bound. Slot ends invalidated; recreate-on-start
# in application.yml handles the next JVM restart.
mvn -q -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.profiles=compose \
  -Dspring-boot.run.arguments="\
--essentials.lab.scenario=slot-invalidation \
--essentials.eventstore.cdc.enabled=true \
--essentials.lab.duration=PT60S \
--essentials.lab.aggregate-cardinality=200 \
--essentials.lab.metrics-output-file=./target/slot-invalidation.json" \
  spring-boot:run
```

### Running the bash-driven scenarios

These wrap the Java scenarios with external lifecycle steps (Docker actions on the PG
container) that can't be expressed cleanly in-process. Each script uses env variables
for tuning — defaults are sensible for a quick smoke.

#### `pg-restart` — survive a Postgres restart mid-run

```bash
# Default: 90s scenario, restart at t=30s
./examples/essentials-performance-lab/scripts/run-pg-restart.sh

# Tunable
DURATION=PT120S RESTART_AT_S=45 RATE_HZ=500 \
  ./examples/essentials-performance-lab/scripts/run-pg-restart.sh
```

What happens:

1. The script verifies the PG container is up.
2. Schedules a background `docker restart` of `essentials-perf-lab-postgres` after
   `RESTART_AT_S` seconds.
3. Runs `slot-lag-bounded` against the live PG.
4. The tailer's reconnect loop picks up the post-restart PG; the scenario keeps
   running.
5. Outputs `target/pg-restart/<run-id>/scenario.json` plus `restart-log.txt` showing
   exactly when the restart fired.

Note: a `verdict=FAIL` here often just means the scenario's strict drain criterion
(lag drained to ≤ 50% of peak) couldn't be met in the budget after the restart cost
some seconds — the fact that the JSON was written at all is itself the meaningful
signal that the tailer reconnected. Inspect `lagBytesEnd` + `walStatusEnd` for the
actual story.

#### `orphaned-slot` — operator runbook validation

```bash
# Default: 15s workload, 30s grace, leaves the slot in place for inspection
./examples/essentials-performance-lab/scripts/run-orphaned-slot.sh

# Auto-cleanup at end (drops any leftover essentials_* slots)
CLEANUP=true ./examples/essentials-performance-lab/scripts/run-orphaned-slot.sh

# Longer grace to watch inactive_since climb
GRACE_S=300 ./examples/essentials-performance-lab/scripts/run-orphaned-slot.sh
```

What happens:

1. Snapshots `pg_replication_slots` before the JVM starts (`slot-state-pre.json`).
2. Runs the lab app for `WORKLOAD_DURATION` to provision and use the slot.
3. Snapshots immediately after the JVM exits (`slot-state-post.json`).
4. Sleeps `GRACE_S` seconds and snapshots again (`slot-state-after-grace.json`).
5. Writes `run-summary.md` with the lifecycle table and the operator's drop-slot SQL.

Validates the cdc.md §13.1 promise: PostgreSQL keeps the slot around indefinitely after
the consumer disappears; the operator must drop it manually. A successful run produces
a summary with `inactive_seconds` growing approximately in lockstep with `GRACE_S`.

### Running the full suite

The `run-slot-suite.sh` script chains the four core in-process scenarios back-to-back
and writes a single combined verdict file:

```bash
# Full default suite (~4 min)
./examples/essentials-performance-lab/scripts/run-slot-suite.sh

# Lighter / faster
LAG_DURATION=PT30S IDLE_DURATION=PT30S PAUSE_DURATION=PT30S POISON_DURATION=PT15S \
  ./examples/essentials-performance-lab/scripts/run-slot-suite.sh

# Heavier / longer (overnight CI)
LAG_DURATION=PT300S IDLE_DURATION=PT300S PAUSE_DURATION=PT300S POISON_DURATION=PT60S \
RATE_HZ=2000 \
  ./examples/essentials-performance-lab/scripts/run-slot-suite.sh
```

Note that `run-slot-suite.sh` does **not** include `slot-invalidation`, `pg-restart`,
or `orphaned-slot`. Those have side effects (server-side config changes, container
restarts, leftover slots) that don't fit "run a clean test suite back-to-back". Run
them individually when you want to validate those specific concerns.

### Output JSON schemas at a glance

Each scenario's JSON output is shaped to be self-describing — `verdict` is always
`PASS`/`FAIL` so a one-liner like `jq -r '.verdict'` works across all of them. The
key fields per scenario:

| Scenario | Key fields |
|---|---|
| `slot-lag-bounded` | `verdict`, `lagBytesMax`, `lagBytesEnd`, `walStatusEnd`, `frameworkVsPgDriftPct`, `samples[]` |
| `slot-idle-push` | `verdict`, `confirmedFlushLsnAdvanced`, `idlePushObserved`, `pre`/`afterSeed`/`post` |
| `consumer-pause-recovery` | `verdict`, `peakBacklog`, `finalBacklog`, `dispatcherRestartedCleanly`, `samples[]` |
| `poison-flood` | `verdict`, `poisonInjected`, `poisonRowsAtEnd`, `producedValidEvents`, `deliveredValidEvents` |
| `slot-invalidation` | `verdict`, `walStatusDegraded`, `availabilityFlipped`, `pre`/`mid`/`post`, `runException` |

Existing utility scripts (`summarize-compare.sh`) currently key off the baseline
scenario shape; they don't pick up these slot-suite JSONs natively. Use the
suite-summary `.md` instead, or grep `verdict` directly.

### Multi-instance chaos scenarios

The following two scenarios run two app JVMs concurrently against the same PG to
exercise the framework's advisory-lock leader election. Activated via Docker
Compose's `chaos` profile and a containerised app image.

| Scenario | Type | What it stresses | Key invariants |
|---|---|---|---|
| `multi-tailer-leadership` (script) | bash + 2 containers | Steady state with both apps + graceful stop | exactly-one active streamer; failover on SIGTERM is near-instant |
| `tailer-kill-failover` (script) | bash + 2 containers | SIGKILL the leader | failover under SIGKILL within `wal_sender_timeout` (default 60s) |

#### One-time setup: build the app image

The chaos profile needs a runnable Docker image. The `Dockerfile` in this directory
copies a pre-built fat JAR into a slim runtime base — Maven runs on the host (uses
your local repo cache, no repeat downloads), Docker just adds a `COPY` step.

```bash
./examples/essentials-performance-lab/scripts/build-app-image.sh
```

Subsequent rebuilds after a code change: same command (the script does
`mvn package` + `docker build`).

If you only changed the Dockerfile / compose YAML and don't need a fresh JAR:

```bash
SKIP_MVN=true ./examples/essentials-performance-lab/scripts/build-app-image.sh
```

#### Run the scenarios

Both scripts spin up the chaos profile (`perf-lab-app-1` + `perf-lab-app-2`),
observe the slot, perform the destructive action, measure failover, and tear
down. PG must already be running.

```bash
# Bring up PG once
docker compose -f examples/essentials-performance-lab/docker-compose.yml up -d --build

# Build the app image (one-time per code change)
./examples/essentials-performance-lab/scripts/build-app-image.sh

# Graceful failover (SIGTERM via docker stop)
./examples/essentials-performance-lab/scripts/run-multi-tailer-leadership.sh

# Hard failover (SIGKILL via docker kill)
./examples/essentials-performance-lab/scripts/run-tailer-kill-failover.sh
```

Tunable env variables:

| Variable | Default | Effect |
|---|---|---|
| `OBSERVATION_S` (multi-tailer) | 20 | Seconds to verify exactly-one-active before failover |
| `FAILOVER_TIMEOUT_S` (multi-tailer) | 15 | Budget for graceful-stop failover |
| `HOLD_S` (kill-failover) | 10 | Seconds to confirm leader is stable before SIGKILL |
| `FAILOVER_TIMEOUT_S` (kill-failover) | 90 | Budget under SIGKILL — bounded by `wal_sender_timeout` (default 60s) |
| `CHAOS_DURATION` (compose env) | PT3600S | Long enough that the script controls when the JVMs exit |
| `CHAOS_RATE_HZ` (compose env) | 50 | Light producer load — enough to keep the tailer connected, not so much it swamps a laptop |
| `CHAOS_SCENARIO` (compose env) | slot-lag-bounded | Which scenario the chaos containers run |

Outputs:

- `target/multi-tailer-leadership/<run-id>/{summary.md, timeline.txt, samples.csv}`
- `target/tailer-kill-failover/<run-id>/{summary.md, timeline.txt}`

Each `summary.md` contains a per-invariant verdict table plus the full timeline so
you can see exactly when each phase started, when the kill/stop fired, and how long
failover took.

#### Observed failover times (reference)

On a healthy compose setup these scenarios should produce:

| Scenario | Typical failover time | Why |
|---|---|---|
| `multi-tailer-leadership` (graceful) | 1–10 s | Spring Boot shutdown hook closes the JDBC connection; PG releases the advisory lock immediately; standby acquires on next backoff retry (250 ms – 5 s). |
| `tailer-kill-failover` (SIGKILL) | 1–60 s | TCP RST + `wal_sender_timeout` (default 60 s). Docker's hard kill closes the socket cleanly so PG often detects within seconds; on a real host network the full 60 s budget can apply. |

A `verdict=FAIL` here usually means either:
- `app-1` didn't reach `Started` before the test killed it (Spring cold-start
  exceeded `start_period`). Crank `CHAOS_DURATION` and the healthcheck
  `start_period`, or rebuild the image after a `mvn clean`.
- `wal_sender_timeout` is set higher than the test budget. Check
  `SHOW wal_sender_timeout;` in PG; tune the script's `FAILOVER_TIMEOUT_S`
  accordingly.

#### Architecture notes

- Both apps run with `essentials.eventstore.cdc.slot.recreate-on-start=true`
  inherited from `application.yml`. The `firstStreamAttempt` AtomicBoolean ensures
  the recreate only fires on each JVM's *first* successful lock acquisition; after
  the standby takes over, it does its own recreate then streams. Subscribers stay
  correct via the polling fallback during the cutover.
- Both compose services run with no restart policy and no inter-app dependency
  — both JVMs start truly concurrently. The framework's
  `PostgresqlUtil.acquireBootstrapLock(handle)` (delivered as **P6** in
  [cdc-improvements.md](../../components/postgresql-event-store/src/main/java/dk/trustworks/essentials/components/eventsourced/eventstore/postgresql/cdc/cdc-improvements.md))
  serializes their `CREATE TABLE IF NOT EXISTS` calls behind a transaction-scoped
  PG advisory lock, so the loser of the bootstrap race waits a few ms then sees
  the table exists and proceeds normally. Prior to P6 the compose profile used
  `restart: on-failure:5` to mirror the K8s-equivalent self-heal behaviour; that
  workaround is no longer needed.
- The chaos scripts identify which container holds the leader by looking up
  `pg_stat_activity.client_addr` against each container's docker-network IP. That
  removes a hardcoded "leader = app-1" assumption and works regardless of which
  container starts first.

## Slot-lag / WAL retention validation

The `slot-lag-bounded` scenario drives a steady-rate write workload for the configured
`duration` and samples `pg_replication_slots` every `slot-lag-sample-interval` (default
`PT5S`). Verifies five invariants in its JSON output:

| Field | Pass when… |
|---|---|
| `lagBoundedOk` | `lagBytesMax` ≤ `essentials.lab.slot-lag-max-bytes` (default 100 MiB) |
| `lagDrainedOk` | `lagBytesEnd` ≤ `lagBytesMax / 2` — the slot drained at run-end |
| `walStatusOk`  | `pg_replication_slots.wal_status` = `reserved` throughout |
| `deliveryOk`   | `produced` = `delivered` — no event loss via CDC |
| `driftOk`      | framework's `essentials.cdc.slot.lag_bytes` gauge agrees with PG within 5% |

The `driftOk` check is the canary: if our P1 gauges ever lie about retention, this
scenario catches it before operators rely on a wrong number in production dashboards.

### Ad-hoc single run

```bash
mvn -q -pl examples/essentials-performance-lab -DskipTests -Dspring-boot.run.profiles=compose \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=slot-lag-bounded \
--essentials.eventstore.cdc.enabled=true \
--essentials.lab.duration=PT120S --essentials.lab.producer-threads=4 \
--essentials.lab.producer-rate-hz=1000 --essentials.lab.aggregate-cardinality=5000 \
--essentials.lab.slot-lag-max-bytes=104857600 --essentials.lab.slot-lag-sample-interval=PT5S \
--essentials.lab.metrics-output-file=./target/slot-lag-single.json" \
  spring-boot:run
```

Output JSON includes `pre`/`post` slot snapshots, the full `samples` time-series, and the
five assertion booleans plus an aggregate `verdict` (PASS/FAIL).

### Matrix

```bash
docker compose -f examples/essentials-performance-lab/docker-compose.yml up -d --build
./examples/essentials-performance-lab/scripts/run-slot-lag-matrix.sh
```

Sweeps `idle-lsn-push-interval`, `slot.metrics-interval`, and dispatcher batch size. Outputs:

- `target/slot-lag/<run-id>/<case>.json` — full per-case scenario output
- `target/slot-lag/<run-id>/summary.json` + `summary.md` — verdict + lag table per case,
  plus a recommended slot-tuning profile (lowest sustained avg lag among PASS cases).

### Progress heartbeat

Long drain phases can take minutes. To keep the operator informed the scenario emits a
grep-friendly progress line every 10 seconds to both the logger and stdout:

```
[backpressure] progress phase=catchup elapsedS=47 delivered=2400 peakBuffer=80 peakInboxBacklog=1560 tickFailures=0 remainingBudgetS=133
```

The matrix script tails this line every 15 seconds (override via `HEARTBEAT_INTERVAL_S`) and
prints it alongside the wall-clock elapsed time for the current case, so you never have to
`tail -f` the per-case log file to know the run is healthy.

### Observability

The scenario reads these meters live during the run (every 100ms) and surfaces peaks in the
per-case JSON:

- `essentials.cdc.backfill_live.buffer.size` — gauge for the in-flight live-event buffer inside
  `BackfillThenLiveOrdered`. Peak is compared against the configured bound.
- `essentials.cdc.dispatcher.tick.failures` / `.conversion.failures` / `.poison.rows` — counter
  deltas over the measurement window.
- Inbox `RECEIVED` backlog — a direct `COUNT(*)` against the inbox table (0 in DIRECT mode).

## Next planned scenarios

1. `baseline-polling` vs `cdc-hybrid` throughput/latency matrix
2. exclusive/non-exclusive subscriber topology matrix
3. ordered/unordered durable queue matrix
4. inbox/outbox load profile with mixed command/event workloads
