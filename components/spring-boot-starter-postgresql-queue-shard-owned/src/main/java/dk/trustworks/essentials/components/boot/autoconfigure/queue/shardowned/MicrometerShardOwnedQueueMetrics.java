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

package dk.trustworks.essentials.components.boot.autoconfigure.queue.shardowned;

import dk.trustworks.essentials.components.queue.shardowned.observability.micrometer.MicrometerQueueObserver;
import dk.trustworks.essentials.components.queue.shardowned.spi.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A {@link MicrometerQueueObserver} per queue, tagged with the queue's name.
 * <p>
 * The engine has shipped the observer all along, but nothing in the starter created one, so an
 * application with a {@code MeterRegistry} still got no queue metrics - no deliveries, retries, dead
 * letters or ownership changes - unless it wired the observer itself.
 * <p>
 * The registry is looked up when a queue is bound, not when this bean is created, so the binding
 * needs no ordering against the metrics auto-configuration: by the time the first queue is built the
 * registry exists or it never will. Where there is none, a queue is simply left unmetered.
 * <p>
 * The event meters are always bound; they are fed by events the engine already emits and cost an
 * increment. The depth and health gauges are opt-in, because a gauge is polled on every scrape and
 * each poll is a query - see {@link MicrometerQueueObserver#bindQueueDepth}.
 */
final class MicrometerShardOwnedQueueMetrics implements ShardOwnedQueueMetrics {
    private final ObjectProvider<MeterRegistry>     registries;
    private final ShardOwnedQueueProperties.Metrics properties;

    MicrometerShardOwnedQueueMetrics(ObjectProvider<MeterRegistry> registries, ShardOwnedQueueProperties.Metrics properties) {
        this.registries = requireNonNull(registries, "No registries provided");
        this.properties = requireNonNull(properties, "No properties provided");
    }

    @Override
    public void bind(QueueName queueName, MessageQueue queue) {
        var registry = registries.getIfAvailable();
        if (registry == null) {
            return;
        }
        var observer = new MicrometerQueueObserver(registry, queueName);
        queue.addObserver(observer);
        if (properties.isDepthGauges()) {
            observer.bindQueueDepth(queue, properties.getGaugeMaxAge());
        }
        if (properties.isHealthGauges()) {
            observer.bindQueueHealth(queue, properties.getGaugeMaxAge());
        }
    }
}
