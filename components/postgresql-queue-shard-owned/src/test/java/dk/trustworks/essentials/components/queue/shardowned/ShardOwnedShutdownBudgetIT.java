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
import dk.trustworks.essentials.components.queue.shardowned.spi.ConsumerOptions;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stopping must finish even when the database has gone, and finishing is the whole assertion.
 *
 * <h2>The hazard</h2>
 * Everything {@code stop()} does against the database is a courtesy: a lease that is not released
 * expires, and a membership row that is not deleted ages out. Giving up therefore costs a successor
 * its lease TTL and nothing else, which the code has always said. What it did not do was give up.
 * <p>
 * Leases were released one per connection, so an instance holding the ordered lane's 64 units asked
 * the pool for 64 connections. Against a database that answers, that is merely wasteful. Against one
 * that does not, every request waits out the pool's {@code connectionTimeout} before failing — 30s at
 * Hikari's default — so handing back one lane of one queue took half an hour, and an application
 * consuming several queues never finished at all. The report was "Ctrl-C does nothing".
 *
 * <h2>Why the partition rather than stopping the container</h2>
 * A stopped container REFUSES connections, and a refusal is an answer: it comes back in microseconds,
 * so the per-unit cost is invisible and the old code passes. The pool's timeout is only paid when
 * connecting hangs, which is what a partition does and what the pool's timeout exists for. Same
 * distinction {@code ShardOwnedNetworkPartitionIT} draws between a reset and a partition, one level
 * down.
 * <p>
 * {@code connectionTimeout} is 2s here rather than the default 30s purely to keep the test short. It
 * is the multiplier that is being asserted, not the constant: at 64 units the old path needed 64 x 2s
 * on the ordered lane alone, so the budget below separates the two by more than an order of magnitude
 * at any timeout.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedShutdownBudgetIT {

    private static final short    QUEUE_ID          = 1;
    private static final int      SHARD_COUNT       = 4;
    private static final int      HELD_UNITS        = SHARD_COUNT + ShardOwnedSchema.ORDERED_UNITS;
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Two lanes, each allowed its own budget, plus the pumps noticing they cannot reconnect. Well
     * under the {@code ORDERED_UNITS x CONNECTION_TIMEOUT} the per-unit release cost, and generous
     * enough that a loaded CI machine does not decide it.
     */
    private static final Duration ALLOWED_SHUTDOWN = Duration.ofSeconds(25);

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private PartitionableProxy proxy;
    private HikariDataSource   partitionable;
    private HikariDataSource   direct;

    @BeforeEach
    void setUp() throws Exception {
        proxy  = new PartitionableProxy(postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
        direct = pool(postgres.getJdbcUrl());
        partitionable = pool("jdbc:postgresql://127.0.0.1:" + proxy.localPort() + "/" + postgres.getDatabaseName()
                             + "?socketTimeout=3&connectTimeout=3&loginTimeout=3");
        ShardOwnedSchema.recreate(direct);
        ShardOwnedSchema.registerQueue(direct, QUEUE_ID, SHARD_COUNT);
    }

    @AfterEach
    void tearDown() {
        if (partitionable != null) {
            partitionable.close();
        }
        if (direct != null) {
            direct.close();
        }
        if (proxy != null) {
            proxy.close();
        }
    }

    @Test
    void stopping_gives_up_on_an_unreachable_database_instead_of_waiting_per_unit() throws Exception {
        var queue = PostgresqlMessageQueue.builder()
                                          .setDataSource(partitionable)
                                          .setQueueId(QUEUE_ID)
                                          .setShardCount(SHARD_COUNT)
                                          .setInstanceId("shutdown-budget")
                                          .build();
        var subscription = queue.consume((messageId, key, payload, payloadType) -> {
        }, ConsumerOptions.defaults());

        // It has to hold something for the release path to have work to skip. Asserting the full
        // count matters: the ordered lane's 64 units are where the per-unit cost was paid, and a test
        // that stopped at the 4 unordered shards would separate the two implementations by 8 seconds
        // instead of two minutes.
        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(subscription.shardsHeld())
                          .as("both lanes must be owned before the database is taken away")
                          .isEqualTo(HELD_UNITS));

        proxy.partition();

        var startedAt = System.nanoTime();
        queue.close();
        var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed)
                .describedAs("stop() must abandon its courtesies rather than pay the pool's timeout "
                             + "once per owned unit — %d units x %s is what the per-unit release cost",
                             HELD_UNITS, CONNECTION_TIMEOUT)
                .isLessThan(ALLOWED_SHUTDOWN);
    }

    private static HikariDataSource pool(String jdbcUrl) {
        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(10);
        // Or a partitioned pool blocks the test rather than the engine.
        config.setConnectionTimeout(CONNECTION_TIMEOUT.toMillis());
        config.setValidationTimeout(1_000);
        return new HikariDataSource(config);
    }
}
