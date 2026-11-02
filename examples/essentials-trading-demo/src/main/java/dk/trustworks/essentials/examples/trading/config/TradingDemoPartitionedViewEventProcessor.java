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
import org.springframework.beans.factory.annotation.Value;

/**
 * The demo's {@link PartitionedViewEventProcessor}s, with the number of queue consumer threads per instance taken from
 * {@code trading-demo.views.partitioned-consumers} (default 1, the processor's own default). Every event of such a view
 * goes through its queue, so this - times the instances - is how many events of the view are handled at once.
 */
public abstract class TradingDemoPartitionedViewEventProcessor extends PartitionedViewEventProcessor {
    private int consumers = 1;

    protected TradingDemoPartitionedViewEventProcessor(ViewEventProcessorDependencies dependencies) {
        super(dependencies);
    }

    @Value("${trading-demo.views.partitioned-consumers:1}")
    void setConsumers(int consumers) {
        this.consumers = consumers;
    }

    @Override
    protected int getNumberOfParallelQueuedMessageConsumers() {
        return consumers;
    }
}
