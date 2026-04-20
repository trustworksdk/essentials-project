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
- a default `pgoutput` publication:
  - `essentials_cdc_publication`

Start local Postgres:

```bash
docker compose -f examples/essentials-performance-lab/docker-compose.yml up -d --build
```

The default CDC plugin is `pgoutput`, and the default publication name is already configured as:

- `essentials.eventstore.cdc.pg-output.publication-name=essentials_cdc_publication`

So a compose-backed run works out of the box with the default CDC plugin. In the EventStore CDC path, `pgoutput` only converts insert messages for configured aggregate event tables; non-insert replication messages are ignored.

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
pipeline's bounded buffers hold under sustained producer pressure. It reports three pass/fail
invariants in its JSON output:

- `invariantBoundedBufferHeld`: peak `BackfillThenLiveOrdered` buffer size stayed ≤
  `essentials.eventstore.cdc.event-bus.backpressure-buffer-size` (default 8192).
- `invariantNoEventsLost`: every produced event was eventually delivered to every subscriber.
- `invariantNoDispatcherTickFailures`: zero dispatcher tick failures during the run.

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
to exercise other configurations. Custom cases via `CUSTOM_CASES`:

```bash
CUSTOM_CASES='smoke|1|0;heavy|1|100' \
WARMUP=PT2S DURATION=PT10S \
examples/essentials-performance-lab/scripts/run-backpressure-matrix.sh
```

Outputs per run:

- `examples/essentials-performance-lab/target/backpressure/<run-id>/summary.json`
- `examples/essentials-performance-lab/target/backpressure/<run-id>/summary.md` — per-case pass/fail
  table plus an invariant-violations section flagging any case that failed.

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
