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

package dk.trustworks.essentials.examples.trading.orders.external_systems.exchange;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.processor.*;
import dk.trustworks.essentials.components.foundation.messaging.*;
import dk.trustworks.essentials.examples.trading.orders.aggregates.Orders;
import dk.trustworks.essentials.examples.trading.orders.events.*;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_exchange_answer.RecordExchangeAnswer;
import org.slf4j.*;
import org.springframework.stereotype.Service;

import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Sends placed orders and cancel requests to the exchange. Both handlers are {@link UnitOfWorkMode#NONE}: the exchange
 * call blocks with no database connection held (the shape of {@code market_data.risk_approve_instrument}), and the
 * answer is queued in {@link ExchangeReports} in a short unit of work of its own.
 * <p>
 * A redelivered {@link OrderPlaced} submits again; the gateway answers a known {@code orderId} as it did the first time.
 */
@Service
public class ExchangeOrderRouter extends EventProcessor {
    private static final Logger log = LoggerFactory.getLogger(ExchangeOrderRouter.class);

    private final ExchangeGateway gateway;
    private final ExchangeReports reports;
    private final Orders          orders;

    public ExchangeOrderRouter(EventProcessorDependencies dependencies, ExchangeGateway gateway, ExchangeReports reports, Orders orders) {
        super(dependencies);
        this.gateway = requireNonNull(gateway, "No gateway provided");
        this.reports = requireNonNull(reports, "No reports provided");
        this.orders = requireNonNull(orders, "No orders provided");
    }

    @Override
    public String getProcessorName() {
        return "ExchangeOrderRouter";
    }

    @Override
    protected List<AggregateType> reactsToEventsRelatedToAggregateTypes() {
        return List.of(Orders.AGGREGATE_TYPE);
    }

    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(OrderPlaced e) {
        var result = gateway.submit(OrderSubmission.of(e));
        if (result.isRejected()) {
            log.debug("Exchange rejected order '{}': {}", e.orderId(), result.rejectReason());
            reports.received(RecordExchangeAnswer.rejected(e.orderId(), result.rejectReason()));
        } else {
            reports.received(RecordExchangeAnswer.submitted(e.orderId(), result.exchangeOrderId()));
        }
    }

    /**
     * Cancelling at the exchange needs the exchange's id, which the order learns when the submission's answer is
     * recorded. Until then the request is redelivered; an order that closed meanwhile (rejected, filled) is let go.
     */
    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(OrderCancelRequested e) {
        var state = withUnitOfWork(() -> orders.exchangeStateOf(e.orderId()))
                .orElseThrow(() -> new IllegalStateException("Order '" + e.orderId() + "' not found"));
        if (state.status().isTerminal()) {
            return;
        }
        var exchangeOrderId = state.exchangeOrderId()
                                   .orElseThrow(() -> new IllegalStateException("Order '" + e.orderId() + "' has no exchange id yet; cancel retried"));
        gateway.cancel(e.orderId(), exchangeOrderId);
    }
}
