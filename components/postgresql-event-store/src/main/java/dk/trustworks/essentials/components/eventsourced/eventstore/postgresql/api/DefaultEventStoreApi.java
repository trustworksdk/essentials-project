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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.api;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.SubscriptionStatisticsRegistry;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.SubscriptionStatisticsRegistry.SubscriptionKey;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.types.SubscriberId;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;

import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityRoles.*;
import static dk.trustworks.essentials.shared.security.EssentialsSecurityValidator.validateHasAnyEssentialsSecurityRoles;

/**
 * The DefaultEventStoreApi class is a concrete implementation of the EventStoreApi interface,
 * providing methods to interact with the event store and manage event-related data.
 * This implementation enforces security through role validation and provides functionality
 * to retrieve event and subscription information.
 * <p>
 * Subscriptions are described from up to three sources, deliberately kept apart because their scopes differ:
 * the {@link DurableSubscriptionRepository} resume points shared by every instance through the database, the
 * {@link EventStoreSubscriptionManager} of this instance, and the {@link SubscriptionStatisticsRegistry} collected in
 * this instance's memory. The last two are optional - without them the API answers exactly what it did before they
 * existed.
 */
public class DefaultEventStoreApi implements EventStoreApi {

    private final EssentialsSecurityProvider               essentialsSecurityProvider;
    private final EventStore                               eventStore;
    private final DurableSubscriptionRepository            durableSubscriptionRepository;
    private final Optional<EventStoreSubscriptionManager>  eventStoreSubscriptionManager;
    private final Optional<SubscriptionStatisticsRegistry> subscriptionStatisticsRegistry;

    /**
     * Create an API that only reports the durable resume points shared by every instance.
     *
     * @param essentialsSecurityProvider    the security provider used for role validation
     * @param eventStore                    the event store queried
     * @param durableSubscriptionRepository the repository holding the durable subscription resume points
     */
    public DefaultEventStoreApi(EssentialsSecurityProvider essentialsSecurityProvider,
                                EventStore eventStore,
                                DurableSubscriptionRepository durableSubscriptionRepository) {
        this(essentialsSecurityProvider,
             eventStore,
             durableSubscriptionRepository,
             Optional.empty(),
             Optional.empty());
    }

    /**
     * @param essentialsSecurityProvider     the security provider used for role validation
     * @param eventStore                     the event store queried
     * @param durableSubscriptionRepository  the repository holding the durable subscription resume points
     * @param eventStoreSubscriptionManager  the subscription manager of this instance, used to report the live state of
     *                                       the subscriptions running here. {@link Optional#empty()} when this instance
     *                                       runs no subscription manager
     * @param subscriptionStatisticsRegistry the registry holding the statistics collected in this instance.
     *                                       {@link Optional#empty()} when statistics collection is disabled
     * @deprecated Use {@link #builder()}. This constructor declares an {@code Optional} parameter and/or more than five parameters; the builder names every argument and accepts both plain values and {@code Optional}s. It is unchanged and remains the implementation the builder delegates to.
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    @Deprecated(forRemoval = true, since = "0.40.x")
    public DefaultEventStoreApi(EssentialsSecurityProvider essentialsSecurityProvider,
                                EventStore eventStore,
                                DurableSubscriptionRepository durableSubscriptionRepository,
                                Optional<EventStoreSubscriptionManager> eventStoreSubscriptionManager,
                                Optional<SubscriptionStatisticsRegistry> subscriptionStatisticsRegistry) {
        this.essentialsSecurityProvider = requireNonNull(essentialsSecurityProvider, "EssentialsSecurityProvider must not be null");
        this.eventStore = requireNonNull(eventStore, "EventStore must not be null");
        this.durableSubscriptionRepository = requireNonNull(durableSubscriptionRepository, "DurableSubscriptionRepository must not be null");
        this.eventStoreSubscriptionManager = requireNonNull(eventStoreSubscriptionManager, "EventStoreSubscriptionManager Optional must not be null");
        this.subscriptionStatisticsRegistry = requireNonNull(subscriptionStatisticsRegistry, "SubscriptionStatisticsRegistry Optional must not be null");
    }

    private void validateSubscriptionReaderRoles(Object principal) {
        validateHasAnyEssentialsSecurityRoles(essentialsSecurityProvider, principal, SUBSCRIPTION_READER, ESSENTIALS_ADMIN);
    }

    @Override
    public Optional<GlobalEventOrder> findHighestGlobalEventOrderPersisted(Object principal, AggregateType aggregateType) {
        validateSubscriptionReaderRoles(principal);
        return eventStore.getUnitOfWorkFactory().withUnitOfWork(uow -> {
            return eventStore.findHighestGlobalEventOrderPersisted(aggregateType);
        });
    }

    /**
     * {@inheritDoc}
     * <p>
     * The durable resume points are combined with the subscriptions registered in this instance: a subscription that
     * has no durable resume point - an in-transaction subscription, or one whose resume point has not been persisted
     * yet - is reported as well, marked {@link ApiSubscription#durableResumePointPresent()} {@code false}.
     * <p>
     * No aggregate-type event stream is queried, so the call stays cheap; use
     * {@link #findHighestGlobalEventOrderPersisted(Object, AggregateType)} per aggregate type to establish how far
     * behind a subscription is.
     */
    @Override
    public List<ApiSubscription> findAllSubscriptions(Object principal) {
        validateSubscriptionReaderRoles(principal);
        var subscriptions = new ArrayList<ApiSubscription>();
        var reportedKeys  = new HashSet<SubscriptionKey>();
        durableSubscriptionRepository.findAllResumePoints().forEach(resumePoint -> {
            var key = new SubscriptionKey(resumePoint.getSubscriberId(), resumePoint.getAggregateType());
            reportedKeys.add(key);
            subscriptions.add(toApiSubscription(resumePoint, findSubscription(key.subscriberId(), key.aggregateType())));
        });
        eventStoreSubscriptionManager.ifPresent(subscriptionManager -> subscriptionManager.getSubscriptions().forEach(subscriberIdAndAggregateType -> {
            var key = new SubscriptionKey(subscriberIdAndAggregateType._1, subscriberIdAndAggregateType._2);
            if (reportedKeys.add(key)) {
                subscriptionManager.getSubscription(key.subscriberId(), key.aggregateType())
                                   .map(DefaultEventStoreApi::toApiSubscription)
                                   .ifPresent(subscriptions::add);
            }
        }));
        return List.copyOf(subscriptions);
    }

