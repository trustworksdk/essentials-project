# Queue Fetch Strategy Benchmark

This benchmark compares:

- `fetchNextBatchOfMessages` (per-queue fetching: one query per active queue)
- `fetchNextBatchOfMessagesBatched` (a single query spanning all active queues)

Implementation: `components/postgresql-queue/src/test/java/dk/trustworks/essentials/components/queue/postgresql/benchmark/QueueFetchStrategyBenchmarkIT.java`

## Result and resulting default

The benchmark showed batched fetching starting to pay off above roughly **four** active queues; below that,
per-queue fetching is as fast or faster and the single large query buys nothing.

That figure is the default value of `batchedFetchSwitchThreshold`. It is **not** enabled by default:
batched fetching is opt-in, because the two strategies do not select the same messages.

```java
PostgresqlDurableQueues.builder()
                       .setUseCentralizedMessageFetcher(true)
                       .setUseBatchedFetch(true)              // opt in; off by default
                       .setBatchedFetchSwitchThreshold(4)     // default; per-queue at or below, batched above
                       .build();
```

Spring Boot:

```properties
essentials.durable-queues.use-batched-fetch=true
essentials.durable-queues.batched-fetch-switch-threshold=4
```

### Behaviour difference to be aware of before enabling

Per-queue fetching is **ordered-priority**: it runs the ordered query first and only falls back to the
unordered query when the ordered query returned nothing. Batched fetching numbers ordered and unordered
candidates in a single **oldest-first** window, capped at the queue's available worker slots.

So on a queue carrying both ordered and unordered messages, enabling batched fetching changes which messages
get delivered, not just how they are queried. Ordered messages lose their priority; in exchange, unordered
messages can no longer starve behind a steady stream of ordered ones. Per-key ordering guarantees are
unaffected - the per-key barrier applies under both strategies.

## Run

From repository root:

```bash
mvn -f components/postgresql-queue/pom.xml \
  -Dbenchmark.run=true \
  -Dtest=QueueFetchStrategyBenchmarkIT \
  test
```

## Fast smoke run

```bash
mvn -f components/postgresql-queue/pom.xml \
  -Dbenchmark.run=true \
  -Dbenchmark.queueCounts=1,2,4 \
  -Dbenchmark.messagesPerQueue=1,5 \
  -Dbenchmark.workerSlots=1 \
  -Dbenchmark.excludedKeys=0 \
  -Dbenchmark.warmupIterations=1 \
  -Dbenchmark.measureIterations=2 \
  -Dtest=QueueFetchStrategyBenchmarkIT \
  test
```

## Full matrix example

```bash
mvn -f components/postgresql-queue/pom.xml \
  -Dbenchmark.run=true \
  -Dbenchmark.queueCounts=1,2,4,8,16,32,64,128 \
  -Dbenchmark.messagesPerQueue=1,5,20 \
  -Dbenchmark.workerSlots=1,4 \
  -Dbenchmark.excludedKeys=0,10 \
  -Dbenchmark.warmupIterations=3 \
  -Dbenchmark.measureIterations=8 \
  -Dbenchmark.outputCsv=target/queue-fetch-strategy-benchmark.csv \
  -Dtest=QueueFetchStrategyBenchmarkIT \
  test
```

## Output

CSV path (default):

`target/queue-fetch-strategy-benchmark.csv` (module-relative, resolves to `components/postgresql-queue/target/...` when run from repo root with `-f components/postgresql-queue/pom.xml`)

Columns include:

- scenario dimensions (`queue_count`, `messages_per_queue`, `worker_slots_per_queue`, `excluded_keys_per_queue`)
- latency stats (`per_queue_avg_ms`, `per_queue_p95_ms`, `batched_avg_ms`, `batched_p95_ms`)
- row stats (`*_avg_rows`, `batched_avg_unique_rows`, `batched_avg_dedup_collisions`, `batched_avg_dedup_ratio`).
  The batched query cannot return a `QueueEntryId` twice, so `batched_avg_dedup_ratio` is expected to be
  exactly `1.0`; anything else indicates a regression in the batched statement.
- simple winner (`winner`)

## Post-process (find threshold N)

Script:

`components/postgresql-queue/scripts/analyze-queue-fetch-strategy-benchmark.sh`

Run:

```bash
components/postgresql-queue/scripts/analyze-queue-fetch-strategy-benchmark.sh \
  components/postgresql-queue/target/queue-fetch-strategy-benchmark.csv
```

Optional tuning via env vars:

```bash
MIN_IMPROVEMENT=0.10 MAX_DEDUP_RATIO=1.25 MIN_PASS_RATIO=0.60 \
components/postgresql-queue/scripts/analyze-queue-fetch-strategy-benchmark.sh \
  components/postgresql-queue/target/queue-fetch-strategy-benchmark.csv
```
