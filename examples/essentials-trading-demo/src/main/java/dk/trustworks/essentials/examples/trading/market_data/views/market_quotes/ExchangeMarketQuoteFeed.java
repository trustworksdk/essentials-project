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

import dk.trustworks.essentials.examples.trading.integration.SimulatorProperties;
import dk.trustworks.essentials.examples.trading.market_data.types.InstrumentId;
import dk.trustworks.essentials.types.Money;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * The exchange-simulator's market-data stream (SR-12, SR-13, D14): {@code GET /api/marketdata/stream}, server-sent
 * events, read on a thread of its own. Active under the {@code simulators} profile, in place of the
 * {@link SyntheticMarketQuoteFeed}.
 * <p>
 * Every {@code quote} and {@code trade} message publishes the instrument's top of book with its last trade, under the
 * message's {@code mdSequence}; {@code reference} messages are not quoted. A side with nothing resting ({@code null})
 * publishes nothing: there is no price to quote, and the last quote goes stale instead of being trusted.
 * <p>
 * The stream is live only. As the exchange prescribes, the snapshot ({@code GET /api/marketdata}) is read after
 * subscribing, and every reconnect subscribes and reads it again. The exchange sends a quote only when the top of book
 * <em>changes</em>, so the snapshot is also read every half of {@code trading-demo.market-data.stale-after}: a quiet
 * book stays fresh as long as the exchange keeps confirming it, and goes stale only when the exchange stops answering.
 * {@link LatestMarketQuotes} lets a message with the same {@code mdSequence} refresh a quote for that reason.
 */
@Component
@ConditionalOnProperty(prefix = "trading-demo.simulators.exchange", name = "base-url")
public class ExchangeMarketQuoteFeed implements MarketQuoteFeed, SmartLifecycle {
    private static final Logger   log               = LoggerFactory.getLogger(ExchangeMarketQuoteFeed.class);
    private static final Duration MAX_RECONNECT_GAP = Duration.ofSeconds(30);

    private final URI                             exchange;
    private final Duration                        snapshotInterval;
    private final ObjectMapper                    objectMapper;
    private final HttpClient                      http;
    private final List<Consumer<MarketQuote>>     listeners = new CopyOnWriteArrayList<>();
    private final Map<InstrumentId, Top>          tops      = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService     snapshots;
    private volatile Thread                       reader;
    private volatile boolean                      running;

    /**
     * One instrument's latest known top of book and last trade, and the {@code mdSequence} they are as of.
     */
    private record Top(Money bid, Money ask, Money last, long mdSequence) {
    }

    public ExchangeMarketQuoteFeed(SimulatorProperties simulators, MarketQuotesProperties properties, ObjectMapper objectMapper) {
        requireNonNull(simulators, "No simulators provided");
        this.exchange = URI.create(requireNonNull(simulators.getExchange().getBaseUrl(), "No exchange base-url provided").replaceAll("/+$", ""));
        this.snapshotInterval = requireNonNull(properties, "No properties provided").getStaleAfter().dividedBy(2);
        this.objectMapper = requireNonNull(objectMapper, "No objectMapper provided");
        this.http = HttpClient.newBuilder().connectTimeout(simulators.getConnectTimeout()).build();
    }

    @Override
    public void subscribe(Consumer<MarketQuote> listener) {
        listeners.add(requireNonNull(listener, "No listener provided"));
    }

