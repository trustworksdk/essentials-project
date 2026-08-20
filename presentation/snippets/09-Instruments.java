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

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentEvent;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.MarketDataAggregateTypes;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link Instrument} aggregates, and the owner of the {@code Instruments} {@link AggregateType} --
 * the name under which their events are stored, which every subscriber and projection referring to instrument
 * reference data resolves back to.
 *
 * <p>It wraps a {@link StatefulAggregateRepository}, which loads an aggregate by replaying its stream and persists the
 * events a command produced. The thin wrapper exists so the context speaks its own language ({@code getInstrument},
 * {@code findInstrument}) instead of a generic {@code load}/{@code save}.
 *
 * <p>It does not construct aggregates; see {@link #registerNewInstrument}.
 */
@Component
public class Instruments {
    public static final AggregateType                                                    AGGREGATE_TYPE = MarketDataAggregateTypes.INSTRUMENTS;
    private final       StatefulAggregateRepository<InstrumentId, InstrumentEvent, Instrument> repository;

    public Instruments(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        requireNonNull(eventStore, "No eventStore provided");
        repository = StatefulAggregateRepository.from(eventStore,
                                                      AGGREGATE_TYPE,
                                                      reflectionBasedAggregateRootFactory(),
                                                      Instrument.class);
    }

    public Instrument getInstrument(InstrumentId instrumentId) {
        requireNonNull(instrumentId, "No instrumentId provided");
        return repository.load(instrumentId);
    }

    public Optional<Instrument> findInstrument(InstrumentId instrumentId) {
        requireNonNull(instrumentId, "No instrumentId provided");
        return repository.tryLoad(instrumentId);
    }

    /**
     * Persists an already-constructed {@link Instrument}. Constructing it — which is what emits
     * {@code InstrumentRegistered} — is the registering slice's decision, not this repository's, so it happens there.
     * Mirrors {@code Accounts.openNewAccount} in the {@code postgresql-cqrs} example.
     */
    public Instrument registerNewInstrument(Instrument instrument) {
        requireNonNull(instrument, "No instrument provided");
        return repository.save(instrument);
    }
}
