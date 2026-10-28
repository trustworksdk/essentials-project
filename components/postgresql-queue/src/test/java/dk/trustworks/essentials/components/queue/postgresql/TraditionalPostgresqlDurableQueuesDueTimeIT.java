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

import dk.trustworks.essentials.components.foundation.messaging.queue.SimpleQueuePollingOptimizer;
import dk.trustworks.essentials.components.foundation.messaging.queue.operations.ConsumeFromQueueBuilder;

/**
 * {@link PostgresqlDurableQueuesDueTimeIT} with each consumer polling on its own
 * ({@code useCentralizedMessageFetcher(false)}) through a {@link SimpleQueuePollingOptimizer}
 */
class TraditionalPostgresqlDurableQueuesDueTimeIT extends PostgresqlDurableQueuesDueTimeIT {
    /**
     * Two empty polls take the backoff to its cap
     */
    @Override
    protected PostgresqlDurableQueuesBuilder configurePolling(PostgresqlDurableQueuesBuilder builder) {
        return builder.setUseCentralizedMessageFetcher(false)
                      .setQueuePollingOptimizerFactory(consumeFromQueue -> new SimpleQueuePollingOptimizer(consumeFromQueue, MAX_BACKOFF_MS / 2, MAX_BACKOFF_MS));
    }

    @Override
    protected ConsumeFromQueueBuilder configurePolling(ConsumeFromQueueBuilder builder) {
        return builder.setPollingInterval(POLLING_INTERVAL);
    }
}
