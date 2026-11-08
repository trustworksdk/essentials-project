# Shard-owned queue modules: test-time reduction plan

Status: plan, nothing built. Written 2026-11-08 against `feature/trading-demo-expansion` at `4ffad19fb`.

The shard-owned queue modules' test time grew by roughly ten minutes over the 0.60.1 release. Almost all of the growth
is integration tests added by the review-fix runs that went into 0.60.1. Those tests guard real defects and must keep
doing so, but most of their time is spent waiting out real durations (handler timeouts, stop budgets, lease TTLs and
fixed `pg_sleep` holds) rather than doing work. This plan lists the changes that remove that waiting without removing
any coverage, ordered by seconds saved per unit of effort. The target is 5 to 6 minutes of summed test time.

## 1. Measurements

### 1.1 How the numbers were taken

- Machine: the devcontainer, Docker with about 15 GB free, nothing else building.
- Upstream modules installed with `mvn install -pl <the three shard modules> -am -DskipTests`, then
  `mvn verify -pl components/postgresql-queue-shard-owned,components/postgresql-queue-shard-owned-adapter,components/spring-boot-starter-postgresql-queue-shard-owned -DskipDependencyCheck=true`.
- Default knobs: `failsafe.forkCount=2`, `surefire.forkCount=2`, `benchmark.run` off, `loadtest.skip` off.
- Ranked with `scripts/test-timings.sh` and a per-method pass over the Failsafe and Surefire XML reports.
- Age of each test method: whether a method of that name exists in the test class at tag `0.60.0`, at tag `0.60.1`,
  or only on this branch. 0.60.1 is one squash commit (`48616f8c1`, "Release/0.60.1 (#54)") that contains 194
  `fix:` commits from `/tw-code-review:fix` runs (F-6xx findings of the `feat-slice-atlas` review), so per-finding
  attribution inside it is not possible from history; the test Javadoc of each method names the defect it guards.

### 1.2 Totals per module (all 1,051 tests green)

| Module | Reactor wall clock | Failsafe sum | Surefire sum |
|---|---:|---:|---:|
| `postgresql-queue-shard-owned` | 12 min 42 s | 1,351.8 s (69 classes, 494 tests) | 29.8 s (378 tests) |
| `postgresql-queue-shard-owned-adapter` | 53 s | 56.1 s (10 classes, 45 tests) | 17.9 s (73 tests) |
| `spring-boot-starter-postgresql-queue-shard-owned` | 13 s | 12.7 s (3 classes, 41 tests) | 1.0 s (10 tests) |

"Sum" is the total of the per-class times in the reports. With two Failsafe forks the wall clock is about half the sum,
but never less than the slowest single class (see `ShardOwnedLifecycleIT` below), so a second saved in a long class
counts for more than a second saved in a short one.

### 1.3 Where the growth came from (summed test-method time)

| Module, suite | Methods present at 0.60.0 | Added in 0.60.1 | Added on this branch since 0.60.1 |
|---|---:|---:|---:|
| core, Failsafe | 396 s | 861 s | 43 s |
| core, Surefire | 0 s | 25 s | 1 s |
| adapter, Failsafe | 4 s | 28 s | 15 s |
| adapter, Surefire | 0 s | 18 s | 0 s |
| starter, both | 9 s | 1 s | 1 s |

About 990 s of summed test time, roughly 16 minutes of test time or 8 to 9 minutes of wall clock at two forks, is in
methods that did not exist at 0.60.0. That agrees with the "about ten minutes" observed.

### 1.4 Slowest classes

