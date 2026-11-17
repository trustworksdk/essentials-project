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

import static dk.trustworks.essentials.components.eventlog.migration.MigrationMutant.*;
import static org.assertj.core.api.Assertions.*;

/**
 * Design phase 9 §9.1 {@code MigrationHeadsIT} and §3.6: the copied heads make the first append's optimistic check
 * right, for streams copied online and by the cut-over alike. (The engine's append is the check; the adapter's
 * {@code OptimisticAppendToStreamException} wraps the engine's {@link StreamAppendConflictException}.)
 */
class MigrationHeadsIT extends AbstractMigrationIT {
    private static final String TYPE = "Orders";

    private record Outcome(int streams, int rightOrderFailed, int staleOrderAccepted) {
    }

    private Outcome firstAppends(MigrationMutant... mutants) throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 4, 6);
        var migration = migration(CopySettings.defaults().withBatchSize(7), old); // streams span batches
        migration.seams(mutants(mutants));
        migration.prepare();
        migration.copyEpoch();
        old.streams(TYPE, 2, 3); // copied by the cut-over
        migration.cutover();
        var streams = new LinkedHashMap<String, Long>();
        try (var c = dataSource.getConnection(); var s = c.createStatement();
             var r = s.executeQuery("SELECT aggregate_id, max(event_order) FROM " + old.table(TYPE) + " GROUP BY aggregate_id")) {
            while (r.next()) {
                streams.put(r.getString(1), r.getLong(2));
            }
        }
        var node = EventLog.builder().setDataSource(dataSource).build();
        node.start();
        node.registerAggregateType(TYPE);
        int rightFailed = 0, staleAccepted = 0;
        try {
            for (var stream : streams.entrySet()) {
                if (!append(node, stream.getKey(), stream.getValue() - 1)) {
                    // refused, as it must be
                } else {
                    staleAccepted++;
                }
                if (!append(node, stream.getKey(), stream.getValue())) {
                    rightFailed++;
                }
            }
        } finally {
            node.stop();
        }
        return new Outcome(streams.size(), rightFailed, staleAccepted);
    }

    private boolean append(EventLog node, String aggregateId, long expectedEventOrder) throws Exception {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                node.append(connection, AppendRequest.afterEventOrder(TYPE, aggregateId, expectedEventOrder,
                                                                      List.of(new EventToAppend(UUID.randomUUID(), null, null, "NewEvent", 1, Instant.now(), "{}", "{}"))));
                connection.commit();
                return true;
            } catch (StreamAppendConflictException e) {
                connection.rollback();
                return false;
            }
        }
    }

    @Test
    void the_first_append_to_every_copied_stream_passes_with_the_right_order_and_fails_with_a_stale_one() throws Exception {
        var outcome = firstAppends();
        assertThat(outcome.streams()).isEqualTo(6);
        assertThat(outcome.rightOrderFailed()).isZero();
        assertThat(outcome.staleOrderAccepted()).isZero();
        assertHeadsConsistent(TYPE);
    }

    @Test
    void must_fail_heads_HEADS_NOT_WRITTEN() throws Exception {
        assertThat(firstAppends(HEADS_NOT_WRITTEN, CUTOVER_WITHOUT_VERIFY).rightOrderFailed()).as("appends with the right expected order refused").isEqualTo(6);
    }
}
