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

package dk.trustworks.essentials.examples.trading.market_data.views.market_quotes;

import dk.trustworks.essentials.examples.trading.integration.*;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.ApiClient;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.api.*;
import dk.trustworks.essentials.examples.trading.integration.exchange_simulator.client.model.*;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * {@link ExchangeMarketQuoteFeed} against the real exchange-simulator as the demo runs it: its liquidity provider
 * quoting every listed instrument around a synthetic price.
 * <p>
 * Skipped unless the {@code exchange-simulator:dev} image exists locally (see {@link SimulatorImages}).
 */
@Testcontainers
@EnabledIf("dk.trustworks.essentials.examples.trading.integration.SimulatorImages#exchangeAvailable")
class ExchangeMarketQuoteFeedIT {
    private static final Duration     TIMEOUT = Duration.ofSeconds(30);
    private static final InstrumentId ACME    = InstrumentId.of("ACME");

    @Container
    static final GenericContainer<?> exchange = SimulatorImages.exchangeAsTheDemoRunsIt();

    private ExchangeMarketQuoteFeed feed;
    private LatestMarketQuotes      quotes;

    @BeforeEach
    void setUp() {
        var simulators = new SimulatorProperties();
        simulators.getExchange().setBaseUrl(exchangeUrl());
        var properties = new MarketQuotesProperties();
        properties.setStaleAfter(Duration.ofSeconds(2));
        feed = new ExchangeMarketQuoteFeed(simulators, properties, JsonMapper.builder().build());
        quotes = new LatestMarketQuotes(feed, properties);
        feed.start();
    }

    @AfterEach
    void tearDown() {
        feed.stop();
    }

    @Test
    void every_listed_instrument_is_quoted_with_a_bid_below_its_ask() {
        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(quotes.all()).hasSize(11)
                                                                            .allSatisfy(quote -> {
                                                                                assertThat(quote.stale()).isFalse();
                                                                                assertThat(quote.bid().compareTo(quote.ask())).isNegative();
                                                                            }));
        assertThat(quotes.bestAsk(ACME)).isPresent();
    }

    @Test
    void quotes_stay_fresh_beyond_the_staleness_window_while_the_exchange_answers() throws InterruptedException {
        await().atMost(TIMEOUT).until(() -> quotes.bestAsk(ACME).isPresent());

        Thread.sleep(Duration.ofSeconds(5)); // more than twice stale-after (2s)

        assertThat(quotes.all()).hasSize(11).noneMatch(MarketQuote::stale);
    }

    @Test
    void a_trade_at_the_exchange_arrives_as_the_last_price() {
        await().atMost(TIMEOUT).until(() -> quotes.bestAsk(ACME).isPresent());
        var api = new ApiClient(RestClient.builder().build());
        api.setBasePath(exchangeUrl());
        try {
            new ParticipantsApi(api).registerParticipant(new Participant().id("FEED-IT").name("Feed IT"));
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict alreadyRegistered) {
            // registered by an earlier test method in this class
        }

        new OrdersApi(api).submitOrder(new OrderRequest().clientOrderId(UUID.randomUUID().toString())
                                                         .participantId("FEED-IT")
                                                         .instrumentId("ACME")
                                                         .side(Side.BUY)
                                                         .type(OrderType.MARKET)
                                                         .quantity(1));

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(quotes.find(ACME)).hasValueSatisfying(quote -> assertThat(quote.last()).isNotNull()));
    }

    private static String exchangeUrl() {
        return "http://" + exchange.getHost() + ":" + exchange.getMappedPort(8080);
    }
}
