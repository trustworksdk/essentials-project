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

package dk.trustworks.essentials.examples.trading._demo_harness;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts what the EF-2 measurement processors handle, per processor: how many events, and when the first and the
 * latest arrived. {@code /api/admin/ef2/noop-stats} reports the rate. See {@code NoOpAccountsViewProcessor}.
 */
@Component
public class NoOpProcessorStats {
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    void handled(String processor) {
        counters.computeIfAbsent(processor, name -> new Counter()).handled();
    }

    public Map<String, Map<String, Object>> snapshot() {
        var result = new java.util.TreeMap<String, Map<String, Object>>();
        counters.forEach((name, counter) -> result.put(name, counter.snapshot()));
        return result;
    }

    public void reset() {
        counters.clear();
    }

    private static final class Counter {
        private final AtomicLong count = new AtomicLong();
        private volatile long firstNanos;
        private volatile long lastNanos;

        void handled() {
            var now = System.nanoTime();
            if (count.getAndIncrement() == 0) {
                firstNanos = now;
            }
            lastNanos = now;
        }

        Map<String, Object> snapshot() {
            var handled = count.get();
            var seconds = (lastNanos - firstNanos) / 1e9;
            return Map.of("handled", handled,
                          "seconds", Math.round(seconds * 10) / 10.0,
                          "eventsPerSecond", seconds > 0 ? Math.round(handled / seconds) : 0);
        }
    }
}
