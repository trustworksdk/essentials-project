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

package dk.trustworks.essentials.examples.trading.market_data.use_cases.update_price;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Record a new market price for an instrument -- one tick.
 *
 * <p>The highest-frequency command in the demo. Every component is mandatory, which is why
 * {@code UpdatePriceAPI} takes the id as a {@code @PathVariable} and the price as a {@code @RequestParam} and builds
 * this record itself, rather than deserializing a partial body whose {@code instrumentId} would be null.
 *
 * <p>A tick that repeats the price already held emits nothing; that guard lives on {@code InstrumentPrice}, so it
 * covers a redelivered command too. That the price is greater than zero is checked on the event by
 * {@code InstrumentPriceEvent.requirePositive}.
 */
public record UpdatePrice(InstrumentId instrumentId,
                          Amount price) {
    public UpdatePrice {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(price, "No price provided");
    }
}
