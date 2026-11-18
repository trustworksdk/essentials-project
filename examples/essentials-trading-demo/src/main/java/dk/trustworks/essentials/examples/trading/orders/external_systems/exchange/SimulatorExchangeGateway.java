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

import dk.trustworks.essentials.examples.trading.accounts.types.*;
import dk.trustworks.essentials.examples.trading.integration.SimulatorProperties;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.api.*;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.examples.trading.orders.types.OrderType;
import dk.trustworks.essentials.examples.trading.orders.types.TimeInForce;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.OrderStatus;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_cancellation.RecordCancellation;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_exchange_answer.RecordExchangeAnswer;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_fill.RecordFill;
import dk.trustworks.essentials.types.Money;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

import java.math.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The brokerage's door to the exchange-simulator (design §4), through its generated client. Active when
 * {@code trading-demo.simulators.exchange.base-url} is set (the {@code simulators} profile); otherwise the
 * {@link StubExchangeGateway} stands in.
 * <p>
 * Every order is submitted as the firm's (D8), with our {@code OrderId} as its {@code clientOrderId}, which makes a
 * resubmission answer as the first submission did. Only the submission's outcome is returned to the router; the
 * acceptance, the fills, a market order's remainder and a confirmed cancel arrive through the exchange's webhooks and
 * event log ({@link ExchangeOrderEvents}), once each (Q-2 revised). With
 * {@code trading-demo.simulators.exchange.report-synchronous-answers=true} the answers the calls carry are also reported
 * at once through {@link ExchangeReports}, as the stub reports them: the acceptance, one fill per execution of a
 * marketable order with the exchange's trade and settlement dates (SR-29), the remainder of a market order the exchange
 * cancelled, and a cancellation the exchange confirmed; the webhook copies then deduplicate on the order.
 * <p>
 * Failure mapping: no answer, a timeout or {@code 503} is {@link ExchangeUnavailableException}, which the router retries
 * and finally rejects as {@code EXCHANGE_UNAVAILABLE}. An order the exchange refuses outright - {@code 400}, {@code 409}
 * (a {@code clientOrderId} reused with other terms), an unknown instrument, a price off the instrument's tick - is
 * rejected with the exchange's error code. An unknown participant is registered and the order submitted again.
 */
@Component
@ConditionalOnProperty(prefix = "trading-demo.simulators.exchange", name = "base-url")
public class SimulatorExchangeGateway implements ExchangeGateway {
    private static final Logger log = LoggerFactory.getLogger(SimulatorExchangeGateway.class);

    private final OrdersApi                         ordersApi;
    private final InstrumentsApi                    instrumentsApi;
    private final ParticipantsApi                   participantsApi;
    private final ExchangeReports                   reports;
    private final String                            participantId;
    private final String                            participantName;
    private final boolean                           reportSynchronousAnswers;
    private final Map<InstrumentId, Instrument>     instruments = new ConcurrentHashMap<>();

    public SimulatorExchangeGateway(ApiClient exchangeSimulatorApiClient, SimulatorProperties properties, ExchangeReports reports) {
        requireNonNull(exchangeSimulatorApiClient, "No exchangeSimulatorApiClient provided");
        requireNonNull(properties, "No properties provided");
        this.reports = requireNonNull(reports, "No reports provided");
        this.ordersApi = new OrdersApi(exchangeSimulatorApiClient);
        this.instrumentsApi = new InstrumentsApi(exchangeSimulatorApiClient);
        this.participantsApi = new ParticipantsApi(exchangeSimulatorApiClient);
        this.participantId = requireNonNull(properties.getExchange().getParticipantId(), "No trading-demo.simulators.exchange.participant-id provided");
        this.participantName = requireNonNull(properties.getExchange().getParticipantName(), "No trading-demo.simulators.exchange.participant-name provided");
        this.reportSynchronousAnswers = properties.getExchange().isReportSynchronousAnswers();
    }

