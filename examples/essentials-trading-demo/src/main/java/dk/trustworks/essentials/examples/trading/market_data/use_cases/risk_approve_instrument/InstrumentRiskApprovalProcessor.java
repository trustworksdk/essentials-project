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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessor;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.UnitOfWorkMode;
import dk.trustworks.essentials.components.foundation.transaction.UnitOfWork;
import dk.trustworks.essentials.examples.trading.market_data.aggregates.Instruments;
import dk.trustworks.essentials.examples.trading.market_data.events.InstrumentRegistered;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The {@code market_data.risk_approve_instrument} automation slice: every newly registered instrument is sent through an
 * external risk assessment, and the answer is recorded on the instrument.
 *
 * <p>This is the demo's example of {@link UnitOfWorkMode#NONE}, and the reason the slice exists at all.
 *
 * <h2>Why the handler declares {@code UnitOfWorkMode.NONE}</h2>
 * A {@link MessageHandler} annotated method runs inside a {@link UnitOfWork} by default, and a {@link UnitOfWork} is
 * what checks out a pooled database connection and opens a transaction. A handler that blocks on an external system
 * therefore parks that connection in {@code idle in transaction} for the whole call -- here, for the risk service's
 * entire response time, times however many instruments are being assessed in parallel. With a handful of consumer
 * threads and a service that answers in a second, that is enough to starve every other writer in the application of
 * connections while nothing is being written.
 *
 * <p>Declaring the handler {@link UnitOfWorkMode#NONE} moves the transaction boundary inside the method: the blocking
 * call runs with no {@link UnitOfWork} and no connection at all, and the write that follows it is wrapped explicitly in
 * {@code usingUnitOfWork(...)}. The transaction now lives as long as the two aggregate operations take, not as long as
 * the risk service does.
 *
 * <h2>What that costs, and how this slice pays it</h2>
 * <ul>
 *   <li><b>The blocking call is no longer part of the transaction that acknowledges the message.</b> The call can
 *       succeed and the transactional tail still fail, after which {@code InstrumentRegistered} is redelivered and the
 *       risk service is called again. Recording the decision is therefore idempotent on the aggregate:
 *       {@code Instrument.recordRiskApproval} / {@code recordRiskRejection} apply nothing once a decision exists, so a
 *       second attempt leaves the stream as the first one left it. The stub also answers deterministically per symbol,
 *       so the repeat call reaches the same decision.</li>
 *   <li><b>The call must finish well inside the DurableQueues message-handling timeout</b> (30s by default). Past it the
 *       message is treated as stuck and can be redelivered while this attempt is still blocked. The stub's latency is
 *       {@code trading-demo.risk-approval.latency}, 500ms by default, and the property's javadoc says so.</li>
 * </ul>
 *
 * <h2>Slice shape</h2>
 * An automation has no external API: nothing calls it, it reacts. It writes one aggregate per handler and issues no
 * command, the same shape as {@code postgresql-cqrs}' {@code banking.transfer_money}. The instrument's risk state is
 * observable through {@code views/instrument_details}.
 */
@Service
public class InstrumentRiskApprovalProcessor extends EventProcessor {
    private static final Logger log = LoggerFactory.getLogger(InstrumentRiskApprovalProcessor.class);

    private final Instruments           instruments;
    private final RiskAssessmentGateway riskAssessmentGateway;

    public InstrumentRiskApprovalProcessor(EventProcessorDependencies eventProcessorDependencies,
                                           Instruments instruments,
                                           RiskAssessmentGateway riskAssessmentGateway) {
        super(eventProcessorDependencies);
        this.instruments = requireNonNull(instruments, "No instruments provided");
        this.riskAssessmentGateway = requireNonNull(riskAssessmentGateway, "No riskAssessmentGateway provided");
    }

    @Override
    public String getProcessorName() {
        return "InstrumentRiskApprovalProcessor";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Instruments.AGGREGATE_TYPE);
    }

    /**
     * The blocking half runs with no {@link UnitOfWork}; the transactional tail is wrapped explicitly.
     * <p>
     * Touching a transactional resource between the two -- loading the instrument before the call, say -- would fail
     * fast rather than silently open a transaction, because with {@link UnitOfWorkMode#NONE} there is no ambient one to
     * join.
     */
    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(InstrumentRegistered e) {
        var assessment = riskAssessmentGateway.assess(e.instrumentId(), e.symbol());

        usingUnitOfWork(() -> recordDecision(e.instrumentId(), assessment));
    }

    private void recordDecision(InstrumentId instrumentId,
                                RiskAssessment assessment) {
        var instrument = instruments.getInstrument(instrumentId);
        switch (assessment.decision()) {
            case APPROVED -> {
                log.debug("===> Instrument '{}' risk approved with rating '{}'", instrumentId, assessment.riskRating());
                instrument.recordRiskApproval(assessment.riskRating());
            }
            case REJECTED -> {
                log.debug("===> Instrument '{}' risk rejected: {}", instrumentId, assessment.rejectionReason());
                instrument.recordRiskRejection(assessment.rejectionReason());
            }
        }
    }
}
