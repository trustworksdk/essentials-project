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
 * The shared cross-implementation suite run with the <b>per-key cursor</b> enabled.
 * <p>
 * The cursor replaces the ordering barrier — the mechanism that makes ordered delivery correct — so the bar for
 * shipping it even as an opt-in flag is that the whole suite passes unmodified against it, exactly as for the
 * two-table split. This class is wiring only, for the same reason: anything it had to relax would be a semantic
 * difference the cursor introduced, and per-key ordering is not a thing to be approximately right about.
 * <p>
 * The corrected design's two faults were both found this way rather than by reading — the original prototype
 * released a key's successor while its predecessor was in flight, and its acknowledgement advanced past a
 * dead-lettered message and lost it permanently. Neither surfaced in a single-connection prototype where claim
 * and acknowledge strictly alternate; both are ordinary for a real consumer.
 */
@Testcontainers
class CursorPostgresqlDurableQueuesIT extends DurableQueuesIT<PostgresqlDurableQueues, GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("cursor-queue-db");

    @Override
    protected JSONSerializer createJSONSerializer() {
        return EssentialsObjectMappers.createJSONSerializer();
    }

    @Override
    protected PostgresqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory,
                                                          JSONSerializer jsonSerializer) {
        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setJsonSerializer(jsonSerializer)
                                      .setUseOrderedMessageCursor(true)
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
            uow.handle().execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME + "_key_cursor");
            uow.handle().execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME);
        });
    }
}
