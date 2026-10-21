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
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * {@code deadLettered} counts every move to the dead-letter table, also when the consumer's {@link QueueObserver} throws
 * on being told of it. The move has committed by the time the observer is told, and no later call moves the row again,
 * so a count made after the observer is lost for good when it throws. Both owners counted after the observer on the
 * takeover exhaustion pass and on a handler failure that exhausts the policy; the release charge already counted first.
 * Driven on an owner built by hand, its handler run inline, so the observer's exception reaches the test.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedDeadLetterCountIT {

    private static final short  QUEUE_ID     = 1;
    private static final int    SHARD_COUNT  = 4;
    private static final int    SHARD        = 0;
    private static final int    MAX_ATTEMPTS = 3;
    private static final String OWNER        = "instance-1";

    @Container
    static PostgreSQLContainer postgres = LabPostgres.create();

    private HikariDataSource  dataSource;
    private ShardOwnedStorage storage;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(config);
        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, QUEUE_ID, SHARD_COUNT);
        storage = new ShardOwnedStorage(dataSource, QUEUE_ID);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * The takeover's charge brings the message to the policy's limit and the exhaustion pass parks it. The pump calls
     * {@code onTakeover} again after it threw, which finds nothing left to park.
     */
    @ParameterizedTest(name = "{0} lane")
    @ValueSource(strings = {"unordered", "ordered"})
    void a_message_parked_by_the_takeover_exhaustion_pass_is_counted_when_the_observer_throws(String lane) throws Exception {
        enqueueWithAttempts(lane, MAX_ATTEMPTS - 1);
        var grant   = storage.acquireLeaseGrant(lane, SHARD, OWNER, 60_000L).orElseThrow();
        var metrics = new ShardOwnerMetrics(throwingObserver());
        var owner   = owner(lane, grant.fence(), false, metrics, () -> { });

        try (var connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> owner.onTakeover(connection))
                    .as("premise: the observer was told, and threw")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("observer failure");
        }
        assertThat(storage.countDeadLetters()).as("premise: the move committed").isEqualTo(1L);
        assertThat(metrics.deadLettered.sum()).as("counted before the observer was told").isEqualTo(1L);

        // The pump calls onTakeover again: the row is gone, so nothing is parked or counted a second time
        try (var connection = dataSource.getConnection()) {
            owner.onTakeover(connection);
        }
        assertThat(metrics.deadLettered.sum()).isEqualTo(1L);
        assertThat(storage.countDeadLetters()).isEqualTo(1L);
    }

    /**
     * The handler's failure is the message's last attempt. A previous owner that released the unit means no takeover
     * charge, so the failure is what brings the message to the limit.
     */
    @ParameterizedTest(name = "{0} lane")
    @ValueSource(strings = {"unordered", "ordered"})
    void a_message_whose_handler_failure_exhausts_the_policy_is_counted_when_the_observer_throws(String lane) throws Exception {
        enqueueWithAttempts(lane, MAX_ATTEMPTS - 1);
        var grant    = storage.acquireLeaseGrant(lane, SHARD, OWNER, 60_000L).orElseThrow();
        var metrics  = new ShardOwnerMetrics(throwingObserver());
        var handled  = new AtomicInteger();
        var owner    = owner(lane, grant.fence(), true, metrics, () -> {
            handled.incrementAndGet();
            throw new IllegalStateException("handler failure");
        });
        var observed = new AtomicInteger();

        try (var connection = dataSource.getConnection()) {
            owner.onTakeover(connection);
            // Handled inline, on this thread: the observer's exception leaves pumpOnce
            Awaitility.await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(10))
                      .until(() -> {
                          try {
                              owner.pumpOnce(connection);
                          } catch (IllegalStateException e) {
                              if ("observer failure".equals(e.getMessage())) {
                                  observed.incrementAndGet();
                              } else {
                                  throw e;
                              }
                          }
                          return handled.get() == 1;
                      });
        }
        assertThat(observed.get()).as("premise: the observer was told, and threw").isEqualTo(1);
        assertThat(storage.countDeadLetters()).as("premise: the move committed").isEqualTo(1L);
        assertThat(metrics.deadLettered.sum()).as("counted before the observer was told").isEqualTo(1L);
    }

    private static QueueObserver throwingObserver() {
        return new QueueObserver() {
            @Override
            public void deadLettered(MessageId id, int attempts, Throwable cause) {
                throw new IllegalStateException("observer failure");
            }
        };
    }

    private LeasedOwner owner(String lane, long fence, boolean previousOwnerReleased, ShardOwnerMetrics metrics, Runnable handler) {
        var policy = RedeliveryPolicy.fixed(Duration.ofSeconds(1), MAX_ATTEMPTS);
        if ("ordered".equals(lane)) {
            return new OrderedShardOwner(storage, SHARD, fence, ShardOwnerSettings.defaults(), (id, key, payload, type) -> handler.run(),
                                         metrics, policy, OWNER, new ShardWakeup(), HandlerDispatch.inline(), previousOwnerReleased);
        }
        return new ShardOwner(storage, SHARD, fence, ShardOwnerSettings.defaults(), (id, payload, type) -> handler.run(), metrics, policy,
                              new ShardWakeup(), OWNER, HandlerDispatch.inline(), previousOwnerReleased);
    }

    /**
     * One message on {@link #SHARD}, stored as having had {@code attempts} attempts already.
     */
    private void enqueueWithAttempts(String lane, int attempts) throws SQLException {
        var payload = "a".getBytes(StandardCharsets.UTF_8);
        var table   = "ordered".equals(lane) ? ShardOwnedSchema.ORDERED_TABLE : ShardOwnedSchema.UNORDERED_TABLE;
        try (var connection = dataSource.getConnection()) {
            if ("ordered".equals(lane)) {
                storage.enqueueOrderedBatch(connection, SHARD, List.of(new ShardOwnedStorage.OrderedPayload("key-1", 1L, payload, 1)));
            } else {
                storage.enqueueBatch(connection, SHARD, List.of(payload), 1);
            }
            try (var statement = connection.prepareStatement("UPDATE " + table + " SET attempts = ? WHERE queue_id = ? AND shard = ?")) {
                statement.setInt(1, attempts);
                statement.setShort(2, QUEUE_ID);
                statement.setInt(3, SHARD);
                assertThat(statement.executeUpdate()).as("premise: one message enqueued").isOne();
            }
        }
    }
}
