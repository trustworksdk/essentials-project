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

package dk.trustworks.essentials.examples.trading.settlement.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.examples.trading.accounts.types.ExecutionId;
import dk.trustworks.essentials.examples.trading.settlement.events.TradeSettlementEvent;
import dk.trustworks.essentials.examples.trading.settlement.types.SettlementAggregateTypes;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link TradeSettlement}s and the owner of the {@code TradeSettlements} stream.
 */
@Component
public class TradeSettlements {
    public static final AggregateType AGGREGATE_TYPE = SettlementAggregateTypes.TRADE_SETTLEMENTS;

    private final StatefulAggregateRepository<ExecutionId, TradeSettlementEvent, TradeSettlement> repository;

    public TradeSettlements(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        requireNonNull(eventStore, "No eventStore provided");
        repository = StatefulAggregateRepository.from(eventStore, AGGREGATE_TYPE, reflectionBasedAggregateRootFactory(), TradeSettlement.class);
    }

    public Optional<TradeSettlement> find(ExecutionId executionId) {
        return repository.tryLoad(requireNonNull(executionId, "No executionId provided"));
    }

    public TradeSettlement saveNew(TradeSettlement settlement) {
        return repository.save(requireNonNull(settlement, "No settlement provided"));
    }
}
