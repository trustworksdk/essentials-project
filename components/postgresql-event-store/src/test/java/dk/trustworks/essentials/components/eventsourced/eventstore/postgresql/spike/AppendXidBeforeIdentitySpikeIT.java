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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SpikePostgres.Protocol;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;

import static dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.spike.SpikePostgres.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spike P0.1 of {@code docs/plans/event-store-subscription-redesign.md}: does the single-statement append of §5.1
 * assign the transaction's xid before the identity default calls {@code nextval} for the first row - under every join
 * order and query protocol?
 * <p>
 * A {@code BEFORE INSERT} trigger holds the first row of the append (its identity value already taken, as
 * {@code NEW.global_order} is set before BEFORE triggers run) until the test releases it. While it is held, another
 * connection reads the writer's {@code pg_stat_activity.backend_xid}: non-null means the watermark sees the writer.
 * The {@code CONTROL_*} shapes drop the {@code xid} CTE and must show it null - otherwise the check proves nothing.
 * <p>
 * Each case also checks that an append against a stale expected event order returns zero rows and consumes no
 * sequence value. Throwaway: runs only with {@code -Dspike.run=true}.
 */
@Tag("spike")
@EnabledIfSystemProperty(named = "spike.run", matches = "true")
class AppendXidBeforeIdentitySpikeIT {
    private static final String RESULTS = "p0-1-append-xid-before-identity.txt";

    private static final String CTES_WITH_XID = """
                                                WITH xid  AS MATERIALIZED (SELECT pg_current_xact_id() AS x),
                                                     head AS MATERIALIZED (SELECT coalesce(max(event_order), 0) AS v FROM spike_events WHERE aggregate_id = ?::text),
                                                     ok   AS (SELECT head.v FROM head, xid WHERE ?::bigint IS NULL OR head.v = ?::bigint)
                                                """;
    private static final String CTES_NO_XID   = """
                                                WITH head AS MATERIALIZED (SELECT coalesce(max(event_order), 0) AS v FROM spike_events WHERE aggregate_id = ?::text),
                                                     ok   AS (SELECT head.v FROM head WHERE ?::bigint IS NULL OR head.v = ?::bigint)
                                                """;
    private static final String INSERT        = """
                                                INSERT INTO spike_events (aggregate_id, event_order, event_id, caused_by_event_id, correlation_id,
                                                                          event_type, event_revision, timestamp, event_payload, event_metadata, tenant)
                                                """;
    private static final String SELECT_LIST   = """
                                                SELECT ?::text, %1$s.v + e.n, e.id, e.caused_by, e.correlation, e.type, e.revision, e.ts,
                                                       e.payload::jsonb, e.metadata::jsonb, ?::text
                                                """;
    private static final String UNNEST        = "unnest(?::text[], ?::text[], ?::text[], ?::text[], ?::text[], ?::timestamptz[], ?::text[], ?::text[]) " +
            "WITH ORDINALITY AS %s(id, caused_by, correlation, type, revision, ts, payload, metadata, n)";
    private static final String RETURNING     = "\nRETURNING global_order, event_order";

    /** §5.1 as written: {@code FROM ok CROSS JOIN unnest(...)}, join order left to the planner. */
    private static String asWritten(String ctes) {
        return ctes + INSERT + SELECT_LIST.formatted("ok") + "FROM ok CROSS JOIN " + UNNEST.formatted("e") + RETURNING;
    }

    /**
     * {@code unnest} forced to the outer side: a lateral subquery that references {@code e} can only be the inner side
     * of a nested loop, and {@code OFFSET 0} keeps it from being pulled up (which would free the join order again).
     */
    private static String unnestOuter(String ctes) {
        return ctes + INSERT + SELECT_LIST.formatted("o") + "FROM " + UNNEST.formatted("e") +
                " CROSS JOIN LATERAL (SELECT ok.v FROM ok WHERE e.n > 0 OFFSET 0) AS o" + RETURNING;
    }

    /** {@code ok} forced to the outer side, the mirror image of {@link #unnestOuter}. */
    private static String okOuter(String ctes) {
        return ctes + INSERT + SELECT_LIST.formatted("ok") + "FROM ok CROSS JOIN LATERAL (SELECT * FROM " + UNNEST.formatted("u") +
                " WHERE ok.v >= 0 OFFSET 0) AS e" + RETURNING;
    }

    enum Shape {
        AS_WRITTEN(asWritten(CTES_WITH_XID), true),
        AS_WRITTEN_NO_NESTLOOP(asWritten(CTES_WITH_XID), true, "enable_nestloop = off"),
        AS_WRITTEN_NO_MATERIAL(asWritten(CTES_WITH_XID), true, "enable_material = off"),
        AS_WRITTEN_COLLAPSE_1(asWritten(CTES_WITH_XID), true, "join_collapse_limit = 1", "from_collapse_limit = 1"),
        UNNEST_OUTER(unnestOuter(CTES_WITH_XID), true),
        OK_OUTER(okOuter(CTES_WITH_XID), true),
        CONTROL_NO_XID(asWritten(CTES_NO_XID), false),
        CONTROL_NO_XID_UNNEST_OUTER(unnestOuter(CTES_NO_XID), false);

