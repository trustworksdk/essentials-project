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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * The edges of the ordered lane's guarantee, which the README's ordering section describes and which
 * are easier to believe than to check.
 * <p>
 * A key advances through the {@code key_order} values that are present and does not wait for a
 * missing one — so a message can reach its handler after a higher {@code key_order} already has. That
 * is counted, not prevented, and a per-key delay is the cheapest way to produce it on purpose: only
 * visible rows are dispatched, so an undelayed later value overtakes a delayed earlier one.
 * <p>
 * The other half is the producer's side of the contract: {@code key_order} is part of the ordered
 * table's primary key, so reusing one is refused rather than silently overwriting the message already
 * queued under it.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedOrderingBoundaryIT {

    private static final short  QUEUE_ID    = 1;
    private static final int    SHARD_COUNT = 1;
    private static final int    PAYLOAD_TYPE = 1;
    private static final String KEY         = "order-42";

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(40);
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
    void a_delayed_key_order_is_overtaken_by_a_later_one_and_the_violation_is_counted() throws Exception {
        var deliveredOrders = new CopyOnWriteArrayList<Long>();

        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ordering-1")) {
            queue.consume((messageId, key, payload, payloadType) -> deliveredOrders.add(Long.parseLong(body(payload))),
                          ConsumerOptions.defaults());

            // key_order 1 is held back; key_order 2 is eligible at once. The engine dispatches what it
            // has rather than waiting for a value that may never arrive, which is the whole of the
            // "not strict" part of the guarantee.
            queue.enqueue(List.of(Message.delayedOrdered("1".getBytes(StandardCharsets.UTF_8), PAYLOAD_TYPE,
                                                         KEY, 1L, Duration.ofSeconds(3)),
                                  Message.ordered("2".getBytes(StandardCharsets.UTF_8), PAYLOAD_TYPE,
                                                  KEY, 2L)));

            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> assertThat(deliveredOrders).containsExactly(2L, 1L));

            // Counted rather than prevented — the number is what tells an operator their producer
            // numbered and committed in different orders.
            Awaitility.await().atMost(Duration.ofSeconds(10))
                      .untilAsserted(() -> assertThat(queue.statistics().orderViolations()).isPositive());
        }
    }

    @Test
    void reusing_a_key_order_for_a_key_is_refused_rather_than_overwriting() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ordering-2")) {
            queue.enqueue(List.of(Message.ordered("first".getBytes(StandardCharsets.UTF_8), PAYLOAD_TYPE, KEY, 7L)));

            // (queue_id, shard, msg_key, key_order) is the ordered table's primary key. A producer
            // that reuses a value is told, rather than losing the message already queued under it.
            assertThatThrownBy(() -> queue.enqueue(List.of(Message.ordered("second".getBytes(StandardCharsets.UTF_8),
                                                                          PAYLOAD_TYPE, KEY, 7L))))
                    .isInstanceOf(SQLException.class);

            assertThat(queue.depth().ordered()).isEqualTo(1);
        }
    }

    private static String body(byte[] payload) {
        return new String(payload, StandardCharsets.UTF_8);
    }
}
