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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.distributed.fencedlock.postgresql.PostgresqlFencedLockManager;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.shardowned.ShardOwnedSchema;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;

/**
 * An enqueue on the shard-owned engine joins a plain Spring transaction — {@code @Transactional} or a
 * {@link TransactionTemplate} — and not only an Essentials {@code UnitOfWork} that is already bound.
 * <p>
 * Wired the way {@code spring-boot-starter-postgresql} wires it: a {@link DataSourceTransactionManager}, a
 * {@link SpringTransactionAwareJdbiUnitOfWorkFactory} over a JDBI behind a {@link TransactionAwareDataSourceProxy}.
 * Under a {@code TransactionTemplate} nothing has bound a {@code UnitOfWork} yet, so an adapter that only asked
 * {@code getCurrentUnitOfWork()} committed the enqueue on its own connection, and the Outbox kept a message for
 * work that had rolled back (N-15).
 * <p>
 * Also the opt-in guard against an enqueue outside any unit of work or transaction (RN-2): by default it commits on
 * its own and WARNs once; with {@code requireActiveUnitOfWorkForEnqueue} it fails.
 */
@Testcontainers(disabledWithoutDocker = true)
class EnqueueTransactionBoundaryOnShardOwnedIT {
    private static final QueueName OUTBOX_QUEUE = QueueName.of("Outbox:orders");
    private static final QueueName PLAIN_QUEUE  = QueueName.of("plain");

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.5-bookworm");

    private HikariDataSource                            dataSource;
    private TransactionTemplate                         transactionTemplate;
    private SpringTransactionAwareJdbiUnitOfWorkFactory unitOfWorkFactory;
    private TestMessageQueues                           queues;
    private ShardOwnedDurableQueues                     durableQueues;
    private PostgresqlFencedLockManager                 lockManager;

    @BeforeEach
    void setUp() throws Exception {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(20);
        dataSource = new HikariDataSource(config);

        var transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
        var jdbi = Jdbi.create(new TransactionAwareDataSourceProxy(dataSource));
        unitOfWorkFactory = new SpringTransactionAwareJdbiUnitOfWorkFactory(jdbi, transactionManager);

        ShardOwnedSchema.recreate(dataSource);
        ShardOwnedSchema.registerQueue(dataSource, engineName(OUTBOX_QUEUE), 4);
        ShardOwnedSchema.registerQueue(dataSource, engineName(PLAIN_QUEUE), 4);

        queues = new TestMessageQueues(dataSource);
        durableQueues = ShardOwnedDurableQueues.builder()
                                               .setQueues(queues)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setDataSource(dataSource)
                                               .build();
        durableQueues.start();

        lockManager = PostgresqlFencedLockManager.builder()
                                                 .setJdbi(jdbi)
                                                 .setUnitOfWorkFactory(unitOfWorkFactory)
                                                 .setLockTimeOut(Duration.ofSeconds(3))
                                                 .setLockConfirmationInterval(Duration.ofSeconds(1))
                                                 .buildAndStart();
    }

