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

package dk.trustworks.essentials.examples.trading.funding.views.account_owners;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountsAggregateTypes;
import dk.trustworks.essentials.examples.trading.config.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link FundingAccountOwnersProjection} on the classic engine: a {@link ViewEventProcessor} that hands each event
 * to the projection with the handle of the {@code UnitOfWork} it handles the event in, so the row commits together with
 * the processor's progress. Keeps the processor name the view always had.
 */
// Deliberately not handled: the startup summary warns about every sealed-hierarchy leaf a view neither handles nor lists here
@IgnoresEventTypes({AccountBooksClosed.class,
                    CashAdjusted.class,
                    CashWithdrawn.class,
                    DepositCleared.class,
                    DepositCredited.class,
                    DepositReturned.class,
                    FundsHeld.class,
                    FundsReleased.class,
                    SettlementAmountCorrected.class,
                    SharesReleased.class,
                    SharesReserved.class,
                    TradeBooked.class,
                    TradeBookingReversed.class,
                    TradeSettled.class,
                    UnknownReferenceIgnored.class,
                    WithdrawalReturnCredited.class})
@ClassicEngine
@Service
public class FundingAccountOwnersClassicView extends TradingDemoViewEventProcessor {
    private final FundingAccountOwnersProjection projection;

    public FundingAccountOwnersClassicView(ViewEventProcessorDependencies dependencies, FundingAccountOwnersProjection projection) {
        super(dependencies);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    @Override
    public String getProcessorName() {
        return "FundingAccountOwnersProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AccountsAggregateTypes.ACCOUNTS);
    }

    @MessageHandler
    void handle(AccountOpened event) {
        projection.on(getRequiredUnitOfWork().handle(), event);
    }
}
