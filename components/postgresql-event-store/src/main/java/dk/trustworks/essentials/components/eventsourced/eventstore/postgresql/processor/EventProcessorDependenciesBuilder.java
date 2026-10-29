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
 * Builder for {@link EventProcessorDependencies}, which encapsulates all the dependencies
 * required by an instance of an {@link EventProcessor}
 */
public final class EventProcessorDependenciesBuilder {
    private EventStoreSubscriptionManager   eventStoreSubscriptionManager;
    private Inboxes                         inboxes;
    private DurableLocalCommandBus          commandBus;
    private List<MessageHandlerInterceptor> messageHandlerInterceptors = List.of();
    private boolean                         competingInboxConsumers;

    public EventProcessorDependenciesBuilder setEventStoreSubscriptionManager(EventStoreSubscriptionManager eventStoreSubscriptionManager) {
        this.eventStoreSubscriptionManager = eventStoreSubscriptionManager;
        return this;
    }

    public EventProcessorDependenciesBuilder setInboxes(Inboxes inboxes) {
        this.inboxes = inboxes;
        return this;
    }

    public EventProcessorDependenciesBuilder setCommandBus(DurableLocalCommandBus commandBus) {
        this.commandBus = commandBus;
        return this;
    }

    public EventProcessorDependenciesBuilder setMessageHandlerInterceptors(List<MessageHandlerInterceptor> messageHandlerInterceptors) {
        this.messageHandlerInterceptors = messageHandlerInterceptors;
        return this;
    }

    /**
     * Opt-in, default {@code false}. When {@code true}, every {@link EventProcessor} built with these dependencies that does
     * not override {@link EventProcessor#getInboxMessageConsumptionMode()} lets every instance handle its {@link Inbox}
     * ({@link MessageConsumptionMode#GlobalCompetingConsumers}) - but only where {@link Inboxes#keepsKeyOrderAcrossInstances()},
     * as on the shard-owned queue engine; elsewhere it stays on {@link MessageConsumptionMode#SingleGlobalConsumer} and logs
     * why at start. The processors' subscriptions stay exclusive either way.
     * <p>
     * Opt-in because the {@code @MessageHandler}s then run on every instance at once - for different aggregates - where they
     * used to run on one: a handler that relies on being alone in the cluster (in-memory state across aggregates, a
     * downstream that takes one caller) must not be switched over, and handlers of different aggregates that write one
     * shared stream conflict on it a little more often.
     *
     * @param competingInboxConsumers whether processors that leave the choice to these dependencies use competing inbox consumers where safe
     * @return this builder
     */
    public EventProcessorDependenciesBuilder setCompetingInboxConsumers(boolean competingInboxConsumers) {
        this.competingInboxConsumers = competingInboxConsumers;
        return this;
    }

    public EventProcessorDependencies build() {
        return new EventProcessorDependencies(eventStoreSubscriptionManager, inboxes, commandBus, messageHandlerInterceptors, competingInboxConsumers);
    }
}