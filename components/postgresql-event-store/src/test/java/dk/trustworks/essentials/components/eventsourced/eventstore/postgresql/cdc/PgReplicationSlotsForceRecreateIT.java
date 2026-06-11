/*
 *  Copyright 2021-2026 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.replication.PGReplicationStream;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the force-recreate race: {@code pg_terminate_backend} only *signals* the walsender,
 * so a naive drop immediately after can fail with "replication slot ... is active for PID". This
 * exercises the real scenario — a slot held open by a live logical replication stream — and asserts
 * {@code forceRecreateSlot} terminates the backend, waits for it to release the slot, drops, and
 * recreates it without throwing.
 */
@Testcontainers
public class PgReplicationSlotsForceRecreateIT extends AbstractLogicalReplicationPostgresIT {

    @Test
    void forceRecreateSlot_drops_and_recreates_a_slot_held_by_a_live_replication_stream() throws Exception {
        var slotName = "force_recreate_" + UUID.randomUUID().toString().replace("-", "");

        // Create a wal2json logical slot.
        jdbi.useHandle(h -> h.execute("select * from pg_create_logical_replication_slot(?, 'wal2json')", slotName));

        // Open a logical replication stream to make the slot ACTIVE (active_pid set).
        try (var streamConn = replicationDataSource.getConnection()) {
            var pgConn = streamConn.unwrap(PGConnection.class);
            PGReplicationStream stream = pgConn.getReplicationAPI()
                                               .replicationStream()
                                               .logical()
                                               .withSlotName(slotName)
                                               .start();
            try {
                // Confirm the slot really is active before we try to force-recreate it.
                try (Connection probe = jdbi.open().getConnection()) {
                    var before = PgReplicationSlots.findSlot(probe, slotName);
                    assertThat(before).isNotNull();
                    assertThat(before.isActive()).as("slot is held by the live stream").isTrue();

                    // Force-recreate: must terminate the stream's backend, wait for release, drop, recreate.
                    boolean dropped = PgReplicationSlots.forceRecreateSlot(probe, slotName, "wal2json");
                    assertThat(dropped).isTrue();

                    // The slot exists again, is no longer active, and is a fresh wal2json logical slot.
                    var after = PgReplicationSlots.findSlot(probe, slotName);
                    assertThat(after).isNotNull();
                    assertThat(after.isActive()).isFalse();
                    assertThat(after.isLogical()).isTrue();
                    assertThat(after.plugin).isEqualTo("wal2json");
                }
            } finally {
                try {
                    stream.close();
                } catch (Exception ignore) {
                    // The backend was terminated by forceRecreateSlot; closing may throw — irrelevant here.
                }
            }
        } finally {
            jdbi.useHandle(h -> h.execute("select pg_drop_replication_slot(?) " +
                                                  "from pg_replication_slots where slot_name = ?", slotName, slotName));
        }
    }
}
