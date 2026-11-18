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

package dk.trustworks.essentials.components.queue.shardowned;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * Makes a crashed instance look long gone without waiting for it to.
 * <p>
 * An instance holding a unit is live while its membership row's {@code last_seen} is within the lease TTL of the instance
 * reading it, and {@code last_seen} is server-side {@code now()}, so a test whose instance "crashed"
 * ({@code stopAbruptly()}) would otherwise wait a whole lease TTL - of the SUCCESSOR's settings, which on defaults is
 * 30 s - before another instance may take its units. Moving the row into the past instead skips the engine's own
 * passage of time; {@code ShardOwnedStaleLivenessIT} keeps a lease lapsing in real time.
 */
final class CrashedInstances {
    private CrashedInstances() {
    }

    /**
     * Move the membership row of {@code instanceId} on {@code queueId} a minute into the past: older than any lease TTL the
     * tests use, younger than the heartbeat's prune age at the default one (10 x 30 s), so the row stays and reads as
     * dead. Fails unless exactly one row was moved, so a test cannot silently age nothing.
     */
    static void ageMembershipOf(DataSource dataSource, short queueId, String instanceId) throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("UPDATE " + ShardOwnedSchema.INSTANCE_TABLE
                                                                 + " SET last_seen = now() - interval '1 minute' WHERE queue_id = ? AND instance_id = ?")) {
            statement.setShort(1, queueId);
            statement.setString(2, instanceId);
            var aged = statement.executeUpdate();
            if (aged != 1) {
                throw new IllegalStateException("Expected to age the membership row of " + instanceId + " on queue " + queueId
                                                + ", aged " + aged + " rows");
            }
        }
    }
}
