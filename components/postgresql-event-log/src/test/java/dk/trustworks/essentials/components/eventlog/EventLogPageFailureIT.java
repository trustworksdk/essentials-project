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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * I14, plan §16 (design phase 4 §4, §8.1): a failing event is isolated by the step-down shrink, retried, then parked;
 * nothing else is lost or applied twice. Pages of 100 over 1 000 seeded events, one of them (offset 37 of the third
 * page) the poison. Also §8.2's "I/O failure retried without shrinking". Failed pages are counted through the page
 * hooks: a page that started and did not commit failed.
 */
class EventLogPageFailureIT extends AbstractPageIT {
    private static final String PROJECTION = "proj";
    private static final int    SEEDED     = 1_000;
    private static final int    POISON_AT  = 237;

    @BeforeEach
    void pageSettings() throws SQLException {
        restartEventLog(dataSource, FAST.withPageSize(100).withRetryBackoff(Duration.ofMillis(1), Duration.ofMillis(10)));
    }

    enum Failure {
        /** Fails in every page of several events and on its first attempt alone, then succeeds */
        TRANSIENT,
        /** Always fails */
        ALWAYS,
        /** Asks to be parked */
        PARK_EXCEPTION
    }

    /** The view projection, failing on every event of an aggregate whose id starts with {@code poison} */
    final class PoisonHandler implements PageHandler {
        final Failure         failure;
        final AtomicInteger   aloneFailures = new AtomicInteger();
        volatile Runnable     onFailure     = () -> {
        };
        volatile Runnable     onLastAlone   = () -> {
        };

        PoisonHandler(Failure failure) {
            this.failure = failure;
        }

        @Override
        public void handle(List<StoredEvent> page, PageTransaction tx) throws Exception {
            var connection = tx.connection();
            for (var event : page) {
                if (event.aggregateId().startsWith("poison")) {
                    fail(event, page.size() == 1);
                }
                apply(event, connection);
            }
        }

        private void fail(StoredEvent event, boolean alone) {
            switch (failure) {
                case TRANSIENT -> {
                    if (alone && aloneFailures.getAndIncrement() >= 1) {
                        return;
                    }
                }
                case PARK_EXCEPTION -> throw new ParkEventException(event, "poison " + event.globalOrder(), new IllegalStateException("bad payload"));
                case ALWAYS -> {
                }
            }
            onFailure.run();
            if (alone && failure == Failure.ALWAYS && aloneFailures.incrementAndGet() == eventLog.settings().retryAttempts() + 1) {
                onLastAlone.run();
            }
            throw new IllegalArgumentException("poison " + event.globalOrder());
        }
    }

    /** Page starts and commits, in order, from the delivery thread's hooks */
    static class PageHooks implements TestSeams.Hooks {
        final List<long[]> starts  = new CopyOnWriteArrayList<>();
        final AtomicLong   commits = new AtomicLong();

        @Override
        public void beforePageHandler(String subscriber, List<StoredEvent> page) throws Exception {
            starts.add(new long[]{page.isEmpty() ? -1 : page.getFirst().globalOrder(), page.size()});
        }

        @Override
        public void afterCommit(String subscriber, List<StoredEvent> page, long resumeFrom) throws Exception {
            commits.incrementAndGet();
        }

        long failedPages() {
            return starts.size() - commits.get();
        }
    }

    /**
     * {@link #SEEDED} single-event appends in one transaction, the one at {@link #POISON_AT} to aggregate {@code poison}
     *
     * @return the poison's global order
     */
    private long seed() throws Exception {
        var requests = new ArrayList<AppendRequest>();
        for (var i = 0; i < SEEDED; i++) {
            requests.add(AppendRequest.withoutExpectedOrder(TYPE, i == POISON_AT ? "poison" : "a-" + (i % 97), events("E", 1)));
        }
        appendCommitted(eventLog, requests.toArray(AppendRequest[]::new));
        var poison = queryLong("SELECT global_order FROM " + tables.eventTable() + " WHERE aggregate_id = 'poison'");
        assertThat(queryLong("SELECT count(*) FROM " + tables.eventTable() + " WHERE global_order < " + poison)).as("the poison's offset").isEqualTo(POISON_AT);
        return poison;
    }

