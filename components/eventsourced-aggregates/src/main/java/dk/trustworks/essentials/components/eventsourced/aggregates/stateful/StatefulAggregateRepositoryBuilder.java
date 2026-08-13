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

package dk.trustworks.essentials.components.eventsourced.aggregates.stateful;

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.shared.types.GenericType;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;
import static dk.trustworks.essentials.shared.FailFast.requireTrue;

/**
 * Builder for {@link StatefulAggregateRepository}. Obtain one via
 * {@link StatefulAggregateRepository#builder(ConfigurableEventStore)}.
 * <p>
 * The {@code from(…)} family on {@link StatefulAggregateRepository} distinguishes a dozen overloads by argument list
 * alone; this builder names each choice instead:
 * <pre>{@code
 * StatefulAggregateRepository.builder(eventStore)
 *                            .setAggregateType(TRADING_ACCOUNTS)
 *                            .setAggregateImplementationType(TradingAccount.class)
 *                            .setAggregateSnapshotRepositoryProvider(snapshotRepositoryProvider)  // Optional-aware
 *                            .build();
 * }</pre>
 * Defaults:
 * <table>
 *     <caption>Builder defaults</caption>
 *     <tr><th>Property</th><th>Required</th><th>Default</th></tr>
 *     <tr><td>{@code setAggregateType} / {@code setEventStreamConfiguration}</td><td>one of the two</td><td>-</td></tr>
 *     <tr><td>{@code setAggregateImplementationType}</td><td>yes</td><td>-</td></tr>
 *     <tr><td>{@code setAggregateRootInstanceFactory}</td><td>no</td>
 *         <td>{@link StatefulAggregateInstanceFactory#reflectionBasedAggregateRootFactory()}</td></tr>
 *     <tr><td>{@code setAggregateIdType}</td><td>no</td>
 *         <td>resolved from the implementation type's generic parameters, as the {@code from(…)} overloads do</td></tr>
 *     <tr><td>{@code setAggregateSnapshotRepositoryProvider} / {@code setAggregateSnapshotRepository}</td><td>no</td>
 *         <td>no snapshots</td></tr>
 * </table>
 *
 * @param <CONFIG> the aggregate event-stream configuration type
 */
public final class StatefulAggregateRepositoryBuilder<CONFIG extends AggregateEventStreamConfiguration> {
    private final ConfigurableEventStore<CONFIG> eventStore;

    private AggregateType                                 aggregateType;
    private CONFIG                                        eventStreamConfiguration;
    private Class<?>                                      aggregateImplementationType;
    private Class<?>                                      aggregateIdType;
    private StatefulAggregateInstanceFactory              aggregateRootInstanceFactory        = StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory();
    private Optional<AggregateSnapshotRepositoryProvider> aggregateSnapshotRepositoryProvider = Optional.empty();
    private AggregateSnapshotRepository                   aggregateSnapshotRepository;

    StatefulAggregateRepositoryBuilder(ConfigurableEventStore<CONFIG> eventStore) {
        this.eventStore = requireNonNull(eventStore, "No eventStore provided");
    }

    /**
     * The aggregate type whose event streams the aggregate is persisted to. The {@link ConfigurableEventStore} is
     * configured with the default {@link AggregateEventStreamConfiguration} for it if it has none.
     * <p>
     * Mutually exclusive with {@link #setEventStreamConfiguration(AggregateEventStreamConfiguration)}.
     *
     * @param aggregateType the aggregate type; must not be null
     * @return this builder
     */
    public StatefulAggregateRepositoryBuilder<CONFIG> setAggregateType(AggregateType aggregateType) {
        this.aggregateType = requireNonNull(aggregateType, "No aggregateType provided");
        return this;
    }

    /**
     * An explicit event-stream configuration, for when the defaults are not wanted.
     * <p>
     * Mutually exclusive with {@link #setAggregateType(AggregateType)}.
     *
     * @param eventStreamConfiguration the configuration; must not be null
     * @return this builder
     */
    public StatefulAggregateRepositoryBuilder<CONFIG> setEventStreamConfiguration(CONFIG eventStreamConfiguration) {
        this.eventStreamConfiguration = requireNonNull(eventStreamConfiguration, "No eventStreamConfiguration provided");
        return this;
    }

    /**
     * @param aggregateImplementationType the concrete aggregate implementation type (MUST be a subtype of
     *                                    {@link StatefulAggregate}); must not be null
     * @return this builder
     */
    public StatefulAggregateRepositoryBuilder<CONFIG> setAggregateImplementationType(Class<?> aggregateImplementationType) {
        this.aggregateImplementationType = requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
        return this;
    }

    /**
     * Only needed when the id type cannot be resolved from the implementation type's generic parameters.
     *
     * @param aggregateIdType the aggregate id (stream id) type; must not be null
     * @return this builder
     */
    public StatefulAggregateRepositoryBuilder<CONFIG> setAggregateIdType(Class<?> aggregateIdType) {
        this.aggregateIdType = requireNonNull(aggregateIdType, "No aggregateIdType provided");
        return this;
    }

