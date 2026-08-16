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

package dk.trustworks.essentials.components.eventsourced.aggregates.closingbooks;

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.eventstream.AggregateType;
import dk.trustworks.essentials.shared.measurement.MeasurementTaker;
import io.micrometer.core.instrument.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * ClosingBooksManagementMeasurementSupport is a utility class responsible for
 * recording and managing metrics related to the closing books operation processing.
 * This includes timings for processing aggregate generations, manager poll cycles,
 * and batch size measurements, as well as tracking outcomes.
 * <p>
 * It utilizes an optional {@link MeterRegistry} for metrics registration and a
 * {@link MeasurementTaker} to facilitate metric contexts and recording.
 */
class ClosingBooksManagementMeasurementSupport {
    static final String METRIC_PREFIX = "essentials.aggregate_closing_books";

    private final MeasurementTaker                       measurementTaker;
    private final Optional<MeterRegistry>                meterRegistryOptional;
    private final Map<AggregateType, AtomicLong>         lastRolloverEpochMs = new ConcurrentHashMap<>();

    ClosingBooksManagementMeasurementSupport(Optional<MeterRegistry> meterRegistryOptional) {
        this.meterRegistryOptional = requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided");
        this.measurementTaker = MeasurementTaker.builder()
                                                .setMeterRegistry(meterRegistryOptional)
                                                .build();
    }

    <T> T recordLoadOpenGenerations(AggregateType aggregateType,
                                    int batchSize,
                                    Supplier<T> block) {
        return measurementTaker.context(METRIC_PREFIX + ".scan.load_open_generations")
                               .description("Time taken to load open aggregate generations for closing books scanning")
                               .tag("aggregate_type", aggregateType.toString())
                               .tag("batch_size", batchSize)
                               .record(block);
    }

    void recordLoadedBatchSize(AggregateType aggregateType,
                               int loadedCount) {
        meterRegistryOptional.ifPresent(meterRegistry -> DistributionSummary.builder(METRIC_PREFIX + ".scan.loaded_batch_size")
                                                                            .description("Number of open aggregate generations loaded in a closing books scan batch")
                                                                            .tag("aggregate_type", aggregateType.toString())
                                                                            .register(meterRegistry)
                                                                            .record(loadedCount));
    }

    void recordProcessGeneration(AggregateType aggregateType,
                                 long generation,
                                 Runnable block) {
        measurementTaker.context(METRIC_PREFIX + ".scan.process_generation")
                        .description("Time taken to process a single open aggregate generation during closing books scanning")
                        .tag("aggregate_type", aggregateType.toString())
                        .tag("generation", String.valueOf(generation))
                        .record(run(block));
    }

    void recordManagerPoll(AggregateType aggregateType,
                           Runnable block) {
        measurementTaker.context(METRIC_PREFIX + ".manager.poll")
                        .description("Time taken for a closing books manager poll cycle")
                        .tag("aggregate_type", aggregateType.toString())
                        .record(run(block));
    }

    void incrementManagerPollOutcome(AggregateType aggregateType,
                                     String outcome) {
        meterRegistryOptional.ifPresent(meterRegistry -> Counter.builder(METRIC_PREFIX + ".manager.poll.outcome")
                                                                .description("Outcome of a closing books manager poll cycle")
                                                                .tag("aggregate_type", aggregateType.toString())
                                                                .tag("outcome", outcome)
                                                                .register(meterRegistry)
                                                                .increment());
    }

    void incrementProcessOutcome(AggregateType aggregateType,
                                 String outcome) {
        meterRegistryOptional.ifPresent(meterRegistry -> Counter.builder(METRIC_PREFIX + ".scan.process_generation.outcome")
                                                                .description("Outcome of closing books scan processing for a single aggregate generation")
                                                                .tag("aggregate_type", aggregateType.toString())
                                                                .tag("outcome", outcome)
                                                                .register(meterRegistry)
                                                                .increment());
    }

    // ── Rollover metrics ───────────────────────────────────────────────────────────────
    // Everything above is recorded from the scheduled-scan path only (ClosingBooksManager /
    // DefaultClosingBooksScheduledScanProcessor). The meters below are recorded by
    // ClosingBooksCoordinator, which every trigger mode passes through - so ON_ACCESS and
    // EXPLICIT_COMMAND aggregates report something too, instead of nothing at all.

    <T> T recordRollover(AggregateType aggregateType,
                         Supplier<T> block) {
        return measurementTaker.context(METRIC_PREFIX + ".rollover")
                               .description("Time taken to close the current aggregate generation and open the next one")
                               .tag("aggregate_type", aggregateType.toString())
                               .record(block);
    }

    void incrementRolloverOutcome(AggregateType aggregateType,
                                  String outcome) {
        meterRegistryOptional.ifPresent(meterRegistry -> Counter.builder(METRIC_PREFIX + ".rollover.outcome")
                                                                .description("Outcome of a close-and-open-next-generation rollover")
                                                                .tag("aggregate_type", aggregateType.toString())
                                                                .tag("outcome", outcome)
                                                                .register(meterRegistry)
                                                                .increment());
    }

    void incrementGenerationsClosed(AggregateType aggregateType) {
        meterRegistryOptional.ifPresent(meterRegistry -> Counter.builder(METRIC_PREFIX + ".generations_closed")
                                                                .description("Number of aggregate generations that have been closed")
                                                                .tag("aggregate_type", aggregateType.toString())
                                                                .register(meterRegistry)
                                                                .increment());
    }

    void incrementGenerationsOpened(AggregateType aggregateType) {
        meterRegistryOptional.ifPresent(meterRegistry -> Counter.builder(METRIC_PREFIX + ".generations_opened")
                                                                .description("Number of aggregate generations that have been opened")
                                                                .tag("aggregate_type", aggregateType.toString())
                                                                .register(meterRegistry)
                                                                .increment());
    }

    void incrementPolicyDecision(AggregateType aggregateType,
                                 ClosingBooksDecision decision,
                                 ClosingBooksTriggerMode triggerMode) {
        meterRegistryOptional.ifPresent(meterRegistry -> Counter.builder(METRIC_PREFIX + ".policy.decision")
                                                                .description("Decisions returned by a closing books policy evaluation")
                                                                .tag("aggregate_type", aggregateType.toString())
                                                                .tag("decision", decision.name())
                                                                .tag("trigger_mode", triggerMode.name())
                                                                .register(meterRegistry)
                                                                .increment());
    }

    /**
     * Records when this aggregate type last rolled over, as a gauge, so a books-not-closing stall is
     * visible as a value that stops advancing. Registered once per aggregate type; the gauge reads
     * through to the {@link AtomicLong} held here, since Micrometer keeps only a weak reference to
     * the state object it is given.
     */
    void recordRolloverTimestamp(AggregateType aggregateType,
                                 long epochMs) {
        meterRegistryOptional.ifPresent(meterRegistry -> lastRolloverEpochMs.computeIfAbsent(aggregateType, type -> {
            var holder = new AtomicLong();
            Gauge.builder(METRIC_PREFIX + ".last_rollover_epoch_ms", holder, AtomicLong::doubleValue)
                 .description("Epoch milliseconds of the most recent close-and-open-next-generation rollover")
                 .tag("aggregate_type", type.toString())
                 .register(meterRegistry);
            return holder;
        }).set(epochMs));
    }

    private Supplier<Void> run(Runnable block) {
        requireNonNull(block, "No block provided");
        return () -> {
            block.run();
            return null;
        };
    }
}
