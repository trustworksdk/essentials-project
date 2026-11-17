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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static dk.trustworks.essentials.components.eventlog.migration.MigrationMutant.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Design phase 9 §9.1 {@code MigrationResumeIT} and §3.7: a crash loses a batch together with its checkpoint, or the
 * whole cut-over; the rerun resumes and the copy is equal. A row someone else wrote stops the run.
 */
class MigrationResumeIT extends AbstractMigrationIT {
    private static final String TYPE = "Orders";

    private static MigrationSeams crashAt(MigrationSeams.Hook hook, int call, MigrationMutant... mutants) {
        var calls = new AtomicInteger();
        return seams(Set.of(mutants), Map.of(hook, connection -> {
            if (calls.incrementAndGet() == call) {
                throw new IllegalStateException("injected crash");
            }
        }), name -> true);
    }

    /** @return the rows that differ after a crash in the second batch and a rerun */
    private long crashBetweenBatchAndCheckpoint(MigrationMutant... mutants) throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 4, 10);
        var migration = migration(CopySettings.defaults().withBatchSize(10), old);
        migration.prepare();
        migration.seams(crashAt(MigrationSeams.Hook.BATCH_BEFORE_CHECKPOINT, 2, mutants));
        assertThatThrownBy(migration::copyEpoch).hasMessageContaining("injected crash");
        migration.seams(mutants(mutants));
        migration.copyEpoch();
        assertThat(migration.status(TYPE).orElseThrow().copiedThrough()).isEqualTo(40);
        return differingRows(old, TYPE, 40);
    }

    @Test
    void a_crash_between_batch_and_checkpoint_loses_both_and_the_rerun_resumes() throws Exception {
        assertThat(crashBetweenBatchAndCheckpoint()).isZero();
        assertThat(newCount(TYPE)).isEqualTo(40);
        assertHeadsConsistent(TYPE);
    }

    @Test
    void must_fail_resume_CHECKPOINT_OUTSIDE_BATCH() throws Exception {
        assertThat(crashBetweenBatchAndCheckpoint(CHECKPOINT_OUTSIDE_BATCH)).as("the batch whose checkpoint committed without it").isEqualTo(10);
    }

    @Test
    void a_crash_in_the_cut_over_rolls_it_back_and_the_rerun_completes_it() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 3, 5);
        var migration = migration(old);
        migration.prepare();
        migration.copyEpoch();
        old.streams(TYPE, 2, 5);
        migration.seams(crashAt(MigrationSeams.Hook.CUTOVER_BEFORE_COMMIT, 1));
        assertThatThrownBy(migration::cutover).hasMessageContaining("injected crash");
        var status = migration.status(TYPE).orElseThrow();
        assertThat(status.state()).isEqualTo(MigrationStatus.State.COPYING);
        assertThat(status.copiedThrough()).isEqualTo(15);
        assertThat(newCount(TYPE)).isEqualTo(15);
        migration.seams(MigrationSeams.NONE);
        assertThat(migration.cutover().types().getFirst().rows()).isEqualTo(10);
        assertThat(differingRows(old, TYPE, oldMax(old, TYPE))).isZero();
        assertHeadsConsistent(TYPE);
    }

    @Test
    void prepare_and_epochs_are_idempotent() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 2, 5);
        var migration = migration(old);
        migration.prepare();
        migration.copyEpoch();
        var again = migration.prepare();
        assertThat(again.table(TYPE).orElseThrow().warnings()).anySatisfy(w -> assertThat(w).startsWith("rerun"));
        assertThat(migration.copyEpoch().rows()).isZero();
        assertThat(differingRows(old, TYPE, oldMax(old, TYPE))).isZero();
    }

    @Test
    void a_row_someone_else_wrote_stops_the_run() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 2, 5);
        var migration = migration(old);
        migration.prepare();
        migration.copyEpoch();
        old.streams(TYPE, 1, 5);
        execute("INSERT INTO el_orders_events (global_order, event_order, timestamp, event_id, event_type, event_revision, aggregate_id, event_payload, event_metadata) " +
                        "VALUES (13, 0, now(), gen_random_uuid(), (SELECT min(id) FROM el_event_types), 1, 'intruder', '{}', '{}')");
        assertThat(migration.preflight().table(TYPE).orElseThrow().refused(PreflightReport.RefusalKind.TARGET_NOT_EMPTY)).isTrue();
        assertThatThrownBy(migration::copyEpoch).isInstanceOf(IllegalStateException.class).hasMessageContaining("something other than this migration wrote it");
        assertThat(migration.status(TYPE).orElseThrow().copiedThrough()).isEqualTo(10);
    }
}
