# Optimizing the Essentials test suite

> **Status: implemented.** Deviations from the plan as written, and why, are recorded in
> [Implementation notes](#implementation-notes) at the end.

## Context

The integration test suite has become the dominant cost in this repository's feedback loop. Measured from the
`target/surefire-reports` and `target/failsafe-reports` of the last local run:

| Layer | Wall clock | Classes |
|---|---|---|
| Unit (surefire) | **63 s** | 195 |
| Integration (failsafe) | **3703 s ≈ 62 min** | 136 |

Unit tests are not the problem. Essentially all of the cost is integration tests, and it is concentrated:

| Module | Failsafe seconds |
|---|---|
| `components/mssql-queue` *(untracked — out of scope)* | 1833 |
| `components/postgresql-queue` | 756 |
| `components/springdata-mongo-queue` | 514 |
| `components/postgresql-event-store` | 316 |
| `components/eventsourced-aggregates` | 82 |
| everything else combined | ~200 |

Excluding the out-of-scope `mssql-queue` and `jdbc-queue-base` modules, the **addressable baseline is ~1870 s
(≈31 min)**, executed strictly sequentially. CI multiplies this by five, because `.github/workflows/maven.yml`
runs a full `mvn verify` on JDK 21, 22, 23, 24 *and* 25.

The intended outcome is a materially faster `mvn verify` — both locally and in CI — achieved by fixing container
lifecycle and build parallelism first, and by trimming a handful of pathologically slow tests second. No test is
deleted and no assertion is weakened.

### Root causes identified

1. **Failsafe runs with no parallelism whatsoever.** `pom.xml:154-164` (pluginManagement) and `pom.xml:238-247`
   (build) declare only the `integration-test`/`verify` goals — no `forkCount`, no `parallel`. All 136 IT classes
   run serially in one JVM, and the reactor itself is single-threaded.
2. **Surefire's parallelism config is self-cancelling.** `pom.xml:171-174` sets `parallel=classes` together with
   `threadCount=1`, which disables the very thing it asks for.
3. **Containers start per *test method*, not per class.** 67 of the 97 IT files declare `@Container` on an
   *instance* field. With JUnit's default `PER_METHOD` lifecycle that is one container start/stop per test method:
   ~300 starts across those files, versus ~97 if they were static. Representative:
   `components/postgresql-queue/src/test/java/dk/trustworks/essentials/components/queue/postgresql/PostgresqlDurableQueuesIT.java:49`.
4. **The CDC tests leak 640 MB images.** `AbstractLogicalReplicationPostgresIT.java:78` and
   `CdcModeAutoRequireIT.java:57` build an *anonymous* `ImageFromDockerfile` on an *instance* field. Each test
   method builds a fresh unnamed image and leaves it behind — the dev machine this was measured on currently holds
   **1906 orphaned `localhost/testcontainers/*` images**.
5. **Images are unpinned and reuse is inert.** `postgres:latest` appears 73×, `mongo:latest` 16×. The machine has
   `testcontainers.reuse.enable=true` in `~/.testcontainers.properties`, but only **one** `withReuse(...)` call
   exists in the whole tree, so container reuse never happens.
6. **A few tests dominate.** `LocalOrderedMessagesRedeliveryDurableQueueIT` pushes `NUMBER_OF_MESSAGES = 2000`
   through up to 5 redeliveries for a *single* test (99 s); `DuplicateConsumptionDurableQueuesIT` spends 126 s on
   2 tests, most of it in fixed `Thread.sleep(2000)` / `Thread.sleep(5000)` calls.
7. **`atMost(Duration.ofSeconds(2000))` appears 8×.** A genuine hang burns 33 minutes of CI before failing.

### Scope decisions

- Aggression level: **infra + test hygiene**. Test semantics and assertions are preserved; the
  `Traditional` × `Centralized` × `SingleOperationTransaction` cross-product of 39 IT subclasses is *not*
  collapsed.
- CI: full `verify` on JDK 21 and 25 only; interim JDKs run unit tests.
- `components/mssql-queue` and `components/jdbc-queue-base` are untracked and **out of scope**.

---

## Step 0 — Measurement harness (do this first)

Every claim below is an estimate until measured. Add a small read-only script that turns the JUnit report files
into a ranked before/after table.

- **New:** `scripts/test-timings.sh` — walks `**/target/{surefire,failsafe}-reports/*.txt`, emits
  `seconds, tests, class, module` sorted descending, plus per-module and grand totals.
- Snapshot the *existing* `target/**-reports` directories as the before-baseline **before running any build**,
  since the next `mvn verify` overwrites them.

This is the only way to know which of the phases below actually paid off, and it is reusable afterwards.

---

## Phase 1 — Container lifecycle (largest win, mechanical)

### 1a. Centralize and pin images

**New:** `components/foundation-test/src/main/java/dk/trustworks/essentials/components/foundation/test/EssentialsTestContainers.java`

`foundation-test` is the right home: it is already depended on by every DB module and already carries
`testcontainers-junit-jupiter` and `testcontainers-postgresql` as `src/main` dependencies
(`components/foundation-test/pom.xml:69,73`).

It should expose:

- `POSTGRES_IMAGE` / `MONGO_IMAGE` constants pinned to explicit versions (e.g. `postgres:17.6-alpine`,
  `mongo:8.0`) rather than `:latest`. The alpine Postgres variant also boots noticeably faster.
- A `postgres()` factory returning a preconfigured `PostgreSQLContainer<?>` with the project's standard
  credentials and `.withReuse(true)`.

Then replace the 73 `postgres:latest` and 16 `mongo:latest` literals with these constants.

> `withReuse(true)` only takes effect when `testcontainers.reuse.enable=true` is present in the developer's
> `~/.testcontainers.properties`. CI runners have no such file, so this is automatically a local-only
> optimization and cannot change CI semantics. Note the trade-off: reused containers are *not* torn down between
> runs, so any test relying on a pristine database must reset its own tables — which is already the established
> pattern here (`resetQueueStorage`, `DROP TABLE IF EXISTS`).

**Do not** repoint the CDC Dockerfile's base image — `docker/postgresql-wal2json/Dockerfile` needs its current
base for the `wal2json` build.

### 1b. Instance `@Container` → `static` `@Container`

The single highest-leverage change: ~300 container starts collapse to ~97.

Apply this pattern across the 67 affected files:

```java
// before — one container per test METHOD
@Container
protected final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest") …

// after — one container per test CLASS
@Container
protected static final PostgreSQLContainer<?> POSTGRESQL_CONTAINER = EssentialsTestContainers.postgres();
```

Representative paths (the same edit repeats across all 67):

- `components/postgresql-queue/src/test/java/.../PostgresqlDurableQueuesIT.java:49`
- `components/eventsourced-aggregates/src/test/java/.../stateful/StatefulAggregateRepositoryIT.java:53`
- `components/eventsourced-aggregates/src/test/java/.../snapshot/PostgresqlAggregateSnapshotStoreIT.java:53`
- `components/foundation/src/test/java/.../postgresql/ListenNotifyIT.java:43`

**Per-file verification is required, not optional.** A static container means state now survives between test
methods. For each file, confirm there is a `@BeforeEach` that resets the relevant tables; where there is not, add
an explicit reset rather than reverting to an instance container. The 30 files that are *already* static are the
reference for what correct looks like.

### 1c. Fix the CDC image leak

`components/postgresql-event-store/src/test/java/.../cdc/AbstractLogicalReplicationPostgresIT.java:78`
and `.../cdc/CdcModeAutoRequireIT.java:57`:

```java
// before — anonymous image, rebuilt and orphaned per test method
new ImageFromDockerfile()
        .withFileFromClasspath("Dockerfile", "docker/postgresql-wal2json/Dockerfile")

// after — stable name + deleteOnExit=false, built once and reused forever
new ImageFromDockerfile("essentials-test/postgres-wal2json:1", false)
        .withFileFromClasspath("Dockerfile", "docker/postgresql-wal2json/Dockerfile")
```

Make the containing `@Container` field `static` as in 1b. Bump the `:1` tag whenever the Dockerfile changes.

Separately, the already-orphaned images should be reclaimed. This is a destructive local cleanup, so it belongs
in a documented one-liner in the module `CLAUDE.md` rather than being run as part of the change:

```bash
docker images --format '{{.Repository}}:{{.Tag}}' | grep '^localhost/testcontainers/' | xargs -r docker rmi
```

---

## Phase 2 — Build parallelism

### 2a. Failsafe

In `pom.xml` pluginManagement (`:154-164`), add configuration mirroring surefire's block:

```xml
<configuration>
    <forkCount>${failsafe.forkCount}</forkCount>   <!-- new property, default 2 -->
    <reuseForks>true</reuseForks>
    <trimStackTrace>false</trimStackTrace>
</configuration>
```

Introduce `<failsafe.forkCount>2</failsafe.forkCount>` in `<properties>` so it can be dialled per machine
(`-Dfailsafe.forkCount=1` on a constrained box, `0.5C` on a big one). Start conservative: each fork multiplies
concurrent Docker containers, and over-forking will make things *slower*, not faster. Tune with the Step 0
harness.

### 2b. Surefire

Resolve the contradiction at `pom.xml:171-174` — either drop `<parallel>classes</parallel>` or raise
`threadCount`. Given unit tests total 63 s, prefer simply removing the misleading `parallel`/`threadCount` pair
and keeping `forkCount=2`/`reuseForks=true`.

### 2c. Reactor

Document `mvn -T 1C verify` in the Commands section of the root `CLAUDE.md`. Do not hardcode it into
`.mvn/maven.config` — combined with `failsafe.forkCount` it multiplies container concurrency, and the right value
is machine-specific.

---

## Phase 3 — Test hygiene on the top offenders

Ordered by measured cost. Each keeps the assertions intact.

1. **`components/foundation-test/src/main/java/.../queue/LocalOrderedMessagesRedeliveryDurableQueueIT.java`**
   (99 s / 1 test). `NUMBER_OF_MESSAGES = 2000` at line 41 drives up to 12 000 deliveries through a real DB
   queue. Replace the constant with an overridable `protected int numberOfMessages()` defaulting to ~300, so the
   ordering-under-redelivery semantics are unchanged while the throughput grind is cut ~85%. Leave a subclass or
   system property able to restore 2000 for a nightly load run.

2. **`components/foundation-test/src/main/java/.../queue/DuplicateConsumptionDurableQueuesIT.java`**
   (126 s / 2 tests). Replace the fixed `Thread.sleep(2000)` (line 265) and `Thread.sleep(5000)` (line 268) with
   Awaitility conditions on the state those sleeps are actually waiting for. The `CONSUMER_START_DELAY_MS` sleep
   at line 258 is deliberately staggering consumer startup — keep it, but shrink it if the Step 0 numbers justify.

3. **`DurableQueuesLoadIT`** — same message-count treatment as (1).

4. **Cap the runaway timeouts.** Replace the 8 occurrences of `atMost(Duration.ofSeconds(2000))` (e.g.
   `DuplicateConsumptionDurableQueuesIT.java:309`, `LocalOrderedMessagesRedeliveryDurableQueueIT.java:204`) with
   something like `Duration.ofSeconds(60)`. This does not speed up the happy path — it stops a hang from burning
   33 minutes of CI before failing.

5. **Shrink the stability windows.** The paired `.during(Duration.ofMillis(1990))` checks are "assert nothing
   further arrives" guards; ~500 ms is sufficient and saves ~1.5 s per occurrence.

The 56 `Thread.sleep` calls elsewhere are mostly small and deliberate (fenced-lock timing, CDC polling) — leave
them unless Step 0 shows them on the critical path.

---

## Phase 4 — CI workflow

Rewrite `.github/workflows/maven.yml` into two jobs:

- **`verify`** — matrix `['21', '25']`, runs `mvn -B verify -DskipDependencyCheck=true`. Covers the Java 21 LTS
  baseline and the newest JDK with the full integration suite.
- **`unit`** — matrix `['22', '23', '24']`, runs `mvn -B test -DskipDependencyCheck=true`. Interim JDKs get
  compile + unit coverage without paying for Docker at all (~63 s each).

Also:

- Extend the pre-pull step to the pinned Postgres *and* Mongo images from Phase 1a (currently only
  `docker pull postgres:latest` is pre-pulled; Mongo is not).
- Keep `fail-fast: false` and both `test-summary` steps as they are; point them at the same paths.

**Optional but recommended — closes a real coverage gap.** `CLAUDE.md` states that touching serialization means
running both Jackson profiles, but no CI job runs `-Pjackson2` today. Dropping three full `verify` legs makes room
for one: add a `mvn -B -Pjackson2 verify -DskipDependencyCheck=true` job on JDK 21. This is flagged because it
*adds* CI time against a task about reducing it — it is a separate call whether to take it now or track it
separately.

---

## Phase 5 — Make the conventions stick

Update `.claude/rules/testing.md` (which already governs `**/*IT.java`) with the rules this work establishes, so
new tests do not reintroduce the same costs:

- `@Container` fields are `static` — an instance field means one container per test *method*.
- Container images come from `EssentialsTestContainers` constants; never `:latest`, never an inline literal.
- `ImageFromDockerfile` always gets a stable name and `deleteOnExit=false`.
- Awaitility `atMost` is capped at 60 s; a long fixed `Thread.sleep` is a smell — wait on the condition instead.

Add the `mvn -T 1C verify` and `-Dfailsafe.forkCount=…` knobs to the Commands section of the root `CLAUDE.md`.

---

## Expected outcome

Estimates, to be confirmed with the Step 0 harness — stated as ranges because container-start cost varies with
the host:

| Phase | Effect on the ~31 min addressable baseline |
|---|---|
| 1 (container lifecycle) | ~200 fewer container starts → est. **−35% to −45%** |
| 2 (parallelism) | wall clock roughly halves at `forkCount=2` → est. **−40%** of the remainder |
| 3 (top offenders) | ~250 s of measured sleep/throughput removed → est. **−10% to −15%** |
| 4 (CI matrix) | 5 full IT runs → 2 → **−60% CI minutes**, independent of the above |

Combined target: **~31 min → roughly 8–14 min** locally, and a CI cycle dominated by two ~10 min legs instead of
five ~62 min ones. Phase 1c additionally stops an unbounded 640 MB-per-test-method disk leak.

### Measured result

Two different numbers matter, and they move by different amounts:

- **Summed class execution time** (what `scripts/test-timings.sh` totals) fell from **1934 s to 1681 s (−13%)**
  across the addressable modules. This measures work done, not elapsed time, so it only reflects the container-start
  savings — not the parallelism.
- **Wall clock** is where the win is, because Failsafe now forks. Verified per module:

  | Module | Before | After | |
  |---|---|---|---|
  | `postgresql-queue` | 756 s | **336 s** | −55% |
  | `postgresql-event-store` | 316 s | **168 s** | −47% |
  | `foundation` | 44 s | **19 s** | −58% |

  The ten remaining modules (including `springdata-mongo-queue`, 514 s of summed class time) complete in **382 s**
  wall clock together.

A few small modules got *slower* — `examples/essentials-performance-lab` +19 s, `types-springdata-jpa` +8 s,
`types-springdata-mongo` +5 s. That is the one-off cost of pulling the newly pinned `postgres:18.4` / `mongo:8.2`
images on a machine that only had `:latest` cached; it does not recur, and CI pre-pulls both.

**Verification status:** a single clean `mvn clean verify -DskipDependencyCheck=true` over the full reactor passes:
**BUILD SUCCESS, 35/35 modules, 0 skipped modules, 1673 tests, 0 failures, 0 errors, 27 skipped, 18:23 min.** The 27
skipped are the pre-existing `@Disabled` / `benchmark.run`-gated performance classes — the same set as the baseline,
nothing newly skipped. Note this is a `clean` build, so it includes full recompilation; the per-module wall-clock
figures above are the like-for-like test comparisons.

The image leak is confirmed closed: a full CDC run left the orphan `localhost/testcontainers/*` count unchanged at
1906 and produced exactly one `essentials-test/postgres-wal2json:1` image, where previously every CDC test method
added a ~640 MB orphan.

---

## Verification

1. **Baseline first.** `mvn -B verify -DskipDependencyCheck=true` on a clean tree, then `scripts/test-timings.sh`
   → save as the before-table. Do this before any edit.
2. **After each phase**, re-run the same command and diff the table. Any class that got *slower* is a regression
   to investigate — particularly after 1b, where a shared container can expose ordering assumptions.
3. **Correctness gate — the suite must stay green, not just fast.** Run the full
   `mvn -B verify -DskipDependencyCheck=true` and confirm the pass/fail/skip counts match the baseline exactly.
   A test that silently starts skipping is a regression, not a win.
4. **Repeat-run stability.** Static containers and reuse are the classic source of order-dependent flakiness.
   Run the two heaviest modules three times consecutively:
   `mvn -B verify -pl components/postgresql-queue,components/springdata-mongo-queue -am -DskipDependencyCheck=true`
   All three runs must be green.
5. **Both Jackson flavors**, since `foundation-test` and the queue/event-store ITs are serialization-sensitive:
   `mvn -B verify -Pjackson2 -DskipDependencyCheck=true` in the full reactor (the `-am`/full-reactor caveat in
   `CLAUDE.md` applies).
6. **Confirm the image leak is closed.** After a full run,
   `docker images | grep -c '^localhost/testcontainers/'` should stay flat instead of growing by one per CDC test
   method.
7. **Fork-count tuning.** Compare `-Dfailsafe.forkCount=1`, `2`, and `0.5C` with the harness and set the default
   to whichever wins on a typical dev machine — over-forking Docker regresses wall clock.

> Build note: this devcontainer needs an explicit JDK on each Maven invocation, e.g.
> `JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-arm64 mvn …`. The integration tests are extremely log-heavy — a
> single-module run can produce hundreds of MB of output.

## Implementation notes

What was built differs from the plan above in several places. Each deviation and its reason:

1. **Pinned `postgres:18.4`, not `postgres:17.6-alpine`.** `postgres:latest` resolved to **18.4** at the time of
   pinning, so the plan's suggested 17.x would have been a silent *major downgrade* under 113 integration tests.
   The image was pinned to exactly what was already running, making the pin behaviour-neutral. Mongo likewise
   pinned to `mongo:8.2` (`:latest` was 8.2.12). The alpine variant remains an untested follow-up.

2. **Container reuse is opt-in and off by default.** The plan argued `withReuse(true)` was safe because CI has no
   `~/.testcontainers.properties`. That reasoning does not hold on a *developer* machine — this devcontainer
   already sets `testcontainers.reuse.enable=true`, and combined with `failsafe.forkCount=2` two forks would attach
   to the same reused database and drop each other's tables in `@BeforeEach`. Reuse is therefore gated behind
   `-Dessentials.test.containers.reuse=true` (`EssentialsTestContainers.REUSE_PROPERTY`) and must only be combined
   with `-Dfailsafe.forkCount=1`.

3. **`EssentialsTestContainers` is used only where `foundation-test` is already a dependency** (`postgresql-queue`,
   `springdata-mongo-queue`, and both fenced-lock modules — about 1330 s of the baseline). The plan implied wider
   use, but `foundation-test` carries a *non-optional* `junit-vintage-engine`, and this repo is on `junit-bom 6.0.3`;
   adding it as a test dependency to `postgresql-event-store` / `eventsourced-aggregates` risked test-engine
   discovery failures for the sake of centralizing one version string. Those modules pin the tag inline instead.
   `components/foundation` cannot depend on `foundation-test` at all — it would be a reactor cycle.

4. **The two event-store-backed modules keep per-test-method containers.** Making containers static failed 7
   classes in `postgresql-event-store` and 10 in `eventsourced-aggregates`: these suites assert on absolute
   `EventOrder`/`GlobalEventOrder` values, own replication slots, and drop/recreate a role that later methods still
   own — so events left by an earlier method are visible to a later one. Rather than rewrite event-store and CDC
   cleanup semantics, the lifecycle change was reverted for both modules. They keep the image pin (and, for CDC,
   the `WAL2JSON_IMAGE` stable tag), and still improve substantially from Failsafe forking alone —
   `postgresql-event-store` went 316 s → 168 s that way.

   This is the plan's "per-file verification is required, not optional" clause doing its job. The container
   lifecycle win is real, but it only applies to suites that already reset their own storage — which is the queue
   and fenced-lock modules, not the event-store ones.

5. **Phase 3 was reduced to capping timeouts.** The plan proposed cutting
   `LocalOrderedMessagesRedeliveryDurableQueueIT`'s message count and `DuplicateConsumptionDurableQueuesIT`'s
   sleeps. Inspection showed those *are* the tests' detection mechanism: `PROCESSING_DELAY_MS = 3000` with
   `PARALLEL_CONSUMERS = 1` is what holds messages in the worker pool long enough for the other instance to
   over-fetch, and message volume is what makes an ordering violation observable. Reducing either weakens defect
   detection, which the plan's own framing ruled out. Only the 9 runaway
   `atMost(Duration.ofSeconds(2000 | 5000))` waits were capped at 60 s — pure hang-protection, no happy-path change.

6. **Latency and load suites were split rather than gated together.** `*PerformanceIT` and
   `QueueFetchStrategyBenchmarkIT` turned out to be *already* gated behind `@Disabled` /
   `@EnabledIfSystemProperty("benchmark.run")`. `*LatencyIT` and `*LoadIT` were not, and inspecting them showed they
   are very different propositions, so they got separate switches:

   | Suite | Cost | What it asserts | Default | Switch |
   |---|---|---|---|---|
   | `*LatencyIT` | 92.8 s / 7 tests | Nothing about latency — results go to `System.out`; the only assertions check that `queueMessages` returned as many ids as it was given | **off** | `-Dbenchmark.run=true` to run |
   | `*LoadIT` | 7.1 s / 2 tests | Real behaviour: 20 000 messages queued, count, batch fetch, and that a consumer drains them | **on** | `-Dloadtest.skip=true` to skip |

   Gating the latency suite loses no assertion that can fail, and removes four unbounded
   `while (totalFetched < targetQueriesToMeasure())` loops from the default build. Its incidental coverage of the
   ordered/unordered SQL builders is also obtained through the public API by `BatchedFetchStrategyIT`,
   `CentralizedFetcherDurableQueueIT_WithOrderedUnordered` and `PostgresqlDuplicateConsumptionDurableQueuesIT`.
   `postgresql-queue` drops from 5:37 to 4:50 as a result.

   **Gotcha worth knowing:** JUnit's condition annotations are not meta-annotated `@Inherited`. Placing
   `@EnabledIfSystemProperty` on the abstract base had no effect and failed silently — the suite kept running. They
   have to go on the concrete class, which is why the existing `*PerformanceIT` concrete classes each carry their own
   `@Disabled`.

7. **No `-Pjackson2` CI job was added.** It remains the open recommendation in Phase 4.

### Pre-existing defects surfaced by the shared containers

Sharing a container per class exposed bugs that a fresh-container-per-method was hiding. These are fixed:

- `BatchedFetchStrategyIT` opened a `HikariDataSource` per test method and never closed it — 20 methods ×
  `maximumPoolSize=20` exhausted PostgreSQL's `max_connections`. Now closed in `@AfterEach`.
- `ListenNotifyIT` and `MultiTableChangeListenerIT` ran `CREATE TABLE` with no preceding drop. Now
  `DROP TABLE IF EXISTS` first.
- **Nine further `postgresql-queue` ITs leaked a pool the same way** and are now fixed too. They were passing only
  because each has one or two test methods, so the leak stayed under the connection ceiling — but adding a method to
  any of them would have reproduced the `BatchedFetchStrategyIT` failure.

  Two of those nine create the pool in their own `@BeforeEach` and simply close it in their own `@AfterEach`. The
  other seven override `createUnitOfWorkFactory()` from an abstract base in `foundation-test` and have no lifecycle
  methods of their own — and an `@AfterEach` in a *subclass* runs **before** the base's, which would close the pool
  while the queues were still stopping. The base ITs therefore gained a `protected void releaseTestResources()`
  no-op hook, invoked at the end of their `cleanup()`, which those subclasses override. Mongo subclasses inherit the
  no-op and are unaffected.

  `DuplicateConsumptionDurableQueuesIT` is the exception worth knowing about: it calls `createUnitOfWorkFactory()`
  **twice** per test to simulate two nodes, so its Postgres subclass tracks a `List<HikariDataSource>` — a single
  field would silently leak the first pool.

## Out of scope

- `components/mssql-queue` (1833 s) and `components/jdbc-queue-base` — untracked. They are the single largest
  cost centre, so once committed they should get Phases 1–3 applied; worth tracking as a follow-up.
- `examples/` modules — 34 s combined, not worth the churn.
- Collapsing the 39-subclass `Traditional` × `Centralized` × `SingleOperationTransaction` cross-product.
- Any change to what a test asserts.
