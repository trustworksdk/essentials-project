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

import dk.trustworks.essentials.examples.trading.brokerage.types.SettlementId;
import dk.trustworks.essentials.examples.trading.brokerage.types.TradeId;

import java.util.List;

/**
 * Summary payload for the lightweight demo dashboard.
 */
public record DashboardSummaryView(int configuredAccountCount,
                                   int accountsPresent,
                                   List<DashboardAccountSummaryView> accounts,
                                   DashboardClosingBooksStatsView closingBooks,
                                   TradingLoadGeneratorStatusView loadGenerator,
                                   PricePathComparisonView pricePathComparison,
                                   PricePathScenarioResultView latestPricePathScenario,
                                   TradingAccountScenarioResultView latestTradingAccountScenario,
                                   DashboardSnapshotStatsView snapshotStats,
                                   List<DashboardMetricSummaryView> snapshotMetrics,
                                   List<TradeId> sampleTradeIds,
                                   List<SettlementId> sampleSettlementIds) {
}
