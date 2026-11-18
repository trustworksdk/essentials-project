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
package dk.trustworks.essentials.examples.trading.orders.automations.apply_order_to_account;

import dk.trustworks.essentials.components.eventlog.integration.EventLogProcessorDependencies;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.transaction.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.components.foundation.messaging.eip.store_and_forward.Inboxes;
import dk.trustworks.essentials.examples.trading.orders.config.OrderForwardingProperties;
import dk.trustworks.essentials.examples.trading.orders.config.OrderProcessingProperties;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.config.*;
import org.springframework.stereotype.Service;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link OrderLedgerProcessor} on the event-log engine: a {@link TradingDemoEventLogInboxProcessor} that forwards the events
 * from the event log into its inbox and hands each one to {@link OrderLedgerProcessor}, each handler in its own unit of work as
 * declared. Same processor name, inbox and handlers as {@link OrderLedgerClassicReaction}.
 */
@EventLogEngine
@Service
public class OrderLedgerEventLogReaction extends TradingDemoEventLogInboxProcessor {
    private final OrderLedgerProcessor      ledger;
    private final OrderProcessingProperties consumers;

    public OrderLedgerEventLogReaction(EventLogProcessorDependencies dependencies,
                                       Inboxes inboxes,
                                       EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                       OrderLedgerProcessor ledger, OrderProcessingProperties consumers) {
        super(OrderLedgerProcessor.PROCESSOR_NAME, OrderLedgerProcessor.AGGREGATE_TYPES, dependencies, inboxes, unitOfWorkFactory);
        this.ledger = requireNonNull(ledger, "No ledger provided");
        this.consumers = requireNonNull(consumers, "No consumers provided");
    }

    /**
     * One order's events stay in order on any number of threads, so a release still never overtakes its bookings.
     */
    @Override
    protected int numberOfParallelInboxMessageConsumers() {
        return consumers.getLedger();
    }

    @MessageHandler
    void on(OrderFilled e) {
        ledger.on(e);
    }

    @MessageHandler
    void on(OrderClosed e) {
        ledger.on(e);
    }
}
