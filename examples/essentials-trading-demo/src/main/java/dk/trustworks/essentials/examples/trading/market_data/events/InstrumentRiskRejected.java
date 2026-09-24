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
 * An external risk assessment refused to clear the instrument for trading, with the reason it gave.
 *
 * <p>The counterpart of {@link InstrumentRiskApproved}, written by the same automation and under the same rule: a risk
 * decision is recorded once per instrument.
 *
 * <p>A rejection is not a suspension. {@code InstrumentSuspended} records a deliberate decision to stop trading an
 * instrument that was cleared; this records that it was never cleared in the first place. Keeping them apart is what
 * lets the read model tell "rejected by risk" from "suspended by an operator".
 */
public record InstrumentRiskRejected(InstrumentId instrumentId,
                                     String reason) implements InstrumentEvent {
    public InstrumentRiskRejected {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(reason, "No reason provided");
    }
}
