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

import static org.assertj.core.api.Assertions.*;

/**
 * Design phase 9 §9.1 {@code MigrationGuardIT} and §3.8: once {@code prepare} marks a type {@code COPYING}, no event
 * log node may register it until the cut-over. (The guard's mutant, {@code GUARD_SKIPPED}, is the engine's and is
 * shown by its {@code EventLogMigrationIT}.)
 */
class MigrationGuardIT extends AbstractMigrationIT {
    private static final String TYPE = "Orders";

    @Test
    void a_node_cannot_register_a_type_while_it_is_being_copied() throws Exception {
        var old = new OldEngine(IdentifierColumnType.TEXT, JSONColumnType.JSONB, TYPE);
        old.streams(TYPE, 2, 3);
        var migration = migration(old);
        migration.prepare();
        var node = EventLog.builder().setDataSource(dataSource).build();
        node.start();
        try {
            assertThatThrownBy(() -> node.registerAggregateType(TYPE)).isInstanceOf(IllegalStateException.class).hasMessageContaining("COPYING")
                                                                     .hasMessageContaining(old.table(TYPE));
            migration.copyEpoch();
            assertThatThrownBy(() -> node.registerAggregateType(TYPE)).isInstanceOf(IllegalStateException.class);
            migration.cutover();
            node.registerAggregateType(TYPE);
            assertThat(node.loadStream(TYPE, queryText("SELECT aggregate_id FROM " + old.table(TYPE) + " LIMIT 1"))).hasSize(3);
        } finally {
            node.stop();
        }
    }

    private String queryText(String sql) throws Exception {
        try (var c = dataSource.getConnection(); var s = c.createStatement(); var r = s.executeQuery(sql)) {
            r.next();
            return r.getString(1);
        }
    }
}
