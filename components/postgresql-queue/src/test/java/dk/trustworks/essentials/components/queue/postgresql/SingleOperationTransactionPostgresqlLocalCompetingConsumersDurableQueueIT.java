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
import dk.trustworks.essentials.components.foundation.messaging.queue.TransactionalMode;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.LocalCompetingConsumersDurableQueueIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.time.Duration;

/**
 * Base class for single operation transaction local competing consumers tests
 */
@Testcontainers
abstract class SingleOperationTransactionPostgresqlLocalCompetingConsumersDurableQueueIT extends LocalCompetingConsumersDurableQueueIT<PostgresqlDurableQueues, GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {
    @Container
    protected final PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("queue-db")
            .withUsername("test-user")
            .withPassword("secret-password");

    /**
     * Determine whether to use the centralized message fetcher
     * @return true for centralized message fetcher, false for traditional consumer
     */
    protected abstract boolean useCentralizedMessageFetcher();

    @Override
    protected PostgresqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setMessageHandlingTimeout(Duration.ofSeconds(5))
                                      .setTransactionalMode(TransactionalMode.SingleOperationTransaction)
                                      .setUseCentralizedMessageFetcher(useCentralizedMessageFetcher())
                                      .build();
    }

    @Override
    protected JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        var ds = new HikariDataSource();
        ds.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        ds.setUsername(postgreSQLContainer.getUsername());
        ds.setPassword(postgreSQLContainer.getPassword());
        ds.setAutoCommit(false);
        ds.setMaximumPoolSize(PARALLEL_CONSUMERS);

        return new JdbiUnitOfWorkFactory(Jdbi.create(ds));
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
    }
}