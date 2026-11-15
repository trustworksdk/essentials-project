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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * I1, plan §16: <i>"No loss: every committed event reaches every active subscription at least once, in both modes"</i>
 * - phase 2 has {@code FRESH} only.
 * <p>
 * Concurrent writers with random commit order and rollbacks, a writer held open while others commit (5 s by default,
 * the plan's five minutes with the opt-in {@code -Dbenchmark.run=true}, the repository's switch for long suites), a sequence jump of a million values, and a
 * reader restarted while a writer holds a value below the head. Must-fail variants (P0.5 mutants): the empty tail
 * taken to mean nobody runs, a restarted reader that takes the head as final (F4), and a writer that draws its value
 * before its xid - the raw insert of P0.1's control, which the engine's append never is (I4).
 */
class EventLogNoLossIT extends AbstractSubscriptionIT {
    private static final int    HOLD_KEY = 731_902;
    private static final String HOLD     = "hold";

    @Test
    void concurrent_writers_with_random_commit_order_and_rollbacks_lose_nothing() throws Exception {
        var recorder = new Recorder();
        subscribe("sub", recorder);
        var longWriter = openAppend("long", 1);
        var executor   = Executors.newFixedThreadPool(8);
        var deadline   = System.nanoTime() + Duration.ofSeconds(4).toNanos();
        var futures    = new ArrayList<Future<Integer>>();
        for (var w = 0; w < 8; w++) {
            futures.add(executor.submit(() -> {
                var random  = ThreadLocalRandom.current();
                var commits = 0;
                try (var connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    while (System.nanoTime() < deadline) {
                        eventLog.append(connection, AppendRequest.withoutExpectedOrder(TYPE, "agg-" + random.nextInt(10), events("E", 1 + random.nextInt(3))));
                        Thread.sleep(random.nextInt(4));
                        if (random.nextInt(10) == 0) {
                            connection.rollback();
                        } else {
                            connection.commit();
                            commits++;
                        }
                    }
                }
                return commits;
            }));
        }
        var commits = 0;
        for (var future : futures) {
            commits += future.get(60, TimeUnit.SECONDS);
        }
        executor.shutdown();
        longWriter.commit();
        assertThat(commits).isGreaterThan(100);

        var committed = committedOrders();
        await().atMost(WAIT).untilAsserted(() -> assertThat(recorder.deliveredOrders()).containsAll(committed));
        assertThat(perAggregateOrderViolations(recorder.delivered)).isEmpty();
        assertHeadsConsistent(tables);
    }

