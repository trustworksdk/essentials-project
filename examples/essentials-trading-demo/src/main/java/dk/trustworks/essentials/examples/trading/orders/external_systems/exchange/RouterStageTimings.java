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

import dk.trustworks.essentials.examples.trading.orders.types.OrderId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A measurement aid, off by default ({@code trading-demo.orders.router-stage-timings.enabled}): when
 * {@link ExchangeOrderRouter} started handling an order's {@code OrderPlaced}, and when the exchange answered it. With
 * the {@code OrderPlaced} and {@code OrderSubmitted} timestamps they split placed → submitted into the router inbox's
 * queue wait, the exchange call, and the {@code ExchangeReports} inbox's queue wait before the answer is recorded (the
 * load driver's report, {@code _demo_harness/LoadRunReport}).
 * <p>
 * In memory and per instance, so nothing is written to the database in the flow it measures; on more than one instance
 * each holds only the orders it routed. Bounded by {@code max-orders}; past it new orders are counted, not kept. The
 * first handling of an order is kept: a redelivery would otherwise move its start past the queue wait it measures.
 */
@Component
public class RouterStageTimings {
    public record Timing(Instant handlingStarted, Instant exchangeAnswered) {
    }

    private final boolean                            enabled;
    private final int                                maxOrders;
    private final ConcurrentHashMap<String, Timing>  timings = new ConcurrentHashMap<>();
    private final AtomicLong                         dropped = new AtomicLong();

    public RouterStageTimings(@Value("${trading-demo.orders.router-stage-timings.enabled:false}") boolean enabled,
                              @Value("${trading-demo.orders.router-stage-timings.max-orders:2000000}") int maxOrders) {
        this.enabled = enabled;
        this.maxOrders = maxOrders;
    }

    public boolean isEnabled() {
        return enabled;
    }

    void record(OrderId orderId, Instant handlingStarted, Instant exchangeAnswered) {
        if (!enabled) {
            return;
        }
        if (timings.size() >= maxOrders) {
            dropped.incrementAndGet();
            return;
        }
        timings.putIfAbsent(orderId.toString(), new Timing(handlingStarted, exchangeAnswered));
    }

    /**
     * @return the timings of the orders whose id starts with {@code orderIdPrefix}
     */
    public Map<String, Timing> timingsFor(String orderIdPrefix) {
        var result = new HashMap<String, Timing>();
        timings.forEach((orderId, timing) -> {
            if (orderId.startsWith(orderIdPrefix)) {
                result.put(orderId, timing);
            }
        });
        return result;
    }

    public long dropped() {
        return dropped.get();
    }
}
