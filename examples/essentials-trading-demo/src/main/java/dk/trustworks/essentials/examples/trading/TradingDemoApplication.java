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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksGenerationRepository;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountClosingBooksPolicy;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountClosingBooksProperties;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountId;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountService;
import dk.trustworks.essentials.examples.trading.dashboard.TradingDashboardStreamService;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentService;
import dk.trustworks.essentials.examples.trading.prices.DirectInstrumentPriceService;
import dk.trustworks.essentials.examples.trading.prices.InstrumentPriceService;
import dk.trustworks.essentials.examples.trading.settlements.SettlementService;
import dk.trustworks.essentials.examples.trading.simulation.TradingDemoLoadGeneratorProperties;
import dk.trustworks.essentials.examples.trading.simulation.TradingLoadGeneratorManager;
import dk.trustworks.essentials.examples.trading.simulation.TradingDemoSimulationProperties;
import dk.trustworks.essentials.examples.trading.simulation.TradingSimulationRunner;
import dk.trustworks.essentials.examples.trading.trades.TradeService;
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
 */
@SpringBootApplication
@EnableConfigurationProperties({TradingDemoSimulationProperties.class,
        TradingDemoLoadGeneratorProperties.class,
        TradingAccountClosingBooksProperties.class})
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

    @Bean
    public ApplicationRunner tradingSimulationRunner(TradingDemoSimulationProperties properties,
                                                     TradingAccountService tradingAccountService,
                                                     TradingAccountClosingBooksPolicy closingBooksPolicy,
                                                     SettlementService settlementService,
                                                     InstrumentService instrumentService,
                                                     DirectInstrumentPriceService directInstrumentPriceService,
                                                     InstrumentPriceService instrumentPriceService,
                                                     TradeService tradeService) {
        return new TradingSimulationRunner(properties,
                                           tradingAccountService,
                                           closingBooksPolicy,
                                           settlementService,
                                           instrumentService,
                                           directInstrumentPriceService,
                                           instrumentPriceService,
                                           tradeService);
    }

    @Bean
    public TradingLoadGeneratorManager tradingLoadGeneratorManager(TradingDemoSimulationProperties simulationProperties,
                                                                  TradingDemoLoadGeneratorProperties loadProperties,
                                                                  TradingAccountService tradingAccountService,
                                                                  TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy,
                                                                  ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository,
                                                                  InstrumentPriceService instrumentPriceService,
                                                                  DirectInstrumentPriceService directInstrumentPriceService,
                                                                  SettlementService settlementService,
                                                                  TradeService tradeService,
                                                                  ObjectProvider<MeterRegistry> meterRegistryProvider,
                                                                  ObjectProvider<TradingDashboardStreamService> tradingDashboardStreamServiceProvider) {
        var manager = new TradingLoadGeneratorManager(simulationProperties,
                                                     loadProperties,
                                                     tradingAccountService,
                                                     tradingAccountClosingBooksPolicy,
                                                     tradingAccountGenerationRepository,
                                                     instrumentPriceService,
                                                     directInstrumentPriceService,
                                                     settlementService,
                                                     tradeService,
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
