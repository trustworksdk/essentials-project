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

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.shared.interceptor.InterceptorChain;
import org.awaitility.Awaitility;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A successfully handled message is acknowledged inside the handler's own transaction, so the handler's writes and
 * the acknowledgement commit together - except where that would change what happens to the message.
 */
@Testcontainers
class PostgresqlDurableQueuesAcknowledgeWithHandlerIT {
    private static final String    QUEUE_TABLE = "ack_with_handler_queue";
    private static final QueueName QUEUE       = QueueName.of("AckWithHandlerQueue");

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = EssentialsTestContainers.postgres("ack-with-handler-db");

    private JdbiUnitOfWorkFactory   unitOfWorkFactory;
    private PostgresqlDurableQueues durableQueues;
    private DurableQueueConsumer    consumer;

    @BeforeEach
    void setUp() {
        unitOfWorkFactory = new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                                   postgreSQLContainer.getUsername(),
                                                                   postgreSQLContainer.getPassword()));
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(EssentialsObjectMappers.createJSONSerializer())
                                               .setSharedQueueTableName(QUEUE_TABLE)
                                               .build();
        durableQueues.start();
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("CREATE TABLE IF NOT EXISTS handler_writes (payload TEXT)");
            uow.handle().execute("TRUNCATE handler_writes");
        });
        durableQueues.purgeQueue(QUEUE);
    }

    @AfterEach
    void tearDown() {
        if (consumer != null) {
            consumer.cancel();
        }
        durableQueues.stop();
    }

    @Test
    void the_acknowledgement_runs_in_the_handlers_unit_of_work() {
        var handlerUnitOfWork     = new AtomicReference<UnitOfWork>();
        var acknowledgeUnitOfWork = new AtomicReference<UnitOfWork>();
        durableQueues.addInterceptor(new DurableQueuesInterceptor() {
            @Override
            public void setDurableQueues(DurableQueues durableQueues) {
            }

            @Override
            public boolean intercept(AcknowledgeMessageAsHandled operation, InterceptorChain<AcknowledgeMessageAsHandled, Boolean, DurableQueuesInterceptor> interceptorChain) {
                acknowledgeUnitOfWork.set(unitOfWorkFactory.getCurrentUnitOfWork().orElse(null));
                return interceptorChain.proceed();
            }
        });

        consumer = consume(message -> handlerUnitOfWork.set(unitOfWorkFactory.getRequiredUnitOfWork()));
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of("payload")));

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero());
        assertThat(acknowledgeUnitOfWork.get()).isNotNull().isSameAs(handlerUnitOfWork.get());
    }

    @Test
    void a_failed_acknowledgement_rolls_back_the_handlers_writes() {
        var acknowledgements = new AtomicInteger();
        durableQueues.addInterceptor(new DurableQueuesInterceptor() {
            @Override
            public void setDurableQueues(DurableQueues durableQueues) {
            }

            @Override
            public boolean intercept(AcknowledgeMessageAsHandled operation, InterceptorChain<AcknowledgeMessageAsHandled, Boolean, DurableQueuesInterceptor> interceptorChain) {
                if (acknowledgements.incrementAndGet() == 1) {
                    throw new IllegalStateException("the first acknowledgement fails");
                }
                return interceptorChain.proceed();
            }
        });

        consumer = consume(message -> unitOfWorkFactory.getRequiredUnitOfWork().handle()
                                                       .execute("INSERT INTO handler_writes VALUES (?)", message.getPayload().toString()));
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of("payload")));

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero());
        assertThat(acknowledgements.get()).as("delivered again after the failed acknowledgement").isEqualTo(2);
        assertThat(handlerWrites()).as("the first delivery's write was rolled back with its acknowledgement").isEqualTo(1);
    }

    @Test
    void a_handler_that_marks_its_unit_of_work_rollback_only_still_consumes_the_message() {
        var deliveries = new AtomicInteger();
        consumer = consume(message -> {
            deliveries.incrementAndGet();
            unitOfWorkFactory.getRequiredUnitOfWork().handle().execute("INSERT INTO handler_writes VALUES ('discarded')");
            unitOfWorkFactory.getRequiredUnitOfWork().markAsRollbackOnly();
        });
        unitOfWorkFactory.usingUnitOfWork(() -> durableQueues.queueMessage(QUEUE, Message.of("payload")));

        Awaitility.waitAtMost(Duration.ofSeconds(10))
                  .untilAsserted(() -> assertThat(durableQueues.getTotalMessagesQueuedFor(QUEUE)).isZero());
        assertThat(deliveries.get()).isEqualTo(1);
        assertThat(handlerWrites()).isZero();
    }

    private DurableQueueConsumer consume(QueuedMessageHandler handler) {
        return durableQueues.consumeFromQueue(ConsumeFromQueue.builder()
                                                              .setQueueName(QUEUE)
                                                              .setRedeliveryPolicy(RedeliveryPolicy.fixedBackoff(Duration.ofMillis(50), 3))
                                                              .setParallelConsumers(1)
                                                              .setQueueMessageHandler(handler)
                                                              .build());
    }

    private long handlerWrites() {
        return unitOfWorkFactory.withUnitOfWork(uow -> uow.handle().createQuery("SELECT count(*) FROM handler_writes").mapTo(Long.class).one());
    }
}
