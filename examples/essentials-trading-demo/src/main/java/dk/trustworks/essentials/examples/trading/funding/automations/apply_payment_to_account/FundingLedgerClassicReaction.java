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
package dk.trustworks.essentials.examples.trading.funding.automations.apply_payment_to_account;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.examples.trading.funding.events.*;
import dk.trustworks.essentials.examples.trading.config.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link FundingLedgerProcessor} on the classic engine: an {@code EventProcessor} that forwards the events into its inbox and
 * hands each one to {@link FundingLedgerProcessor}, each handler in its own unit of work as declared. Same processor name, inbox and
 * handlers as {@link FundingLedgerEventLogReaction}.
 */
@ClassicEngine
@Service
public class FundingLedgerClassicReaction extends TradingDemoEventProcessor {
    private final FundingLedgerProcessor ledger;

    public FundingLedgerClassicReaction(EventProcessorDependencies dependencies, FundingLedgerProcessor ledger) {
        super(dependencies);
        this.ledger = requireNonNull(ledger, "No ledger provided");
    }

    @Override
    public String getProcessorName() {
        return FundingLedgerProcessor.PROCESSOR_NAME;
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return FundingLedgerProcessor.AGGREGATE_TYPES;
    }

    @MessageHandler
    void on(DepositCompleted e) {
        ledger.on(e);
    }

    @MessageHandler
    void on(DepositReturnedByBank e) {
        ledger.on(e);
    }

    @MessageHandler
    void on(WithdrawalCompleted e) {
        ledger.on(e);
    }

    @MessageHandler
    void on(WithdrawalFailed e) {
        ledger.on(e);
    }

    @MessageHandler
    void on(WithdrawalReturnedByBank e) {
        ledger.on(e);
    }
}
