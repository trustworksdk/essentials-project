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

package dk.trustworks.essentials.components.queue.shardowned.adapter;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ShardOwnedDurableQueues#hasOrderedMessageQueuedForKey} and {@link ShardOwnedDurableQueues#getKeysWithOrderedMessagesQueued}
 * asked from inside a {@code UnitOfWork} must answer on that unit of work's connection.
 * <p>
 * A {@code ViewEventProcessor} asks from inside the unit of work it handles its events in. The engine used to answer on
 * a connection of its own, so every asking thread held one connection idle in its transaction while it waited for a
 * second - in the trading demo several connections of a 40-connection pool at a time, at every rush. These tests make
 * as many threads as the pool has connections hold one each and then ask: answered on a second connection, nobody can
 * get one and every thread times out; answered on the unit of work's, all of them answer.
 */
@Testcontainers(disabledWithoutDocker = true)
class OrderedKeyCheckOnShardOwnedIT {

    private static final QueueName QUEUE     = QueueName.of("projection");
    private static final int       POOL_SIZE = 4;

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5-bookworm");

    private HikariDataSource        dataSource;
    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private CachingMessageQueues    queues;
    private ShardOwnedDurableQueues durableQueues;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(POOL_SIZE);
        // Short, so a thread that waits for a second connection fails the test in seconds rather than hanging it
        config.setConnectionTimeout(3_000);
        dataSource = new HikariDataSource(config);
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));

        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, engineName(QUEUE), 2);

        queues = new CachingMessageQueues(dataSource);
        durableQueues = ShardOwnedDurableQueues.builder()
                                               .setQueues(queues)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setDataSource(dataSource)
                                               .build();
        durableQueues.start();
        // Resolve the queue and its routing space up front, outside any unit of work, as a running application has
        durableQueues.hasOrderedMessageQueuedForKey(QUEUE, "warm-up");
        durableQueues.getKeysWithOrderedMessagesQueued(QUEUE, List.of("warm-up"));
    }

    @AfterEach
    void tearDown() {
        if (durableQueues != null) {
            durableQueues.stop();
        }
        if (queues != null) {
            queues.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @Test
    void every_connection_held_by_a_unit_of_work_asking_for_one_key_still_gets_an_answer() throws Exception {
        var answers = askFromEveryConnectionAtOnce(key -> durableQueues.hasOrderedMessageQueuedForKey(QUEUE, key));

        assertThat(answers).hasSize(POOL_SIZE).containsOnly(false);
    }

    @Test
    void every_connection_held_by_a_unit_of_work_asking_for_many_keys_still_gets_an_answer() throws Exception {
        var answers = askFromEveryConnectionAtOnce(key -> durableQueues.getKeysWithOrderedMessagesQueued(QUEUE, List.of(key, key + "-other")));

        assertThat(answers).hasSize(POOL_SIZE).allSatisfy(answer -> assertThat((Set<?>) answer).isEmpty());
    }

    /**
     * The other half of answering on the unit of work's connection: the answer includes what that unit of work has
     * queued and not yet committed, and only that unit of work sees it.
     */
    @Test
    void the_answer_includes_what_the_asking_unit_of_work_queued_and_has_not_committed() throws Exception {
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, OrderedMessage.of(new Event("committed"), "committed", 0)));

        var seenInside = new ArrayList<Object>();
        var seenOutside = new CompletableFuture<Set<String>>();
        unitOfWorkFactory.usingUnitOfWork(() -> {
            durableQueues.queueMessage(QUEUE, OrderedMessage.of(new Event("pending"), "pending", 0));
            seenInside.add(durableQueues.hasOrderedMessageQueuedForKey(QUEUE, "pending"));
            seenInside.add(durableQueues.getKeysWithOrderedMessagesQueued(QUEUE, List.of("committed", "pending", "absent")));
            // Another thread, outside this unit of work, must not see the uncommitted message
            seenOutside.complete(CompletableFuture.supplyAsync(() -> durableQueues.getKeysWithOrderedMessagesQueued(QUEUE, List.of("committed", "pending", "absent")))
                                                  .join());
        });

        assertThat(seenInside.get(0)).isEqualTo(true);
        assertThat(seenInside.get(1)).isEqualTo(Set.of("committed", "pending"));
        assertThat(seenOutside.get(5, TimeUnit.SECONDS)).isEqualTo(Set.of("committed"));
    }

    /**
     * {@code POOL_SIZE} threads each open a unit of work, take its connection by using it, wait until all of them hold
     * one - every connection of the pool - and then ask.
     */
    private List<Object> askFromEveryConnectionAtOnce(java.util.function.Function<String, Object> ask) throws Exception {
        var allHolding = new CyclicBarrier(POOL_SIZE);
        var pool       = Executors.newFixedThreadPool(POOL_SIZE);
        try {
            var futures = new ArrayList<Future<Object>>();
            for (var thread = 0; thread < POOL_SIZE; thread++) {
                var key = "key-" + thread;
                futures.add(pool.submit(() -> unitOfWorkFactory.withUnitOfWork(unitOfWork -> {
                    ((HandleAwareUnitOfWork) unitOfWork).handle().execute("SELECT 1");
                    allHolding.await(10, TimeUnit.SECONDS);
                    return ask.apply(key);
                })));
            }
            var answers = new ArrayList<Object>();
            for (var future : futures) {
                answers.add(future.get(30, TimeUnit.SECONDS));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    private static dk.trustworks.essentials.components.queue.shardowned.spi.QueueName engineName(QueueName queueName) {
        return dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(queueName.toString());
    }

    private record Event(String id) {
    }

    /**
     * Resolves a queue against the registry once and keeps it - every lookup after that is free, as in a running
     * application, so the only connections the tests measure are the key check's own.
     */
    private static final class CachingMessageQueues
            implements dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues, AutoCloseable {
        private final javax.sql.DataSource dataSource;
        private final Map<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName,
                dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue> built = new LinkedHashMap<>();

        CachingMessageQueues(javax.sql.DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @Override
        public synchronized List<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName> queueNames()
                throws java.sql.SQLException {
            return ShardOwnedSchema.queueNames(dataSource);
        }

        @Override
        public synchronized Optional<dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueue> findQueue(
                dk.trustworks.essentials.components.queue.shardowned.spi.QueueName queueName) throws java.sql.SQLException {
            var queue = built.get(queueName);
            if (queue == null) {
                if (ShardOwnedSchema.resolve(dataSource, queueName).isEmpty()) {
                    return Optional.empty();
                }
                queue = dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue
                        .builder()
                        .setDataSource(dataSource)
                        .setQueueName(queueName)
                        .setInstanceId("key-check-test")
                        .build();
                built.put(queueName, queue);
            }
            return Optional.of(queue);
        }

        @Override
        public synchronized void close() {
            built.values().forEach(dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue::close);
        }
    }
}
