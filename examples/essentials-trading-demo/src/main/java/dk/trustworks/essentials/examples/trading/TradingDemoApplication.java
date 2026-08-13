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

package dk.trustworks.essentials.examples.trading;

import dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateLifecycleApi;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksGenerationRepository;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.examples.trading._demo_harness.DirectInstrumentPriceService;
import dk.trustworks.essentials.examples.trading._demo_harness.TradingDashboardStreamService;
import dk.trustworks.essentials.examples.trading._demo_harness.TradingDemoLoadGeneratorProperties;
import dk.trustworks.essentials.examples.trading._demo_harness.TradingDemoSimulationProperties;
import dk.trustworks.essentials.examples.trading._demo_harness.TradingLoadGeneratorManager;
import dk.trustworks.essentials.examples.trading._demo_harness.TradingSimulationRunner;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Settlements;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccountClosingBooksPolicy;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Trades;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.brokerage.views.account_statement.AccountStatementQuery;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instruments;
import dk.trustworks.essentials.examples.trading.market_data.views.latest_price.LatestPriceQuery;
import dk.trustworks.essentials.reactive.command.CommandBus;
import io.micrometer.core.instrument.MeterRegistry;
import dk.trustworks.essentials.shared.security.EssentialsAuthenticatedUser;
import dk.trustworks.essentials.shared.security.EssentialsSecurityProvider;

import java.time.Clock;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

/**
 * Entry point for the headless trading demo application.
 * <p>
 * Only the two {@code _demo_harness} properties classes are registered here. {@code TradingAccountClosingBooksProperties}
 * belongs to the {@code brokerage} context and is registered by its own {@code BrokerageConfiguration}.
 */
@SpringBootApplication
@EnableConfigurationProperties({TradingDemoSimulationProperties.class,
        TradingDemoLoadGeneratorProperties.class})
public class TradingDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(TradingDemoApplication.class, args);
    }

    /**
     * Demo-only security: every caller is authenticated as the same principal and authorized for everything, which is
     * what makes the admin console usable without wiring an identity provider into a sample application. The admin API
     * authenticates nobody itself — it asks these two beans — so without them every request answers 401.
     * <p>
     * Never do this in a real application: it authorizes destructive admin operations for anonymous callers.
     */
    @Bean
    public EssentialsAuthenticatedUser essentialsAuthenticatedUser() {
        return new EssentialsAuthenticatedUser.AllAccessAuthenticatedUser();
    }

    @Bean
    public EssentialsSecurityProvider essentialsSecurityProvider() {
        return new EssentialsSecurityProvider.AllAccessSecurityProvider();
    }

    @Bean
    public Clock tradingDemoClock() {
        return Clock.systemDefaultZone();
    }

    /**
     * The bootstrap seeder. It writes through the {@link CommandBus} like everything else in the harness; the four
     * repository wrappers are here only for its strongly-consistent idempotency probe — see
     * {@code TradingSimulationRunner.seedDataState()}.
     */
    @Bean
    public ApplicationRunner tradingSimulationRunner(TradingDemoSimulationProperties properties,
                                                     CommandBus commandBus,
                                                     TradingAccountClosingBooksPolicy closingBooksPolicy,
                                                     AggregateLifecycleApi aggregateLifecycleApi,
                                                     LatestPriceQuery latestPriceQuery,
                                                     DirectInstrumentPriceService directInstrumentPriceService,
                                                     EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                     TradingAccounts tradingAccounts,
                                                     Trades trades,
                                                     Settlements settlements,
                                                     Instruments instruments) {
        return new TradingSimulationRunner(properties,
                                           commandBus,
                                           closingBooksPolicy,
                                           aggregateLifecycleApi,
                                           latestPriceQuery,
                                           directInstrumentPriceService,
                                           unitOfWorkFactory,
                                           tradingAccounts,
                                           trades,
                                           settlements,
                                           instruments);
    }

    @Bean
    public TradingLoadGeneratorManager tradingLoadGeneratorManager(TradingDemoSimulationProperties simulationProperties,
                                                                   TradingDemoLoadGeneratorProperties loadProperties,
                                                                   CommandBus commandBus,
                                                                   TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy,
                                                                   ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository,
                                                                   AccountStatementQuery accountStatementQuery,
                                                                   LatestPriceQuery latestPriceQuery,
                                                                   TradingAccounts tradingAccounts,
                                                                   EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                                                   DirectInstrumentPriceService directInstrumentPriceService,
                                                                   ObjectProvider<MeterRegistry> meterRegistryProvider,
                                                                   ObjectProvider<TradingDashboardStreamService> tradingDashboardStreamServiceProvider) {
        var manager = new TradingLoadGeneratorManager(simulationProperties,
                                                      loadProperties,
                                                      commandBus,
                                                      tradingAccountClosingBooksPolicy,
                                                      tradingAccountGenerationRepository,
                                                      accountStatementQuery,
                                                      latestPriceQuery,
                                                      tradingAccounts,
                                                      unitOfWorkFactory,
                                                      directInstrumentPriceService,
                                                      meterRegistryProvider.stream().findFirst());
        manager.addStatusListener(ignored -> {
            var streamService = tradingDashboardStreamServiceProvider.getIfAvailable();
            if (streamService != null) {
                streamService.broadcastSummaryThrottled();
            }
        });
        return manager;
    }
}
