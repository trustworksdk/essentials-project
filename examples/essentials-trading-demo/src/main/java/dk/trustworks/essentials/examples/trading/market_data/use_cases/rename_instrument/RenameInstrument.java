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

package dk.trustworks.essentials.examples.trading.market_data.use_cases.rename_instrument;

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Change the display name of an already-registered instrument. The ticker symbol is not renameable.
 *
 * <p>Every component is mandatory, which is why {@code RenameInstrumentAPI} takes the id as a {@code @PathVariable}
 * and the new name as a {@code @RequestParam} and builds this record itself, rather than deserializing a partial body
 * whose {@code instrumentId} would be null and would trip the guard below before the controller ever ran.
 *
 * <p>Renaming to the name already held is a no-op rather than an error; that guard lives on the {@code Instrument}
 * aggregate.
 */
public record RenameInstrument(InstrumentId instrumentId,
                               String displayName) {
    public RenameInstrument {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(displayName, "No displayName provided");
    }
}