    private Subscription start(ConsistencyMode mode, PageHandler handler, TestSeams.Hooks hooks, WatermarkMutant... mutants) throws SQLException {
        return subscribe(Subscription.SubscriptionSpec.inTransaction(new ProgressStore.Key(PROJECTION, TYPE, "node-1"), 1, mode, handler,
                                                                     new TestSeams(Set.of(mutants), hooks, null)));
    }

    private Map<Long, String> parkedRows() throws SQLException {
        var rows = new TreeMap<Long, String>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT global_order, blocked_by, attempts, error_type, error_message FROM " + EventLogSchema.PARKED_EVENTS_TABLE +
                                                            " WHERE subscriber = '" + PROJECTION + "'")) {
            while (resultSet.next()) {
                rows.put(resultSet.getLong(1), resultSet.getObject(2) + "/" + resultSet.getInt(3) + "/" + resultSet.getString(4) + "/" + resultSet.getString(5));
            }
        }
        return rows;
    }

    // ---- (a) and GROW_INSIDE_SUSPECT_RANGE ----

    @Test
    void a_transient_failure_is_isolated_within_eight_failed_pages_and_applied_once() throws Exception {
        var hooks = isolateATransientFailure();
        assertThat(hooks.failedPages()).as("failed pages").isLessThanOrEqualTo(8);
        assertThat(parkedRows()).isEmpty();
        assertThat(foldDifferences()).isEmpty();
    }

    @Test
    void must_fail_growing_inside_the_suspect_range_exceeds_eight_failed_pages() throws Exception {
        var hooks = isolateATransientFailure(WatermarkMutant.GROW_INSIDE_SUSPECT_RANGE);
        assertThat(hooks.failedPages()).as("failed pages").isGreaterThan(8);
    }

    private PageHooks isolateATransientFailure(WatermarkMutant... mutants) throws Exception {
        seed();
        var hooks = new PageHooks();
        start(ConsistencyMode.CONTIGUOUS, new PoisonHandler(Failure.TRANSIENT), hooks, mutants);
        awaitCaughtUp(PROJECTION);
        return hooks;
    }

    // ---- (b) and NO_SHRINK ----

    @Test
    void an_event_that_always_fails_is_parked_after_its_retries_and_nothing_else_is_lost() throws Exception {
        var poison = alwaysFailing(ConsistencyMode.CONTIGUOUS);
        assertThat(parkedRows()).containsExactly(Map.entry(poison, "null/4/java.lang.IllegalArgumentException/poison " + poison));
        assertThat(foldDifferences(Set.of(poison), true)).isEmpty();
    }

    @Test
    void fresh_in_transaction_parks_an_event_that_always_fails_too() throws Exception {
        var poison = alwaysFailing(ConsistencyMode.FRESH);
        assertThat(parkedRows()).containsExactly(Map.entry(poison, "null/4/java.lang.IllegalArgumentException/poison " + poison));
        assertThat(foldDifferences(Set.of(poison), true)).isEmpty();
    }

    @Test
    void must_fail_without_shrinking_the_wrong_events_are_parked() throws Exception {
        var poison = alwaysFailing(ConsistencyMode.CONTIGUOUS, WatermarkMutant.NO_SHRINK);
        assertThat(foldDifferences(Set.of(poison), true)).isNotEmpty();
    }

    private long alwaysFailing(ConsistencyMode mode, WatermarkMutant... mutants) throws Exception {
        var poison = seed();
        start(mode, new PoisonHandler(Failure.ALWAYS), TestSeams.Hooks.NONE, mutants);
        awaitCaughtUp(PROJECTION);
        return poison;
    }

    // ---- (c) ----

    @Test
    void a_park_request_parks_the_named_event_at_once() throws Exception {
        var poison = seed();
        var hooks  = new PageHooks();
        start(ConsistencyMode.CONTIGUOUS, new PoisonHandler(Failure.PARK_EXCEPTION), hooks);
        awaitCaughtUp(PROJECTION);
        assertThat(parkedRows()).containsExactly(Map.entry(poison, "null/1/java.lang.IllegalStateException/poison " + poison));
        assertThat(hooks.failedPages()).as("only the page that asked").isEqualTo(1);
        assertThat(foldDifferences(Set.of(poison), true)).isEmpty();
    }

    // ---- (d) and PARK_OUTSIDE_PAGE_TRANSACTION ----

    enum Point {
        BEFORE_PAGE_HANDLER,
        AFTER_HANDLER_BEFORE_PROGRESS,
        AFTER_PROGRESS_BEFORE_COMMIT,
        AFTER_COMMIT,
        ON_PARK
    }

    /** Throws {@link TestSeams.Crash} the first time {@code point} is reached after it was armed */
    static final class ArmedCrash extends PageHooks {
        final Point         point;
        final AtomicBoolean armed   = new AtomicBoolean();
        final AtomicBoolean crashed = new AtomicBoolean();

        ArmedCrash(Point point) {
            this.point = point;
        }

        void maybeCrash(Point at) {
            if (at == point && armed.compareAndSet(true, false)) {
                crashed.set(true);
                throw new TestSeams.Crash(at.name());
            }
        }

        @Override
        public void beforePageHandler(String subscriber, List<StoredEvent> page) throws Exception {
            super.beforePageHandler(subscriber, page);
            maybeCrash(Point.BEFORE_PAGE_HANDLER);
        }

        @Override
        public void afterHandlerBeforeProgress(String subscriber, List<StoredEvent> page) {
            maybeCrash(Point.AFTER_HANDLER_BEFORE_PROGRESS);
        }

        @Override
        public void afterProgressBeforeCommit(String subscriber, List<StoredEvent> page, long resumeFrom) {
            maybeCrash(Point.AFTER_PROGRESS_BEFORE_COMMIT);
        }

        @Override
        public void afterCommit(String subscriber, List<StoredEvent> page, long resumeFrom) throws Exception {
            super.afterCommit(subscriber, page, resumeFrom);
            maybeCrash(Point.AFTER_COMMIT);
        }

        @Override
        public void onPark(String subscriber, long globalOrder, Long blockedBy) {
            maybeCrash(Point.ON_PARK);
        }
    }

    /** {@code parking}: armed once the poison's retries are used up (the next page parks it); else at its first failure */
    record Round(Point point, boolean parking) {
    }

    @Test
    void a_crash_at_any_hook_during_isolation_and_parking_still_parks_the_event_once_and_loses_nothing() throws Exception {
        var rounds = new ArrayList<Round>();
        for (var point : Point.values()) {
            rounds.add(new Round(point, true));
            if (point != Point.ON_PARK) {
                rounds.add(new Round(point, false));
            }
        }
        assertThat(crashRounds(rounds)).isEmpty();
    }

    @Test
    void must_fail_parking_outside_the_page_transaction_loses_the_event_on_a_crash() throws Exception {
        assertThat(crashRounds(List.of(new Round(Point.ON_PARK, true)), WatermarkMutant.PARK_OUTSIDE_PAGE_TRANSACTION)).isNotEmpty();
    }

    /**
     * Per round: 30 events, one of them a new poison; a subscription that crashes at the round's point, then the page
     * connection's backend terminated. Finally a subscription without a crash catches up.
     *
     * @return what differs: a poison not parked exactly once with all its attempts, or the view against the reference fold
     */
    private List<String> crashRounds(List<Round> rounds, WatermarkMutant... mutants) throws Exception {
        var poisons = new ArrayList<Long>();
        for (var r = 0; r < rounds.size(); r++) {
            var round    = rounds.get(r);
            var requests = new ArrayList<AppendRequest>();
            for (var i = 0; i < 30; i++) {
                requests.add(AppendRequest.withoutExpectedOrder(TYPE, i == 17 ? "poison-" + r : "b-" + (i % 7), events("E", 1)));
            }
            appendCommitted(eventLog, requests.toArray(AppendRequest[]::new));
            poisons.add(queryLong("SELECT global_order FROM " + tables.eventTable() + " WHERE aggregate_id = 'poison-" + r + "'"));
            var hooks   = new ArmedCrash(round.point());
            var handler = new PoisonHandler(Failure.ALWAYS);
            if (round.point() == Point.ON_PARK) {
                hooks.armed.set(true);
            } else if (round.parking()) {
                handler.onLastAlone = () -> hooks.armed.set(true);
            } else {
                handler.onFailure = () -> hooks.armed.set(true);
            }
            var subscription = start(ConsistencyMode.CONTIGUOUS, handler, hooks, mutants);
            await().atMost(WAIT).until(hooks.crashed::get);
            cleanUpCrash(subscription);
        }
        start(ConsistencyMode.CONTIGUOUS, new PoisonHandler(Failure.ALWAYS), TestSeams.Hooks.NONE, mutants);
        commitAppend("tail", 1);
        awaitCaughtUp(PROJECTION);
        var differences = new ArrayList<String>();
        var parked      = parkedRows();
        for (var poison : poisons) {
            var row = parked.get(poison);
            if (row == null || !row.startsWith("null/4/")) {
                differences.add("poison " + poison + " parked as " + row);
            }
        }
        if (parked.size() != poisons.size()) {
            differences.add("parked " + parked.keySet() + ", poisons " + poisons);
        }
        differences.addAll(foldDifferences(new HashSet<>(poisons), true));
        return differences;
    }

    // ---- I/O failure ----

    @Test
    void an_io_failure_retries_the_same_page_without_shrinking_or_parking() throws Exception {
        seed();
        var trigger    = queryLong("SELECT global_order FROM " + tables.eventTable() + " ORDER BY global_order OFFSET 150 LIMIT 1");
        var terminated = new AtomicBoolean();
        var hooks      = new PageHooks();
        PageHandler handler = (page, tx) -> {
            var connection = tx.connection();
            for (var event : page) {
                if (event.globalOrder() == trigger && terminated.compareAndSet(false, true)) {
                    long pid;
                    try (var statement = connection.createStatement(); var resultSet = statement.executeQuery("SELECT pg_backend_pid()")) {
                        resultSet.next();
                        pid = resultSet.getLong(1);
                    }
                    execute("SELECT pg_terminate_backend(" + pid + ")");
                }
                apply(event, connection);
            }
        };
        start(ConsistencyMode.CONTIGUOUS, handler, hooks);
        awaitCaughtUp(PROJECTION);
        assertThat(terminated).isTrue();
        assertThat(hooks.failedPages()).isEqualTo(1);
        var sizes = hooks.starts.stream().map(start -> start[1]).distinct().toList();
        assertThat(sizes).as("page sizes").containsExactly(100L);
        var firsts = hooks.starts.stream().map(start -> start[0]).toList();
        assertThat(firsts).as("pages").hasSize(SEEDED / 100 + 1);
        var retried = new ArrayList<Long>();
        for (var i = 1; i < firsts.size(); i++) {
            if (firsts.get(i).equals(firsts.get(i - 1))) {
                retried.add(firsts.get(i));
            }
        }
        assertThat(retried).as("the failed page, retried as it was").hasSize(1);
        assertThat(retried.getFirst()).isLessThanOrEqualTo(trigger).isGreaterThan(trigger - 100);
        assertThat(parkedRows()).isEmpty();
        assertThat(foldDifferences()).isEmpty();
    }
}
