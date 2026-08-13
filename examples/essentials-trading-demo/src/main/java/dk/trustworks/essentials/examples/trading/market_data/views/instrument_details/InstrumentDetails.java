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

package dk.trustworks.essentials.examples.trading.market_data.views.instrument_details;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.Symbol;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The read shape this slice serves — one instrument's reference data and its suspension state.
 * <p>
 * Returned straight from the API; there is no DTO between this and the wire (§R2).
 */
public record InstrumentDetails(InstrumentId instrumentId,
                                Symbol symbol,
                                String displayName,
                                boolean suspended,
                                String suspensionReason) {
    public InstrumentDetails {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(symbol, "No symbol provided");
        requireNonNull(displayName, "No displayName provided");
    }
}
