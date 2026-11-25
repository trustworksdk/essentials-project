/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dk.trustworks.essentials.components.eventlog;

import org.junit.jupiter.api.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hold the rest of the aggregate: once an event is parked, every later event of its
 * aggregate is parked behind it ({@code blocked_by}) and never handled, while every other aggregate continues; the held
 * set survives a restart; {@code retryParked} applies the aggregate in order, {@code discardParked} without its head; a
 * reset deletes the parked rows. {@code A1} (the first event of aggregate {@code A}) fails while the handler is broken.
 */
class EventLogParkedAggregateIT extends AbstractPageIT {
    private static final String PROJECTION = "proj";

    private volatile boolean broken = true;

    @BeforeEach
    void pageSettings() throws SQLException {
        restartEventLog(dataSource, FAST.withRetryBackoff(Duration.ofMillis(1), Duration.ofMillis(10)));
    }

    /** Set: A1 fails with a {@link ParkEventException}, a terminal park only an operator retries */
    private volatile boolean terminalA1;

    private void maybeTerminal(StoredEvent event) {
        if (terminalA1 && event.aggregateId().equals("A") && event.eventOrder() == 0) {
            throw new ParkEventException(event, "A1 cannot be handled");
        }
    }

    /** Set: A3 (the third event of aggregate A) fails too, as an event poisoned by the same cause would */
    private volatile boolean poisonA3;

    private void maybeFail(StoredEvent event) {
        if (broken && event.aggregateId().equals("A") && event.eventOrder() == 0) {
            throw new IllegalStateException("A1 fails");
        }
        if (poisonA3 && event.aggregateId().equals("A") && event.eventOrder() == 2) {
            throw new IllegalStateException("A3 fails");
        }
    }

    /** Set: the next handling of A1 signals {@link #blockedInRetry} and sleeps until interrupted */
    private volatile boolean                         blockA1;
    private final java.util.concurrent.CountDownLatch blockedInRetry = new java.util.concurrent.CountDownLatch(1);

    private void maybeBlock(StoredEvent event) throws InterruptedException {
        if (blockA1 && event.aggregateId().equals("A") && event.eventOrder() == 0) {
            blockedInRetry.countDown();
            Thread.sleep(Duration.ofMinutes(5));
        }
    }

    private Subscription start(WatermarkMutant... mutants) throws SQLException {
        return start(null, mutants);
    }

    private Subscription start(ErrorSettings errors, WatermarkMutant... mutants) throws SQLException {
        return start(errors, TestSeams.Hooks.NONE, mutants);
    }

    private Subscription start(ErrorSettings errors, TestSeams.Hooks hooks, WatermarkMutant... mutants) throws SQLException {
        PageHandler handler = PageHandler.perEvent((event, tx) -> {
            var connection = tx.connection();
            maybeTerminal(event);
            maybeFail(event);
            maybeBlock(event);
            apply(event, connection);
        });
        var spec = Subscription.SubscriptionSpec.inTransaction(new ProgressStore.Key(PROJECTION, TYPE, "node-1"), 1, ConsistencyMode.CONTIGUOUS,
                                                               handler, new TestSeams(Set.of(mutants), hooks, null));
        return subscribe(errors == null ? spec : spec.withErrors(errors));
    }

