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
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;

import java.util.List;

/**
 * Read-model for the runtime load generator.
 */
public record TradingLoadGeneratorStatusView(boolean enabled,
                                             boolean started,
                                             long generatedTradeCount,
                                             long generatedSettlementCount,
                                             long generatedPriceUpdateCount,
                                             int pendingSettlementCount,
                                             TradeId latestTradeId,
                                             SettlementId latestSettlementId,
                                             InstrumentId latestPriceInstrumentId,
                                             String currentPriceStressMode,
                                             boolean priceStressRunning,
                                             long priceStressRequestedCount,
                                             long priceStressCompletedCount,
                                             long priceStressIntervalMillis,
                                             List<InstrumentPriceTickerView> latestPrices) {
}
