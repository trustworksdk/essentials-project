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
import dk.trustworks.essentials.examples.trading.orders.types.OrderId;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_exchange_answer.RecordExchangeAnswer;
import org.slf4j.*;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Sends placed orders, cancel requests and end-of-day expiries to the exchange. The handlers are
 * {@link UnitOfWorkMode#NONE}: the exchange call blocks with no database connection held (the shape of
 * {@code market_data.risk_approve_instrument}), and the answer is queued in {@link ExchangeReports} in a short unit of
 * work of its own.
 * <p>
 * A redelivered {@link OrderPlaced} submits again; the gateway answers a known {@code orderId} as it did the first time.
 * An unreachable exchange is retried with a doubling backoff ({@link ExchangeSubmissionProperties}); when the attempts
 * run out the order is rejected {@value #EXCHANGE_UNAVAILABLE} and its hold released. Should the exchange have taken it
 * after all, its fills still arrive and are booked as facts (design Q-3).
 */
@Service
public class ExchangeOrderRouter extends EventProcessor {
    public static final String EXCHANGE_UNAVAILABLE = "EXCHANGE_UNAVAILABLE";

    private static final Logger log = LoggerFactory.getLogger(ExchangeOrderRouter.class);

    private final ExchangeGateway              gateway;
    private final ExchangeReports              reports;
    private final Orders                       orders;
    private final ExchangeSubmissionProperties submission;

    public ExchangeOrderRouter(EventProcessorDependencies dependencies,
                               ExchangeGateway gateway,
                               ExchangeReports reports,
                               Orders orders,
                               ExchangeSubmissionProperties submission) {
        super(dependencies);
        this.gateway = requireNonNull(gateway, "No gateway provided");
        this.reports = requireNonNull(reports, "No reports provided");
        this.orders = requireNonNull(orders, "No orders provided");
        this.submission = requireNonNull(submission, "No submission provided");
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
        var result = submit(OrderSubmission.of(e));
        if (result.isRejected()) {
            log.debug("Exchange rejected order '{}': {}", e.orderId(), result.rejectReason());
            reports.received(RecordExchangeAnswer.rejected(e.orderId(), result.rejectReason()));
        } else {
            reports.received(RecordExchangeAnswer.submitted(e.orderId(), result.exchangeOrderId()));
        }
    }

    /**
     * Cancels at the exchange by our order id, so a cancel requested right after the order was placed does not wait for
     * the submission's answer to be recorded. This inbox delivers one order's events in order, so the order was submitted
     * before its cancel request arrives here. An order that closed meanwhile (rejected, filled) is let go.
     */
    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(OrderCancelRequested e) {
        cancelAtExchange(e.orderId());
    }

    /**
     * The brokerage's end-of-day expiry of a {@code DAY} order is a cancel at the exchange; the order expires when the
     * exchange confirms it.
     */
    @MessageHandler(unitOfWork = UnitOfWorkMode.NONE)
    void on(OrderExpiryRequested e) {
        cancelAtExchange(e.orderId());
    }

    private void cancelAtExchange(OrderId orderId) {
        var status = withUnitOfWork(() -> orders.statusOf(orderId))
                .orElseThrow(() -> new IllegalStateException("Order '" + orderId + "' not found"));
        if (status.isTerminal()) {
            return;
        }
        gateway.cancel(orderId);
    }

    /**
     * Retries an unreachable exchange within this one delivery, then gives up with a rejection. Anything else the
     * gateway throws is left to the processor's redelivery.
     */
    private SubmissionResult submit(OrderSubmission order) {
        var backoff = submission.getInitialBackoff();
        for (var attempt = 1; ; attempt++) {
            try {
                return gateway.submit(order);
            } catch (ExchangeUnavailableException e) {
                if (attempt >= submission.getAttempts()) {
                    log.warn("Exchange unavailable for order '{}' after {} attempt(s); rejecting it {}: {}", order.orderId(), attempt, EXCHANGE_UNAVAILABLE, e.getMessage());
                    return SubmissionResult.rejected(EXCHANGE_UNAVAILABLE);
                }
                log.debug("Exchange unavailable for order '{}' (attempt {}), retrying in {}: {}", order.orderId(), attempt, backoff, e.getMessage());
                sleep(backoff);
                backoff = backoff.multipliedBy(2);
            }
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to resubmit to the exchange", e);
        }
    }
}
