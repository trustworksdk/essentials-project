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

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.RiskRating;
import dk.trustworks.essentials.examples.trading.market_data.types.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The slice's door to the external risk service, and a stub: it stands in for a blocking HTTP call by sleeping for the
 * configured latency and then deciding from the symbol.
 *
 * <p>A {@code Thread.sleep} is a fair stand-in for what this slice exists to demonstrate. What matters to the
 * {@link InstrumentRiskApprovalProcessor} is that the call occupies its thread for a stretch of wall-clock time it does
 * not control, and that it is not a database operation -- an HTTP round trip through a real client would behave
 * identically from the handler's point of view, and would only add a dependency and a port to the demo. Swapping this
 * implementation for one is a change to this class alone.
 *
 * <p>Ratings are derived from the symbol rather than drawn at random, so a redelivered assessment reaches the same
 * answer as the first attempt.
 */
@Component
public class RiskAssessmentGateway {
    private static final Logger       log     = LoggerFactory.getLogger(RiskAssessmentGateway.class);
    private static final RiskRating[] RATINGS = {RiskRating.of("AAA"),
                                                 RiskRating.of("AA"),
                                                 RiskRating.of("A"),
                                                 RiskRating.of("BBB")};

    private final RiskApprovalProperties properties;

    public RiskAssessmentGateway(RiskApprovalProperties properties) {
        this.properties = requireNonNull(properties, "No properties provided");
    }

    /**
     * Blocking call to the risk service. Returns only after the configured latency has elapsed.
     *
     * @param instrumentId the instrument being assessed
     * @param symbol       the symbol it trades under, which is what the stub decides from
     * @return the service's answer, never null
     */
    public RiskAssessment assess(InstrumentId instrumentId,
                                 Symbol symbol) {
        requireNonNull(instrumentId, "No instrumentId provided");
        requireNonNull(symbol, "No symbol provided");

        log.debug("===> Calling risk service for Instrument '{}' ({}), blocking for {}", instrumentId, symbol, properties.getLatency());
        sleepFor();

        var assessment = decideFor(symbol);
        log.debug("===> Risk service answered {} for Instrument '{}' ({})", assessment.decision(), instrumentId, symbol);
        return assessment;
    }

    private void sleepFor() {
        try {
            Thread.sleep(properties.getLatency());
        } catch (InterruptedException interrupted) {
            // Restoring the flag is what lets the queue consumer's thread pool shut down while a call is in flight
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the risk service", interrupted);
        }
    }

    private RiskAssessment decideFor(Symbol symbol) {
        var isRejected = properties.getRejectedSymbols()
                                   .stream()
                                   .anyMatch(rejected -> rejected.equalsIgnoreCase(symbol.toString()));
        if (isRejected) {
            return RiskAssessment.rejected("Risk service refused to clear symbol " + symbol);
        }
        return RiskAssessment.approved(RATINGS[Math.abs(symbol.toString().hashCode() % RATINGS.length)]);
    }
}
