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
import dk.trustworks.essentials.components.foundation.messaging.RedeliveryPolicy;
import org.springframework.beans.factory.annotation.Value;

/**
 * The base of the demo's projections: a {@link ViewEventProcessor} whose direct-handling batch size
 * ({@link ViewEventProcessor#getDirectHandlingBatchSize()}) is {@code trading-demo.views.direct-handling-batch-size} -
 * {@code 1} by default, event by event, and set higher by the {@code compose} profile, as the order flow's forwarding
 * batch is. Set by Spring before the processor starts.
 * <p>
 * The EF-2 measurement processor ({@code _demo_harness/NoOpAccountsViewProcessor}) stays on {@link ViewEventProcessor}:
 * it measures the event-by-event reader.
 * <p>
 * Retries with a {@link FailoverRedeliveryPolicy} instead of the processor's default (about 10 s): the demo's handlers
 * throw until another processor's work is there ("not yet"), and with the default such a message dead-letters during a
 * failover - one killed instance of two dead-lettered 438 settlement reports.
 */
public abstract class TradingDemoViewEventProcessor extends ViewEventProcessor {
    private int directHandlingBatchSize = 1;

    protected TradingDemoViewEventProcessor(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
    }

    @Override
    protected RedeliveryPolicy getDurableQueueRedeliveryPolicy() {
        return failoverRedeliveryPolicy(getDurableQueues().maxUnservedAfterInstanceLoss()).policy();
    }

    @Value("${trading-demo.views.direct-handling-batch-size:1}")
    void setDirectHandlingBatchSize(int directHandlingBatchSize) {
        this.directHandlingBatchSize = directHandlingBatchSize;
    }

    @Override
    protected int getDirectHandlingBatchSize() {
        return directHandlingBatchSize;
    }
}
