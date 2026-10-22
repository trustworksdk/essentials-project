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
import dk.trustworks.essentials.examples.trading.integration.*;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.ApiClient;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.examples.trading.orders.types.*;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_cancellation.RecordCancellation;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_exchange_answer.RecordExchangeAnswer;
import dk.trustworks.essentials.examples.trading.orders.use_cases.record_fill.RecordFill;
import dk.trustworks.essentials.types.Money;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link SimulatorExchangeGateway} against the real exchange-simulator, configured as the demo runs it: its
 * instruments, synthetic prices and the liquidity provider quoting them. Checks what the adapter makes of the
 * exchange's synchronous answers - the reports it queues and the submission result - and how it maps the exchange's
 * refusals and its absence.
 * <p>
 * Skipped unless the {@code exchange-simulator:dev} image exists locally (see {@link SimulatorImages}).
 */
@Testcontainers
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#exchangeAvailable")
class SimulatorExchangeGatewayIT {
    private static final InstrumentId AAPL = InstrumentId.of("AAPL");

    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchangeAsTheDemoRunsIt();

    private final List<Object>       reported = new CopyOnWriteArrayList<>();
    private SimulatorExchangeGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = gatewayTo("http://" + exchange.getHost() + ":" + exchange.getMappedPort(8080));
    }

    @Test
    void a_market_order_is_filled_by_the_liquidity_provider_and_reported_at_once() {
        var orderId = OrderId.random();

        var result = gateway.submit(new OrderSubmission(orderId, AAPL, TradeSide.BUY, OrderType.MARKET, Quantity.of(10), null, TimeInForce.DAY));

        assertThat(result.isRejected()).isFalse();
        assertThat((Object) result.exchangeOrderId()).isNotNull();
        assertThat(reported).contains(RecordExchangeAnswer.accepted(orderId));
        var fills = reported.stream().filter(RecordFill.class::isInstance).map(RecordFill.class::cast).toList();
        assertThat(fills).isNotEmpty();
        assertThat(fills).allSatisfy(fill -> {
            assertThat((Object) fill.orderId()).isEqualTo(orderId);
            assertThat(fill.price().getCurrency().toString()).isEqualTo("USD");
            assertThat(fill.settlementDate()).isAfter(fill.tradeDate());
        });
        assertThat(fills.stream().mapToLong(fill -> fill.quantity().value()).sum()).isEqualTo(10);
        assertThat(reported).noneMatch(RecordCancellation.class::isInstance);
    }

    @Test
    void a_resubmission_answers_as_the_first_submission_did() {
        var submission = new OrderSubmission(OrderId.random(), AAPL, TradeSide.BUY, OrderType.LIMIT, Quantity.of(5), usd("1.00"), TimeInForce.GTC);

        var first  = gateway.submit(submission);
        var second = gateway.submit(submission);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void a_resting_order_is_cancelled_by_our_order_id_and_a_second_cancel_is_let_go() {
        var orderId = OrderId.random();
        gateway.submit(new OrderSubmission(orderId, AAPL, TradeSide.BUY, OrderType.LIMIT, Quantity.of(5), usd("1.00"), TimeInForce.GTC));
        assertThat(reported).noneMatch(RecordFill.class::isInstance);

        gateway.cancel(orderId);
        gateway.cancel(orderId);

        assertThat(reported.stream().filter(RecordCancellation.class::isInstance)).containsExactly(new RecordCancellation(orderId, "REQUESTED"));
    }

    @Test
    void an_instrument_the_exchange_does_not_list_is_rejected() {
        var result = gateway.submit(new OrderSubmission(OrderId.random(), InstrumentId.of("NOT-LISTED"), TradeSide.BUY, OrderType.LIMIT, Quantity.of(1), usd("10.00"), TimeInForce.DAY));

        assertThat(result).isEqualTo(SubmissionResult.rejected("UNKNOWN_INSTRUMENT"));
        assertThat(reported).isEmpty();
    }

    @Test
    void a_price_that_is_not_a_whole_number_of_ticks_is_rejected_before_it_is_sent() {
        var result = gateway.submit(new OrderSubmission(OrderId.random(), AAPL, TradeSide.BUY, OrderType.LIMIT, Quantity.of(1), usd("10.005"), TimeInForce.DAY));

        assertThat(result).isEqualTo(SubmissionResult.rejected("INVALID_PRICE_TICK"));
    }

    @Test
    void an_unreachable_exchange_is_reported_as_unavailable() {
        var unreachable = gatewayTo("http://localhost:1");

        assertThatThrownBy(() -> unreachable.submit(new OrderSubmission(OrderId.random(), AAPL, TradeSide.BUY, OrderType.MARKET, Quantity.of(1), null, TimeInForce.DAY)))
                .isInstanceOf(ExchangeUnavailableException.class);
        assertThatThrownBy(() -> unreachable.cancel(OrderId.random()))
                .isInstanceOf(ExchangeUnavailableException.class);
    }

    private SimulatorExchangeGateway gatewayTo(String baseUrl) {
        var properties = new SimulatorProperties();
        properties.getExchange().setBaseUrl(baseUrl);
        var apiClient = new ApiClient(RestClient.builder().build());
        apiClient.setBasePath(baseUrl);
        var reports = mock(ExchangeReports.class);
        doAnswer(invocation -> reported.add(invocation.getArgument(0))).when(reports).received(any());
        return new SimulatorExchangeGateway(apiClient, properties, reports);
    }

    private static Money usd(String amount) {
        return Money.of(amount, "USD");
    }
}
