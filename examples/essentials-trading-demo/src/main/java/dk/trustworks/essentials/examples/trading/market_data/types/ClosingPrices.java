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

import dk.trustworks.essentials.types.Money;

import java.time.LocalDate;
import java.util.Optional;

/**
 * {@code market_data}'s published read seam for the end-of-day market: the closing prices the exchange issues at each
 * business day's cutoff (SR-14), durable and replayable, unlike the live quotes ({@link MarketQuotes}). Implemented by
 * {@code views/closing_prices}. Empty without the exchange-simulator.
 */
public interface ClosingPrices {
    /**
     * @param businessDate the business day the price closed
     * @param basis        {@code LAST_TRADE} or {@code REFERENCE}
     */
    record ClosingPrice(InstrumentId instrumentId, LocalDate businessDate, Money price, String basis) {
    }

    /**
     * The latest closing price of an instrument, on or before {@code businessDate}.
     */
    Optional<ClosingPrice> latestOnOrBefore(InstrumentId instrumentId, LocalDate businessDate);

    /**
     * The last business day any closing price was issued for.
     */
    Optional<LocalDate> latestBusinessDate();
}
