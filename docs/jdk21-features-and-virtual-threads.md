# JDK 17+ language features and virtual threads in Essentials

Status: analysis and proposed plan. Nothing in here has been applied to `components/` or the core modules —
the only code changes that accompany this document are the measurement harness in
`examples/essentials-performance-lab` that produced the numbers below.

## 1. Where the codebase stands today

The reactor compiles `--release 21` (`java.release.version=21`) and builds on JDK 21–25
(`java.build.version=25`, enforcer range `[21,26)`). Every language feature through Java 21 is therefore
already available without any build change: records, sealed types, pattern matching for `instanceof` and
`switch`, record patterns, text blocks, sequenced collections, and virtual threads.

Adoption across the 4043 `src/main` Java files outside `examples/`:

| Feature | Files using it | Read |
|---|---|---|
| Records | 106 | Established idiom |
| Pattern-matching `instanceof` | 181 occurrences (vs 245 without a pattern variable) | Roughly 43% converted |
| `switch` with arrow form / patterns | 22 | Sparse |
| Text blocks | 17 | Partial — 36 lines of concatenated SQL remain |
| Sealed types | 3 | Essentially unused |
| Sequenced collections (`getFirst`/`getLast`/`SequencedCollection`) | 0 | Unused; 247 `.get(0)` and 32 `.get(size()-1)` occurrences |
| Virtual threads / structured concurrency / scoped values | 0 | Unused |

Two constraints shape everything below, both from `CLAUDE.md`:

- **Stable central APIs.** Breaking changes only in a new major; patch and minor releases are additive.
- **Deprecate, never delete.** An API being replaced stays, marked `@Deprecated(forRemoval = true, since = …)`,
  re-implemented to delegate.

## 2. Virtual threads

### 2.1 Where threads actually live

Thread-creating sites in `src/main` (excluding tests and examples):

| Site | Shape | Count of threads |
|---|---|---|
| `CentralizedMessageFetcherDurableQueueConsumer` | worker pool, `submit(Runnable)` only | `parallelConsumers` **per queue** |
| `DefaultDurableQueueConsumer` | `scheduleAtFixedRate` × `parallelConsumers` | `parallelConsumers` per consumer |
| `CentralizedMessageFetcher` | single poll thread per `DurableQueues` instance | 1 |
| `DBFencedLockManager` | lock confirmation + async acquiring | 1 + 2 |
| `MultiTableChangeListener` | dedicated LISTEN/NOTIFY connection | 1 |
| `CdcDispatcher`, `CdcSlotMetrics`, `CdcEffectivenessMonitor`, `WalReplicationTailer` | one dedicated thread each | 1 each |
| `DefaultEssentialsScheduler` | scheduled pool | configured |
| `AsyncAggregateSnapshotRepository`, `DurableAsyncSnapshotManager` | worker pools for snapshot writes | configured |
| `LocalCommandBus`, `BatchedPersistedEventSubscriber`, `DefaultEventStoreSubscriptionManager`, `EventStoreSubscriptionMonitorManager`, `ClosingBooksManager` | single-thread schedulers | 1 each |

Only one of these scales with workload rather than with configuration: the durable-queue consumer worker
pool. Every other site is a fixed, small number of long-lived threads, where the thread implementation is
irrelevant — a virtual thread parked forever on a replication socket costs the same as a platform thread
doing the same thing, minus a stack, and gains nothing.

The consumer worker pool is different because **each queue gets its own pool**
(`CentralizedMessageFetcherDurableQueueConsumer` constructor: `Executors.newScheduledThreadPool(parallelConsumers, …)`).
An application with 50 queues at 10 parallel consumers each holds 500 platform threads whose only job is to
block, even though a single shared `CentralizedMessageFetcher` thread feeds all of them.

Pinning is not a concern: there are 12 `synchronized` occurrences across all of `src/main`, none in the
queue-consumption path, and JEP 491 (JDK 24) removed `synchronized` pinning anyway.

