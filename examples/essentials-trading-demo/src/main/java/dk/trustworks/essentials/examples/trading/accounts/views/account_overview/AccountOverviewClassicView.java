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
package dk.trustworks.essentials.examples.trading.accounts.views.account_overview;

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
 * Feeds {@link AccountOverviewProjection} on the classic engine: a {@link PartitionedViewEventProcessor} that hands each event, with
 * its {@code EventOrder}, to the projection on the handle of the {@code UnitOfWork} it handles the event in. Every
 * instance consumes the view's queue; the processor name stays the one the view always had.
 */
// Deliberately not handled: the startup summary warns about every sealed-hierarchy leaf a view neither handles nor lists here
@IgnoresEventTypes({UnknownReferenceIgnored.class})
@ClassicEngine
@Service
public class AccountOverviewClassicView extends TradingDemoPartitionedViewEventProcessor {
    private final AccountOverviewProjection projection;

    public AccountOverviewClassicView(ViewEventProcessorDependencies dependencies, AccountOverviewProjection projection) {
        super(dependencies);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    @Override
    public String getProcessorName() {
        return "AccountOverviewProjection";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(AccountsAggregateTypes.ACCOUNTS);
    }

    @MessageHandler
    void handle(AccountOpened e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(CashAdjusted e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(DepositCredited e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(DepositCleared e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(DepositReturned e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(FundsHeld e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(FundsReleased e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(CashWithdrawn e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(SettlementAmountCorrected e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(WithdrawalReturnCredited e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(TradeBooked e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(TradeSettled e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(TradeBookingReversed e, OrderedMessage m) {
        projection.on(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(AccountBooksClosed e, OrderedMessage m) {
        projection.advance(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(SharesReserved e, OrderedMessage m) {
        projection.advance(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(SharesReleased e, OrderedMessage m) {
        projection.advance(getRequiredUnitOfWork().handle(), e, m.getOrder());
    }

    @Override
    protected void onSubscriptionsReset(AggregateType aggregateType, GlobalEventOrder resubscribeFromAndIncluding) {
        projection.truncate();
    }
}
