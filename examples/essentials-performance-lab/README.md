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

## Local Postgres + wal2json (docker compose)

Start local Postgres with `wal2json` plugin:

```bash
docker compose -f examples/essentials-performance-lab/docker-compose.yml up -d --build
```

Run the app against compose DB (port `55432`):

```bash
mvn -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.profiles=compose \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc-compare --essentials.eventstore.cdc.enabled=false --essentials.lab.metrics-output-file=./target/baseline-compare.json" \
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

## Baseline CDC vs polling runs

Run with CDC enabled:

```bash
mvn -pl examples/essentials-performance-lab -DskipTests \
  -Dspring-boot.run.arguments="--essentials.lab.scenario=baseline-polling-vs-cdc --essentials.eventstore.cdc.enabled=true --essentials.lab.metrics-output-file=./target/baseline-cdc.json" \
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

This smoke test does not require `wal2json` to be installed in the container; CDC path is exercised in `auto` fallback semantics for robustness.

## Benchmark matrix scripts

Two helper scripts are included under `examples/essentials-performance-lab/scripts`:

- `run-baseline-matrix.sh`: fixed small matrix over subscriber/cardinality shapes
- `run-cdc-tuning-matrix.sh`: CDC tuning matrix over dispatcher/backfill/tailer knobs
- `run-cdc-dispatched-policy-ab.sh`: repeated A/B compare of `mark-dispatched` vs `delete` inbox row policy

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

Outputs are written per run to:

- `examples/essentials-performance-lab/target/cdc-dispatched-policy-ab/<run-id>/summary.json`
- `examples/essentials-performance-lab/target/cdc-dispatched-policy-ab/<run-id>/summary.md`

## Next planned scenarios

1. `baseline-polling` vs `cdc-hybrid` throughput/latency matrix
2. exclusive/non-exclusive subscriber topology matrix
3. ordered/unordered durable queue matrix
4. inbox/outbox load profile with mixed command/event workloads
