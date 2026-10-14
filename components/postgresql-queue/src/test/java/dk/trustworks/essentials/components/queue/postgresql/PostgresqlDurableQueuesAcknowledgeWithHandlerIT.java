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
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A successfully handled message is acknowledged inside the handler's own transaction, so the handler's writes and
 * the acknowledgement commit together - except where that would change what happens to the message.
 * <p>
 * Run for each kind of {@link HandleAwareUnitOfWorkFactory} (plain JDBI, and Spring transaction-aware as
 * {@code spring-boot-starter-postgresql} wires it) and for both consumers (the centralized fetcher, the default, and
 * the traditional per-consumer poller): each consumer has its own path from the handler to the acknowledgement and to
 * the stop-time hand-back.
 */
@Testcontainers
abstract class PostgresqlDurableQueuesAcknowledgeWithHandlerIT {
    private static final String    QUEUE_TABLE = "ack_with_handler_queue";
    private static final QueueName QUEUE       = QueueName.of("AckWithHandlerQueue");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("ack-with-handler-db");

    private HikariDataSource                                              dataSource;
    /**
     * Outside every unit of work, so it sees only what has been committed
     */
    private Jdbi                                                          jdbi;
    private HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory;
    private PostgresqlDurableQueues                                       durableQueues;
    private DurableQueueConsumer                                          consumer;

    protected abstract boolean useCentralizedMessageFetcher();

    protected abstract HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> createUnitOfWorkFactory(DataSource dataSource);

