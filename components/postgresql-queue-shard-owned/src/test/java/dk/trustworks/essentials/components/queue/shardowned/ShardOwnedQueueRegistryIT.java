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
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/**
 * The queue registry: a name is interned to a {@code smallint} once, durably, and carries the shard
 * count with it.
 * <p>
 * <b>The shard count is the reason this is a safety feature and not an ergonomic one.</b> Shards are
 * the unit of ordering — a key's shard is {@code hash(key) mod shardCount} — so two processes that
 * disagree about the count route the same key to different shards, and shards nobody owns receive
 * messages nobody delivers. Nothing tied a count to a queue before: it was a constructor argument
 * each process supplied for itself, and a disagreement was silent.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedQueueRegistryIT {

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
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * The hazard, constructed rather than hoped for: the queue is registered with eight shards, a
     * consumer is built believing there are four, and the messages routed to the other four are
     * never delivered by anyone. This is what the registry exists to make impossible, and it is
     * asserted here so that the protection below is measured against a real failure.
     */
    @Test
    void a_shard_count_disagreement_strands_messages_when_nothing_enforces_it() throws Exception {
        short queueId = 1;
        ShardOwnedSchema.registerQueue(dataSource, queueId, 8);

        var delivered = ConcurrentHashMap.<String>newKeySet();
        var consumer = new ShardOwnedQueue(dataSource, queueId, 4, "believes-four");
        var producer = new ShardOwnedQueue(dataSource, queueId, 8, "believes-eight");
        try {
            consumer.startConsuming((messageId, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                                    ShardOwnerSettings.defaults(), 4);
            producer.setLocalHandoffEnabled(false);
            for (var i = 0; i < 80; i++) {
                producer.enqueue(List.of(("m" + i).getBytes(StandardCharsets.UTF_8)), 1);
            }
            Thread.sleep(4_000);

            assertThat(delivered)
                    .describedAs("everything routed to shards 4-7 has no owner, so this cannot reach 80")
                    .hasSizeLessThan(80);
            assertThat(rowsInShardsAtOrAbove(queueId, 4))
                    .describedAs("undelivered messages left sitting in the shards nobody leased")
                    .isPositive();
        } finally {
            consumer.stop();
            producer.stop();
        }
    }

    @Test
    void a_name_is_interned_once_and_resolves_to_the_same_queue_everywhere() throws Exception {
        var first = ShardOwnedSchema.registerQueue(dataSource, QueueName.of("orders"), 8);
        var again = ShardOwnedSchema.registerQueue(dataSource, QueueName.of("orders"), 8);

        assertThat(again).describedAs("registering is idempotent").isEqualTo(first);
        assertThat(ShardOwnedSchema.resolve(dataSource, QueueName.of("orders")))
                .describedAs("a second process resolves the same id and shard count")
                .contains(first);

        var other = ShardOwnedSchema.registerQueue(dataSource, QueueName.of("shipments"), 4);
        assertThat(other.queueId()).describedAs("different names intern to different ids")
                                   .isNotEqualTo(first.queueId());
        assertThat(other.shardCount()).isEqualTo(4);

        assertThat(ShardOwnedSchema.queueNames(dataSource))
                .describedAs("the registry is what an admin surface would list")
                .containsExactlyInAnyOrder(QueueName.of("orders"), QueueName.of("shipments"));
    }

    /** The protection. The same disagreement as the first test, refused at the point it is made. */
    @Test
    void registering_a_name_with_a_different_shard_count_is_refused() throws Exception {
        ShardOwnedSchema.registerQueue(dataSource, QueueName.of("orders"), 8);

        assertThatThrownBy(() -> ShardOwnedSchema.registerQueue(dataSource, QueueName.of("orders"), 4))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orders")
                .hasMessageContaining("8")
                .hasMessageContaining("4");
    }

    /**
     * Building from a name takes both the id and the shard count from the registry, so a caller has
     * nowhere to supply a count that could disagree.
     */
    @Test
    void a_queue_built_from_a_name_cannot_disagree_about_its_shard_count() throws Exception {
        var registered = ShardOwnedSchema.registerQueue(dataSource, QueueName.of("orders"), 8);

        var delivered = ConcurrentHashMap.<String>newKeySet();
        try (var consumer = PostgresqlMessageQueue.builder()
                                                  .setDataSource(dataSource)
                                                  .setQueueName(QueueName.of("orders"))
                                                  .setInstanceId("by-name")
                                                  .build()) {
            assertThat(consumer.queueId()).isEqualTo(registered.queueId());
            assertThat(consumer.shardCount()).isEqualTo(8);

            consumer.consume((messageId, key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                             ConsumerOptions.defaults());
            var messages = new ArrayList<Message>();
            for (var i = 0; i < 80; i++) {
                messages.add(Message.of(("m" + i).getBytes(StandardCharsets.UTF_8), 1));
            }
            consumer.enqueue(messages);

            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30))
                                     .untilAsserted(() -> assertThat(delivered)
                                             .describedAs("every shard has an owner, so everything arrives")
                                             .hasSize(80));
        }
    }

    @Test
    void an_unregistered_name_is_refused_rather_than_silently_creating_a_queue() {
        assertThatThrownBy(() -> PostgresqlMessageQueue.builder()
                                                       .setDataSource(dataSource)
                                                       .setQueueName(QueueName.of("never-registered"))
                                                       .setInstanceId("x")
                                                       .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("never-registered");
    }

    @Test
    void a_queue_name_rejects_what_it_cannot_represent() {
        assertThatThrownBy(() -> QueueName.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueName.of("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueueName.of("x".repeat(256))).isInstanceOf(IllegalArgumentException.class);
        assertThat(QueueName.of("orders")).isEqualTo(QueueName.of("orders"));
        assertThat(QueueName.of("orders")).hasToString("orders");
    }

    private long rowsInShardsAtOrAbove(short queueId, int shard) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + ShardOwnedSchema.UNORDERED_TABLE
                     + " WHERE queue_id = ? AND shard >= ?")) {
            statement.setShort(1, queueId);
            statement.setInt(2, shard);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }
}