    @AfterEach
    void tearDown() {
        if (lockManager != null) {
            lockManager.stop();
        }
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
    void an_outbox_enqueue_rolls_back_with_a_spring_transaction_template() {
        var received = new CopyOnWriteArrayList<OrderPlaced>();
        var outbox   = outbox(received);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            outbox.sendMessage(Message.of(new OrderPlaced("rolled-back", 1)));
            throw new IllegalStateException("the caller's own work failed");
        })).hasMessageContaining("the caller's own work failed");

        assertThat(durableQueues.getTotalMessagesQueuedFor(OUTBOX_QUEUE))
                .describedAs("the enqueue joined the Spring transaction, so it died with it")
                .isZero();

        // The committing case still delivers - and is the only thing delivered, which also catches a rolled-back
        // message the outbox consumer took before the count above was read.
        transactionTemplate.executeWithoutResult(status -> outbox.sendMessage(Message.of(new OrderPlaced("committed", 2))));
        Awaitility.await().atMost(Duration.ofSeconds(30))
                  .untilAsserted(() -> assertThat(received).extracting(OrderPlaced::id).containsExactly("committed"));
    }

    @Test
    void a_direct_enqueue_rolls_back_with_a_spring_transaction_marked_rollback_only() {
        transactionTemplate.executeWithoutResult(status -> {
            durableQueues.queueMessage(PLAIN_QUEUE, Message.of(new OrderPlaced("rolled-back", 1)));
            durableQueues.queueMessages(PLAIN_QUEUE, List.of(Message.of(new OrderPlaced("rolled-back-too", 2))));
            status.setRollbackOnly();
        });

        assertThat(durableQueues.getTotalMessagesQueuedFor(PLAIN_QUEUE)).isZero();
    }

    @Test
    void a_committed_spring_transaction_keeps_its_enqueue() {
        transactionTemplate.executeWithoutResult(status -> durableQueues.queueMessage(PLAIN_QUEUE, Message.of(new OrderPlaced("committed", 1))));

        assertThat(durableQueues.getTotalMessagesQueuedFor(PLAIN_QUEUE)).isEqualTo(1);
    }

    @Test
    void an_enqueue_outside_any_transaction_still_commits_on_its_own() {
        durableQueues.queueMessage(PLAIN_QUEUE, Message.of(new OrderPlaced("standalone", 1)));

        assertThat(durableQueues.getTotalMessagesQueuedFor(PLAIN_QUEUE)).isEqualTo(1);
    }

    @Test
    void building_without_a_unit_of_work_factory_warns_that_enqueues_commit_on_their_own() {
        var logger   = (Logger) LoggerFactory.getLogger(ShardOwnedDurableQueues.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            ShardOwnedDurableQueues.builder()
                                   .setQueues(queues)
                                   .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                   .setDataSource(dataSource)
                                   .build();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .extracting(ILoggingEvent::getFormattedMessage)
                .singleElement(as(STRING))
                .contains("without a UnitOfWorkFactory")
                .contains("setUnitOfWorkFactory");
    }

    @Test
    void a_direct_enqueue_rolls_back_with_an_essentials_unit_of_work() {
        var essentialsUnitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
        var essentialsQueues            = adapter(essentialsUnitOfWorkFactory, false);

        assertThatThrownBy(() -> essentialsUnitOfWorkFactory.usingUnitOfWork(() -> {
            essentialsQueues.queueMessage(PLAIN_QUEUE, Message.of(new OrderPlaced("rolled-back", 1)));
            essentialsQueues.queueMessages(PLAIN_QUEUE, List.of(Message.of(new OrderPlaced("rolled-back-too", 2))));
            throw new IllegalStateException("the caller's own work failed");
        })).hasRootCauseMessage("the caller's own work failed");

        assertThat(essentialsQueues.getTotalMessagesQueuedFor(PLAIN_QUEUE)).isZero();
    }

    // --------------------------------------- outside a unit of work (RN-2)

    @Test
    void by_default_an_enqueue_outside_a_unit_of_work_commits_and_warns_once_with_its_call_site() {
        var logger   = (Logger) LoggerFactory.getLogger(ShardOwnedDurableQueues.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            durableQueues.queueMessage(PLAIN_QUEUE, Message.of(new OrderPlaced("first", 1)));
            durableQueues.queueMessages(PLAIN_QUEUE, List.of(Message.of(new OrderPlaced("second", 2))));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(durableQueues.isRequireActiveUnitOfWorkForEnqueue()).isFalse();
        assertThat(durableQueues.getTotalMessagesQueuedFor(PLAIN_QUEUE)).isEqualTo(2);
        assertThat(appender.list)
                .filteredOn(event -> event.getLevel() == Level.WARN)
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getFormattedMessage()).contains("outside a UnitOfWork")
                                                           .contains(EnqueueOutsideUnitOfWorkGuard.REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY);
                    assertThat(event.getThrowableProxy()).as("the call site").isNotNull();
                });
    }

    @Test
    void when_required_an_enqueue_outside_a_unit_of_work_fails_and_queues_nothing() {
        var strict = adapter(unitOfWorkFactory, true);

        assertThatThrownBy(() -> strict.queueMessage(PLAIN_QUEUE, Message.of(new OrderPlaced("refused", 1))))
                .isInstanceOf(NoActiveUnitOfWorkException.class)
                .hasMessageContaining(EnqueueOutsideUnitOfWorkGuard.REQUIRE_ACTIVE_UNIT_OF_WORK_FOR_ENQUEUE_PROPERTY);
        assertThatThrownBy(() -> strict.queueMessages(PLAIN_QUEUE, List.of(Message.of(new OrderPlaced("refused-too", 2)))))
                .isInstanceOf(NoActiveUnitOfWorkException.class);

        assertThat(strict.getTotalMessagesQueuedFor(PLAIN_QUEUE)).isZero();
    }

    @Test
    void when_required_an_enqueue_inside_a_plain_spring_transaction_goes_ahead() {
        var strict = adapter(unitOfWorkFactory, true);

        transactionTemplate.executeWithoutResult(status -> strict.queueMessage(PLAIN_QUEUE, Message.of(new OrderPlaced("inside", 1))));

        assertThat(strict.getTotalMessagesQueuedFor(PLAIN_QUEUE)).isEqualTo(1);
    }

    @Test
    void when_required_an_enqueue_inside_an_essentials_unit_of_work_goes_ahead() {
        var essentialsUnitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
        var strict                      = adapter(essentialsUnitOfWorkFactory, true);

        essentialsUnitOfWorkFactory.usingUnitOfWork(() -> strict.queueMessage(PLAIN_QUEUE, Message.of(new OrderPlaced("inside", 1))));

        assertThat(strict.getTotalMessagesQueuedFor(PLAIN_QUEUE)).isEqualTo(1);
    }

    @Test
    void requiring_a_unit_of_work_without_a_unit_of_work_factory_is_refused_at_build() {
        assertThatThrownBy(() -> ShardOwnedDurableQueues.builder()
                                                        .setQueues(queues)
                                                        .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                                        .setDataSource(dataSource)
                                                        .setRequireActiveUnitOfWorkForEnqueue(true)
                                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("setUnitOfWorkFactory");
    }

    private ShardOwnedDurableQueues adapter(UnitOfWorkFactory<? extends UnitOfWork> factory, boolean requireActiveUnitOfWorkForEnqueue) {
        return ShardOwnedDurableQueues.builder()
                                      .setQueues(queues)
                                      .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                      .setUnitOfWorkFactory(factory)
                                      .setDataSource(dataSource)
                                      .setRequireActiveUnitOfWorkForEnqueue(requireActiveUnitOfWorkForEnqueue)
                                      .build();
    }

    private Outbox outbox(List<OrderPlaced> received) {
        return Outboxes.durableQueueBasedOutboxes(durableQueues, lockManager)
                       .getOrCreateOutbox(OutboxConfig.builder()
                                                      .setOutboxName(OutboxName.of("orders"))
                                                      .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(200), 3))
                                                      .setMessageConsumptionMode(MessageConsumptionMode.SingleGlobalConsumer)
                                                      .setNumberOfParallelMessageConsumers(1)
                                                      .build(),
                                          message -> received.add((OrderPlaced) message.getPayload()));
    }

    private static dk.trustworks.essentials.components.queue.shardowned.spi.QueueName engineName(QueueName queueName) {
        return dk.trustworks.essentials.components.queue.shardowned.spi.QueueName.of(queueName.toString());
    }

    record OrderPlaced(String id, int amount) {
    }

    private static final class TestMessageQueues
            implements dk.trustworks.essentials.components.queue.shardowned.spi.MessageQueues, AutoCloseable {
        private final javax.sql.DataSource dataSource;
        private final Map<dk.trustworks.essentials.components.queue.shardowned.spi.QueueName,
                dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue> built = new LinkedHashMap<>();

        TestMessageQueues(javax.sql.DataSource dataSource) {
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
            var registered = ShardOwnedSchema.resolve(dataSource, queueName);
            if (registered.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(built.computeIfAbsent(
                    queueName,
                    name -> dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue
                            .builder()
                            .setDataSource(dataSource)
                            .setQueueName(name)
                            .setInstanceId("spring-tx-test")
                            .build()));
        }

        @Override
        public synchronized void close() {
            built.values().forEach(dk.trustworks.essentials.components.queue.shardowned.PostgresqlMessageQueue::close);
        }
    }
}