    @BeforeEach
    void setUp() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        config.setUsername(postgreSQLContainer.getUsername());
        config.setPassword(postgreSQLContainer.getPassword());
        config.setMaximumPoolSize(10);
        dataSource = new HikariDataSource(config);
        jdbi = Jdbi.create(dataSource);
        unitOfWorkFactory = createUnitOfWorkFactory(dataSource);
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setSharedQueueTableName(QUEUE_TABLE)
                                               .setUseCentralizedMessageFetcher(useCentralizedMessageFetcher())
                                               .build();
        durableQueues.start();
        jdbi.useHandle(handle -> {
            handle.execute("CREATE TABLE IF NOT EXISTS handler_writes (payload TEXT)");
            handle.execute("TRUNCATE handler_writes");
        });
        durableQueues.purgeQueue(QUEUE);
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.cancel();
        }
        durableQueues.stop();
        dataSource.close();
    }

    @Test
    void the_acknowledgement_runs_in_the_handlers_unit_of_work() {
        var handlerUnitOfWork                   = new AtomicReference<UnitOfWork>();
        var acknowledgeUnitOfWork               = new AtomicReference<UnitOfWork>();
        var handlerWritesCommittedAtAcknowledge = new AtomicLong(-1);
        durableQueues.addInterceptor(onAcknowledge(chain -> {
            acknowledgeUnitOfWork.set(unitOfWorkFactory.getCurrentUnitOfWork().orElse(null));
            handlerWritesCommittedAtAcknowledge.set(handlerWrites());
            return chain.proceed();
        }));

        consumer = consume(message -> {
            handlerUnitOfWork.set(unitOfWorkFactory.getRequiredUnitOfWork());
            writeFromHandler(message);
        });
        var queueEntryId = enqueue();

        awaitQueueEmpty();
        assertThat(acknowledgeUnitOfWork.get()).isNotNull().isSameAs(handlerUnitOfWork.get());
        assertThat(handlerWritesCommittedAtAcknowledge.get()).as("the handler's write was not yet committed when the acknowledgement ran").isZero();
        assertThat(handlerWrites()).as("committed together with the acknowledgement").isEqualTo(1);
        assertThat(durableQueues.getQueuedMessage(queueEntryId)).isEmpty();
    }

    @Test
    void a_failed_acknowledgement_rolls_back_the_handlers_writes_and_the_message_is_retried() {
        var acknowledgements   = new AtomicInteger();
        var redeliveryAttempts = new CopyOnWriteArrayList<Integer>();
        durableQueues.addInterceptor(onAcknowledge(chain -> {
            if (acknowledgements.incrementAndGet() == 1) {
                throw new IllegalStateException("the first acknowledgement fails");
            }
            return chain.proceed();
        }));

        consumer = consume(message -> {
            redeliveryAttempts.add(message.getRedeliveryAttempts());
            writeFromHandler(message);
        });
        enqueue();

        awaitQueueEmpty();
        assertThat(acknowledgements.get()).as("delivered again after the failed acknowledgement").isEqualTo(2);
        assertThat(redeliveryAttempts).as("the failed acknowledgement counted as an attempt").containsExactly(0, 1);
        assertThat(handlerWrites()).as("the first delivery's write was rolled back with its acknowledgement").isEqualTo(1);
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(QUEUE)).isZero();
    }

    @Test
    void a_handler_that_marks_its_unit_of_work_rollback_only_still_consumes_the_message() {
        var deliveries = new AtomicInteger();
        consumer = consume(message -> {
            deliveries.incrementAndGet();
            writeFromHandler(message);
            unitOfWorkFactory.getRequiredUnitOfWork().markAsRollbackOnly();
        });
        enqueue();

        awaitQueueEmpty();
        assertThat(deliveries.get()).isEqualTo(1);
        assertThat(handlerWrites()).isZero();
    }

    /**
     * Stopping a consumer interrupts its handler. A handler that catches the interrupt and returns normally with the
     * flag re-set has not necessarily finished its work, so its message is not acknowledged - neither inside its unit
     * of work nor by the consumer - but handed back for the next consumer, the hand-back counting one attempt. The
     * handler did return normally, so its unit of work commits its writes; the redelivery may repeat them, as
     * at-least-once delivery always allows.
     */
    @Test
    void a_handler_that_returns_while_interrupted_by_the_consumer_stopping_has_its_message_handed_back_not_acknowledged() {
        var acknowledgements = new AtomicInteger();
        durableQueues.addInterceptor(onAcknowledge(chain -> {
            acknowledgements.incrementAndGet();
            return chain.proceed();
        }));
        var deliveries      = new AtomicInteger();
        var handlerStarted  = new CountDownLatch(1);
        var handlerFinished = new CountDownLatch(1);
        consumer = consume(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(50), 0), message -> {
            deliveries.incrementAndGet();
            try {
                writeFromHandler(message);
                handlerStarted.countDown();
                Thread.sleep(Duration.ofSeconds(60));
            } catch (InterruptedException e) {
                // Gives up on its work and keeps the interrupt, as well-behaved code does (Kafka does)
                Thread.currentThread().interrupt();
            } finally {
                handlerFinished.countDown();
            }
        });
        var queueEntryId = enqueue();
        awaitLatch(handlerStarted);

        consumer.cancel();

        awaitLatch(handlerFinished);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getQueuedMessage(queueEntryId))
                          .as("handed back for redelivery, the hand-back counting one attempt")
                          .hasValueSatisfying(queued -> {
                              assertThat(queued.isBeingDelivered()).isFalse();
                              assertThat(queued.getRedeliveryAttempts()).isEqualTo(1);
                          }));
        assertThat(acknowledgements.get()).as("not acknowledged").isZero();
        assertThat(handlerWrites()).as("the handler returned normally, so its unit of work committed").isEqualTo(1);
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(QUEUE)).isZero();

        consumer = consume(message -> deliveries.incrementAndGet());
        awaitQueueEmpty();
        assertThat(deliveries.get()).as("delivered again to the next consumer").isEqualTo(2);
        assertThat(acknowledgements.get()).as("acknowledged once, by the next consumer").isEqualTo(1);
    }

    /**
     * Stopping a consumer interrupts its handler. A handler that fails because of it has its writes rolled back, and
     * its message is handed back without being classified - the redelivery policy allows no redeliveries, so
     * classifying the failure would dead-letter it - for the next consumer to handle.
     */
    @Test
    void a_handler_that_fails_after_the_consumer_stopped_has_its_writes_rolled_back_and_its_message_handed_back() {
        var acknowledgements = new AtomicInteger();
        durableQueues.addInterceptor(onAcknowledge(chain -> {
            acknowledgements.incrementAndGet();
            return chain.proceed();
        }));
        var handlerStarted  = new CountDownLatch(1);
        var handlerFinished = new CountDownLatch(1);
        consumer = consume(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(50), 0), message -> {
            try {
                writeFromHandler(message);
                handlerStarted.countDown();
                Thread.sleep(Duration.ofSeconds(60));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while sending", e);
            } finally {
                handlerFinished.countDown();
            }
        });
        var queueEntryId = enqueue();
        awaitLatch(handlerStarted);

        consumer.cancel();

        awaitLatch(handlerFinished);
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getQueuedMessage(queueEntryId))
                          .as("handed back for redelivery, the hand-back counting one attempt")
                          .hasValueSatisfying(queued -> {
                              assertThat(queued.isBeingDelivered()).isFalse();
                              assertThat(queued.getRedeliveryAttempts()).isEqualTo(1);
                          }));
        assertThat(handlerWrites()).as("rolled back with the failed delivery").isZero();
        assertThat(acknowledgements.get()).isZero();
        assertThat(durableQueues.getTotalDeadLetterMessagesQueuedFor(QUEUE)).isZero();

        var redelivered = new CopyOnWriteArrayList<QueueEntryId>();
        consumer = consume(message -> {
            redelivered.add(message.getId());
            writeFromHandler(message);
        });
        awaitQueueEmpty();
        assertThat(redelivered).containsExactly(queueEntryId);
        assertThat(handlerWrites()).isEqualTo(1);
        assertThat(acknowledgements.get()).isEqualTo(1);
    }

    private DurableQueueConsumer consume(QueuedMessageHandler handler) {
        return consume(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(50), 3), handler);
    }

    private DurableQueueConsumer consume(RedeliveryPolicy redeliveryPolicy, QueuedMessageHandler handler) {
        return durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                              .setQueueName(QUEUE)
                                                              .setRedeliveryPolicy(redeliveryPolicy)
                                                              .setParallelConsumers(1)
                                                              .setQueueMessageHandler(handler)
                                                              .build());
    }

    private QueueEntryId enqueue() {
        return unitOfWorkFactory.withUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of("payload")));
    }

    private void writeFromHandler(QueuedMessage message) {
        unitOfWorkFactory.getRequiredUnitOfWork().handle().execute("INSERT INTO handler_writes VALUES (?)", message.getPayload().toString());
    }

    private void awaitQueueEmpty() {
        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero());
    }

    private static void awaitLatch(CountDownLatch latch) {
        Awaitility.waitAtMost(Duration.ofSeconds(30)).until(() -> latch.getCount() == 0);
    }

    private long handlerWrites() {
        return jdbi.withHandle(handle -> handle.createQuery("SELECT count(*) FROM handler_writes").mapTo(Long.class).one());
    }

    private static DurableQueuesInterceptor onAcknowledge(Function<InterceptorChain<AcknowledgeMessageAsHandled, Boolean, DurableQueuesInterceptor>, Boolean> onAcknowledge) {
        return new DurableQueuesInterceptor() {
            @Override
            public void setDurableQueues(DurableQueues durableQueues) {
            }

            @Override
            public boolean intercept(AcknowledgeMessageAsHandled operation, InterceptorChain<AcknowledgeMessageAsHandled, Boolean, DurableQueuesInterceptor> interceptorChain) {
                return onAcknowledge.apply(interceptorChain);
            }
        };
    }
}
