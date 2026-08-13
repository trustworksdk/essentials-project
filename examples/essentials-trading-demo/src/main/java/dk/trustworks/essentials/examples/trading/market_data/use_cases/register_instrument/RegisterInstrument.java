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

package dk.trustworks.essentials.examples.trading.market_data.use_cases.register_instrument;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.Symbol;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Register a new instrument's reference data under the given ticker symbol.
 *
 * <p>Both the command dispatched on the {@code CommandBus} and the request body of
 * {@code POST /api/admin/instruments} -- there is no separate DTO to keep in step.
 *
 * <p>The caller supplies the {@link InstrumentId}, which is what makes registration retryable from the client's side:
 * the id is not minted server-side, so a retried call addresses the same instrument rather than creating a second one.
 */
public record RegisterInstrument(InstrumentId instrumentId,
                                 Symbol symbol,
                                 String displayName) {
    public RegisterInstrument {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(symbol, "No symbol provided");
        requireNonNull(displayName, "No displayName provided");
    }
}
