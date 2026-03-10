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

import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.jdbc.test.AbstractDurableQueuesPerformanceIT;
import dk.trustworks.essentials.components.queue.jdbc.test.DurableQueuesTestSupport;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class PostgresqlDurableQueuesPerformanceIT extends AbstractDurableQueuesPerformanceIT<PostgresqlDurableQueues> {

    @Container
    static PostgreSQLContainer<?> postgreSQLContainer = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test")
            .withPassword("test")
            .withUsername("test");

    protected abstract boolean useCentralizedMessageFetcher();

    protected abstract boolean useOrderedUnorderedQuery();

    @Override
    protected PostgresqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setQueuePollingOptimizerFactory(DurableQueuesTestSupport.defaultQueuePollingOptimizerFactory())
                                      .setMultiTableChangeListener(DurableQueuesTestSupport.defaultMultiTableChangeListener(unitOfWorkFactory.getJdbi()))
                                      .setUseCentralizedMessageFetcher(useCentralizedMessageFetcher())
                                      .setCentralizedMessageFetcherPollingInterval(consumerPollInterval())
                                      .setUseOrderedUnorderedQuery(useOrderedUnorderedQuery())
                                      .build();
    }

    @Override
    protected JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        return new JdbiUnitOfWorkFactory(Jdbi.create(postgreSQLContainer.getJdbcUrl(),
                                                     postgreSQLContainer.getUsername(),
                                                     postgreSQLContainer.getPassword()));
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        DurableQueuesTestSupport.dropQueueTable(unitOfWorkFactory, PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME);
    }
}
