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

package dk.trustworks.essentials.examples.trading.funding.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import dk.trustworks.essentials.examples.trading.funding.types.FundingAggregateTypes;
import org.springframework.stereotype.Component;

import java.util.Optional;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The repository for {@link Withdrawal}s and the owner of the {@code Withdrawals} stream.
 */
@Component
public class Withdrawals {
    public static final AggregateType AGGREGATE_TYPE = FundingAggregateTypes.WITHDRAWALS;

    private final StatefulAggregateRepository<WithdrawalId, WithdrawalEvent, Withdrawal> repository;

    public Withdrawals(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        requireNonNull(eventStore, "No eventStore provided");
        repository = StatefulAggregateRepository.from(eventStore, AGGREGATE_TYPE, reflectionBasedAggregateRootFactory(), Withdrawal.class);
    }

    public Withdrawal get(WithdrawalId id) {
        return repository.load(requireNonNull(id, "No id provided"));
    }

    public Optional<Withdrawal> find(WithdrawalId id) {
        return repository.tryLoad(requireNonNull(id, "No id provided"));
    }

    public Withdrawal saveNew(Withdrawal withdrawal) {
        return repository.save(requireNonNull(withdrawal, "No withdrawal provided"));
    }
}
