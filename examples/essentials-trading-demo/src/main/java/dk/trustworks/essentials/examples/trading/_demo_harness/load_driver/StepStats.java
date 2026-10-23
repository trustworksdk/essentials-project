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
package dk.trustworks.essentials.examples.trading._demo_harness.load_driver;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * The driver's own view of its requests: per step, latency and outcome (HTTP status, or {@code IO} for a request that
 * got no answer). What the brokerage recorded is the server's report ({@code /api/admin/load-report}).
 */
final class StepStats {
    record Summary(long count, double p50Ms, double p99Ms, double maxMs, Map<String, Long> outcomes) {
    }

    private final ConcurrentMap<String, ConcurrentLinkedQueue<Long>>             latencies = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ConcurrentMap<String, LongAdder>> outcomes  = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, LongAdder>                         counters  = new ConcurrentHashMap<>();

    void record(String step, long nanos, String outcome) {
        latencies.computeIfAbsent(step, k -> new ConcurrentLinkedQueue<>()).add(nanos);
        outcomes.computeIfAbsent(step, k -> new ConcurrentHashMap<>()).computeIfAbsent(outcome, k -> new LongAdder()).increment();
    }

    void count(String counter) {
        counters.computeIfAbsent(counter, k -> new LongAdder()).increment();
    }

    long counter(String counter) {
        var adder = counters.get(counter);
        return adder == null ? 0 : adder.sum();
    }

    Map<String, Long> counters() {
        var result = new TreeMap<String, Long>();
        counters.forEach((k, v) -> result.put(k, v.sum()));
        return result;
    }

    Map<String, Summary> summaries() {
        var result = new TreeMap<String, Summary>();
        latencies.forEach((step, queue) -> {
            var sorted = queue.stream().mapToLong(Long::longValue).sorted().toArray();
            var byOutcome = new TreeMap<String, Long>();
            outcomes.getOrDefault(step, new ConcurrentHashMap<>()).forEach((k, v) -> byOutcome.put(k, v.sum()));
            result.put(step, new Summary(sorted.length, millis(sorted, 0.50), millis(sorted, 0.99), sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e6, byOutcome));
        });
        return result;
    }

    private static double millis(long[] sorted, double percentile) {
        if (sorted.length == 0) {
            return 0;
        }
        var index = (int) Math.min(sorted.length - 1, Math.ceil(percentile * sorted.length) - 1);
        return Math.round(sorted[Math.max(0, index)] / 1e4) / 100.0;
    }
}
