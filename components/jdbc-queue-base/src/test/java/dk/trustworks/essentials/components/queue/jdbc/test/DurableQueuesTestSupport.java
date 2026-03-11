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

package dk.trustworks.essentials.components.queue.jdbc.test;

import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.trustworks.essentials.components.foundation.json.JacksonJSONSerializer;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueue;
import dk.trustworks.essentials.components.foundation.postgresql.*;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.JdbiUnitOfWorkFactory;
import dk.trustworks.essentials.jackson.types.EssentialTypesJacksonModule;
import dk.trustworks.essentials.reactive.LocalEventBus;
import org.jdbi.v3.core.Jdbi;

import java.time.Duration;
import java.util.function.Function;

import static dk.trustworks.essentials.jackson.immutable.EssentialsImmutableJacksonModule.createObjectMapper;

public final class DurableQueuesTestSupport {
    private DurableQueuesTestSupport() {
    }

    public static Function<ConsumeFromQueue, QueuePollingOptimizer> defaultQueuePollingOptimizerFactory() {
        return consumeFromQueue -> new SimpleQueuePollingOptimizer(consumeFromQueue, 100, 1000);
    }

    public static MultiTableChangeListener<TableChangeNotification> defaultMultiTableChangeListener(Jdbi jdbi) {
        return new MultiTableChangeListener<>(jdbi,
                                              Duration.ofMillis(100),
                                              new JacksonJSONSerializer(createObjectMapper(new Jdk8Module(),
                                                                                           new JavaTimeModule(),
                                                                                           new EssentialTypesJacksonModule())),
                                              LocalEventBus.builder().build(),
                                              true);
    }

    public static void dropQueueTable(JdbiUnitOfWorkFactory unitOfWorkFactory, String tableName) {
        unitOfWorkFactory.usingUnitOfWork(uow -> uow.handle().execute("DROP TABLE IF EXISTS " + tableName));
    }
}