    @Override
    public List<ApiSubscriptionStatistics> findAllSubscriptionStatistics(Object principal) {
        validateSubscriptionReaderRoles(principal);
        return subscriptionStatisticsRegistry.map(registry -> registry.allStatistics().stream()
                                                                      .map(ApiSubscriptionStatistics::from)
                                                                      .toList())
                                             .orElseGet(List::of);
    }

    @Override
    public Optional<ApiSubscriptionStatistics> findSubscriptionStatistics(Object principal,
                                                                         SubscriberId subscriberId,
                                                                         AggregateType aggregateType) {
        validateSubscriptionReaderRoles(principal);
        requireNonNull(subscriberId, "No subscriberId provided");
        requireNonNull(aggregateType, "No aggregateType provided");
        return subscriptionStatisticsRegistry.flatMap(registry -> registry.findStatistics(subscriberId, aggregateType))
                                             .map(ApiSubscriptionStatistics::from);
    }

    private Optional<EventStoreSubscription> findSubscription(SubscriberId subscriberId, AggregateType aggregateType) {
        return eventStoreSubscriptionManager.flatMap(subscriptionManager -> subscriptionManager.getSubscription(subscriberId, aggregateType));
    }

    /**
     * Describe a subscription from its durable resume point, enriched with the live state of the subscription if it
     * runs in this instance
     *
     * @param resumePoint            the durable resume point
     * @param eventStoreSubscription the live subscription of this instance, if any
     * @return the subscription
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private static ApiSubscription toApiSubscription(SubscriptionResumePoint resumePoint,
                                                    Optional<EventStoreSubscription> eventStoreSubscription) {
        return new ApiSubscription(
                resumePoint.getSubscriberId(),
                resumePoint.getAggregateType(),
                resumePoint.getResumeFromAndIncluding().longValue(),
                resumePoint.getLastUpdated(),
                true,
                eventStoreSubscription.isPresent(),
                eventStoreSubscription.map(EventStoreSubscription::isActive).orElse(null),
                eventStoreSubscription.map(EventStoreSubscription::isExclusive).orElse(null),
                eventStoreSubscription.map(EventStoreSubscription::isInTransaction).orElse(null),
                eventStoreSubscription.flatMap(subscription -> subscription.onlyIncludeEventsForTenant().map(Object::toString)).orElse(null),
                inMemoryGlobalOrderOf(eventStoreSubscription.orElse(null)));
    }

    /**
     * Describe a subscription that runs in this instance but has no durable resume point - either because it is an
     * in-transaction subscription, or because its resume point has not been persisted yet
     *
     * @param eventStoreSubscription the live subscription of this instance
     * @return the subscription
     */
    private static ApiSubscription toApiSubscription(EventStoreSubscription eventStoreSubscription) {
        return new ApiSubscription(
                eventStoreSubscription.subscriberId(),
                eventStoreSubscription.aggregateType(),
                0,
                null,
                false,
                true,
                eventStoreSubscription.isActive(),
                eventStoreSubscription.isExclusive(),
                eventStoreSubscription.isInTransaction(),
                eventStoreSubscription.onlyIncludeEventsForTenant().map(Object::toString).orElse(null),
                inMemoryGlobalOrderOf(eventStoreSubscription));
    }

