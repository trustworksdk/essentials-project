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
 * A new market price has been observed for an instrument. This is the high-frequency event in the demo -- the tick
 * that the load generator emits and that the snapshot policy on {@code InstrumentPrice} exists for.
 *
 * <p>A tick that repeats the price already held emits nothing; {@code InstrumentPrice.updatePrice} returns without
 * applying, so an unchanged price never lengthens the stream.
 */
public record PriceUpdated(InstrumentId instrumentId,
                           Amount price) implements InstrumentPriceEvent {
    public PriceUpdated {
        requireNonNull(instrumentId, "No instrumentId provided");
        price = InstrumentPriceEvent.requirePositive(price);
    }
}
