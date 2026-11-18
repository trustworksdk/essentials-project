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
package dk.trustworks.essentials.examples.trading.orders.external_systems.exchange;

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
 * Feeds {@link ExchangeOrderRouter} on the event-log engine: a {@link TradingDemoEventLogInboxProcessor} that forwards the events
 * from the event log into its inbox and hands each one to {@link ExchangeOrderRouter}, each handler in its own unit of work as
 * declared. Same processor name, inbox and handlers as {@link ExchangeOrderRouterClassicReaction}.
 */
@EventLogEngine
@Service
public class ExchangeOrderRouterEventLogReaction extends TradingDemoEventLogInboxProcessor {
    private final ExchangeOrderRouter       router;
    private final OrderProcessingProperties consumers;
    private final OrderForwardingProperties forwarding;

    public ExchangeOrderRouterEventLogReaction(EventLogProcessorDependencies dependencies,
                                               Inboxes inboxes,
                                               EventStoreUnitOfWorkFactory<? extends EventStoreUnitOfWork> unitOfWorkFactory,
                                               ExchangeOrderRouter router, OrderProcessingProperties consumers,
                                               OrderForwardingProperties forwarding) {
        super(ExchangeOrderRouter.PROCESSOR_NAME, ExchangeOrderRouter.AGGREGATE_TYPES, dependencies, inboxes, unitOfWorkFactory);
        this.router = requireNonNull(router, "No router provided");
        this.consumers = requireNonNull(consumers, "No consumers provided");
        this.forwarding = requireNonNull(forwarding, "No forwarding provided");
    }

    /**
     * One order's events stay in order on any number of threads, so its cancel still follows its submission.
     */
    @Override
    protected int numberOfParallelInboxMessageConsumers() {
        return consumers.getExchangeRouter();
    }

    /**
     * As many events per forward transaction as the classic reaction's {@code getForwardBatchSize()}:
     * {@code trading-demo.orders.forwarding.batch-size}.
     */
    @Override
    protected int forwardPageSize() {
        return forwarding.getBatchSize();
    }

    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(OrderPlaced e) {
        router.on(e);
    }

    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(OrderCancelRequested e) {
        router.on(e);
    }

    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(OrderExpiryRequested e) {
        router.on(e);
    }
}
