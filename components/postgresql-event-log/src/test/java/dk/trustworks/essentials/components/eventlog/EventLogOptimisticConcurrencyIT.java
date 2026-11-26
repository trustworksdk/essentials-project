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

import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * I10: a lost optimistic-concurrency check consumes no {@code global_order}, and names the right stream.
 */
class EventLogOptimisticConcurrencyIT extends AbstractEventLogIT {

    @Test
    void a_stale_expected_order_against_a_committed_competitor_conflicts_without_consuming_a_value() throws Exception {
        var eventLog = startedEventLog("Orders");
        var tables   = AggregateTypeTables.of("Orders");
        appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "o1", -1, events("E", 2)));
        var before = sequenceLastValue(tables);

        for (var stale : new long[]{-1, 0, 5}) {
            assertThatThrownBy(() -> appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "o1", stale, events("E", 2))))
                    .isInstanceOfSatisfying(StreamAppendConflictException.class, e -> {
                        assertThat(e.aggregateType()).isEqualTo("Orders");
                        assertThat(e.aggregateId()).isEqualTo("o1");
                        assertThat(e.expectedEventOrder()).isEqualTo(stale);
                        assertThat(e.actualEventOrder()).as("not read: phase 8 §18.5").isNull();
                    });
        }
        // An expected order on a stream that does not exist
        assertThatThrownBy(() -> appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "missing", 0, events("E", 1))))
                .isInstanceOfSatisfying(StreamAppendConflictException.class, e -> assertThat(e.actualEventOrder()).isNull());

        assertThat(sequenceLastValue(tables)).as("no global_order consumed").isEqualTo(before);
        var next = appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "o1", 1, events("E", 1))).getFirst();
        assertThat(next.events().getFirst().globalOrder()).as("no hole").isEqualTo(before + 1);
        assertHeadsConsistent(tables);
    }

    @Test
    void the_conflict_names_the_stale_aggregate_in_a_five_aggregate_batch() throws Exception {
        var eventLog = startedEventLog("Orders");
        for (var id : List.of("a", "b", "c", "d", "e")) {
            appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", id, -1, events("E", 1)));
        }
        var before = sequenceLastValue(AggregateTypeTables.of("Orders"));
        assertThatThrownBy(() -> appendCommitted(eventLog,
                                                 AppendRequest.afterEventOrder("Orders", "a", 0, events("E", 1)),
                                                 AppendRequest.afterEventOrder("Orders", "b", 0, events("E", 1)),
                                                 AppendRequest.afterEventOrder("Orders", "c", 7, events("E", 1)),
                                                 AppendRequest.afterEventOrder("Orders", "d", 0, events("E", 1)),
                                                 AppendRequest.afterEventOrder("Orders", "e", 0, events("E", 1))))
                .isInstanceOfSatisfying(StreamAppendConflictException.class, e -> {
                    assertThat(e.aggregateId()).isEqualTo("c");
                    assertThat(e.getSuppressed()).isEmpty();
                });
        assertThat(sequenceLastValue(AggregateTypeTables.of("Orders"))).as("the rollback undid the others; only their values are gone").isEqualTo(before + 4);
        assertHeadsConsistent(AggregateTypeTables.of("Orders"));
    }

    @Test
    void racing_an_in_flight_writer_exactly_one_wins_and_the_loser_consumes_no_value() throws Exception {
        var eventLog = startedEventLog("Orders");
        var tables   = AggregateTypeTables.of("Orders");
        appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "o1", -1, events("E", 1)));

        for (var expected : new long[]{0, -1}) {
            var id = expected == -1 ? "fresh" : "o1";
            try (var first = dataSource.getConnection(); var second = dataSource.getConnection()) {
                first.setAutoCommit(false);
                second.setAutoCommit(false);
                eventLog.append(first, AppendRequest.afterEventOrder("Orders", id, expected, events("E", 2)));
                var afterFirst = sequenceLastValue(tables);
                var secondPid  = queryLong(second, "SELECT pg_backend_pid()");
                var loser = CompletableFuture.supplyAsync(() -> {
                    try {
                        return eventLog.append(second, AppendRequest.afterEventOrder("Orders", id, expected, events("E", 2)));
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });
                await().atMost(Duration.ofSeconds(10)).until(() -> queryLong("SELECT count(*) FROM pg_stat_activity WHERE pid = " + secondPid + " AND wait_event_type = 'Lock'") == 1);
                first.commit();
                assertThatThrownBy(() -> loser.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(StreamAppendConflictException.class);
                second.rollback();
                assertThat(sequenceLastValue(tables)).as("the loser drew no value").isEqualTo(afterFirst);
            }
        }
        assertHeadsConsistent(tables);
    }

    @Test
    void concurrent_writers_on_one_stream_never_burn_a_value_and_keep_the_stream_contiguous() throws Exception {
        var eventLog  = startedEventLog("Orders");
        var tables    = AggregateTypeTables.of("Orders");
        var writers   = 8;
        var deadline  = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        var wins      = new AtomicLong();
        var conflicts = new AtomicLong();
        var executor  = Executors.newFixedThreadPool(writers);
        try {
            var futures = new ArrayList<Future<?>>();
            for (var w = 0; w < writers; w++) {
                var stream = "s" + (w % 2);
                futures.add(executor.submit(() -> {
                    try (var connection = dataSource.getConnection()) {
                        connection.setAutoCommit(false);
                        while (System.nanoTime() < deadline) {
                            var expected = lastEventOrder(connection, tables, stream);
                            connection.commit();
                            try {
                                eventLog.append(connection, AppendRequest.afterEventOrder("Orders", stream, expected, events("E", 2)));
                                connection.commit();
                                wins.incrementAndGet();
                            } catch (StreamAppendConflictException e) {
                                connection.rollback();
                                conflicts.incrementAndGet();
                            }
                        }
                    }
                    return null;
                }));
            }
            for (var future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(wins.get()).isPositive();
        assertThat(conflicts.get()).as("the test produced contention").isPositive();
        assertThat(queryLong("SELECT count(*) FROM " + tables.eventTable())).isEqualTo(wins.get() * 2);
        assertThat(sequenceLastValue(tables)).as("0 burned values: every drawn global_order belongs to a committed event").isEqualTo(wins.get() * 2);
        assertThat(queryLong("SELECT count(*) FROM (SELECT aggregate_id, max(event_order) - min(event_order) + 1 - count(*) AS gap FROM " + tables.eventTable() +
                                     " GROUP BY aggregate_id) g WHERE gap <> 0")).as("contiguous event orders").isZero();
        assertHeadsConsistent(tables);
    }

    @Test
    void the_unique_stream_order_backstop_holds_when_the_head_is_out_of_step() throws Exception {
        var eventLog = startedEventLog("Orders");
        var tables   = AggregateTypeTables.of("Orders");
        appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "o1", -1, events("E", 2)));
        appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "o2", -1, events("E", 1)));
        // An external writer broke the head: it claims o1 has no events
        execute("DELETE FROM " + tables.headTable() + " WHERE aggregate_id = 'o1'");

        assertThatThrownBy(() -> appendCommitted(eventLog,
                                                 AppendRequest.afterEventOrder("Orders", "o2", 0, events("E", 1)),
                                                 AppendRequest.afterEventOrder("Orders", "o1", -1, events("E", 1))))
                .isInstanceOfSatisfying(StreamAppendConflictException.class, e -> {
                    assertThat(e.aggregateId()).isEqualTo("o1");
                    assertThat(e.actualEventOrder()).isNull();
                });
        assertThat(eventLog.loadStream("Orders", "o1")).hasSize(2);
        assertThat(eventLog.loadStream("Orders", "o2")).hasSize(1);
    }

    private static long lastEventOrder(Connection connection, AggregateTypeTables tables, String stream) throws Exception {
        return queryLong(connection, "SELECT coalesce((SELECT version FROM " + tables.headTable() + " WHERE aggregate_id = '" + stream + "'), -1)");
    }
}
