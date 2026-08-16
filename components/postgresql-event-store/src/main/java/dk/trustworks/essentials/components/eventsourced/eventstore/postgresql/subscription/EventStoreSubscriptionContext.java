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
 * The seven arguments every {@link EventStoreSubscription} needs regardless of its kind — the ones
 * {@link AbstractEventStoreSubscription} consumes — gathered into one value.
 * <p>
 * These were repeated positionally in all five subscription constructors, which is what pushed the widest of them to
 * thirteen parameters. What stays <em>out</em> of this context is deliberate: each subscription's own required
 * collaborators (its event handler, its fenced-lock manager, its batching settings) remain explicit constructor
 * arguments, because those are exactly what distinguishes one subscription kind from another. Folding them in too
 * would make the five classes look interchangeable when they are not.
 *
 * @param eventStore                        the event store to subscribe to
 * @param aggregateType                     the aggregate type whose event stream is subscribed to
 * @param subscriberId                      the durable identity of this subscriber
 * @param onlyIncludeEventsForTenant        restrict the subscription to one tenant, or {@code null} for all tenants.
 *                                          Nullable rather than {@code Optional} — see {@link #onlyIncludeEventsForTenantOptional()}
 * @param eventStoreSubscriptionObserver    observability hook for the subscription lifecycle
 * @param unsubscribeCallback               invoked when the subscription unsubscribes, so the manager can forget it
 * @param eventStorePollingOptimizerFactory creates the polling optimizer for a given subscription
 */
public record EventStoreSubscriptionContext(EventStore eventStore,
                                            AggregateType aggregateType,
                                            SubscriberId subscriberId,
                                            Tenant onlyIncludeEventsForTenant,
                                            EventStoreSubscriptionObserver eventStoreSubscriptionObserver,
                                            Consumer<EventStoreSubscription> unsubscribeCallback,
                                            Function<String, EventStorePollingOptimizer> eventStorePollingOptimizerFactory) {

    public EventStoreSubscriptionContext {
        requireNonNull(eventStore, "No eventStore provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        requireNonNull(subscriberId, "No subscriberId provided");
        requireNonNull(eventStoreSubscriptionObserver, "No eventStoreSubscriptionObserver provided");
        requireNonNull(unsubscribeCallback, "No unsubscribeCallback provided");
        requireNonNull(eventStorePollingOptimizerFactory, "No eventStorePollingOptimizerFactory provided");
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    public static EventStoreSubscriptionContextBuilder builder() {
        return new EventStoreSubscriptionContextBuilder();
    }

    /**
     * @return the tenant restriction as an {@code Optional}, for callers and subclasses that read it that way.
     *         {@link Optional#empty()} means "all tenants"
     */
    public Optional<Tenant> onlyIncludeEventsForTenantOptional() {
        return Optional.ofNullable(onlyIncludeEventsForTenant);
    }
}
