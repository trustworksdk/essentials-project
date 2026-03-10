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

import dk.trustworks.essentials.components.foundation.json.JSONSerializer;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.components.foundation.ttl.TTLJob;
import dk.trustworks.essentials.components.queue.jdbc.JdbcDurableQueuesStatistics;

@TTLJob(name = "durable_queues_statistics_ttl",
        enabledProperty = "essentials.durable-queues.enable-queue-statistics-ttl",
        tableNameProperty = "essentials.durable-queues.shared-queue-statistics-table-name",
        timestampColumn = "deletion_ts",
        cronExpression = "0 0 * * *",
        ttlDurationProperty = "essentials.durable-queues.queue-statistics-ttl-duration"
)
public class PostgresqlDurableQueuesStatistics extends JdbcDurableQueuesStatistics {
    public static final String DEFAULT_DURABLE_QUEUES_TABLE_NAME = JdbcDurableQueuesStatistics.DEFAULT_DURABLE_QUEUES_TABLE_NAME;

    public PostgresqlDurableQueuesStatistics(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                             String durableQueueTableName) {
        super(unitOfWorkFactory, durableQueueTableName);
    }

    public PostgresqlDurableQueuesStatistics(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                             JSONSerializer jsonSerializer,
                                             String durableQueueTableName,
                                             String statsQueueTableName) {
        super(unitOfWorkFactory, jsonSerializer, durableQueueTableName, statsQueueTableName);
    }
}
