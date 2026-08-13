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

package dk.trustworks.essentials.examples.trading.market_data.events;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The set of events an {@code InstrumentPrice} can emit is closed, so the interface is {@code sealed}: adding a variant
 * means updating the {@code permits} clause, which is a compile error away rather than a silent omission. Sealing does
 * not restrict the EventStore, which deserializes the concrete records reflectively by their fully qualified class
 * name.
 *
 * <p>The aggregate id is an {@link InstrumentId}, not a dedicated price id -- a price stream is the same identity as
 * the instrument it prices, in a different {@code AggregateType}.
 */
public sealed interface InstrumentPriceEvent permits PriceInitialized, PriceUpdated {

    InstrumentId instrumentId();

    /**
     * A price of zero or less is not a price. Both variants validate through this one method so the rule cannot drift
     * between them, and so it is enforced on <em>deserialization</em> too -- a stream that somehow contains a
     * non-positive price fails loudly on replay rather than projecting a nonsense valuation.
     */
    static Amount requirePositive(Amount price) {
        requireNonNull(price, "No price provided");
        if (price.signum() <= 0) {
            throw new IllegalArgumentException("price must be > 0");
        }
        return price;
    }
}
