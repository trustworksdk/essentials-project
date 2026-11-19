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

import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;

import static dk.trustworks.essentials.components.eventlog.WatermarkMutant.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Phase 9 unit 9-B (design {@code docs/archive/event-store-engine-phase9.md} §3.8, §4.3, §9): the engine's side of the
 * migration from the old event store - the {@code el_migration} table, the {@code registerAggregateType} guard while a
 * type is being copied, and {@link EventLogAdmin#importProgress}. Each must-fail mutant has a {@code must_fail_*} test
 * that runs the arm against it and asserts the defect shows.
 */
class EventLogMigrationIT extends AbstractLeaseIT {
    private static final String COPIED = "CopiedOrders";

    private void migrationRow(String aggregateType, String state) throws SQLException {
        execute("INSERT INTO " + EventLogSchema.MIGRATION_TABLE + " (aggregate_type, state, source_table) VALUES ('" + aggregateType + "', '" + state +
                        "', 'copiedorders_events') ON CONFLICT (aggregate_type) DO UPDATE SET state = EXCLUDED.state");
    }

    private boolean tableExists(String table) throws SQLException {
        return queryLong("SELECT count(*) FROM pg_class WHERE relname = '" + table + "'") == 1;
    }

    // ---- el_migration ----

    @Test
    void the_fixed_schema_has_the_migration_table() throws Exception {
        eventLog("A");
        assertThat(queryLong("SELECT count(*) FROM information_schema.columns WHERE table_name = '" + EventLogSchema.MIGRATION_TABLE + "' AND column_name IN " +
                                     "('aggregate_type', 'state', 'source_table', 'copied_through', 'epochs', 'started_at', 'cut_over_at', 'report')")).isEqualTo(8);
        migrationRow(COPIED, "COPYING");
        assertThat(queryLong("SELECT copied_through + epochs FROM " + EventLogSchema.MIGRATION_TABLE)).isZero();
        assertThatThrownBy(() -> migrationRow("Other", "DONE")).isInstanceOf(SQLException.class).hasMessageContaining("check constraint");
        // Idempotent: a second start leaves the row
        eventLog("B");
        assertThat(queryLong("SELECT count(*) FROM " + EventLogSchema.MIGRATION_TABLE)).isEqualTo(1);
    }

    // ---- the registerAggregateType guard (§3.8) ----

    /** @return whether registering {@link #COPIED} while its row is {@code COPYING} was refused, with no table created */
    private boolean guardRefuses(WatermarkMutant... mutants) throws Exception {
        var logA = eventLog("A");
        migrationRow(COPIED, "COPYING");
        logA.adminSeams(seams(TestSeams.Hooks.NONE, mutants));
        var tables = AggregateTypeTables.of(COPIED);
        try {
            logA.registerAggregateType(COPIED);
            return false;
        } catch (IllegalStateException e) {
            assertThat(e).hasMessageContaining(COPIED).hasMessageContaining(EventLogSchema.MIGRATION_TABLE).hasMessageContaining("COPYING")
                         .hasMessageContaining("copiedorders_events").hasMessageContaining("cut-over");
            assertThat(tableExists(tables.eventTable())).as("event table created despite the refusal").isFalse();
            assertThat(tableExists(tables.headTable())).as("head table created despite the refusal").isFalse();
            return true;
        }
    }

    @Test
    void registration_is_refused_while_the_type_is_being_copied() throws Exception {
        assertThat(guardRefuses()).isTrue();
    }

    @Test
    void must_fail_guard_GUARD_SKIPPED() throws Exception {
        assertThat(guardRefuses(GUARD_SKIPPED)).as("a type being copied was registered").isFalse();
    }

    @Test
    void registration_after_the_cut_over_and_of_other_types_is_allowed() throws Exception {
        var logA = eventLog("A");
        migrationRow(COPIED, "COPYING");
        assertThatThrownBy(() -> logA.registerAggregateType(COPIED)).isInstanceOf(IllegalStateException.class);
        // Another type is unaffected
        logA.registerAggregateType("Shipments");
        // A node started during the copy is refused too, and a refusal is not cached
        var logB = EventLog.builder().setDataSource(dataSource("")).build();
        logB.start();
        try {
            assertThatThrownBy(() -> logB.registerAggregateType(COPIED)).isInstanceOf(IllegalStateException.class);
            migrationRow(COPIED, "CUT_OVER");
            assertThat(logA.registerAggregateType(COPIED)).isEqualTo(AggregateTypeTables.of(COPIED));
            assertThat(logB.registerAggregateType(COPIED)).isEqualTo(AggregateTypeTables.of(COPIED));
        } finally {
            logB.stop();
        }
        // The engine keeps the CUT_OVER row as the record of the move
        assertThat(queryLong("SELECT count(*) FROM " + EventLogSchema.MIGRATION_TABLE + " WHERE state = 'CUT_OVER'")).isEqualTo(1);
    }

    @Test
    void registration_without_the_migration_table_is_allowed() throws Exception {
        var logA = eventLog("A");
        execute("DROP TABLE " + EventLogSchema.MIGRATION_TABLE);
        assertThat(logA.registerAggregateType(COPIED)).isEqualTo(AggregateTypeTables.of(COPIED));
    }

    // ---- importProgress (§4.3) ----

    private static SubscriptionDefinition exclusiveDefinition(String subscriber) {
        // StartFrom 1: an imported row must win over it
        return Definitions.definition(subscriber, TYPE, null, 1, ConsistencyMode.FRESH, null);
    }

    /** importProgress on a connection of its own, committed */
    private long importCommitted(EventLog eventLog, SubscriptionDefinition definition, ProgressModel model, String instanceName, long resumeFrom) throws SQLException {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var stored = eventLog.admin().importProgress(definition, model, instanceName, resumeFrom, connection);
            connection.commit();
            return stored;
        }
    }

    private record ImportedRow(boolean exclusive, String mode, String model, long resumeFrom, long resetEpoch, boolean leaseFree, String filterDigest) {
    }

    private ImportedRow importedRow(String subscriber, String instanceName) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("SELECT exclusive, consistency_mode, progress_model, resume_from, reset_epoch, " +
                                                                 "lease_owner IS NULL AND lease_until IS NULL AND lease_fence IS NULL, filter_digest FROM " +
                                                                 EventLogSchema.PROGRESS_TABLE + " WHERE subscriber = ? AND aggregate_type = ? AND instance_name = ?")) {
            statement.setString(1, subscriber);
            statement.setString(2, TYPE);
            statement.setString(3, instanceName);
            try (var resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new ImportedRow(resultSet.getBoolean(1), resultSet.getString(2), resultSet.getString(3), resultSet.getLong(4), resultSet.getLong(5),
                                       resultSet.getBoolean(6), resultSet.getString(7));
            }
        }
    }

    @Test
    void an_imported_exclusive_row_is_claimed_and_delivers_from_its_resume_point() throws Exception {
        var logA = eventLog("A");
        append(logA, "order-1", 10);
        var definition = exclusiveDefinition("imported");
        assertThat(importCommitted(logA, definition, ProgressModel.PERIODIC, "", 4)).isEqualTo(4);
        assertThat(importedRow("imported", "")).isEqualTo(new ImportedRow(true, "FRESH", "PERIODIC", 4, 0, true, null));

        var delivered = new CopyOnWriteArrayList<Long>();
        logA.subscribe(definition, (PeriodicHandler) event -> delivered.add(event.globalOrder()));
        append(logA, "order-2", 2);
        awaitTrue(() -> delivered.size() == 9);
        assertThat(delivered).containsExactlyElementsOf(LongStream.rangeClosed(4, 12).boxed().toList());
        var row = importedRow("imported", "");
        assertThat(row.mode()).isEqualTo("FRESH");
        assertThat(row.model()).isEqualTo("PERIODIC");
        assertThat(row.resetEpoch()).isZero();
    }

    @Test
    void imported_per_instance_rows_deliver_from_their_resume_point() throws Exception {
        var logA = eventLog("A");
        append(logA, "order-1", 6);
        var filter = EventTypeFilter.of("OrderPlaced");
        var definitionA = Definitions.definition("view", TYPE, "a", 1, ConsistencyMode.CONTIGUOUS, filter);
        var definitionB = Definitions.definition("view", TYPE, "b", 1, ConsistencyMode.CONTIGUOUS, filter);
        assertThat(importCommitted(logA, definitionA, ProgressModel.IN_HANDLER_TRANSACTION, "a", 3)).isEqualTo(3);
        assertThat(importCommitted(logA, definitionA, ProgressModel.IN_HANDLER_TRANSACTION, "b", 3)).isEqualTo(3);
        var expected = new ImportedRow(false, "CONTIGUOUS", "IN_HANDLER_TRANSACTION", 3, 0, true, definitionA.filterDigest());
        assertThat(expected.filterDigest()).isNotNull();
        assertThat(importedRow("view", "a")).isEqualTo(expected);
        assertThat(importedRow("view", "b")).isEqualTo(expected);

        var deliveredA = new CopyOnWriteArrayList<Long>();
        var deliveredB = new CopyOnWriteArrayList<Long>();
        logA.subscribe(definitionA, PageHandler.perEvent((event, tx) -> deliveredA.add(event.globalOrder())));
        logA.subscribe(definitionB, PageHandler.perEvent((event, tx) -> deliveredB.add(event.globalOrder())));
        append(logA, "order-2", 1);
        awaitTrue(() -> deliveredA.size() == 5 && deliveredB.size() == 5);
        assertThat(deliveredA).containsExactly(3L, 4L, 5L, 6L, 7L);
        assertThat(deliveredB).containsExactly(3L, 4L, 5L, 6L, 7L);
        assertThat(importedRow("view", "a").model()).isEqualTo("IN_HANDLER_TRANSACTION");
    }

    @Test
    void the_import_runs_on_the_callers_transaction() throws Exception {
        var logA = eventLog("A");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            assertThat(logA.admin().importProgress(exclusiveDefinition("rolled-back"), ProgressModel.PERIODIC, "", 5, connection)).isEqualTo(5);
            assertThat(importedRow("rolled-back", "")).as("visible before the caller's commit").isNull();
            connection.rollback();
        }
        assertThat(importedRow("rolled-back", "")).isNull();
    }

    @Test
    void the_import_refuses_a_mismatched_instance_name() throws Exception {
        var admin = eventLog("A").admin();
        try (var connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> admin.importProgress(exclusiveDefinition("x"), ProgressModel.PERIODIC, "a", 5, connection))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> admin.importProgress(Definitions.definition("x", TYPE, "a", 1, null, null), ProgressModel.PERIODIC, " ", 5, connection))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> admin.importProgress(exclusiveDefinition("x"), ProgressModel.PERIODIC, "", 0, connection))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(queryLong("SELECT count(*) FROM " + EventLogSchema.PROGRESS_TABLE)).isZero();
    }

    /**
     * An existing row at resume point 7 (written by an earlier run, then advanced), imported again at 3.
     *
     * @return the value the import returned and the row's resume point after it
     */
    private long[] importOverExisting(WatermarkMutant... mutants) throws Exception {
        var logA = eventLog("A");
        logA.adminSeams(seams(TestSeams.Hooks.NONE, mutants));
        assertThat(importCommitted(logA, exclusiveDefinition("rerun"), ProgressModel.PERIODIC, "", 7)).isEqualTo(7);
        var returned = importCommitted(logA, exclusiveDefinition("rerun"), ProgressModel.PERIODIC, "", 3);
        return new long[]{returned, importedRow("rerun", "").resumeFrom()};
    }

    @Test
    void an_existing_row_is_left_and_its_resume_point_returned() throws Exception {
        assertThat(importOverExisting()).containsExactly(7, 7);
    }

    @Test
    void must_fail_import_IMPORT_PROGRESS_OVERWRITES() throws Exception {
        assertThat(importOverExisting(IMPORT_PROGRESS_OVERWRITES)[1]).as("the existing row's resume point was replaced").isEqualTo(3);
    }
}