    /** @return global order -> blocked_by (or {@code head/attempts} for a head) of every parked row */
    private Map<Long, String> parkedRows() throws SQLException {
        var rows = new TreeMap<Long, String>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT global_order, blocked_by, attempts FROM " + EventLogSchema.PARKED_EVENTS_TABLE)) {
            while (resultSet.next()) {
                var blockedBy = resultSet.getObject(2);
                rows.put(resultSet.getLong(1), blockedBy == null ? "head/" + resultSet.getInt(3) : "behind " + blockedBy);
            }
        }
        return rows;
    }

    /** @return {@code count/digest} of {@code aggregateId}'s view row, or {@code null} */
    private String viewRow(String aggregateId) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("SELECT count, digest FROM " + VIEW + " WHERE aggregate_id = ?")) {
            statement.setString(1, aggregateId);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) + "/" + resultSet.getString(2) : null;
            }
        }
    }

    /** @return the view row applying {@code globalOrders} in this order would give */
    private static String chain(long... globalOrders) throws Exception {
        var digest = "";
        for (var g : globalOrders) {
            digest = md5(digest + g);
        }
        return globalOrders.length + "/" + digest;
    }

    /** {@code A1, B, A2, B, A3, B}, each committed on its own; @return the A global orders */
    private long[] parkA1() throws Exception {
        var a1 = commitAppend("A", 1);
        commitAppend("B", 1);
        var a2 = commitAppend("A", 1);
        commitAppend("B", 1);
        var a3 = commitAppend("A", 1);
        commitAppend("B", 1);
        awaitCaughtUp(PROJECTION);
        return new long[]{a1, a2, a3};
    }

    @Test
    void later_events_of_a_parked_aggregate_are_held_behind_it_across_a_restart_until_it_is_retried() throws Exception {
        assertThat(holdRestartRetry()).isEmpty();
    }

    @Test
    void must_fail_without_the_aggregate_hold_a_later_event_is_applied_after_its_parked_head() throws Exception {
        assertThat(holdRestartRetry(WatermarkMutant.NO_AGGREGATE_HOLD)).isNotEmpty();
    }

    @Test
    void must_fail_without_reloading_the_held_set_a_restart_applies_a_later_event() throws Exception {
        assertThat(holdRestartRetry(WatermarkMutant.HELD_SET_NOT_RELOADED)).isNotEmpty();
    }

    /** @return every check that failed, in order */
    private List<String> holdRestartRetry(WatermarkMutant... mutants) throws Exception {
        var problems     = new ArrayList<String>();
        var subscription = start(mutants);
        var a            = parkA1();
        check(problems, "held", parkedRows(), Map.of(a[0], "head/4", a[1], "behind " + a[0], a[2], "behind " + a[0]));
        check(problems, "A while held", viewRow("A"), null);
        check(problems, "B", viewRow("B").split("/")[0], "3");

        subscription.stop();
        subscription = start(mutants);
        var a4 = commitAppend("A", 1);
        commitAppend("B", 1);
        awaitCaughtUp(PROJECTION);
        check(problems, "A4 after a restart", parkedRows().get(a4), "behind " + a[0]);
        check(problems, "A after a restart", viewRow("A"), null);

        broken = false;
        check(problems, "retry", subscription.retryParked(a[0]).get(30, TimeUnit.SECONDS), true);
        check(problems, "A after the retry", viewRow("A"), chain(a[0], a[1], a[2], a4));
        check(problems, "parked after the retry", parkedRows(), Map.of());

        var a5 = commitAppend("A", 1);
        awaitCaughtUp(PROJECTION);
        check(problems, "A5 after the retry", viewRow("A"), chain(a[0], a[1], a[2], a4, a5));
        problems.addAll(foldDifferences(Set.of(), false));
        return problems;
    }

    private static void check(List<String> problems, String what, Object actual, Object expected) {
        if (!Objects.equals(actual, expected)) {
            problems.add(what + ": expected " + expected + ", was " + actual);
        }
    }

    @Test
    void stopping_during_a_parked_retry_rolls_its_transaction_back_and_releases_the_progress_row() throws Exception {
        var subscription = start();
        var a            = parkA1();
        broken = false;
        blockA1 = true;
        var retry = subscription.retryParked(a[0]);
        assertThat(blockedInRetry.await(30, TimeUnit.SECONDS)).isTrue();

        subscription.stop();

        assertThat(subscription.isRunning()).isFalse();
        assertThat(queryLong("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND state LIKE 'idle in transaction%'"))
                .as("no serve transaction left open").isZero();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL lock_timeout = '2s'");
            statement.execute("SELECT 1 FROM " + EventLogSchema.PROGRESS_TABLE + " WHERE subscriber = '" + PROJECTION + "' FOR UPDATE");
            connection.rollback();
        }
        assertThat(parkedRows()).as("the retry did not happen, A stays parked").hasSize(3);
        assertThat(retry).isDone();
    }

    @Test
    void discarding_a_parked_aggregate_applies_the_events_held_behind_its_head() throws Exception {
        var subscription = start();
        var a            = parkA1();
        assertThat(parkedRows()).hasSize(3);
        assertThat(subscription.discardParked(a[0]).get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(viewRow("A")).isEqualTo(chain(a[1], a[2]));
        assertThat(parkedRows()).isEmpty();
        var a4 = commitAppend("A", 1);
        awaitCaughtUp(PROJECTION);
        assertThat(viewRow("A")).isEqualTo(chain(a[1], a[2], a4));
        assertThat(foldDifferences(Set.of(a[0]), false)).isEmpty();
    }

    @Test
    void a_held_event_that_fails_on_a_discard_becomes_the_head_and_the_events_before_it_are_applied() throws Exception {
        var subscription = start();
        var a            = parkA1();
        poisonA3 = true;
        assertThat(subscription.discardParked(a[0]).get(30, TimeUnit.SECONDS)).as("the discard commits what it applied").isTrue();
        assertThat(viewRow("A")).as("A2 applied once, A3 not").isEqualTo(chain(a[1]));
        assertThat(parkedRows()).as("A3 heads the aggregate, one attempt counted").isEqualTo(Map.of(a[2], "head/1"));

        var a4 = commitAppend("A", 1);
        awaitCaughtUp(PROJECTION);
        assertThat(parkedRows()).as("a later event of A waits behind the new head").isEqualTo(Map.of(a[2], "head/1", a4, "behind " + a[2]));

        poisonA3 = false;
        assertThat(subscription.retryParked(a[2]).get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(viewRow("A")).isEqualTo(chain(a[1], a[2], a4));
        assertThat(parkedRows()).isEmpty();
    }

    @Test
    void a_periodic_retry_parks_a_failing_held_event_as_the_new_head() throws Exception {
        var handled = new CopyOnWriteArrayList<StoredEvent>();
        PeriodicHandler handler = event -> {
            maybeFail(event);
            handled.add(event);
        };
        var subscription = subscribe(Subscription.SubscriptionSpec.periodic(new ProgressStore.Key(PROJECTION, TYPE, "node-1"), 1, ConsistencyMode.FRESH, handler,
                                                                            TestSeams.NONE));
        var a = parkA1();
        broken = false;
        poisonA3 = true;
        assertThat(subscription.retryParked(a[0]).get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(handled).extracting(StoredEvent::globalOrder).as("A1 and A2 handled, A3 not").endsWith(a[0], a[1]).doesNotContain(a[2]);
        assertThat(parkedRows()).isEqualTo(Map.of(a[2], "head/1"));

        poisonA3 = false;
        assertThat(subscription.retryParked(a[2]).get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(handled).extracting(StoredEvent::globalOrder).endsWith(a[0], a[1], a[2]);
        assertThat(parkedRows()).isEmpty();
    }

    @Test
    void a_retry_applies_a_long_held_backlog_one_page_per_transaction_in_order() throws Exception {
        var subscription = start(new ErrorSettings(3, Duration.ofMillis(1), Duration.ofMillis(10), 2));
        var a  = parkA1();
        var a4 = commitAppend("A", 1);
        var a5 = commitAppend("A", 1);
        awaitCaughtUp(PROJECTION);
        assertThat(parkedRows()).hasSize(5);

        broken = false;
        assertThat(subscription.retryParked(a[0]).get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(viewRow("A")).as("the first page: A1 and A2").isEqualTo(chain(a[0], a[1]));
        awaitTrue(() -> {
            try {
                return parkedRows().isEmpty();
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(viewRow("A")).isEqualTo(chain(a[0], a[1], a[2], a4, a5));
    }

    @Test
    void a_reset_deletes_the_parked_rows_and_releases_the_held_aggregate() throws Exception {
        start();
        parkA1();
        assertThat(parkedRows()).hasSize(3);
        broken = false;
        assertThat(eventLog.reset(PROJECTION, TYPE, "node-1", 1)).isTrue();
        assertThat(parkedRows()).isEmpty();
        // The next page meets the new epoch, repositions at 1 and replays everything, A1 included
        commitAppend("C", 1);
        awaitTrue(() -> {
            try {
                return viewRow("A") != null && viewRow("C") != null;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        awaitCaughtUp(PROJECTION);
        assertThat(parkedRows()).isEmpty();
    }

    @Test
    void a_periodic_fresh_subscription_holds_the_aggregate_too() throws Exception {
        var handled = new CopyOnWriteArrayList<StoredEvent>();
        PeriodicHandler handler = event -> {
            maybeFail(event);
            handled.add(event);
        };
        subscribe(Subscription.SubscriptionSpec.periodic(new ProgressStore.Key(PROJECTION, TYPE, "node-1"), 1, ConsistencyMode.FRESH, handler,
                                                         TestSeams.NONE));
        var a = parkA1();
        assertThat(parkedRows()).isEqualTo(Map.of(a[0], "head/4", a[1], "behind " + a[0], a[2], "behind " + a[0]));
        assertThat(handled).extracting(StoredEvent::aggregateId).containsExactly("B", "B", "B");
    }

    // ---- the automatic retry of parked aggregates (I17) ----

    /** Retried by the engine 50 ms after parking, doubling to 200 ms */
    private static final ErrorSettings SHORT_RETRY = new ErrorSettings(3, Duration.ofMillis(1), Duration.ofMillis(10), 100).withParkedRetry(Duration.ofMillis(50),
                                                                                                                                             Duration.ofMillis(200));

    /** The automatic retries the delivery thread is about to serve, by head */
    private final List<Long> autoRetries = new CopyOnWriteArrayList<>();

    private TestSeams.Hooks countingAutoRetries() {
        return new TestSeams.Hooks() {
            @Override
            public void beforeParkedRetry(String subscriber, long head, boolean auto) {
                if (auto) {
                    autoRetries.add(head);
                }
            }
        };
    }

    private Subscription startPeriodic(ErrorSettings errors, List<StoredEvent> handled, TestSeams.Hooks hooks, WatermarkMutant... mutants) throws SQLException {
        PeriodicHandler handler = event -> {
            maybeTerminal(event);
            maybeFail(event);
            handled.add(event);
        };
        return subscribe(Subscription.SubscriptionSpec.periodic(new ProgressStore.Key(PROJECTION, TYPE, "node-1"), 1, ConsistencyMode.FRESH, handler,
                                                                new TestSeams(Set.of(mutants), hooks, null)).withErrors(errors));
    }

    /**
     * Wait until {@code done} holds, or until {@code atLeast} passed and the attached loop went round three more times
     * after that - long enough for every automatic retry due by then to have been served
     */
    private static boolean awaitOrPasses(Subscription subscription, Duration atLeast, java.util.function.BooleanSupplier done) throws InterruptedException {
        var deadline = System.nanoTime() + WAIT.toNanos();
        var passed   = System.nanoTime() + atLeast.toNanos();
        long iterationsAtPassed = -1;
        while (System.nanoTime() - deadline < 0) {
            if (done.getAsBoolean()) {
                return true;
            }
            if (System.nanoTime() - passed >= 0) {
                if (iterationsAtPassed < 0) {
                    iterationsAtPassed = subscription.iterations();
                } else if (subscription.iterations() >= iterationsAtPassed + 3) {
                    return done.getAsBoolean();
                }
            }
            Thread.sleep(5);
        }
        return done.getAsBoolean();
    }

    private boolean noneParked() {
        try {
            return parkedRows().isEmpty();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** @return the retry columns of {@code globalOrder}'s row: {@code retries/terminal/next set/last failure set} */
    private String schedule(long globalOrder) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT retries, terminal, next_retry_at IS NOT NULL, last_failed_at IS NOT NULL FROM " + EventLogSchema.PARKED_EVENTS_TABLE +
                                                                 " WHERE global_order = ?")) {
            statement.setLong(1, globalOrder);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) + "/" + resultSet.getBoolean(2) + "/" + resultSet.getBoolean(3) + "/" + resultSet.getBoolean(4) : null;
            }
        }
    }

    /** @return every check that failed: a transient failure of an in-transaction subscription heals with no operator */
    private List<String> healsInTransaction(WatermarkMutant... mutants) throws Exception {
        var problems     = new ArrayList<String>();
        var subscription = start(SHORT_RETRY, countingAutoRetries(), mutants);
        var a            = parkA1();
        check(problems, "parked", parkedRows().size(), 3);
        check(problems, "head scheduled", schedule(a[0]).replaceFirst("^\\d+/", ""), "false/true/true");
        check(problems, "held rows unscheduled", schedule(a[1]), "0/false/false/false");
        var a4 = commitAppend("A", 1);
        awaitCaughtUp(PROJECTION);
        broken = false;
        check(problems, "healed", awaitOrPasses(subscription, Duration.ofSeconds(2), this::noneParked), true);
        check(problems, "A in order", viewRow("A"), chain(a[0], a[1], a[2], a4));
        check(problems, "served automatically", autoRetries.contains(a[0]), true);
        problems.addAll(foldDifferences(Set.of(), false));
        return problems;
    }

    @Test
    void a_transient_failure_of_an_in_transaction_subscription_heals_without_an_operator_in_order() throws Exception {
        assertThat(healsInTransaction()).isEmpty();
    }

    @Test
    void must_fail_when_nothing_is_ever_due_a_transient_failure_waits_for_an_operator() throws Exception {
        assertThat(healsInTransaction(WatermarkMutant.PARKED_RETRY_NEVER_DUE)).isNotEmpty();
    }

    @Test
    void a_transient_failure_of_a_periodic_subscription_heals_without_an_operator_in_order() throws Exception {
        var handled      = new CopyOnWriteArrayList<StoredEvent>();
        var subscription = startPeriodic(SHORT_RETRY, handled, countingAutoRetries());
        var a            = parkA1();
        assertThat(handled).extracting(StoredEvent::aggregateId).containsExactly("B", "B", "B");
        broken = false;
        assertThat(awaitOrPasses(subscription, Duration.ofSeconds(2), this::noneParked)).isTrue();
        assertThat(handled).extracting(StoredEvent::globalOrder).endsWith(a[0], a[1], a[2]);
        assertThat(autoRetries).contains(a[0]);
    }

    @Test
    void more_events_than_a_page_held_behind_a_head_are_handed_on_across_transactions_in_order() throws Exception {
        var subscription = start(new ErrorSettings(3, Duration.ofMillis(1), Duration.ofMillis(10), 2).withParkedRetry(Duration.ofMillis(50), Duration.ofMillis(200)),
                                 countingAutoRetries());
        var a  = parkA1();
        var a4 = commitAppend("A", 1);
        var a5 = commitAppend("A", 1);
        awaitCaughtUp(PROJECTION);
        assertThat(parkedRows()).hasSize(5);
        broken = false;
        assertThat(awaitOrPasses(subscription, Duration.ofSeconds(2), this::noneParked)).isTrue();
        assertThat(viewRow("A")).isEqualTo(chain(a[0], a[1], a[2], a4, a5));
        assertThat(autoRetries).contains(a[0]);
    }

    @Test
    void a_held_event_that_fails_on_an_automatic_retry_becomes_the_head_with_its_schedule_reset() throws Exception {
        var gate    = new java.util.concurrent.CountDownLatch(1);
        var reached = new java.util.concurrent.CountDownLatch(1);
        var a3      = new java.util.concurrent.atomic.AtomicLong(-1);
        var subscription = start(SHORT_RETRY, new TestSeams.Hooks() {
            @Override
            public void beforeParkedRetry(String subscriber, long head, boolean auto) throws Exception {
                if (auto && head == a3.get()) {
                    reached.countDown();
                    gate.await();
                }
            }
        });
        var a = parkA1();
        // A1 fails a few automatic retries, so its retries are above zero before A3 takes over
        awaitTrue(() -> {
            try {
                return Integer.parseInt(schedule(a[0]).split("/")[0]) >= 2;
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        a3.set(a[2]);
        poisonA3 = true;
        broken = false;
        assertThat(reached.await(30, TimeUnit.SECONDS)).as("A3's first automatic retry").isTrue();
        assertThat(viewRow("A")).isEqualTo(chain(a[0], a[1]));
        assertThat(parkedRows()).isEqualTo(Map.of(a[2], "head/1"));
        assertThat(schedule(a[2])).as("a new head starts its schedule over").isEqualTo("0/false/true/true");
        poisonA3 = false;
        gate.countDown();
        assertThat(awaitOrPasses(subscription, Duration.ofSeconds(2), this::noneParked)).isTrue();
        assertThat(viewRow("A")).isEqualTo(chain(a[0], a[1], a[2]));
    }

    /** @return every check that failed: a {@link ParkEventException} head is never retried automatically */
    private List<String> terminalStays(WatermarkMutant... mutants) throws Exception {
        var problems     = new ArrayList<String>();
        terminalA1 = true;
        var subscription = start(SHORT_RETRY, countingAutoRetries(), mutants);
        var a            = parkA1();
        check(problems, "terminal head", schedule(a[0]), "0/true/false/true");
        terminalA1 = false;
        broken = false;
        awaitOrPasses(subscription, Duration.ofSeconds(1), () -> !autoRetries.isEmpty());
        check(problems, "no automatic retry", autoRetries, List.of());
        check(problems, "still parked", parkedRows().size(), 3);
        check(problems, "operator retry", subscription.retryParked(a[0]).get(30, TimeUnit.SECONDS), true);
        check(problems, "A in order", viewRow("A"), chain(a[0], a[1], a[2]));
        return problems;
    }

    @Test
    void a_park_event_exception_head_is_never_retried_automatically() throws Exception {
        assertThat(terminalStays()).isEmpty();
    }

    @Test
    void must_fail_when_a_terminal_head_is_scheduled_it_is_retried_automatically() throws Exception {
        assertThat(terminalStays(WatermarkMutant.PARKED_RETRY_TERMINAL_RETRIED)).isNotEmpty();
    }

    @Test
    void the_latest_failure_decides_whether_a_head_is_terminal() throws Exception {
        terminalA1 = true;
        var subscription = start(SHORT_RETRY, countingAutoRetries());
        var a            = parkA1();
        assertThat(schedule(a[0])).isEqualTo("0/true/false/true");

        // An operator retry that fails with an ordinary exception: the head retries by itself from now on
        terminalA1 = false;
        assertThat(subscription.retryParked(a[0]).get(30, TimeUnit.SECONDS)).isFalse();
        assertThat(schedule(a[0])).startsWith("1/false/true/");
        awaitTrue(() -> autoRetries.contains(a[0]));

        // An automatic retry that fails with a ParkEventException: terminal again, never retried by itself
        terminalA1 = true;
        awaitTrue(() -> {
            try {
                return schedule(a[0]).contains("/true/false/");
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        autoRetries.clear();
        terminalA1 = false;
        broken = false;
        awaitOrPasses(subscription, Duration.ofSeconds(1), () -> !autoRetries.isEmpty());
        assertThat(autoRetries).isEmpty();
        assertThat(parkedRows()).hasSize(3);
        assertThat(subscription.retryParked(a[0]).get(30, TimeUnit.SECONDS)).isTrue();
        assertThat(viewRow("A")).isEqualTo(chain(a[0], a[1], a[2]));
    }

    /** @return every check that failed: each failed retry doubles the stored wait, up to the cap */
    private List<String> backoffAdvances(WatermarkMutant... mutants) throws Exception {
        var problems = new ArrayList<String>();
        start(new ErrorSettings(3, Duration.ofMillis(1), Duration.ofMillis(10), 100).withParkedRetry(Duration.ofMillis(20), Duration.ofSeconds(10)), countingAutoRetries(),
              mutants);
        var a = parkA1();
        awaitTrue(() -> autoRetries.size() >= 3);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT retries, round(extract(epoch FROM next_retry_at - last_failed_at) * 1000) FROM " +
                                                                 EventLogSchema.PARKED_EVENTS_TABLE + " WHERE global_order = ?")) {
            statement.setLong(1, a[0]);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                var retries = resultSet.getInt(1);
                check(problems, "retries counted", retries >= 2, true);
                check(problems, "wait after " + retries + " failed retries", resultSet.getLong(2), 20L << retries);
            }
        }
        return problems;
    }

    @Test
    void each_failed_retry_doubles_the_wait_before_the_next() throws Exception {
        assertThat(backoffAdvances()).isEmpty();
    }

    @Test
    void must_fail_when_the_backoff_is_not_advanced_the_wait_stays_at_the_first() throws Exception {
        assertThat(backoffAdvances(WatermarkMutant.PARKED_RETRY_BACKOFF_NOT_ADVANCED)).isNotEmpty();
    }

    /** @return the parked statements a subscription with nothing held sent while it delivered */
    private long statementsWhileNothingHeld(WatermarkMutant... mutants) throws Exception {
        broken = false;
        var subscription = start(SHORT_RETRY, countingAutoRetries(), mutants);
        for (var i = 0; i < 5; i++) {
            commitAppend("B", 1);
        }
        awaitCaughtUp(PROJECTION);
        var iterations = subscription.iterations();
        awaitTrue(() -> subscription.iterations() >= iterations + 3);
        return subscription.parkedStatements();
    }

    @Test
    void the_due_check_sends_no_statement_while_nothing_is_held() throws Exception {
        assertThat(statementsWhileNothingHeld()).isZero();
    }

    @Test
    void must_fail_when_the_due_check_reads_while_idle_it_sends_statements() throws Exception {
        assertThat(statementsWhileNothingHeld(WatermarkMutant.PARKED_RETRY_STATEMENT_WHEN_IDLE)).isPositive();
    }

    /** @return every check that failed: a restart continues the stored schedule (an hour away) instead of retrying at once */
    private List<String> restartKeepsSchedule(WatermarkMutant... mutants) throws Exception {
        var problems     = new ArrayList<String>();
        var subscription = start(null, countingAutoRetries(), mutants);
        var a            = parkA1();
        broken = false;
        subscription.stop();
        subscription = start(null, countingAutoRetries(), mutants);
        commitAppend("B", 1);
        awaitCaughtUp(PROJECTION);
        awaitOrPasses(subscription, Duration.ofMillis(500), () -> !autoRetries.isEmpty());
        check(problems, "no retry before the stored due time", autoRetries, List.of());
        check(problems, "still parked", parkedRows().size(), 3);
        return problems;
    }

    @Test
    void a_restart_continues_the_stored_retry_schedule() throws Exception {
        assertThat(restartKeepsSchedule()).isEmpty();
    }

    @Test
    void must_fail_when_the_schedule_is_not_loaded_a_restart_retries_at_once() throws Exception {
        assertThat(restartKeepsSchedule(WatermarkMutant.PARKED_RETRY_SCHEDULE_NOT_LOADED)).isNotEmpty();
    }
}