    private static Long inMemoryGlobalOrderOf(EventStoreSubscription eventStoreSubscription) {
        return eventStoreSubscription != null
               ? eventStoreSubscription.currentResumePoint()
                                       .map(resumePoint -> resumePoint.getResumeFromAndIncluding().longValue())
                                       .orElse(null)
               : null;
    }

    /**
     * Creates a builder for a {@link DefaultEventStoreApi}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link DefaultEventStoreApi}, obtained from {@link #builder()}.
     * <p>
     * The previously-{@code Optional} constructor parameters are plain nullable fields here, each with a
     * plain-value setter and an {@code Optional} overload.
     */
    public static final class Builder {
        private EssentialsSecurityProvider essentialsSecurityProvider;
        private EventStore eventStore;
        private DurableSubscriptionRepository durableSubscriptionRepository;
        private EventStoreSubscriptionManager eventStoreSubscriptionManager;
        private SubscriptionStatisticsRegistry subscriptionStatisticsRegistry;

        /**
         * @param essentialsSecurityProvider required
         * @return this builder
         */
        public Builder setEssentialsSecurityProvider(EssentialsSecurityProvider essentialsSecurityProvider) {
            this.essentialsSecurityProvider = essentialsSecurityProvider;
            return this;
        }

        /**
         * @param eventStore required
         * @return this builder
         */
        public Builder setEventStore(EventStore eventStore) {
            this.eventStore = eventStore;
            return this;
        }

        /**
         * @param durableSubscriptionRepository required
         * @return this builder
         */
        public Builder setDurableSubscriptionRepository(DurableSubscriptionRepository durableSubscriptionRepository) {
            this.durableSubscriptionRepository = durableSubscriptionRepository;
            return this;
        }

        /**
         * @param eventStoreSubscriptionManager optional — {@code null} selects the default
         * @return this builder
         */
        public Builder setEventStoreSubscriptionManager(EventStoreSubscriptionManager eventStoreSubscriptionManager) {
            this.eventStoreSubscriptionManager = eventStoreSubscriptionManager;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setEventStoreSubscriptionManager}.
         *
         * @param eventStoreSubscriptionManager the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setEventStoreSubscriptionManager(Optional<EventStoreSubscriptionManager> eventStoreSubscriptionManager) {
            requireNonNull(eventStoreSubscriptionManager, "No eventStoreSubscriptionManager provided");
            return setEventStoreSubscriptionManager(eventStoreSubscriptionManager.orElse(null));
        }

        /**
         * @param subscriptionStatisticsRegistry optional — {@code null} selects the default
         * @return this builder
         */
        public Builder setSubscriptionStatisticsRegistry(SubscriptionStatisticsRegistry subscriptionStatisticsRegistry) {
            this.subscriptionStatisticsRegistry = subscriptionStatisticsRegistry;
            return this;
        }

        /**
         * {@code Optional} overload of {@link #setSubscriptionStatisticsRegistry}.
         *
         * @param subscriptionStatisticsRegistry the value, or empty for the default
         * @return this builder
         */
        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        public Builder setSubscriptionStatisticsRegistry(Optional<SubscriptionStatisticsRegistry> subscriptionStatisticsRegistry) {
            requireNonNull(subscriptionStatisticsRegistry, "No subscriptionStatisticsRegistry provided");
            return setSubscriptionStatisticsRegistry(subscriptionStatisticsRegistry.orElse(null));
        }

        /**
         * @return the new {@link DefaultEventStoreApi}
         */
        @SuppressWarnings("removal")
        public DefaultEventStoreApi build() {
            return new DefaultEventStoreApi(essentialsSecurityProvider,
                                            eventStore,
                                            durableSubscriptionRepository,
                                            Optional.ofNullable(eventStoreSubscriptionManager),
                                            Optional.ofNullable(subscriptionStatisticsRegistry));
        }
    }

}
