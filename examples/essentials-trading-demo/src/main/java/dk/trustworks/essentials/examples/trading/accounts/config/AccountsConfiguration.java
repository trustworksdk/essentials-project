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

package dk.trustworks.essentials.examples.trading.accounts.config;

import dk.trustworks.essentials.components.boot.autoconfigure.postgresql.EssentialsComponentsProperties;
import dk.trustworks.essentials.components.eventsourced.aggregates.EssentialsAggregateDeclarations;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotRepositoryProvider;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.*;
import dk.trustworks.essentials.examples.trading.accounts.aggregates.*;
import dk.trustworks.essentials.examples.trading.accounts.events.AccountEvent;
import dk.trustworks.essentials.examples.trading.accounts.types.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

import java.util.Optional;

/**
 * Spring wiring for the {@code accounts} bounded context: closing books and snapshots for {@link Account}, the same
 * shape as {@code BrokerageConfiguration} for the frozen {@code TradingAccount}, under its own {@code Accounts}
 * aggregate type.
 */
@Configuration
@EnableConfigurationProperties(AccountsProperties.class)
public class AccountsConfiguration {

    /**
     * Declaring {@link Account} is what carries its {@code @AggregateSnapshotPolicy} and
     * {@code @AggregateClosingBooksPolicy} into the registries; undeclared, both annotations are inert and nothing says
     * so (see the demo's root {@code CLAUDE.md}).
     */
    @Bean
    public EssentialsAggregateDeclarations accountsAggregates() {
        return EssentialsAggregateDeclarations.builder()
                                              .declare(Accounts.AGGREGATE_TYPE, Account.class)
                                              .build();
    }

    @Bean
    public ClosingBooksSetup<AccountId, AccountGenerationId> accountClosingBooks(HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
                                                                                 Optional<MeterRegistry> meterRegistry,
                                                                                 EssentialsComponentsProperties essentialsComponentsProperties) {
        return ClosingBooksSetup.<AccountId, AccountGenerationId>builder(Accounts.AGGREGATE_TYPE, Account.class)
                                .setLogicalAggregateIdType(AccountId.class)
                                .setStreamIdType(AccountGenerationId.class)
                                // The <accountId>#<generation> convention lives on AccountGenerationId, so the
                                // projections that parse it back cannot drift from this writer
                                .setStreamIdGenerator((aggregateType, logicalAggregateId, generation) ->
                                                              AccountGenerationId.of(logicalAggregateId.value(), generation).toString())
                                .setUnitOfWorkFactory(unitOfWorkFactory)
                                .setMeterRegistry(meterRegistry)
                                .setSchemaOwnership(essentialsComponentsProperties.getSchema().getMode().schemaOwnership())
                                .build();
    }

    @Bean
    public ClosingBooksGenerationRepository<AccountId> accountGenerationRepository(ClosingBooksSetup<AccountId, AccountGenerationId> accountClosingBooks) {
        return accountClosingBooks.generationRepository();
    }

    @Bean
    public StatefulAggregateRepository<AccountGenerationId, AccountEvent, Account> accountStreamRepository(ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
                                                                                                         Optional<AggregateSnapshotRepositoryProvider> aggregateSnapshotRepositoryProvider) {
        return StatefulAggregateRepository.builder(eventStore)
                                          .setAggregateType(Accounts.AGGREGATE_TYPE)
                                          .setAggregateImplementationType(Account.class)
                                          .setAggregateSnapshotRepositoryProvider(aggregateSnapshotRepositoryProvider)
                                          .build();
    }

    @Bean
    public ClosingBooksLogicalAggregateRepository<AccountId, AccountGenerationId, AccountEvent, Account> accountRepository(ClosingBooksSetup<AccountId, AccountGenerationId> accountClosingBooks,
                                                                                                                          StatefulAggregateRepository<AccountGenerationId, AccountEvent, Account> accountStreamRepository) {
        return accountClosingBooks.logicalAggregateRepository(accountStreamRepository);
    }
}