### 2.2 What was measured, and how

`examples/essentials-performance-lab` gained a `virtual-threads-queue` scenario
(`VirtualThreadsQueueScenario`) that A/Bs the consumer worker pool with everything else held constant.

The comparison is clean for a specific structural reason. With the default
`useCentralizedMessageFetcher=true`, the worker pool does **not** bound concurrency —
`CentralizedMessageFetcher.calculateAvailableWorkerSlotsPerQueue()` does, via `maxParallelConsumers - activeWorkers`.
The pool only supplies threads for `submit(...)`. Both arms therefore admit exactly `parallelConsumers`
messages in flight and differ only in thread implementation.

Two handler shapes are measured, because reporting either alone would mislead:

- **SLEEP** — handler blocks without holding a pooled JDBC connection (the external-HTTP-call shape).
- **DB** — handler blocks inside a unit of work via `pg_sleep`, holding a Hikari connection throughout
  (the read-or-write-the-database shape).

Method: a fixed burst is queued up front, the consumer starts, the queue drains, and drain time, throughput,
latency percentiles, JVM peak thread count and process RSS delta are recorded. Bursts scale to at least 8
messages per slot so high-parallelism cases get a steady state rather than pure ramp-up. Executor kinds
alternate inside each repetition rather than running in blocks, so drift over the run cannot land on one arm.

**Repetitions are not optional here.** The first pass ran one sample per configuration and produced a
consistent 15–20% virtual-thread *penalty*; a follow-up single-sample run of the same configuration produced
a 1.8× virtual-thread *win*. The scenario now reduces repetitions to a median and reports the observed range
next to it, and flags `speedupWithinNoise` when the two arms' ranges overlap. Numbers below are medians of 5
repetitions with the range in brackets.

Environment: Temurin 25.0.4 (aarch64), 14 available processors, PostgreSQL 17.5 in Testcontainers on Docker,
handler delay 50 ms. Absolute figures are specific to this host and are not production numbers; the ratios
within a single run are the result.

Reproduce with:

```bash
JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64 ./mvnw verify -pl examples/essentials-performance-lab \
  -Dbenchmark.run=true -Dit.test=VirtualThreadsQueueBenchmarkIT \
  -Dvt.parallelConsumers=8,32,128 -Dvt.handlerMode=SLEEP -Dvt.repetitions=5 -Dvt.poolSize=100
```

### 2.3 Result 1 — the queue consumer is connection-bound, not thread-bound

Throughput in messages/second, platform-thread arm, medians of 5 repetitions:

| Handler | `parallelConsumers` | Hikari pool 10 | Hikari pool 40 | Hikari pool 100 |
|---|---|---|---|---|
| SLEEP | 8 | 94.0 | — | 90.5 |
| SLEEP | 32 | 134.6 | 234.6 | 270.1 |
| SLEEP | 128 | 132.6 | 448.1 | 707.2 |
| DB | 8 | 89.4 | — | 89.6 |
| DB | 32 | 121.9 | — | 237.4 |
| DB | 128 | 137.8 | — | 556.8 |

Raising `parallelConsumers` from 32 to 128 at pool 10 buys nothing in either handler shape (SLEEP
134.6 → 132.6; DB 121.9 → 137.8). Raising the connection pool from 10 to 100 at 128 parallel consumers gives
**5.3×** for SLEEP and **4.0×** for DB. The scarce resource is the JDBC connection, not the thread.

The most telling comparison is between the two handler shapes at pool 10: they are within about 5% of each
other across the whole sweep. Whether the handler holds a connection for its 50 ms of work or not barely
matters, because the framework's own per-message round trips — the fetcher's batch fetch, and
`acknowledgeMessageAsHandled` per message — already saturate a 10-connection pool on their own. The handler
is not what makes the workload connection-bound; the queue implementation is.

This is the single most actionable finding in this document, and it has nothing to do with virtual threads:
**`parallelConsumers` is only meaningful up to the connection pool size.** Today nothing in the framework
says so, and nothing warns when a consumer is configured past it.