| Class | Seconds | Tests | Added since 0.60.0 | Main cause |
|---|---:|---:|---:|---|
| `ShardOwnedLifecycleIT` | 343.9 | 125 | 334.8 s | Fixed `pg_sleep` holds; waiting out `stuckHandlerTimeout` (`HUNG_FLUSH_BOUND`, 12 s) |
| `ShardOwnedUnsubscribeIT` | 164.3 | 46 | 163.5 s (new class) | Waiting out `stopFlushGiveUpAfter`; `SHUTDOWN_BUDGET` per stop |
| `ShardOwnedOrderedRebalanceIT` | 96.8 | 9 | 67.8 s | `Thread.sleep(stuckHandlerTimeout + 500)` with a 20 s timeout |
| `ShardOwnedDeadPumpHandBackIT` | 90.7 | 42 | 89.8 s (new class) | `Thread.sleep(stuckHandlerTimeout + 300..500)` in 17 places |
| `ShardOwnedMultiQueueCostIT` | 72.3 | 2 | 0 s | A cost recording: `sleep(35)` plus a measuring window |
| `ShardOwnedUnorderedReleaseChargeIT` | 49.1 | 14 | 48.3 s (new class) | `Thread.sleep(STUCK_HANDLER_TIMEOUT + 300)` in 11 places |
| `ShardOwnedTakeoverAttemptsIT` | 48.2 | 12 | 47.3 s (new class) | Waiting out `LEASE_TTL`; one method takes 31.4 s |
| `ShardOwnedQueueIT` | 38.2 | 5 | 0 s | One crash-redelivery method takes 31.5 s |
| `ShardOwnedMultiProcessIT` | 36.0 | 3 | 0 s | Real multi-process lease lifetimes |
| `ShardOwnedDatabaseFailureIT` | 34.1 | 5 | 0 s | Real outage and pool starvation windows |
| `ShardOwnedLocalHandoffIT` | 32.7 | 2 | 0 s | One dead-owner method takes 31.5 s |
| `ShardOwnedStaleLivenessIT` | 30.3 | 2 | 7.4 s | Network partition over a 3 s lease |
| `ShardOwnedConcurrentProducersOrderIT` | 28.9 | 3 | 28.0 s (new class) | Volume test, already behind `loadtest.skip` |
| `ShardOwnedSpiFairShareIT` | 26.1 | 1 | 0 s | `Thread.sleep(Duration.ofSeconds(25))` on the default 30 s lease |
| `OrderedRetryHandoverOnShardOwnedIT` (adapter) | 25.7 | 3 | 24.9 s (new class) | Three 8 s backoff scenarios |

Inside `ShardOwnedLifecycleIT`, 60 of the 125 runs take under 2 s each (32 s together). The time is in a few dozen
methods:

| Method (`ShardOwnedLifecycleIT`) | Seconds |
|---|---:|
| `a_message_queue_stop_called_while_a_start_is_still_leasing_returns_within_its_budget_and_leaves_nothing_running` (4 rows) | 45.3 |
| `a_message_queue_stop_giving_up_on_a_consume_stops_its_pair_after_an_observers_nested_consume_returned` | 25.3 |
| The five `a_unit_a_stop_kept_whose_flush_never_returns_…` / `…_outlasts_a_short_stuck_handler_timeout_…` methods | 70.4 |
| `a_stop_called_while_a_start_is_still_leasing_returns_within_its_budget_and_leaves_nothing_of_the_start_running` (2 rows) | 12.6 |
| `a_consume_a_message_queue_stop_gave_up_waiting_for_never_reads_as_started_after_that_stop_returned` | 11.7 |
| `a_message_queue_start_called_while_an_earlier_consume_still_owes_a_stop_waits_for_it_and_says_so_once` | 10.2 |
| `a_release_on_request_…` and `a_hand_back_of_a_kept_unit_the_database_never_answers_…` | 17.4 |
| `a_start_called_while_an_earlier_start_still_owes_a_stop_waits_for_it_and_says_so_once` | 8.2 |

### 1.5 What was not measured

