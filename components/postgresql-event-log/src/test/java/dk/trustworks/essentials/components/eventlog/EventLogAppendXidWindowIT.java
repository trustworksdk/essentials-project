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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * I4, xid before value, for the head-table append (decision 4), after P0.1's {@code AppendXidBeforeIdentitySpikeIT}.
 * <p>
 * A {@code BEFORE INSERT} trigger holds the first event row of an append - its {@code global_order} already drawn, as
 * identity defaults are evaluated before BEFORE triggers - until the test releases it. Meanwhile another connection
 * reads the writer's {@code backend_xid}: non-null means a watermark would see the writer. Each of the three head paths
 * runs under the simple protocol, the extended protocol with unnamed statements, and named statements on the generic
 * plan, with default planner settings and with nested loops off and join collapsing disabled.
 * <p>
 * Must-fail variant: in the head-table paths the head row is written before the event insert, so the xid is taken
 * first even without the explicit {@code pg_current_xact_id()} step (P0.7's finding, asserted by
 * {@link #the_head_update_alone_also_takes_the_xid_first}). Removing the step from the engine therefore cannot be made
 * to fail; the control is instead an insert that draws its identity value before any write - no {@code xid} CTE and
 * no head CTE, P0.5's "value before xid" - and the test must see {@code backend_xid} null there, which proves the
 * probe detects the window.
 */
class EventLogAppendXidWindowIT extends AbstractEventLogIT {
    private static final int    HOLD_KEY = 731_803;
    private static final String HOLD     = "hold";

    enum Protocol {
        SIMPLE("preferQueryMode=simple", null),
        EXTENDED_UNNAMED("prepareThreshold=0", null),
        NAMED_GENERIC_PLAN("prepareThreshold=1", "force_generic_plan");

        final String urlParameter;
        final String planCacheMode;

        Protocol(String urlParameter, String planCacheMode) {
            this.urlParameter = urlParameter;
            this.planCacheMode = planCacheMode;
        }
    }

    enum Path {GUARDED_UPDATE, NEW_STREAM, NO_EXPECTED_ORDER}

    enum Planner {
        DEFAULT,
        NO_NESTLOOP_NO_COLLAPSE("enable_nestloop = off", "join_collapse_limit = 1", "from_collapse_limit = 1");

        final List<String> settings;

        Planner(String... settings) {
            this.settings = List.of(settings);
        }
    }

    private EventLog            eventLog;
    private AggregateTypeTables tables;

    @BeforeEach
    void installHold() throws SQLException {
        eventLog = startedEventLog("Orders");
        tables = AggregateTypeTables.of("Orders");
        execute("""
                CREATE OR REPLACE FUNCTION el_test_hold() RETURNS boolean LANGUAGE plpgsql VOLATILE AS $$
                BEGIN
                    PERFORM pg_advisory_lock_shared(%1$d);
                    PERFORM pg_advisory_unlock_shared(%1$d);
                    RETURN true;
                END $$""".formatted(HOLD_KEY));
        execute("""
                CREATE OR REPLACE FUNCTION el_test_pause() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.tenant = '%s' THEN
                        PERFORM el_test_hold();
                    END IF;
                    RETURN NEW;
                END $$""".formatted(HOLD));
        execute("CREATE TRIGGER el_test_pause BEFORE INSERT ON " + tables.eventTable() + " FOR EACH ROW EXECUTE FUNCTION el_test_pause()");
    }

    static Stream<Arguments> cases() {
        return Arrays.stream(Protocol.values())
                     .flatMap(protocol -> Arrays.stream(Path.values())
                                                .flatMap(path -> Arrays.stream(Planner.values()).map(planner -> Arguments.of(protocol, path, planner))));
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("cases")
    void the_engine_append_takes_the_xid_before_the_first_global_order(Protocol protocol, Path path, Planner planner) throws Exception {
        appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "existing", -1, events("E", 1)));
        try (var writer = writer(protocol, planner)) {
            var request = switch (path) {
                case GUARDED_UPDATE -> AppendRequest.afterEventOrder("Orders", "existing", 0, events("E", 3));
                case NEW_STREAM -> AppendRequest.afterEventOrder("Orders", "fresh", -1, events("E", 3));
                case NO_EXPECTED_ORDER -> AppendRequest.withoutExpectedOrder("Orders", "existing", events("E", 3));
            };
            var xid = heldBackendXid(writer, () -> eventLog.append(writer, request.withTenant(HOLD)));
            assertThat(xid).as("xid assigned before the first global_order (%s %s %s)", protocol, path, planner).isNotNull();
        }
        assertHeadsConsistent(tables);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Protocol.class)
    void control_an_insert_that_draws_its_value_before_any_write_has_no_xid_while_held(Protocol protocol) throws Exception {
        var control = "INSERT INTO " + tables.eventTable() + " (aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_metadata, tenant)\n" +
                "SELECT ?, e.n, gen_random_uuid(), 1, 1, now(), '{}', '{}', ? FROM generate_series(0, 2) AS e(n)";
        try (var writer = writer(protocol, Planner.DEFAULT); var statement = writer.prepareStatement(control)) {
            statement.setString(1, "control");
            statement.setString(2, HOLD);
            var xid = heldBackendXid(writer, () -> statement.executeUpdate());
            assertThat(xid).as("must-fail control: the probe sees the value-before-xid window").isNull();
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Protocol.class)
    void the_head_update_alone_also_takes_the_xid_first(Protocol protocol) throws Exception {
        appendCommitted(eventLog, AppendRequest.afterEventOrder("Orders", "existing", -1, events("E", 1)));
        var withoutXidStep = EventLogStorage.TableStatements.of(tables).appendAfter()
                                                            .replace(EventLogStorage.XID_CTE + "     h AS", "WITH h AS")
                                                            .replace(" AND (SELECT true FROM xid)", "");
        assertThat(withoutXidStep).doesNotContain("pg_current_xact_id");
        try (var writer = writer(protocol, Planner.DEFAULT); var statement = writer.prepareStatement(withoutXidStep)) {
            var p = 1;
            statement.setLong(p++, 1);
            statement.setString(p++, "existing");
            statement.setLong(p++, 0);
            statement.setLong(p++, 1);
            statement.setString(p++, "existing");
            statement.setString(p++, HOLD);
            statement.setArray(p++, writer.createArrayOf("uuid", new UUID[]{UUID.randomUUID()}));
            statement.setArray(p++, writer.createArrayOf("uuid", new UUID[]{null}));
            statement.setArray(p++, writer.createArrayOf("uuid", new UUID[]{null}));
            statement.setArray(p++, writer.createArrayOf("int2", new Short[]{1}));
            statement.setArray(p++, writer.createArrayOf("int2", new Short[]{1}));
            statement.setArray(p++, writer.createArrayOf("text", new String[]{"2026-11-15T10:00:00Z"}));
            statement.setArray(p++, writer.createArrayOf("text", new String[]{"{}"}));
            statement.setArray(p, writer.createArrayOf("text", new String[]{"{}"}));
            var xid = heldBackendXid(writer, () -> statement.executeQuery().close());
            assertThat(xid).as("P0.7: the head write assigns the xid before the event insert draws a value").isNotNull();
        }
    }

    private Connection writer(Protocol protocol, Planner planner) throws SQLException {
        var writer = dataSource(protocol.urlParameter).getConnection();
        try (var statement = writer.createStatement()) {
            statement.execute("SET statement_timeout = '60s'");
            if (protocol.planCacheMode != null) {
                statement.execute("SET plan_cache_mode = '" + protocol.planCacheMode + "'");
            }
            for (var setting : planner.settings) {
                statement.execute("SET " + setting);
            }
        }
        writer.setAutoCommit(false);
        return writer;
    }

    @FunctionalInterface
    private interface SqlWork {
        void run() throws Exception;
    }

    /**
     * Runs {@code work} on {@code writer} with the hold armed, and returns the writer's {@code backend_xid} as seen while
     * its first held row has drawn its {@code global_order}. Commits the writer afterwards.
     */
    private String heldBackendXid(Connection writer, SqlWork work) throws Exception {
        var writerPid = queryLong(writer, "SELECT pg_backend_pid()");
        writer.commit();
        try (var observer = dataSource.getConnection()) {
            var sequence     = sequenceOf(observer);
            var beforeHeld   = preTake(observer, sequence);
            var holder       = dataSource.getConnection();
            try {
                try (var statement = holder.createStatement()) {
                    statement.execute("SELECT pg_advisory_lock(" + HOLD_KEY + ")");
                }
                var held = CompletableFuture.runAsync(() -> {
                    try {
                        work.run();
                    } catch (Exception e) {
                        throw new CompletionException(e);
                    }
                });
                await().atMost(Duration.ofSeconds(20))
                       .until(() -> queryLong(observer, "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted AND objid = " + HOLD_KEY) == 1);
                var drawnWhileHeld = queryLong(observer, "SELECT last_value FROM " + sequence);
                String xid;
                try (var statement = observer.prepareStatement("SELECT backend_xid::text FROM pg_stat_activity WHERE pid = ?")) {
                    statement.setLong(1, writerPid);
                    try (var resultSet = statement.executeQuery()) {
                        resultSet.next();
                        xid = resultSet.getString(1);
                    }
                }
                holder.close();
                held.get(30, TimeUnit.SECONDS);
                writer.commit();
                assertThat(drawnWhileHeld).as("the held row has drawn its global_order").isEqualTo(beforeHeld + 1);
                return xid;
            } finally {
                holder.close();
            }
        }
    }

    private String sequenceOf(Connection observer) throws SQLException {
        try (var statement = observer.createStatement();
             var resultSet = statement.executeQuery("SELECT pg_get_serial_sequence('" + tables.eventTable() + "', 'global_order')")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    /**
     * {@code nextval} WAL-logs - and so assigns its transaction an xid - once per 32 values. Draw values until the next
     * few are certain not to log, so the held draw is the ordinary kind that does not assign an xid.
     *
     * @return the sequence's last value
     */
    private static long preTake(Connection observer, String sequence) throws SQLException {
        for (var i = 0; i < 40; i++) {
            try (var statement = observer.createStatement();
                 var resultSet = statement.executeQuery("SELECT log_cnt, is_called FROM " + sequence)) {
                resultSet.next();
                if (resultSet.getBoolean(2) && resultSet.getLong(1) >= 2) {
                    return queryLong(observer, "SELECT last_value FROM " + sequence);
                }
            }
            queryLong(observer, "SELECT nextval('" + sequence + "')");
        }
        throw new IllegalStateException("Could not prepare " + sequence);
    }
}
