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

package dk.trustworks.essentials.examples.trading.config;

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotRepositoryProvider;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateInstanceFactory;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicyRegistry;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicyDescriptor;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicyRegistry;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicyDescriptor;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.annotation.AnnotationUtils;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccount;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountEvent;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountGenerationId;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountId;
import dk.trustworks.essentials.examples.trading.instruments.Instrument;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentEvent;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentId;
import dk.trustworks.essentials.examples.trading.prices.InstrumentPrice;
import dk.trustworks.essentials.examples.trading.prices.InstrumentPriceEvent;
import dk.trustworks.essentials.examples.trading.settlements.Settlement;
import dk.trustworks.essentials.examples.trading.settlements.SettlementEvent;
import dk.trustworks.essentials.examples.trading.settlements.SettlementId;
import dk.trustworks.essentials.examples.trading.trades.Trade;
import dk.trustworks.essentials.examples.trading.trades.TradeEvent;
import dk.trustworks.essentials.examples.trading.trades.TradeId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Registers event-store-backed repositories for the trading demo aggregates.
 */
@Configuration
public class TradingDemoAggregateConfiguration {
    public static final AggregateType TRADING_ACCOUNTS = AggregateType.of("TradingAccounts");
    public static final AggregateType SETTLEMENTS = AggregateType.of("Settlements");
    public static final AggregateType INSTRUMENTS = AggregateType.of("Instruments");
    public static final AggregateType TRADES = AggregateType.of("Trades");
    public static final AggregateType INSTRUMENT_PRICES = AggregateType.of("InstrumentPrices");


    /**
     * Publishes the policies {@link TradingAccount} declares to the policy registries, which is what the admin API's
     * lifecycle endpoints report.
     * <p>
     * The framework only registers these from its two bean post-processors, so the annotations reach a registry only
     * when the annotated class is itself a Spring bean. An aggregate root is not — a singleton instance of one would be
     * meaningless — so the annotations on TradingAccount would otherwise be inert and the console would show no
     * policies. The annotation stays the single source of the values; this only carries them across.
     */
    @Bean
    public InitializingBean tradingAccountPolicyRegistrations(AggregateSnapshotPolicyRegistry snapshotPolicyRegistry,
                                                              AggregateClosingBooksPolicyRegistry closingBooksPolicyRegistry) {
        return () -> {
            var snapshotPolicy = AnnotationUtils.findAnnotation(TradingAccount.class,
                                                                dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.AggregateSnapshotPolicy.class);
            if (snapshotPolicy != null) {
                snapshotPolicyRegistry.register(new AggregateSnapshotPolicyDescriptor(TradingAccount.class,
                                                                                     Optional.of(TRADING_ACCOUNTS.toString()),
                                                                                     snapshotPolicy));
            }
            var closingBooksPolicy = AnnotationUtils.findAnnotation(TradingAccount.class,
                                                                   dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.AggregateClosingBooksPolicy.class);
            if (closingBooksPolicy != null) {
                closingBooksPolicyRegistry.register(new AggregateClosingBooksPolicyDescriptor(TradingAccount.class,
                                                                                              Optional.of(TRADING_ACCOUNTS.toString()),
                                                                                              closingBooksPolicy));
            }
        };
    }

    @Bean
    public PostgresqlClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository(
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        return new PostgresqlClosingBooksGenerationRepository<TradingAccountId>(unitOfWorkFactory,
                                                                                Optional.empty(),
                                                                                new ClosingBooksLogicalAggregateIdSerializer<TradingAccountId>() {
                                                                    @Override
                                                                    public String serialize(LogicalAggregateId<TradingAccountId> logicalAggregateId) {
                                                                        return logicalAggregateId.value().toString();
                                                                    }

                                                                    @Override
                                                                    public LogicalAggregateId<TradingAccountId> deserialize(String persistedValue) {
                                                                        return new LogicalAggregateId<>(TradingAccountId.of(persistedValue));
                                                                    }
                                                                });
    }

