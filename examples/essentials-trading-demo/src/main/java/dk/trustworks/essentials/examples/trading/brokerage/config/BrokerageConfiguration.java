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

package dk.trustworks.essentials.examples.trading.brokerage.config;

import dk.trustworks.essentials.components.eventsourced.aggregates.EssentialsAggregateDeclarations;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Settlement;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Settlements;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Trade;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Trades;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccount;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.examples.trading.brokerage.events.TradingAccountEvent;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Spring wiring for the {@code brokerage} bounded context.
 *
 * <p>Only the {@link TradingAccount} closing-books chain is assembled here. {@code Trades} and {@code Settlements}
 * build their own {@link StatefulAggregateRepository} in their constructors, the way the house template's
 * {@code Accounts} does, so they need no bean of their own — a trading account needs six collaborating framework
 * objects to support generation rollover, which is more than a repository wrapper should assemble for itself.
 *
 * <p>The {@link AggregateType} constants that used to live in a module-wide configuration class now live on the
 * repository wrappers ({@link TradingAccounts#AGGREGATE_TYPE} and its two siblings), beside the aggregates whose
 * streams they name.
 */
@Configuration
@EnableConfigurationProperties(TradingAccountClosingBooksProperties.class)
public class BrokerageConfiguration {

    /**
     * Declares this context's aggregates, which is what makes the {@code @AggregateSnapshotPolicy} and
     * {@code @AggregateClosingBooksPolicy} on {@link TradingAccount} take effect.
     * <p>
     * The framework registers those annotations from bean post-processors, which only observe Spring beans. An
     * aggregate root is not one — a singleton instance of one would be meaningless — so without a declaration the
     * annotations reach no registry, the admin console shows no policies, and nothing says why. Each bounded context
     * declares its own aggregates; every {@code EssentialsAggregateDeclarations} bean in the context is merged.
     * <p>
     * {@code Trade} and {@code Settlement} carry no policy today and are declared anyway, so that adding one later is
     * enough on its own.
     */
    @Bean
    public EssentialsAggregateDeclarations brokerageAggregates() {
        return EssentialsAggregateDeclarations.builder()
                                             .declare(TradingAccounts.AGGREGATE_TYPE, TradingAccount.class)
                                             .declare(Trades.AGGREGATE_TYPE, Trade.class)
                                             .declare(Settlements.AGGREGATE_TYPE, Settlement.class)
                                             .build();
    }

    /**
     * The generation repository, the coordinator and the admin API's generation access for {@link TradingAccount},
     * assembled from the two id types.
     * <p>
     * The meter registry matters because TradingAccounts roll over via {@code ON_ACCESS}, which never runs a scheduled
     * scan — the coordinator is the only place the rollover can be measured, so the registry has to reach it for the
     * admin UI's closing-books statistics to show anything.
     */
    @Bean
    public ClosingBooksSetup<TradingAccountId, TradingAccountGenerationId> tradingAccountClosingBooks(
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
            Optional<MeterRegistry> meterRegistry) {
        return ClosingBooksSetup.<TradingAccountId, TradingAccountGenerationId>builder(TradingAccounts.AGGREGATE_TYPE,
                                                                                      TradingAccount.class)
                                .setLogicalAggregateIdType(TradingAccountId.class)
                                .setStreamIdType(TradingAccountGenerationId.class)
                                // NOT the framework default, which concatenates '#' itself: the <logicalId>#<generation>
                                // convention lives on the id type, which is also what the account_statement view parses
                                // it back out with. Keeping it here is what stops the two drifting apart.
                                .setStreamIdGenerator((aggregateType, logicalAggregateId, generation) ->
                                                              TradingAccountGenerationId.of(logicalAggregateId.value(), generation).toString())
                                .setUnitOfWorkFactory(unitOfWorkFactory)
                                .setMeterRegistry(meterRegistry)
                                .build();
    }

    /**
     * The setup's generation repository, published as its own bean because the load generator, the startup runner and
     * the application test read generation metadata directly.
     */
    @Bean
    public ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository(
            ClosingBooksSetup<TradingAccountId, TradingAccountGenerationId> tradingAccountClosingBooks) {
        return tradingAccountClosingBooks.generationRepository();
    }

    @Bean
    public StatefulAggregateRepository<TradingAccountGenerationId, TradingAccountEvent, TradingAccount> tradingAccountStreamRepository(
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
            Optional<AggregateSnapshotRepositoryProvider> aggregateSnapshotRepositoryProvider) {
        return StatefulAggregateRepository.builder(eventStore)
                                         .setAggregateType(TradingAccounts.AGGREGATE_TYPE)
                                         .setAggregateImplementationType(TradingAccount.class)
                                         .setAggregateSnapshotRepositoryProvider(aggregateSnapshotRepositoryProvider)
                                         .build();
    }

    @Bean
    public ClosingBooksLogicalAggregateRepository<TradingAccountId, TradingAccountGenerationId, TradingAccountEvent, TradingAccount> tradingAccountRepository(
            ClosingBooksSetup<TradingAccountId, TradingAccountGenerationId> tradingAccountClosingBooks,
            StatefulAggregateRepository<TradingAccountGenerationId, TradingAccountEvent, TradingAccount> tradingAccountStreamRepository) {
        return tradingAccountClosingBooks.logicalAggregateRepository(tradingAccountStreamRepository);
    }
}
