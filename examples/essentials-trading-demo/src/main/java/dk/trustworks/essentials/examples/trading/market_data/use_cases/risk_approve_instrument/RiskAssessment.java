/*
 *  Copyright 2021-2025 the original author or authors.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package dk.trustworks.essentials.examples.trading.market_data.use_cases.risk_approve_instrument;

import dk.trustworks.essentials.examples.trading.market_data.types.RiskRating;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * What the external risk service answered: a decision, plus the rating that comes with an approval or the reason that
 * comes with a rejection.
 *
 * <p>Build it through {@link #approved(RiskRating)} or {@link #rejected(String)}. The canonical constructor requires
 * whichever field the decision implies, so a rejection without a reason cannot exist -- the automation writes that
 * reason into an event and there is nothing sensible to put there instead.
 */
public record RiskAssessment(RiskDecision decision,
                             RiskRating riskRating,
                             String rejectionReason) {
    public RiskAssessment {
        requireNonNull(decision, "No decision provided");
        if (decision == RiskDecision.APPROVED) {
            requireNonNull(riskRating, "No riskRating provided for an APPROVED assessment");
        } else {
            requireNonNull(rejectionReason, "No rejectionReason provided for a REJECTED assessment");
        }
    }

    public static RiskAssessment approved(RiskRating riskRating) {
        return new RiskAssessment(RiskDecision.APPROVED, riskRating, null);
    }

    public static RiskAssessment rejected(String rejectionReason) {
        return new RiskAssessment(RiskDecision.REJECTED, null, rejectionReason);
    }
}
