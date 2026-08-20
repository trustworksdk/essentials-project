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

package dk.trustworks.essentials.examples.trading.market_data.types;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;

/**
 * The stream names this context publishes under.
 *
 * <p>These sit in {@code types/} rather than on the repository wrappers because an {@link AggregateType} is part of
 * this context's <b>public</b> contract: a foreign context that subscribes to {@code market_data}'s events has to name
 * the stream it is subscribing to, and §R4 makes {@code events/} and {@code types/} the only packages it may import.
 * {@code brokerage.trade_valuation} projects {@link dk.trustworks.essentials.examples.trading.market_data.events.PriceUpdated}
 * into its own read model and needs {@link #INSTRUMENT_PRICES} to do it; reaching into {@code aggregates/} for the same
 * constant would have made a BC-private package part of that slice's compile surface.
 *
 * <p>The wrappers re-expose these as their own {@code AGGREGATE_TYPE} so that the aggregate and its stream name still
 * read together at the point of use.
 */
public final class MarketDataAggregateTypes {
    public static final AggregateType INSTRUMENTS       = AggregateType.of("Instruments");
    public static final AggregateType INSTRUMENT_PRICES = AggregateType.of("InstrumentPrices");

    private MarketDataAggregateTypes() {
    }
}
