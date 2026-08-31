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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.bus.EventStoreEventBus;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.observability.EventStoreSubscriptionObserver.NoOpEventStoreSubscriptionObserver;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;

import java.util.Optional;
import java.util.function.Function;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link PostgresqlEventStore}, obtained from {@link PostgresqlEventStore#builder()}.
 * <p>
 * The gap handler defaults to {@link NoEventStreamGapHandler} and the subscription observer to
 * {@link NoOpEventStoreSubscriptionObserver}, matching the two-argument constructor. For the gap-handling counterpart of
 * {@link PostgresqlEventStore#withGapHandling}, set {@link #setEventStreamGapHandlerFactory(Function)} to
 * {@code eventStore -> PostgresqlEventStreamGapHandler.builder()...build()}.
 * <p>
 * The {@link EventStoreEventBus} is held as a plain nullable field — absent means the store creates its own — and also
 * has an {@code Optional} overload, for Spring {@code @Bean} methods where an {@code Optional} injection point is
 * idiomatic.
 *
 * @param <CONFIG> the concrete {@link AggregateEventStreamConfiguration}
 */
public final class PostgresqlEventStoreBuilder<CONFIG extends AggregateEventStreamConfiguration> {
    private EventStoreUnitOfWorkFactory                                            unitOfWorkFactory;
    private AggregateEventStreamPersistenceStrategy<CONFIG>                        persistenceStrategy;
    private EventStoreEventBus                                                     eventStoreEventBus;
    private Function<PostgresqlEventStore<CONFIG>, EventStreamGapHandler<CONFIG>>  eventStreamGapHandlerFactory = eventStore -> new NoEventStreamGapHandler<>();
    private EventStoreSubscriptionObserver                                         eventStoreSubscriptionObserver = new NoOpEventStoreSubscriptionObserver();

    /**
     * @param unitOfWorkFactory the unit-of-work factory. Required
     * @return this builder instance for fluent chaining
     */
    public PostgresqlEventStoreBuilder<CONFIG> setUnitOfWorkFactory(EventStoreUnitOfWorkFactory unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        return this;
    }

    /**
     * @param persistenceStrategy the persistence strategy. Required. Please see
     *                            {@link AggregateEventStreamPersistenceStrategy} documentation regarding
     *                            <b>Security</b> considerations
     * @return this builder instance for fluent chaining
     */
    public PostgresqlEventStoreBuilder<CONFIG> setPersistenceStrategy(AggregateEventStreamPersistenceStrategy<CONFIG> persistenceStrategy) {
        this.persistenceStrategy = persistenceStrategy;
        return this;
    }

    /**
     * @param eventStoreEventBus the {@link EventStoreEventBus} to use, or {@code null} (the default) to let the store
     *                           create its own
     * @return this builder instance for fluent chaining
     */
    public PostgresqlEventStoreBuilder<CONFIG> setEventStoreEventBus(EventStoreEventBus eventStoreEventBus) {
        this.eventStoreEventBus = eventStoreEventBus;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setEventStoreEventBus(EventStoreEventBus)}.
     *
     * @param eventStoreEventBus the bus, or empty to let the store create its own
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public PostgresqlEventStoreBuilder<CONFIG> setEventStoreEventBus(Optional<EventStoreEventBus> eventStoreEventBus) {
        requireNonNull(eventStoreEventBus, "eventStoreEventBus cannot be null");
        return setEventStoreEventBus(eventStoreEventBus.orElse(null));
    }

    /**
     * @param eventStreamGapHandlerFactory the factory producing the {@link EventStreamGapHandler} for tracking event
     *                                     stream gaps. Defaults to {@link NoEventStreamGapHandler}; production
     *                                     deployments should use {@link PostgresqlEventStreamGapHandler}
     * @return this builder instance for fluent chaining
     */
    public PostgresqlEventStoreBuilder<CONFIG> setEventStreamGapHandlerFactory(Function<PostgresqlEventStore<CONFIG>, EventStreamGapHandler<CONFIG>> eventStreamGapHandlerFactory) {
        this.eventStreamGapHandlerFactory = eventStreamGapHandlerFactory;
        return this;
    }

    /**
     * @param eventStoreSubscriptionObserver the observer the {@link EventStore} and
     *                                       {@link dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.subscription.EventStoreSubscriptionManager}
     *                                       use to track subscription statistics. Defaults to
     *                                       {@link NoOpEventStoreSubscriptionObserver}
     * @return this builder instance for fluent chaining
     */
    public PostgresqlEventStoreBuilder<CONFIG> setEventStoreSubscriptionObserver(EventStoreSubscriptionObserver eventStoreSubscriptionObserver) {
        this.eventStoreSubscriptionObserver = eventStoreSubscriptionObserver;
        return this;
    }

    /**
     * Builds the event store.
     *
     * @return the event store
     */
    @SuppressWarnings("removal")
    public PostgresqlEventStore<CONFIG> build() {
        return new PostgresqlEventStore<>(requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null"),
                                          requireNonNull(persistenceStrategy, "persistenceStrategy cannot be null"),
                                          Optional.ofNullable(eventStoreEventBus),
                                          requireNonNull(eventStreamGapHandlerFactory, "eventStreamGapHandlerFactory cannot be null"),
                                          requireNonNull(eventStoreSubscriptionObserver, "eventStoreSubscriptionObserver cannot be null"));
    }
}
