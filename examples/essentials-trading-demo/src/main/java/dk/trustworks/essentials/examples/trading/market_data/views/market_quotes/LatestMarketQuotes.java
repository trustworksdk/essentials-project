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

import dk.trustworks.essentials.examples.trading.market_data.types.*;
import dk.trustworks.essentials.types.Money;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The latest quote per instrument, in memory (D19: a price is a high-rate feed, not a decision, and not an aggregate).
 * Nothing is persisted: after a restart the feed's next message, or its snapshot, rebuilds it.
 * <p>
 * Implements {@code market_data}'s published read seam {@link MarketQuotes}, which answers only from fresh quotes.
 */
@Service
public class LatestMarketQuotes implements MarketQuotes {
    private final Map<InstrumentId, MarketQuote> quotes = new ConcurrentHashMap<>();
    private final Duration                       staleAfter;
    private final Clock                          clock;

    @Autowired
    public LatestMarketQuotes(MarketQuoteFeed feed, MarketQuotesProperties properties) {
        this(feed, properties, Clock.systemUTC());
    }

    LatestMarketQuotes(MarketQuoteFeed feed, MarketQuotesProperties properties, Clock clock) {
        requireNonNull(feed, "No feed provided");
        this.staleAfter = requireNonNull(properties, "No properties provided").getStaleAfter();
        this.clock = requireNonNull(clock, "No clock provided");
        feed.subscribe(this::received);
    }

    /**
     * An older quote ({@link MarketQuote#isOlderThan}) never replaces a newer one. The same one does: it is the feed confirming a quote that
     * has not changed, which keeps a quiet book fresh ({@link ExchangeMarketQuoteFeed} re-reads the exchange's snapshot).
     */
    void received(MarketQuote quote) {
        quotes.merge(quote.instrumentId(), quote, (current, offered) -> offered.isOlderThan(current) ? current : offered);
    }

    @Override
    public Optional<Money> bestAsk(InstrumentId instrumentId) {
        return fresh(instrumentId).map(MarketQuote::ask);
    }

    @Override
    public Optional<Money> bestBid(InstrumentId instrumentId) {
        return fresh(instrumentId).map(MarketQuote::bid);
    }

    public List<MarketQuote> all() {
        return quotes.values().stream()
                     .map(quote -> isStale(quote) ? quote.asStale() : quote)
                     .sorted(Comparator.comparing(quote -> quote.instrumentId().toString()))
                     .toList();
    }

    public Optional<MarketQuote> find(InstrumentId instrumentId) {
        return Optional.ofNullable(quotes.get(requireNonNull(instrumentId, "No instrumentId provided")))
                       .map(quote -> isStale(quote) ? quote.asStale() : quote);
    }

    private Optional<MarketQuote> fresh(InstrumentId instrumentId) {
        return find(instrumentId).filter(quote -> !quote.stale());
    }

    private boolean isStale(MarketQuote quote) {
        return quote.receivedAt().plus(staleAfter).isBefore(clock.instant());
    }
}
