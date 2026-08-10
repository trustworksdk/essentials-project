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

import com.zaxxer.hikari.HikariDataSource;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.LocalOrderedMessagesRedeliveryDurableQueueIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Base class for local ordered messages redelivery tests
 */
@Testcontainers
abstract class PostgresqlLocalOrderedMessagesRedeliveryDurableQueueIT extends LocalOrderedMessagesRedeliveryDurableQueueIT<PostgresqlDurableQueues, GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {
    @Container
    protected static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("queue-db");

    private HikariDataSource dataSource;

    /**
     * Determine whether to use the centralized message fetcher
     * @return true for centralized message fetcher, false for traditional consumer
     */
    protected abstract boolean useCentralizedMessageFetcher();

    @Override
    protected PostgresqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return PostgresqlDurableQueues.builder()
                                     .setUnitOfWorkFactory(unitOfWorkFactory)
                                     .setUseCentralizedMessageFetcher(useCentralizedMessageFetcher())
                                     .build();
    }

    @Override
    protected JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgreSQLContainer.getJdbcUrl());
        dataSource.setUsername(postgreSQLContainer.getUsername());
        dataSource.setPassword(postgreSQLContainer.getPassword());
        dataSource.setAutoCommit(false);
        dataSource.setMaximumPoolSize(PARALLEL_CONSUMERS);

        var jdbi = Jdbi.create(dataSource);
        return new JdbiUnitOfWorkFactory(jdbi);
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
    }


    /**
     * The container is shared by every test in this class, so the pool created per test method has to be closed
     * again - otherwise PostgreSQL runs out of connections part-way through the class.
     */
    @Override
    protected void releaseTestResources() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }

}