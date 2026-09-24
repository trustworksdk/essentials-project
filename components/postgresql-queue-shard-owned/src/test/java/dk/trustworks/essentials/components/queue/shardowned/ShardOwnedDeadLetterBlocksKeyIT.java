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
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A key never advances past a dead letter — {@code docs/durable-queue-shard-owned.md} §9.1.
 * <p>
 * The engine used to release the key when a message exhausted its attempts, so 6, 7 and 8 were
 * delivered after 5 could not be. For a work queue that is the right trade; for a key carrying state
 * transitions it is corruption, and it is the one ordering difference from
 * {@code PostgresqlDurableQueues}, whose fetch barrier has always counted a dead-lettered row as
 * blocking.
 * <p>
 * The block is derived from the dead-letter table rather than remembered, so it has to survive the
 * owner being replaced; that is what the second test is for. The third covers the way out — the
 * messages behind the block are themselves dead-lettered, so recovery is per key and ascending, and
 * resurrecting out of order re-stalls at each step by design.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedDeadLetterBlocksKeyIT {

    private static final short  QUEUE_ID     = 1;
    private static final int    SHARD_COUNT  = 1;
    private static final int    PAYLOAD_TYPE = 1;
    private static final String KEY          = "account-7";

    @Container
    static PostgreSQLContainer<?> postgres = LabPostgres.create();

    private HikariDataSource dataSource;

    /** Messages handed to a handler, in the order they were handed over. */
    private final List<Long> delivered = new CopyOnWriteArrayList<>();
    /** Flipped once the "broken handler" has been fixed, so a resurrected message can succeed. */
    private final AtomicBoolean orderTwoStillFails = new AtomicBoolean(true);

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
        delivered.clear();
        orderTwoStillFails.set(true);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void a_key_stops_at_its_dead_letter_and_everything_behind_it_is_dead_lettered_unhandled() throws Exception {
        try (var queue = queue("blocked-1")) {
            consume(queue);
            enqueue(queue, 1, 2, 3, 4, 5);

            // 2 fails its way to the dead-letter table; 3, 4 and 5 follow it there without ever being
            // handed to a handler.
            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> assertThat(queue.depth().deadLettered()).isEqualTo(4L));

            assertThat(delivered).as("only the message in front of the failure may be delivered")
                                 .containsExactly(1L);

            var parked = byOrder(queue);
            assertThat(parked.get(2L).neverDelivered()).as("2 was tried and failed").isFalse();
            assertThat(parked.get(2L).blockedByKeyOrder()).isNull();
            assertThat(parked.get(2L).lastError()).contains("cannot apply");
            for (var order : List.of(3L, 4L, 5L)) {
                assertThat(parked.get(order).neverDelivered())
                        .as("%d was never handed to a handler", order).isTrue();
                // Names the message that actually has to be dealt with — 2, not the one directly in
                // front — so an operator reading any row knows where recovery starts.
                assertThat(parked.get(order).blockedByKeyOrder()).as("%d", order).isEqualTo(2L);
            }

            var statistics = queue.statistics();
            assertThat(statistics.keysBlockedByDeadLetter()).isPositive();
            assertThat(statistics.messagesPoisonedBehindDeadLetter()).isEqualTo(3L);
            assertThat(statistics.deadLettered()).as("only the message that was tried counts here").isEqualTo(1L);

            // And it stays that way — the ordered lane is empty, so nothing can be redelivered from it.
            Thread.sleep(1_500L);
            assertThat(delivered).containsExactly(1L);
            assertThat(queue.depth().ordered()).isZero();
        }
    }

    @Test
    void the_block_outlives_the_owner_that_recorded_it() throws Exception {
        try (var queue = queue("blocked-2")) {
            consume(queue);
            enqueue(queue, 1, 2);
            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> assertThat(queue.depth().deadLettered()).isEqualTo(1L));
        }

        // A different queue instance, with nothing in memory from the first. If the block lived only
        // in the owner that recorded it, this is where the key would silently resume.
        try (var queue = queue("blocked-3")) {
            consume(queue);
            enqueue(queue, 6);

            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> assertThat(queue.depth().deadLettered()).isEqualTo(2L));
            assertThat(byOrder(queue).get(6L).neverDelivered()).isTrue();
            assertThat(delivered).as("nothing past the dead letter").containsExactly(1L);
        }
    }

    @Test
    void resurrecting_the_dead_letters_in_ascending_order_releases_the_key() throws Exception {
        try (var queue = queue("blocked-4")) {
            consume(queue);
            enqueue(queue, 1, 2, 3);
            // Two, not three: 1 is delivered, 2 fails its way there and 3 follows it unhandled.
            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> assertThat(queue.depth().deadLettered()).isEqualTo(2L));

            // The operator fixes the handler and puts the messages back, lowest key_order first.
            orderTwoStillFails.set(false);
            for (var order : List.of(2L, 3L)) {
                var parked = byOrder(queue).get(order);
                assertThat(queue.resurrect(parked.id())).isTrue();
                // Awaited one at a time on purpose: the block lifts to the next dead-lettered value,
                // so a message resurrected ahead of a lower one is simply dead-lettered again.
                Awaitility.await().atMost(Duration.ofSeconds(45))
                          .untilAsserted(() -> assertThat(delivered).contains(order));
            }

            assertThat(delivered).containsExactly(1L, 2L, 3L);
            Awaitility.await().atMost(Duration.ofSeconds(20))
                      .untilAsserted(() -> assertThat(queue.depth().deadLettered()).isZero());
        }
    }

    @Test
    void resurrecting_the_whole_key_at_once_replays_it_in_key_order() throws Exception {
        try (var queue = queue("blocked-5")) {
            consume(queue);
            enqueue(queue, 1, 2, 3, 4, 5);
            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> assertThat(queue.depth().deadLettered()).isEqualTo(4L));
            assertThat(delivered).containsExactly(1L);

            orderTwoStillFails.set(false);
            // One call, and no ordering discipline required of the caller: the rows become visible
            // together, so the owner holds all four before it dispatches the first.
            assertThat(queue.resurrectKey(KEY)).isEqualTo(4);

            Awaitility.await().atMost(Duration.ofSeconds(45))
                      .untilAsserted(() -> assertThat(delivered).hasSize(5));
            assertThat(delivered).as("the key resumes where it stopped, in key_order")
                                 .containsExactly(1L, 2L, 3L, 4L, 5L);
            assertThat(queue.depth().deadLettered()).isZero();
        }
    }

    @Test
    void resurrecting_a_key_that_has_no_dead_letters_does_nothing() throws Exception {
        try (var queue = queue("blocked-6")) {
            assertThat(queue.resurrectKey("never-used")).isZero();
        }
    }

    private PostgresqlMessageQueue queue(String instanceId) {
        return new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, instanceId);
    }

    private void consume(MessageQueue queue) throws Exception {
        queue.consume((messageId, key, payload, payloadType) -> {
            var order = Long.parseLong(new String(payload, StandardCharsets.UTF_8));
            if (order == 2L && orderTwoStillFails.get()) {
                throw new IllegalStateException("cannot apply " + order);
            }
            delivered.add(order);
        }, new ConsumerOptions(4, SHARD_COUNT, 2, Duration.ofMillis(20), 1.0d, Duration.ofMillis(20)));
    }

    private void enqueue(MessageQueue queue, long... orders) throws Exception {
        var batch = new ArrayList<Message>();
        for (var order : orders) {
            batch.add(Message.ordered(Long.toString(order).getBytes(StandardCharsets.UTF_8),
                                      PAYLOAD_TYPE, KEY, order));
        }
        queue.enqueue(batch);
    }

    private Map<Long, DeadLetter> byOrder(MessageQueue queue) throws Exception {
        var byOrder = new HashMap<Long, DeadLetter>();
        for (var deadLetter : queue.deadLetters(0, 100)) {
            byOrder.put(Long.parseLong(new String(deadLetter.payload(), StandardCharsets.UTF_8)), deadLetter);
        }
        return byOrder;
    }
}
