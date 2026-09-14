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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import dk.trustworks.essentials.components.queue.shardowned.spi.QueueName;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * What a LARGER ordered routing space actually costs, now that the per-unit lease renewal is gone.
 * <p>
 * The space was fixed at 64 on the argument that idle cost is linear in units held. That argument was
 * true of two mechanisms, and both have since been removed: the reads are batched per queue, and a
 * unit-owned lease no longer carries an expiry to renew. So the ceiling — 64 consuming instances per
 * ordered queue — may now be bought off cheaply, and this measures the price rather than arguing it.
 * <p>
 * What is still per-unit, and therefore what this is looking for:
 * <ul>
 *     <li>a lease row and an owner object per unit — static, but they exist;</li>
 *     <li>roughly three buffers per unit inside the one batched read;</li>
 *     <li><b>acquisition</b>, which is one statement per unit at start-up and on rebalance, and is
 *         the one remaining unbatched per-unit write.</li>
 * </ul>
 * Measure-only, so it is gated off by default like the other benchmark suites:
 * {@code -Dbenchmark.run=true}.
 */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(named = "benchmark.run", matches = "true")
class ShardOwnedRoutingSpaceCostIT {

    private static final Logger log = LoggerFactory.getLogger(ShardOwnedRoutingSpaceCostIT.class);

    private static final int SHARD_COUNT = 4;

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void what_a_bigger_routing_space_costs() throws Exception {
        log.warn("=== ordered routing space: cost per unit count, one queue, idle ===");
        log.warn("{:>6} {:>12} {:>14} {:>16} {:>14}", "units", "acquire ms", "idle queries/s",
                 "lease writes/s", "per unit/s");
        for (var units : new int[]{64, 256, 1024}) {
            measure(units);
        }
    }

    private void measure(int units) throws Exception {
        ShardOwnedSchema.recreate(dataSource);
        var queueId = registerWithUnits("space-" + units, units);

        try (var queue = new ShardOwnedQueue(dataSource, queueId, SHARD_COUNT, "space-" + units)) {
            var acquireStart = System.nanoTime();
            queue.startConsumingOrdered((messageId, key, payload, payloadType) -> {
            }, ShardOwnerSettings.defaults(), units);
            Awaitility.await().atMost(Duration.ofSeconds(120))
                      .until(() -> queue.shardsHeld() == units);
            var acquireMillis = (System.nanoTime() - acquireStart) / 1_000_000L;

            // Past the sweep backoff, so the steady state is the one being measured.
            TimeUnit.SECONDS.sleep(35);

            var transactionsBefore = transactions();
            var leaseWritesBefore  = leaseRowUpdates();
            var windowSeconds      = 30L;
            TimeUnit.SECONDS.sleep(windowSeconds);
            var queriesPerSecond    = (transactions() - transactionsBefore) / (double) windowSeconds;
            var leaseWritesPerSecond = (leaseRowUpdates() - leaseWritesBefore) / (double) windowSeconds;

            log.warn(String.format("%6d %12d %14.2f %16.2f %14.4f",
                                   units, acquireMillis, queriesPerSecond, leaseWritesPerSecond,
                                   queriesPerSecond / units));
        }
    }

    /**
     * Register a queue whose ordered space is {@code units} rather than the build's default, the way
     * a queue created by a differently-configured build would look. The lease rows the seeding did
     * not create have to be added, since seeding uses the default.
     */
    private short registerWithUnits(String name, int units) throws Exception {
        // By NAME, not by queue id: the by-id overload creates sequences and lease rows but no
        // registry row, so the UPDATE below would match nothing and every arm would silently measure
        // the fallback of ORDERED_UNITS. The first run did exactly that and only the ownership
        // assertion caught it.
        var registered = ShardOwnedSchema.registerQueue(dataSource, QueueName.of(name), SHARD_COUNT);
        var queueId    = registered.queueId();
        try (var connection = dataSource.getConnection()) {
            try (var statement = connection.prepareStatement(
                    "UPDATE " + ShardOwnedSchema.REGISTRY_TABLE + " SET ordered_units = ? WHERE queue_id = ?")) {
                statement.setInt(1, units);
                statement.setShort(2, queueId);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("the routing space was not rewritten");
                }
            }
            try (var statement = connection.prepareStatement(
                    "INSERT INTO " + ShardOwnedSchema.LEASE_TABLE + " (queue_id, lane, shard, owner, fence)"
                    + " VALUES (?, 'ordered', ?, NULL, 0) ON CONFLICT DO NOTHING")) {
                for (var shard = 0; shard < units; shard++) {
                    statement.setShort(1, queueId);
                    statement.setShort(2, (short) shard);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
        }
        return queueId;
    }

    private long transactions() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT xact_commit + xact_rollback FROM pg_stat_database WHERE datname = current_database()");
             var resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private long leaseRowUpdates() throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT n_tup_upd FROM pg_stat_user_tables WHERE relname = ?")) {
            statement.setString(1, ShardOwnedSchema.LEASE_TABLE);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }
}