- A 0.60.0 baseline was run in a throw-away worktree with a private local repository, on the same machine and knobs.
  `postgresql-queue-shard-owned` at 0.60.0 took **3 min 50 s** wall clock (Failsafe sum 429.1 s over 44 classes,
  Surefire 2.3 s), against 12 min 42 s and 1,351.8 s today: **+8 min 52 s** of wall clock for this module alone. The
  classes that existed then run at the same speed now (for example `ShardOwnedMultiQueueCostIT` 72.7 s then, 72.3 s
  now), so the growth is new tests, not a slower engine. The adapter and starter modules at 0.60.0 could not be run
  that way (the adapter resolved some upstream artifacts from today's snapshot and 9 tests errored), so their baseline
  is the per-method estimate in 1.3 only.
- Container start-up per test class is not in the per-method numbers. 67 classes start their own PostgreSQL container
  (`LabPostgres.create()`), and `ShardOwnedSchemaContributorIT` starts one per test. Item 7 needs a measurement first.
- Nothing was run under `-T 1C`.

## 2. Causes

Four causes account for nearly all of the added time.

1. **Waiting out the stuck-handler bound in real time.** A large family of review-fix tests checks what happens to a
   key that has been in its handler longer than `stuckHandlerTimeout`, or to a unit kept longer than
   `ShardOwnedQueue.stopFlushGiveUpAfter` (`max(stuckHandlerTimeout, SHUTDOWN_BUDGET + max(leaseTtl, SHUTDOWN_BUDGET))`).
   The engine reads elapsed time from `System.nanoTime()` directly in `ShardOwnedQueue.KeptUnit.keptFor()`
   (`ShardOwnedQueue.java:5109`, `:5128`), `OrderedShardOwner.InHandler.sinceNanos` (`OrderedShardOwner.java:926`,
   `:1821`, `:1979`) and the unordered owner's in-flight map (`ShardOwner.java:1319`). The tests therefore sleep for the
   whole bound: `HUNG_FLUSH_BOUND = 12 s` (`ShardOwnedLifecycleIT.java:68`), `stuckHandlerTimeout = 20 s` in
   `ShardOwnedOrderedRebalanceIT`, 8 s and 6 s in `ShardOwnedUnsubscribeIT.java:971` and `:1079`, 5 s and 1 s in
   `ShardOwnedDeadPumpHandBackIT`, 1 s in `ShardOwnedUnorderedReleaseChargeIT.java:57`. "Stuck versus young key" tests
   need one key past the bound and one under it, so the bound is padded upwards for the young key's sake, and the stuck
   key's wait grows with it.
2. **Fixed `pg_sleep` holds.** `ShardOwnedLifecycleIT` holds a lease acquire or a membership upsert inside a trigger
   that sleeps a fixed time (`slowTheAcquireOf(…, 10)` at lines 3598, 3606, 3662 and 3719; `slowTheRegistrationOf(…, 12)`
   at 3969; 8 s at 3108; 5 s at 2970; 3 s at 2874, 3013 and 3052). The hold only has to last until the test has made its
   point, but the test then waits for the sleep to end before it can tear down.
3. **The 2 s `SHUTDOWN_BUDGET`** (`ShardOwnedQueue.java:4881`). Every stop with a handler held drains for `shedGrace`
   and then waits out the budget. Many `ShardOwnedUnsubscribeIT` and `ShardOwnedLifecycleIT` tests stop a queue with a
   held handler once or twice, which is where most of their 2 to 7 s per test goes.
4. **Waiting out lease TTLs, and pre-0.60.0 measurement tests in the normal build.** `ShardOwnedTakeoverAttemptsIT`
   sleeps `LEASE_TTL + 200 ms` to age a lease; `ShardOwnedSpiFairShareIT` sleeps 25 s on the default 30 s lease;
   `ShardOwnedMultiQueueCostIT` records costs over a 35 s idle period plus a window, asserting only limits a container
   survives.

The engine already has the seam cause 1 needs: `PumpFailures` (`PumpFailures.java:102`) and `TakeoverStalls`
(`TakeoverStalls.java:90`) take a `LongSupplier nanoTime`, and their unit tests drive it.

## 3. Changes, ordered by seconds saved per effort

Savings are summed test time, measured from the per-method numbers above. Divide by roughly two for wall clock at the
default two forks, except where the change shortens `ShardOwnedLifecycleIT`, the long pole of one fork, which counts
almost one for one.

### Item 1: Gate `ShardOwnedMultiQueueCostIT` behind `benchmark.run`

- Target: `ShardOwnedMultiQueueCostIT.records_what_several_idle_queues_cost_in_threads_connections_and_queries`
  (71.2 s; sleeps at lines 151, 175, 188 and 203).
- Cause: a cost recording. Its own Javadoc (line 43) says it records what the costs are rather than asserting they are
  good, and asserts only limits a container can survive. Its siblings (`ShardOwnedUnorderedReadCostIT`,
  `ShardOwnedOrderedLoadedCostIT`, `ShardOwnedStalledKeyCostIT`) are already `@EnabledIfSystemProperty(named =
  "benchmark.run", matches = "true")`.
- Change: add the same annotation and say so in the class Javadoc. If the connection-count assertion near line 225 is
  wanted as a guard, keep it in a new short test that starts the queues, waits for one heartbeat tick and asserts the
  connection delta, without the 35 s idle period.
- Saving: about 70 s. Effort: minutes.
- Coverage risk: the recording stops running in the default build. Keep it in the benchmark run, and keep the
  connection-delta assertion as described if anyone relies on it.
- Verify: `mvn verify -pl components/postgresql-queue-shard-owned -DskipUTs -Dit.test=ShardOwnedMultiQueueCostIT
  -Dfailsafe.failIfNoSpecifiedTests=false` reports it skipped, and runs it with `-Dbenchmark.run=true`.

### Item 2: Give `ShardOwnedSpiFairShareIT` a short lease

- Target: `ShardOwnedSpiFairShareIT.a_single_process_still_owns_every_shard_after_the_first_rebalance` (25.3 s, the
  `Thread.sleep(Duration.ofSeconds(25))` in the test body).
- Cause: the queue runs on defaults, a 30 s lease with a heartbeat every 10 s, so the test sleeps through several real
  rebalances.
- Change: build the `PostgresqlMessageQueue` with `ShardOwnerSettings` whose `leaseTtl` is 3 s (heartbeat about 1 s), and
  replace the sleep with an Awaitility wait until at least three heartbeat ticks have passed (the queue's metrics or the
  membership row's heartbeat column show it), then make the same assertions.
- Saving: about 20 s. Effort: under an hour.
- Coverage risk: none. The property is "still owns every shard after several rebalances", not "after 25 s".
- Verify: break it on purpose by making `shardsHeld()` count the unordered lane only (the defect its message names);
  the test must fail.

### Item 3: Replace fixed `pg_sleep` holds with a hold the test releases

- Targets: `ShardOwnedLifecycleIT` helpers `slowTheAcquireOf`, `slowTheRegistrationOf` and `holdTheNextAcquireOf`
  (lines 5444 to 5485) and their callers at 2874, 2970, 3013, 3052, 3108, 3598, 3606, 3662, 3719 and 3969; also the
  1.5 s and 1 s inline triggers at 1807 and 2227, `ShardOwnedUnsubscribeIT.java:3383` and
  `ShardOwnedWatermarkXidWindowIT.java:91`.
- Cause: the hold is sized for the slowest build, then waited out in full on every build.
- Change: have the trigger wait on a PostgreSQL advisory lock (`PERFORM pg_advisory_xact_lock(<key>)`) that the test
  takes on its own connection before arming the trigger, and releases once its assertions about the held statement are
  done. `aHeartbeatIsSleeping()` (line 5522) becomes a query for a backend waiting on that advisory lock in
  `pg_locks`. Keep an upper bound in the helper (the test releases in a `finally`) so a failing assertion cannot leave
  the statement held for the tear-down.
- Saving: about 65 s, all of it in the long-pole class. The four-row
  `a_message_queue_stop_called_while_a_start_is_still_leasing_…` test drops from 45 s to roughly 12 s, the
  nested-consume test from 25 s to about 6 s, and the 8 to 12 s methods around it by about half.
- Coverage risk: low. A few tests assert that something returns "within its budget" while the statement is still held
  (for example the `isLessThan(Duration.ofMillis(interrupted ? 1_500 : 5_500))` check at about line 3653). These keep
  their meaning as long as the test releases the hold only after that assertion; review each caller for that order.
- Verify: per test, revert the fix its Javadoc names (for the stop-while-leasing test, have `PostgresqlMessageQueue.stop()`
  take the lifecycle monitor without the timed wait) and confirm the test still fails, now by its assertion rather than
  by a timeout.

### Item 4: An elapsed-time seam for the stuck-handler and kept-unit bounds

- Targets, with their current time:
  - `ShardOwnedLifecycleIT`: the five hung-flush methods (`HUNG_FLUSH_BOUND`, lines 711, 831, 933 and two more), 70 s.
  - `ShardOwnedUnsubscribeIT`: `an_unsubscribed_subscription_whose_stop_flush_hangs_…` (line 966, 22.3 s),
    `an_unsubscribed_unit_whose_stop_flush_never_returns_…` (line 1075, 13.5 s),
    `a_unit_taken_over_past_the_stuck_handler_timeout_…` (9.8 s),
    `a_subscription_whose_kept_unit_outlasts_the_stuck_handler_timeout_…` (8.5 s),
    `a_queue_restarted_while_a_key_is_in_the_handler_…` (7.6 s): 62 s.
  - `ShardOwnedOrderedRebalanceIT`: `a_shed_released_past_the_stuck_handler_timeout_…` (21.9 s),
    `a_kept_unit_released_on_request_after_its_shed_charged_the_stuck_key_…` (22.1 s), and the two
    charge-before-release tests (11.6 s, 7.1 s): 63 s.
  - `ShardOwnedDeadPumpHandBackIT`: the 17 `Thread.sleep(stuckHandlerTimeout + …)` sites (lines 359, 443, 529, 607,
    717, 860, 980, 1119, 1233, 1294 and the 1.5 s and 1 s sleeps): about 40 s.
  - `ShardOwnedUnorderedReleaseChargeIT`: 11 sleeps of 1.3 s (lines 110 to 918): about 15 s.
- Cause: cause 1 above.
- Change: follow the `PumpFailures` and `TakeoverStalls` pattern. Give `ShardRuntime` (or `ShardOwnerSettings`, as a
  package-private, non-builder field) a `LongSupplier nanoTime` that defaults to `System::nanoTime`, and read it in
  `KeptUnit` (both `keptAtNanos` and `keptFor()`), where `OrderedShardOwner` stamps `InHandler.sinceNanos` and compares
  it with the bound (`:926`, `:1979`), and where `ShardOwner` stamps and compares its in-flight entries (`:1319`).
  Nothing else changes: lease TTLs, heartbeats, shed grace and the stop budget stay on real time. In the tests, a
  `TestNanoClock` that is real time plus an offset the test can advance replaces each
  `Thread.sleep(stuckHandlerTimeout + margin)` with `clock.advance(stuckHandlerTimeout.plusMillis(1))`. For the
  "stuck versus young" tests, the young key enters after the advance, so the bound can be large (keep 20 s) and the
  young key is then under it by construction instead of by timing margin, which also removes their flakiness on a loaded
  build.
- Saving: about 190 s summed, about 70 s of it in `ShardOwnedLifecycleIT`.
- Effort: one to two days. It touches production code paths that guard data (charging and dead-lettering a key), so the
  seam must be a plain field read with no behaviour of its own, default `System::nanoTime`, and package-private.
- Coverage risk: the seam replaces a real-time passage with a simulated one. Keep one real-time canary for each bound:
  one hung-flush test in `ShardOwnedLifecycleIT` and one stuck-key test in `ShardOwnedDeadPumpHandBackIT` keep sleeping
  for real (with a short bound), so the wiring of the default clock stays covered. Note in the module `CLAUDE.md` that
  any new elapsed-time comparison for these bounds must read the seam, or the fast tests silently stop covering it.
- Verify: for each converted test, revert the fix its Javadoc names (for the "young key" tests, make the release charge
  every key in a handler, `ReleaseChargeScope.EVERY_KEY_IN_HANDLER`) and confirm it fails; and set the default clock in
  the seam to a stub that never advances and confirm the canaries fail.

### Item 5: A test-only stop budget

- Targets: every test that stops a queue with a handler held and is not about the budget itself. The bulk of the 2 to 7 s
  methods in `ShardOwnedUnsubscribeIT` (25 methods over 2 s, about 110 s) and a share of `ShardOwnedLifecycleIT`,
  `ShardOwnedDeadPumpHandBackIT` and `ShardOwnedUnorderedReleaseChargeIT`.
- Cause: `SHUTDOWN_BUDGET` is a 2 s constant (`ShardOwnedQueue.java:4881`), and `stopFlushGiveUpAfter` is derived from
  it, so tests also size `leaseTtl` and `stuckHandlerTimeout` around it.
- Change: read the budget through a package-private setting (default 2 s), and let these test classes set 300 to 500 ms.
  `ShardOwnedShutdownBudgetIT` and the `…returns_within_its_budget…` tests in `ShardOwnedLifecycleIT` keep the
  production value.
- Saving: about 60 to 80 s. Effort: half a day plus a careful pass over the tests that compute expected times from the
  budget (several comments spell the arithmetic out, for example `ShardOwnedUnsubscribeIT.java:1076`).
- Coverage risk: tests whose premise is "the stop gave up waiting for its budget thread" still hold with a shorter
  budget. Tests that assert ordering against the lease TTL must be re-derived, not just re-run.
- Verify: run each changed class ten times in a row (`-Dfailsafe.rerunFailingTestsCount=0` and a shell loop) with
  `-Dfailsafe.forkCount=2`, then once under load (`-T 1C` across the reactor), before trusting it.

### Item 6: Age leases with SQL instead of waiting for them

- Targets: `ShardOwnedTakeoverAttemptsIT` (`Thread.sleep(LEASE_TTL.toMillis() + 200)` at lines 185, 225, 264 and 302)
  and `ShardOwnedQueueIT.a_new_owner_redelivers_what_a_crashed_owner_left_unacknowledged` (`Thread.sleep(1_200L)`).
- Cause: the lease and membership timestamps are server-side `now()`, so the tests wait for them to pass.
- Change: move the crashed owner's lease and membership rows into the past with an `UPDATE` (the tests already own the
  schema), then wait for the takeover. Before doing this, profile the two 31 s methods
  (`ShardOwnedTakeoverAttemptsIT.a_takeover_after_a_crash_charges_only_what_the_crashed_owner_could_have_had_in_flight`,
  `ShardOwnedQueueIT.a_new_owner_redelivers_what_a_crashed_owner_left_unacknowledged`, and the similar
  `ShardOwnedLocalHandoffIT.a_message_pre_claimed_by_a_dead_owner_is_delivered_by_the_next_one`): 31 s is far more than
  their visible sleeps, which points to a default-settings wait (hole expiry, sweep or poll back-stop) somewhere in them.
- Saving: about 10 s for the visible sleeps; possibly 60 s more if the three 31 s methods share one default-settings
  wait. Effort: an hour for the sleeps, an hour to profile the rest.
- Coverage risk: an `UPDATE` that ages the rows skips the engine's own expiry path. Keep at least one test that ages a
  lease in real time (`ShardOwnedStaleLivenessIT` does).
- Verify: as for item 4, revert the guarded fix and confirm a failure.

### Item 7 (measure first): One PostgreSQL container per fork instead of per class

- Targets: the 61 classes using `static PostgreSQLContainer postgres = LabPostgres.create()` with no arguments, and
  `ShardOwnedSchemaContributorIT`'s per-test container.
- Change: a per-JVM singleton in `LabPostgres` for the no-argument case, each class still calling
  `ShardOwnedSchema.recreate` in `@BeforeEach` as now. Classes with their own server arguments
  (`max_connections`, `pg_stat_statements`, a sized tmpfs, a network) keep their own container.
- Saving: unknown until measured. Container start is not in the per-method numbers; at the 2 to 3 s a start usually
  takes here, 60 starts are 2 to 3 minutes summed. Measure by comparing the reactor wall clock with the per-class sum
  before deciding.
- Coverage risk: state leaking between classes (left-over triggers such as the ones items 3 and 6 create, advisory
  locks, `LISTEN` channels). `ShardOwnedSchema.recreate` must drop every trigger the tests create, which it may not.

### Smaller items, not counted in the total

- Negative-window sleeps (`Thread.sleep(1_000)` and then "nothing happened", for example
  `ShardOwnedDeadPumpHandBackIT.java:2499`, `:2515`, `:2607`) can be `Awaitility.await().during(…)` tied to a known
  number of heartbeat ticks. A few seconds in total.
- `ShardOwnedConcurrentProducersOrderIT` (28.9 s) is a volume test already behind `-Dloadtest.skip=true`; its three
  parameter rows could share one queue start. About 10 s.
- `OrderedRetryHandoverOnShardOwnedIT` (adapter, 25.7 s) runs three scenarios on an 8 s backoff; a 2 s backoff keeps
  the ordering property. About 15 s.
- Several `ShardOwnedLifecycleIT` and `ShardOwnedUnsubscribeIT` assertions check only the text of a WARN or the phase
  of a `KeptUnit`. The unit-test classes (`ShardOwnedQueueReleaseLeaseRowsTest`, `KeptUnitPhaseTest`) show these can be
  driven without a database; moving them is the user's stated preference but saves little time per test, so do it
  when touching a test for another item, not as its own project.

## 4. Total estimate

| Item | Summed seconds saved | Effort |
|---|---:|---|
| 1. Gate `ShardOwnedMultiQueueCostIT` | 70 | Minutes |
| 2. Short lease in `ShardOwnedSpiFairShareIT` | 20 | Under an hour |
| 3. Released holds instead of `pg_sleep` | 65 | Half a day |
| 4. Elapsed-time seam for the stuck-handler bounds | 190 | One to two days |
| 5. Test-only stop budget | 60 to 80 | Half a day |
| 6. Age leases with SQL | 10 (up to 70) | Two hours |
| **Total, items 1 to 6** | **about 415 to 495 s (7 to 8 minutes)** | |
| 7. Container per fork | Not measured, plausibly 120 to 180 | A day |

Items 1 to 6 remove about 7 to 8 minutes of summed test time. At two Failsafe forks that is about 3.5 to 4 minutes of
wall clock for `postgresql-queue-shard-owned`, of which items 3 and 4 also cut `ShardOwnedLifecycleIT`, the longest
class in one fork, from about 344 s to about 210 s. The user's estimate of 5 to 6 minutes matches the summed figure for
items 1 to 5; whether it also holds for wall clock depends on how the two forks balance afterwards, so measure it.

## 5. Order of work

1. Items 1 and 2: minutes of work for 90 s, no production code.
2. Item 3: test code only, and the largest saving in the long-pole class.
3. Item 6, starting with profiling the three 31 s methods, which may turn up one shared cause.
4. Item 4: the largest saving, and the only one that needs a production-code seam; do it as its own commit with the
   canaries in place.
5. Item 5 after item 4, since several tests size their timeouts around the budget and item 4 changes those first.
6. Item 7 only if a measurement shows the container starts are worth it.

After each item: rerun the module, `scripts/test-timings.sh --csv > after-<item>.csv`, and compare with the baseline in
this document. Each converted test must still fail when the fix its Javadoc names is reverted; record which revert was
used in the commit message. Add a `docs/releases/NEXT_VERSION_RELEASE.md` entry only for item 4 or 5, if the seam is
visible to anyone outside the module (it should not be).

## 6. Do not do

- Do not just shorten `stuckHandlerTimeout`, `leaseTtl`, `shedGrace` or Awaitility bounds. Root `CLAUDE.md` already
  records that this module's timing-sensitive ITs flake under `-T 1C`, and the "stuck versus young key" tests are sized
  so the young key stays under the bound on a loaded build. Remove the wait, do not narrow the margin.
- Do not delete or merge review-fix tests because they look alike. Each guards a distinct defect, named in its Javadoc;
  folding two into one hides which one regressed.
- Do not put an injectable clock on everything. Lease TTLs, heartbeats and fencing are server-side `now()` by design
  (module `CLAUDE.md`: "All durable time is `now()`, server-side"); the seam in item 4 covers only the handler and
  kept-unit bounds that are already measured with `System.nanoTime()`.
- Do not raise `failsafe.forkCount` to hide the cost. Over-forking Docker makes this build slower, as the root
  `pom.xml` comment and `components/postgresql-event-store/pom.xml` explain.
- Do not combine item 7 with `-Dessentials.test.containers.reuse=true` and more than one fork.
- Do not move tests behind `benchmark.run` or `loadtest.skip` unless they only record numbers; a test that asserts a
  correctness property stays in the default build.
