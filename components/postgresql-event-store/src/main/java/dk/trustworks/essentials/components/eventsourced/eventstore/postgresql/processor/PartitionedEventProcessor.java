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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.*;
import dk.trustworks.essentials.components.foundation.reactive.command.DurableLocalCommandBus;

import java.util.List;

/**
 * An {@link EventProcessor} whose {@code @MessageHandler}s run on <b>every</b> instance, each instance handling its share
 * of the aggregates, instead of on the one instance that holds the {@link Inbox}'s lock.
 * <p>
 * <b>What it does:</b> one instance reads the events (the exclusive subscription, as in {@link EventProcessor}) and
 * forwards them to the processor's {@link Inbox} in batches ({@link #getForwardBatchSize()} defaults to
 * {@value #DEFAULT_FORWARD_BATCH_SIZE}). Every instance consumes the {@link Inbox}
 * ({@link MessageConsumptionMode#GlobalCompetingConsumers}); the queue implementation gives each aggregate to one instance
 * at a time, so one aggregate's events are still handled one at a time, in order.
 * <p>
 * <b>What it does not do:</b> keep order <i>across</i> aggregates; or start on a queue implementation that keeps an
 * ordered key in order per node only ({@link Inboxes#keepsKeyOrderAcrossInstances()} {@code false}, e.g.
 * {@code PostgresqlDurableQueues}): {@link #start()} refuses, as competing consumers there could handle one aggregate's
 * events out of order on two instances. Use the shard-owned queue engine
 * ({@code essentials.shard-owned-queue.durable-queues-enabled=true}) - in every environment the processor runs in, tests
 * included.
 * <p>
 * <b>Be aware:</b>
 * <ul>
 *     <li>Handlers run on several instances at once, for different aggregates. Not for a handler that keeps in-memory
 *     state across aggregates, or calls something that accepts one caller only - use {@link EventProcessor} for that.</li>
 *     <li>Handlers of different aggregates that write one shared stream conflict on it more often; the {@link Inbox}
 *     redelivers them.</li>
 *     <li>Handlers must be idempotent: a failover can deliver a message twice (true of every processor, more likely here).</li>
 * </ul>
 * The consumption mode is fixed ({@link #getInboxMessageConsumptionMode()} is final): the contract is the class.
 */
public abstract class PartitionedEventProcessor extends EventProcessor {
    /**
     * The default {@link #getForwardBatchSize()}: forwarding pays per batch, not per event, and only pays together with
     * more consumers - which a partitioned processor has by running on every instance.
     */
    public static final int DEFAULT_FORWARD_BATCH_SIZE = 100;

    /**
     * @param eventProcessorDependencies the dependencies of an {@link EventProcessor}
     */
    protected PartitionedEventProcessor(EventProcessorDependencies eventProcessorDependencies) {
        super(eventProcessorDependencies);
    }

    /**
     * @see EventProcessor#EventProcessor(EventStoreSubscriptionManager, Inboxes, DurableLocalCommandBus, List)
     */
    protected PartitionedEventProcessor(EventStoreSubscriptionManager eventStoreSubscriptionManager,
                                        Inboxes inboxes,
                                        DurableLocalCommandBus commandBus,
                                        List<MessageHandlerInterceptor> messageHandlerInterceptors) {
        super(eventStoreSubscriptionManager, inboxes, commandBus, messageHandlerInterceptors);
    }

    /**
     * Always {@link MessageConsumptionMode#GlobalCompetingConsumers}: every instance handles the {@link Inbox}.
     */
    @Override
    protected final MessageConsumptionMode getInboxMessageConsumptionMode() {
        return MessageConsumptionMode.GlobalCompetingConsumers;
    }

    /**
     * Default {@value #DEFAULT_FORWARD_BATCH_SIZE}; override to tune.
     */
    @Override
    protected int getForwardBatchSize() {
        return DEFAULT_FORWARD_BATCH_SIZE;
    }
}
