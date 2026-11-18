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
 * The demo's {@link PartitionedViewEventProcessor}s, with the number of queue consumer threads per instance taken from
 * {@code trading-demo.views.partitioned-consumers} (default 1, the processor's own default). Every event of such a view
 * goes through its queue, so this - times the instances - is how many events of the view are handled at once.
 * <p>
 * Retries with a {@link FailoverRedeliveryPolicy} instead of the processor's default (about 10 s): the demo's handlers
 * throw until another processor's work is there ("not yet"), and with the default such a message dead-letters during a
 * failover - one killed instance of two dead-lettered 438 settlement reports.
 */
public abstract class TradingDemoPartitionedViewEventProcessor extends PartitionedViewEventProcessor {
    private int     consumers       = 1;
    private boolean forwardPayloads = false;

    protected TradingDemoPartitionedViewEventProcessor(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
    }

    @Override
    protected RedeliveryPolicy getDurableQueueRedeliveryPolicy() {
        return failoverRedeliveryPolicy(getDurableQueues().maxUnservedAfterInstanceLoss()).policy();
    }

    @Value("${trading-demo.views.partitioned-consumers:1}")
    void setConsumers(int consumers) {
        this.consumers = consumers;
    }

    @Value("${trading-demo.views.partitioned-forward-payloads:false}")
    void setForwardPayloads(boolean forwardPayloads) {
        this.forwardPayloads = forwardPayloads;
    }

    @Override
    protected boolean forwardsEventPayloads() {
        return forwardPayloads;
    }

    @Override
    protected int getNumberOfParallelQueuedMessageConsumers() {
        return consumers;
    }
}
