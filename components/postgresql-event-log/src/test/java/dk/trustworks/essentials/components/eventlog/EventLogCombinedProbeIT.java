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

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;


import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * I11, plan §16: <i>"Combined probe: the running-writer set is read after the statement's snapshot"</i>.
 * <p>
 * A writer held between taking its value and committing keeps its candidate; the hold-inside-scan proof (a writer that
 * takes its xid while the probe is blocked inside its scan, after its snapshot, still appears in that probe's running
 * set); the running set comes back on an empty tail; inside an explicit transaction the running set is fresh only
 * after {@code pg_stat_clear_snapshot()}. Must-fail variants (P0.5 mutants): the running set read in a statement
 * before the tail read, a cached {@code pg_stat_activity} (probes inside one transaction), and an empty tail taken to
 * mean nobody runs.
 */
class EventLogCombinedProbeIT extends AbstractSubscriptionIT {
    @Test
    void a_writer_held_between_value_and_commit_keeps_its_candidate_and_is_delivered() throws Exception {
        var recorder = new Recorder();
        var subscription = subscribe("sub", recorder);
        awaitDelivered(recorder, commitAppend("pre", 1));
        var held = openAppend("held", 1);
        var xid  = currentXid(held.connection());
        awaitDelivered(recorder, commitAppend("above", 1));
        awaitReads(10);
        assertThat(eventLog.reader(TYPE).state().candidates()).anySatisfy(c -> assertThat(c.runningXids()).contains(xid));
        assertThat(eventLog.reader(TYPE).state().holes()).contains(held.globalOrder());
        held.commit();
        awaitDelivered(recorder, held.globalOrder());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = WatermarkMutant.class, names = {"RUNNING_SET_BEFORE_TAIL_READ", "CACHED_STAT_ACTIVITY", "EMPTY_TAIL_WITHOUT_RUNNING_SET"})
    void must_fail_the_held_writer_is_lost(WatermarkMutant mutant) throws Exception {
        assertThat(writerStartingBetweenProbes(mutant)).isFalse();
    }

    @Test
    void a_writer_starting_between_probes_is_delivered() throws Exception {
        assertThat(writerStartingBetweenProbes(null)).isTrue();
    }

    /**
     * A long writer is open. Before a read - or, for the two-statement mutant, between its running-set read and its
     * tail read - a writer W takes its value and xid and a value above it commits. The cached mutant read its
     * backend status when the reader started, before W. The long writer then ends while W runs, and only then W
     * commits.
     *
     * @return whether W's event was delivered
     */
    private boolean writerStartingBetweenProbes(WatermarkMutant mutant) throws Exception {
        var held     = new AtomicReference<OpenWriter>();
        var armed    = new AtomicBoolean();
        var hooks = new TestSeams.Hooks() {
            @Override
            public void beforeRead(boolean combined) throws Exception {
                if (mutant != WatermarkMutant.RUNNING_SET_BEFORE_TAIL_READ && armed.get() && held.get() == null) {
                    startW();
                }
            }

            @Override
            public void afterRunningSetRead() throws Exception {
                if (armed.get() && held.get() == null) {
                    startW();
                }
            }

            private void startW() throws SQLException {
                held.set(openAppend("held", 1));
                commitAppend("above", 1);
            }
        };
        var recorder = new Recorder();
        subscribe("sub", recorder, new TestSeams(mutant == null ? Set.of() : Set.of(mutant), hooks, null));
        awaitDelivered(recorder, commitAppend("pre", 1));
        var longWriter = openAppend("long", 1);
        awaitDelivered(recorder, commitAppend("above-long", 1));
        armed.set(true);
        awaitTrue(() -> held.get() != null);
        awaitReads(3);
        // The long writer ends while W still runs: only a running set that holds W keeps W's hole outstanding
        longWriter.rollback();
        awaitReads(10);
        held.get().commit();
        return deliveredWithin(recorder, held.get().globalOrder());
    }

    @Test
    void the_running_set_is_read_after_the_statements_snapshot() throws Exception {
        var recorder     = new Recorder();
        var subscription = subscribe("sub", recorder, new TestSeams(Set.of(), TestSeams.Hooks.NONE, HELD));
        awaitDelivered(recorder, commitAppend("pre", 1));
        var longWriter = openAppend("long", 1);
        awaitDelivered(recorder, commitAppend("above", 1));
        var holder = armScanHold();
        // W1 leaves a new hole above every candidate, so the held probe creates a candidate from its running set
        var w1 = openAppend("w1", 1);
        commitAppend("above", 1);
        awaitScanBlocked();
        // W2 takes its xid while the probe is inside its scan, so after the probe's snapshot
        var w2 = connection();
        w2.setAutoCommit(false);
        queryLong(w2, "SELECT pg_current_xact_id()::text::bigint");
        var xid = currentXid(w2);
        releaseScan(holder);
        await().atMost(WAIT).untilAsserted(() -> assertThat(eventLog.reader(TYPE).state().candidates())
                .anySatisfy(c -> assertThat(c.runningXids()).contains(xid)));
        w2.rollback();
        w1.rollback();
        longWriter.rollback();
    }

    @Test
    void an_empty_tail_returns_the_running_set_as_one_row() throws Exception {
        var held       = openAppend("held", 1);
        var xid        = currentXid(held.connection());
        var statements = EventLogStorage.TailStatements.of(tables.eventTable());
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(statements.probe())) {
            statement.setLong(1, Long.MAX_VALUE - 1);
            statement.setInt(2, 100);
            statement.setArray(3, connection.createArrayOf("bigint", new Long[0]));
            try (var resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                resultSet.getLong(1);
                assertThat(resultSet.wasNull()).isTrue();
                assertThat((Object[]) resultSet.getArray(EventLogStorage.RUNNING_COLUMN).getArray()).contains(xid);
                assertThat(resultSet.next()).isFalse();
            }
        }
        held.rollback();
    }

    @Test
    void inside_a_transaction_the_running_set_is_fresh_only_after_pg_stat_clear_snapshot() throws Exception {
        var statements = EventLogStorage.TailStatements.of(tables.eventTable());
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertThat(runningSet(connection, statements)).isNotNull();
            var held = openAppend("held", 1);
            var xid  = currentXid(held.connection());
            assertThat(runningSet(connection, statements)).as("must-fail: the cached backend status misses the writer").doesNotContain(xid);
            queryLong(connection, "SELECT count(*) FROM (SELECT pg_stat_clear_snapshot()) s");
            assertThat(runningSet(connection, statements)).contains(xid);
            connection.rollback();
            held.rollback();
        }
    }

    private static List<String> runningSet(Connection connection, EventLogStorage.TailStatements statements) throws SQLException {
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(statements.runningSet())) {
            resultSet.next();
            var values = (Object[]) resultSet.getArray(1).getArray();
            return Arrays.stream(values).map(String.class::cast).toList();
        }
    }
}
