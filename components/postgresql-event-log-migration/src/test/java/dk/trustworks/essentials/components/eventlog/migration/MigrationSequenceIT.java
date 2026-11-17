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
 * Design phase 9 §9.1 {@code MigrationSequenceIT} and §3.6: values burned at the old table's end can put an old resume
 * point above the highest row; the event log's identity continues above the old sequence, not above the highest row.
 */
class MigrationSequenceIT extends AbstractMigrationIT {
    private static final String TYPE = "Orders";

    /** @return the global order of the first event appended on the event log after the cut-over, and the old sequence's last value */
    private long[] firstAppendAfterCutover(MigrationMutant... mutants) throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 3, 4);
        old.burn(TYPE, 5); // values 13..17 burned: an old subscriber that saw everything resumes from 18
        var oldLastValue = queryLong("SELECT last_value FROM pg_sequences WHERE sequencename = '" + old.table(TYPE).toLowerCase(Locale.ROOT) + "_global_order_seq'");
        assertThat(oldLastValue).isEqualTo(17);
        var migration = migration(old);
        migration.seams(mutants(mutants));
        migration.prepare();
        migration.copyEpoch();
        migration.cutover();
        // a node of the new engine, started after the cut-over
        var node = EventLog.builder().setDataSource(dataSource).build();
        node.start();
        node.registerAggregateType(TYPE);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var result = node.append(connection, AppendRequest.afterEventOrder(TYPE, "new-stream", AppendRequest.NEW_STREAM,
                                                                               List.of(new EventToAppend(UUID.randomUUID(), null, null, "NewEvent", 1, Instant.now(), "{}", "{}"))));
            connection.commit();
            return new long[]{result.events().getFirst().globalOrder(), oldLastValue};
        } finally {
            node.stop();
        }
    }

    @Test
    void the_first_new_event_lands_above_the_old_sequence() throws Exception {
        var r = firstAppendAfterCutover();
        assertThat(r[0]).as("at or above the resume point of a subscriber that saw everything").isEqualTo(r[1] + 1);
    }

    @Test
    void must_fail_sequence_SEQUENCE_AT_MAX_ROW() throws Exception {
        var r = firstAppendAfterCutover(SEQUENCE_AT_MAX_ROW);
        assertThat(r[0]).as("below the old resume point, so the imported subscription skips it").isLessThanOrEqualTo(r[1]);
    }
}
