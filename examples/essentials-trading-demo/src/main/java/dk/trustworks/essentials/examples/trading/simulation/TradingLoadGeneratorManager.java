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

import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksGenerationRepository;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.LogicalAggregateId;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksDefaultPolicyType;
import dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks.ClosingBooksTimeBoundary;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.persistence.OptimisticAppendToStreamException;
import dk.trustworks.essentials.components.foundation.types.RandomIdGenerator;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountId;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountClosingBooksPolicy;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountService;
import dk.trustworks.essentials.examples.trading.config.TradingDemoAggregateConfiguration;
import dk.trustworks.essentials.examples.trading.instruments.InstrumentId;
import dk.trustworks.essentials.examples.trading.prices.DirectInstrumentPriceService;
import dk.trustworks.essentials.examples.trading.prices.InstrumentPriceService;
import dk.trustworks.essentials.examples.trading.settlements.SettlementId;
import dk.trustworks.essentials.examples.trading.settlements.SettlementService;
import dk.trustworks.essentials.examples.trading.trades.TradeId;
import dk.trustworks.essentials.examples.trading.trades.TradeService;
import dk.trustworks.essentials.shared.Lifecycle;
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

/**
 * Generates runtime demo traffic while the application is running.
 */
public class TradingLoadGeneratorManager implements Lifecycle {
    private static final Logger log = LoggerFactory.getLogger(TradingLoadGeneratorManager.class);
    private static final int    PRICE_UPDATE_RETRY_ATTEMPTS = 5;
    private static final String AGGREGATE_DESCRIPTION = "Event-sourced aggregate path: load aggregate, apply event, append event, commit transaction.";
    private static final String DIRECT_WRITE_DESCRIPTION = "Direct-write market data path: single-row upsert of latest price.";

    private final TradingDemoSimulationProperties simulationProperties;
    private final TradingDemoLoadGeneratorProperties loadProperties;
    private final TradingAccountService tradingAccountService;
    private final TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy;
    private final ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository;
    private final InstrumentPriceService instrumentPriceService;
    private final DirectInstrumentPriceService directInstrumentPriceService;
    private final SettlementService settlementService;
    private final TradeService tradeService;
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
    private final Queue<PendingSettlement> pendingSettlements = new ConcurrentLinkedQueue<>();
    private final ReentrantLock simulationLock = new ReentrantLock();
    private final CopyOnWriteArrayList<Consumer<TradingLoadGeneratorStatusView>> statusListeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, InstrumentPriceTickerView> latestPriceTickers = new ConcurrentHashMap<>();

    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService priceStressExecutor;
    private volatile String latestTradeId;
    private volatile String latestSettlementId;
    private volatile String latestPriceInstrumentId;
    private volatile long priceStressIntervalMillis;
    private volatile PriceStressMode currentPriceStressMode = PriceStressMode.AGGREGATE_EVENT_SOURCED;
    private volatile PricePathScenarioResultView latestPricePathScenarioResult = PricePathScenarioResultView.unavailable();
    private volatile TradingAccountScenarioResultView latestTradingAccountScenarioResult = TradingAccountScenarioResultView.unavailable();

    public TradingLoadGeneratorManager(TradingDemoSimulationProperties simulationProperties,
                                       TradingDemoLoadGeneratorProperties loadProperties,
                                       TradingAccountService tradingAccountService,
                                       TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy,
                                       ClosingBooksGenerationRepository<TradingAccountId> tradingAccountGenerationRepository,
                                       InstrumentPriceService instrumentPriceService,
                                       DirectInstrumentPriceService directInstrumentPriceService,
                                       SettlementService settlementService,
                                       TradeService tradeService,
                                       Optional<MeterRegistry> meterRegistry) {
        this.simulationProperties = simulationProperties;
        this.loadProperties = loadProperties;
        this.tradingAccountService = tradingAccountService;
        this.tradingAccountClosingBooksPolicy = tradingAccountClosingBooksPolicy;
        this.tradingAccountGenerationRepository = tradingAccountGenerationRepository;
        this.instrumentPriceService = instrumentPriceService;
        this.directInstrumentPriceService = directInstrumentPriceService;
        this.settlementService = settlementService;
        this.tradeService = tradeService;
        this.meterRegistry = meterRegistry;
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
        return withSimulationLockResult(() -> {
            var safeCount = normalizeBurstCount(count);
            ensureSeedDataAvailableForBurst();
            for (int i = 0; i < safeCount; i++) {
                generateTradeLifecycle();
            }
            log.info("Trading runtime load generator completed a trade lifecycle burst of {} items", safeCount);
            return status();
        });
    }

