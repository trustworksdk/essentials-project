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

import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.jdbc.test.AbstractDurableQueuesLatencyIT;
import dk.trustworks.essentials.components.queue.jdbc.test.DurableQueuesTestSupport;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.*;
import java.util.*;

@Testcontainers
public abstract class PostgresqlDurableQueuesLatencyIT extends AbstractDurableQueuesLatencyIT<PostgresqlDurableQueues> {

    @Container
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test")
            .withPassword("test")
            .withUsername("test");

    @Override
    protected PostgresqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setQueuePollingOptimizerFactory(DurableQueuesTestSupport.defaultQueuePollingOptimizerFactory())
                                      .setMultiTableChangeListener(DurableQueuesTestSupport.defaultMultiTableChangeListener(unitOfWorkFactory.getJdbi()))
                                      .setUseCentralizedMessageFetcher(false)
                                      .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(30))
                                      .setUseOrderedUnorderedQuery(false)
                                      .build();
    }

    @Override
    protected JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        var jdbi = Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                               postgreSQLContainer.getUsername(),
                               postgreSQLContainer.getPassword());
        return new JdbiUnitOfWorkFactory(jdbi);
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        DurableQueuesTestSupport.dropQueueTable(unitOfWorkFactory, PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME);
    }

    @Override
    protected String orderedSql() {
        return durableQueues.getDurableQueuesSql().buildOrderedSqlStatement(false);
    }

    @Override
    protected String unorderedSql() {
        return durableQueues.getDurableQueuesSql().buildUnorderedSqlStatement();
    }

    @Override
    protected String oldSql() {
        return durableQueues.getDurableQueuesSql().buildGetNextMessageReadyForDeliverySqlStatement(Collections.emptySet());
    }

    @Override
    protected Optional<QueuedMessage> fetchAndDeleteBySql(String sql, QueueName queueName) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var queuedMessage = uow.handle().createQuery(sql)
                                   .bind("queueName", queueName)
                                   .bind("now", Instant.now())
                                   .bind("limit", 1)
                                   .map(durableQueues.getQueuedMessageMapper())
                                   .findOne();
            queuedMessage.ifPresent(message -> uow.handle().createUpdate("DELETE FROM durable_queues WHERE id = :id")
                                                      .bind("id", message.getId())
                                                      .execute());
            return queuedMessage;
        });
    }

    @Override
    protected Optional<QueuedMessage> fetchAndDeleteOrderedThenUnordered(String orderedSql, String unorderedSql, QueueName queueName) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var handle = uow.handle();
            var queuedOrderedMessage = handle.createQuery(orderedSql)
                                             .bind("queueName", queueName)
                                             .bind("now", Instant.now())
                                             .bind("limit", 1)
                                             .map(durableQueues.getQueuedMessageMapper())
                                             .findOne();
            if (queuedOrderedMessage.isPresent()) {
                handle.createUpdate("DELETE FROM durable_queues WHERE id = :id")
                      .bind("id", queuedOrderedMessage.get().getId())
                      .execute();
                return queuedOrderedMessage;
            }

            var queuedMessage = handle.createQuery(unorderedSql)
                                      .bind("queueName", queueName)
                                      .bind("now", Instant.now())
                                      .bind("limit", 1)
                                      .map(durableQueues.getQueuedMessageMapper())
                                      .findOne();
            queuedMessage.ifPresent(message -> handle.createUpdate("DELETE FROM durable_queues WHERE id = :id")
                                             .bind("id", message.getId())
                                             .execute());
            return queuedMessage;
        });
    }

    @Override
    protected List<QueuedMessage> fetchAndDeleteBatched(List<QueueName> queuesList,
                                                        Map<QueueName, Integer> availableSlotPrQueue,
                                                        QueueName queueName) {
        return unitOfWorkFactory.withUnitOfWork(uow -> {
            var batchedSqlResult = durableQueues.getDurableQueuesSql().buildBatchedSqlStatement(Map.of(), availableSlotPrQueue, queuesList);
            var query = uow.handle().createQuery(batchedSqlResult.getSql())
                           .bind("queueName", queueName)
                           .bind("now", Instant.now())
                           .bind("limit", 1);

            for (var entry : batchedSqlResult.getSingleValueBindings().entrySet()) {
                query.bind(entry.getKey(), entry.getValue());
            }
            for (var entry : batchedSqlResult.getListBindings().entrySet()) {
                query.bindList(entry.getKey(), entry.getValue());
            }

            var queuedMessages = query.map(durableQueues.getQueuedMessageMapper()).list();
            if (!queuedMessages.isEmpty()) {
                uow.handle()
                   .createUpdate("DELETE FROM durable_queues WHERE id IN (<ids>)")
                   .bindList("ids", queuedMessages.stream().map(QueuedMessage::getId).toList())
                   .execute();
            }
            return queuedMessages;
        });
    }
}
