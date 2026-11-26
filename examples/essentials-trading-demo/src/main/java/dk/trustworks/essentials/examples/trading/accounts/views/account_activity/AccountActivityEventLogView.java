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
package dk.trustworks.essentials.examples.trading.accounts.views.account_activity;

import dk.trustworks.essentials.components.eventlog.processor.*;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import dk.trustworks.essentials.components.foundation.messaging.queue.*;
import dk.trustworks.essentials.examples.trading.accounts.events.*;
import dk.trustworks.essentials.examples.trading.accounts.types.AccountsAggregateTypes;
import dk.trustworks.essentials.examples.trading.config.EventLogEngine;
import org.jdbi.v3.core.Handle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link AccountActivityProjection} on the event-log engine: an {@link EventLogPartitionedViewProcessor} that queues each account
 * event on {@code AccountActivityProjection:queue} keyed by account (in the page transaction that moves its progress) and hands it,
 * with its {@code EventOrder}, to the projection on the handle of the unit of work the queued message is handled in, with
 * {@code trading-demo.views.partitioned-consumers} consumer threads per instance. A rebuild truncates the table, as the
 * classic twin's reset does. Same processor name as {@link AccountActivityClassicView}.
 */
@EventLogEngine
@Service
public class AccountActivityEventLogView extends EventLogPartitionedViewProcessor {
    private final AccountActivityProjection projection;
    private       int consumers = 1;

    public AccountActivityEventLogView(EventLogProcessorDependencies dependencies,
                                       DurableQueues durableQueues,
                                       EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                       AccountActivityProjection projection) {
        super("AccountActivityProjection", List.of(AccountsAggregateTypes.ACCOUNTS.toString()), dependencies, durableQueues, unitOfWorkFactory);
        this.projection = requireNonNull(projection, "No projection provided");
    }

    @Value("${trading-demo.views.partitioned-consumers:1}")
    void setConsumers(int consumers) {
        this.consumers = consumers;
    }

    @Override
    protected int numberOfParallelQueuedMessageConsumers() {
        return consumers;
    }

    @Override
    protected void onRebuild(ProjectionContext context) {
        projection.truncate(messageHandle());
    }

    /** The handle of the unit of work the current message is handled in, or of the rebuild transaction */
    private Handle messageHandle() {
        return ((EventStoreUnitOfWork) messageUnitOfWork()).handle();
    }

    @MessageHandler
    void handle(AccountOpened e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(CashAdjusted e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(DepositCredited e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(DepositCleared e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(DepositReturned e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(FundsHeld e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(FundsReleased e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(CashWithdrawn e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(SettlementAmountCorrected e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(WithdrawalReturnCredited e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(SharesReserved e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(SharesReleased e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(TradeBooked e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(TradeSettled e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(TradeBookingReversed e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(UnknownReferenceIgnored e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }

    @MessageHandler
    void handle(AccountBooksClosed e, OrderedMessage m) {
        projection.on(messageHandle(), e, m.getOrder());
    }
}
