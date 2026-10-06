package com.example.multi.ledger.aggregates;

import com.example.multi.ledger.events.AccountEvent;
import com.example.multi.ledger.types.AccountId;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import org.springframework.stereotype.Component;

import static dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory;

@Component
public class Accounts {
    public static final AggregateType AGGREGATE_TYPE = AggregateType.of("LedgerAccounts");

    private final StatefulAggregateRepository<AccountId, AccountEvent, Account> repository;

    public Accounts(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        this.repository = StatefulAggregateRepository.from(eventStore, AGGREGATE_TYPE,
                                                           reflectionBasedAggregateRootFactory(), Account.class);
    }

    public Account getAccount(AccountId id) { return repository.load(id); }
}
