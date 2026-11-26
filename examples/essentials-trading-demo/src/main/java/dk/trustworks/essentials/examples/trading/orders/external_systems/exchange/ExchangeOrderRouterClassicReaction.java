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

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.EventProcessorDependencies;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.examples.trading.orders.config.OrderForwardingProperties;
import dk.trustworks.essentials.examples.trading.orders.config.OrderProcessingProperties;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.config.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Feeds {@link ExchangeOrderRouter} on the classic engine: an {@code EventProcessor} that forwards the events into its inbox and
 * hands each one to {@link ExchangeOrderRouter}, each handler in its own unit of work as declared. Same processor name, inbox and
 * handlers as {@link ExchangeOrderRouterEventLogReaction}.
 */
@ClassicEngine
@Service
public class ExchangeOrderRouterClassicReaction extends TradingDemoEventProcessor {
    private final ExchangeOrderRouter       router;
    private final OrderProcessingProperties consumers;
    private final OrderForwardingProperties forwarding;

    public ExchangeOrderRouterClassicReaction(EventProcessorDependencies dependencies, ExchangeOrderRouter router, OrderProcessingProperties consumers, OrderForwardingProperties forwarding) {
        super(dependencies);
        this.router = requireNonNull(router, "No router provided");
        this.consumers = requireNonNull(consumers, "No consumers provided");
        this.forwarding = requireNonNull(forwarding, "No forwarding provided");
    }

    @Override
    public String getProcessorName() {
        return ExchangeOrderRouter.PROCESSOR_NAME;
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return ExchangeOrderRouter.AGGREGATE_TYPES;
    }

    /**
     * One order's events stay in order on any number of threads, so its cancel still follows its submission.
     */
    @Override
    protected int getNumberOfParallelInboxMessageConsumers() {
        return consumers.getExchangeRouter();
    }

    @Override
    protected int getForwardBatchSize() {
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
