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

package dk.trustworks.essentials.examples.trading.simulation;

import dk.trustworks.essentials.examples.trading.accounts.TradingAccountService;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountId;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentService;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentId;
import dk.trustworks.essentials.examples.trading.prices.DirectInstrumentPriceService;
import dk.trustworks.essentials.examples.trading.prices.InstrumentPriceService;
import dk.trustworks.essentials.examples.trading.settlements.SettlementService;
import dk.trustworks.essentials.examples.trading.settlements.SettlementId;
import dk.trustworks.essentials.examples.trading.trades.TradeId;
import dk.trustworks.essentials.examples.trading.trades.TradeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless demo runner that exercises the example aggregates against the event store at startup.
 */
public class TradingSimulationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TradingSimulationRunner.class);
    private static final List<InstrumentSeed> INSTRUMENT_SEEDS = List.of(
            new InstrumentSeed("AAPL", "Apple Inc."),
            new InstrumentSeed("MSFT", "Microsoft Corporation"),
            new InstrumentSeed("NVDA", "NVIDIA Corporation"),
            new InstrumentSeed("AMZN", "Amazon.com, Inc."),
            new InstrumentSeed("GOOGL", "Alphabet Inc. Class A"),
            new InstrumentSeed("META", "Meta Platforms, Inc."),
            new InstrumentSeed("TSLA", "Tesla, Inc."),
            new InstrumentSeed("JPM", "JPMorgan Chase & Co."),
            new InstrumentSeed("SAP", "SAP SE"),
            new InstrumentSeed("NOVO-B", "Novo Nordisk A/S B")
    );

    private final TradingDemoSimulationProperties properties;
    private final TradingAccountService           tradingAccountService;
    private final SettlementService               settlementService;
    private final InstrumentService               instrumentService;
    private final DirectInstrumentPriceService    directInstrumentPriceService;
    private final InstrumentPriceService          instrumentPriceService;
    private final TradeService                    tradeService;

    public TradingSimulationRunner(TradingDemoSimulationProperties properties,
                                  TradingAccountService tradingAccountService,
                                  SettlementService settlementService,
                                  InstrumentService instrumentService,
                                  DirectInstrumentPriceService directInstrumentPriceService,
                                  InstrumentPriceService instrumentPriceService,
                                  TradeService tradeService) {
        this.properties = properties;
        this.tradingAccountService = tradingAccountService;
        this.settlementService = settlementService;
        this.instrumentService = instrumentService;
        this.directInstrumentPriceService = directInstrumentPriceService;
        this.instrumentPriceService = instrumentPriceService;
        this.tradeService = tradeService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("Trading demo simulation is disabled");
            return;
        }

        var seedState = seedDataState();
        if (seedState == SeedState.COMPLETE) {
            log.info("Trading demo simulation detected existing seed data and will skip bootstrap");
            logEndpointHints();
            return;
        }
        if (seedState == SeedState.LEGACY_OR_PARTIAL) {
            log.warn("Trading demo simulation detected existing but incomplete demo data. This usually means the local database was initialized by an older demo version before trades, prices, or the newer settlement ids were added.");
            log.warn("For a clean local run, remove the local demo database/volume and start the app again so the simulator can reseed the current dataset.");
            log.warn("Expected current demo ids include account '{}', trade '{}', and settlement '{}'.",
                     firstDemoAccountId(),
                     firstDemoTradeId(),
                     firstDemoSettlementId());
            return;
        }

        log.info("Trading demo simulation bootstrap starting with {} accounts, {} instruments, {} settlements per account, rolloverAccounts={}, initialPeriodId={}, nextPeriodId={}",
                 properties.getAccountCount(),
                 properties.getInstrumentCount(),
                 properties.getSettlementsPerAccount(),
                 properties.isRolloverAccounts(),
                 properties.getInitialPeriodId(),
                 properties.getNextPeriodId());

        for (int instrumentIndex = 0; instrumentIndex < properties.getInstrumentCount(); instrumentIndex++) {
            var seed = INSTRUMENT_SEEDS.get(instrumentIndex % INSTRUMENT_SEEDS.size());
            var instrumentId = InstrumentId.of(seed.symbol());
            instrumentService.registerInstrument(instrumentId,
                                                 seed.symbol(),
                                                 seed.displayName());
            instrumentService.rename(instrumentId, seed.displayName() + " (Demo)");
            directInstrumentPriceService.initializePrice(instrumentId,
                                                         BigDecimal.valueOf(475 + (instrumentIndex * 12L)));
            instrumentPriceService.initializePrice(instrumentId,
                                                   BigDecimal.valueOf(475 + (instrumentIndex * 12L)));
        }

        log.info("Trading demo simulation seeded {} instruments using realistic ticker symbols such as {} and {}",
                 properties.getInstrumentCount(),
                 INSTRUMENT_SEEDS.get(0).symbol(),
                 INSTRUMENT_SEEDS.get(Math.min(1, INSTRUMENT_SEEDS.size() - 1)).symbol());

        for (int accountIndex = 0; accountIndex < properties.getAccountCount(); accountIndex++) {
            var accountId = TradingAccountId.of("ACC-DEMO-%03d".formatted(accountIndex + 1));
            tradingAccountService.openAccount(accountId,
                                              "demo-owner-" + (accountIndex + 1),
                                              properties.getInitialPeriodId());

            for (int depositIndex = 0; depositIndex < properties.getDepositsPerAccount(); depositIndex++) {
                tradingAccountService.depositCash(accountId, BigDecimal.valueOf(1_000L * (depositIndex + 1)));
            }

            tradingAccountService.reserveFunds(accountId, BigDecimal.valueOf(250));
            tradingAccountService.releaseFunds(accountId, BigDecimal.valueOf(100));

            for (int settlementIndex = 0; settlementIndex < properties.getSettlementsPerAccount(); settlementIndex++) {
                var instrumentSeed = INSTRUMENT_SEEDS.get(settlementIndex % Math.max(1, Math.min(properties.getInstrumentCount(), INSTRUMENT_SEEDS.size())));
                var tradeId = TradeId.of("TRD-%03d-%03d".formatted(accountIndex + 1, settlementIndex + 1));
                var settlementId = SettlementId.of(tradeId + "-SET");
                var tradeGrossAmount = BigDecimal.valueOf(500);

                tradeService.placeTrade(tradeId,
                                        accountId,
                                        InstrumentId.of(instrumentSeed.symbol()),
                                        settlementIndex % 2 == 0 ? "BUY" : "SELL",
                                        BigDecimal.ONE,
                                        tradeGrossAmount);
                tradeService.executeTrade(tradeId);
                tradeService.requestSettlement(tradeId, settlementId.toString());
                directInstrumentPriceService.updatePrice(InstrumentId.of(instrumentSeed.symbol()),
                                                         tradeGrossAmount.add(BigDecimal.valueOf(15 + settlementIndex)));
                instrumentPriceService.updatePrice(InstrumentId.of(instrumentSeed.symbol()),
                                                   tradeGrossAmount.add(BigDecimal.valueOf(15 + settlementIndex)));

                settlementService.createSettlement(settlementId,
                                                   tradeId.toString(),
                                                   accountId.toString(),
                                                   tradeGrossAmount);
                settlementService.requestClearing(settlementId);
                settlementService.confirmClearing(settlementId);
                settlementService.markSettled(settlementId);
                settlementService.reconcile(settlementId);
                settlementService.closeSettlement(settlementId);
                tradeService.markSettled(tradeId);

                tradingAccountService.applyTradeSettlement(accountId,
                                                          tradeId.toString(),
                                                          BigDecimal.valueOf(-500),
                                                          BigDecimal.valueOf(12));
            }

            if (properties.isRolloverAccounts()) {
                log.info("Trading demo simulation is closing books and opening the next period for account {}. The admin endpoint will therefore show the current account generation as 2 after bootstrap.",
                         accountId);
                tradingAccountService.closeBooksAndOpenNextPeriod(accountId, properties.getNextPeriodId());
            } else {
                log.info("Trading demo simulation is closing books without opening the next generation for account {}. The admin endpoint will therefore show the account as closed in generation 1.",
                         accountId);
                tradingAccountService.closeBooks(accountId, properties.getNextPeriodId());
            }
        }

        log.info("Trading demo simulation completed with {} accounts, {} instruments, {} settlements per account, rolloverAccounts={}",
                 properties.getAccountCount(),
                 properties.getInstrumentCount(),
                 properties.getSettlementsPerAccount(),
                 properties.isRolloverAccounts());
        logEndpointHints();
    }

    private SeedState seedDataState() {
        var firstSeedInstrument = INSTRUMENT_SEEDS.get(0);
        var hasInstrument = instrumentService.tryLoad(InstrumentId.of(firstSeedInstrument.symbol())).isPresent();
        var hasDirectPrice = directInstrumentPriceService.tryLoad(InstrumentId.of(firstSeedInstrument.symbol())).isPresent();
        var hasPrice = instrumentPriceService.tryLoad(InstrumentId.of(firstSeedInstrument.symbol())).isPresent();
        var hasAccount = tradingAccountService.tryLoad(TradingAccountId.of(firstDemoAccountId())).isPresent();
        var hasTrade = tradeService.tryLoad(TradeId.of(firstDemoTradeId())).isPresent();
        var hasSettlement = settlementService.tryLoad(SettlementId.of(firstDemoSettlementId())).isPresent();

        if (hasInstrument && hasDirectPrice && hasPrice && hasAccount && hasTrade && hasSettlement) {
            return SeedState.COMPLETE;
        }
        if (hasInstrument || hasDirectPrice || hasPrice || hasAccount || hasTrade || hasSettlement) {
            return SeedState.LEGACY_OR_PARTIAL;
        }
        return SeedState.NONE;
    }

    private void logEndpointHints() {
        log.info("Trading demo admin endpoint hints:");
        log.info("  account ids: {}", demoAccountIds());
        log.info("  trade ids: {}", demoTradeIds());
        log.info("  settlement ids: {}", demoSettlementIds());
        log.info("  inspect current account state at /api/admin/trading-accounts/{}", firstDemoAccountId());
        log.info("  inspect trade valuation at /api/admin/trades/{}", firstDemoTradeId());
        log.info("  inspect settlement lifecycle at /api/admin/settlements/{}", firstDemoSettlementId());
    }

    private List<String> demoAccountIds() {
        var accountIds = new ArrayList<String>(properties.getAccountCount());
        for (int accountIndex = 0; accountIndex < properties.getAccountCount(); accountIndex++) {
            accountIds.add("ACC-DEMO-%03d".formatted(accountIndex + 1));
        }
        return accountIds;
    }

    private List<String> demoTradeIds() {
        var tradeIds = new ArrayList<String>(properties.getAccountCount() * properties.getSettlementsPerAccount());
        for (int accountIndex = 0; accountIndex < properties.getAccountCount(); accountIndex++) {
            for (int settlementIndex = 0; settlementIndex < properties.getSettlementsPerAccount(); settlementIndex++) {
                tradeIds.add("TRD-%03d-%03d".formatted(accountIndex + 1, settlementIndex + 1));
            }
        }
        return tradeIds;
    }

    private List<String> demoSettlementIds() {
        return demoTradeIds().stream()
                             .map(tradeId -> tradeId + "-SET")
                             .toList();
    }

    private String firstDemoAccountId() {
        return "ACC-DEMO-001";
    }

    private String firstDemoTradeId() {
        return "TRD-001-001";
    }

    private String firstDemoSettlementId() {
        return firstDemoTradeId() + "-SET";
    }

    private record InstrumentSeed(String symbol, String displayName) {
    }

    private enum SeedState {
        NONE,
        COMPLETE,
        LEGACY_OR_PARTIAL
    }
}
