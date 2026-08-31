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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import org.jdbi.v3.core.Jdbi;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link SeparateTablePerAggregateTypePersistenceStrategy}, obtained from
 * {@link SeparateTablePerAggregateTypePersistenceStrategy#builder()}.
 * <p>
 * {@code aggregateTypeConfigurations} and {@code persistableEventEnrichers} both default to empty, matching the
 * constructors that omitted them. The configurations can be supplied either as a {@link List} or as a varargs array —
 * the two setters are the builder equivalent of the constructor pairs they replace.
 */
public final class SeparateTablePerAggregateTypePersistenceStrategyBuilder {
    @SuppressWarnings("rawtypes")
    private EventStoreUnitOfWorkFactory                                                                unitOfWorkFactory;
    private Jdbi                                                                                       jdbi;
    private PersistableEventMapper                                                                     eventMapper;
    private AggregateEventStreamConfigurationFactory<SeparateTablePerAggregateEventStreamConfiguration> aggregateEventStreamConfigurationFactory;
    private List<SeparateTablePerAggregateEventStreamConfiguration>                                    aggregateTypeConfigurations = List.of();
    private List<PersistableEventEnricher>                                                             persistableEventEnrichers  = List.of();

    /**
     * @param jdbi the Jdbi instance. Required
     * @return this builder instance for fluent chaining
     */
    public SeparateTablePerAggregateTypePersistenceStrategyBuilder setJdbi(Jdbi jdbi) {
        this.jdbi = jdbi;
        return this;
    }

    /**
     * @param unitOfWorkFactory the {@link EventStoreUnitOfWorkFactory}. Required
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("rawtypes")
    public SeparateTablePerAggregateTypePersistenceStrategyBuilder setUnitOfWorkFactory(EventStoreUnitOfWorkFactory unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        return this;
    }

    /**
     * @param eventMapper the mapper from raw Java events to {@link PersistableEvent}, controlling meta-data,
     *                    correlation id, tenant id, etc. at a cross-functional level. Required
     * @return this builder instance for fluent chaining
     */
    public SeparateTablePerAggregateTypePersistenceStrategyBuilder setEventMapper(PersistableEventMapper eventMapper) {
        this.eventMapper = eventMapper;
        return this;
    }

    /**
     * @param aggregateEventStreamConfigurationFactory the factory providing the default
     *                                                 {@link AggregateEventStreamConfiguration}. Required. See
     *                                                 {@link SeparateTablePerAggregateTypeEventStreamConfigurationFactory}
     * @return this builder instance for fluent chaining
     */
    public SeparateTablePerAggregateTypePersistenceStrategyBuilder setAggregateEventStreamConfigurationFactory(
            AggregateEventStreamConfigurationFactory<SeparateTablePerAggregateEventStreamConfiguration> aggregateEventStreamConfigurationFactory) {
        this.aggregateEventStreamConfigurationFactory = aggregateEventStreamConfigurationFactory;
        return this;
    }

    /**
     * @param aggregateTypeConfigurations the configurations to add immediately. Defaults to empty
     * @return this builder instance for fluent chaining
     */
    public SeparateTablePerAggregateTypePersistenceStrategyBuilder setAggregateTypeConfigurations(List<SeparateTablePerAggregateEventStreamConfiguration> aggregateTypeConfigurations) {
        this.aggregateTypeConfigurations = aggregateTypeConfigurations;
        return this;
    }

    /**
     * Varargs overload of {@link #setAggregateTypeConfigurations(List)}.
     *
     * @param aggregateTypeConfigurations the configurations to add immediately
     * @return this builder instance for fluent chaining
     */
    public SeparateTablePerAggregateTypePersistenceStrategyBuilder setAggregateTypeConfigurations(SeparateTablePerAggregateEventStreamConfiguration... aggregateTypeConfigurations) {
        requireNonNull(aggregateTypeConfigurations, "aggregateTypeConfigurations cannot be null");
        return setAggregateTypeConfigurations(List.of(aggregateTypeConfigurations));
    }

    /**
     * @param persistableEventEnrichers the enrichers called in sequence after
     *                                  {@link PersistableEventMapper#map(Object, AggregateEventStreamConfiguration, Object, dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder)}.
     *                                  Defaults to empty
     * @return this builder instance for fluent chaining
     */
    public SeparateTablePerAggregateTypePersistenceStrategyBuilder setPersistableEventEnrichers(List<PersistableEventEnricher> persistableEventEnrichers) {
        this.persistableEventEnrichers = persistableEventEnrichers;
        return this;
    }

    /**
     * Builds the persistence strategy.
     *
     * @return the strategy
     */
    @SuppressWarnings("removal")
    public SeparateTablePerAggregateTypePersistenceStrategy build() {
        return new SeparateTablePerAggregateTypePersistenceStrategy(requireNonNull(jdbi, "jdbi cannot be null"),
                                                                    requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null"),
                                                                    requireNonNull(eventMapper, "eventMapper cannot be null"),
                                                                    requireNonNull(aggregateEventStreamConfigurationFactory, "aggregateEventStreamConfigurationFactory cannot be null"),
                                                                    requireNonNull(aggregateTypeConfigurations, "aggregateTypeConfigurations cannot be null"),
                                                                    requireNonNull(persistableEventEnrichers, "persistableEventEnrichers cannot be null"));
    }
}
