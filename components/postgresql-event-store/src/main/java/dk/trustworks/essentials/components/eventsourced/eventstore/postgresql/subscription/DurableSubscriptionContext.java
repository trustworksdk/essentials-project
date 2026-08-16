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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The three arguments the <em>resumable</em> subscriptions share on top of {@link EventStoreSubscriptionContext}:
 * where the resume point is stored, where to start when there is no resume point yet, and the manager-wide settings.
 * <p>
 * Only the three asynchronous subscriptions need these — the in-transaction subscriptions are not durable and take no
 * such arguments, which is why this is a second, smaller context rather than more fields on the first one.
 * <p>
 * {@code onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder} is a {@code Function<AggregateType, …>} because
 * {@link ExclusiveAsynchronousSubscription} resolves it per aggregate type. The non-exclusive subscriptions took a
 * plain {@link GlobalEventOrder}; {@link #fromFixedGlobalOrder(DurableSubscriptionRepository, GlobalEventOrder,
 * EventStoreSubscriptionManagerSettings)} lifts such a constant into the same shape, so both kinds share one context
 * without either losing precision.
 *
 * @param durableSubscriptionRepository                          where the subscriber's resume {@link GlobalEventOrder} is persisted
 * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder where to start when this subscriber has no stored resume point
 * @param eventStoreSubscriptionManagerSettings                   the manager-wide subscription settings
 */
public record DurableSubscriptionContext(DurableSubscriptionRepository durableSubscriptionRepository,
                                         Function<AggregateType, GlobalEventOrder> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                         EventStoreSubscriptionManagerSettings eventStoreSubscriptionManagerSettings) {

    public DurableSubscriptionContext {
        requireNonNull(durableSubscriptionRepository, "No durableSubscriptionRepository provided");
        requireNonNull(onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder, "No onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder provided");
        requireNonNull(eventStoreSubscriptionManagerSettings, "No eventStoreSubscriptionManagerSettings provided");
    }

    /**
     * Creates a context whose start position is the same {@link GlobalEventOrder} regardless of aggregate type — the
     * shape the non-exclusive asynchronous subscriptions have always used.
     *
     * @param durableSubscriptionRepository                          where the resume point is persisted
     * @param onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder the fixed start position
     * @param eventStoreSubscriptionManagerSettings                   the manager-wide subscription settings
     * @return the context
     */
    public static DurableSubscriptionContext fromFixedGlobalOrder(DurableSubscriptionRepository durableSubscriptionRepository,
                                                                  GlobalEventOrder onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                                                  EventStoreSubscriptionManagerSettings eventStoreSubscriptionManagerSettings) {
        requireNonNull(onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder, "No onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder provided");
        return new DurableSubscriptionContext(durableSubscriptionRepository,
                                              aggregateType -> onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder,
                                              eventStoreSubscriptionManagerSettings);
    }

    /**
     * Resolves the start position for the given aggregate type.
     *
     * @param aggregateType the aggregate type being subscribed to
     * @return the {@link GlobalEventOrder} to start from when there is no stored resume point
     */
    public GlobalEventOrder resolveOnFirstSubscriptionGlobalOrder(AggregateType aggregateType) {
        return requireNonNull(onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder.apply(aggregateType),
                              "onFirstSubscriptionSubscribeFromAndIncludingGlobalOrder resolved to null");
    }
}
