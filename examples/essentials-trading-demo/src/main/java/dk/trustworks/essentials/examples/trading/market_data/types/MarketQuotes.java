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

import java.util.Optional;

/**
 * {@code market_data}'s published read seam for the current market: what a marketable order would trade at now.
 * <p>
 * Answers only from a fresh quote. A quote the feed has not refreshed within its staleness window answers empty,
 * like an instrument the feed has never quoted, so a caller can never size a hold on a price that is no longer there.
 * Implemented by {@code views/market_quotes}.
 */
public interface MarketQuotes {
    /**
     * The best price a buyer can take now.
     */
    Optional<Money> bestAsk(InstrumentId instrumentId);

    /**
     * The best price a seller can take now.
     */
    Optional<Money> bestBid(InstrumentId instrumentId);
}
