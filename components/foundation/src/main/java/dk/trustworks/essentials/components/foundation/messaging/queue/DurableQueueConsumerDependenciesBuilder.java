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

package dk.trustworks.essentials.components.foundation.messaging.queue;

import dk.trustworks.essentials.components.foundation.transaction.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * Builder for {@link DurableQueueConsumerDependencies}, obtained from
 * {@link DurableQueueConsumerDependencies#builder()}.
 * <p>
 * {@code queuePollingOptimizer} and {@code interceptors} are held as plain nullable fields and resolved in
 * {@link #build()} to {@link QueuePollingOptimizer#None()} and an empty list — the same neutral defaults
 * {@link DefaultDurableQueueConsumer} used to apply inline.
 *
 * @param <DURABLE_QUEUES> the concrete {@link DurableQueues} type
 * @param <UOW>            the {@link UnitOfWork} type
 * @param <UOW_FACTORY>    the {@link UnitOfWorkFactory} type
 */
public final class DurableQueueConsumerDependenciesBuilder<DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>> {
    private UOW_FACTORY                    unitOfWorkFactory;
    private DURABLE_QUEUES                 durableQueues;
    private Consumer<DurableQueueConsumer> removeDurableQueueConsumer;
    private long                           pollingIntervalMs;
    private QueuePollingOptimizer          queuePollingOptimizer;
    private List<DurableQueuesInterceptor> interceptors;

    /**
     * @param unitOfWorkFactory the {@link UnitOfWorkFactory}. Only required when the {@link DurableQueues} implementation runs in
     *                          {@link TransactionalMode#FullyTransactional}; may be {@code null} otherwise
     * @return this builder instance for fluent chaining
     */
    public DurableQueueConsumerDependenciesBuilder<DURABLE_QUEUES, UOW, UOW_FACTORY> setUnitOfWorkFactory(UOW_FACTORY unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        return this;
    }

    /**
     * @param durableQueues the {@link DurableQueues} instance the consumer belongs to. Required
     * @return this builder instance for fluent chaining
     */
    public DurableQueueConsumerDependenciesBuilder<DURABLE_QUEUES, UOW, UOW_FACTORY> setDurableQueues(DURABLE_QUEUES durableQueues) {
        this.durableQueues = durableQueues;
        return this;
    }

    /**
     * @param removeDurableQueueConsumer callback invoked when the consumer stops. Required
     * @return this builder instance for fluent chaining
     */
    public DurableQueueConsumerDependenciesBuilder<DURABLE_QUEUES, UOW, UOW_FACTORY> setRemoveDurableQueueConsumer(Consumer<DurableQueueConsumer> removeDurableQueueConsumer) {
        this.removeDurableQueueConsumer = removeDurableQueueConsumer;
        return this;
    }

    /**
     * @param pollingIntervalMs how often the consumer polls for new messages, in milliseconds
     * @return this builder instance for fluent chaining
     */
    public DurableQueueConsumerDependenciesBuilder<DURABLE_QUEUES, UOW, UOW_FACTORY> setPollingIntervalMs(long pollingIntervalMs) {
        this.pollingIntervalMs = pollingIntervalMs;
        return this;
    }

    /**
     * @param queuePollingOptimizer the polling optimizer, or {@code null} for {@link QueuePollingOptimizer#None()}
     * @return this builder instance for fluent chaining
     */
    public DurableQueueConsumerDependenciesBuilder<DURABLE_QUEUES, UOW, UOW_FACTORY> setQueuePollingOptimizer(QueuePollingOptimizer queuePollingOptimizer) {
        this.queuePollingOptimizer = queuePollingOptimizer;
        return this;
    }

    /**
     * @param interceptors the interceptor chain applied to every queue operation, or {@code null} for none
     * @return this builder instance for fluent chaining
     */
    public DurableQueueConsumerDependenciesBuilder<DURABLE_QUEUES, UOW, UOW_FACTORY> setInterceptors(List<DurableQueuesInterceptor> interceptors) {
        this.interceptors = interceptors;
        return this;
    }

    /**
     * @return the new {@link DurableQueueConsumerDependencies}, with the neutral defaults applied
     */
    public DurableQueueConsumerDependencies<DURABLE_QUEUES, UOW, UOW_FACTORY> build() {
        return new DurableQueueConsumerDependencies<>(unitOfWorkFactory,
                                                      durableQueues,
                                                      removeDurableQueueConsumer,
                                                      pollingIntervalMs,
                                                      queuePollingOptimizer != null ? queuePollingOptimizer : QueuePollingOptimizer.None(),
                                                      interceptors != null ? interceptors : List.of());
    }
}
