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

import dk.trustworks.essentials.components.foundation.json.*;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.DurableQueuesIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.mssql.MsSqlDurableQueues;
import dk.trustworks.essentials.components.queue.postgresql.test_data.MsSqlGenericContainer;
import dk.trustworks.essentials.types.jdbi.mssql.JavaTimeSupport;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.junit.jupiter.Container;

import static dk.trustworks.essentials.components.queue.mssql.DurableQueuesSerialization.createDefaultObjectMapper;

/**
 * Base test class for MsSqlDurableQueues integration tests
 */
@Testcontainers
abstract class MsSqlDurableQueuesIT extends DurableQueuesIT<MsSqlDurableQueues, GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    @Container
    public static final MsSqlGenericContainer msSQLContainer = new MsSqlGenericContainer();

    public String jdbcUrl() {
        return String.format(
                "jdbc:sqlserver://%s:%d;encrypt=true;trustServerCertificate=true",
                msSQLContainer.getHost(), msSQLContainer.getMappedPort(1433));
    }

    /**
     * Determine whether to use the centralized message fetcher
     * @return true for centralized message fetcher, false for traditional consumer
     */
    protected abstract boolean useCentralizedMessageFetcher();

    @Override
    protected JSONSerializer createJSONSerializer() {
        return new JacksonJSONSerializer(createDefaultObjectMapper());
    }

    @Override
    protected MsSqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory,
                                                          JSONSerializer jsonSerializer) {
        return MsSqlDurableQueues.builder()
                .setUnitOfWorkFactory(unitOfWorkFactory)
                .setJsonSerializer(jsonSerializer)
                .setUseCentralizedMessageFetcher(useCentralizedMessageFetcher())
                .build();
    }

    @Override
    protected JdbiUnitOfWorkFactory createUnitOfWorkFactory() {
        var jdbi = Jdbi.create(jdbcUrl(),
                   msSQLContainer.getUsername(),
                   msSQLContainer.getPassword());
        JavaTimeSupport.install(jdbi);
        return new JdbiUnitOfWorkFactory(jdbi);
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + MsSqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME));
    }

}