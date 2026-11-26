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
import java.util.concurrent.*;

import static dk.trustworks.essentials.components.eventlog.migration.MigrationMutant.*;
import static org.assertj.core.api.Assertions.*;

/**
 * An epoch copies only what can no longer change. A writer that
 * drew a value below {@code M} and commits after the epoch's start holds the epoch up until it ends.
 */
class MigrationEpochIT extends AbstractMigrationIT {
    private static final String TYPE = "Orders";

    /** @return the old rows missing from the event log after the held writer committed and a second epoch ran */
    private long epochAroundHeldWriter(MigrationMutant... mutants) throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 3, 5);
        var migration = migration(old);
        migration.prepare();
        migration.seams(mutants(mutants));
        var held = old.held(TYPE);
        held.awaitInserted();
        old.stream(TYPE, 2); // committed, above the held writer's value: M
        var epoch = CompletableFuture.supplyAsync(() -> {
            try {
                return migration.copyEpoch();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
        await(epoch::isDone, Duration.ofSeconds(1));
        var finishedBeforeRelease = epoch.isDone();
        held.releaseAndJoin();
        var first = epoch.get(30, TimeUnit.SECONDS);
        assertThat(first.timedOut()).isFalse();
        if (mutants.length == 0) {
            assertThat(finishedBeforeRelease).as("the epoch waited for the older transaction").isFalse();
        }
        migration.copyEpoch();
        assertThat(migration.status(TYPE).orElseThrow().epochs()).isEqualTo(2);
        return queryLong("SELECT count(*) FROM " + old.table(TYPE)) - newCount(TYPE);
    }

    @Test
    void the_epoch_waits_for_a_writer_holding_a_value_below_M() throws Exception {
        assertThat(epochAroundHeldWriter()).isZero();
    }

    @Test
    void must_fail_epoch_EPOCH_WITHOUT_WAIT() throws Exception {
        assertThat(epochAroundHeldWriter(EPOCH_WITHOUT_WAIT)).as("the held writer's row, committed below the copied maximum").isEqualTo(1);
    }

    @Test
    void a_wait_timeout_copies_nothing_and_names_the_blocker() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 2, 5);
        var migration = migration(CopySettings.defaults().withEpochWaitTimeout(Duration.ofMillis(300)), old);
        migration.prepare();
        var held = old.held(TYPE);
        held.awaitInserted();
        try {
            var report = migration.copyEpoch();
            assertThat(report.timedOut()).isTrue();
            assertThat(report.blockers()).isNotEmpty();
            assertThat(report.blockers().getFirst().transactionStart()).isNotNull();
            assertThat(report.rows()).isZero();
            assertThat(newCount(TYPE)).isZero();
            assertThat(migration.status(TYPE).orElseThrow().copiedThrough()).isZero();
            assertThat(migration.status(TYPE).orElseThrow().epochs()).isZero();
        } finally {
            held.releaseAndJoin();
        }
        assertThat(migration.copyEpoch().rows()).isEqualTo(11);
    }

    @Test
    void the_epoch_waits_for_a_prepared_two_phase_transaction_holding_a_value_below_M() throws Exception {
        // The barrier read only pg_stat_activity, which a prepared transaction leaves: the epoch copied through 12, and
        // the prepared append's 11, committed afterwards below copied_through, was never copied
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 2, 5);                                   // 1-10
        var migration = migration(CopySettings.defaults().withEpochWaitTimeout(Duration.ofMillis(300)), old);
        migration.prepare();
        old.prepared(TYPE, "migration-2pc");                       // 11, prepared, invisible
        old.stream(TYPE, 1);                                       // 12, committed
        try {
            var report = migration.copyEpoch();
            assertThat(report.timedOut()).as("the prepared transaction holds the epoch").isTrue();
            assertThat(report.blockers()).anySatisfy(blocker -> assertThat(blocker.applicationName()).contains("migration-2pc"));
            assertThat(report.rows()).isZero();
        } finally {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("COMMIT PREPARED 'migration-2pc'");
            }
        }
        assertThat(migration.copyEpoch().rows()).isEqualTo(12);
        assertThat(differingRows(old, TYPE, oldMax(old, TYPE))).isZero();
    }

    @Test
    void the_throttle_holds_the_copy_rate() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 10, 10);
        var migration = migration(CopySettings.defaults().withBatchSize(10).withMaxEventsPerSecond(200), old);
        migration.prepare();
        var report = migration.copyEpoch();
        assertThat(report.rows()).isEqualTo(100);
        assertThat(report.copyTime()).isGreaterThanOrEqualTo(Duration.ofMillis(450)); // 100 rows at 200/s
        assertThat(differingRows(old, TYPE, oldMax(old, TYPE))).isZero();
    }

    @Test
    void epochs_converge_and_a_rerun_copies_nothing_new() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 4, 5);
        var migration = migration(old);
        migration.prepare();
        assertThat(migration.copyEpoch().rows()).isEqualTo(20);
        old.streams(TYPE, 1, 3);
        var second = migration.copyEpoch();
        assertThat(second.rows()).isEqualTo(3);
        assertThat(second.types().getFirst().from()).isEqualTo(second.types().getFirst().to() - 3);
        assertThat(migration.copyEpoch().rows()).isZero();
        assertThat(differingRows(old, TYPE, oldMax(old, TYPE))).isZero();
        assertHeadsConsistent(TYPE);
    }
}
