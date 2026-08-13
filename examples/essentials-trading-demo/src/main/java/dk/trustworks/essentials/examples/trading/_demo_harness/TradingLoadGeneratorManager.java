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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksGenerationRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.LogicalAggregateId;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWork;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.EventStoreUnitOfWorkFactory;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccount;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccountClosingBooksPolicy;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.examples.trading.brokerage.types.Quantity;
import dk.trustworks.essentials.examples.trading.brokerage.types.OwnerId;
import dk.trustworks.essentials.examples.trading.brokerage.types.PeriodId;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeSide;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.apply_trade_settlement.ApplyTradeSettlement;
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
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.request_clearing.RequestClearing;
import dk.trustworks.essentials.examples.trading.brokerage.use_cases.request_settlement.RequestSettlement;
import dk.trustworks.essentials.examples.trading.brokerage.views.account_statement.AccountStatement;
import dk.trustworks.essentials.examples.trading.brokerage.views.account_statement.AccountStatementQuery;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.use_cases.update_price.UpdatePrice;
import dk.trustworks.essentials.examples.trading.market_data.views.latest_price.LatestPrice;
import dk.trustworks.essentials.examples.trading.market_data.views.latest_price.LatestPriceQuery;
import dk.trustworks.essentials.reactive.command.CommandBus;
import dk.trustworks.essentials.shared.Lifecycle;
import dk.trustworks.essentials.types.Amount;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Generates runtime demo traffic while the application is running.
 *
 * <h2>Every write is a command</h2>
 * Nothing here touches an aggregate or a repository. Each domain mutation is {@code commandBus.send(new SomeCommand(…))},
 * naming a slice's command type -- rules/slice-design.md &sect;R4's sanctioned collaboration. Two consequences worth
 * knowing before reading the benchmark numbers this class produces:
 * <ul>
 *   <li>Each {@code send} is dispatched through the {@code DurableLocalCommandBus} and gets its <em>own</em>
 *       {@code UnitOfWork} from the bus's interceptor. The pre-slice code called one {@code @Transactional} service
 *       method per step, so the step count and the transaction count are unchanged -- but the dispatch and handler
 *       lookup are new work inside the measured window. The aggregate price path is therefore measured slightly
 *       heavier than before, against a direct-write path that is unchanged.</li>
 *   <li>A multi-step sequence such as {@link #createPendingTradeAndSettlement()} is six separate transactions, as it
 *       always was. It is not atomic and never was.</li>
 * </ul>
 *
 * <h2>Every read is a view slice</h2>
 * Prices come from {@code market_data.latest_price}, which reads the aggregate and is strongly consistent. Account
 * existence and the comparison scenario's read passes come from {@code brokerage.account_statement}, which is a
 * projection and is <em>eventually</em> consistent -- see {@link #seedDataIsAvailable()} and
 * {@link #runTradingAccountScenario}.
 */
public class TradingLoadGeneratorManager implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(TradingLoadGeneratorManager.class);
    private static final int    PRICE_UPDATE_RETRY_ATTEMPTS = 5;
    private static final String AGGREGATE_DESCRIPTION = "Event-sourced aggregate path: load aggregate, apply event, append event, commit transaction.";
    private static final String DIRECT_WRITE_DESCRIPTION = "Direct-write market data path: single-row upsert of latest price.";
    /** Stand-in price for an instrument no tick has been seen for yet. */
    private static final Amount FALLBACK_PRICE = Amount.of(BigDecimal.valueOf(500));
    private static final Amount PRICE_FLOOR = Amount.of(BigDecimal.valueOf(50));
    private static final Amount BUY_REALIZED_PNL = Amount.of(BigDecimal.valueOf(4));
    private static final Amount SELL_REALIZED_PNL = Amount.of(BigDecimal.valueOf(6));
    private static final TradingAccountId SEED_PROBE_ACCOUNT_ID = TradingAccountId.of("ACC-DEMO-001");

    private final TradingDemoSimulationProperties simulationProperties;
    private final TradingDemoLoadGeneratorProperties loadProperties;
    private final CommandBus commandBus;
    private final TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy;
    private final ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository;
    private final AccountStatementQuery accountStatementQuery;
    private final LatestPriceQuery latestPriceQuery;
    /**
     * Held for the closing-books benchmark's read pass ONLY -- see {@link #runTradingAccountScenario}. This is the
     * second of the two places in {@code _demo_harness} that touches a repository wrapper; both are documented in
     * this package's {@code CLAUDE.md}.
     */
    private final TradingAccounts tradingAccounts;
    private final EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory;
    private final DirectInstrumentPriceService directInstrumentPriceService;
    private final Optional<MeterRegistry> meterRegistry;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicLong generatedTradeCount = new AtomicLong();
    private final AtomicLong generatedSettlementCount = new AtomicLong();
    private final AtomicLong generatedPriceUpdateCount = new AtomicLong();
    private final AtomicLong sequence = new AtomicLong(1_000);
    private final AtomicLong aggregatePriceOpCount = new AtomicLong();
    private final AtomicLong aggregatePriceOpTotalNanos = new AtomicLong();
    private final AtomicLong aggregatePriceOpMaxNanos = new AtomicLong();
    private final AtomicLong directWritePriceOpCount = new AtomicLong();
    private final AtomicLong directWritePriceOpTotalNanos = new AtomicLong();
    private final AtomicLong directWritePriceOpMaxNanos = new AtomicLong();
    private final AtomicBoolean priceStressRunning = new AtomicBoolean(false);
    private final AtomicLong priceStressRequestedCount = new AtomicLong();
    private final AtomicLong priceStressCompletedCount = new AtomicLong();
    private final AtomicBoolean waitingForSeedDataLogged = new AtomicBoolean(false);
    /**
     * Latches once the seed data has been observed. The account half of that probe is a projection read, so
     * re-running it on every tick would both cost a table scan per generated trade and be able to flap; seed data is
     * never removed while the application runs, so observing it once is enough.
     */
    private final AtomicBoolean seedDataObserved = new AtomicBoolean(false);
    private final Queue<PendingSettlement> pendingSettlements = new ConcurrentLinkedQueue<>();
    private final ReentrantLock simulationLock = new ReentrantLock();
    private final CopyOnWriteArrayList<Consumer<TradingLoadGeneratorStatusView>> statusListeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<InstrumentId, InstrumentPriceTickerView> latestPriceTickers = new ConcurrentHashMap<>();

    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService priceStressExecutor;
    private volatile TradeId latestTradeId;
    private volatile SettlementId latestSettlementId;
    private volatile InstrumentId latestPriceInstrumentId;
    private volatile long priceStressIntervalMillis;
    private volatile PriceStressMode currentPriceStressMode = PriceStressMode.AGGREGATE_EVENT_SOURCED;
    private volatile PricePathScenarioResultView latestPricePathScenarioResult = PricePathScenarioResultView.unavailable();
    private volatile TradingAccountScenarioResultView latestTradingAccountScenarioResult = TradingAccountScenarioResultView.unavailable();

    public TradingLoadGeneratorManager(TradingDemoSimulationProperties simulationProperties,
                                       TradingDemoLoadGeneratorProperties loadProperties,
                                       CommandBus commandBus,
                                       TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy,
                                       ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository,
                                       AccountStatementQuery accountStatementQuery,
                                       LatestPriceQuery latestPriceQuery,
                                       TradingAccounts tradingAccounts,
                                       EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                       DirectInstrumentPriceService directInstrumentPriceService,
                                       Optional<MeterRegistry> meterRegistry) {
        this.simulationProperties = requireNonNull(simulationProperties, "No simulationProperties provided");
        this.loadProperties = requireNonNull(loadProperties, "No loadProperties provided");
        this.commandBus = requireNonNull(commandBus, "No commandBus provided");
        this.tradingAccountClosingBooksPolicy = requireNonNull(tradingAccountClosingBooksPolicy, "No tradingAccountClosingBooksPolicy provided");
        this.tradingAccountGenerationRepository = requireNonNull(tradingAccountGenerationRepository, "No tradingAccountGenerationRepository provided");
        this.accountStatementQuery = requireNonNull(accountStatementQuery, "No accountStatementQuery provided");
        this.latestPriceQuery = requireNonNull(latestPriceQuery, "No latestPriceQuery provided");
        this.tradingAccounts = requireNonNull(tradingAccounts, "No tradingAccounts provided");
        this.unitOfWorkFactory = requireNonNull(unitOfWorkFactory, "No unitOfWorkFactory provided");
        this.directInstrumentPriceService = requireNonNull(directInstrumentPriceService, "No directInstrumentPriceService provided");
        this.meterRegistry = requireNonNull(meterRegistry, "No meterRegistry provided");
    }

    @Override
    public void start() {
        doStart(false);
    }

    public TradingLoadGeneratorStatusView startManually() {
        doStart(true);
        return status();
    }

    private void doStart(boolean ignoreConfiguredEnabled) {
        if (!ignoreConfiguredEnabled && !loadProperties.isEnabled()) {
            log.info("Trading runtime load generator is disabled");
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }

        scheduler = Executors.newScheduledThreadPool(2, daemonThreadFactory());
        scheduler.scheduleWithFixedDelay(this::safeGenerateTradeLifecycle,
                                         loadProperties.getTradeInterval().toMillis(),
                                         loadProperties.getTradeInterval().toMillis(),
                                         TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::safeGeneratePriceUpdate,
                                         loadProperties.getPriceUpdateInterval().toMillis(),
                                         loadProperties.getPriceUpdateInterval().toMillis(),
                                         TimeUnit.MILLISECONDS);
        log.info("Trading runtime load generator started with tradeInterval={} and priceUpdateInterval={}",
                 loadProperties.getTradeInterval(),
                 loadProperties.getPriceUpdateInterval());
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        var currentScheduler = scheduler;
        scheduler = null;
        if (currentScheduler != null) {
            currentScheduler.shutdownNow();
        }
        stopPriceStressExecutor();
        log.info("Trading runtime load generator stopped after generating {} trades, {} settlements, and {} price updates",
                 generatedTradeCount.get(),
                 generatedSettlementCount.get(),
                 generatedPriceUpdateCount.get());
        publishStatus();
    }

    public TradingLoadGeneratorStatusView stopManually() {
        stop();
        return status();
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    public TradingLoadGeneratorStatusView status() {
        return new TradingLoadGeneratorStatusView(loadProperties.isEnabled(),
                                                  isStarted(),
                                                  generatedTradeCount.get(),
                                                  generatedSettlementCount.get(),
                                                  generatedPriceUpdateCount.get(),
                                                  pendingSettlements.size(),
                                                  latestTradeId,
                                                  latestSettlementId,
                                                  latestPriceInstrumentId,
                                                  currentPriceStressMode.name().toLowerCase().replace('_', '-'),
                                                  priceStressRunning.get(),
                                                  priceStressRequestedCount.get(),
                                                  priceStressCompletedCount.get(),
                                                  priceStressIntervalMillis,
                                                  latestPrices());
    }

    public void addStatusListener(Consumer<TradingLoadGeneratorStatusView> listener) {
        statusListeners.add(listener);
    }

    public void removeStatusListener(Consumer<TradingLoadGeneratorStatusView> listener) {
        statusListeners.remove(listener);
    }

    public TradingLoadGeneratorStatusView generateTradeLifecycleBurst(int count) {
        var statusAfterBurst = withSimulationLockResult(() -> {
            var safeCount = normalizeBurstCount(count);
            ensureSeedDataAvailableForBurst();
            for (int i = 0; i < safeCount; i++) {
                generateTradeLifecycle();
            }
            log.info("Trading runtime load generator completed a trade lifecycle burst of {} items", safeCount);
            return status();
        });
        publishStatus();
        return statusAfterBurst;
    }

    public TradingLoadGeneratorStatusView generatePendingTradeBurst(int count) {
        var statusAfterBurst = withSimulationLock(() -> {
            var safeCount = normalizeBurstCount(count);
            ensureSeedDataAvailableForBurst();
            for (int i = 0; i < safeCount; i++) {
                createPendingTradeAndSettlement();
            }
            log.info("Trading runtime load generator completed a pending trade burst of {} items", safeCount);
            return status();
        });
        publishStatus();
        return statusAfterBurst;
    }

    public TradingLoadGeneratorStatusView settlePendingTradeBurst(int count) {
        var statusAfterBurst = withSimulationLock(() -> {
            var safeCount = normalizeBurstCount(count);
            ensureSeedDataAvailableForBurst();
            for (int i = 0; i < safeCount; i++) {
                if (pendingSettlements.isEmpty()) {
                    createPendingTradeAndSettlement();
                }
                settleNextPendingTrade();
            }
            log.info("Trading runtime load generator completed a settlement burst of {} items", safeCount);
            return status();
        });
        publishStatus();
        return statusAfterBurst;
    }

    public TradingLoadGeneratorStatusView generatePriceUpdateBurst(int count) {
        var statusAfterBurst = withSimulationLock(() -> {
            var safeCount = normalizeBurstCount(count);
            ensureSeedDataAvailableForBurst();
            for (int i = 0; i < safeCount; i++) {
                generatePriceUpdate();
            }
            log.info("Trading runtime load generator completed a price update burst of {} items", safeCount);
            return status();
        });
        publishStatus();
        return statusAfterBurst;
    }

    public TradingLoadGeneratorStatusView startAsyncPriceStress(int count, long intervalMillis, PriceStressMode mode) {
        var safeCount = normalizeBurstCount(count);
        var safeIntervalMillis = normalizePriceStressInterval(intervalMillis);
        ensureSeedDataAvailableForBurst();
        if (!priceStressRunning.compareAndSet(false, true)) {
            throw new IllegalStateException("Price stress is already running");
        }
        currentPriceStressMode = mode;
        priceStressRequestedCount.set(safeCount);
        priceStressCompletedCount.set(0);
        priceStressIntervalMillis = safeIntervalMillis;
        publishStatus();

        if (priceStressExecutor == null) {
            priceStressExecutor = Executors.newSingleThreadExecutor(priceStressThreadFactory());
        }
        priceStressExecutor.submit(() -> runAsyncPriceStress(safeCount, safeIntervalMillis));
        log.info("Trading runtime load generator started async price stress in mode {} with {} updates at {} ms interval",
                 mode,
                 safeCount,
                 safeIntervalMillis);
        return status();
    }

    public TradingLoadGeneratorStatusView stopAsyncPriceStress() {
        priceStressRunning.set(false);
        publishStatus();
        return status();
    }

    public PricePathScenarioResultView runPricePathComparisonScenario(int count) {
        return withSimulationLockResult(() -> {
            if (priceStressRunning.get()) {
                throw new IllegalStateException("Stop the async price stress run before starting a comparison scenario.");
            }
            var safeCount = normalizeBurstCount(count);
            ensureSeedDataAvailableForBurst();

            var originalMode = currentPriceStressMode;
            try {
                var aggregateResult = runPricePathScenario(PriceStressMode.AGGREGATE_EVENT_SOURCED, safeCount);
                var directWriteResult = runPricePathScenario(PriceStressMode.DIRECT_WRITE, safeCount);
                var winnerMode = aggregateResult.elapsedMillis() <= directWriteResult.elapsedMillis()
                        ? aggregateResult.mode()
                        : directWriteResult.mode();
                latestPricePathScenarioResult = new PricePathScenarioResultView(true,
                                                                               safeCount,
                                                                               winnerMode,
                                                                               aggregateResult.elapsedMillis() - directWriteResult.elapsedMillis(),
                                                                               aggregateResult,
                                                                               directWriteResult,
                                                                               "Runs the same number of price updates through both paths back-to-back so their write cost is easier to compare.");
                publishStatus();
                return latestPricePathScenarioResult;
            } finally {
                currentPriceStressMode = originalMode;
            }
        });
    }

    public PricePathScenarioResultView latestPricePathScenarioResult() {
        return latestPricePathScenarioResult;
    }

    /**
     * Runs the same workload twice against two different closing-books policies and reports the difference.
     *
     * <p>The policy override goes through {@link TradingAccountClosingBooksPolicy#withTemporarySettings}, which holds
     * the policy's lock for the whole scenario. That is the point: the previous shape captured five values, ran the
     * workload, and restored them in a {@code finally}, so an admin request retuning the policy mid-scenario returned
     * 200 and was then silently reverted. It is now excluded instead. The per-mode retune inside the scenario re-enters
     * the same lock on the same thread, which a {@code ReentrantLock} allows.
     */
    public TradingAccountScenarioResultView runTradingAccountComparisonScenario(int mutationCount, int readPasses, long eventThreshold) {
        return withSimulationLockResult(() -> {
            if (priceStressRunning.get()) {
                throw new IllegalStateException("Stop the async price stress run before starting a comparison scenario.");
            }
            var safeMutationCount = normalizeBurstCount(mutationCount);
            var safeReadPasses = normalizeReadPasses(readPasses);
            var safeEventThreshold = normalizeEventThreshold(eventThreshold);

            return tradingAccountClosingBooksPolicy.withTemporarySettings(settings -> settings,
                                                                          () -> {
                var scenarioId = RandomIdGenerator.generate();
                var bootstrapOnly = runTradingAccountScenario("manual-only",
                                                              ClosingBooksDefaultPolicyType.MANUAL_ONLY,
                                                              safeMutationCount,
                                                              safeReadPasses,
                                                              safeEventThreshold,
                                                              scenarioId);
                var eventCount = runTradingAccountScenario("event-count",
                                                           ClosingBooksDefaultPolicyType.EVENT_COUNT,
                                                           safeMutationCount,
                                                           safeReadPasses,
                                                           safeEventThreshold,
                                                           scenarioId);
                var winnerMode = bootstrapOnly.totalElapsedMillis() <= eventCount.totalElapsedMillis()
                        ? bootstrapOnly.mode()
                        : eventCount.mode();
                latestTradingAccountScenarioResult = new TradingAccountScenarioResultView(true,
                                                                                         safeMutationCount,
                                                                                         safeReadPasses,
                                                                                         safeEventThreshold,
                                                                                         winnerMode,
                                                                                         bootstrapOnly.totalElapsedMillis() - eventCount.totalElapsedMillis(),
                                                                                         bootstrapOnly,
                                                                                         eventCount,
                                                                                         "Runs the same trading-account mutation and repeated-read workload twice so you can compare no rollover against event-count rollover with snapshot deltas and generation growth.");
                publishStatus();
                return latestTradingAccountScenarioResult;
            });
        });
    }

    public TradingAccountScenarioResultView latestTradingAccountScenarioResult() {
        return latestTradingAccountScenarioResult;
    }

    private void safeGenerateTradeLifecycle() {
        withSimulationLockVoid(() -> {
            try {
                generateTradeLifecycle();
            } catch (Exception e) {
                log.warn("Trading runtime load generator failed to generate a trade lifecycle", e);
            }
        });
        publishStatus();
    }

    private void safeGeneratePriceUpdate() {
        withSimulationLockVoid(() -> {
            try {
                generatePriceUpdate();
            } catch (Exception e) {
                log.warn("Trading runtime load generator failed to generate a price update", e);
            }
        });
        publishStatus();
    }

    private void generateTradeLifecycle() {
        if (!seedDataIsAvailable()) {
            logWaitingForSeedData();
            return;
        }
        waitingForSeedDataLogged.set(false);

        if (generatedTradeCount.get() >= loadProperties.getMaxGeneratedTrades()) {
            return;
        }
        createPendingTradeAndSettlement();
        settleNextPendingTrade();
    }

    private void generatePriceUpdate() {
        if (!seedDataIsAvailable()) {
            logWaitingForSeedData();
            return;
        }
        waitingForSeedDataLogged.set(false);

        for (int attempt = 1; attempt <= PRICE_UPDATE_RETRY_ATTEMPTS; attempt++) {
            try {
                doGeneratePriceUpdate();
                return;
            } catch (OptimisticAppendToStreamException e) {
                if (attempt == PRICE_UPDATE_RETRY_ATTEMPTS) {
                    throw e;
                }
                log.debug("Optimistic concurrency conflict while generating price update, retrying attempt {} of {}",
                          attempt + 1,
                          PRICE_UPDATE_RETRY_ATTEMPTS);
            }
        }
    }

    private void doGeneratePriceUpdate() {
        var nextSequence = sequence.incrementAndGet();
        var instrumentIds = demoInstrumentIds();
        var instrumentId = instrumentIds.get((int) (nextSequence % instrumentIds.size()));
        var currentPrice = currentPrice(instrumentId);
        var jitter = Amount.of(BigDecimal.valueOf(loadProperties.getPriceJitter().getMin()
                                                  + (nextSequence % Math.max(1, loadProperties.getPriceJitter().getMax() - loadProperties.getPriceJitter().getMin() + 1))));
        // The direction alternates per instrument, not per sequence value. Both the instrument index and a
        // sequence-parity direction are driven by the same counter, so with the default instrument-count of 2 they
        // were perfectly correlated: one instrument only ever rose and the other only ever fell until it hit the
        // floor below. Dividing out the instrument index first gives each instrument its own alternating walk.
        var rising = (nextSequence / instrumentIds.size()) % 2 == 0;
        var nextPrice = rising ? currentPrice.add(jitter) : currentPrice.subtract(jitter);
        if (nextPrice.signum() <= 0) {
            nextPrice = PRICE_FLOOR;
        }
        var startNanos = System.nanoTime();
        if (currentPriceStressMode == PriceStressMode.DIRECT_WRITE) {
            directInstrumentPriceService.updatePrice(instrumentId, nextPrice);
            recordDirectWriteDuration(System.nanoTime() - startNanos);
        } else {
            commandBus.send(new UpdatePrice(instrumentId, nextPrice));
            recordAggregateDuration(System.nanoTime() - startNanos);
        }
        latestPriceInstrumentId = instrumentId;
        latestPriceTickers.put(instrumentId,
                               new InstrumentPriceTickerView(instrumentId, nextPrice.toPlainString()));
        generatedPriceUpdateCount.incrementAndGet();
        // Deliberately does not publish status. Callers publish once they have released simulationLock, because a
        // status listener fans out to the dashboard SSE broadcast, and that renders the full summary (an account
        // view plus a generation-snapshot query per demo account). Publishing from here charged that work to every
        // single update while holding the lock, and it also landed inside the timed section of
        // runPricePathScenario, inflating the price-path comparison with dashboard cost.
    }

    /**
     * Whether the bootstrap has finished seeding.
     *
     * <p>The price half reads {@code market_data.latest_price}, which is strongly consistent. The account half reads
     * the {@code brokerage.account_statement} projection, which is not -- so immediately after bootstrap this can
     * still answer "no" for a moment while the projection catches up, and the generator logs that it is waiting. The
     * pre-slice version rehydrated the account aggregate here and saw it at once. Latched once observed, so the
     * projection read happens a handful of times at startup rather than on every tick.
     */
    private boolean seedDataIsAvailable() {
        if (seedDataObserved.get()) {
            return true;
        }
        var instrumentIds = demoInstrumentIds();
        var available = !instrumentIds.isEmpty()
                        && latestPriceQuery.findLatestPrice(instrumentIds.get(0)).isPresent()
                        && accountStatementQuery.accountStatements()
                                                .stream()
                                                .map(AccountStatement::logicalAccountId)
                                                .anyMatch(SEED_PROBE_ACCOUNT_ID::equals);
        if (available) {
            seedDataObserved.set(true);
        }
        return available;
    }

    private int normalizeBurstCount(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be > 0");
        }
        return Math.min(count, 10_000);
    }

    private long normalizePriceStressInterval(long intervalMillis) {
        if (intervalMillis < 0) {
            throw new IllegalArgumentException("intervalMillis must be >= 0");
        }
        return intervalMillis;
    }

    private Amount currentPrice(InstrumentId instrumentId) {
        var cachedPrice = latestPriceTickers.get(instrumentId);
        if (cachedPrice != null) {
            return Amount.of(cachedPrice.latestPrice());
        }
        return switch (currentPriceStressMode) {
            case DIRECT_WRITE -> directInstrumentPriceService.findLatestPrice(instrumentId).orElse(FALLBACK_PRICE);
            case AGGREGATE_EVENT_SOURCED -> latestPriceQuery.findLatestPrice(instrumentId)
                                                            .map(LatestPrice::latestPrice)
                                                            .orElse(FALLBACK_PRICE);
        };
    }

    private void ensureSeedDataAvailableForBurst() {
        if (!seedDataIsAvailable()) {
            throw new IllegalStateException("Demo seed data is not available yet. Let the bootstrap finish first.");
        }
        initializeLatestPriceTickersIfMissing();
    }

    private void createPendingTradeAndSettlement() {
        var nextSequence = sequence.incrementAndGet();
        var accountIds = demoAccountIds();
        var instrumentIds = demoInstrumentIds();
        var accountId = accountIds.get((int) (nextSequence % accountIds.size()));
        var instrumentId = instrumentIds.get((int) (nextSequence % instrumentIds.size()));
        var tradeId = nextLiveTradeId();
        var settlementId = SettlementId.forTrade(tradeId);
        var side = nextSequence % 2 == 0 ? TradeSide.BUY : TradeSide.SELL;
        var quantity = Quantity.ONE;
        var executionPrice = latestPriceQuery.findLatestPrice(instrumentId)
                                             .map(LatestPrice::latestPrice)
                                             .orElse(FALLBACK_PRICE);

        commandBus.send(new PlaceTrade(tradeId, accountId, instrumentId, side, quantity, executionPrice));
        commandBus.send(new ExecuteTrade(tradeId));
        commandBus.send(new RequestSettlement(tradeId, settlementId));
        commandBus.send(new CreateSettlement(settlementId, tradeId, accountId, grossAmount(executionPrice, quantity)));
        commandBus.send(new RequestClearing(settlementId));
        commandBus.send(new ConfirmClearing(settlementId));

        latestTradeId = tradeId;
        latestSettlementId = settlementId;
        generatedTradeCount.incrementAndGet();
        pendingSettlements.add(new PendingSettlement(tradeId, settlementId, accountId, executionPrice, side));
        // See doGeneratePriceUpdate: status is published by the caller after simulationLock is released, so a burst
        // pays for one dashboard render instead of one per item, and none of them while holding the lock.
    }

    /**
     * {@code Amount} and {@code Quantity} are deliberately different types, so the two are multiplied through their
     * underlying values and the result is re-typed as money.
     */
    private static Amount grossAmount(Amount price, Quantity quantity) {
        return Amount.of(price.value().multiply(quantity.value()));
    }

    private TradeId nextLiveTradeId() {
        return TradeId.of("TRD-LIVE-" + RandomIdGenerator.generate());
    }

    private void settleNextPendingTrade() {
        var pendingSettlement = pendingSettlements.poll();
        if (pendingSettlement == null) {
            throw new IllegalStateException("No pending settlements available");
        }

        commandBus.send(new MarkSettlementSettled(pendingSettlement.settlementId()));
        commandBus.send(new ReconcileSettlement(pendingSettlement.settlementId()));
        commandBus.send(new CloseSettlement(pendingSettlement.settlementId()));
        commandBus.send(new MarkTradeSettled(pendingSettlement.tradeId()));
        commandBus.send(new ApplyTradeSettlement(pendingSettlement.accountId(),
                                                 pendingSettlement.tradeId(),
                                                 pendingSettlement.executionPrice().negate(),
                                                 pendingSettlement.side() == TradeSide.BUY ? BUY_REALIZED_PNL : SELL_REALIZED_PNL));
        generatedSettlementCount.incrementAndGet();
        // Status is published by the caller once simulationLock is released — see createPendingTradeAndSettlement.
    }

    private void logWaitingForSeedData() {
        if (waitingForSeedDataLogged.compareAndSet(false, true)) {
            log.info("Trading runtime load generator is waiting for bootstrap seed data before generating live activity");
        }
    }

    private void initializeLatestPriceTickersIfMissing() {
        if (!latestPriceTickers.isEmpty()) {
            return;
        }
        demoInstrumentIds().forEach(instrumentId -> {
            switch (currentPriceStressMode) {
                case DIRECT_WRITE -> directInstrumentPriceService.findLatestPrice(instrumentId)
                                                                 .ifPresent(price -> latestPriceTickers.put(instrumentId,
                                                                                                            new InstrumentPriceTickerView(instrumentId,
                                                                                                                                          price.toPlainString())));
                case AGGREGATE_EVENT_SOURCED -> latestPriceQuery.findLatestPrice(instrumentId)
                                                                .ifPresent(price -> latestPriceTickers.put(instrumentId,
                                                                                                           new InstrumentPriceTickerView(instrumentId,
                                                                                                                                         price.latestPrice().toPlainString())));
            }
        });
    }

    /**
     * One half of the closing-books comparison.
     *
     * <p><b>The read pass loads the aggregate, and has to.</b> What this scenario measures is the cost of
     * <em>rehydrating a trading account</em> under two rollover policies -- how far back the replay reaches, and how
     * often a snapshot spares it. That is a write-model operation by definition, so {@code readElapsedMillis} and the
     * four {@code snapshot*Delta} figures only mean anything if the pass actually performs one. Routing it through
     * {@code brokerage.account_statement} would time a single-row {@code SELECT} against a projection instead, and
     * every snapshot delta would read zero -- measuring nothing the scenario claims to compare.
     *
     * <p>So this is the harness's second sanctioned use of a repository wrapper, alongside the bootstrap's idempotency
     * probe. It is not the read side leaking back into the write model: nothing here reads a field off the returned
     * aggregate, and the value is discarded. The load itself is the measurement.
     *
     * <p>Each load gets its own unit of work, which is what the deleted {@code TradingAccountService.load} got from its
     * {@code @Transactional(readOnly = true)}. Sharing one across the whole pass would let the aggregate cache serve
     * later iterations and collapse the timing.
     */
    private TradingAccountScenarioModeResultView runTradingAccountScenario(String modeLabel,
                                                                           ClosingBooksDefaultPolicyType mode,
                                                                           int mutationCount,
                                                                           int readPasses,
                                                                           long eventThreshold,
                                                                           String scenarioId) {
        tradingAccountClosingBooksPolicy.update(settings -> settings.withMode(mode)
                                                                    .withEventThreshold(eventThreshold)
                                                                    .withTimeBoundary(ClosingBooksTimeBoundary.NONE));

        var accountIds = createComparisonAccounts(modeLabel, scenarioId);
        var beforeMetrics = tradingAccountSnapshotMetrics();

        var writeStarted = System.nanoTime();
        for (int index = 0; index < mutationCount; index++) {
            var accountId = accountIds.get(index % accountIds.size());
            commandBus.send(new ApplyTradeSettlement(accountId,
                                                     TradeId.of("trade-cmp-" + modeLabel + "-" + scenarioId + "-" + index),
                                                     Amount.of(BigDecimal.valueOf(-10)),
                                                     Amount.of(BigDecimal.ONE)));
        }
        var writeElapsedMillis = nanosToMillis(System.nanoTime() - writeStarted);

        var readStarted = System.nanoTime();
        for (int readPass = 0; readPass < readPasses; readPass++) {
            for (var accountId : accountIds) {
                unitOfWorkFactory.usingUnitOfWork(uow -> tradingAccounts.getAccount(accountId));
            }
        }
        var readElapsedMillis = nanosToMillis(System.nanoTime() - readStarted);
        var afterMetrics = tradingAccountSnapshotMetrics();

        long rolledOverAccountCount = 0;
        long totalGenerations = 0;
        long maxGeneration = 0;
        for (var accountId : accountIds) {
            var generations = tradingAccountGenerationRepository.loadGenerations(TradingAccounts.AGGREGATE_TYPE,
                                                                                 new LogicalAggregateId<>(accountId));
            totalGenerations += generations.size();
            if (generations.size() > 1) {
                rolledOverAccountCount++;
            }
            maxGeneration = Math.max(maxGeneration,
                                     generations.stream().mapToLong(generation -> generation.generation()).max().orElse(0));
        }

        return new TradingAccountScenarioModeResultView(modeLabel,
                                                        accountIds.size(),
                                                        mutationCount,
                                                        readPasses,
                                                        eventThreshold,
                                                        writeElapsedMillis,
                                                        readElapsedMillis,
                                                        writeElapsedMillis + readElapsedMillis,
                                                        rolledOverAccountCount,
                                                        totalGenerations,
                                                        maxGeneration,
                                                        afterMetrics.getOrDefault("essentials.aggregate_snapshot.load_snapshot", 0L)
                                                                - beforeMetrics.getOrDefault("essentials.aggregate_snapshot.load_snapshot", 0L),
                                                        afterMetrics.getOrDefault("essentials.aggregate_snapshot.save_snapshot", 0L)
                                                                - beforeMetrics.getOrDefault("essentials.aggregate_snapshot.save_snapshot", 0L),
                                                        afterMetrics.getOrDefault("essentials.aggregate_snapshot.serialize_snapshot", 0L)
                                                                - beforeMetrics.getOrDefault("essentials.aggregate_snapshot.serialize_snapshot", 0L),
                                                        afterMetrics.getOrDefault("essentials.aggregate_snapshot.deserialize_snapshot", 0L)
                                                                - beforeMetrics.getOrDefault("essentials.aggregate_snapshot.deserialize_snapshot", 0L),
                                                        mode == ClosingBooksDefaultPolicyType.MANUAL_ONLY
                                                                ? "Disables automatic rollover so writes and reads keep using one growing generation."
                                                                : "Enables event-count rollover so reads can span shorter generations at the configured threshold.");
    }

    private List<TradingAccountId> createComparisonAccounts(String modeLabel, String scenarioId) {
        return List.of(1, 2, 3).stream()
                   .map(index -> TradingAccountId.of("ACC-CMP-%s-%s-%03d".formatted(modeLabel.toUpperCase().replace('-', '_'),
                                                                                     scenarioId.substring(0, Math.min(8, scenarioId.length())),
                                                                                     index)))
                   .peek(accountId -> {
                       commandBus.send(new OpenTradingAccount(accountId,
                                                              OwnerId.of("comparison-" + modeLabel),
                                                              PeriodId.of("2026-04")));
                       commandBus.send(new DepositCash(accountId, Amount.of(BigDecimal.valueOf(50_000))));
                   })
                   .toList();
    }

    private Map<String, Long> tradingAccountSnapshotMetrics() {
        return meterRegistry.map(registry -> List.of(
                                              "essentials.aggregate_snapshot.load_snapshot",
                                              "essentials.aggregate_snapshot.save_snapshot",
                                              "essentials.aggregate_snapshot.serialize_snapshot",
                                              "essentials.aggregate_snapshot.deserialize_snapshot")
                                          .stream()
                                          .collect(Collectors.toMap(metricName -> metricName,
                                                                    metricName -> registry.find(metricName)
                                                                                          .tag("aggregate_type", TradingAccounts.AGGREGATE_TYPE.toString())
                                                                                          // Derived from the class, not written out as a string literal -
                                                                                          // the literal silently stopped matching the moment the class moved.
                                                                                          .tag("aggregate_impl_type", TradingAccount.class.getName())
                                                                                          .timers()
                                                                                          .stream()
                                                                                          .mapToLong(Timer::count)
                                                                                          .sum())))
                            .orElseGet(Map::of);
    }

    private int normalizeReadPasses(int readPasses) {
        if (readPasses <= 0) {
            throw new IllegalArgumentException("readPasses must be > 0");
        }
        return Math.min(readPasses, 1_000);
    }

    private long normalizeEventThreshold(long eventThreshold) {
        if (eventThreshold <= 0) {
            throw new IllegalArgumentException("eventThreshold must be > 0");
        }
        return Math.min(eventThreshold, 10_000);
    }

    private double nanosToMillis(long nanos) {
        return nanos / 1_000_000d;
    }

    private List<TradingAccountId> demoAccountIds() {
        return IntStream.range(0, simulationProperties.getAccountCount())
                        .mapToObj(index -> TradingAccountId.of("ACC-DEMO-%03d".formatted(index + 1)))
                        .toList();
    }

    private List<InstrumentId> demoInstrumentIds() {
        return TradingSimulationIds.instrumentIds(simulationProperties.getInstrumentCount());
    }

    private ThreadFactory daemonThreadFactory() {
        return runnable -> {
            var thread = new Thread(runnable, "trading-demo-load-generator");
            thread.setDaemon(true);
            return thread;
        };
    }

    private ThreadFactory priceStressThreadFactory() {
        return runnable -> {
            var thread = new Thread(runnable, "trading-demo-price-stress");
            thread.setDaemon(true);
            return thread;
        };
    }

    private TradingLoadGeneratorStatusView withSimulationLock(StatusSupplier supplier) {
        simulationLock.lock();
        try {
            return supplier.get();
        } finally {
            simulationLock.unlock();
        }
    }

    private <T> T withSimulationLockResult(ResultSupplier<T> supplier) {
        simulationLock.lock();
        try {
            return supplier.get();
        } finally {
            simulationLock.unlock();
        }
    }

    private void withSimulationLockVoid(Runnable runnable) {
        simulationLock.lock();
        try {
            runnable.run();
        } finally {
            simulationLock.unlock();
        }
    }

    private void runAsyncPriceStress(int requestedCount, long intervalMillis) {
        try {
            for (int i = 0; i < requestedCount && priceStressRunning.get(); i++) {
                withSimulationLockVoid(this::generatePriceUpdate);
                priceStressCompletedCount.incrementAndGet();
                publishStatus();
                if (intervalMillis > 0 && i + 1 < requestedCount && priceStressRunning.get()) {
                    TimeUnit.MILLISECONDS.sleep(intervalMillis);
                }
            }
            log.info("Trading runtime load generator completed async price stress with {} of {} updates",
                     priceStressCompletedCount.get(),
                     requestedCount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Trading runtime load generator interrupted async price stress");
        } catch (Exception e) {
            log.warn("Trading runtime load generator failed async price stress", e);
        } finally {
            priceStressRunning.set(false);
            publishStatus();
        }
    }

    private List<InstrumentPriceTickerView> latestPrices() {
        return demoInstrumentIds().stream()
                                  .map(instrumentId -> latestPriceTickers.getOrDefault(instrumentId,
                                                                                       new InstrumentPriceTickerView(instrumentId, "n/a")))
                                  .toList();
    }

    public List<PricePathPerformanceSnapshot> pricePathPerformanceSnapshots() {
        return List.of(new PricePathPerformanceSnapshot(PriceStressMode.AGGREGATE_EVENT_SOURCED,
                                                        aggregatePriceOpCount.get(),
                                                        aggregatePriceOpTotalNanos.get(),
                                                        aggregatePriceOpMaxNanos.get(),
                                                        AGGREGATE_DESCRIPTION),
                       new PricePathPerformanceSnapshot(PriceStressMode.DIRECT_WRITE,
                                                        directWritePriceOpCount.get(),
                                                        directWritePriceOpTotalNanos.get(),
                                                        directWritePriceOpMaxNanos.get(),
                                                        DIRECT_WRITE_DESCRIPTION));
    }

    private PricePathScenarioModeResultView runPricePathScenario(PriceStressMode mode, int count) {
        currentPriceStressMode = mode;
        var before = performanceSnapshot(mode);
        var startedAt = System.nanoTime();
        for (int i = 0; i < count; i++) {
            generatePriceUpdate();
        }
        var elapsedNanos = System.nanoTime() - startedAt;
        var after = performanceSnapshot(mode);
        var completedCount = Math.max(0, after.operationCount() - before.operationCount());
        var totalNanos = Math.max(0, after.totalNanos() - before.totalNanos());
        var maxNanos = Math.max(0, after.maxNanos());
        return new PricePathScenarioModeResultView(mode.name().toLowerCase().replace('_', '-'),
                                                   count,
                                                   completedCount,
                                                   elapsedNanos / 1_000_000d,
                                                   completedCount == 0 ? 0 : (totalNanos / 1_000_000d) / completedCount,
                                                   maxNanos / 1_000_000d,
                                                   mode == PriceStressMode.AGGREGATE_EVENT_SOURCED ? AGGREGATE_DESCRIPTION : DIRECT_WRITE_DESCRIPTION);
    }

    private PricePathPerformanceSnapshot performanceSnapshot(PriceStressMode mode) {
        return switch (mode) {
            case AGGREGATE_EVENT_SOURCED -> new PricePathPerformanceSnapshot(mode,
                                                                             aggregatePriceOpCount.get(),
                                                                             aggregatePriceOpTotalNanos.get(),
                                                                             aggregatePriceOpMaxNanos.get(),
                                                                             AGGREGATE_DESCRIPTION);
            case DIRECT_WRITE -> new PricePathPerformanceSnapshot(mode,
                                                                  directWritePriceOpCount.get(),
                                                                  directWritePriceOpTotalNanos.get(),
                                                                  directWritePriceOpMaxNanos.get(),
                                                                  DIRECT_WRITE_DESCRIPTION);
        };
    }

    private void recordAggregateDuration(long nanos) {
        aggregatePriceOpCount.incrementAndGet();
        aggregatePriceOpTotalNanos.addAndGet(nanos);
        aggregatePriceOpMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    private void recordDirectWriteDuration(long nanos) {
        directWritePriceOpCount.incrementAndGet();
        directWritePriceOpTotalNanos.addAndGet(nanos);
        directWritePriceOpMaxNanos.accumulateAndGet(nanos, Math::max);
    }

    private void publishStatus() {
        var currentStatus = status();
        statusListeners.forEach(listener -> {
            try {
                listener.accept(currentStatus);
            } catch (RuntimeException ignored) {
            }
        });
    }

    private void stopPriceStressExecutor() {
        priceStressRunning.set(false);
        var currentPriceStressExecutor = priceStressExecutor;
        priceStressExecutor = null;
        if (currentPriceStressExecutor != null) {
            currentPriceStressExecutor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface StatusSupplier {
        TradingLoadGeneratorStatusView get();
    }

    @FunctionalInterface
    private interface ResultSupplier<T> {
        T get();
    }

    private record PendingSettlement(TradeId tradeId,
                                     SettlementId settlementId,
                                     TradingAccountId accountId,
                                     Amount executionPrice,
                                     TradeSide side) {
    }

    public record PricePathPerformanceSnapshot(PriceStressMode mode,
                                               long operationCount,
                                               long totalNanos,
                                               long maxNanos,
                                               String description) {
    }
}
