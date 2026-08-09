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

package dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.aggregates;

import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.EventOrder;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.events.AccountEvent;
import dk.trustworks.essentials.types.LongRange;
import org.springframework.stereotype.Component;

import java.util.Optional;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountId;
import dk.trustworks.essentials.spring.examples.postgresql.cqrs.banking.types.AccountNumber;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;
import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

@Component
public class Accounts {
    public static final AggregateType                                                             AGGREGATE_TYPE = AggregateType.of("Accounts");
    private final       ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore;
    private final       StatefulAggregateRepository<AccountId, AccountEvent, Account>             repository;

    public Accounts(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        requireNonNull(eventStore, "No eventStore provided");
        this.eventStore = eventStore;
        repository = StatefulAggregateRepository.from(eventStore,
                                                      AGGREGATE_TYPE,
                                                      reflectionBasedAggregateRootFactory(),
                                                      Account.class);
    }

    public boolean hasAccount(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return eventStore.fetchStream(AGGREGATE_TYPE,
                                      accountId,
                                      LongRange.only(EventOrder.FIRST_EVENT_ORDER.longValue()))
                         .isPresent();
    }

    public boolean isAccountMissing(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return !hasAccount(accountId);
    }

    public Optional<Account> findAccount(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return repository.tryLoad(accountId);
    }

    public Account getAccount(AccountId accountId) {
        requireNonNull(accountId, "No accountId provided");
        return repository.load(accountId);
    }

    public Account openNewAccount(AccountId accountId,
                                  AccountNumber accountNumber) {
        requireNonNull(accountId, "No accountId provided");
        requireNonNull(accountNumber, "No accountNumber provided");
        var account = new Account(accountId, accountNumber);
        return repository.save(account);
    }
}
