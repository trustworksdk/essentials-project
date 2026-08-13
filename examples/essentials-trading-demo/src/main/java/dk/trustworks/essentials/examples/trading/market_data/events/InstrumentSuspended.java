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

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Trading in an instrument has been suspended, with the reason it was suspended for.
 *
 * <p>Suspension is one-way in this model: an already-suspended instrument emits nothing when suspended again, and
 * there is no un-suspend event. The first reason therefore stands for the life of the stream.
 */
public record InstrumentSuspended(InstrumentId instrumentId,
                                  String reason) implements InstrumentEvent {
    public InstrumentSuspended {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(reason, "No reason provided");
    }
}
