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

package dk.trustworks.essentials.examples.trading.market_data.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotRepositoryProvider;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentPriceEvent;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.MarketDataAggregateTypes;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link InstrumentPrice} aggregates, and the owner of the {@code InstrumentPrices}
 * {@link AggregateType} -- the name under which their events are stored.
 *
 * <p>Note the id type: this repository is keyed by {@link InstrumentId}, not by a dedicated price id. A price stream is
 * the instrument's own identity under a second {@code AggregateType}, so {@code Instruments} and
 * {@code InstrumentPrices} address the same ids in two different tables. Do not "fix" this by inventing an
 * {@code InstrumentPriceId} -- it would add a lookup and buy nothing.
 *
 * <p>It does not construct aggregates; see {@link #initializeNewPrice}.
 */
@Component
public class InstrumentPrices {
    public static final AggregateType                                                                   AGGREGATE_TYPE = MarketDataAggregateTypes.INSTRUMENT_PRICES;
    private final       StatefulAggregateRepository<InstrumentId, InstrumentPriceEvent, InstrumentPrice> repository;

    /**
     * {@link InstrumentPrice} declares an {@link dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicy},
     * and {@code MarketDataConfiguration.marketDataAggregates} declares the aggregate so that policy reaches the
     * registry the admin console reads. The policy only takes effect on the <em>load</em> path when the repository is
     * built with the snapshot repository provider, so this wrapper has to resolve it -- a repository built without one
     * passes a null snapshot repository and every load replays the whole stream, which is quadratic under the
     * price-stress runs this aggregate exists to demonstrate.
     *
     * <p>The provider is {@link Optional} because it is only present when snapshot support is configured. Passing the
     * {@code Optional} straight to the builder is what keeps that a single expression: an empty one yields the plain
     * repository rather than failing to start.
     */
    public InstrumentPrices(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
                            Optional<AggregateSnapshotRepositoryProvider> aggregateSnapshotRepositoryProvider) {
        requireNonNull(eventStore, "No eventStore provided");
        requireNonNull(aggregateSnapshotRepositoryProvider, "No aggregateSnapshotRepositoryProvider provided");
        repository = StatefulAggregateRepository.builder(eventStore)
                                               .setAggregateType(AGGREGATE_TYPE)
                                               .setAggregateImplementationType(InstrumentPrice.class)
                                               .setAggregateSnapshotRepositoryProvider(aggregateSnapshotRepositoryProvider)
                                               .build();
    }

    public InstrumentPrice getPrice(InstrumentId instrumentId) {
        requireNonNull(instrumentId, "No instrumentId provided");
        return repository.load(instrumentId);
    }

    public Optional<InstrumentPrice> findPrice(InstrumentId instrumentId) {
        requireNonNull(instrumentId, "No instrumentId provided");
        return repository.tryLoad(instrumentId);
    }

    /**
     * Persists an already-constructed {@link InstrumentPrice}. Constructing it — which is what emits
     * {@code PriceInitialized} — is the initializing slice's decision, not this repository's, so it happens there.
     */
    public InstrumentPrice initializeNewPrice(InstrumentPrice instrumentPrice) {
        requireNonNull(instrumentPrice, "No instrumentPrice provided");
        return repository.save(instrumentPrice);
    }
}
