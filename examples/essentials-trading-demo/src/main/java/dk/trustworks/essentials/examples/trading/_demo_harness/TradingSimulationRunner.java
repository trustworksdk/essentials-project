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
package dk.trustworks.essentials.examples.trading._demo_harness;

import dk.trustworks.essentials.components.eventsourced.aggregates.api.AggregateLifecycleApi;
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiClosingBooksGeneration;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundaryCalculator;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Settlements;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccountClosingBooksPolicy;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.Trades;
import dk.trustworks.essentials.examples.trading.brokerage.types.OwnerId;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.Quantity;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeSide;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.apply_trade_settlement.ApplyTradeSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_books_and_open_next_period.CloseBooksAndOpenNextPeriod;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.close_settlement.CloseSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.confirm_clearing.ConfirmClearing;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.create_settlement.CreateSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.deposit_cash.DepositCash;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.execute_trade.ExecuteTrade;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.mark_settlement_settled.MarkSettlementSettled;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.mark_trade_settled.MarkTradeSettled;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.open_trading_account.OpenTradingAccount;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.place_trade.PlaceTrade;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.reconcile_settlement.ReconcileSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.release_funds.ReleaseFunds;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.request_clearing.RequestClearing;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.request_settlement.RequestSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.reserve_funds.ReserveFunds;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instruments;
import dk.trustworks.essentials.examples.trading.market_data.types.Symbol;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.initialize_price.InitializePrice;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.register_instrument.RegisterInstrument;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.rename_instrument.RenameInstrument;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.update_price.UpdatePrice;
import dk.trustworks.essentials.examples.trading.market_data.views.latest_price.LatestPriceQuery;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.types.Amount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

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
 *   <li><b>ACC-DEMO-002 — explicit command.</b> Rolled by the application sending
 *       {@code CloseBooksAndOpenNextPeriod} directly. The escape hatch, for period ends a policy cannot
 *       express. No policy involvement.</li>
 *   <li><b>ACC-DEMO-003 — baseline.</b> Left in generation 1 so there is something to compare against.</li>
 * </ul>
 * Accounts are opened in the <em>current</em> period, derived from the configured boundary via
 * {@link ClosingBooksTimeBoundaryCalculator#currentPeriodId}. A hardcoded period id would age into the past and
 * make every later evaluation report skipped periods — which is exactly what this runner used to do.
 *
 * <h2>The one sanctioned aggregate read in the harness</h2>
 * {@link #seedDataState()} is the single place anything in {@code _demo_harness} holds a repository wrapper, and it
 * only calls their {@code findX} methods. It has to: the brokerage read models are projected asynchronously, so on a
 * restart against a populated database a projection-backed probe could answer "absent" while the data is present, and
 * this runner would then seed a second time on top of it. Everything else here writes through the command bus and
 * reads through a view slice. See {@code CLAUDE.md} in this package.
 */
public class TradingSimulationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(TradingSimulationRunner.class);
    /**
     * Mirrors {@code @AggregateSnapshotPolicy(everyNEvents = …)} on TradingAccount. Only used for log text —
     * the policy itself is the source of truth and is resolved by the framework.
     */
    private static final int    SNAPSHOT_EVERY_N_EVENTS = 100;
    /**
     * The principal the demo's admin surface acts as. The demo has no authentication.
     */
    private static final String DEMO_ADMIN_PRINCIPAL    = "demo-admin";
    /** NONE has no period concept, but the aggregate still requires a non-null period id. */
    private static final PeriodId NO_TIME_BOUNDARY_PERIOD_ID = PeriodId.of("no-time-boundary");
    private static final Amount   RESERVED_FUNDS             = Amount.of(BigDecimal.valueOf(250));
    private static final Amount   RELEASED_FUNDS             = Amount.of(BigDecimal.valueOf(100));
    private static final Amount   TRADE_GROSS_AMOUNT         = Amount.of(BigDecimal.valueOf(500));
    private static final Amount   SETTLEMENT_CASH_DELTA      = Amount.of(BigDecimal.valueOf(-500));
    private static final Amount   SETTLEMENT_REALIZED_PNL    = Amount.of(BigDecimal.valueOf(12));
    private static final Amount   POLICY_DRIVEN_DEPOSIT      = Amount.of(BigDecimal.valueOf(10));

    private final TradingDemoSimulationProperties                     properties;
    private final CommandBus                                          commandBus;
    private final TradingAccountClosingBooksPolicy                    closingBooksPolicy;
    private final AggregateLifecycleApi                               aggregateLifecycleApi;
    private final LatestPriceQuery                                    latestPriceQuery;
    private final DirectInstrumentPriceService                        directInstrumentPriceService;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final TradingAccounts                                     tradingAccounts;
    private final Trades                                              trades;
    private final Settlements                                         settlements;
    private final Instruments                                         instruments;
    private final Clock                                               clock;

    public TradingSimulationRunner(TradingDemoSimulationProperties properties,
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
        this(properties, commandBus, closingBooksPolicy, aggregateLifecycleApi, latestPriceQuery,
             directInstrumentPriceService, unitOfWorkFactory, tradingAccounts, trades, settlements, instruments,
             Clock.systemUTC());
    }

    public TradingSimulationRunner(TradingDemoSimulationProperties properties,
                                   CommandBus commandBus,
                                   TradingAccountClosingBooksPolicy closingBooksPolicy,
                                   AggregateLifecycleApi aggregateLifecycleApi,
                                   LatestPriceQuery latestPriceQuery,
                                   DirectInstrumentPriceService directInstrumentPriceService,
                                   EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                   TradingAccounts tradingAccounts,
                                   Trades trades,
                                   Settlements settlements,
                                   Instruments instruments,
                                   Clock clock) {
        this.properties = requireNonNull(properties, "No properties provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.closingBooksPolicy = requireNonNull(closingBooksPolicy, "No closingBooksPolicy provided");
        this.aggregateLifecycleApi = requireNonNull(aggregateLifecycleApi, "No aggregateLifecycleApi provided");
        this.latestPriceQuery = requireNonNull(latestPriceQuery, "No latestPriceQuery provided");
        this.directInstrumentPriceService = requireNonNull(directInstrumentPriceService, "No directInstrumentPriceService provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.tradingAccounts = requireNonNull(tradingAccounts, "No tradingAccounts provided");
        this.trades = requireNonNull(trades, "No trades provided");
        this.settlements = requireNonNull(settlements, "No settlements provided");
        this.instruments = requireNonNull(instruments, "No instruments provided");
        this.clock = requireNonNull(clock, "No clock provided");
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

        var settings        = closingBooksPolicy.settings();
        var initialPeriodId = currentPeriodId();
        log.info("Trading demo simulation bootstrap starting with {} accounts, {} instruments, {} settlements per account, initialPeriodId={} (derived from time boundary {} in zone {})",
                 properties.getAccountCount(),
                 properties.getInstrumentCount(),
                 properties.getSettlementsPerAccount(),
                 initialPeriodId,
                 settings.timeBoundary(),
                 settings.zoneId());
        log.info("Closing-books policy in effect: {}. Snapshot policy takes a snapshot every {} events.",
                 closingBooksPolicy.description(),
                 SNAPSHOT_EVERY_N_EVENTS);

        var instrumentSeeds = TradingSimulationIds.INSTRUMENT_SEEDS;
        for (int instrumentIndex = 0; instrumentIndex < properties.getInstrumentCount(); instrumentIndex++) {
            var seed         = instrumentSeeds.get(instrumentIndex % instrumentSeeds.size());
            var instrumentId = seed.instrumentId();
            var initialPrice = Amount.of(BigDecimal.valueOf(475 + (instrumentIndex * 12L)));
            commandBus.send(new RegisterInstrument(instrumentId,
                                                   Symbol.of(seed.symbol()),
                                                   seed.displayName()));
            commandBus.send(new RenameInstrument(instrumentId, seed.displayName() + " (Demo)"));
            directInstrumentPriceService.initializePrice(instrumentId, initialPrice);
            commandBus.send(new InitializePrice(instrumentId, initialPrice));
        }

        log.info("Trading demo simulation seeded {} instruments using realistic ticker symbols such as {} and {}",
                 properties.getInstrumentCount(),
                 instrumentSeeds.get(0).symbol(),
                 instrumentSeeds.get(Math.min(1, instrumentSeeds.size() - 1)).symbol());

        for (int accountIndex = 0; accountIndex < properties.getAccountCount(); accountIndex++) {
            var accountId = TradingAccountId.of("ACC-DEMO-%03d".formatted(accountIndex + 1));
            var role      = roleFor(accountIndex);
            commandBus.send(new OpenTradingAccount(accountId,
                                                   OwnerId.of("demo-owner-" + (accountIndex + 1)),
                                                   initialPeriodId));

            for (int depositIndex = 0; depositIndex < properties.getDepositsPerAccount(); depositIndex++) {
                commandBus.send(new DepositCash(accountId, Amount.of(BigDecimal.valueOf(1_000L * (depositIndex + 1)))));
            }

            commandBus.send(new ReserveFunds(accountId, RESERVED_FUNDS));
            commandBus.send(new ReleaseFunds(accountId, RELEASED_FUNDS));

            for (int settlementIndex = 0; settlementIndex < properties.getSettlementsPerAccount(); settlementIndex++) {
                var instrumentSeed = instrumentSeeds.get(settlementIndex % Math.max(1, Math.min(properties.getInstrumentCount(), instrumentSeeds.size())));
                var instrumentId   = instrumentSeed.instrumentId();
                var tradeId        = TradeId.of("TRD-%03d-%03d".formatted(accountIndex + 1, settlementIndex + 1));
                var settlementId   = SettlementId.forTrade(tradeId);
                var updatedPrice   = TRADE_GROSS_AMOUNT.add(Amount.of(BigDecimal.valueOf(15 + settlementIndex)));

                commandBus.send(new PlaceTrade(tradeId,
                                               accountId,
                                               instrumentId,
                                               settlementIndex % 2 == 0 ? TradeSide.BUY : TradeSide.SELL,
                                               Quantity.ONE,
                                               TRADE_GROSS_AMOUNT));
                commandBus.send(new ExecuteTrade(tradeId));
                commandBus.send(new RequestSettlement(tradeId, settlementId));
                directInstrumentPriceService.updatePrice(instrumentId, updatedPrice);
                commandBus.send(new UpdatePrice(instrumentId, updatedPrice));

                commandBus.send(new CreateSettlement(settlementId, tradeId, accountId, TRADE_GROSS_AMOUNT));
                commandBus.send(new RequestClearing(settlementId));
                commandBus.send(new ConfirmClearing(settlementId));
                commandBus.send(new MarkSettlementSettled(settlementId));
                commandBus.send(new ReconcileSettlement(settlementId));
                commandBus.send(new CloseSettlement(settlementId));
                commandBus.send(new MarkTradeSettled(tradeId));

                commandBus.send(new ApplyTradeSettlement(accountId,
                                                         tradeId,
                                                         SETTLEMENT_CASH_DELTA,
                                                         SETTLEMENT_REALIZED_PNL));
            }

            switch (role) {
                case POLICY_DRIVEN -> driveUntilPolicyRollsTheBooks(accountId);
                case EXPLICIT_COMMAND -> {
                    // The escape hatch: the application decides, the policy is not consulted. nextPeriodId is derived
                    // from the same boundary calculator the policy evaluates against, anchored on the period this
                    // account was opened in, so the new generation is labelled with the period it actually opens in.
                    var nextPeriodId = nextPeriodIdAfter(initialPeriodId);
                    commandBus.send(new CloseBooksAndOpenNextPeriod(accountId, nextPeriodId));
                    log.info("[{}] EXPLICIT COMMAND: application sent CloseBooksAndOpenNextPeriod directly (nextPeriodId={}). Now in generation {}. The closing-books policy played no part in this.",
                             accountId,
                             nextPeriodId,
                             currentGeneration(accountId));
                }
                case BASELINE -> log.info("[{}] BASELINE: left untouched in generation {} so there is an un-rolled account to compare against.",
                                          accountId,
                                          currentGeneration(accountId));
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
        var startingGeneration = currentGeneration(accountId);
        log.info("[{}] POLICY DRIVEN: writing deposits until the policy rolls the books by itself (threshold {} events, currently in generation {})",
                 accountId,
                 closingBooksPolicy.settings().eventThreshold(),
                 startingGeneration);

        for (int deposit = 1; deposit <= properties.getMaxPolicyDrivenEvents(); deposit++) {
            commandBus.send(new DepositCash(accountId, POLICY_DRIVEN_DEPOSIT));
            var generation = currentGeneration(accountId);
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
                 closingBooksPolicy.settings().eventThreshold());
    }

    /**
     * The generation an account's books are currently open in, or {@code 0} if no generation exists yet.
     * <p>
     * Read through the framework's {@link AggregateLifecycleApi} rather than through the account repository: this is
     * closing-books lifecycle metadata owned by the event store, it is strongly consistent, and reading it costs the
     * harness no access to the aggregate itself.
     */
    private long currentGeneration(TradingAccountId accountId) {
        return aggregateLifecycleApi.findCurrentClosingBooksGeneration(DEMO_ADMIN_PRINCIPAL,
                                                                       TradingAccounts.AGGREGATE_TYPE,
                                                                       accountId.toString())
                                    .map(ApiClosingBooksGeneration::generation)
                                    .orElse(0L);
    }

    /**
     * The period id a newly opened account belongs to, in whatever format the configured boundary requires.
     * Delegates to the framework calculator rather than formatting a date here, so the seed can never disagree
     * with the boundary the policy evaluates against.
     */
    private PeriodId currentPeriodId() {
        var settings = closingBooksPolicy.settings();
        var periodId = ClosingBooksTimeBoundaryCalculator.currentPeriodId(settings.timeBoundary(),
                                                                          settings.zoneId(),
                                                                          clock,
                                                                          settings.intervalDays());
        return periodId != null ? PeriodId.of(periodId) : NO_TIME_BOUNDARY_PERIOD_ID;
    }

    /**
     * The period that follows {@code currentPeriodId} under the configured boundary — the same computation
     * {@code TradingAccountClosingBooksPolicy.nextPeriodId} performs, minus the aggregate it would have to be handed.
     * For {@code NONE} the boundary has no period concept and the period is returned unchanged, exactly as the
     * policy's evaluator does.
     */
    private PeriodId nextPeriodIdAfter(PeriodId currentPeriodId) {
        var settings = closingBooksPolicy.settings();
        return PeriodId.of(ClosingBooksTimeBoundaryCalculator.resolveCurrentPeriodId(settings.timeBoundary(),
                                                                                     settings.zoneId(),
                                                                                     clock,
                                                                                     currentPeriodId.toString(),
                                                                                     settings.intervalDays()));
    }

    private AccountRole roleFor(int accountIndex) {
        return switch (accountIndex) {
            case 0 -> AccountRole.POLICY_DRIVEN;
            case 1 -> AccountRole.EXPLICIT_COMMAND;
            default -> AccountRole.BASELINE;
        };
    }

    /**
     * The idempotency probe, and the only aggregate read anywhere in the harness.
     * <p>
     * It has to be strongly consistent. The brokerage read models are projected asynchronously, so a
     * projection-backed probe could answer "absent" on a restart against a populated database, and this runner would
     * then seed a second time on top of existing data. The four repository wrappers are therefore consulted directly,
     * through their {@code findX} methods and inside one read-only unit of work. {@code LatestPriceQuery} is already
     * strongly consistent — it reads the price aggregate — so the price half goes through the view slice.
     */
    private SeedState seedDataState() {
        var firstSeedInstrument = TradingSimulationIds.INSTRUMENT_SEEDS.get(0);
        var firstInstrumentId   = firstSeedInstrument.instrumentId();

        var hasDirectPrice = directInstrumentPriceService.findLatestPrice(firstInstrumentId).isPresent();
        var hasPrice       = latestPriceQuery.findLatestPrice(firstInstrumentId).isPresent();

        var probe = unitOfWorkFactory.withUnitOfWork(uow -> new AggregateProbe(
                instruments.findInstrument(firstInstrumentId).isPresent(),
                tradingAccounts.findAccount(firstDemoAccountId()).isPresent(),
                trades.findTrade(firstDemoTradeId()).isPresent(),
                settlements.findSettlement(firstDemoSettlementId()).isPresent()));

        if (probe.hasInstrument() && hasDirectPrice && hasPrice && probe.hasAccount() && probe.hasTrade() && probe.hasSettlement()) {
            return SeedState.COMPLETE;
        }
        if (probe.hasInstrument() || hasDirectPrice || hasPrice || probe.hasAccount() || probe.hasTrade() || probe.hasSettlement()) {
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

    private List<TradingAccountId> demoAccountIds() {
        var accountIds = new ArrayList<TradingAccountId>(properties.getAccountCount());
        for (int accountIndex = 0; accountIndex < properties.getAccountCount(); accountIndex++) {
            accountIds.add(TradingAccountId.of("ACC-DEMO-%03d".formatted(accountIndex + 1)));
        }
        return accountIds;
    }

    private List<TradeId> demoTradeIds() {
        var tradeIds = new ArrayList<TradeId>(properties.getAccountCount() * properties.getSettlementsPerAccount());
        for (int accountIndex = 0; accountIndex < properties.getAccountCount(); accountIndex++) {
            for (int settlementIndex = 0; settlementIndex < properties.getSettlementsPerAccount(); settlementIndex++) {
                tradeIds.add(TradeId.of("TRD-%03d-%03d".formatted(accountIndex + 1, settlementIndex + 1)));
            }
        }
        return tradeIds;
    }

    private List<SettlementId> demoSettlementIds() {
        return demoTradeIds().stream()
                             .map(SettlementId::forTrade)
                             .toList();
    }

    private TradingAccountId firstDemoAccountId() {
        return TradingAccountId.of("ACC-DEMO-001");
    }

    private TradeId firstDemoTradeId() {
        return TradeId.of("TRD-001-001");
    }

    private SettlementId firstDemoSettlementId() {
        return SettlementId.forTrade(firstDemoTradeId());
    }

    /** What {@link #seedDataState()} found in the four aggregate stores, gathered in one unit of work. */
    private record AggregateProbe(boolean hasInstrument,
                                  boolean hasAccount,
                                  boolean hasTrade,
                                  boolean hasSettlement) {
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