    @Override
    public SubmissionResult submit(OrderSubmission submission) {
        requireNonNull(submission, "No submission provided");
        var instrument = instrument(submission.instrumentId());
        if (instrument.isEmpty()) {
            return SubmissionResult.rejected("UNKNOWN_INSTRUMENT");
        }
        OrderRequest request;
        try {
            request = orderRequest(submission, instrument.get());
        } catch (UnsupportedPriceException e) {
            log.debug("Order '{}' cannot be submitted: {}", submission.orderId(), e.getMessage());
            return SubmissionResult.rejected(e.reason);
        }

        OrderResult answer;
        try {
            answer = submitRegisteringTheFirmIfUnknown(request);
        } catch (RestClientResponseException e) {
            return SubmissionResult.rejected(rejectionReason(e));
        }
        return reportAnswer(submission.orderId(), answer);
    }

    @Override
    public void cancel(OrderId orderId) {
        requireNonNull(orderId, "No orderId provided");
        OrderResult answer;
        try {
            answer = call(() -> ordersApi.cancelOrderByClientOrderId(participantId, orderId.toString()));
        } catch (RestClientResponseException e) {
            // 404: the exchange does not know the order (reset since); 409: it is no longer open - filled, cancelled or
            // expired, and that is reported through the exchange's events. Nothing to cancel either way.
            if (e.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND) || e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                log.debug("Exchange did not cancel order '{}': {} {}", orderId, e.getStatusCode().value(), errorCode(e).orElse(""));
                return;
            }
            throw e;
        }
        if (reportSynchronousAnswers && answer.getOrder().getStatus() == OrderStatus.CANCELLED) {
            reports.received(new RecordCancellation(orderId, "REQUESTED"));
        }
    }

    private OrderResult submitRegisteringTheFirmIfUnknown(OrderRequest request) {
        try {
            return call(() -> ordersApi.submitOrder(request));
        } catch (RestClientResponseException e) {
            if (!"unknown_participant".equals(errorCode(e).orElse(null))) {
                throw e;
            }
            registerTheFirm();
            return call(() -> ordersApi.submitOrder(request));
        }
    }

    /**
     * Registers the firm as a participant. A {@code 409} means it is registered already - by another instance, say.
     */
    private void registerTheFirm() {
        try {
            call(() -> participantsApi.registerParticipant(new Participant().id(participantId).name(participantName)));
            log.info("Registered participant '{}' at the exchange", participantId);
        } catch (RestClientResponseException e) {
            if (!e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw e;
            }
        }
    }

    private SubmissionResult reportAnswer(OrderId orderId, OrderResult answer) {
        var order = answer.getOrder();
        if (order.getStatus() == OrderStatus.REJECTED) {
            return SubmissionResult.rejected(order.getRejectReason() != null ? order.getRejectReason().getValue() : "REJECTED");
        }
        var taken = SubmissionResult.taken(ExchangeOrderId.of(order.getOrderId().toString()));
        if (!reportSynchronousAnswers) {
            return taken;
        }
        reports.received(RecordExchangeAnswer.accepted(orderId));
        for (var execution : answer.getExecutions()) {
            reports.received(new RecordFill(orderId,
                                            ExecutionId.of(execution.getExecutionId().toString()),
                                            Quantity.of(execution.getQuantity()),
                                            Money.of(execution.getPrice(), execution.getCurrency()),
                                            execution.getTradeDate(),
                                            execution.getIntendedSettlementDate()));
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            // A market order is IOC at the exchange: what it could not fill at once is cancelled
            reports.received(new RecordCancellation(orderId, "IOC_REMAINDER"));
        }
        return taken;
    }

    private OrderRequest orderRequest(OrderSubmission submission, Instrument instrument) {
        var request = new OrderRequest().clientOrderId(submission.orderId().toString())
                                        .participantId(participantId)
                                        .instrumentId(instrument.getId())
                                        .side(submission.side() == TradeSide.BUY ? Side.BUY : Side.SELL)
                                        .quantity(Math.toIntExact(submission.quantity().value()));
        if (submission.type() == OrderType.MARKET) {
            // Always IOC at the exchange; naming DAY or GTC on a market order is a 400
            return request.type(dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.OrderType.MARKET);
        }
        return request.type(dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.OrderType.LIMIT)
                      .priceTicks(priceTicks(submission.limitPrice(), instrument))
                      .timeInForce(submission.timeInForce() == TimeInForce.GTC
                                   ? dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.TimeInForce.GTC
                                   : dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.TimeInForce.DAY);
    }

    /**
     * The price in the instrument's ticks: minor units ({@code currencyScale} decimals) divided by the tick size in minor
     * units. A price that is not a whole number of ticks, or in another currency, cannot be sent.
     */
    private static int priceTicks(Money price, Instrument instrument) {
        if (!price.getCurrency().toString().equals(instrument.getCurrency())) {
            throw new UnsupportedPriceException("CURRENCY_MISMATCH", "Price in " + price.getCurrency() + ", instrument '" + instrument.getId() + "' trades in " + instrument.getCurrency());
        }
        try {
            var minorUnits = price.getAmount().value().movePointRight(instrument.getCurrencyScale()).toBigIntegerExact();
            BigInteger[] ticks = minorUnits.divideAndRemainder(BigInteger.valueOf(instrument.getTickSizeMinor()));
            if (ticks[1].signum() != 0) {
                throw new ArithmeticException("not a multiple of the tick size");
            }
            return ticks[0].intValueExact();
        } catch (ArithmeticException e) {
            throw new UnsupportedPriceException("INVALID_PRICE_TICK", "Price " + price + " is not a whole number of instrument '" + instrument.getId() + "' ticks of " + instrument.getTickSize() + ": " + e.getMessage());
        }
    }

    /**
     * The exchange's reference data for an instrument, cached. Listed instruments never change their tick size, scale or
     * currency, so the cache needs no invalidation; a miss reloads the list, which also finds a newly listed one.
     */
    private Optional<Instrument> instrument(InstrumentId instrumentId) {
        var cached = instruments.get(instrumentId);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            call(() -> instrumentsApi.listInstruments(null, null, null, null))
                    .forEach(listed -> instruments.put(InstrumentId.of(listed.getId()), listed));
        } catch (RestClientResponseException e) {
            throw unavailableIfServerSide(e);
        }
        return Optional.ofNullable(instruments.get(instrumentId));
    }

    /**
     * Runs a call, turning no answer and a {@code 5xx} into {@link ExchangeUnavailableException}; the other refusals are
     * left to the caller.
     */
    private static <T> T call(java.util.function.Supplier<T> exchangeCall) {
        try {
            return exchangeCall.get();
        } catch (ResourceAccessException e) {
            throw new ExchangeUnavailableException("Exchange not reachable: " + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            throw unavailableIfServerSide(e);
        }
    }

    private static RuntimeException unavailableIfServerSide(RestClientResponseException e) {
        if (e.getStatusCode().is5xxServerError()) {
            return new ExchangeUnavailableException("Exchange unavailable: " + e.getStatusCode().value() + " " + errorCode(e).orElse(""), e);
        }
        return e;
    }

    private static String rejectionReason(RestClientResponseException e) {
        var reason = errorCode(e).map(code -> code.toUpperCase(Locale.ROOT)).orElse("EXCHANGE_REFUSED_" + e.getStatusCode().value());
        log.warn("Exchange refused an order: {} {} - {}", e.getStatusCode().value(), reason, e.getResponseBodyAsString());
        return reason;
    }

    private static Optional<String> errorCode(RestClientResponseException e) {
        try {
            return Optional.ofNullable(e.getResponseBodyAs(dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.Error.class))
                           .map(dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.Error::getError);
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    private static final class UnsupportedPriceException extends RuntimeException {
        private final String reason;

        UnsupportedPriceException(String reason, String message) {
            super(message);
            this.reason = reason;
        }
    }
}