### 2.4 Result 2 — virtual threads cost throughput on this workload

Medians of 5 repetitions, [min–max]. "Ranges overlap" means the two arms' observed ranges intersect, i.e.
the difference between the medians is not distinguishable from either arm's own variance.

| Handler | Pool | `parallelConsumers` | Platform msg/s | Virtual msg/s | Ratio | Ranges overlap |
|---|---|---|---|---|---|---|
| SLEEP | 10 | 8 | 94.0 [92–95] | 60.8 [57–64] | 0.65× | no |
| SLEEP | 10 | 32 | 134.6 [129–136] | 102.3 [100–111] | 0.76× | no |
| SLEEP | 10 | 128 | 132.6 [124–137] | 105.3 [102–109] | 0.79× | no |
| SLEEP | 100 | 8 | 90.5 [88–95] | 60.7 [60–68] | 0.67× | no |
| SLEEP | 100 | 32 | 270.1 [238–274] | 167.0 [138–175] | 0.62× | no |
| SLEEP | 100 | 128 | 707.2 [550–760] | 585.5 [512–611] | 0.83× | **yes** |
| DB | 10 | 8 | 89.4 [84–92] | 73.9 [72–80] | 0.83× | no |
| DB | 10 | 32 | 121.9 [119–131] | 116.4 [112–127] | 0.96× | **yes** |
| DB | 10 | 128 | 137.8 [135–139] | 126.5 [114–130] | 0.92× | no |
| DB | 100 | 8 | 89.6 [82–92] | 74.7 [69–79] | 0.83× | no |
| DB | 100 | 32 | 237.4 [229–266] | 193.1 [192–205] | 0.81× | no |
| DB | 100 | 128 | 556.8 [480–611] | 584.8 [467–708] | 1.05× | **yes** |

Virtual threads are slower in 11 of 12 configurations, and in 8 of those the ranges do not overlap, so the
penalty is a real effect on this workload rather than sampling noise. It is milder in the DB shape
(0.81–1.05×) than the SLEEP shape (0.62–0.83×), which is consistent with the DB shape being more thoroughly
connection-bound — the more the connection pool dictates the outcome, the less the thread type can affect it.
The one configuration where virtual came out ahead (DB, pool 100, p128, 1.05×) has heavily overlapping
ranges and should be read as a tie.

The mechanism behind the penalty was not isolated. The plausible contributors are per-message virtual thread
creation (the virtual arm creates a thread per message; the platform arm reuses a fixed pool) and scheduling
overhead that does not pay for itself when throughput is bounded elsewhere. That investigation was not
pursued because no recommendation below depends on the answer.

### 2.5 Result 3 — virtual threads flatten the thread footprint completely

JVM peak thread count during the case, SLEEP handler:

| `parallelConsumers` | Platform arm | Virtual arm |
|---|---|---|
| 8 | 46 | 37 |
| 32 | 70 | 38 |
| 128 | 166 | 38 |
| 512 | 549 | 37 |

The 8/32/128 rows are the maximum across the 5 repetitions; the 512 row comes from the wider
single-repetition sweep, which is adequate here because thread counts — unlike throughput — barely vary
between repetitions.

The virtual arm is flat at ~37 platform threads — the carrier pool plus fixed application infrastructure —
while the platform arm grows one-for-one with `parallelConsumers`. This is the reproducible, unambiguous
virtual-thread benefit, and it is a *resource* benefit, not a speed one.

Note the corollary at low parallelism: at `parallelConsumers=2` the virtual arm used *more* platform threads
than the platform arm (34 vs 22), because the carrier ForkJoinPool ramps toward `availableProcessors`
regardless. Virtual threads are only a footprint win once the configured concurrency exceeds the core count.

### 2.6 A concrete API gap found while building the harness

