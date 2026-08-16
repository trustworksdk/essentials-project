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
 * The collaborators every {@link DurableQueueConsumer} implementation needs, bundled so that a consumer's constructor
 * takes "what to consume" and "what to consume it with" rather than seven positional arguments.
 * <p>
 * This is deliberately shared across the queue implementations — {@code PostgresqlDurableQueueConsumer} and
 * {@code MongoDurableQueueConsumer} both extend {@link DefaultDurableQueueConsumer} and both were repeating the same
 * parameter list. Adding a collaborator now means one field here instead of a new constructor in every implementation.
 *
 * @param <DURABLE_QUEUES> the concrete {@link DurableQueues} type
 * @param <UOW>            the {@link UnitOfWork} type
 * @param <UOW_FACTORY>    the {@link UnitOfWorkFactory} type
 */
public final class DurableQueueConsumerDependencies<DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>> {
    private final UOW_FACTORY                     unitOfWorkFactory;
    private final DURABLE_QUEUES                  durableQueues;
    private final Consumer<DurableQueueConsumer>  removeDurableQueueConsumer;
    private final long                            pollingIntervalMs;
    private final QueuePollingOptimizer           queuePollingOptimizer;
    private final List<DurableQueuesInterceptor>  interceptors;

    DurableQueueConsumerDependencies(UOW_FACTORY unitOfWorkFactory,
                                     DURABLE_QUEUES durableQueues,
                                     Consumer<DurableQueueConsumer> removeDurableQueueConsumer,
                                     long pollingIntervalMs,
                                     QueuePollingOptimizer queuePollingOptimizer,
                                     List<DurableQueuesInterceptor> interceptors) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        this.durableQueues = durableQueues;
        this.removeDurableQueueConsumer = removeDurableQueueConsumer;
        this.pollingIntervalMs = pollingIntervalMs;
        this.queuePollingOptimizer = queuePollingOptimizer;
        this.interceptors = interceptors;
    }

    /**
     * Creates a new builder.
     *
     * @param <DURABLE_QUEUES> the concrete {@link DurableQueues} type
     * @param <UOW>            the {@link UnitOfWork} type
     * @param <UOW_FACTORY>    the {@link UnitOfWorkFactory} type
     * @return a new builder
     */
    public static <DURABLE_QUEUES extends DurableQueues, UOW extends UnitOfWork, UOW_FACTORY extends UnitOfWorkFactory<UOW>>
    DurableQueueConsumerDependenciesBuilder<DURABLE_QUEUES, UOW, UOW_FACTORY> builder() {
        return new DurableQueueConsumerDependenciesBuilder<>();
    }

    /**
     * @return the {@link UnitOfWorkFactory}. May be {@code null} when the {@link DurableQueues} implementation runs in
     *         {@link TransactionalMode#SingleOperationTransaction}, where no caller-visible unit of work exists
     */
    public UOW_FACTORY unitOfWorkFactory() {
        return unitOfWorkFactory;
    }

    /**
     * @return the {@link DurableQueues} instance the consumer belongs to. Required
     */
    public DURABLE_QUEUES durableQueues() {
        return durableQueues;
    }

    /**
     * @return callback invoked when the consumer stops, so the owning {@link DurableQueues} can forget it. Required
     */
    public Consumer<DurableQueueConsumer> removeDurableQueueConsumer() {
        return removeDurableQueueConsumer;
    }

    /**
     * @return how often the consumer polls for new messages, in milliseconds
     */
    public long pollingIntervalMs() {
        return pollingIntervalMs;
    }

    /**
     * @return the polling optimizer. Never {@code null} — defaults to {@link QueuePollingOptimizer#None()}
     */
    public QueuePollingOptimizer queuePollingOptimizer() {
        return queuePollingOptimizer;
    }

    /**
     * @return the interceptor chain applied to every queue operation. Never {@code null}
     */
    public List<DurableQueuesInterceptor> interceptors() {
        return interceptors;
    }
}
