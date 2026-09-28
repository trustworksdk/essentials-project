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

import com.zaxxer.hikari.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Losing the database connection, without losing the process.
 * <p>
 * A connection blip, a database failover, a connection reaper, a pooler restart — all routine, none
 * of which kills the application. The engine has to survive them, because a queue that stops
 * delivering after a transient network event and never resumes is worse than one that crashes: a
 * crash gets noticed and restarted, a silent stall does not.
 * <p>
 * {@code pg_terminate_backend} produces exactly this from the database side: the connections go, the
 * process stays, and it still believes it owns its shards.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedConnectionLossIT {

    private static final short QUEUE_ID    = 1;
    private static final int   SHARD_COUNT = 4;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(30);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void the_engine_recovers_when_its_connections_are_terminated_underneath_it() throws Exception {
        var received = new CopyOnWriteArrayList<String>();
        try (var queue = new ShardOwnedQueue(dataSource, QUEUE_ID, SHARD_COUNT, "instance-1")) {
            queue.startConsuming((messageId, payload, payloadType) -> received.add(new String(payload, StandardCharsets.UTF_8)),
                                 ShardOwnerSettings.defaults(), SHARD_COUNT);

            for (var index = 0; index < 50; index++) {
                queue.enqueue(List.of(("before-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }
            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(received).hasSize(50));

            // Cut every connection the engine holds. Its owner threads are mid-loop on those
            // connections; the process itself is untouched and still holds its leases.
            var terminated = terminateOtherBackends();
            assertThat(terminated).as("the engine must actually have had connections to lose").isPositive();

            // The pool reconnects on demand, so new work should flow again. If an owner thread died
            // on the SQLException instead of reconnecting, its shard is now held but unserved — and
            // the heartbeat, on a different connection, keeps renewing the lease so nothing else can
            // take it either.
            for (var index = 0; index < 50; index++) {
                queue.enqueue(List.of(("after-" + index).getBytes(StandardCharsets.UTF_8)), 1);
            }

            Awaitility.await().atMost(Duration.ofSeconds(60))
                      .untilAsserted(() -> assertThat(received.stream().filter(m -> m.startsWith("after-")).distinct().count())
                              .as("every shard must resume delivering after its connection was cut")
                              .isEqualTo(50L));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.remaining()).isZero());
        }
    }

    /**
     * Terminate every backend except this test's own connection.
     */
    private int terminateOtherBackends() throws SQLException {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity"
                     + " WHERE datname = current_database() AND pid <> pg_backend_pid()")) {
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
