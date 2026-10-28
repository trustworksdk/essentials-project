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

package dk.trustworks.essentials.components.queue.postgresql;

import com.zaxxer.hikari.*;
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.spring.jdbi.SpringTransactionAwareJdbiUnitOfWorkFactory;
import dk.trustworks.essentials.shared.interceptor.*;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * A queued message handler, run in a {@link SpringTransactionAwareJdbiUnitOfWorkFactory} unit of work, calls an inner
 * Spring transactional participant (here a {@link TransactionTemplate}, as an inner {@code @Transactional} method
 * would) that fails, and catches the exception. The participant has marked the Spring transaction rollback-only, so it
 * can never commit.
 * <p>
 * The transaction must still be completed - rolled back, with the handler's writes and the in-transaction
 * acknowledgement - and the message retried as a failed attempt. Neither the transaction nor the unit of work may stay
 * bound to the consumer's worker thread, where the next message would silently join it.
 * <p>
 * Wired as {@code spring-boot-starter-postgresql} wires it, and run for both consumers (the centralized fetcher and
 * the traditional per-consumer poller).
 */
@Testcontainers
class SpringRollbackOnlyTransactionInQueuedMessageHandlerIT {
    private static final String    QUEUE_TABLE             = "spring_rollback_only_queue";
    private static final QueueName QUEUE                   = QueueName.of("SpringRollbackOnlyQueue");
    private static final String    FAILS_INNER_PARTICIPANT = "fails-inner-participant";
    private static final String    NEXT_MESSAGE            = "next-message";

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("spring-rollback-only-db");

    private HikariDataSource                            dataSource;
    /**
     * Outside every unit of work, so it sees only what has been committed
     */
    private Jdbi                                        jdbi;
    private TransactionTemplate                         transactionTemplate;
    private SpringTransactionAwareJdbiUnitOfWorkFactory unitOfWorkFactory;
    private PostgresqlDurableQueues                     durableQueues;
    private DurableQueueConsumer                        consumer;