    @Override
    public void start() {
        running = true;
        reader = Thread.ofPlatform().name("exchange-market-data").daemon().start(this::readStreamUntilStopped);
        snapshots = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("exchange-market-data-snapshot").daemon().factory());
        snapshots.scheduleWithFixedDelay(this::readSnapshot, snapshotInterval.toMillis(), snapshotInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        running = false;
        var currentReader = reader;
        if (currentReader != null) {
            currentReader.interrupt();
        }
        var currentSnapshots = snapshots;
        if (currentSnapshots != null) {
            currentSnapshots.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void readStreamUntilStopped() {
        var gap = Duration.ofMillis(500);
        while (running) {
            try {
                readStream();
                gap = Duration.ofMillis(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Exchange market-data stream failed, reconnecting in {}: {}", gap, e.toString());
            }
            if (!running) {
                return;
            }
            try {
                Thread.sleep(gap);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            gap = gap.multipliedBy(2).compareTo(MAX_RECONNECT_GAP) > 0 ? MAX_RECONNECT_GAP : gap.multipliedBy(2);
        }
    }

    /**
     * One connection: subscribe, then resynchronise from the snapshot, then follow the stream until it ends.
     */
    private void readStream() throws Exception {
        var request = HttpRequest.newBuilder(exchange.resolve("/api/marketdata/stream"))
                                 .header("Accept", "text/event-stream")
                                 .GET()
                                 .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IllegalStateException("market-data stream answered " + response.statusCode());
        }
        log.info("Subscribed to the exchange's market-data stream at {}", exchange);
        readSnapshot();
        try (var lines = response.body()) {
            var event = new StringBuilder();
            var data  = new StringBuilder();
            for (var iterator = lines.iterator(); iterator.hasNext() && running; ) {
                var line = iterator.next();
                if (line.isEmpty()) {
                    dispatch(event.toString(), data.toString());
                    event.setLength(0);
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event.append(line.substring(6).strip());
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).strip());
                }
                // id: is the mdSequence, which the data carries as well; comments (":") are keep-alives
            }
        }
        log.info("The exchange's market-data stream ended");
    }

    private void dispatch(String event, String data) {
        if (data.isEmpty()) {
            return;
        }
        try {
            var json = objectMapper.readTree(data);
            switch (event) {
                case "quote" -> onQuote(json);
                case "trade" -> onTrade(json);
                default -> {
                    // reference: the price source's price, not the market's
                }
            }
        } catch (RuntimeException e) {
            log.warn("Skipping an unreadable '{}' market-data message: {} ({})", event, data, e.toString());
        }
    }

    private void onQuote(JsonNode quote) {
        var instrumentId = InstrumentId.of(quote.get("instrumentId").asString());
        var sequence     = quote.get("mdSequence").asLong();
        tops.compute(instrumentId, (id, current) -> newer(current, sequence)
                                                    ? new Top(price(quote.get("bestBid")), price(quote.get("bestAsk")), current != null ? current.last() : null, sequence)
                                                    : current);
        publish(instrumentId, sequence);
    }

    private void onTrade(JsonNode trade) {
        var instrumentId = InstrumentId.of(trade.get("instrumentId").asString());
        var sequence     = trade.get("mdSequence").asLong();
        var last         = usd(trade.get("price").asString());
        tops.compute(instrumentId, (id, current) -> newer(current, sequence)
                                                    ? new Top(current != null ? current.bid() : null, current != null ? current.ask() : null, last, sequence)
                                                    : current);
        publish(instrumentId, sequence);
    }

    /**
     * The snapshot of every instrument: resynchronises after a (re)subscription, and confirms a quiet book.
     */
    private void readSnapshot() {
        try {
            var response = http.send(HttpRequest.newBuilder(exchange.resolve("/api/marketdata")).timeout(snapshotInterval).GET().build(),
                                     HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                log.debug("Exchange market-data snapshot answered {}", response.statusCode());
                return;
            }
            for (var instrument : objectMapper.readTree(response.body())) {
                var instrumentId = InstrumentId.of(instrument.get("instrumentId").asString());
                var sequence     = instrument.get("mdSequence").asLong();
                var lastTrade    = instrument.get("lastTrade");
                var last         = lastTrade == null || lastTrade.isNull() ? null : usd(lastTrade.get("price").asString());
                tops.compute(instrumentId, (id, current) -> current == null || sequence >= current.mdSequence()
                                                            ? new Top(price(instrument.get("bestBid")), price(instrument.get("bestAsk")), last, sequence)
                                                            : current);
                publish(instrumentId, sequence);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Exchange market-data snapshot failed: {}", e.toString());
        }
    }

    private void publish(InstrumentId instrumentId, long sequence) {
        var top = tops.get(instrumentId);
        if (top == null || top.mdSequence() != sequence || top.bid() == null || top.ask() == null) {
            return;
        }
        var quote = new MarketQuote(instrumentId, top.bid(), top.ask(), top.last(), sequence, Instant.now(), false);
        listeners.forEach(listener -> listener.accept(quote));
    }

    private static boolean newer(Top current, long sequence) {
        return current == null || sequence > current.mdSequence();
    }

    private static Money price(JsonNode side) {
        return side == null || side.isNull() ? null : usd(side.get("price").asString());
    }

    /**
     * Every instrument the exchange lists trades in USD (D3).
     */
    private static Money usd(String amount) {
        return Money.of(amount, "USD");
    }
}
