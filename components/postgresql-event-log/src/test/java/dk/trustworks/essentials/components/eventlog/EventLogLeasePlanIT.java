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
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.*;
import java.util.stream.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The keeper's batched renewal and claim keep a linear plan on an {@code el_progress} that has
 * never been analysed - the state after the table is created, and (for the owner predicate) after every owner restart.
 * Before unit D both statements filtered {@code el_progress} by predicates the planner, without statistics, guessed
 * leave about one row, and the generic plan pgjdbc switches to after five executions became a nested loop that rescans
 * the whole key list for every row of {@code el_progress}: O(N²), 104 ms for 1 000 leases.
 * <p>
 * Every plan is taken with {@code PREPARE} / {@code EXPLAIN EXECUTE} on the real key arrays, under
 * {@code plan_cache_mode = force_custom_plan} and {@code force_generic_plan}, on a table whose autovacuum is off and
 * that {@code pg_stat_user_tables} confirms was never analysed. Must-fail control: the pre-unit-D renewal statement.
 */
class EventLogLeasePlanIT extends AbstractEventLogIT {
    private static final String OWNER = "node-1/3f1c9a52-6d0e-4a52-9c8e-6b1f0f2d7a10";

    /** The renewal statement's earlier shape: owner, fence and instance as restrictions of el_progress */
    private static final String PRE_UNIT_D_RENEW =
            "UPDATE " + EventLogSchema.PROGRESS_TABLE + " p\n" +
                    "   SET lease_until = now() + ?::interval\n" +
                    "  FROM unnest(?::text[], ?::text[], ?::bigint[]) AS k(subscriber, aggregate_type, fence)\n" +
                    " WHERE p.subscriber = k.subscriber AND p.aggregate_type = k.aggregate_type AND p.instance_name = ''\n" +
                    "   AND p.lease_fence = k.fence\n" +
                    "   AND p.lease_owner = ?\n" +
                    "RETURNING p.subscriber, p.aggregate_type";

    private static final List<String> PLAN_MODES = List.of("force_custom_plan", "force_generic_plan");

    @BeforeEach
    void freshProgressTable() throws SQLException {
        for (var statement : EventLogSchema.schemaStatements()) {
            execute(statement);
        }
        execute("ALTER TABLE " + EventLogSchema.PROGRESS_TABLE + " SET (autovacuum_enabled = false)");
    }

    @ParameterizedTest
    @ValueSource(ints = {1_000, 5_000})
    void the_renewal_never_rescans_the_key_list_on_a_never_analysed_table(int leases) throws SQLException {
        insertLeases(leases, "now() + interval '15 seconds'");
        for (var mode : PLAN_MODES) {
            var plan = plan(EventLogStorage.LEASE_RENEW, mode, renewArguments(leases));
            assertThat(keyListRescans(plan)).as("LEASE_RENEW, %d leases, %s:%n%s", leases, mode, String.join("\n", plan)).isEmpty();
        }
        assertNeverAnalysed();
    }

    /** Live leases (the claim matches nothing, a standby's every pass) and expired ones (the takeover's claim) */
    @ParameterizedTest
    @ValueSource(ints = {1_000, 5_000})
    void the_claim_never_rescans_the_key_list_on_a_never_analysed_table(int leases) throws SQLException {
        for (var until : List.of("now() + interval '15 seconds'", "now() - interval '1 second'")) {
            execute("TRUNCATE " + EventLogSchema.PROGRESS_TABLE);
            insertLeases(leases, until);
            for (var mode : PLAN_MODES) {
                var plan = plan(EventLogStorage.LEASE_CLAIM, mode, claimArguments(leases));
                assertThat(keyListRescans(plan)).as("LEASE_CLAIM, %d leases until %s, %s:%n%s", leases, until, mode, String.join("\n", plan)).isEmpty();
            }
        }
        assertNeverAnalysed();
    }

    @Test
    void control_the_pre_unit_d_renewal_rescans_the_key_list_under_a_generic_plan() throws SQLException {
        insertLeases(1_000, "now() + interval '15 seconds'");
        var arguments = List.of(literal("15000 milliseconds"), textArray(subscribers(1_000)), textArray(Collections.nCopies(1_000, "Orders")),
                                fenceArray(1_000), literal(OWNER));
        var plan = plan(PRE_UNIT_D_RENEW, "force_generic_plan", arguments);
        assertThat(keyListRescans(plan)).as("must-fail control, plan:%n%s", String.join("\n", plan)).isNotEmpty();
        assertNeverAnalysed();
    }

