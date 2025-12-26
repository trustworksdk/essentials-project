/*
 * Copyright 2021-2025 the original author or authors.
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

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.foundation.json.JacksonJSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.postgresql.MultiTableChangeListener;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.DuplicateConsumptionDurableQueuesIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.reactive.LocalEventBus;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;

/**
 * Base class for PostgreSQL duplicate consumption IT tests.
 * <p>
 * This test verifies that no duplicate message consumption occurs when
 * multiple PostgreSQL DurableQueues instances compete for the same messages.
 */
@Testcontainers
abstract class PostgresqlDuplicateConsumptionDurableQueuesIT extends DuplicateConsumptionDurableQueuesIT<PostgresqlDurableQueues, GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    @Container
    protected final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("queue-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    /**
     * Determine whether to use the centralized message fetcher.
     *
     * @return true for centralized message fetcher, false for traditional consumer
     */
    protected abstract boolean useCentralizedMessageFetcher();

    @Override
    protected void disruptDatabaseConnection() {
        var dockerClient = postgreSQLContainer.getDockerClient();
        dockerClient.pauseContainerCmd(postgreSQLContainer.getContainerId()).exec();
    }

    @Override
    protected void restoreDatabaseConnection() {
        var dockerClient = postgreSQLContainer.getDockerClient();
        dockerClient.unpauseContainerCmd(postgreSQLContainer.getContainerId()).exec();
    }

    @Override
    protected PostgresqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        var pollingIntervalMillis                = 20;
        var maxPollingIntervalMillis             = 2000;
        var centralizedPollingDelayBackOffFactor = 1.5d;
        var jsonSerializer                       = new JacksonJSONSerializer(DurableQueuesSerialization.createDefaultObjectMapper());
        var eventBus = LocalEventBus.builder()
                                    .busName("default")
                                    .build();
        var multiTableChangeListener = new MultiTableChangeListener<>(unitOfWorkFactory.getJdbi(),
                                                                      Duration.ofMillis(50),
                                                                      jsonSerializer,
                                                                      eventBus,
                                                                      true);

        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setJsonSerializer(jsonSerializer)
                                      .setUseCentralizedMessageFetcher(useCentralizedMessageFetcher())
                                      .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(pollingIntervalMillis))
                                      .setTransactionalMode(TransactionalMode.SingleOperationTransaction)
                                      .setMessageHandlingTimeout(Duration.ofMillis(getMessageHandlingTimeoutMs()))
                                      .setUseOrderedUnorderedQuery(true)
                                      .setQueuePollingOptimizerFactory(consumeFromQueue -> new SimpleQueuePollingOptimizer(consumeFromQueue,
                                                                                                                           (long) (consumeFromQueue.getPollingInterval().toMillis() * 0.5d),
                                                                                                                           maxPollingIntervalMillis
                                      ))
                                      .setCentralizedQueuePollingOptimizerFactory(queueName -> new CentralizedQueuePollingOptimizer(queueName,
                                                                                                                                    pollingIntervalMillis,
                                                                                                                                    maxPollingIntervalMillis,
                                                                                                                                    centralizedPollingDelayBackOffFactor,
                                                                                                                                    0.1
                                      ))
                                      .setMultiTableChangeListener(multiTableChangeListener)
                                      .build();
    }

    @Override
    protected JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        var ds = new HikariDataSource();
        ds.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        ds.setUsername(postgreSQLContainer.getUsername());
        ds.setPassword(postgreSQLContainer.getPassword());
        ds.setAutoCommit(false);
        ds.setMaximumPoolSize(PARALLEL_CONSUMERS * 2 + 10); // Extra connections for both instances

        return new JdbiUnitOfWorkFactory(Jdbi.create(ds));
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        unitOfWorkFactory.usingUnitOfWork(uow ->
                                                  uow.handle().execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
    }
}
