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

import dk.trustworks.essentials.components.eventlog.migration.VerificationReport.RowDifference;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static dk.trustworks.essentials.components.eventlog.migration.MigrationMutant.*;
import static dk.trustworks.essentials.components.eventlog.migration.VerificationReport.RowDifference.Kind.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Design phase 9 §6: window hashes, bisection to the differing rows, the structural checks, online and under the
 * cut-over, the record in {@code el_migration.report}, and {@code verify --after-cutover}
 */
class MigrationVerifyIT extends AbstractMigrationIT {
    private static final String TYPE = "Orders";

    private OldEngine old() {
        return new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSON, TYPE);
    }

    private Migration migration(OldEngine old, int window) {
        return migration(CopySettings.defaults().withBatchSize(50).withVerifyWindow(window), old);
    }

    private String report(String path) throws SQLException {
        try (var c = dataSource.getConnection(); var s = c.prepareStatement("SELECT report::jsonb #>> ?::text[] FROM el_migration WHERE aggregate_type = ?")) {
            s.setString(1, path);
            s.setString(2, TYPE);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getString(1);
            }
        }
    }

    @Test
    void online_verification_is_incremental_and_recorded() throws Exception {
        var old = old();
        old.streams(TYPE, 20, 10);
        var migration = migration(old, 64);
        migration.prepare();
        migration.copyEpoch();

        var first = migration.verify().type(TYPE).orElseThrow();
        assertThat(first.equal()).isTrue();
        assertThat(first.from()).isZero();
        assertThat(first.to()).isEqualTo(200);
        assertThat(first.rows()).isEqualTo(200);
        assertThat(first.windows()).extracting(VerificationReport.Window::to).containsExactly(64L, 128L, 192L, 200L);
        assertThat(report("{verifiedThrough}")).isEqualTo("200");
        assertThat(queryLong("SELECT jsonb_array_length(report::jsonb -> 'windows') FROM el_migration")).isEqualTo(4);

        var again = migration.verify().type(TYPE).orElseThrow();
        assertThat(again.windows()).as("nothing copied since").isEmpty();
        assertThat(again.equal()).isTrue();

        old.streams(TYPE, 3, 5);
        migration.copyEpoch();
        var next = migration.verify().type(TYPE).orElseThrow();
        assertThat(next.from()).isEqualTo(200);
        assertThat(next.to()).isEqualTo(215);
        assertThat(next.rows()).isEqualTo(15);
        assertThat(report("{verifiedThrough}")).isEqualTo("215");
        assertThat(queryLong("SELECT jsonb_array_length(report::jsonb -> 'windows') FROM el_migration")).isEqualTo(5);

        var all = migration.verifyAll().type(TYPE).orElseThrow();
        assertThat(all.equal()).isTrue();
        assertThat(all.from()).isZero();
        assertThat(queryLong("SELECT jsonb_array_length(report::jsonb -> 'windows') FROM el_migration")).as("replaced").isEqualTo(4);

        // the cut-over verifies exactly what its final pass copies (§16.7), and records it
        old.streams(TYPE, 1, 4);
        migration.cutover();
        assertThat(report("{lastVerification,mode}")).isEqualTo("CUTOVER");
        assertThat(report("{lastVerification,from}")).isEqualTo("215");
        assertThat(report("{lastVerification,to}")).isEqualTo("219");
        assertThat(report("{verifiedThrough}")).isEqualTo("219");
    }

    @Test
    void bisection_names_exactly_the_rows_that_differ() throws Exception {
        var old = old();
        old.streams(TYPE, 10, 10);  // 1..100
        old.burn(TYPE, 2);          // 101, 102: holes
        old.streams(TYPE, 10, 10);  // 103..202
        var migration = migration(old, 1_000); // one window, bisected down to rows
        migration.prepare();
        migration.copyEpoch();
        execute("UPDATE el_orders_events SET event_payload = '{\"tampered\":true}' WHERE global_order = 37");
        execute("UPDATE el_orders_events SET tenant = 't' WHERE global_order = 180");
        execute("DELETE FROM el_orders_events WHERE global_order = 150");
        execute("INSERT INTO el_orders_events (global_order, event_order, timestamp, event_id, caused_by_event_id, correlation_id, event_type, event_revision, " +
                        "aggregate_id, event_payload, event_metadata, tenant) SELECT 101, event_order + 1000, timestamp, gen_random_uuid(), caused_by_event_id, correlation_id, " +
                        "event_type, event_revision, aggregate_id, event_payload, event_metadata, tenant FROM el_orders_events WHERE global_order = 100");

        var report = migration.verify();
        assertThat(report.equal()).isFalse();
        var type = report.type(TYPE).orElseThrow();
        assertThat(type.differingWindows()).singleElement().satisfies(w -> {
            assertThat(w.oldRows()).isEqualTo(200);
            assertThat(w.newRows()).isEqualTo(200);
        });
        assertThat(type.differingRows()).containsExactly(new RowDifference(37, DIFFERENT), new RowDifference(101, ONLY_IN_EVENT_LOG),
                                                         new RowDifference(150, MISSING_IN_EVENT_LOG), new RowDifference(180, DIFFERENT));
        assertThat(report("{verifiedThrough}")).as("nothing verified").isEqualTo("0");
        assertThat(report("{lastVerification,equal}")).isEqualTo("false");

        assertThatThrownBy(migration::cutover).isInstanceOf(VerificationFailedException.class)
                                              .satisfies(e -> assertThat(((VerificationFailedException) e).report().type(TYPE).orElseThrow().differingRows())
                                                      .extracting(RowDifference::globalOrder).containsExactly(37L, 101L, 150L, 180L));
        assertThat(migration.status(TYPE).orElseThrow().state()).isEqualTo(MigrationStatus.State.COPYING);
    }

    @Test
    void a_difference_found_by_verify_all_is_verified_again_by_the_cut_over() throws Exception {
        var old = old();
        old.streams(TYPE, 5, 10);
        var migration = migration(old, 16);
        migration.prepare();
        migration.copyEpoch();
        assertThat(migration.verify().equal()).isTrue();
        execute("UPDATE el_orders_events SET event_revision = 7 WHERE global_order = 3");
        assertThat(migration.verify().type(TYPE).orElseThrow().windows()).as("incremental: nothing new").isEmpty();
        assertThat(migration.verifyAll().type(TYPE).orElseThrow().differingRows()).containsExactly(new RowDifference(3, DIFFERENT));
        assertThat(report("{verifiedThrough}")).isEqualTo("0");
        assertThatThrownBy(migration::cutover).isInstanceOf(VerificationFailedException.class);
    }

    @Test
    void verification_finds_missing_heads() throws Exception {
        var old = old();
        old.streams(TYPE, 3, 4);
        var migration = migration(old, 1_000);
        migration.seams(mutants(HEADS_NOT_WRITTEN));
        migration.prepare();
        migration.copyEpoch();
        var type = migration.verify().type(TYPE).orElseThrow();
        assertThat(type.differingRows()).isEmpty();
        assertThat(type.structuralFailures()).singleElement().asString().startsWith("3 streams").contains("head is missing");
        old.streams(TYPE, 1, 2);
        assertThatThrownBy(migration::cutover).isInstanceOf(VerificationFailedException.class).hasMessageContaining("head is missing");
    }

    @Test
    void verification_finds_an_identity_sequence_below_the_old_one() throws Exception {
        var old = old();
        old.streams(TYPE, 3, 4);
        old.burn(TYPE, 5); // 13..17 burned at the end
        var migration = migration(old, 1_000);
        migration.prepare();
        migration.copyEpoch();
        migration.seams(mutants(SEQUENCE_AT_MAX_ROW));
        assertThatThrownBy(migration::cutover).isInstanceOf(VerificationFailedException.class)
                                              .hasMessageContaining("identity sequence stands at 12")
                                              .hasMessageContaining("= 17");
        migration.seams(MigrationSeams.NONE);
        assertThat(migration.cutover().types().getFirst().sequenceValue()).isEqualTo(17);
    }

    @Test
    void rows_an_old_node_writes_after_the_cut_over_are_reported() throws Exception {
        var old = old();
        old.streams(TYPE, 2, 5);
        var migration = migration(old, 1_000);
        migration.prepare();
        migration.copyEpoch();
        assertThatThrownBy(migration::verifyAfterCutover).isInstanceOf(IllegalStateException.class).hasMessageContaining("not cut over");
        migration.cutover();
        assertThat(migration.verifyAfterCutover().clean()).isTrue();

        old.stream(TYPE, 2); // a forgotten old node
        var late = migration.verifyAfterCutover();
        assertThat(late.clean()).isFalse();
        var type = late.type(TYPE).orElseThrow();
        assertThat(type.copiedThrough()).isEqualTo(10);
        assertThat(type.count()).isEqualTo(2);
        assertThat(type.max()).isEqualTo(12);
        assertThat(type.samples()).extracting(LateRowsReport.LateRow::globalOrder).containsExactly(11L, 12L);
        assertThat(type.samples()).allSatisfy(r -> assertThat(r.timestamp()).isNotNull());
        // the copied rows themselves are still equal
        assertThat(migration.verifyAll().equal()).isTrue();
    }
}
