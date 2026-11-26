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

package dk.trustworks.essentials.examples.trading.accounts.views.positions;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.types.GlobalEventOrder;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.OrderedMessage;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountsAggregateTypes;
import dk.trustworks.essentials.examples.trading.config.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link PositionsProjection} on the classic engine: a {@link ViewEventProcessor} that hands each event, with its
 * {@code EventOrder} ({@link OrderedMessage#getOrder()}), to the projection with the handle of the {@code UnitOfWork}
 * it handles the event in, so the rows commit together with the processor's progress. Keeps the processor name the
 * view always had.
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
                    UnknownReferenceIgnored.class,
                    WithdrawalReturnCredited.class})
@ClassicEngine
@Service
public class PositionsClassicView extends TradingDemoViewEventProcessor {
    private final PositionsProjection projection;

    public PositionsClassicView(ViewEventProcessorDependencies dependencies, PositionsProjection projection) {
        super(dependencies);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    @Override
    public String getProcessorName() {
        return "PositionsProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AccountsAggregateTypes.ACCOUNTS);
    }

    @MessageHandler
    void handle(AccountOpened event, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), event, message.getOrder());
    }

    @MessageHandler
    void handle(SharesReserved event, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), event, message.getOrder());
    }

    @MessageHandler
    void handle(SharesReleased event, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), event, message.getOrder());
    }

    @MessageHandler
    void handle(TradeBooked event, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), event, message.getOrder());
    }

    @MessageHandler
    void handle(TradeSettled event, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), event, message.getOrder());
    }

    @MessageHandler
    void handle(SettlementAmountCorrected event, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), event, message.getOrder());
    }

    @MessageHandler
    void handle(TradeBookingReversed event, OrderedMessage message) {
        projection.on(getRequiredUnitOfWork().handle(), event, message.getOrder());
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
        projection.truncate();
    }
}
