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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.foundation.types.*;

import java.util.Optional;
import java.util.function.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link EventStoreSubscriptionContext}, obtained from {@link EventStoreSubscriptionContext#builder()}.
 */
public final class EventStoreSubscriptionContextBuilder {
    private EventStore                                   eventStore;
    private AggregateType                                aggregateType;
    private SubscriberId                                 subscriberId;
    private Tenant                                       onlyIncludeEventsForTenant;
    private EventStoreSubscriptionObserver               eventStoreSubscriptionObserver;
    private Consumer<EventStoreSubscription>             unsubscribeCallback;
    private Function<String, EventStorePollingOptimizer> eventStorePollingOptimizerFactory;

    /**
     * @param eventStore the event store to subscribe to. Required
     * @return this builder instance for fluent chaining
     */
    public EventStoreSubscriptionContextBuilder setEventStore(EventStore eventStore) {
        this.eventStore = eventStore;
        return this;
    }

    /**
     * @param aggregateType the aggregate type whose event stream is subscribed to. Required
     * @return this builder instance for fluent chaining
     */
    public EventStoreSubscriptionContextBuilder setAggregateType(AggregateType aggregateType) {
        this.aggregateType = aggregateType;
        return this;
    }

    /**
     * @param subscriberId the durable identity of this subscriber. Required
     * @return this builder instance for fluent chaining
     */
    public EventStoreSubscriptionContextBuilder setSubscriberId(SubscriberId subscriberId) {
        this.subscriberId = subscriberId;
        return this;
    }

    /**
     * @param onlyIncludeEventsForTenant restrict the subscription to one tenant, or {@code null} for all tenants
     * @return this builder instance for fluent chaining
     */
    public EventStoreSubscriptionContextBuilder setOnlyIncludeEventsForTenant(Tenant onlyIncludeEventsForTenant) {
        this.onlyIncludeEventsForTenant = onlyIncludeEventsForTenant;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setOnlyIncludeEventsForTenant(Tenant)}. An empty {@code Optional} means
     * "all tenants".
     *
     * @param onlyIncludeEventsForTenant the tenant restriction, or empty for all tenants
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public EventStoreSubscriptionContextBuilder setOnlyIncludeEventsForTenant(Optional<Tenant> onlyIncludeEventsForTenant) {
        requireNonNull(onlyIncludeEventsForTenant, "No onlyIncludeEventsForTenant provided");
        return setOnlyIncludeEventsForTenant(onlyIncludeEventsForTenant.orElse(null));
    }

    /**
     * @param eventStoreSubscriptionObserver observability hook for the subscription lifecycle. Required
     * @return this builder instance for fluent chaining
     */
    public EventStoreSubscriptionContextBuilder setEventStoreSubscriptionObserver(EventStoreSubscriptionObserver eventStoreSubscriptionObserver) {
        this.eventStoreSubscriptionObserver = eventStoreSubscriptionObserver;
        return this;
    }

    /**
     * @param unsubscribeCallback invoked when the subscription unsubscribes. Required
     * @return this builder instance for fluent chaining
     */
    public EventStoreSubscriptionContextBuilder setUnsubscribeCallback(Consumer<EventStoreSubscription> unsubscribeCallback) {
        this.unsubscribeCallback = unsubscribeCallback;
        return this;
    }

    /**
     * @param eventStorePollingOptimizerFactory creates the polling optimizer for a given subscription. Required
     * @return this builder instance for fluent chaining
     */
    public EventStoreSubscriptionContextBuilder setEventStorePollingOptimizerFactory(Function<String, EventStorePollingOptimizer> eventStorePollingOptimizerFactory) {
        this.eventStorePollingOptimizerFactory = eventStorePollingOptimizerFactory;
        return this;
    }

    /**
     * @return the new {@link EventStoreSubscriptionContext}
     */
    public EventStoreSubscriptionContext build() {
        return new EventStoreSubscriptionContext(eventStore,
                                                 aggregateType,
                                                 subscriberId,
                                                 onlyIncludeEventsForTenant,
                                                 eventStoreSubscriptionObserver,
                                                 unsubscribeCallback,
                                                 eventStorePollingOptimizerFactory);
    }
}
