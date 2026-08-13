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

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;

import java.util.List;

/**
 * The fixed instrument seed set the bootstrap writes and the load generator then trades against.
 * <p>
 * Both sides derive their ids from this one list, so the load generator can never pick an instrument the bootstrap
 * did not seed.
 */
final class TradingSimulationIds {
    static final List<InstrumentSeed> INSTRUMENT_SEEDS = List.of(
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

    private TradingSimulationIds() {
    }

    static List<InstrumentSeed> seeds(int instrumentCount) {
        var safeCount = Math.max(1, Math.min(instrumentCount, INSTRUMENT_SEEDS.size()));
        return INSTRUMENT_SEEDS.subList(0, safeCount);
    }

    static List<InstrumentId> instrumentIds(int instrumentCount) {
        return seeds(instrumentCount).stream()
                                     .map(InstrumentSeed::instrumentId)
                                     .toList();
    }

    /**
     * One seeded instrument. The {@code symbol} doubles as the instrument id -- the demo deliberately keeps the two
     * the same so a reader of the event store can tell which instrument a stream belongs to at a glance.
     */
    record InstrumentSeed(String symbol, String displayName) {
        InstrumentId instrumentId() {
            return InstrumentId.of(symbol);
        }
    }
}
