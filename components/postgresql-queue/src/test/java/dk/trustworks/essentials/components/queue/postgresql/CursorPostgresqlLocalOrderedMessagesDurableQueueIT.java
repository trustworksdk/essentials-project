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
import dk.trustworks.essentials.components.foundation.test.EssentialsTestContainers;
import dk.trustworks.essentials.components.foundation.test.messaging.queue.LocalOrderedMessagesDurableQueueIT;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.GenericHandleAwareUnitOfWorkFactory.GenericHandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

/**
 * Per-key ordering under real concurrency, with the cursor enabled — 2 000 ordered messages across 20 parallel
 * consumers.
 *
 * <h2>Why this is the gate that matters for the cursor</h2>
 * {@code CursorPostgresqlDurableQueuesIT} runs the whole SPI but with modest concurrency. The fault that sank the
 * first cursor prototype was invisible without contention: filtering {@code is_being_delivered = FALSE} inside the
 * per-key lookup returns a key's <em>next-but-one</em> while its head is in flight, so two worker threads on one
 * node are enough to deliver a key out of order. A single-connection prototype where claim and acknowledge
 * strictly alternate can never see it.
 * <p>
 * This suite is exactly that contention, and it asserts per-key ordering directly — so it is the test that
 * distinguishes "the cursor works" from "the cursor works when nothing else is happening".
 */
@Testcontainers
class CursorPostgresqlLocalOrderedMessagesDurableQueueIT extends LocalOrderedMessagesDurableQueueIT<PostgresqlDurableQueues, GenericHandleAwareUnitOfWork, JdbiUnitOfWorkFactory> {

    @Container
    private static final PostgreSQLContainer<?> postgreSQLContainer = EssentialsTestContainers.postgres("cursor-ordered-queue-db");

    private HikariDataSource dataSource;

    @Override
    protected PostgresqlDurableQueues createDurableQueues(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        return PostgresqlDurableQueues.builder()
                                      .setUnitOfWorkFactory(unitOfWorkFactory)
                                      .setUseCentralizedMessageFetcher(true)
                                      .setUseOrderedMessageCursor(true)
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
        return new JdbiUnitOfWorkFactory(Jdbi.create(dataSource));
    }

    @Override
    protected void resetQueueStorage(JdbiUnitOfWorkFactory unitOfWorkFactory) {
        unitOfWorkFactory.usingUnitOfWork(uow -> {
            uow.handle().execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME + "_key_cursor");
            uow.handle().execute("DROP TABLE IF EXISTS " + PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME);
        });
    }

    @Override
    protected void releaseTestResources() {
        if (dataSource != null) {
            dataSource.close();
            dataSource = null;
        }
    }
}
