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

package dk.trustworks.essentials.components.eventlog.migration;

import dk.trustworks.essentials.components.eventlog.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static dk.trustworks.essentials.components.eventlog.migration.PreflightReport.RefusalKind.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Each preflight refusal, and that a refused {@code prepare}
 * writes nothing
 */
class MigrationPreflightIT extends AbstractMigrationIT {

    private Migration plan(SourceTable... tables) {
        return new Migration(new MigrationPlan(dataSource, eventLog, List.of(tables), CopySettings.defaults()));
    }

    @Test
    void each_row_refusal_is_counted_with_samples_and_nothing_is_written() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, "Orders", "Users");
        old.streams("Orders", 3, 5);
        old.streams("Users", 2, 5);
        var orders = old.table("Orders");
        execute("UPDATE " + orders + " SET event_id = upper(event_id) WHERE global_order IN (2, 3)");
        execute("UPDATE " + orders + " SET correlation_id = 'corr-4' WHERE global_order = 4");
        execute("UPDATE " + orders + " SET event_revision = 'x' WHERE global_order = 5");
        execute("UPDATE " + orders + " SET event_revision = '70000' WHERE global_order = 6");
        execute("ALTER TABLE " + orders + " ALTER COLUMN event_metadata DROP NOT NULL");
        execute("UPDATE " + orders + " SET event_metadata = NULL WHERE global_order = 7");
        execute("ALTER SEQUENCE " + old.table("Users") + "_global_order_seq CACHE 10");
        var migration = migration(old);
        var report    = migration.preflight();
        assertThat(report.passed()).isFalse();
        var o = report.table("Orders").orElseThrow();
        assertThat(o.refusals()).extracting(PreflightReport.Refusal::kind)
                                .containsExactlyInAnyOrder(EVENT_ID_NOT_CANONICAL_UUID, CORRELATION_ID_NOT_CANONICAL_UUID, EVENT_REVISION_NOT_SMALLINT, REQUIRED_VALUE_NULL);
        assertThat(o.refusals()).filteredOn(r -> r.kind() == EVENT_ID_NOT_CANONICAL_UUID).singleElement()
                                .satisfies(r -> assertThat(r.sampleGlobalOrders()).containsExactly(2L, 3L));
        assertThat(o.refusals()).filteredOn(r -> r.kind() == EVENT_REVISION_NOT_SMALLINT).singleElement()
                                .satisfies(r -> assertThat(r.sampleGlobalOrders()).containsExactly(5L, 6L));
        assertThat(o.rows()).isEqualTo(15);
        assertThat(report.table("Users").orElseThrow().refusals()).extracting(PreflightReport.Refusal::kind).containsExactly(SEQUENCE_CACHE_NOT_ONE);
        // a refused prepare writes nothing
        assertThatThrownBy(migration::prepare).isInstanceOf(PreflightRefusedException.class).hasMessageContaining("EVENT_ID_NOT_CANONICAL_UUID");
        assertThat(queryLong("SELECT count(*) FROM el_migration")).isZero();
        assertThat(queryLong("SELECT count(*) FROM pg_class WHERE relname IN ('el_orders_events', 'el_users_events')")).isZero();
    }

    @Test
    void a_missing_table_a_missing_column_and_a_name_too_long_are_refused() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, "Orders", "Users");
        old.streams("Orders", 1, 2);
        var c = SourceColumns.defaults();
        var renamed = new SourceColumns(c.globalOrder(), c.aggregateId(), c.eventOrder(), c.eventId(), c.causedByEventId(), c.correlationId(), c.eventType(),
                                        c.eventRevision(), c.timestamp(), "payload_column", c.eventMetaData(), c.tenant());
        var report = plan(SourceTable.of("Missing", "missing_events"),
                          new SourceTable("Renamed", old.table("Orders"), renamed),
                          SourceTable.of("A".repeat(AggregateTypeTables.MAX_AGGREGATE_TYPE_LENGTH + 1), old.table("Users"))).preflight();
        assertThat(report.table("Missing").orElseThrow().refused(SOURCE_TABLE_MISSING)).isTrue();
        assertThat(report.table("Renamed").orElseThrow().refused(COLUMN_MISSING)).isTrue();
        assertThat(report.tables().get(2).refused(AGGREGATE_TYPE_NAME)).isTrue();
        assertThat(report.tables().get(2).refusals()).hasSize(1);
    }

    @Test
    void a_target_table_the_event_log_already_wrote_is_refused() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, "Orders");
        old.streams("Orders", 1, 2);
        eventLog.registerAggregateType("Orders");
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            eventLog.append(connection, AppendRequest.withoutExpectedOrder("Orders", "x",
                                                                           List.of(new EventToAppend(UUID.randomUUID(), null, null, "E", 1, Instant.now(), "{}", "{}"))));
            connection.commit();
        }
        assertThat(migration(old).preflight().table("Orders").orElseThrow().refused(TARGET_NOT_EMPTY)).isTrue();
    }

    @Test
    void more_event_types_than_smallint_ids_are_refused() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, "Orders");
        old.streams("Orders", 1, 1);
        var table = old.table("Orders");
        execute("INSERT INTO " + table + " (aggregate_id, event_order, event_id, correlation_id, event_type, event_revision, timestamp, event_payload, event_metadata) " +
                        "SELECT 'g' || k, 0, gen_random_uuid()::text, NULL, 'Type' || k, '1', now(), '{}', '{}' FROM generate_series(1, 32767) k");
        var report = migration(old).preflight();
        assertThat(report.planRefusals()).extracting(PreflightReport.Refusal::kind).containsExactly(TOO_MANY_EVENT_TYPES);
        assertThat(report.passed()).isFalse();
    }

    @Test
    void a_clean_source_passes_and_a_uuid_id_source_is_warned_about() throws Exception {
        var old = new OldEngine(IdentifierColumnType.UUID, JSONColumnType.JSON, "Orders");
        old.streams("Orders", 2, 3);
        var report = migration(old).preflight();
        assertThat(report.passed()).isTrue();
        var t = report.table("Orders").orElseThrow();
        assertThat(t.rows()).isEqualTo(6);
        assertThat(t.maxGlobalOrder()).isEqualTo(6);
        assertThat(t.sequenceLastValue()).isEqualTo(6);
        assertThat(t.warnings()).anySatisfy(w -> assertThat(w).contains("AggregateIdSerializer"));
    }
}
