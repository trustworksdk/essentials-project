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
package dk.trustworks.essentials.examples.trading.config;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.MessageConsumptionMode;

/**
 * The base of the demo's {@link EventProcessor}s: every instance handles the processor's inbox when the queues keep an
 * ordered key in order across instances - the shard-owned engine the {@code compose} profile runs - and one instance
 * handles it otherwise, as on {@code PostgresqlDurableQueues}, which the tests use and the A/B switch
 * ({@code essentials.shard-owned-queue.durable-queues-enabled=false}) selects.
 * <p>
 * <b>A test aid, not a pattern to copy.</b> It lets the same processors run on both engines. An application picks the
 * contract in its code instead: {@link PartitionedEventProcessor} (every instance, refuses to start on an engine that
 * cannot keep the order) or {@link EventProcessor} (one instance) - see {@code docs/event-processor-specializations.md}.
 */
public abstract class TradingDemoEventProcessor extends EventProcessor {
    private final boolean keepsKeyOrderAcrossInstances;

    protected TradingDemoEventProcessor(EventProcessorDependencies dependencies) {
        super(dependencies);
        this.keepsKeyOrderAcrossInstances = dependencies.inboxes.keepsKeyOrderAcrossInstances();
    }

    @Override
    protected final MessageConsumptionMode getInboxMessageConsumptionMode() {
        return keepsKeyOrderAcrossInstances ? MessageConsumptionMode.GlobalCompetingConsumers : MessageConsumptionMode.SingleGlobalConsumer;
    }
}