        final String       sql;
        final boolean      takesXid;
        final List<String> settings;

        Shape(String sql, boolean takesXid, String... settings) {
            this.sql = sql;
            this.takesXid = takesXid;
            this.settings = List.of(settings);
        }
    }

    static Stream<Arguments> cases() {
        return images().stream()
                       .flatMap(image -> Arrays.stream(Protocol.values())
                                               .flatMap(protocol -> Arrays.stream(Shape.values())
                                                                          .map(shape -> Arguments.of(image, protocol, shape))));
    }

    @AfterAll
    static void stopContainers() {
        stopAll();
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("cases")
    void the_xid_is_taken_before_the_first_identity_value(String image, Protocol protocol, Shape shape) throws Exception {
        var postgres = container(image);
        try (var setup = connect(postgres);
             var observer = connect(postgres);
             var writer = connect(postgres, protocol)) {
            var version = serverVersion(setup);
            var pretaken = recreateEventTable(setup);
            SpikeHold.install(setup);
            try (var statement = setup.createStatement()) {
                // Holds the first row of an append whose first event is of type HOLD - after its identity value was taken,
                // before its heap insert.
                statement.execute("""
                                  CREATE OR REPLACE FUNCTION spike_pause_before_insert() RETURNS trigger LANGUAGE plpgsql AS $$
                                  BEGIN
                                      IF NEW.event_type = 'HOLD' THEN
                                          PERFORM spike_hold();
                                      END IF;
                                      RETURN NEW;
                                  END $$""");
                statement.execute("CREATE TRIGGER spike_pause_before_insert BEFORE INSERT ON spike_events FOR EACH ROW EXECUTE FUNCTION spike_pause_before_insert()");
            }

            writer.setAutoCommit(false);
            try (var statement = writer.createStatement()) {
                for (var setting : shape.settings) {
                    statement.execute("SET " + setting);
                }
                // The executed plan of every statement comes back to the client as a NOTICE, generic plans included.
                statement.execute("LOAD 'auto_explain'");
                statement.execute("SET auto_explain.log_min_duration = 0");
                statement.execute("SET auto_explain.log_level = 'notice'");
            }
            writer.commit();
            var writerPid = backendPid(writer);

            try (var append = writer.prepareStatement(shape.sql)) {
                // Stale appends: zero rows, no sequence value consumed. Repeated so a server-side prepared statement is
                // past the five custom plans and the held append below may run on the generic plan.
                var staleRuns = protocol.serverPrepared ? 6 : 1;
                for (var i = 0; i < staleRuns; i++) {
                    var rows = execute(append, "warm-" + i, 99L, List.of("Warm"));
                    writer.rollback();
                    assertThat(rows).as("stale append returns no rows").isEmpty();
                    assertThat(sequenceLastValue(observer)).as("stale append consumes no sequence value").isEqualTo(pretaken);
                }

                // The held append: three events, the first one held in the trigger.
                try (var hold = SpikeHold.arm(postgres)) {
                    var heldAppend = CompletableFuture.supplyAsync(() -> {
                        try {
                            return execute(append, "A", null, List.of("HOLD", "Second", "Third"));
                        } catch (SQLException e) {
                            throw new CompletionException(e);
                        }
                    });
                    Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> SpikeHold.held(observer) == 1);

                    var identityTakenWhileHeld = sequenceLastValue(observer);
                    var backendXidWhileHeld    = backendXidOf(observer, writerPid);

                    hold.release();
                    var rows = heldAppend.get(30, TimeUnit.SECONDS);
                    var plan = planOf(append);
                    var planCounts = protocol.serverPrepared ? preparedPlanCounts(writer) : "n/a";
                    writer.commit();

                    var order = joinOrder(plan);
                    record(RESULTS, "%s (%s) | %s | %s | settings=%s | backend_xid while held=%s | identity taken while held=%d (pretaken %d) | join order=%s | prepared plans=%s"
                            .formatted(image, version, protocol, shape, shape.settings, backendXidWhileHeld, identityTakenWhileHeld, pretaken, order, planCounts));
                    record(RESULTS, "    plan: " + plan.replace("\n", "\n          "));

                    assertThat(identityTakenWhileHeld).as("the held row has taken its identity value").isEqualTo(pretaken + 1);
                    if (shape.takesXid) {
                        assertThat(backendXidWhileHeld).as("xid assigned before the first identity value").isNotNull();
                    } else {
                        assertThat(backendXidWhileHeld).as("control: no xid while holding an identity value").isNull();
                    }
                    assertThat(rows).extracting(r -> r[0]).containsExactly(pretaken + 1, pretaken + 2, pretaken + 3);
                    assertThat(rows).extracting(r -> r[1]).containsExactly(1L, 2L, 3L);
                    if (shape == Shape.UNNEST_OUTER || shape == Shape.CONTROL_NO_XID_UNNEST_OUTER) {
                        assertThat(order).as("the rewrite really puts unnest outside").isEqualTo("unnest-before-xid-or-head");
                    }
                    if (shape == Shape.OK_OUTER) {
                        assertThat(order).as("the rewrite really puts ok outside").isEqualTo("xid-or-head-before-unnest");
                    }
                    if (protocol == Protocol.PREPARED_GENERIC) {
                        assertThat(planCounts).as("the held append ran on a generic plan").doesNotStartWith("generic=0 ");
                    }
                }

                // Stale expected order after real events: zero rows, and the next append's global_order follows on directly.
                var stale = execute(append, "A", 0L, List.of("Stale"));
                writer.rollback();
                assertThat(stale).isEmpty();
                assertThat(sequenceLastValue(observer)).isEqualTo(pretaken + 3);
                var next = execute(append, "A", 3L, List.of("Fourth"));
                writer.commit();
                assertThat(next).hasSize(1);
                assertThat(next.getFirst()[0]).as("no value was consumed by the stale append").isEqualTo(pretaken + 4);
                assertThat(next.getFirst()[1]).isEqualTo(4L);
            }
        }
    }