    public TradingLoadGeneratorStatusView generatePendingTradeBurst(int count) {
        return withSimulationLock(() -> {
            var safeCount = normalizeBurstCount(count);
            ensureSeedDataAvailableForBurst();
            for (int i = 0; i < safeCount; i++) {
                createPendingTradeAndSettlement();
            }
            log.info("Trading runtime load generator completed a pending trade burst of {} items", safeCount);
            return status();
        });
    }

    public TradingLoadGeneratorStatusView settlePendingTradeBurst(int count) {
        return withSimulationLock(() -> {
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
    }

    public TradingLoadGeneratorStatusView generatePriceUpdateBurst(int count) {
        return withSimulationLock(() -> {
            var safeCount = normalizeBurstCount(count);
            ensureSeedDataAvailableForBurst();
            for (int i = 0; i < safeCount; i++) {
                generatePriceUpdate();
            }
            log.info("Trading runtime load generator completed a price update burst of {} items", safeCount);
            return status();
        });
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

    public TradingAccountScenarioResultView runTradingAccountComparisonScenario(int mutationCount, int readPasses, long eventThreshold) {
        return withSimulationLockResult(() -> {
            if (priceStressRunning.get()) {
                throw new IllegalStateException("Stop the async price stress run before starting a comparison scenario.");
            }
            var safeMutationCount = normalizeBurstCount(mutationCount);
            var safeReadPasses = normalizeReadPasses(readPasses);
            var safeEventThreshold = normalizeEventThreshold(eventThreshold);

            var originalMode = tradingAccountClosingBooksPolicy.mode();
            var originalThreshold = tradingAccountClosingBooksPolicy.eventThreshold();
            var originalTimeBoundary = tradingAccountClosingBooksPolicy.timeBoundary();
            var originalZoneId = tradingAccountClosingBooksPolicy.zoneId();
            var originalIntervalDays = tradingAccountClosingBooksPolicy.intervalDays();
            try {
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
            } finally {
                tradingAccountClosingBooksPolicy.updateMode(originalMode.name());
                tradingAccountClosingBooksPolicy.updateEventThreshold(originalThreshold);
                tradingAccountClosingBooksPolicy.updateTimeBoundary(originalTimeBoundary.name());
                tradingAccountClosingBooksPolicy.updateZoneId(originalZoneId);
                if (originalIntervalDays != null && originalIntervalDays > 0) {
                    tradingAccountClosingBooksPolicy.updateIntervalDays(originalIntervalDays);
                }
            }
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
    }

    private void safeGeneratePriceUpdate() {
        withSimulationLockVoid(() -> {
            try {
                generatePriceUpdate();
            } catch (Exception e) {
                log.warn("Trading runtime load generator failed to generate a price update", e);
            }
        });
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
        var instrumentId = InstrumentId.of(demoInstrumentIds().get((int) (nextSequence % demoInstrumentIds().size())));
        var currentPrice = currentPrice(instrumentId);
        var jitter = BigDecimal.valueOf(loadProperties.getPriceJitter().getMin()
                                        + (nextSequence % Math.max(1, loadProperties.getPriceJitter().getMax() - loadProperties.getPriceJitter().getMin() + 1)));
        var direction = nextSequence % 2 == 0 ? BigDecimal.ONE : BigDecimal.ONE.negate();
        var nextPrice = currentPrice.add(jitter.multiply(direction));
        if (nextPrice.signum() <= 0) {
            nextPrice = BigDecimal.valueOf(50);
        }
        var startNanos = System.nanoTime();
        if (currentPriceStressMode == PriceStressMode.DIRECT_WRITE) {
            directInstrumentPriceService.updatePrice(instrumentId, nextPrice);
            recordDirectWriteDuration(System.nanoTime() - startNanos);
        } else {
            instrumentPriceService.updatePrice(instrumentId, nextPrice);
            recordAggregateDuration(System.nanoTime() - startNanos);
        }
        latestPriceInstrumentId = instrumentId.toString();
        latestPriceTickers.put(instrumentId.toString(),
                               new InstrumentPriceTickerView(instrumentId.toString(), nextPrice.toPlainString()));
        generatedPriceUpdateCount.incrementAndGet();
        publishStatus();
    }

    private boolean seedDataIsAvailable() {
        return tradingAccountService.tryLoad(TradingAccountId.of("ACC-DEMO-001")).isPresent()
               && !demoInstrumentIds().isEmpty()
               && instrumentPriceService.tryLoad(InstrumentId.of(demoInstrumentIds().get(0))).isPresent();
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

    private BigDecimal currentPrice(InstrumentId instrumentId) {
        var cachedPrice = latestPriceTickers.get(instrumentId.toString());
        if (cachedPrice != null) {
            return new BigDecimal(cachedPrice.latestPrice());
        }
        return switch (currentPriceStressMode) {
            case DIRECT_WRITE -> directInstrumentPriceService.tryLoad(instrumentId).orElse(BigDecimal.valueOf(500));
            case AGGREGATE_EVENT_SOURCED -> instrumentPriceService.tryLoad(instrumentId)
                                                                  .map(instrumentPrice -> instrumentPrice.latestPrice)
                                                                  .orElse(BigDecimal.valueOf(500));
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
        var accountId = TradingAccountId.of(demoAccountIds().get((int) (nextSequence % demoAccountIds().size())));
        var instrumentId = InstrumentId.of(demoInstrumentIds().get((int) (nextSequence % demoInstrumentIds().size())));
        var tradeId = nextLiveTradeId();
        var settlementId = SettlementId.of(tradeId + "-SET");
        var side = nextSequence % 2 == 0 ? "BUY" : "SELL";
        var quantity = BigDecimal.ONE;
        var executionPrice = instrumentPriceService.tryLoad(instrumentId)
                                                   .map(instrumentPrice -> instrumentPrice.latestPrice)
                                                   .orElse(BigDecimal.valueOf(500));

        tradeService.placeTrade(tradeId, accountId, instrumentId, side, quantity, executionPrice);
        tradeService.executeTrade(tradeId);
        tradeService.requestSettlement(tradeId, settlementId.toString());
        settlementService.createSettlement(settlementId, tradeId.toString(), accountId.toString(), executionPrice.multiply(quantity));
        settlementService.requestClearing(settlementId);
        settlementService.confirmClearing(settlementId);

        latestTradeId = tradeId.toString();
        latestSettlementId = settlementId.toString();
        generatedTradeCount.incrementAndGet();
        pendingSettlements.add(new PendingSettlement(tradeId, settlementId, accountId, executionPrice, side));
        publishStatus();
    }

    private TradeId nextLiveTradeId() {
        return TradeId.of("TRD-LIVE-" + RandomIdGenerator.generate());
    }

    private void settleNextPendingTrade() {
        var pendingSettlement = pendingSettlements.poll();
        if (pendingSettlement == null) {
            throw new IllegalStateException("No pending settlements available");
        }

        settlementService.markSettled(pendingSettlement.settlementId());
        settlementService.reconcile(pendingSettlement.settlementId());
        settlementService.closeSettlement(pendingSettlement.settlementId());
        tradeService.markSettled(pendingSettlement.tradeId());
        tradingAccountService.applyTradeSettlement(pendingSettlement.accountId(),
                                                   pendingSettlement.tradeId().toString(),
                                                   pendingSettlement.executionPrice().negate(),
                                                   BigDecimal.valueOf("BUY".equals(pendingSettlement.side()) ? 4 : 6));
        generatedSettlementCount.incrementAndGet();
        publishStatus();
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
            var typedInstrumentId = InstrumentId.of(instrumentId);
            switch (currentPriceStressMode) {
                case DIRECT_WRITE -> directInstrumentPriceService.tryLoad(typedInstrumentId)
                                                                 .ifPresent(price -> latestPriceTickers.put(instrumentId,
                                                                                                            new InstrumentPriceTickerView(instrumentId,
                                                                                                                                          price.toPlainString())));
                case AGGREGATE_EVENT_SOURCED -> instrumentPriceService.tryLoad(typedInstrumentId)
                                                                     .ifPresent(price -> latestPriceTickers.put(instrumentId,
                                                                                                                new InstrumentPriceTickerView(instrumentId,
                                                                                                                                              price.latestPrice.toPlainString())));
            }
        });
    }

    private TradingAccountScenarioModeResultView runTradingAccountScenario(String modeLabel,
                                                                          ClosingBooksDefaultPolicyType mode,
                                                                          int mutationCount,
                                                                          int readPasses,
                                                                          long eventThreshold,
                                                                          String scenarioId) {
        tradingAccountClosingBooksPolicy.updateMode(mode.name());
        tradingAccountClosingBooksPolicy.updateEventThreshold(eventThreshold);
        tradingAccountClosingBooksPolicy.updateTimeBoundary(ClosingBooksTimeBoundary.NONE.name());

        var accountIds = createComparisonAccounts(modeLabel, scenarioId);
        var beforeMetrics = tradingAccountSnapshotMetrics();

        var writeStarted = System.nanoTime();
        for (int index = 0; index < mutationCount; index++) {
            var accountId = accountIds.get(index % accountIds.size());
            tradingAccountService.applyTradeSettlement(accountId,
                                                       "trade-cmp-" + modeLabel + "-" + scenarioId + "-" + index,
                                                       BigDecimal.valueOf(-10),
                                                       BigDecimal.ONE);
        }
        var writeElapsedMillis = nanosToMillis(System.nanoTime() - writeStarted);

        var readStarted = System.nanoTime();
        for (int readPass = 0; readPass < readPasses; readPass++) {
            for (var accountId : accountIds) {
                tradingAccountService.load(accountId);
            }
        }
        var readElapsedMillis = nanosToMillis(System.nanoTime() - readStarted);
        var afterMetrics = tradingAccountSnapshotMetrics();

        long rolledOverAccountCount = 0;
        long totalGenerations = 0;
        long maxGeneration = 0;
        for (var accountId : accountIds) {
            var generations = tradingAccountGenerationRepository.loadGenerations(TradingDemoAggregateConfiguration.TRADING_ACCOUNTS,
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
                       tradingAccountService.openAccount(accountId, "comparison-" + modeLabel, "2026-04");
                       tradingAccountService.depositCash(accountId, BigDecimal.valueOf(50_000));
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
                                          .collect(java.util.stream.Collectors.toMap(metricName -> metricName,
                                                                                    metricName -> registry.find(metricName)
                                                                                                          .tag("aggregate_type", TradingDemoAggregateConfiguration.TRADING_ACCOUNTS.toString())
                                                                                                          .tag("aggregate_impl_type", "dk.trustworks.essentials.examples.trading.accounts.TradingAccount")
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

    private List<String> demoAccountIds() {
        return java.util.stream.IntStream.range(0, simulationProperties.getAccountCount())
                                         .mapToObj(index -> "ACC-DEMO-%03d".formatted(index + 1))
                                         .toList();
    }

    private List<String> demoInstrumentIds() {
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
                                     BigDecimal executionPrice,
                                     String side) {
    }

    public record PricePathPerformanceSnapshot(PriceStressMode mode,
                                               long operationCount,
                                               long totalNanos,
                                               long maxNanos,
                                               String description) {
    }
}