    /**
     * @param aggregateRootInstanceFactory the factory responsible for instantiating aggregates when loading them;
     *                                     must not be null
     * @return this builder
     */
    public StatefulAggregateRepositoryBuilder<CONFIG> setAggregateRootInstanceFactory(StatefulAggregateInstanceFactory aggregateRootInstanceFactory) {
        this.aggregateRootInstanceFactory = requireNonNull(aggregateRootInstanceFactory, "No aggregateRootInstanceFactory provided");
        return this;
    }

    /**
     * Snapshot support as an {@link Optional} - an empty one yields a repository with no snapshot repository attached,
     * which is what makes "snapshots when configured" a single expression.
     *
     * @param aggregateSnapshotRepositoryProvider the provider; must not be null, may be {@link Optional#empty()}
     * @return this builder
     */
    public StatefulAggregateRepositoryBuilder<CONFIG> setAggregateSnapshotRepositoryProvider(Optional<AggregateSnapshotRepositoryProvider> aggregateSnapshotRepositoryProvider) {
        this.aggregateSnapshotRepositoryProvider = requireNonNull(aggregateSnapshotRepositoryProvider, "No aggregateSnapshotRepositoryProvider Optional provided");
        return this;
    }

    /**
     * @param aggregateSnapshotRepositoryProvider the provider; must not be null
     * @return this builder
     */
    public StatefulAggregateRepositoryBuilder<CONFIG> setAggregateSnapshotRepositoryProvider(AggregateSnapshotRepositoryProvider aggregateSnapshotRepositoryProvider) {
        return setAggregateSnapshotRepositoryProvider(Optional.of(requireNonNull(aggregateSnapshotRepositoryProvider, "No aggregateSnapshotRepositoryProvider provided")));
    }

    /**
     * An already-resolved snapshot repository, bypassing provider resolution.
     *
     * @param aggregateSnapshotRepository the snapshot repository; must not be null
     * @return this builder
     */
    public StatefulAggregateRepositoryBuilder<CONFIG> setAggregateSnapshotRepository(AggregateSnapshotRepository aggregateSnapshotRepository) {
        this.aggregateSnapshotRepository = requireNonNull(aggregateSnapshotRepository, "No aggregateSnapshotRepository provided");
        return this;
    }

    /**
     * @param <ID>                  the aggregate ID type
     * @param <EVENT_TYPE>          the type of event
     * @param <AGGREGATE_IMPL_TYPE> the concrete aggregate type
     * @return the built {@link StatefulAggregateRepository}
     * @throws IllegalArgumentException if neither or both of aggregate type and event-stream configuration were given,
     *                                  if the implementation type is missing, or if both a snapshot repository and a
     *                                  snapshot repository provider were given
     */
    @SuppressWarnings("unchecked")
    public <ID, EVENT_TYPE, AGGREGATE_IMPL_TYPE extends StatefulAggregate<ID, EVENT_TYPE, AGGREGATE_IMPL_TYPE>>
    StatefulAggregateRepository<ID, EVENT_TYPE, AGGREGATE_IMPL_TYPE> build() {
        requireNonNull(aggregateImplementationType, "No aggregateImplementationType provided");
        requireTrue(aggregateType != null || eventStreamConfiguration != null,
                    "Either an aggregateType or an eventStreamConfiguration must be provided");
        requireTrue(aggregateType == null || eventStreamConfiguration == null,
                    "Provide either an aggregateType or an eventStreamConfiguration, not both");
        requireTrue(aggregateSnapshotRepository == null || aggregateSnapshotRepositoryProvider.isEmpty(),
                    "Provide either an aggregateSnapshotRepository or an aggregateSnapshotRepositoryProvider, not both");

        var implementationType = (Class<AGGREGATE_IMPL_TYPE>) aggregateImplementationType;
        var idType = (Class<ID>) (aggregateIdType != null
                                  ? aggregateIdType
                                  : GenericType.resolveGenericTypeOnSuperClass(implementationType, 0));

        if (eventStreamConfiguration != null) {
            var snapshotRepository = resolveSnapshotRepository(eventStreamConfiguration.aggregateType, implementationType);
            return StatefulAggregateRepository.from(eventStore,
                                                    eventStreamConfiguration,
                                                    aggregateRootInstanceFactory,
                                                    idType,
                                                    implementationType,
                                                    snapshotRepository);
        }
        var snapshotRepository = resolveSnapshotRepository(aggregateType, implementationType);
        return StatefulAggregateRepository.from(eventStore,
                                                aggregateType,
                                                aggregateRootInstanceFactory,
                                                idType,
                                                implementationType,
                                                snapshotRepository);
    }

    private AggregateSnapshotRepository resolveSnapshotRepository(AggregateType forAggregateType,
                                                                 Class<?> forAggregateImplementationType) {
        if (aggregateSnapshotRepository != null) {
            return aggregateSnapshotRepository;
        }
        return aggregateSnapshotRepositoryProvider.flatMap(provider -> provider.resolve(forAggregateType,
                                                                                        forAggregateImplementationType))
                                                  .orElse(null);
    }
}
