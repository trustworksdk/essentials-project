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

package dk.trustworks.essentials.examples.trading.market_data.use_cases.initialize_price;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Amount;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Open the price stream for an instrument at its first known market price.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of
 * {@code POST /api/admin/instrument-prices} -- there is no separate DTO to keep in step.
 *
 * <p>The {@code instrumentId} is the aggregate id of the price stream as well as of the instrument itself: a price
 * stream <em>is</em> the instrument's identity under a second {@code AggregateType}, so there is no price id to supply.
 *
 * <p>This record checks only that the price is present. That it is greater than zero is checked by
 * {@code InstrumentPriceEvent.requirePositive} on the event, so the rule is enforced on replay as well as on command.
 */
public record InitializePrice(InstrumentId instrumentId,
                              Amount price) {
    public InitializePrice {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(price, "No price provided");
    }
}
