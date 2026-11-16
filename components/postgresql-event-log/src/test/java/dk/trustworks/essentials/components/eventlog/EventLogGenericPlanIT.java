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
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The plan-mode check of P0.16 (design §3.3): the tail reader runs under {@code force_generic_plan}, so every reader
 * statement not in {@link TailReader#CUSTOM_PLAN_STATEMENTS} must have a generic plan that reaches the event table only
 * through index conditions - no sequential scan and no walk of the primary key with the predicate as a filter.
 * Must-fail control: P0.11's {@code OR global_order = ANY(:holes)} form, which walks the primary key.
 */
class EventLogGenericPlanIT extends AbstractEventLogIT {
    private AggregateTypeTables tables;

    @BeforeEach
    void populate() throws SQLException {
        startedEventLog("Orders");
        tables = AggregateTypeTables.of("Orders");
        execute("INSERT INTO " + tables.eventTable() + " (aggregate_id, event_order, event_id, event_type, event_revision, timestamp, event_payload, event_metadata) " +
                        "SELECT 'a' || (n % 1000), n / 1000, gen_random_uuid(), 1, 1, now(), '{\"n\":1}', '{}' FROM generate_series(1, 200000) n");
        execute("ANALYZE " + tables.eventTable());
    }

    @Test
    void every_generic_reader_statement_reads_the_event_table_by_index_only() throws SQLException {
        var statements = EventLogStorage.TailStatements.of(tables.eventTable()).all();
        assertThat(statements).containsKeys("head", "plainTail", "probe", "probeWithRanges", "holeRead", "runningSet", "rereadTail", "rereadTailWithRanges");
        for (var entry : statements.entrySet()) {
            if (TailReader.CUSTOM_PLAN_STATEMENTS.contains(entry.getKey())) {
                continue;
            }
            var plan = genericPlan(entry.getValue());
            assertThat(primaryKeyWalks(plan)).as("%s's generic plan:%n%s", entry.getKey(), String.join("\n", plan)).isEmpty();
        }
    }

    @Test
    void control_the_or_form_walks_the_primary_key_under_a_generic_plan() throws SQLException {
        var or = "SELECT global_order FROM " + tables.eventTable() + " WHERE global_order > ? OR global_order = ANY(?::bigint[]) ORDER BY global_order LIMIT ?";
        var plan = genericPlan(or);
        assertThat(primaryKeyWalks(plan)).as("must-fail control, plan:%n%s", String.join("\n", plan)).isNotEmpty();
    }

    private List<String> genericPlan(String sql) throws SQLException {
        var numbered = new StringBuilder();
        var n        = 0;
        for (var c : sql.toCharArray()) {
            numbered.append(c == '?' ? "$" + (++n) : String.valueOf(c));
        }
        var plan = new ArrayList<String>();
        try (var connection = dataSource("preferQueryMode=simple").getConnection(); var statement = connection.createStatement()) {
            statement.execute("SET jit = off");
            try (var resultSet = statement.executeQuery("EXPLAIN (GENERIC_PLAN) " + numbered)) {
                while (resultSet.next()) {
                    plan.add(resultSet.getString(1));
                }
            }
        }
        return plan;
    }

    /**
     * @return plan nodes that walk the event table: a sequential scan, or an index scan with a {@code Filter} and no
     * {@code Index Cond} (an index scan with neither, as {@code max()} uses behind a limit, reads one row)
     */
    private List<String> primaryKeyWalks(List<String> plan) {
        var walks = new ArrayList<String>();
        for (var i = 0; i < plan.size(); i++) {
            var line = plan.get(i);
            if (!line.contains(" on " + tables.eventTable())) {
                continue;
            }
            if (line.contains("Seq Scan")) {
                walks.add(line.trim());
                continue;
            }
            if (line.contains("Index Scan") || line.contains("Index Only Scan") || line.contains("Bitmap Heap Scan")) {
                var hasCondition = false;
                var hasFilter    = false;
                for (var j = i + 1; j < plan.size() && !plan.get(j).contains("->"); j++) {
                    hasCondition |= plan.get(j).contains("Index Cond") || plan.get(j).contains("Recheck Cond");
                    hasFilter |= plan.get(j).trim().startsWith("Filter:");
                }
                if (!hasCondition && hasFilter) {
                    walks.add(line.trim());
                }
            }
        }
        return walks;
    }
}