`ConsumeFromQueue.getConsumerExecutorService()` and `ConsumeFromQueueBuilder.setConsumerExecutorService(…)`
are typed `ScheduledExecutorService`. `Executors.newVirtualThreadPerTaskExecutor()` returns a plain
`ExecutorService`, and the JDK has no virtual-thread-backed `ScheduledExecutorService` as of Java 25.
**A consumer therefore cannot be handed a virtual-thread executor through the public builder at all** — the
scenario needed `VirtualThreadScheduledExecutorAdapter` to run the experiment.

The wide type is only needed by the legacy `DefaultDurableQueueConsumer`, which genuinely calls
`scheduleAtFixedRate`. The centralized path — the default since `useCentralizedMessageFetcher=true` — stores
the value in an `ExecutorService` field and calls nothing but `submit(Runnable)`. One consumer implementation's
requirement is constraining the other's public surface.

Note also that `scheduleAtFixedRate` cannot simply be emulated on virtual threads:
`ScheduledThreadPoolExecutor` guarantees successive runs of the same periodic task never overlap, and
`DefaultDurableQueueConsumer` depends on that — it schedules the same `pollQueue` runnable N times precisely
to get N non-overlapping pollers. The adapter throws on the periodic methods for that reason rather than
silently changing the concurrency contract.

### 2.7 Recommendations on virtual threads

**Do not switch any thread pool to virtual threads by default.** The measured evidence does not support it:
the one pool that scales with workload is bounded by the connection pool, and across 12 measured
configurations virtual threads cost throughput in 11 of them (0.62×–1.05×, median around 0.83×) to buy a
thread-count reduction that most deployments do not need.

Recommended, in priority order:

1. **Document and enforce the `parallelConsumers` ≤ connection-pool-size relationship** (§2.3). This is worth
   more than any threading change. Concretely: log a warning at consumer start when the sum of
   `parallelConsumers` across registered consumers exceeds the pool size, and say so in the durable-queues
   documentation. Additive, no API change.
2. **Widen the executor knob additively so virtual threads become possible for those who want them.** Add
   `ConsumeFromQueueBuilder.setConsumerWorkerExecutorService(ExecutorService)` and a matching
   `getConsumerWorkerExecutorService()` returning `Optional<ExecutorService>`, keeping the existing
   `ScheduledExecutorService` pair delegating to it and marked `@Deprecated(forRemoval = true)`. The
   centralized consumer reads the new one; `DefaultDurableQueueConsumer` keeps requiring the scheduled
   variant and fails fast with a clear message if only the plain one is supplied. This satisfies both the
   additive-API rule and the deprecate-never-delete rule.
3. **Ship the adapter, or the pattern, in `shared`** — with the periodic-method restriction intact and
   documented — so consumers do not each hand-roll a broken one. Optional; only worth doing after (2).
4. **Leave every fixed-thread site alone**: the CDC dispatcher/tailer/metrics threads, fenced-lock threads,
   `MultiTableChangeListener`, the scheduler, and the snapshot worker pools. All are either single-threaded,
   scheduling-dependent, or database-bound, and (2.3) shows database-bound work does not benefit.
5. **Revisit only if the workload changes shape.** If Essentials ever grows message handlers that block on
   non-database resources at high fan-out — an outbound HTTP gateway, say — the footprint result in §2.5
   becomes the deciding factor and this should be re-measured with that handler shape.

Explicitly not recommended: structured concurrency (`StructuredTaskScope`) and scoped values. Neither is
final in Java 21, the release the reactor targets, and no site in the codebase currently fans out
subtasks that need joined lifetimes.

## 3. Language features

None of this is performance work — the JIT compiles a pattern-matching `switch` and an `if`/`else if` chain
to equivalent code. The case for it is defect surface and readability, so it should be scheduled as such and
not justified with performance claims.

### 3.1 Additive, any release

- **Records** (106 files today) — keep as the default for new value carriers. Watch the Jackson-3
  constructor-parameter-name contract documented in `CLAUDE.md`: under Jackson 3 a record component name is
  part of the JSON contract.