    @Test
    void a_writer_held_open_is_delivered_when_it_commits_and_does_not_hold_back_later_events() throws Exception {
        var seconds  = Boolean.getBoolean("benchmark.run") ? 300 : 5;
        var recorder = new Recorder();
        subscribe("sub", recorder);
        var held  = openAppend("long", 1);
        var later = new ArrayList<Long>();
        var end   = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < end) {
            var g = commitAppend("busy", 1);
            later.add(g);
            awaitDelivered(recorder, g);
            Thread.sleep(250);
        }
        assertThat(recorder.deliveredOrder(held.globalOrder())).as("not committed yet").isFalse();
        held.commit();
        awaitDelivered(recorder, held.globalOrder());
        assertThat(recorder.deliveredOrders()).containsAll(later);
        await().atMost(WAIT).until(() -> reads.lastCandidates == 0 && reads.lastHoles == 0);
    }

    @Test
    void a_sequence_jump_of_a_million_is_one_range_and_a_late_commit_inside_it_arrives() throws Exception {
        var recorder     = new Recorder();
        var subscription = subscribe("sub", recorder);
        awaitDelivered(recorder, commitAppend("pre", 1));
        var held = openAppend("held", 1);
        execute("SELECT setval(pg_get_serial_sequence('" + tables.eventTable() + "', 'global_order'), " + (held.globalOrder() + 1_000_000) + ")");
        var after = commitAppend("after", 1);
        assertThat(after).isEqualTo(held.globalOrder() + 1_000_001);
        awaitDelivered(recorder, after);
        await().atMost(WAIT).untilAsserted(() -> {
            var state = subscription.reader().state();
            assertThat(state.ranges()).hasSize(1);
            assertThat(state.holes()).isEmpty();
        });
        held.commit();
        awaitDelivered(recorder, held.globalOrder());
        await().atMost(WAIT).until(() -> subscription.reader().state().ranges().isEmpty() && subscription.reader().state().candidates().isEmpty());
        assertThat(recorder.delivered).hasSize(3);
    }

    @Test
    void a_restarted_reader_delivers_a_value_a_writer_held_below_the_head() throws Exception {
        assertThat(restartWhileAWriterHoldsAValueBelowTheHead()).isTrue();
    }

    @Test
    void must_fail_a_restarted_reader_that_takes_the_head_as_final_loses_the_held_value() throws Exception {
        assertThat(restartWhileAWriterHoldsAValueBelowTheHead(WatermarkMutant.RESTARTED_READER_AT_HEAD)).isFalse();
    }

    /**
     * @return whether the second incarnation delivered the value the writer held across the restart
     */
    private boolean restartWhileAWriterHoldsAValueBelowTheHead(WatermarkMutant... mutants) throws Exception {
        var first = new Recorder();
        var s1    = subscribe("sub", first);
        awaitDelivered(first, commitAppend("pre", 1));
        var held  = openAppend("held", 1);
        var above = commitAppend("above", 2);
        awaitDelivered(first, above, above + 1);
        await().atMost(WAIT).until(() -> savedResumeFrom("sub") == held.globalOrder());
        s1.crash();

        var second = new Recorder();
        subscribe("sub", second, mutants);
        awaitDelivered(second, above, above + 1);
        held.commit();
        return deliveredWithin(second, held.globalOrder());
    }

    @Test
    void an_empty_tail_still_reads_the_running_set() throws Exception {
        assertThat(emptyProbesWhileAWriterHoldsAHole()).isTrue();
    }

    @Test
    void must_fail_an_empty_tail_taken_as_nobody_running_loses_the_held_value() throws Exception {
        assertThat(emptyProbesWhileAWriterHoldsAHole(WatermarkMutant.EMPTY_TAIL_WITHOUT_RUNNING_SET)).isFalse();
    }

    private boolean emptyProbesWhileAWriterHoldsAHole(WatermarkMutant... mutants) throws Exception {
        var recorder = new Recorder();
        subscribe("sub", recorder, mutants);
        awaitDelivered(recorder, commitAppend("pre", 1));
        var held = openAppend("held", 1);
        awaitDelivered(recorder, commitAppend("above", 1));
        awaitReads(5);
        held.commit();
        return deliveredWithin(recorder, held.globalOrder());
    }

    @Test
    void the_engine_append_held_after_its_value_is_drawn_is_delivered() throws Exception {
        assertThat(heldWriter(true)).isTrue();
    }

    @Test
    void must_fail_a_writer_that_draws_its_value_before_its_xid_is_lost() throws Exception {
        assertThat(heldWriter(false)).isFalse();
    }

    /**
     * A {@code BEFORE INSERT} trigger holds the writer with its first {@code global_order} drawn. The engine's append
     * has its xid by then; P0.1's raw insert does not, so the reader's running set misses it (P0.5 "value before xid").
     */
    private boolean heldWriter(boolean engineAppend) throws Exception {
        execute("""
                CREATE OR REPLACE FUNCTION el_test_pause() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.tenant = '%s' THEN
                        PERFORM pg_advisory_lock_shared(%d);
                        PERFORM pg_advisory_unlock_shared(%d);
                    END IF;
                    RETURN NEW;
                END $$""".formatted(HOLD, HOLD_KEY, HOLD_KEY));
        execute("CREATE TRIGGER el_test_pause BEFORE INSERT ON " + tables.eventTable() + " FOR EACH ROW EXECUTE FUNCTION el_test_pause()");
        var recorder = new Recorder();
        subscribe("sub", recorder);
        var pre = commitAppend("pre", 1);
        awaitDelivered(recorder, pre);
        var holder = connection();
        try (var statement = holder.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + HOLD_KEY + ")");
        }
        var writer = connection();
        writer.setAutoCommit(false);
        var held = CompletableFuture.runAsync(() -> {
            try {
                if (engineAppend) {
                    eventLog.append(writer, AppendRequest.withoutExpectedOrder(TYPE, "held", events("E", 1)).withTenant(HOLD));
                } else {
                    try (var statement = writer.prepareStatement("INSERT INTO " + tables.eventTable() +
                                                                         " (aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_metadata, tenant) " +
                                                                         "VALUES ('raw', 0, gen_random_uuid(), 1, 1, now(), '{}', '{}', ?)")) {
                        statement.setString(1, HOLD);
                        statement.executeUpdate();
                    }
                }
                writer.commit();
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        });
        await().atMost(WAIT).until(() -> queryLong("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND objid = " + HOLD_KEY) == 1);
        var heldValue = pre + 1;
        awaitDelivered(recorder, commitAppend("above", 1));
        awaitReads(5);
        holder.close();
        held.get(30, TimeUnit.SECONDS);
        return deliveredWithin(recorder, heldValue);
    }

    private Set<Long> committedOrders() throws SQLException {
        var orders = new TreeSet<Long>();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var resultSet = statement.executeQuery("SELECT global_order FROM " + tables.eventTable())) {
            while (resultSet.next()) {
                orders.add(resultSet.getLong(1));
            }
        }
        return orders;
    }
}
