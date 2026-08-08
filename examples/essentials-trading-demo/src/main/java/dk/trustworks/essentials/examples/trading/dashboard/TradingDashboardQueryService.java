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

package dk.trustworks.essentials.examples.trading.dashboard;

import dk.trustworks.essentials.components.eventsourced.aggregates.api.ApiAggregateSnapshot;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccount;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountAdminQueryService;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountClosingBooksPolicy;
import dk.trustworks.essentials.examples.trading.accounts.TradingAccountId;
import dk.trustworks.essentials.examples.trading.config.TradingDemoAggregateConfiguration;
import dk.trustworks.essentials.examples.trading.simulation.TradingDemoSimulationProperties;
import dk.trustworks.essentials.examples.trading.simulation.TradingLoadGeneratorManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Read-only aggregation service for the lightweight demo dashboard.
 */
@Service
public class TradingDashboardQueryService {
    private static final String SNAPSHOT_AGGREGATE_TYPE = TradingDemoAggregateConfiguration.TRADING_ACCOUNTS.toString();
    private static final String SNAPSHOT_AGGREGATE_IMPL_TYPE = TradingAccount.class.getName();
    private static final List<String> SNAPSHOT_METRIC_NAMES = List.of(
            "essentials.aggregate_snapshot.load_snapshot",
            "essentials.aggregate_snapshot.save_snapshot",
            "essentials.aggregate_snapshot.serialize_snapshot",
            "essentials.aggregate_snapshot.deserialize_snapshot"
    );

    private final TradingDemoSimulationProperties simulationProperties;
    private final TradingAccountAdminQueryService tradingAccountAdminQueryService;
    private final TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy;
    private final TradingLoadGeneratorManager tradingLoadGeneratorManager;
    private final Optional<MeterRegistry> meterRegistry;

    public TradingDashboardQueryService(TradingDemoSimulationProperties simulationProperties,
                                        TradingAccountAdminQueryService tradingAccountAdminQueryService,
                                        TradingAccountClosingBooksPolicy tradingAccountClosingBooksPolicy,
                                        TradingLoadGeneratorManager tradingLoadGeneratorManager,
                                        Optional<MeterRegistry> meterRegistry) {
        this.simulationProperties = simulationProperties;
        this.tradingAccountAdminQueryService = tradingAccountAdminQueryService;
        this.tradingAccountClosingBooksPolicy = tradingAccountClosingBooksPolicy;
        this.tradingLoadGeneratorManager = tradingLoadGeneratorManager;
        this.meterRegistry = meterRegistry;
    }

    public DashboardSummaryView getSummary() {
        var accountSummaries = new ArrayList<DashboardAccountSummaryView>();
        for (int index = 0; index < simulationProperties.getAccountCount(); index++) {
            var accountId = TradingAccountId.of("ACC-DEMO-%03d".formatted(index + 1));
            try {
                var view      = tradingAccountAdminQueryService.getAccountView(accountId);
                var snapshots = tradingAccountAdminQueryService.getCurrentGenerationSnapshots(accountId);
                accountSummaries.add(new DashboardAccountSummaryView(view.logicalAccountId(),
                                                                     view.generations().size(),
                                                                     view.currentGeneration(),
                                                                     view.currentStatementPeriod(),
                                                                     view.cashBalance(),
                                                                     view.realizedPnl(),
                                                                     view.booksClosed(),
                                                                     snapshots.size(),
                                                                     snapshots.stream()
                                                                              .map(ApiAggregateSnapshot::lastIncludedEventOrder)
                                                                              .max(Long::compareTo)
                                                                              .orElse(null)));
            } catch (Exception ignored) {
            }
        }

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
                                                                   tradingAccountClosingBooksPolicy.mode().name().toLowerCase().replace('_', '-'),
                                                                   tradingAccountClosingBooksPolicy.eventThreshold(),
                                                                   tradingAccountClosingBooksPolicy.timeBoundary().name().toLowerCase().replace('_', '-'),
                                                                   tradingAccountClosingBooksPolicy.zoneId(),
                                                                   tradingAccountClosingBooksPolicy.intervalDays());
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
                                        List.of("TRD-001-001", "TRD-LIVE-001001"),
                                        List.of("TRD-001-001-SET", "TRD-LIVE-001001-SET"));
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
        return meterRegistry.map(registry -> SNAPSHOT_METRIC_NAMES.stream()
                                                                  .map(metricName -> toMetricSummary(registry, metricName))
                                                                  .toList())
                            .orElseGet(List::of);
    }

    private DashboardMetricSummaryView toMetricSummary(MeterRegistry registry, String metricName) {
        var timers = registry.find(metricName)
                             .tag("aggregate_type", SNAPSHOT_AGGREGATE_TYPE)
                             .tag("aggregate_impl_type", SNAPSHOT_AGGREGATE_IMPL_TYPE)
                             .timers();
        if (timers.isEmpty()) {
            return new DashboardMetricSummaryView(metricName, 0, 0, 0);
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
        return new DashboardMetricSummaryView(metricName,
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
        return new DashboardSnapshotStatsView(SNAPSHOT_AGGREGATE_TYPE,
                                              loadCount,
                                              saveCount,
                                              serializeCount,
                                              deserializeCount,
                                              totalObservedTimeMs);
    }

    private long countFor(List<DashboardMetricSummaryView> metricSummaries, String name) {
        return metricSummaries.stream()
                              .filter(metric -> metric.name().equals(name))
                              .findFirst()
                              .map(DashboardMetricSummaryView::count)
                              .orElse(0L);
    }
}