- **Pattern-matching `instanceof`** — 245 occurrences still use the cast-after-test form. Mechanical,
  reviewable, zero behavioural risk. Convert opportunistically when touching a file rather than in one sweep.
- **Pattern-matching `switch` over `if`/`else if` chains** — the highest-value single instance is
  `isPermanentError`, which exists in two textually near-identical copies in
  `DefaultDurableQueueConsumer` and `CentralizedMessageFetcher`. Extracting it once and expressing it as a
  pattern switch removes a real duplication hazard: the two copies can drift, and a message classified as
  permanent by one consumer implementation and transient by the other is a silent behavioural difference
  between the centralized and legacy paths.
- **Text blocks** — partially adopted (17 files), with 36 lines of concatenated SQL left. Low priority, but
  note the interaction with the SQL-injection gotcha: a text block does not make an interpolated table name
  safe, and `PostgresqlUtil.checkIsValidTableOrColumnName()` still has to be called.

### 3.2 Additive but needs per-site judgement

- **`SequencedCollection` (`getFirst()`/`getLast()`)** — currently unused, against 247 `.get(0)` and 32
  `.get(size()-1)` occurrences. Not a blind find-and-replace: the receiver must actually be a `List` or other
  `SequencedCollection`, and many `.get(0)` calls are on maps, arrays or JDBI result types.
- **`Stream.toList()` over `collect(Collectors.toList())`** — 220 occurrences. Also not blind:
  `Stream.toList()` returns an **unmodifiable** list while `Collectors.toList()` historically returns a
  mutable `ArrayList`. Any site whose result is later mutated, sorted in place, or handed to a caller that
  might mutate it will start throwing `UnsupportedOperationException` at runtime, not compile time. Convert
  with the return path checked, file by file.

### 3.3 Next major only

- **Sealed types** — 3 uses today. Sealing an existing public interface or abstract class is a **breaking
  change for external implementors**, which the stable-API rule confines to a major release. The candidates
  worth evaluating then are the closed result and mode hierarchies in the public SPI, where an exhaustive
  `switch` would replace a default branch that currently exists only to satisfy the compiler. Sealing new
  types introduced from now on carries no such constraint and should be the default where the hierarchy is
  genuinely closed.

## 4. Suggested sequencing

| Phase | Work | Risk | Depends on |
|---|---|---|---|
| 1 | `parallelConsumers` vs connection-pool warning + docs (§2.7.1) | Low, additive | — |
| 2 | Additive `ExecutorService` knob on `ConsumeFromQueue` (§2.7.2) | Low, additive + deprecation | — |
| 3 | Extract and dedupe `isPermanentError` as a pattern switch (§3.1) | Low, behaviour-preserving by construction | — |
| 4 | Opportunistic `instanceof` pattern conversion (§3.1) | None | — |
| 5 | `toList()` / `SequencedCollection` conversion, file by file (§3.2) | Medium — runtime-only failure mode | 4 |
| 6 | Optional virtual-thread executor helper in `shared` (§2.7.3) | Low | 2 |
| 7 | Sealed public hierarchies (§3.3) | Breaking | Next major |

## 5. Harness added by this analysis

In `examples/essentials-performance-lab`:

- `scenario/VirtualThreadsQueueScenario` — the `virtual-threads-queue` scenario.
- `vthreads/VirtualThreadScheduledExecutorAdapter` — adapts a virtual-thread-per-task executor to
  `ScheduledExecutorService`; documents the gap in §2.6.
- `VirtualThreadsQueueScenarioSmokeIT` — always-on, tiny sweep; asserts the harness works and asserts nothing
  about which arm is faster.
- `VirtualThreadsQueueBenchmarkIT` — opt-in via `-Dbenchmark.run=true` per this repo's convention for suites
  that measure rather than assert; sweeps `parallelConsumers`, handler shape, repetitions and connection-pool
  size, and asserts only that every case actually drained.
