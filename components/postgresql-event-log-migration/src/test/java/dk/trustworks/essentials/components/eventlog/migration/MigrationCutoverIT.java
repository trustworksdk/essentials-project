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

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static dk.trustworks.essentials.components.eventlog.migration.MigrationMutant.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Design phase 9 §9.1 {@code MigrationCutoverIT} and §3.5: the cut-over's {@code SHARE} locks wait for every in-flight
 * old writer and block every new one until the commit.
 */
class MigrationCutoverIT extends AbstractMigrationIT {
    private static final String TYPE = "Orders";

    private OldEngine prepared(Migration[] migration, CopySettings settings) throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 3, 4);
        migration[0] = migration(settings, old);
        migration[0].prepare();
        migration[0].copyEpoch();
        old.streams(TYPE, 2, 3); // the delta
        return old;
    }

    private static CompletableFuture<CutoverReport> async(Migration migration) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return migration.cutover();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    /** @return the old rows missing from the event log after the cut-over that started under an in-flight writer */
    private long cutoverUnderInFlightWriter(MigrationMutant... mutants) throws Exception {
        var migration = new Migration[1];
        var old       = prepared(migration, CopySettings.defaults().withBatchSize(5));
        migration[0].seams(mutants(mutants));
        var held = old.held(TYPE);
        held.awaitInserted();
        var cutover = async(migration[0]);
        await(() -> cutover.isDone() || waitingForLockOn(old.table(TYPE)), Duration.ofSeconds(5));
        if (mutants.length == 0) {
            assertThat(cutover.isDone()).as("the cut-over waits for the in-flight writer").isFalse();
        }
        held.releaseAndJoin();
        var report = cutover.get(30, TimeUnit.SECONDS);
        assertThat(report.types().getFirst().to()).isEqualTo(migration[0].status(TYPE).orElseThrow().copiedThrough());
        return queryLong("SELECT count(*) FROM " + old.table(TYPE)) - newCount(TYPE);
    }

    @Test
    void the_cut_over_waits_for_an_in_flight_writer_and_copies_its_row() throws Exception {
        assertThat(cutoverUnderInFlightWriter()).isZero();
        assertHeadsConsistent(TYPE);
    }

    @Test
    void must_fail_cutover_CUTOVER_WITHOUT_LOCK() throws Exception {
        assertThat(cutoverUnderInFlightWriter(CUTOVER_WITHOUT_LOCK)).as("the in-flight writer's row, committed after the cut-over").isEqualTo(1);
    }

    /** @return whether a writer arriving during the cut-over was blocked until its commit */
    private boolean writerArrivingDuringTheCutover(MigrationMutant... mutants) throws Exception {
        var migration = new Migration[1];
        var old       = prepared(migration, CopySettings.defaults());
        var blocked   = new AtomicBoolean();
        var arriving  = new ArrayList<CompletableFuture<Void>>();
        migration[0].seams(seams(Set.of(mutants), Map.of(MigrationSeams.Hook.CUTOVER_LOCKED, connection -> {
            var writer = CompletableFuture.runAsync(() -> old.stream(TYPE, 1));
            arriving.add(writer);
            try {
                await(() -> writer.isDone() || waitingForLockOn(old.table(TYPE)), Duration.ofSeconds(5));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            blocked.set(!writer.isDone());
        }), name -> true));
        migration[0].cutover();
        arriving.getFirst().get(30, TimeUnit.SECONDS);
        var copiedThrough = migration[0].status(TYPE).orElseThrow().copiedThrough();
        assertThat(queryLong("SELECT count(*) FROM " + old.table(TYPE) + " WHERE global_order > " + copiedThrough))
                .as("the late writer's row is above the recorded copied maximum (verify --after-cutover finds it)").isEqualTo(mutants.length == 0 ? 1 : 0);
        return blocked.get();
    }

    @Test
    void a_writer_arriving_during_the_cut_over_waits_for_its_commit() throws Exception {
        assertThat(writerArrivingDuringTheCutover()).isTrue();
    }

    @Test
    void must_fail_cutover_arriving_writer_CUTOVER_WITHOUT_LOCK() throws Exception {
        assertThat(writerArrivingDuringTheCutover(CUTOVER_WITHOUT_LOCK)).as("the arriving writer appended during the cut-over").isFalse();
    }

    @Test
    void writers_still_running_time_the_cut_over_out_and_nothing_is_copied() throws Exception {
        var migration = new Migration[1];
        var old       = prepared(migration, CopySettings.defaults().withLockTimeout(Duration.ofMillis(300)));
        var before    = newCount(TYPE);
        var held      = old.held(TYPE);
        held.awaitInserted();
        try {
            assertThatThrownBy(migration[0]::cutover).isInstanceOf(CutoverLockTimeoutException.class).hasMessageContaining("Writers still running");
            assertThat(newCount(TYPE)).isEqualTo(before);
            assertThat(migration[0].status(TYPE).orElseThrow().state()).isEqualTo(MigrationStatus.State.COPYING);
        } finally {
            held.releaseAndJoin();
        }
        var report = migration[0].cutover();
        assertThat(report.types().getFirst().rows()).isEqualTo(7);
        assertThat(differingRows(old, TYPE, oldMax(old, TYPE))).isZero();
        var status = migration[0].status(TYPE).orElseThrow();
        assertThat(status.state()).isEqualTo(MigrationStatus.State.CUT_OVER);
        assertThat(status.cutOverAt()).isNotNull();
        // the copy is over
        assertThatThrownBy(migration[0]::cutover).isInstanceOf(IllegalStateException.class).hasMessageContaining("already CUT_OVER");
        assertThatThrownBy(migration[0]::copyEpoch).isInstanceOf(IllegalStateException.class).hasMessageContaining("CUT_OVER");
        assertThat(migration[0].preflight().table(TYPE).orElseThrow().refused(PreflightReport.RefusalKind.ALREADY_CUT_OVER)).isTrue();
    }

    @Test
    void cut_over_steps_run_under_the_lock_and_their_failure_rolls_everything_back() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 2, 4);
        var seen = new ArrayList<CutoverReport.TypeCutover>();
        var failing = new Migration(new MigrationPlan(dataSource, eventLog, List.of(SourceTable.of(TYPE, old.table(TYPE))), CopySettings.defaults(),
                                                      List.of((connection, copied) -> {
                                                          seen.addAll(copied);
                                                          throw new IllegalStateException("verification mismatch");
                                                      })));
        failing.prepare();
        assertThatThrownBy(failing::cutover).hasMessageContaining("verification mismatch");
        assertThat(seen).singleElement().satisfies(t -> assertThat(t.rows()).isEqualTo(8));
        assertThat(newCount(TYPE)).isZero();
        assertThat(failing.status(TYPE).orElseThrow().state()).isEqualTo(MigrationStatus.State.COPYING);
        assertThat(migration(old).cutover().types().getFirst().rows()).isEqualTo(8);
    }
}
