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

package dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.cdc;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.gap.EventStreamGapHandler;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.AggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Builder for {@link CdcEventStore}, obtained from {@link CdcEventStore#builder()}.
 * <p>
 * The {@link MeterRegistry} is held as a plain nullable field — absent means no CDC event-store metrics are recorded —
 * and also has an {@code Optional} overload, for Spring {@code @Bean} methods where an {@code Optional} injection point
 * is idiomatic.
 *
 * @param <CONFIG> the event-stream configuration type
 */
public final class CdcEventStoreBuilder<CONFIG extends AggregateEventStreamConfiguration> {
    private ConfigurableEventStore<CONFIG>                              delegate;
    private EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private EventStreamGapHandler<?>                                    eventStreamGapHandler;
    private CdcEventBus                                                 cdcBus;
    private CdcProperties                                               cdcProperties;
    private CdcAvailability                                             availability;
    private MeterRegistry                                               meterRegistry;

    /**
     * @param delegate the {@link ConfigurableEventStore} being decorated. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEventStoreBuilder<CONFIG> setDelegate(ConfigurableEventStore<CONFIG> delegate) {
        this.delegate = delegate;
        return this;
    }

    /**
     * @param unitOfWorkFactory the unit-of-work factory. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEventStoreBuilder<CONFIG> setUnitOfWorkFactory(EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory) {
        this.unitOfWorkFactory = unitOfWorkFactory;
        return this;
    }

    /**
     * @param eventStreamGapHandler the gap handler. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEventStoreBuilder<CONFIG> setEventStreamGapHandler(EventStreamGapHandler<?> eventStreamGapHandler) {
        this.eventStreamGapHandler = eventStreamGapHandler;
        return this;
    }

    /**
     * @param cdcBus the in-memory CDC fan-out bus between {@link CdcDispatcher} and this store's subscribers. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEventStoreBuilder<CONFIG> setCdcBus(CdcEventBus cdcBus) {
        this.cdcBus = cdcBus;
        return this;
    }

    /**
     * @param cdcProperties the CDC configuration. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEventStoreBuilder<CONFIG> setCdcProperties(CdcProperties cdcProperties) {
        this.cdcProperties = cdcProperties;
        return this;
    }

    /**
     * @param availability the shared CDC availability tracker. Required
     * @return this builder instance for fluent chaining
     */
    public CdcEventStoreBuilder<CONFIG> setAvailability(CdcAvailability availability) {
        this.availability = availability;
        return this;
    }

    /**
     * @param meterRegistry the Micrometer registry, or {@code null} for no CDC event-store metrics
     * @return this builder instance for fluent chaining
     */
    public CdcEventStoreBuilder<CONFIG> setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        return this;
    }

    /**
     * {@code Optional} overload of {@link #setMeterRegistry(MeterRegistry)}.
     *
     * @param meterRegistry the registry, or empty for no metrics
     * @return this builder instance for fluent chaining
     */
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public CdcEventStoreBuilder<CONFIG> setMeterRegistry(Optional<MeterRegistry> meterRegistry) {
        requireNonNull(meterRegistry, "meterRegistry cannot be null");
        return setMeterRegistry(meterRegistry.orElse(null));
    }

    /**
     * Builds the CDC event store.
     *
     * @return the store
     */
    @SuppressWarnings("removal")
    public CdcEventStore<CONFIG> build() {
        return new CdcEventStore<>(requireNonNull(delegate, "delegate cannot be null"),
                                   requireNonNull(unitOfWorkFactory, "unitOfWorkFactory cannot be null"),
                                   requireNonNull(eventStreamGapHandler, "eventStreamGapHandler cannot be null"),
                                   requireNonNull(cdcBus, "cdcBus cannot be null"),
                                   requireNonNull(cdcProperties, "cdcProperties cannot be null"),
                                   requireNonNull(availability, "availability cannot be null"),
                                   Optional.ofNullable(meterRegistry));
    }
}
