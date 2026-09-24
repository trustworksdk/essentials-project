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

package dk.trustworks.essentials.examples.perflab.harness;

import org.HdrHistogram.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Latency recorder that is free of <em>coordinated omission</em>.
 * <p>
 * Coordinated omission is the measurement error that appears when a load generator waits for
 * operation <em>n</em> to complete before starting operation <em>n+1</em>. Under saturation the
 * generator slows down in lock-step with the system under test, so the very requests that would
 * have recorded the worst latencies are never issued, and the histogram reports a system that
 * looks far healthier than it is.
 * <p>
 * The fix is to give every operation an <em>intended</em> start time, computed up-front from the
 * target rate and never adjusted for how long earlier operations took. Latency is then measured
 * from that intended start, so an operation delayed by a stalled predecessor carries the delay in
 * its own measurement.
 * <p>
 * Both measures are kept, because the difference between them <em>is</em> the coordinated omission
 * and is worth seeing:
 * <ul>
 *     <li><b>Response time</b> — {@code completed - intendedStart}. The honest number, and the one
 *         a caller experiences.</li>
 *     <li><b>Service time</b> — {@code completed - actualStart}. What a naive harness would have
 *         reported. When the two diverge, the system is not keeping up with the offered rate.</li>
 * </ul>
 * Recording is lock-free and safe from many threads.
 */
public final class LatencyRecorder {
    private final String              name;
    private final ConcurrentHistogram responseTime = new ConcurrentHistogram(3);
    private final ConcurrentHistogram serviceTime  = new ConcurrentHistogram(3);

    public LatencyRecorder(String name) {
        this.name = requireNonNull(name, "No name provided");
    }

    /**
     * Record one completed operation.
     *
     * @param intendedStartNanos when the operation <em>should</em> have started, from the rate schedule
     * @param actualStartNanos   when it actually started
     * @param completedNanos     when it completed
     */
    public void record(long intendedStartNanos, long actualStartNanos, long completedNanos) {
        responseTime.recordValue(toMicros(completedNanos - intendedStartNanos));
        serviceTime.recordValue(toMicros(completedNanos - actualStartNanos));
    }

    /**
     * Record an operation for which there is no separate intended start — used for measurements
     * that are not driven by a rate schedule, such as how long a detected gap took to resolve.
     */
    public void recordDuration(long durationNanos) {
        responseTime.recordValue(toMicros(durationNanos));
        serviceTime.recordValue(toMicros(durationNanos));
    }

    /**
     * A negative or absurd duration means the clock moved, not that the operation was instant.
     * Clamp rather than throw — losing one sample is better than failing a 30 minute run.
     */
    private static long toMicros(long nanos) {
        return nanos <= 0 ? 0 : nanos / 1_000L;
    }

    public Summary responseTimeSummary() {
        return Summary.of(name + ".responseTime", responseTime);
    }

    public Summary serviceTimeSummary() {
        return Summary.of(name + ".serviceTime", serviceTime);
    }

    public long count() {
        return responseTime.getTotalCount();
    }

    /**
     * Percentile summary in microseconds. Serialized straight to the run's result JSON.
     */
    public record Summary(String name,
                          long count,
                          long minMicros,
                          double meanMicros,
                          long p50Micros,
                          long p90Micros,
                          long p99Micros,
                          long p999Micros,
                          long maxMicros) {

        static Summary of(String name, Histogram histogram) {
            var copy = histogram.copy();
            return new Summary(name,
                               copy.getTotalCount(),
                               copy.getTotalCount() == 0 ? 0 : copy.getMinValue(),
                               copy.getMean(),
                               copy.getValueAtPercentile(50.0),
                               copy.getValueAtPercentile(90.0),
                               copy.getValueAtPercentile(99.0),
                               copy.getValueAtPercentile(99.9),
                               copy.getMaxValue());
        }
    }
}
