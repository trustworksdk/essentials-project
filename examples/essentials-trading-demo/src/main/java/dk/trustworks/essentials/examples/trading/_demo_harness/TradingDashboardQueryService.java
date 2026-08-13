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
import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiAggregateSnapshot;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccount;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccountClosingBooksPolicy;
import dk.trustworks.essentials.examples.trading.brokerage.aggregates.TradingAccounts;
import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradingAccountId;
import dk.trustworks.essentials.examples.trading.brokerage.views.account_statement.AccountStatement;
import dk.trustworks.essentials.examples.trading.brokerage.views.account_statement.AccountStatementQuery;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.InstrumentPrice;
import dk.trustworks.essentials.examples.trading.market_data.types.MarketDataAggregateTypes;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Read-only aggregation service for the lightweight demo dashboard.
 *
 * <h2>Where the numbers come from</h2>
 * <ul>
 *   <li><b>Account balances</b> — the {@code brokerage.account_statement} view slice, not a rehydrated
 *       {@code TradingAccount}. That read model is projected asynchronously, so the balances shown here are
 *       <em>eventually consistent</em>; the pre-slice dashboard read the write aggregate and was not. For a status
 *       screen that refreshes on a timer this is the right trade, but it is a real change: an account that has just
 *       been opened shows up a moment later than it used to, and a burst of writes is reflected a moment after it
 *       lands.</li>
 *   <li><b>Snapshot counts</b> — {@link AggregateLifecycleApi} directly. Snapshots are event-store bookkeeping that
 *       nothing in this application projects, so there is no view slice to ask; the framework API is the read model.</li>
 *   <li><b>Closing-books configuration</b> — {@link TradingAccountClosingBooksPolicy#settings()}, one consistent
 *       snapshot of all five values rather than five separate reads that could disagree.</li>
 * </ul>
 */
@Service
public class TradingDashboardQueryService {
    /**
     * The principal the demo's admin surface acts as. The demo has no authentication; a real deployment would pass
     * the authenticated caller.
     */
    private static final String                  DEMO_ADMIN_PRINCIPAL = "demo-admin";
    /**
     * The snapshot timers are tagged per aggregate type, so the dashboard has to name every snapshotting aggregate it
     * wants counted. Reporting only {@code TradingAccounts} meant a price-stress run -- the workload the
     * {@code InstrumentPrices} aggregate exists to demonstrate -- could never move any number on the Snapshots tab.
     * <p>
     * Each context is named through the surface it publishes: {@code brokerage} exposes its stream name on the
     * repository wrapper, {@code market_data} on {@link MarketDataAggregateTypes}. The {@code aggregate_impl_type} tag
     * is derived from the class rather than written out as a string literal -- the literal silently stopped matching
     * the moment the class moved package, which is exactly what the slice refactor did to it. That is also the only
     * reason this harness names two BC-private aggregate classes: it reads {@code getName()} off them and nothing else.
     */
    private static final List<SnapshotAggregate> SNAPSHOT_AGGREGATES  = List.of(
            new SnapshotAggregate(TradingAccounts.AGGREGATE_TYPE.toString(), TradingAccount.class.getName()),
            new SnapshotAggregate(MarketDataAggregateTypes.INSTRUMENT_PRICES.toString(), InstrumentPrice.class.getName())
    );
    private static final List<String>            SNAPSHOT_METRIC_NAMES = List.of(
            "essentials.aggregate_snapshot.load_snapshot",
            "essentials.aggregate_snapshot.save_snapshot",
            "essentials.aggregate_snapshot.serialize_snapshot",
            "essentials.aggregate_snapshot.deserialize_snapshot"
    );

    private final TradingDemoSimulationProperties  simulationProperties;
    private final AccountStatementQuery            accountStatementQuery;
    private final AggregateLifecycleApi            aggregateLifecycleApi;
    private final TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy;
    private final TradingLoadGeneratorManager      tradingLoadGeneratorManager;
    private final Optional<MeterRegistry>          meterRegistry;

    public TradingDashboardQueryService(TradingDemoSimulationProperties simulationProperties,
                                        AccountStatementQuery accountStatementQuery,
                                        AggregateLifecycleApi aggregateLifecycleApi,
                                        TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy,
                                        TradingLoadGeneratorManager tradingLoadGeneratorManager,
                                        Optional<MeterRegistry> meterRegistry) {
        this.simulationProperties = requireNonNull(simulationProperties, "No simulationProperties provided");
        this.accountStatementQuery = requireNonNull(accountStatementQuery, "No accountStatementQuery provided");
        this.aggregateLifecycleApi = requireNonNull(aggregateLifecycleApi, "No aggregateLifecycleApi provided");
        this.tradingAccountClosingBooksPolicy = requireNonNull(tradingAccountClosingBooksPolicy, "No tradingAccountClosingBooksPolicy provided");
        this.tradingLoadGeneratorManager = requireNonNull(tradingLoadGeneratorManager, "No tradingLoadGeneratorManager provided");
        this.meterRegistry = requireNonNull(meterRegistry, "No meterRegistry provided");
    }

    @Transactional(readOnly = true)
    public DashboardSummaryView getSummary() {
        var accountSummaries = accountSummaries();
        var closingBooksSettings = tradingAccountClosingBooksPolicy.settings();
        var closingBooksStats = new DashboardClosingBooksStatsView(accountSummaries.stream()
                                                                                   .filter(account -> account.currentGeneration() > 1)
                                                                                   .count(),
                                                                   accountSummaries.stream()
                                                                                   .mapToLong(DashboardAccountSummaryView::generationCount)
                                                                                   .sum(),
                                                                   accountSummaries.stream()
                                                                                   .mapToLong(DashboardAccountSummaryView::currentGeneration)
                                                                                   .max()
                                                                                   .orElse(0),
                                                                   accountSummaries.isEmpty() ? 0
                                                                                             : accountSummaries.stream()
                                                                                                               .mapToInt(DashboardAccountSummaryView::generationCount)
                                                                                                               .average()
                                                                                                               .orElse(0),
                                                                   tradingAccountClosingBooksPolicy.description(),
                                                                   closingBooksSettings.mode().name().toLowerCase().replace('_', '-'),
                                                                   closingBooksSettings.eventThreshold(),
                                                                   closingBooksSettings.timeBoundary().name().toLowerCase().replace('_', '-'),
                                                                   closingBooksSettings.zoneId().toString(),
                                                                   closingBooksSettings.intervalDays());
        var snapshotMetricSummaries = snapshotMetrics();

        return new DashboardSummaryView(simulationProperties.getAccountCount(),
                                        accountSummaries.size(),
                                        accountSummaries,
                                        closingBooksStats,
                                        tradingLoadGeneratorManager.status(),
                                        pricePathComparison(),
                                        tradingLoadGeneratorManager.latestPricePathScenarioResult(),
                                        tradingLoadGeneratorManager.latestTradingAccountScenarioResult(),
                                        snapshotStats(snapshotMetricSummaries),
                                        snapshotMetricSummaries,
                                        List.of(TradeId.of("TRD-001-001"), TradeId.of("TRD-LIVE-001001")),
                                        List.of(SettlementId.forTrade(TradeId.of("TRD-001-001")),
                                                SettlementId.forTrade(TradeId.of("TRD-LIVE-001001"))));
    }

    /**
     * The configured demo accounts, in id order, skipping any the statement projection has not caught up with yet.
     * <p>
     * Every statement row is fetched once and indexed, rather than queried per account: the load generator's
     * comparison scenario creates {@code ACC-CMP-*} accounts that also land in this read model, so the demo accounts
     * are selected out of the result rather than the read model being asked for each of them in turn.
     */
    private List<DashboardAccountSummaryView> accountSummaries() {
        Map<TradingAccountId, AccountStatement> statementsByAccountId =
                accountStatementQuery.accountStatements()
                                     .stream()
                                     .collect(Collectors.toMap(AccountStatement::logicalAccountId,
                                                               Function.identity(),
                                                               (first, second) -> first));

        var accountSummaries = new ArrayList<DashboardAccountSummaryView>();
        for (int index = 0; index < simulationProperties.getAccountCount(); index++) {
            var accountId = TradingAccountId.of("ACC-DEMO-%03d".formatted(index + 1));
            var statement = statementsByAccountId.get(accountId);
            if (statement == null) {
                continue;
            }
            try {
                var snapshots = currentGenerationSnapshots(accountId);
                accountSummaries.add(new DashboardAccountSummaryView(statement.logicalAccountId(),
                                                                     statement.generationCount(),
                                                                     statement.currentGeneration(),
                                                                     statement.periodId(),
                                                                     statement.cashBalance(),
                                                                     statement.realizedPnl(),
                                                                     statement.booksClosed(),
                                                                     snapshots.size(),
                                                                     snapshots.stream()
                                                                              .map(ApiAggregateSnapshot::lastIncludedEventOrder)
                                                                              .max(Long::compareTo)
                                                                              .orElse(null)));
            } catch (Exception ignored) {
            }
        }
        return accountSummaries;
    }

    /**
     * Snapshots stored for the account's <em>current</em> generation.
     * <p>
     * Scoped to the current generation on purpose: snapshots are keyed by the per-generation stream id, so
     * "all snapshots for this account" means one lookup per generation, and the demo's load generator rolls
     * generations continuously -- an unbounded fan-out on a dashboard that refreshes on a timer. The live
     * generation is also the interesting one when watching the snapshot policy work.
     */
    private List<ApiAggregateSnapshot> currentGenerationSnapshots(TradingAccountId accountId) {
        return aggregateLifecycleApi.findCurrentClosingBooksGeneration(DEMO_ADMIN_PRINCIPAL,
                                                                      TradingAccounts.AGGREGATE_TYPE,
                                                                      accountId.toString())
                                    .map(generation -> aggregateLifecycleApi.findSnapshots(DEMO_ADMIN_PRINCIPAL,
                                                                                           TradingAccounts.AGGREGATE_TYPE,
                                                                                           generation.streamAggregateId(),
                                                                                           false))
                                    .orElseGet(List::of);
    }

    private PricePathComparisonView pricePathComparison() {
        var performances = tradingLoadGeneratorManager.pricePathPerformanceSnapshots().stream()
                                                      .map(snapshot -> {
                                                          var totalMillis = snapshot.totalNanos() / 1_000_000d;
                                                          var averageMillis = snapshot.operationCount() == 0 ? 0 : totalMillis / snapshot.operationCount();
                                                          var maxMillis = snapshot.maxNanos() / 1_000_000d;
                                                          return new PricePathPerformanceView(snapshot.mode().name().toLowerCase().replace('_', '-'),
                                                                                              snapshot.operationCount(),
                                                                                              totalMillis,
                                                                                              averageMillis,
                                                                                              maxMillis,
                                                                                              snapshot.description());
                                                      })
                                                      .toList();
        return new PricePathComparisonView(tradingLoadGeneratorManager.status().currentPriceStressMode(),
                                           "High-frequency market prices are intentionally shown as a contrast case. The aggregate path is heavier but preserves a full event history, while the direct-write path behaves more like a latest-price market data store.",
                                           performances);
    }

    private List<DashboardMetricSummaryView> snapshotMetrics() {
        return meterRegistry.map(registry -> SNAPSHOT_AGGREGATES.stream()
                                                                .flatMap(snapshotAggregate -> SNAPSHOT_METRIC_NAMES.stream()
                                                                                                                   .map(metricName -> toMetricSummary(registry,
                                                                                                                                                      snapshotAggregate,
                                                                                                                                                      metricName)))
                                                                .toList())
                            .orElseGet(List::of);
    }

    private DashboardMetricSummaryView toMetricSummary(MeterRegistry registry, SnapshotAggregate snapshotAggregate, String metricName) {
        var timers = registry.find(metricName)
                             .tag("aggregate_type", snapshotAggregate.aggregateType())
                             .tag("aggregate_impl_type", snapshotAggregate.aggregateImplType())
                             .timers();
        if (timers.isEmpty()) {
            return new DashboardMetricSummaryView(snapshotAggregate.aggregateType(), metricName, 0, 0, 0);
        }
        long count = timers.stream()
                           .mapToLong(Timer::count)
                           .sum();
        double totalTimeMs = timers.stream()
                                   .mapToDouble(timer -> timer.totalTime(TimeUnit.MILLISECONDS))
                                   .sum();
        double maxMs = timers.stream()
                             .mapToDouble(timer -> timer.max(TimeUnit.MILLISECONDS))
                             .max()
                             .orElse(0);
        return new DashboardMetricSummaryView(snapshotAggregate.aggregateType(),
                                              metricName,
                                              count,
                                              totalTimeMs,
                                              maxMs);
    }

    private DashboardSnapshotStatsView snapshotStats(List<DashboardMetricSummaryView> metricSummaries) {
        long loadCount = countFor(metricSummaries, "essentials.aggregate_snapshot.load_snapshot");
        long saveCount = countFor(metricSummaries, "essentials.aggregate_snapshot.save_snapshot");
        long serializeCount = countFor(metricSummaries, "essentials.aggregate_snapshot.serialize_snapshot");
        long deserializeCount = countFor(metricSummaries, "essentials.aggregate_snapshot.deserialize_snapshot");
        double totalObservedTimeMs = metricSummaries.stream()
                                                    .mapToDouble(DashboardMetricSummaryView::totalTimeMs)
                                                    .sum();
        return new DashboardSnapshotStatsView(SNAPSHOT_AGGREGATES.stream()
                                                                 .map(SnapshotAggregate::aggregateType)
                                                                 .collect(Collectors.joining(", ")),
                                              loadCount,
                                              saveCount,
                                              serializeCount,
                                              deserializeCount,
                                              totalObservedTimeMs);
    }

    private long countFor(List<DashboardMetricSummaryView> metricSummaries, String name) {
        return metricSummaries.stream()
                              .filter(metric -> metric.name().equals(name))
                              .mapToLong(DashboardMetricSummaryView::count)
                              .sum();
    }

    private record SnapshotAggregate(String aggregateType, String aggregateImplType) {
    }
}