    /**
     * Timing sanity: a 5 000-lease renewal, bound as the keeper binds it, on a never-analysed table; seven executions so
     * pgjdbc's server-prepared statement (threshold 5) runs both its custom and its generic plan
     */
    @Test
    void a_5_000_lease_renewal_on_a_never_analysed_table_completes_in_under_a_second() throws SQLException {
        var leases = 5_000;
        insertLeases(leases, "now() + interval '15 seconds'");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(EventLogStorage.LEASE_RENEW)) {
            statement.setString(1, OWNER);
            statement.setArray(2, connection.createArrayOf("text", subscribers(leases).toArray(String[]::new)));
            statement.setArray(3, connection.createArrayOf("text", Collections.nCopies(leases, "Orders").toArray(String[]::new)));
            statement.setArray(4, connection.createArrayOf("bigint", LongStream.rangeClosed(1, leases).boxed().toArray(Long[]::new)));
            statement.setString(5, "15000 milliseconds");
            for (var execution = 1; execution <= 7; execution++) {
                var start   = System.nanoTime();
                var renewed = 0;
                try (var resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        renewed++;
                    }
                }
                var millis = (System.nanoTime() - start) / 1_000_000;
                assertThat(renewed).as("execution %d renewed", execution).isEqualTo(leases);
                assertThat(millis).as("execution %d took %d ms", execution, millis).isLessThan(1_000);
            }
        }
        assertNeverAnalysed();
    }

    // ---- helpers ----

    private void insertLeases(int leases, String until) throws SQLException {
        execute("INSERT INTO " + EventLogSchema.PROGRESS_TABLE + " (subscriber, aggregate_type, instance_name, exclusive, consistency_mode, progress_model, resume_from, " +
                        "lease_owner, lease_until, lease_fence) SELECT 's' || n, 'Orders', '', true, 'FRESH', 'PERIODIC', 1, '" + OWNER + "', " + until + ", n " +
                        "FROM generate_series(1, " + leases + ") n");
        execute("SELECT setval('" + EventLogSchema.LEASE_FENCE_SEQUENCE + "', " + leases + ")");
    }

    private void assertNeverAnalysed() throws SQLException {
        assertThat(queryLong("SELECT count(*) FROM pg_stat_user_tables WHERE relname = '" + EventLogSchema.PROGRESS_TABLE + "' " +
                                     "AND last_analyze IS NULL AND last_autoanalyze IS NULL AND analyze_count = 0 AND autoanalyze_count = 0"))
                .as("el_progress was never analysed").isEqualTo(1);
    }

    private static List<String> renewArguments(int leases) {
        return List.of(literal(OWNER), textArray(subscribers(leases)), textArray(Collections.nCopies(leases, "Orders")), fenceArray(leases),
                       literal("15000 milliseconds"));
    }

    private static List<String> claimArguments(int leases) {
        return List.of(textArray(subscribers(leases)), textArray(Collections.nCopies(leases, "Orders")), literal("standby-owner"), literal("15000 milliseconds"));
    }

    private static List<String> subscribers(int leases) {
        return IntStream.rangeClosed(1, leases).mapToObj(n -> "s" + n).toList();
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String textArray(List<String> values) {
        return literal("{" + String.join(",", values) + "}");
    }

    private static String fenceArray(int leases) {
        return literal("{" + LongStream.rangeClosed(1, leases).mapToObj(Long::toString).collect(Collectors.joining(",")) + "}");
    }

    /**
     * @return the plan of {@code sql} ({@code ?} parameters) prepared server-side and explained with {@code arguments}
     * (SQL literals) under {@code planCacheMode}; not executed
     */
    private List<String> plan(String sql, String planCacheMode, List<String> arguments) throws SQLException {
        var numbered = new StringBuilder();
        var n        = 0;
        for (var c : sql.toCharArray()) {
            numbered.append(c == '?' ? "$" + (++n) : String.valueOf(c));
        }
        assertThat(arguments).as("arguments for %d parameters", n).hasSize(n);
        var plan = new ArrayList<String>();
        try (var connection = dataSource("preferQueryMode=simple").getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET jit = off");
            statement.execute("SET plan_cache_mode = " + planCacheMode);
            statement.execute("PREPARE lease_statement AS " + numbered);
            try (var resultSet = statement.executeQuery("EXPLAIN EXECUTE lease_statement(" + String.join(", ", arguments) + ")")) {
                while (resultSet.next()) {
                    plan.add(resultSet.getString(1));
                }
            }
        }
        return plan;
    }

    /**
     * @return every {@code Nested Loop} whose inner (second) child reads the key list - a {@code Function Scan} of the
     * {@code unnest} or a {@code CTE Scan} of it, directly or under a {@code Materialize} / {@code Memoize} - so that the
     * key list is read again for every outer row
     */
    static List<String> keyListRescans(List<String> plan) {
        var rescans = new ArrayList<String>();
        for (var i = 0; i < plan.size(); i++) {
            var line = plan.get(i);
            if (!line.contains("Nested Loop")) {
                continue;
            }
            var children = children(plan, i);
            if (children.size() < 2) {
                continue;
            }
            var inner = children.get(1);
            var end   = subtreeEnd(plan, inner);
            for (var j = inner; j < end; j++) {
                if (plan.get(j).contains("Function Scan") || plan.get(j).contains("CTE Scan")) {
                    rescans.add(line.trim() + " / inner: " + plan.get(inner).trim());
                    break;
                }
            }
        }
        return rescans;
    }

    /** The column a plan node's name starts at: after {@code "->  "}, or the first non-blank of the root */
    private static int nameColumn(String line) {
        var arrow = line.indexOf("->");
        if (arrow >= 0) {
            return arrow + 4;
        }
        var column = 0;
        while (column < line.length() && line.charAt(column) == ' ') {
            column++;
        }
        return column;
    }

    private static List<Integer> children(List<String> plan, int node) {
        var column   = nameColumn(plan.get(node));
        var children = new ArrayList<Integer>();
        for (var j = node + 1; j < plan.size(); j++) {
            var arrow = plan.get(j).indexOf("->");
            if (arrow < 0) {
                if (leadingBlanks(plan.get(j)) < column) {
                    break;
                }
                continue;
            }
            if (arrow < column + 2) {
                break;
            }
            if (arrow == column + 2) {
                children.add(j);
            }
        }
        return children;
    }

    private static int subtreeEnd(List<String> plan, int node) {
        var arrow = plan.get(node).indexOf("->");
        for (var j = node + 1; j < plan.size(); j++) {
            var other = plan.get(j).indexOf("->");
            if (other >= 0 && other <= arrow) {
                return j;
            }
        }
        return plan.size();
    }

    private static int leadingBlanks(String line) {
        var column = 0;
        while (column < line.length() && line.charAt(column) == ' ') {
            column++;
        }
        return column;
    }
}
