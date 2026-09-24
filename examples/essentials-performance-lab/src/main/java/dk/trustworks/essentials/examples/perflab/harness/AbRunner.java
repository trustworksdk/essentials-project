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

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.*;

import java.util.*;
import java.util.function.IntFunction;

import static dk.trustworks.essentials.shared.FailFast.*;

/**
 * Runs two or more arms interleaved and reports each one's median and interquartile range.
 * <p>
 * Two disciplines are built in rather than left to the caller, because both are easy to skip and
 * each invalidates the result on its own.
 * <p>
 * <b>Interleaving.</b> Arms run round-robin — A, B, A, B — instead of all of A then all of B. A
 * container that warms up, a host that thermally throttles, a page cache that fills, a neighbour
 * process that starts: every one of these drifts monotonically through a session, and a
 * block-structured run charges the whole drift to whichever arm ran second. Interleaving spreads it
 * evenly across both, so the comparison survives an environment that is not perfectly stable — which
 * is every environment.
 * <p>
 * <b>Median over interquartile range, never a single number.</b> One run is an anecdote. The IQR is
 * reported beside the median so that an improvement smaller than the spread can be recognised as
 * noise instead of being announced as a win.
 */
public final class AbRunner {
    private static final Logger log = LoggerFactory.getLogger(AbRunner.class);

    private final int repetitions;

    public AbRunner(int repetitions) {
        requireTrue(repetitions >= 3, "At least 3 repetitions are required for a median and an IQR to mean anything");
        this.repetitions = repetitions;
    }

    /**
     * Run every arm {@code repetitions} times, interleaved.
     *
     * @param arms arm name to a factory taking the repetition index and producing that run's result
     * @return every individual result, in execution order
     */
    public List<RunResult> run(Map<String, IntFunction<RunResult>> arms) {
        requireNonNull(arms, "No arms provided");
        requireTrue(!arms.isEmpty(), "At least one arm is required");

        var results = new ArrayList<RunResult>(arms.size() * repetitions);
        for (var repetition = 0; repetition < repetitions; repetition++) {
            for (var arm : arms.entrySet()) {
                log.info("Running arm '{}' repetition {}/{}", arm.getKey(), repetition + 1, repetitions);
                results.add(arm.getValue().apply(repetition));
            }
        }
        return results;
    }

    /**
     * Collapse individual results into one summary per arm.
     */
    public static List<ArmSummary> summarize(List<RunResult> results) {
        requireNonNull(results, "No results provided");
        var byArm = new LinkedHashMap<String, List<RunResult>>();
        results.forEach(result -> byArm.computeIfAbsent(result.arm(), key -> new ArrayList<>()).add(result));

        var summaries = new ArrayList<ArmSummary>(byArm.size());
        byArm.forEach((arm, armResults) -> {
            var throughput = armResults.stream().mapToDouble(RunResult::throughputPerSecond).toArray();
            var p50 = armResults.stream().mapToDouble(result -> primaryResponseTime(result, 50)).toArray();
            var p99 = armResults.stream().mapToDouble(result -> primaryResponseTime(result, 99)).toArray();
            var walPerOp = armResults.stream().mapToDouble(RunResult::walBytesPerOperation).toArray();
            summaries.add(new ArmSummary(arm,
                                         armResults.size(),
                                         Distribution.of(throughput),
                                         Distribution.of(p50),
                                         Distribution.of(p99),
                                         Distribution.of(walPerOp)));
        });
        return summaries;
    }

    private static double primaryResponseTime(RunResult result, int percentile) {
        return result.latencies().stream()
                     .filter(summary -> summary.name().endsWith(".responseTime"))
                     .findFirst()
                     .map(summary -> (double) (percentile == 50 ? summary.p50Micros() : summary.p99Micros()))
                     .orElse(0.0d);
    }

    /**
     * One arm's aggregated result. Every figure is a {@link Distribution} rather than a scalar, so a
     * reader cannot accidentally quote a median without its spread.
     */
    public record ArmSummary(String arm,
                             int repetitions,
                             Distribution throughputPerSecond,
                             Distribution responseTimeP50Micros,
                             Distribution responseTimeP99Micros,
                             Distribution walBytesPerOperation) {
    }

    /**
     * Median with the quartiles either side of it.
     * <p>
     * {@code overlaps} is the question a benchmark is actually asked — if two arms' interquartile
     * ranges overlap, the run has not distinguished them, whatever the medians say.
     */
    public record Distribution(double median, double q1, double q3, double min, double max) {

        /**
         * Annotated so it reaches the result JSON — Jackson serializes a record's components, not
         * its derived accessors, and the spread is the half of the result most likely to be dropped.
         */
        @JsonProperty
        public double interQuartileRange() {
            return q3 - q1;
        }

        public boolean overlaps(Distribution other) {
            requireNonNull(other, "No other distribution provided");
            return q1 <= other.q3 && other.q1 <= q3;
        }

        public static Distribution of(double[] values) {
            requireNonNull(values, "No values provided");
            requireTrue(values.length > 0, "At least one value is required");
            var sorted = values.clone();
            Arrays.sort(sorted);
            return new Distribution(percentile(sorted, 0.50d),
                                    percentile(sorted, 0.25d),
                                    percentile(sorted, 0.75d),
                                    sorted[0],
                                    sorted[sorted.length - 1]);
        }

        /**
         * Linear interpolation between the two nearest ranks. With three to five repetitions the
         * choice of percentile definition moves the number more than most people expect, so it is
         * stated here rather than left to whichever library happens to be on the classpath.
         */
        private static double percentile(double[] sorted, double fraction) {
            if (sorted.length == 1) {
                return sorted[0];
            }
            var position = fraction * (sorted.length - 1);
            var lower = (int) Math.floor(position);
            var upper = (int) Math.ceil(position);
            if (lower == upper) {
                return sorted[lower];
            }
            return sorted[lower] + (position - lower) * (sorted[upper] - sorted[lower]);
        }
    }
}