    @Bean
    public TypedAggregateClosingBooksGenerationAccess<TradingAccountId> tradingAccountClosingBooksGenerationAccess(
            ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository) {
        return new TypedAggregateClosingBooksGenerationAccess<>() {
            @Override
            public AggregateType aggregateType() {
                return TRADING_ACCOUNTS;
            }

            @Override
            public Class<?> aggregateImplementationType() {
                return TradingAccount.class;
            }

            @Override
            public ClosingBooksGenerationRepository<TradingAccountId> generationRepository() {
                return tradingAccountGenerationRepository;
            }

            @Override
            public ClosingBooksLogicalAggregateIdSerializer<TradingAccountId> logicalAggregateIdSerializer() {
                return new ClosingBooksLogicalAggregateIdSerializer<>() {
                    @Override
                    public String serialize(LogicalAggregateId<TradingAccountId> logicalAggregateId) {
                        return logicalAggregateId.value().toString();
                    }

                    @Override
                    public LogicalAggregateId<TradingAccountId> deserialize(String persistedValue) {
                        return new LogicalAggregateId<>(TradingAccountId.of(persistedValue));
                    }
                };
            }
        };
    }

    @Bean
    public ClosingBooksCoordinator<TradingAccountId> tradingAccountClosingBooksCoordinator(
            ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository,
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory) {
        return new ClosingBooksCoordinator<>(TRADING_ACCOUNTS,
                                             tradingAccountGenerationRepository,
                                             (aggregateType, logicalAggregateId, generation) -> logicalAggregateId.value() + "#" + generation,
                                             unitOfWorkFactory);
    }

    @Bean
    public StatefulAggregateRepository<TradingAccountGenerationId, TradingAccountEvent, TradingAccount> tradingAccountStreamRepository(
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
            Optional<AggregateSnapshotRepositoryProvider> aggregateSnapshotRepositoryProvider) {
        return aggregateSnapshotRepositoryProvider
                .map(provider -> StatefulAggregateRepository.fromUsingSnapshotRepositoryProvider(
                        eventStore,
                        TRADING_ACCOUNTS,
                        StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                        TradingAccount.class,
                        provider))
                .orElseGet(() -> StatefulAggregateRepository.from(
                        eventStore,
                        TRADING_ACCOUNTS,
                        StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                        TradingAccount.class));
    }

    @Bean
    public ClosingBooksLogicalAggregateRepository<TradingAccountId, TradingAccountGenerationId, TradingAccountEvent, TradingAccount> tradingAccountRepository(
            StatefulAggregateRepository<TradingAccountGenerationId, TradingAccountEvent, TradingAccount> tradingAccountStreamRepository,
            ClosingBooksCoordinator<TradingAccountId> tradingAccountClosingBooksCoordinator) {
        return new ClosingBooksLogicalAggregateRepository<>(TRADING_ACCOUNTS,
                                                            tradingAccountStreamRepository,
                                                            tradingAccountClosingBooksCoordinator,
                                                            new ClosingBooksStreamIdSerializer<>() {
                                                                @Override
                                                                public String serialize(TradingAccountGenerationId streamId) {
                                                                    return streamId.toString();
                                                                }

                                                                @Override
                                                                public TradingAccountGenerationId deserialize(String persistedStreamId) {
                                                                    return TradingAccountGenerationId.of(persistedStreamId);
                                                                }
                                                            });
    }

    @Bean
    public StatefulAggregateRepository<SettlementId, SettlementEvent, Settlement> settlementRepository(
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        return StatefulAggregateRepository.from(
                eventStore,
                SETTLEMENTS,
                StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                Settlement.class);
    }

    @Bean
    public StatefulAggregateRepository<TradeId, TradeEvent, Trade> tradeRepository(
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        return StatefulAggregateRepository.from(
                eventStore,
                TRADES,
                StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                Trade.class);
    }

    @Bean
    public StatefulAggregateRepository<InstrumentId, InstrumentEvent, Instrument> instrumentRepository(
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        return StatefulAggregateRepository.from(
                eventStore,
                INSTRUMENTS,
                StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                Instrument.class);
    }

    @Bean
    public StatefulAggregateRepository<InstrumentId, InstrumentPriceEvent, InstrumentPrice> instrumentPriceRepository(
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        return StatefulAggregateRepository.from(
                eventStore,
                INSTRUMENT_PRICES,
                StatefulAggregateInstanceFactory.reflectionBasedAggregateRootFactory(),
                InstrumentPrice.class);
    }
}
