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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.math.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A stand-in for the exchange's market-data stream, for runs without the exchange-simulator: every {@code tick-interval}, each configured instrument's mid
 * moves by a random step of at most {@code volatility}, and a quote {@code half-spread-bps} either side of it is
 * published. The first quote is published at start, so a fresh application can price a market order at once.
 * <p>
 * Per instance, like the real stream: two instances quote independently, and that is fine — a quote sizes a hold,
 * the fill price comes from the exchange.
 * <p>
 * Active when no exchange-simulator is configured; with one, {@link ExchangeMarketQuoteFeed} reads the exchange's own
 * stream instead.
 */
@Component
@ConditionalOnExpression("'${trading-demo.simulators.exchange.base-url:}'.isBlank()")
public class SyntheticMarketQuoteFeed implements MarketQuoteFeed, SmartLifecycle {
    private static final BigDecimal BPS = new BigDecimal("10000");

    private final MarketQuotesProperties.Synthetic   settings;
    private final List<Consumer<MarketQuote>>        listeners = new CopyOnWriteArrayList<>();
    private final Map<InstrumentId, BigDecimal>      mids      = new ConcurrentHashMap<>();
    private final Map<InstrumentId, Long>            sequences = new ConcurrentHashMap<>();
    private volatile ScheduledExecutorService        scheduler;

    public SyntheticMarketQuoteFeed(MarketQuotesProperties properties) {
        this.settings = requireNonNull(properties, "No properties provided").getSynthetic();
        settings.getInstruments().forEach((id, mid) -> mids.put(InstrumentId.of(id), mid));
    }

    @Override
    public void subscribe(Consumer<MarketQuote> listener) {
        listeners.add(requireNonNull(listener, "No listener provided"));
    }

    @Override
    public void start() {
        if (!settings.isEnabled()) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("synthetic-market-quotes").daemon().factory());
        var interval = settings.getTickInterval().toMillis();
        scheduler.scheduleAtFixedRate(this::tick, 0, interval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        var running = scheduler;
        scheduler = null;
        if (running != null) {
            running.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }

    private void tick() {
        var currency = CurrencyCode.of(settings.getCurrency());
        var random   = ThreadLocalRandom.current();
        mids.replaceAll((id, mid) -> {
            var step = settings.getVolatility().multiply(BigDecimal.valueOf(random.nextDouble(-1, 1)));
            return mid.multiply(BigDecimal.ONE.add(step)).max(new BigDecimal("0.01"));
        });
        var now = Instant.now();
        mids.forEach((id, mid) -> {
            var halfSpread = mid.multiply(BigDecimal.valueOf(settings.getHalfSpreadBps())).divide(BPS, 6, RoundingMode.HALF_UP);
            var bid        = mid.subtract(halfSpread).setScale(2, RoundingMode.DOWN);
            var ask        = mid.add(halfSpread).setScale(2, RoundingMode.UP);
            var quote = new MarketQuote(id,
                                        Money.of(bid, currency),
                                        Money.of(ask, currency),
                                        Money.of(mid.setScale(2, RoundingMode.HALF_UP), currency),
                                        sequences.merge(id, 1L, Long::sum),
                                        now,
                                        false);
            listeners.forEach(listener -> listener.accept(quote));
        });
    }
}
