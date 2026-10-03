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

import dk.trustworks.essentials.components.queue.shardowned.spi.*;

/**
 * Binds metrics to each queue {@link ShardOwnedQueueFactory} builds, before anything consumes from it.
 * <p>
 * Per queue rather than one {@code QueueObserver} bean shared by all of them, because a metric is only
 * useful if it says which queue it is about: a shared observer would count every queue's deliveries
 * into one series. The factory calls every binding of this type in the context once per queue.
 * <p>
 * A type of this starter's own, so the factory's signature names no Micrometer class. Micrometer is
 * an optional dependency here, and the binding that uses it is only defined when it is on the
 * classpath; see {@code ShardOwnedQueueAutoConfiguration.MicrometerMetricsConfiguration}.
 */
@FunctionalInterface
public interface ShardOwnedQueueMetrics {
    /**
     * @param queueName the name the queue was built under, which is what its meters are tagged with
     * @param queue     the queue, not yet consuming
     */
    void bind(QueueName queueName, MessageQueue queue);
}
