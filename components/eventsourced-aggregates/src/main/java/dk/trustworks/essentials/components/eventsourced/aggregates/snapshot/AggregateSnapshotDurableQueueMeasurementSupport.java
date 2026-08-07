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

package dk.trustworks.essentials.components.eventsourced.aggregates.snapshot;

import dk.trustworks.essentials.shared.measurement.MeasurementTaker;
import io.micrometer.core.instrument.*;

import java.util.*;
import java.util.function.Supplier;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * Provides support for capturing measurements related to durable queue operations used in
 * durable aggregate snapshot processing. This class offers functionality to track metrics such
 * as enqueueing times, batch locking times, queue depth, and job processing outcomes.
 * <p>
 * The metrics are recorded and published using the Micrometer framework if a {@code MeterRegistry}
 * is available.
 */
class AggregateSnapshotDurableQueueMeasurementSupport {
    static final String METRIC_PREFIX = "essentials.aggregate_snapshot.durable_queue";

    private final MeasurementTaker        measurementTaker;
    private final Optional<MeterRegistry> meterRegistryOptional;

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    AggregateSnapshotDurableQueueMeasurementSupport(Optional<MeterRegistry> meterRegistryOptional) {
        this.meterRegistryOptional = requireNonNull(meterRegistryOptional, "No meterRegistryOptional provided");
        this.measurementTaker = MeasurementTaker.builder()
                                                .withOptionalMicrometerMeasurementRecorder(meterRegistryOptional)
                                                .build();
    }

    void recordEnqueue(AggregateSnapshotJob job, Runnable block) {
        measurementTaker.context(METRIC_PREFIX + ".enqueue")
                        .description("Time taken to enqueue a durable aggregate snapshot job")
                        .tag("aggregate_type", job.aggregateType())
                        .tag("aggregate_impl_type", job.aggregateImplementationType())
                        .record(run(block));
    }

    <T> T recordLockNextBatch(int batchSize, Supplier<T> block) {
        return measurementTaker.context(METRIC_PREFIX + ".lock_next_batch")
                               .description("Time taken to lock the next durable aggregate snapshot job batch")
                               .tag("batch_size", batchSize)
                               .record(block);
    }

    void recordLockedBatchSize(int lockedJobsCount) {
        meterRegistryOptional.ifPresent(meterRegistry -> DistributionSummary.builder(METRIC_PREFIX + ".locked_batch_size")
                                                                            .description("Number of durable aggregate snapshot jobs locked in a polling batch")
                                                                            .register(meterRegistry)
                                                                            .record(lockedJobsCount));
    }

    void registerQueueDepthGauge(String status, Supplier<Number> supplier) {
        requireNonNull(status, "No status provided");
        requireNonNull(supplier, "No supplier provided");
        // Gauge.builder(name, stateObject, valueFunction) holds the state object weakly, and the suppliers registered
        // here are lambdas nothing else keeps alive, so all queue-depth gauges started reporting NaN as soon as a GC
        // ran. The Supplier overload holds it strongly.
        meterRegistryOptional.ifPresent(meterRegistry -> Gauge.builder(METRIC_PREFIX + ".queue_depth", supplier)
                                                              .description("Current durable aggregate snapshot queue depth by job status")
                                                              .tag("status", status)
                                                              .register(meterRegistry));
    }

    void recordProcessJob(AggregateSnapshotJob job, Runnable block) {
        measurementTaker.context(METRIC_PREFIX + ".process_job")
                        .description("Time taken to process a durable aggregate snapshot job")
                        .tag("aggregate_type", job.aggregateType())
                        .tag("aggregate_impl_type", job.aggregateImplementationType())
                        .record(run(block));
    }

    void incrementProcessOutcome(AggregateSnapshotJob job, String outcome) {
        meterRegistryOptional.ifPresent(meterRegistry -> Counter.builder(METRIC_PREFIX + ".process_job.outcome")
                                                                .description("Outcome of durable aggregate snapshot job processing")
                                                                .tag("aggregate_type", job.aggregateType())
                                                                .tag("aggregate_impl_type", job.aggregateImplementationType())
                                                                .tag("outcome", outcome)
                                                                .register(meterRegistry)
                                                                .increment());
    }

    private Supplier<Void> run(Runnable block) {
        requireNonNull(block, "No block provided");
        return () -> {
            block.run();
            return null;
        };
    }
}