    /** Binds and runs one append. Parameter order is the same for every shape. */
    private static List<long[]> execute(PreparedStatement append, String aggregateId, Long expectedEventOrder, List<String> eventTypes) throws SQLException {
        var connection = append.getConnection();
        var n          = eventTypes.size();
        var ids        = new String[n];
        var nulls      = new String[n];
        var revisions  = new String[n];
        var timestamps = new String[n];
        var payloads   = new String[n];
        var metadata   = new String[n];
        for (var i = 0; i < n; i++) {
            ids[i] = UUID.randomUUID().toString();
            revisions[i] = "1";
            timestamps[i] = "2026-11-10T10:00:00Z";
            payloads[i] = "{\"n\":" + i + "}";
            metadata[i] = "{}";
        }
        var p = 1;
        append.setString(p++, aggregateId);
        if (expectedEventOrder == null) {
            append.setNull(p++, Types.BIGINT);
            append.setNull(p++, Types.BIGINT);
        } else {
            append.setLong(p++, expectedEventOrder);
            append.setLong(p++, expectedEventOrder);
        }
        append.setString(p++, aggregateId);
        append.setString(p++, "tenant-1");
        append.setArray(p++, connection.createArrayOf("text", ids));
        append.setArray(p++, connection.createArrayOf("text", nulls));
        append.setArray(p++, connection.createArrayOf("text", nulls));
        append.setArray(p++, connection.createArrayOf("text", eventTypes.toArray(String[]::new)));
        append.setArray(p++, connection.createArrayOf("text", revisions));
        append.setArray(p++, connection.createArrayOf("text", timestamps));
        append.setArray(p++, connection.createArrayOf("text", payloads));
        append.setArray(p, connection.createArrayOf("text", metadata));
        append.clearWarnings();
        var rows = new ArrayList<long[]>();
        try (var rs = append.executeQuery()) {
            while (rs.next()) {
                rows.add(new long[]{rs.getLong(1), rs.getLong(2)});
            }
        }
        return rows;
    }

    /** The plan auto_explain sent back as a NOTICE for the last execution. */
    private static String planOf(Statement statement) throws SQLException {
        var plan    = new StringBuilder();
        var warning = statement.getWarnings();
        while (warning != null) {
            if (warning.getMessage() != null && warning.getMessage().contains("Insert on")) {
                plan.append(warning.getMessage());
            }
            warning = warning.getNextWarning();
        }
        return plan.isEmpty() ? "(no plan captured)" : plan.toString();
    }

    /**
     * Which side the plan reads first. Text plans list a node's outer child before its inner child, so whichever of the
     * {@code unnest} function scan and the {@code xid} (or, in a control, {@code head}) CTE scan is printed first is read
     * first.
     */
    private static String joinOrder(String plan) {
        var start = plan.indexOf("Insert on");
        if (start < 0) {
            return "unknown";
        }
        plan = plan.substring(start);
        var unnest = plan.indexOf("Function Scan on ");
        var xid    = plan.indexOf("CTE Scan on xid");
        if (xid < 0) {
            xid = plan.indexOf("CTE Scan on head");
        }
        if (unnest < 0 || xid < 0) {
            return "unknown";
        }
        return unnest < xid ? "unnest-before-xid-or-head" : "xid-or-head-before-unnest";
    }

    private static String preparedPlanCounts(Connection writer) throws SQLException {
        try (var statement = writer.createStatement();
             var rs = statement.executeQuery("SELECT generic_plans, custom_plans FROM pg_prepared_statements WHERE statement LIKE '%INSERT INTO spike_events%' AND statement NOT LIKE '%pg_prepared_statements%'")) {
            var counts = new StringJoiner("; ");
            while (rs.next()) {
                counts.add("generic=" + rs.getLong(1) + " custom=" + rs.getLong(2));
            }
            return counts.length() == 0 ? "none" : counts.toString();
        }
    }
}
