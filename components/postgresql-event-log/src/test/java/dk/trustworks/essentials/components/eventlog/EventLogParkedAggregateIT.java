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

    private void maybeFail(StoredEvent event) {
        if (broken && event.aggregateId().equals("A") && event.eventOrder() == 0) {
            throw new IllegalStateException("A1 fails");
        }
    }

    private Subscription start(WatermarkMutant... mutants) throws SQLException {
        PageHandler handler = PageHandler.perEvent((event, tx) -> {
            var connection = tx.connection();
            maybeFail(event);
            apply(event, connection);
        });
        return subscribe(Subscription.SubscriptionSpec.inTransaction(new ProgressStore.Key(PROJECTION, TYPE, "node-1"), 1, ConsistencyMode.CONTIGUOUS,
                                                                     handler, new TestSeams(Set.of(mutants), TestSeams.Hooks.NONE, null)));
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
}
