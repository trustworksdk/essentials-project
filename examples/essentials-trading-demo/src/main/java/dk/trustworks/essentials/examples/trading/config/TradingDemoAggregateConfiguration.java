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

import dk.trustworks.essentials.components.eventsourced.aggregates.EssentialsAggregateDeclarations;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.snapshot.*;
import dk.trustworks.essentials.components.eventsourced.aggregates.stateful.StatefulAggregateRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.ConfigurableEventStore;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.table_per_aggregate_type.SeparateTablePerAggregateEventStreamConfiguration;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork;
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory;
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
import io.micrometer.core.instrument.MeterRegistry;
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
     * Tells the framework which aggregate implementation class serves which {@link AggregateType}.
     * <p>
     * This is what makes the {@link AggregateSnapshotPolicy} and {@link AggregateClosingBooksPolicy} annotations on
     * {@link TradingAccount} and {@link InstrumentPrice} take effect: an aggregate root is not a Spring bean — a
     * singleton instance of one would be meaningless — so the framework's policy bean post-processors never see it.
     * Without this bean the annotations would reach no registry and the admin console would report no policies, with
     * nothing to explain why.
     */
    @Bean
    public EssentialsAggregateDeclarations tradingAggregates() {
        return EssentialsAggregateDeclarations.builder()
                                              .declare(TRADING_ACCOUNTS, TradingAccount.class)
                                              .declare(INSTRUMENT_PRICES, InstrumentPrice.class)
                                              .declare(SETTLEMENTS, Settlement.class)
                                              .declare(TRADES, Trade.class)
                                              .declare(INSTRUMENTS, Instrument.class)
                                              .build();
    }

    /**
     * Everything closing books needs for {@link TradingAccount}: the generation repository, the coordinator and the
     * admin API's generation access, assembled from the two id types.
     * <p>
     * The meter registry matters here because TradingAccounts roll over via {@code ON_ACCESS}, which never runs a
     * scheduled scan — the coordinator is the only place the rollover can be measured, so the registry has to reach it
     * for the admin UI's closing-books statistics to show anything.
     */
    @Bean
    public ClosingBooksSetup<TradingAccountId, TradingAccountGenerationId> tradingAccountClosingBooks(
            HandleAwareUnitOfWorkFactory<? extends HandleAwareUnitOfWork> unitOfWorkFactory,
            Optional<MeterRegistry> meterRegistry) {
        return ClosingBooksSetup.<TradingAccountId, TradingAccountGenerationId>builder(TRADING_ACCOUNTS, TradingAccount.class)
                                .setLogicalAggregateIdType(TradingAccountId.class)
                                .setStreamIdType(TradingAccountGenerationId.class)
                                .setUnitOfWorkFactory(unitOfWorkFactory)
                                .setMeterRegistry(meterRegistry)
                                .build();
    }

    /**
     * The setup's generation repository, published as its own bean because the load generator and the startup runner
     * read generation metadata directly.
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
                                          .setAggregateType(TRADING_ACCOUNTS)
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

    @Bean
    public StatefulAggregateRepository<SettlementId, SettlementEvent, Settlement> settlementRepository(
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        return StatefulAggregateRepository.builder(eventStore)
                                          .setAggregateType(SETTLEMENTS)
                                          .setAggregateImplementationType(Settlement.class)
                                          .build();
    }

    @Bean
    public StatefulAggregateRepository<TradeId, TradeEvent, Trade> tradeRepository(
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        return StatefulAggregateRepository.builder(eventStore)
                                          .setAggregateType(TRADES)
                                          .setAggregateImplementationType(Trade.class)
                                          .build();
    }

    @Bean
    public StatefulAggregateRepository<InstrumentId, InstrumentEvent, Instrument> instrumentRepository(
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore) {
        return StatefulAggregateRepository.builder(eventStore)
                                          .setAggregateType(INSTRUMENTS)
                                          .setAggregateImplementationType(Instrument.class)
                                          .build();
    }

    /**
     * InstrumentPrice declares an {@link AggregateSnapshotPolicy}, which {@link #tradingAggregates()} publishes so the
     * admin console reports it. The policy only takes effect on the load path when the repository is built with the
     * snapshot repository provider — a repository built without it passes a null snapshot repository and every load
     * replays the whole stream, which is quadratic under the price-stress runs this aggregate exists to demonstrate.
     */
    @Bean
    public StatefulAggregateRepository<InstrumentId, InstrumentPriceEvent, InstrumentPrice> instrumentPriceRepository(
            ConfigurableEventStore<SeparateTablePerAggregateEventStreamConfiguration> eventStore,
            Optional<AggregateSnapshotRepositoryProvider> aggregateSnapshotRepositoryProvider) {
        return StatefulAggregateRepository.builder(eventStore)
                                          .setAggregateType(INSTRUMENT_PRICES)
                                          .setAggregateImplementationType(InstrumentPrice.class)
                                          .setAggregateSnapshotRepositoryProvider(aggregateSnapshotRepositoryProvider)
                                          .build();
    }
}