    private final List<Delivery> deliveries               = new CopyOnWriteArrayList<>();
    private final List<String>   leftBoundAfterHandling   = new CopyOnWriteArrayList<>();
    private final AtomicBoolean  failInnerParticipantOnce = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        config.setUsername(postgreSQLContainer.getUsername());
        config.setPassword(postgreSQLContainer.getPassword());
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);
        jdbi = Jdbi.create(dataSource);
        var transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
        unitOfWorkFactory = new SpringTransactionAwareJdbiUnitOfWorkFactory(Jdbi.create(new TransactionAwareDataSourceProxy(dataSource)),
                                                                            transactionManager);
        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE IF NOT EXISTS handler_writes (payload TEXT)");
            handle.execute("TRUNCATE handler_writes");
        });
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.cancel();
        }
        if (durableQueues != null) {
            durableQueues.stop();
        }
        dataSource.close();
    }

    @ParameterizedTest(name = "centralized message fetcher: {0}")
    @ValueSource(booleans = {true, false})
    void the_rollback_only_transaction_is_not_left_bound_to_the_worker_thread(boolean useCentralizedMessageFetcher) {
        startDurableQueues(useCentralizedMessageFetcher);
        consumer = consume();
        enqueue(FAILS_INNER_PARTICIPANT);
        Awaitility.waitAtMost(Duration.ofSeconds(10)).until(() -> !deliveries.isEmpty());

        enqueue(NEXT_MESSAGE);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .until(() -> deliveries.stream().anyMatch(delivery -> delivery.payload().equals(NEXT_MESSAGE)));

        var failed = deliveries.getFirst();
        var next = deliveries.stream().filter(delivery -> delivery.payload().equals(NEXT_MESSAGE)).findFirst().orElseThrow();
        assertThat(next.thread()).as("one worker thread, so a transaction left bound to it would be joined by the next message")
                                 .isSameAs(failed.thread());
        assertSoftly(softly -> {
            softly.assertThat(leftBoundAfterHandling)
                  .as("transaction state still bound to the worker thread once a message's handling returned")
                  .isEmpty();
            softly.assertThat(next.unitOfWork())
                  .as("the next message runs in a unit of work of its own")
                  .isNotSameAs(failed.unitOfWork());
            softly.assertThat(next.springTransactionRollbackOnlyOnEntry())
                  .as("the next message's Spring transaction is fresh, not the rollback-only one")
                  .isFalse();
        });
    }

    @ParameterizedTest(name = "centralized message fetcher: {0}")
    @ValueSource(booleans = {true, false})
    void the_handlers_writes_are_rolled_back_and_the_message_is_retried_as_a_failed_attempt(boolean useCentralizedMessageFetcher) {
        startDurableQueues(useCentralizedMessageFetcher);
        consumer = consume();
        var queueEntryId = enqueue(FAILS_INNER_PARTICIPANT);

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getQueuedMessage(queueEntryId))
                          .as("retried after its rollback-only transaction rolled back, then handled and acknowledged")
                          .isEmpty());
        assertThat(deliveries).extracting(Delivery::redeliveryAttempts)
                              .as("the rolled back delivery counted as a failed attempt")
                              .containsExactly(0, 1);
        assertThat(handlerWrites()).as("only the retry's write committed - the first delivery's was rolled back").isEqualTo(1);
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(QUEUE)).isZero();
    }

    private void startDurableQueues(boolean useCentralizedMessageFetcher) {
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setSharedQueueTableName(QUEUE_TABLE)
                                               .setUseCentralizedMessageFetcher(useCentralizedMessageFetcher)
                                               .build();
        durableQueues.addInterceptor(new RecordThreadStateAfterHandling(leftBoundAfterHandling));
        durableQueues.start();
        durableQueues.purgeQueue(QUEUE);
    }

    /**
     * The first delivery of {@link #FAILS_INNER_PARTICIPANT} calls an inner transactional participant that fails, and
     * catches its exception - the handler itself returns normally
     */
    private DurableQueueConsumer consume() {
        return durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                              .setQueueName(QUEUE)
                                                              .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(50), 3))
                                                              .setParallelConsumers(1)
                                                              .setQueueMessageHandler(message -> {
                                                                  deliveries.add(new Delivery(message.getPayload().toString(),
                                                                                              message.getRedeliveryAttempts(),
                                                                                              unitOfWorkFactory.getRequiredUnitOfWork(),
                                                                                              Thread.currentThread(),
                                                                                              springTransactionRollbackOnly()));
                                                                  unitOfWorkFactory.getRequiredUnitOfWork()
                                                                                   .handle()
                                                                                   .execute("INSERT INTO handler_writes VALUES (?)", message.getPayload().toString());
                                                                  if (message.getPayload().equals(FAILS_INNER_PARTICIPANT) && failInnerParticipantOnce.compareAndSet(true, false)) {
                                                                      try {
                                                                          transactionTemplate.executeWithoutResult(status -> {
                                                                              throw new IllegalStateException("the inner transactional participant fails");
                                                                          });
                                                                      } catch (IllegalStateException caught) {
                                                                          // The handler carries on - but the participant has marked the transaction rollback-only
                                                                      }
                                                                  }
                                                              })
                                                              .build());
    }

    private QueueEntryId enqueue(String payload) {
        return unitOfWorkFactory.withUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of(payload)));
    }

    private boolean springTransactionRollbackOnly() {
        return TransactionSynchronizationManager.getResource(dataSource) instanceof ConnectionHolder connectionHolder && connectionHolder.isRollbackOnly();
    }

    private long handlerWrites() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) FROM handler_writes").mapTo(Long.class).one());
    }

    private record Delivery(String payload, int redeliveryAttempts, UnitOfWork unitOfWork, Thread thread, boolean springTransactionRollbackOnlyOnEntry) {
    }

    /**
     * Outermost, so it sees the worker thread once the unit of work around the handler has been completed
     */
    @InterceptorOrder(1)
    private static final class RecordThreadStateAfterHandling implements DurableQueuesInterceptor {
        private final List<String> leftBoundAfterHandling;

        private RecordThreadStateAfterHandling(List<String> leftBoundAfterHandling) {
            this.leftBoundAfterHandling = leftBoundAfterHandling;
        }

        @Override
        public void setDurableQueues(DurableQueues durableQueues) {
        }

        @Override
        public Void intercept(HandleQueuedMessage operation, InterceptorChain<HandleQueuedMessage, Void, DurableQueuesInterceptor> interceptorChain) {
            try {
                return interceptorChain.proceed();
            } finally {
                if (TransactionSynchronizationManager.isSynchronizationActive() || !TransactionSynchronizationManager.getResourceMap().isEmpty()) {
                    leftBoundAfterHandling.add(operation.getMessage().getPayload() + ": synchronizationActive=" + TransactionSynchronizationManager.isSynchronizationActive() +
                                                       ", resources=" + TransactionSynchronizationManager.getResourceMap().keySet());
                }
            }
        }
    }
}
