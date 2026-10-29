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

import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.*;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class LatestMarketQuotesTest {
    private static final InstrumentId ACME = InstrumentId.of("ACME");
    private static final Instant      NOW  = Instant.parse("2030-01-07T15:00:00Z");

    private static MarketQuote quote(String ask, long sequence, Instant receivedAt) {
        return new MarketQuote(ACME, Money.of("99.95", CurrencyCode.USD), Money.of(ask, CurrencyCode.USD), null, 0, sequence, receivedAt, false);
    }

    private static LatestMarketQuotes quotes(Clock clock) {
        var properties = new MarketQuotesProperties();
        properties.setStaleAfter(Duration.ofSeconds(10));
        MarketQuoteFeed feed = (Consumer<MarketQuote> listener) -> { };
        return new LatestMarketQuotes(feed, properties, clock);
    }

    @Test
    void an_older_sequence_never_replaces_a_newer_quote() {
        var quotes = quotes(Clock.fixed(NOW, ZoneOffset.UTC));
        quotes.received(quote("100.10", 2, NOW));
        quotes.received(quote("100.05", 1, NOW));

        assertThat(quotes.bestAsk(ACME)).contains(Money.of("100.10", CurrencyCode.USD));
    }

    @Test
    void a_stale_quote_is_shown_as_stale_and_answers_nothing() {
        var quotes = quotes(Clock.fixed(NOW, ZoneOffset.UTC));
        quotes.received(quote("100.10", 1, NOW.minusSeconds(11)));

        assertThat(quotes.bestAsk(ACME)).isEmpty();
        assertThat(quotes.bestBid(ACME)).isEmpty();
        assertThat(quotes.find(ACME)).hasValueSatisfying(quote -> assertThat(quote.stale()).isTrue());
        assertThat(quotes.bestAsk(InstrumentId.of("UNQUOTED"))).isEmpty();
    }

    @Test
    void the_same_sequence_again_keeps_an_unchanged_quote_fresh() {
        var quotes = quotes(Clock.fixed(NOW, ZoneOffset.UTC));
        quotes.received(quote("100.10", 3, NOW.minusSeconds(11)));
        quotes.received(quote("100.10", 3, NOW)); // the exchange's snapshot confirming it

        assertThat(quotes.bestAsk(ACME)).contains(Money.of("100.10", CurrencyCode.USD));
    }

    @Test
    void a_new_epoch_replaces_the_old_one_whatever_its_sequence() {
        // The exchange numbers mdSequence from 1 every time it starts; the feed starts an epoch per subscription
        var quotes = quotes(Clock.fixed(NOW, ZoneOffset.UTC));
        quotes.received(new MarketQuote(ACME, Money.of("99.95", CurrencyCode.USD), Money.of("100.10", CurrencyCode.USD), null, 1, 500_000, NOW, false));
        quotes.received(new MarketQuote(ACME, Money.of("99.95", CurrencyCode.USD), Money.of("100.20", CurrencyCode.USD), null, 2, 3, NOW, false));
        quotes.received(new MarketQuote(ACME, Money.of("99.95", CurrencyCode.USD), Money.of("100.30", CurrencyCode.USD), null, 1, 500_001, NOW, false));

        assertThat(quotes.bestAsk(ACME)).contains(Money.of("100.20", CurrencyCode.USD));
    }
}
