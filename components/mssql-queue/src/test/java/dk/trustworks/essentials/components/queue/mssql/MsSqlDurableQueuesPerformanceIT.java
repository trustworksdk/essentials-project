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

import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.jdbc.test.AbstractDurableQueuesPerformanceIT;
import dk.trustworks.essentials.components.queue.jdbc.test.DurableQueuesTestSupport;
import dk.trustworks.essentials.components.queue.mssql.MsSqlDurableQueues;
import dk.trustworks.essentials.types.jdbi.mssql.JavaTimeSupport;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class MsSqlDurableQueuesPerformanceIT extends AbstractDurableQueuesPerformanceIT<MsSqlDurableQueues> {

    @Container
    static MSSQLServerContainer<?> msSQLContainer = dk.trustworks.essentials.components.queue.mssql.MsSqlTestContainerSupport.createMsSqlContainer();

    protected abstract boolean useCentralizedMessageFetcher();

    protected abstract boolean useOrderedUnorderedQuery();

    @Override
    protected MsSqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return MsSqlDurableQueues.builder()
                                 .setUnitOfWorkFactory(unitOfWorkFactory)
                                 .setQueuePollingOptimizerFactory(DurableQueuesTestSupport.defaultQueuePollingOptimizerFactory())
                                 .setUseCentralizedMessageFetcher(useCentralizedMessageFetcher())
                                 .setCentralizedMessageFetcherPollingInterval(consumerPollInterval())
                                 .setUseOrderedUnorderedQuery(useOrderedUnorderedQuery())
                                 .build();
    }

    @Override
    protected JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        var jdbi = Jdbi.create(msSQLContainer.getJdbcUrl(),
                               msSQLContainer.getUsername(),
                               msSQLContainer.getPassword());
        JavaTimeSupport.install(jdbi);
        return new JdbiUnitOfWorkFactory(jdbi);
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        DurableQueuesTestSupport.dropQueueTable(unitOfWorkFactory, MsSqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME);
    }
}
