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

package dk.trustworks.essentials.examples.trading.market_data.views.market_quotes;

import java.util.function.Consumer;

/**
 * The exchange's market-data stream, as this view consumes it. Live and not replayable (SR-13): a consumer that missed
 * messages resynchronises from the snapshot, it never replays. Every instance subscribes on its own, as every
 * instance of a broker's gateway would; there is nothing to share and nothing to persist.
 * <p>
 * {@link SyntheticMarketQuoteFeed} stands in until the exchange-simulator's stream exists.
 */
public interface MarketQuoteFeed {
    void subscribe(Consumer<MarketQuote> listener);
}
