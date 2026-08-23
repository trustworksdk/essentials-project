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

import dk.trustworks.essentials.components.foundation.json.*;
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.DurableQueuesIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * The acceptance gate for the two-table split (S3): {@link DurableQueuesIT}, the shared cross-implementation
 * suite, run <b>unmodified</b> against {@link PostgresqlSplitDurableQueues}.
 * <p>
 * That the suite is untouched is the whole point. The split is a storage-layout change and nothing else — every
 * behaviour the SPI promises has to hold identically whether ordered and unordered messages share a table or not.
 * Anything this class had to relax would be a semantic difference the split introduced, so the file contains no
 * test methods of its own: only the wiring.
 * <p>
 * What the suite exercises that the split could plausibly break, and does not:
 * <ul>
 *     <li><b>By-id operations.</b> A {@link dk.trustworks.essentials.components.foundation.messaging.queue.QueueEntryId}
 *     carries no delivery mode, so acknowledge/retry/delete/dead-letter have to find their row in whichever of
 *     the two tables holds it.</li>
 *     <li><b>Mixed traffic through one consumer.</b> A single
 *     {@link dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedMessageFetcher} claims from
 *     both tables under one {@code parallelConsumers} budget, ordered table first, and must not hand out more
 *     than that budget.</li>
 *     <li><b>Counts and queries.</b> Both are merged across the two tables.</li>
 * </ul>
 */
@Testcontainers
class PostgresqlSplitDurableQueuesIT extends DurableQueuesIT<PostgresqlSplitDurableQueues, GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    private static final String BASE_TABLE_NAME = "split_durable_queues";

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("split-queue-db");

    @Override
    protected JSONSerializer createJSONSerializer() {
        return EssentialsObjectMappers.createJSONSerializer();
    }

    @Override
    protected PostgresqlSplitDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory,
                                                               JSONSerializer jsonSerializer) {
        return PostgresqlSplitDurableQueues.builder()
                                           .setUnitOfWorkFactory(unitOfWorkFactory)
                                           .setJsonSerializer(jsonSerializer)
                                           .setBaseQueueTableName(BASE_TABLE_NAME)
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
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE_NAME + PostgresqlSplitDurableQueues.UNORDERED_TABLE_SUFFIX);
            uow.handle().execute("DROP TABLE IF EXISTS " + BASE_TABLE_NAME + PostgresqlSplitDurableQueues.ORDERED_TABLE_SUFFIX);
        });
    }
}
