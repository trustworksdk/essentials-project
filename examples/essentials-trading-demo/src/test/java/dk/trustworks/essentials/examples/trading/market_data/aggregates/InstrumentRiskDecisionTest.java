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

package dk.trustworks.essentials.examples.trading.market_data.aggregates;

import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRegistered;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRiskApproved;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRiskRejected;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.market_data.types.RiskRating;
import dk.trustworks.essentials.examples.trading.market_data.types.Symbol;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the guard the {@code market_data.risk_approve_instrument} automation depends on.
 *
 * <p>That automation calls the risk service outside any transaction, so the call can succeed while the transaction
 * recording its outcome fails — after which the triggering {@code InstrumentRegistered} is redelivered and the risk
 * service is called again. What keeps that harmless is the aggregate refusing to record a second decision, which is
 * what these tests pin. No database is involved: the applied events are read straight off the uncommitted changes.
 */
class InstrumentRiskDecisionTest {

    @Test
    void a_risk_approval_is_recorded_once_no_matter_how_often_it_is_replayed() {
        var instrument = registeredInstrument();

        instrument.recordRiskApproval(RiskRating.of("A"));
        instrument.recordRiskApproval(RiskRating.of("BBB"));
        instrument.recordRiskApproval(RiskRating.of("AAA"));

        assertThat(instrument.getUncommittedChanges().events)
                .describedAs("Only the first approval may reach the stream, so a redelivered assessment adds nothing")
                .hasExactlyElementsOfTypes(InstrumentRegistered.class,
                                            InstrumentRiskApproved.class);
        assertThat(instrument.getUncommittedChanges().events)
                .filteredOn(InstrumentRiskApproved.class::isInstance)
                .extracting(event -> ((InstrumentRiskApproved) event).riskRating())
                .containsExactly(RiskRating.of("A"));
    }

    @Test
    void a_rejection_cannot_overwrite_an_approval_and_the_other_way_round() {
        var approvedInstrument = registeredInstrument();
        approvedInstrument.recordRiskApproval(RiskRating.of("A"));
        approvedInstrument.recordRiskRejection("Second thoughts");

        assertThat(approvedInstrument.getUncommittedChanges().events)
                .hasExactlyElementsOfTypes(InstrumentRegistered.class,
                                            InstrumentRiskApproved.class);

        var rejectedInstrument = registeredInstrument();
        rejectedInstrument.recordRiskRejection("Unrated issuer");
        rejectedInstrument.recordRiskApproval(RiskRating.of("A"));

        assertThat(rejectedInstrument.getUncommittedChanges().events)
                .hasExactlyElementsOfTypes(InstrumentRegistered.class,
                                            InstrumentRiskRejected.class);
    }

    private static Instrument registeredInstrument() {
        return new Instrument(InstrumentId.of("INST-RISK-UNIT-1"),
                              Symbol.of("ABC"),
                              "Alpha Bravo Corp");
    }
}
