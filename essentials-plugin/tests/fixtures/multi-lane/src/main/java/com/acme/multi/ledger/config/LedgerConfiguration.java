package com.acme.multi.ledger.config;

import com.acme.multi.ledger.aggregates.Accounts;
import com.acme.multi.ledger.events.AccountEvent;
import com.acme.multi.ledger.routing.AccountCommand;
import com.acme.multi.ledger.types.AccountId;
import com.acme.multi.ledger.use_cases.open_account.OpenAccountDecider;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamAggregateTypeConfiguration;
import dk.trustworks.essentials.components.eventsourced.aggregates.eventstream.EventStreamDeciderSupportsAggregateTypeChecker.HandlesCommandsThatInheritFromCommandType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.AggregateIdSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LedgerConfiguration {

    @Bean
    public EventStreamAggregateTypeConfiguration accountAggregateTypeConfiguration() {
        return new EventStreamAggregateTypeConfiguration(
                Accounts.AGGREGATE_TYPE,
                AccountId.class,
                AggregateIdSerializer.serializerFor(AccountId.class),
                new HandlesCommandsThatInheritFromCommandType(AccountCommand.class),
                cmd -> ((AccountCommand) cmd).id(),
                event -> ((AccountEvent) event).id());
    }

    @Bean
    public OpenAccountDecider openAccountDecider() {
        return new OpenAccountDecider();
    }
}
