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

package dk.trustworks.essentials.examples.trading.brokerage.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.examples.trading.brokerage.events.SettlementEvent;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link Settlement} aggregates, and the owner of the {@code Settlements} {@link AggregateType} --
 * the name under which their events are stored, which every subscriber and projection in the brokerage context refers
 * back to.
 *
 * <p>It wraps a {@link StatefulAggregateRepository}, which loads an aggregate by replaying its stream and persists the
 * events a command produced. The thin wrapper exists so the context speaks its own language ({@code getSettlement},
 * {@code createNewSettlement}) instead of a generic {@code load}/{@code save}.
 *
 * <p>It does not construct aggregates; see {@link #createNewSettlement}.
 */
@Component
public class Settlements {
    public static final AggregateType                                                        AGGREGATE_TYPE = AggregateType.of("Settlements");
    private final       StatefulAggregateRepository<SettlementId, SettlementEvent, Settlement> repository;

    public Settlements(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        requireNonNull(eventStore, "No eventStore provided");
        repository = StatefulAggregateRepository.from(eventStore,
                                                      AGGREGATE_TYPE,
                                                      reflectionBasedAggregateRootFactory(),
                                                      Settlement.class);
    }

    public Settlement getSettlement(SettlementId settlementId) {
        requireNonNull(settlementId, "No settlementId provided");
        return repository.load(settlementId);
    }

    public Optional<Settlement> findSettlement(SettlementId settlementId) {
        requireNonNull(settlementId, "No settlementId provided");
        return repository.tryLoad(settlementId);
    }

    /**
     * Persists an already-constructed {@link Settlement}. Constructing it -- which is what emits
     * {@code SettlementCreated} -- is the creating slice's decision, not this repository's, so it happens there.
     */
    public Settlement createNewSettlement(Settlement settlement) {
        requireNonNull(settlement, "No settlement provided");
        return repository.save(settlement);
    }
}
