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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundaryCalculator;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountClosingBooksPolicy;
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
import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless demo runner that exercises the example aggregates against the event store at startup.
 * <p>
 * The demo exists to show snapshots and closing books, so bootstrap deliberately leaves the three accounts in
 * three different states, one per mechanism:
 * <ul>
 *   <li><b>ACC-DEMO-001 — policy driven.</b> Fed ordinary deposits until the configured
 *       {@code @AggregateClosingBooksPolicy} event threshold is crossed and <em>the framework</em> rolls the
 *       generation on next access. This is the headline feature; nothing here asks for a rollover.
 *       Crossing the threshold also crosses the snapshot policy's {@code everyNEvents}, so this account is the
 *       one that has snapshots to look at.</li>
 *   <li><b>ACC-DEMO-002 — explicit command.</b> Rolled by the application calling
 *       {@code closeBooksAndOpenNextGeneration} directly. The escape hatch, for period ends a policy cannot
 *       express. No policy involvement.</li>
 *   <li><b>ACC-DEMO-003 — baseline.</b> Left in generation 1 so there is something to compare against.</li>
 * </ul>
 * Accounts are opened in the <em>current</em> period, derived from the configured boundary via
 * {@link ClosingBooksTimeBoundaryCalculator#currentPeriodId}. A hardcoded period id would age into the past and
 * make every later evaluation report skipped periods — which is exactly what this runner used to do.
 */
public class TradingSimulationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TradingSimulationRunner.class);
    /**
     * Mirrors {@code @AggregateSnapshotPolicy(everyNEvents = …)} on TradingAccount. Only used for log text —
     * the policy itself is the source of truth and is resolved by the framework.
     */
    private static final int    SNAPSHOT_EVERY_N_EVENTS = 100;
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

    private final TradingDemoSimulationProperties   properties;
    private final TradingAccountService             tradingAccountService;
    private final TradingAccountClosingBooksPolicy  closingBooksPolicy;
    private final SettlementService                 settlementService;
    private final InstrumentService                 instrumentService;
    private final DirectInstrumentPriceService      directInstrumentPriceService;
    private final InstrumentPriceService            instrumentPriceService;
    private final TradeService                      tradeService;
    private final Clock                             clock;

    public TradingSimulationRunner(TradingDemoSimulationProperties properties,
                                  TradingAccountService tradingAccountService,
                                  TradingAccountClosingBooksPolicy closingBooksPolicy,
                                  SettlementService settlementService,
                                  InstrumentService instrumentService,
                                  DirectInstrumentPriceService directInstrumentPriceService,
                                  InstrumentPriceService instrumentPriceService,
                                  TradeService tradeService) {
        this(properties, tradingAccountService, closingBooksPolicy, settlementService, instrumentService,
             directInstrumentPriceService, instrumentPriceService, tradeService, Clock.systemUTC());
    }

    public TradingSimulationRunner(TradingDemoSimulationProperties properties,
                                  TradingAccountService tradingAccountService,
                                  TradingAccountClosingBooksPolicy closingBooksPolicy,
                                  SettlementService settlementService,
                                  InstrumentService instrumentService,
                                  DirectInstrumentPriceService directInstrumentPriceService,
                                  InstrumentPriceService instrumentPriceService,
                                  TradeService tradeService,
                                  Clock clock) {
        this.properties = properties;
        this.tradingAccountService = tradingAccountService;
        this.closingBooksPolicy = closingBooksPolicy;
        this.settlementService = settlementService;
        this.instrumentService = instrumentService;
        this.directInstrumentPriceService = directInstrumentPriceService;
        this.instrumentPriceService = instrumentPriceService;
        this.tradeService = tradeService;
        this.clock = clock;
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

        var initialPeriodId = currentPeriodId();
        log.info("Trading demo simulation bootstrap starting with {} accounts, {} instruments, {} settlements per account, initialPeriodId={} (derived from time boundary {} in zone {})",
                 properties.getAccountCount(),
                 properties.getInstrumentCount(),
                 properties.getSettlementsPerAccount(),
                 initialPeriodId,
                 closingBooksPolicy.timeBoundary(),
                 closingBooksPolicy.zoneId());
        log.info("Closing-books policy in effect: {}. Snapshot policy takes a snapshot every {} events.",
                 closingBooksPolicy.description(),
                 SNAPSHOT_EVERY_N_EVENTS);

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
            var role      = roleFor(accountIndex);
            tradingAccountService.openAccount(accountId,
                                              "demo-owner-" + (accountIndex + 1),
                                              initialPeriodId);

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

            switch (role) {
                case POLICY_DRIVEN -> driveUntilPolicyRollsTheBooks(accountId);
                case EXPLICIT_COMMAND -> {
                    // The escape hatch: the application decides, the policy is not consulted. nextPeriodId comes
                    // from the policy so the new generation is labelled with the period it actually opens in.
                    var account = tradingAccountService.load(accountId);
                    var nextPeriodId = closingBooksPolicy.nextPeriodId(account);
                    tradingAccountService.closeBooksAndOpenNextPeriod(accountId, nextPeriodId);
                    log.info("[{}] EXPLICIT COMMAND: application called closeBooksAndOpenNextGeneration directly (nextPeriodId={}). Now in generation {}. The closing-books policy played no part in this.",
                             accountId,
                             nextPeriodId,
                             tradingAccountService.currentGeneration(accountId));
                }
                case BASELINE -> log.info("[{}] BASELINE: left untouched in generation {} so there is an un-rolled account to compare against.",
                                          accountId,
                                          tradingAccountService.currentGeneration(accountId));
            }
        }

        log.info("Trading demo simulation completed with {} accounts, {} instruments, {} settlements per account",
                 properties.getAccountCount(),
                 properties.getInstrumentCount(),
                 properties.getSettlementsPerAccount());
        logEndpointHints();
    }

    /**
     * Writes ordinary business events until the closing-books policy decides to roll the generation.
     * <p>
     * Nothing here asks for a rollover: every deposit goes through the normal mutation path, which evaluates the
     * policy on load. The loop simply stops once the generation number moves, which is the framework acting on
     * its own. Bounded by {@code maxPolicyDrivenEvents} so a misconfigured threshold cannot spin forever.
     */
    private void driveUntilPolicyRollsTheBooks(TradingAccountId accountId) {
        var startingGeneration = tradingAccountService.currentGeneration(accountId);
        log.info("[{}] POLICY DRIVEN: writing deposits until the policy rolls the books by itself (threshold {} events, currently in generation {})",
                 accountId,
                 closingBooksPolicy.eventThreshold(),
                 startingGeneration);

        for (int deposit = 1; deposit <= properties.getMaxPolicyDrivenEvents(); deposit++) {
            tradingAccountService.depositCash(accountId, BigDecimal.valueOf(10));
            var generation = tradingAccountService.currentGeneration(accountId);
            if (generation > startingGeneration) {
                log.info("[{}] POLICY DRIVEN: the closing-books policy rolled generation {} → {} after {} deposits, without the application asking. Snapshots were written along the way (every {} events).",
                         accountId,
                         startingGeneration,
                         generation,
                         deposit,
                         SNAPSHOT_EVERY_N_EVENTS);
                return;
            }
        }

        // Reaching here means the policy never fired - worth saying loudly, because the account is then
        // indistinguishable from the baseline one and the demo silently stops demonstrating its main feature.
        log.warn("[{}] POLICY DRIVEN: wrote {} deposits without the policy rolling the books. Check that closing books is enabled and that the event threshold ({}) is below trading-demo.simulation.max-policy-driven-events.",
                 accountId,
                 properties.getMaxPolicyDrivenEvents(),
                 closingBooksPolicy.eventThreshold());
    }

    /**
     * The period id a newly opened account belongs to, in whatever format the configured boundary requires.
     * Delegates to the framework calculator rather than formatting a date here, so the seed can never disagree
     * with the boundary the policy evaluates against.
     */
    private String currentPeriodId() {
        var periodId = ClosingBooksTimeBoundaryCalculator.currentPeriodId(closingBooksPolicy.timeBoundary(),
                                                                          ZoneId.of(closingBooksPolicy.zoneId()),
                                                                          clock,
                                                                          closingBooksPolicy.intervalDays());
        // NONE has no period concept, but the aggregate still requires a non-null period id.
        return periodId != null ? periodId : "no-time-boundary";
    }

    private AccountRole roleFor(int accountIndex) {
        return switch (accountIndex) {
            case 0 -> AccountRole.POLICY_DRIVEN;
            case 1 -> AccountRole.EXPLICIT_COMMAND;
            default -> AccountRole.BASELINE;
        };
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
        log.info("  ACC-DEMO-001 was rolled by the closing-books POLICY and is the account with snapshots");
        log.info("  ACC-DEMO-002 was rolled by an EXPLICIT command");
        log.info("  ACC-DEMO-003 was never rolled (baseline)");
        log.info("  compare them in the admin UI under Aggregates → Aggregate lookup");
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

    /** What each seeded account is there to demonstrate. */
    private enum AccountRole {
        /** Rolled by the closing-books policy, with no application involvement. */
        POLICY_DRIVEN,
        /** Rolled by an explicit application command, with no policy involvement. */
        EXPLICIT_COMMAND,
        /** Never rolled — the comparison case. */
        BASELINE
    }
}
