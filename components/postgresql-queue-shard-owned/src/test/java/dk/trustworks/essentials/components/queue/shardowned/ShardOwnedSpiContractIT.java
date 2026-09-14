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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the {@link MessageQueue} contract promises, checked against what the implementation does.
 * <p>
 * Every case here is one the implementation previously got wrong in a way no existing test could
 * see, because each is about a promise made in a signature or a javadoc rather than about whether
 * messages arrive — and messages arrived in all of them.
 */
@Testcontainers(disabledWithoutDocker = true)
class ShardOwnedSpiContractIT {

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

    /**
     * {@code Message.payloadType} is per message, so a batch that mixes types must persist each one.
     * The implementation used to read the type off the first message and apply it to the whole
     * batch, which silently mislabelled everything after it.
     */
    @Test
    void a_mixed_type_batch_keeps_each_messages_own_payload_type() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "types-1")) {
            var messages = new ArrayList<Message>();
            for (var i = 0; i < 40; i++) {
                messages.add(Message.of(("u" + i).getBytes(StandardCharsets.UTF_8), 100 + i));
            }
            for (var i = 0; i < 40; i++) {
                messages.add(Message.ordered(("o" + i).getBytes(StandardCharsets.UTF_8), 200 + i, "key-" + i, i));
            }
            queue.enqueue(messages);

            assertThat(distinctPayloadTypes(ShardOwnedSchema.UNORDERED_TABLE))
                    .describedAs("each unordered message keeps the type it was enqueued with")
                    .hasSize(40);
            assertThat(distinctPayloadTypes(ShardOwnedSchema.ORDERED_TABLE))
                    .describedAs("each ordered message keeps the type it was enqueued with")
                    .hasSize(40);
        }
    }

    /**
     * The ids {@code enqueue} returns must address the rows it wrote. An ordered id used to be built
     * from {@code key_order} rather than {@code seq}, so it pointed at nothing.
     */
    @Test
    void the_ids_enqueue_returns_actually_address_the_rows_it_wrote() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "ids-1")) {
            var messages = List.of(Message.of("u".getBytes(StandardCharsets.UTF_8), 1),
                                   Message.ordered("o".getBytes(StandardCharsets.UTF_8), 2, "k", 7_000L),
                                   Message.of("u2".getBytes(StandardCharsets.UTF_8), 3));
            var ids = queue.enqueue(messages);

            assertThat(ids).describedAs("one id per input message, in input order").hasSize(3);
            assertThat(ids.get(0).lane()).isEqualTo(MessageId.Lane.UNORDERED);
            assertThat(ids.get(1).lane()).describedAs("the ordered message keeps its position").isEqualTo(MessageId.Lane.ORDERED);
            assertThat(ids.get(2).lane()).isEqualTo(MessageId.Lane.UNORDERED);

            var orderedId = ids.get(1);
            assertThat(orderedId.sequence())
                    .describedAs("addressed by seq, not by the producer's key_order of 7000")
                    .isNotEqualTo(7_000L);
            assertThat(rowExists(ShardOwnedSchema.ORDERED_TABLE, orderedId.shard(), orderedId.sequence()))
                    .describedAs("the returned ordered id resolves to a real row")
                    .isTrue();
        }
    }

    /**
     * A mixed batch is one unit of work. It used to run the two lanes on two connections, so a
     * failure in the second left the first committed.
     */
    @Test
    void a_mixed_batch_that_fails_persists_nothing() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "atomic-1")) {
            // Poison the ordered lane only, so the unordered half of the batch is written first and
            // then has to be rolled back by the failure in the ordered half.
            blockInsertsInto(ShardOwnedSchema.ORDERED_TABLE);

            var messages = new ArrayList<Message>();
            for (var i = 0; i < 2_000; i++) {
                messages.add(Message.of(("u" + i).getBytes(StandardCharsets.UTF_8), 1));
            }
            messages.add(Message.ordered("o".getBytes(StandardCharsets.UTF_8), 1, "k", 0));

            assertThatEnqueueFails(queue, messages);

            assertThat(countRows(ShardOwnedSchema.UNORDERED_TABLE))
                    .describedAs("the unordered half must not survive a batch the caller was told had failed")
                    .isZero();
        }
    }

    /** {@code Lifecycle} says a fresh resource is not started. It used to report that it was. */
    @Test
    void a_queue_that_has_never_consumed_is_not_started() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "life-1")) {
            assertThat(queue.isStarted()).describedAs("nothing is running yet").isFalse();

            var delivered = ConcurrentHashMap.<String>newKeySet();
            queue.consume((messageId, key, payload, payloadType) -> delivered.add(new String(payload, StandardCharsets.UTF_8)),
                          ConsumerOptions.defaults());
            assertThat(queue.isStarted()).describedAs("consuming started it").isTrue();

            queue.enqueue(Message.of("before".getBytes(StandardCharsets.UTF_8), 1));
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> delivered.contains("before"));

            queue.stop();
            assertThat(queue.isStarted()).isFalse();

            // Restartable, not merely stoppable: the consumers stay registered so start() brings
            // them back. Clearing them made a restarted queue silently consume nothing.
            queue.start();
            assertThat(queue.isStarted()).isTrue();
            queue.enqueue(Message.of("after".getBytes(StandardCharsets.UTF_8), 1));
            Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> delivered.contains("after"));
        }
    }

    /**
     * {@code QueueObserver}'s javadoc calls an observer failure a bug in the observer. It must not
     * also be a failure of the message: a broken observer used to get every message retried and
     * eventually dead-lettered.
     */
    @Test
    void an_observer_that_throws_after_the_handler_succeeded_does_not_fail_the_message() throws Exception {
        var handlerCalls = new AtomicInteger();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "observer-1")) {
            queue.addObserver(new QueueObserver() {
                @Override
                public void aroundDelivery(String key, Runnable delivery) {
                    delivery.run();
                    throw new IllegalStateException("this observer is broken");
                }
            });
            queue.consume((messageId, key, payload, payloadType) -> handlerCalls.incrementAndGet(),
                          new ConsumerOptions(8, Integer.MAX_VALUE, 2, Duration.ofMillis(50), 1.0d, Duration.ofMillis(50)));

            queue.enqueue(Message.of("m".getBytes(StandardCharsets.UTF_8), 1));

            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> handlerCalls.get() >= 1);
            // Long enough for the retry policy to have run twice over and dead-lettered it.
            Thread.sleep(2_000);

            assertThat(handlerCalls.get())
                    .describedAs("the handler succeeded, so the message must not be redelivered")
                    .isEqualTo(1);
            assertThat(queue.depth().deadLettered())
                    .describedAs("a broken observer must not be able to dead-letter a handled message")
                    .isZero();
            assertThat(queue.depth().unordered())
                    .describedAs("the message is acknowledged and gone")
                    .isZero();
        }
    }

    private void assertThatEnqueueFails(MessageQueue queue, List<Message> messages) {
        try {
            queue.enqueue(messages);
            throw new AssertionError("enqueue was expected to fail, and the injection must be proven to work "
                                     + "before anything is asserted about behaviour under it");
        } catch (Exception expected) {
            // The injection fired, which is the precondition for the assertion that follows.
        }
    }

    private void blockInsertsInto(String table) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                              CREATE OR REPLACE FUNCTION shard_queue_reject_insert() RETURNS trigger AS $$
                              BEGIN RAISE EXCEPTION 'insert blocked by test'; END;
                              $$ LANGUAGE plpgsql
                              """);
            statement.execute("CREATE TRIGGER shard_queue_block_insert BEFORE INSERT ON " + table
                              + " FOR EACH ROW EXECUTE FUNCTION shard_queue_reject_insert()");
        }
    }

    private Set<Integer> distinctPayloadTypes(String table) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT DISTINCT payload_type FROM " + table + " WHERE queue_id = ?")) {
            statement.setShort(1, QUEUE_ID);
            try (var resultSet = statement.executeQuery()) {
                var types = new HashSet<Integer>();
                while (resultSet.next()) {
                    types.add(resultSet.getInt(1));
                }
                return types;
            }
        }
    }

    private boolean rowExists(String table, int shard, long seq) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT 1 FROM " + table + " WHERE queue_id = ? AND shard = ? AND seq = ?")) {
            statement.setShort(1, QUEUE_ID);
            statement.setShort(2, (short) shard);
            statement.setLong(3, seq);
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private long countRows(String table) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM " + table + " WHERE queue_id = ?")) {
            statement.setShort(1, QUEUE_ID);
            try (var resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    /**
     * A handler must receive the {@code payloadType} its message was enqueued with.
     * <p>
     * The column was written on every row, carried through dead-lettering and returned on the pull
     * and dead-letter paths — but {@code MessageHandler} did not take it, so the push path, which is
     * how almost everything consumes, never saw it. A consumer had to recover the type from inside
     * the payload, which is precisely what storing it separately was supposed to avoid.
     */
    @Test
    void a_handler_receives_the_payload_type_its_message_was_enqueued_with() throws Exception {
        var seen = new ConcurrentHashMap<String, Integer>();
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "types-2")) {
            queue.consume((messageId, key, payload, payloadType) ->
                                  seen.put(new String(payload, StandardCharsets.UTF_8), payloadType),
                          ConsumerOptions.defaults());

            var messages = new ArrayList<Message>();
            for (var i = 0; i < 12; i++) {
                messages.add(Message.of(("u" + i).getBytes(StandardCharsets.UTF_8), 500 + i));
            }
            for (var i = 0; i < 12; i++) {
                messages.add(Message.ordered(("o" + i).getBytes(StandardCharsets.UTF_8), 900 + i, "k" + i, 0L));
            }
            queue.enqueue(messages);

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(seen).hasSize(24));

            for (var i = 0; i < 12; i++) {
                assertThat(seen.get("u" + i))
                        .describedAs("the unordered lane must deliver each message's own type")
                        .isEqualTo(500 + i);
                assertThat(seen.get("o" + i))
                        .describedAs("the ordered lane must too")
                        .isEqualTo(900 + i);
            }
        }
    }

    /**
     * The type has to survive the paths that copy a row between tables, not only the delivery one.
     */
    @Test
    void the_payload_type_survives_dead_lettering_and_resurrection() throws Exception {
        try (var queue = new PostgresqlMessageQueue(dataSource, QUEUE_ID, SHARD_COUNT, "types-3")) {
            var subscription = queue.consume((messageId, key, payload, payloadType) -> {
                                                 throw new IllegalStateException("always fails");
                                             },
                                             new ConsumerOptions(8, Integer.MAX_VALUE, 1, Duration.ofMillis(20),
                                                                 1.0d, Duration.ofMillis(20)));
            queue.enqueue(Message.of("poison".getBytes(StandardCharsets.UTF_8), 4_242));

            Awaitility.await().atMost(Duration.ofSeconds(30))
                      .untilAsserted(() -> assertThat(queue.deadLetters(0, 10)).hasSize(1));
            var deadLetter = queue.deadLetters(0, 10).getFirst();
            assertThat(deadLetter.payloadType())
                    .describedAs("a dead letter keeps the type, so a human can tell what it was")
                    .isEqualTo(4_242);

            // Stop consuming BEFORE resurrecting. The handler fails everything it is given, so a
            // live consumer would re-kill the resurrected message and dead-letter it again — and
            // the lane would be empty for reasons that have nothing to do with the payload type.
            subscription.stop();
            assertThat(queue.resurrect(deadLetter.id())).isTrue();
            assertThat(distinctPayloadTypes(ShardOwnedSchema.UNORDERED_TABLE))
                    .describedAs("resurrection must not lose it either")
                    .contains(4_242);
        }
    }
}
