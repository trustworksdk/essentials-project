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

package dk.trustworks.essentials.components.queue.postgresql.benchmark;

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers;
import dk.trustworks.essentials.components.foundation.messaging.queue.CentralizedQueuePollingOptimizer;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.components.queue.postgresql.PostgresqlDurableQueues;
import dk.trustworks.essentials.reactive.LocalEventBus;
import org.jdbi.v3.core.Jdbi;

import java.time.Duration;

/**
 * A {@link PostgresqlDurableQueues} wired the way the Spring Boot starter wires it by default: a
 * {@link MultiTableChangeListener} polled every 50 ms, and a {@link CentralizedQueuePollingOptimizer} that starts at
 * the 20 ms polling interval, grows ×1.5 per empty poll and is capped at 2 s. Configured explicitly rather than taken
 * from builder defaults, so a benchmark measures the same wiring on any release - what a Spring application runs.
 */
final class SpringWiredDurableQueues implements AutoCloseable {
    final PostgresqlDurableQueues                           durableQueues;
    private final MultiTableChangeListener<TableChangeNotification> listener;

    SpringWiredDurableQueues(Jdbi jdbi, JdbiUnitOfWorkFactory unitOfWorkFactory) {
        this(jdbi, unitOfWorkFactory, PostgresqlDurableQueues.DEFAULT_DURABLE_QUEUES_TABLE_NAME);
    }

    SpringWiredDurableQueues(Jdbi jdbi, JdbiUnitOfWorkFactory unitOfWorkFactory, String sharedQueueTableName) {
        var serializer = EssentialsObjectMappers.createJSONSerializer();
        listener = new MultiTableChangeListener<>(jdbi, Duration.ofMillis(50), serializer, new LocalEventBus("benchmark-change-listener"), true);
        listener.start();
        durableQueues = PostgresqlDurableQueues.builder()
                                               .setUnitOfWorkFactory(unitOfWorkFactory)
                                               .setJsonSerializer(serializer)
                                               .setSharedQueueTableName(sharedQueueTableName)
                                               .setMultiTableChangeListener(listener)
                                               .setCentralizedMessageFetcherPollingInterval(Duration.ofMillis(20))
                                               .setCentralizedQueuePollingOptimizerFactory(queueName -> new CentralizedQueuePollingOptimizer(queueName, 20, 2_000, 1.5, 0.1))
                                               .build();
        durableQueues.start();
    }

    @Override
    public void close() {
        durableQueues.stop();
        listener.stop();
    }
}
