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
import dk.trustworks.essentials.examples.trading.market_data.types.RiskRating;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * An external risk assessment cleared the instrument for trading, with the rating it awarded.
 *
 * <p>Written by the {@code market_data.risk_approve_instrument} automation after its blocking call to the risk service
 * returned. Because that call happens outside any transaction, the event is applied by a separate transaction of its
 * own -- and only if the instrument has no risk decision yet, so a redelivered {@code InstrumentRegistered} cannot
 * record a second decision.
 */
public record InstrumentRiskApproved(InstrumentId instrumentId,
                                     RiskRating riskRating) implements InstrumentEvent {
    public InstrumentRiskApproved {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(riskRating, "No riskRating provided");
    }
}
