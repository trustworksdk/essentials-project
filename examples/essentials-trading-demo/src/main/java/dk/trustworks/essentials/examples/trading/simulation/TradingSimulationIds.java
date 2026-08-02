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

import java.util.List;

final class TradingSimulationIds {
    static final List<String> INSTRUMENT_SEED_SYMBOLS = List.of(
            "AAPL",
            "MSFT",
            "NVDA",
            "AMZN",
            "GOOGL",
            "META",
            "TSLA",
            "JPM",
            "SAP",
            "NOVO-B"
    );

    private TradingSimulationIds() {
    }

    static List<String> instrumentIds(int instrumentCount) {
        var safeCount = Math.max(1, Math.min(instrumentCount, INSTRUMENT_SEED_SYMBOLS.size()));
        return INSTRUMENT_SEED_SYMBOLS.subList(0, safeCount);
    }
}
